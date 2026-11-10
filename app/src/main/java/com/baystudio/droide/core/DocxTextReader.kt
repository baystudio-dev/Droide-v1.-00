package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader
import org.xml.sax.ext.DefaultHandler2

/** Local, read-only DOCX main-body text. No extraction, relationship fetching, or rendering. */
object DocxTextReader {
    const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
    const val MAX_ENTRIES = 2048
    const val MAX_CENTRAL_DIRECTORY_BYTES = 1024L * 1024
    const val MAX_XML_BYTES = 8L * 1024 * 1024
    const val MAX_OUTPUT_CHARS = 500_000
    const val MAX_DEPTH = 128
    const val MAX_COMPRESSION_RATIO = 1000L
    const val MAX_XML_EVENTS = 2_000_000
    const val MAX_ATTRIBUTES_PER_ELEMENT = 128
    const val MAX_ATTRIBUTE_CHARS = 16_384
    private const val MAX_METADATA_BYTES = 256L * 1024
    private const val MAIN = "word/document.xml"
    private const val TYPES = "[Content_Types].xml"
    private const val RELS = "_rels/.rels"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main"
    private const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    private const val STRICT_OFFICE_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument"
    private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private const val LEXICAL = "http://xml.org/sax/properties/lexical-handler"
    private const val SAX_FEATURE = "http://xml.org/sax/features/"
    private val IGNORED_BODY_ELEMENTS = setOf("drawing", "pict", "object", "txbxContent", "del", "moveFrom")
    private const val NOTICE = "Main-body text only. Layout, images, headers, footers, notes, text boxes, " +
        "equations and embedded objects are omitted; table cells use tabs."

    data class Result(val text: String, val truncated: Boolean, val notice: String)
    enum class Reason { UNSUPPORTED, CORRUPT, LIMIT_EXCEEDED, UNSAFE_XML, SECURITY_UNAVAILABLE }
    class ReadException(val reason: Reason, message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /** Call on a worker thread with a stable private snapshot. Cancellation exceptions propagate. */
    fun read(file: File, checkCancelled: () -> Unit = {}): Result {
        checkCancelled()
        if (!file.isFile) fail(Reason.CORRUPT, "Document is not a readable file.")
        if (file.length() > MAX_ARCHIVE_BYTES) fail(Reason.LIMIT_EXCEEDED, "DOCX exceeds the 32 MiB preview limit.")
        file.inputStream().use { input ->
            val signature = ByteArray(8)
            val count = input.read(signature)
            checkCancelled()
            if (count >= 8 && signature.contentEquals(byteArrayOf(
                    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))) {
                fail(Reason.UNSUPPORTED, "Legacy DOC and encrypted Office documents cannot be previewed.")
            }
            if (count < 4 || signature[0] != 0x50.toByte() || signature[1] != 0x4B.toByte() ||
                signature[2] != 0x03.toByte() || signature[3] != 0x04.toByte()) {
                fail(Reason.CORRUPT, "This is not a supported DOCX ZIP document.")
            }
        }
        try {
            preflightZip(file, checkCancelled)
            ZipFile(file).use { zip ->
                val entries = HashMap<String, ZipEntry>()
                val names = HashSet<String>()
                val enumeration = zip.entries()
                var count = 0
                while (enumeration.hasMoreElements()) {
                    checkCancelled()
                    if (++count > MAX_ENTRIES) fail(Reason.LIMIT_EXCEEDED, "DOCX has more than 2,048 ZIP entries.")
                    val entry = enumeration.nextElement()
                    validateName(entry.name)

                    val key = entry.name.removeSuffix("/").lowercase(Locale.ROOT)
                    if (!names.add(key)) fail(Reason.CORRUPT, "DOCX contains duplicate or ambiguous ZIP entries.")
                    if (key.endsWith("/vbaproject.bin") || key.endsWith("/vbadata.xml")) {
                        fail(Reason.UNSUPPORTED, "Macro-enabled Office documents cannot be previewed.")
                    }
                    entries[entry.name] = entry
                }
                val budget = ByteBudget()
                val events = EventBudget()
                val types = TypesHandler(checkCancelled, events)
                parse(zip, required(entries, TYPES), budget, MAX_METADATA_BYTES, types, checkCancelled)
                if (!types.mainFound) fail(Reason.UNSUPPORTED, "Only standard DOCX documents are supported.")
                val relationships = RelationshipsHandler(checkCancelled, events)
                parse(zip, required(entries, RELS), budget, MAX_METADATA_BYTES, relationships, checkCancelled)
                if (!relationships.mainFound) fail(Reason.CORRUPT, "DOCX has no main document relationship.")
                val body = BodyHandler(checkCancelled, events)
                var truncated = false
                try {
                    parse(zip, required(entries, MAIN), budget, MAX_XML_BYTES, body, checkCancelled)
                } catch (_: PreviewFull) {
                    truncated = true
                }
                checkCancelled()
                val text = body.output.toString()
                val notice = if (truncated) {
                    "$NOTICE Preview stopped at the 500,000-character limit; the remainder was not validated."
                } else if (text.isBlank()) {
                    "$NOTICE No main-body text was found."
                } else NOTICE
                return Result(text, truncated, notice)
            }
        } catch (e: ReadException) {
            throw e
        } catch (e: SAXException) {
            throw ReadException(Reason.CORRUPT, "DOCX contains invalid XML.", e)
        } catch (e: IOException) {
            throw ReadException(Reason.CORRUPT, "DOCX is corrupt, encrypted, or unreadable.", e)
        }
    }

    private fun preflightZip(file: File, checkCancelled: () -> Unit) {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length > MAX_ARCHIVE_BYTES) fail(Reason.LIMIT_EXCEEDED, "DOCX exceeds the 32 MiB preview limit.")
            if (length < 22) fail(Reason.CORRUPT, "DOCX ZIP directory is missing.")
            val tail = ByteArray(minOf(length, 65_557L).toInt())
            input.seek(length - tail.size); input.readFully(tail); checkCancelled()
            var end = -1
            for (i in tail.size - 22 downTo 0) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.size) {
                    end = i; break
                }
            }
            if (end < 0) fail(Reason.CORRUPT, "DOCX ZIP directory is invalid.")
            val count = u16(tail, end + 10)
            val directorySize = u32(tail, end + 12)
            val directoryOffset = u32(tail, end + 16)
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0 ||
                u16(tail, end + 8) != count || count == 0xffff ||
                directorySize == 0xffffffffL || directoryOffset == 0xffffffffL) {
                fail(Reason.UNSUPPORTED, "Multi-disk and ZIP64 DOCX archives are not supported.")
            }
            if (count > MAX_ENTRIES || directorySize > MAX_CENTRAL_DIRECTORY_BYTES) {
                fail(Reason.LIMIT_EXCEEDED, "DOCX ZIP directory exceeds the preview limit.")
            }
            val directoryEnd = length - tail.size + end
            if (directoryOffset + directorySize != directoryEnd || directorySize < 46L * count) {
                fail(Reason.CORRUPT, "DOCX ZIP directory bounds are invalid.")
            }
            val names = HashSet<String>()
            val ranges = ArrayList<Pair<Long, Long>>()
            val header = ByteArray(46)
            var offset = directoryOffset
            var actualCount = 0
            while (offset < directoryEnd) {
                checkCancelled()
                if (++actualCount > MAX_ENTRIES) fail(Reason.LIMIT_EXCEEDED, "DOCX has more than 2,048 ZIP entries.")
                if (directoryEnd - offset < header.size) fail(Reason.CORRUPT, "DOCX ZIP directory is truncated.")
                input.seek(offset); input.readFully(header)
                if (u32(header, 0) != 0x02014b50L) fail(Reason.CORRUPT, "DOCX ZIP directory record is invalid.")
                val flags = u16(header, 8)
                val method = u16(header, 10)
                val compressed = u32(header, 20)
                val expanded = u32(header, 24)
                val nameLength = u16(header, 28)
                val extraLength = u16(header, 30)
                val commentLength = u16(header, 32)
                val localOffset = u32(header, 42)
                if (u16(header, 34) != 0 || compressed == 0xffffffffL || expanded == 0xffffffffL || localOffset == 0xffffffffL) {
                    fail(Reason.UNSUPPORTED, "Multi-disk and ZIP64 DOCX archives are not supported.")
                }
                if (flags and 0x2041 != 0 || (method != 0 && method != 8)) {
                    fail(Reason.UNSUPPORTED, "Encrypted or unsupported ZIP compression cannot be previewed.")
                }
                val next = offset + 46 + nameLength + extraLength + commentLength
                if (nameLength == 0 || nameLength > 1024 || next > directoryEnd) {
                    fail(Reason.CORRUPT, "DOCX ZIP entry bounds are invalid.")
                }
                val rawName = ByteArray(nameLength); input.readFully(rawName)
                val name = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawName)).toString()
                } catch (e: java.nio.charset.CharacterCodingException) {
                    throw ReadException(Reason.CORRUPT, "DOCX ZIP part name is not valid UTF-8.", e)
                }
                validateName(name)
                if (!names.add(name.removeSuffix("/").lowercase(Locale.ROOT))) {
                    fail(Reason.CORRUPT, "DOCX contains duplicate or ambiguous ZIP entries.")
                }
                val extra = ByteArray(extraLength); input.readFully(extra)
                var e = 0
                while (e < extra.size) {
                    if (extra.size - e < 4 || e + 4 + u16(extra, e + 2) > extra.size) {
                        fail(Reason.CORRUPT, "DOCX ZIP extra fields are invalid.")
                    }
                    if (u16(extra, e) == 1) fail(Reason.UNSUPPORTED, "ZIP64 DOCX archives are not supported.")
                    e += 4 + u16(extra, e + 2)
                }
                // Check local headers and payload bounds without decompressing any entry.
                if (localOffset + 30 > directoryOffset) fail(Reason.CORRUPT, "DOCX ZIP local header overlaps its directory.")
                input.seek(localOffset)
                val local = ByteArray(30); input.readFully(local)
                val localNameLength = u16(local, 26)
                val dataEnd = localOffset + 30 + localNameLength + u16(local, 28) + compressed
                if (u32(local, 0) != 0x04034b50L || u16(local, 6) != flags || u16(local, 8) != method ||
                    localNameLength != nameLength || dataEnd > directoryOffset) {
                    fail(Reason.CORRUPT, "DOCX ZIP local entry bounds are invalid.")
                }
                val localName = ByteArray(localNameLength); input.readFully(localName)
                if (!rawName.contentEquals(localName)) fail(Reason.CORRUPT, "DOCX ZIP part names disagree.")
                ranges.add(localOffset to dataEnd)
                offset = next
            }
            if (actualCount != count) fail(Reason.CORRUPT, "DOCX ZIP entry count is invalid.")
            var previousEnd = 0L
            for ((start, stop) in ranges.sortedBy { it.first }) {
                checkCancelled()
                if (start < previousEnd) fail(Reason.CORRUPT, "DOCX ZIP entries overlap.")
                previousEnd = stop
            }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun fail(reason: Reason, message: String): Nothing = throw ReadException(reason, message)

    private fun validateName(name: String) {
        val path = name.removeSuffix("/")
        if (path.isEmpty() || name.length > 1024 || name.startsWith('/') ||
            name.any { it == '\\' || it == ':' || it == '%' || it.code < 32 || it.code == 127 } ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            fail(Reason.CORRUPT, "DOCX contains an unsafe or unsupported part name.")
        }
    }

    private fun required(entries: Map<String, ZipEntry>, name: String): ZipEntry =
        entries[name]?.takeUnless { it.isDirectory }
            ?: fail(Reason.CORRUPT, "DOCX is missing a required package part.")

    private class ByteBudget(var used: Long = 0)
    private class EventBudget(var used: Int = 0)

    /** Bounds actual inflated bytes, not just attacker-controlled ZIP size metadata. */
    private class BoundedEntry(
        private val input: InputStream,
        private val entry: ZipEntry,
        private val budget: ByteBudget,
        private val limit: Long,
        private val checkCancelled: () -> Unit,
    ) : InputStream() {
        private var count = 0L
        private val crc = CRC32()
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            if (length == 0) return 0
            val remaining = minOf(limit - count, MAX_XML_BYTES - budget.used)
            val n = input.read(bytes, offset, minOf(length.toLong(), 8192L, remaining + 1).toInt())
            checkCancelled()
            if (n < 0) {
                if (entry.size != count || entry.crc != crc.value) {
                    fail(Reason.CORRUPT, "DOCX part size or checksum is invalid.")
                }
            } else {
                count += n
                budget.used += n
                if (count > limit || budget.used > MAX_XML_BYTES ||
                    count > maxOf(1L, entry.compressedSize) * MAX_COMPRESSION_RATIO) {
                    fail(Reason.LIMIT_EXCEEDED, "DOCX XML exceeds the preview size limit.")
                }
                crc.update(bytes, offset, n)
            }
            return n
        }
        // The owning use block closes the real stream. SAX implementations may close this wrapper.
        override fun close() = Unit
    }

    private fun parse(
        zip: ZipFile, entry: ZipEntry, budget: ByteBudget, limit: Long,
        handler: SecureHandler, checkCancelled: () -> Unit,
    ) {

        if (entry.compressedSize < 0 || entry.compressedSize > MAX_ARCHIVE_BYTES ||
            entry.size > limit || entry.size > MAX_XML_BYTES - budget.used ||
            entry.size > maxOf(1L, entry.compressedSize) * MAX_COMPRESSION_RATIO) {
            fail(Reason.LIMIT_EXCEEDED, "DOCX XML exceeds the preview size limit.")
        }
        val reader = try {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
                .newSAXParser().xmlReader
        } catch (e: Exception) {
            throw ReadException(Reason.SECURITY_UNAVAILABLE, "A secure XML parser is unavailable.", e)
        }
        secureReader(reader, handler)
        zip.getInputStream(entry).use { input ->
            val bounded = BoundedEntry(input, entry, budget, limit, checkCancelled)
            reader.parse(InputSource(bounded)) // No systemId: never interpret a URL or file path as XML input. Verify actual size and CRC even
 // if a parser stops reading just after the root closes.
            val tail = ByteArray(8192)
            while (bounded.read(tail) != -1) checkCancelled()
        }
    }

    /** Required Android-compatible protections. No ignored feature/property setup failures. */
    internal fun secureReader(reader: XMLReader, handler: DefaultHandler2) {
        try {
            mapOf("namespaces" to true, "namespace-prefixes" to false, "validation" to false,
                "external-general-entities" to false, "external-parameter-entities" to false).forEach { (name, value) ->
                reader.setFeature(SAX_FEATURE + name, value)
                if (reader.getFeature(SAX_FEATURE + name) != value) throw SAXException("Feature not applied")
            }

            reader.setProperty(LEXICAL, handler)
            if (reader.getProperty(LEXICAL) !== handler) throw SAXException("Lexical handler not applied")
            reader.entityResolver = handler
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.dtdHandler = handler
            if (reader.entityResolver !== handler) throw SAXException("Entity resolver not applied")
        } catch (e: Exception) {
            throw ReadException(Reason.SECURITY_UNAVAILABLE, "Required XML safety protections are unavailable.", e)
        }
    }

    private open class SecureHandler(
        protected val checkCancelled: () -> Unit, private val events: EventBudget,
    ) : DefaultHandler2() {
        protected var depth = 0
        private var pendingNamespaces = 0
        protected fun tick() {
            checkCancelled()
            if (++events.used > MAX_XML_EVENTS) fail(Reason.LIMIT_EXCEEDED, "DOCX XML has too many events to preview.")
        }
        final override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            tick()
            if (attributes.length > MAX_ATTRIBUTES_PER_ELEMENT) {
                fail(Reason.LIMIT_EXCEEDED, "DOCX XML has too many attributes.")
            }
            var attributeChars = 0L
            for (i in 0 until attributes.length) {
                attributeChars += attributes.getValue(i).length + attributes.getQName(i).length + attributes.getURI(i).length
                if (attributeChars > MAX_ATTRIBUTE_CHARS) fail(Reason.LIMIT_EXCEEDED, "DOCX XML attributes are too large.")
            }
            pendingNamespaces = 0
            if (++depth > MAX_DEPTH) fail(Reason.LIMIT_EXCEEDED, "DOCX XML nesting exceeds the preview limit.")
            onStart(uri, localName, attributes)
        }
        final override fun endElement(uri: String, localName: String, qName: String) {
            tick(); onEnd(uri, localName); depth--
        }
        final override fun characters(ch: CharArray, start: Int, length: Int) {
            tick(); onText(ch, start, length)
        }
        protected open fun onStart(uri: String, name: String, attributes: Attributes) = Unit
        protected open fun onEnd(uri: String, name: String) = Unit
        protected open fun onText(ch: CharArray, start: Int, length: Int) = Unit
        override fun setDocumentLocator(locator: Locator?) = tick()
        override fun startDocument() = tick()
        override fun endDocument() = tick()
        override fun startPrefixMapping(prefix: String?, uri: String?) {
            tick()
            if (++pendingNamespaces > MAX_ATTRIBUTES_PER_ELEMENT ||
                (prefix?.length ?: 0) + (uri?.length ?: 0) > MAX_ATTRIBUTE_CHARS) {
                fail(Reason.LIMIT_EXCEEDED, "DOCX XML namespace declarations exceed the preview limit.")
            }
        }
        override fun endPrefixMapping(prefix: String?) = tick()
        override fun ignorableWhitespace(ch: CharArray?, start: Int, length: Int) = tick()
        override fun processingInstruction(target: String?, data: String?) = tick()
        override fun comment(ch: CharArray?, start: Int, length: Int) = tick()
        override fun startCDATA() = tick()
        override fun endCDATA() = tick()
        override fun startEntity(name: String?) = tick()
        override fun endEntity(name: String?) = tick()
        override fun endDTD() = tick()
        override fun startDTD(name: String?, publicId: String?, systemId: String?): Unit = unsafeXml()
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = unsafeXml()
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = unsafeXml()
        override fun getExternalSubset(name: String?, baseURI: String?): InputSource = unsafeXml()
        override fun skippedEntity(name: String?): Unit = unsafeXml()
        override fun elementDecl(name: String?, model: String?): Unit = unsafeXml()
        override fun attributeDecl(eName: String?, aName: String?, type: String?, mode: String?, value: String?): Unit = unsafeXml()
        override fun internalEntityDecl(name: String?, value: String?): Unit = unsafeXml()
        override fun externalEntityDecl(name: String?, publicId: String?, systemId: String?): Unit = unsafeXml()
        override fun notationDecl(name: String?, publicId: String?, systemId: String?): Unit = unsafeXml()
        override fun unparsedEntityDecl(name: String?, publicId: String?, systemId: String?, notationName: String?): Unit = unsafeXml()
        override fun warning(e: SAXParseException) { tick(); throw e }
        override fun error(e: SAXParseException) { tick(); throw e }
        override fun fatalError(e: SAXParseException) { tick(); throw e }
        private fun unsafeXml(): Nothing {
            tick(); fail(Reason.UNSAFE_XML, "DOCX XML with DTDs or external entities is not supported.")
        }
    }

    private class TypesHandler(check: () -> Unit, events: EventBudget) : SecureHandler(check, events) {
        var mainFound = false
        private val seen = HashSet<String>()
        override fun onStart(uri: String, name: String, attributes: Attributes) {
            if (uri != CONTENT_TYPES || (depth == 1 && name != "Types") ||
                (depth == 2 && name != "Override" && name != "Default") || depth > 2) {
                fail(Reason.CORRUPT, "DOCX content types are invalid.")
            }
            if (depth != 2) return
            val type = attributes.getValue("ContentType") ?: fail(Reason.CORRUPT, "DOCX content type is missing.")
            val lower = type.lowercase(Locale.ROOT)
            if (lower.contains("macroenabled") || lower.contains("vba")) {
                fail(Reason.UNSUPPORTED, "Macro-enabled Office documents cannot be previewed.")
            }
            val key = if (name == "Override") {
                val part = attributes.getValue("PartName") ?: fail(Reason.CORRUPT, "DOCX part name is missing.")
                if (!part.startsWith('/')) fail(Reason.CORRUPT, "DOCX part name is invalid.")
                validateName(part.substring(1))
                if (part == "/$MAIN") {
                    if (type != MAIN_TYPE) fail(Reason.UNSUPPORTED, "Only standard DOCX documents are supported.")
                    mainFound = true
                }
                if (type == MAIN_TYPE && part != "/$MAIN") {
                    fail(Reason.UNSUPPORTED, "DOCX with a nonstandard main document part is not supported.")
                }
                "part:${part.lowercase(Locale.ROOT)}"
            } else {
                val extension = attributes.getValue("Extension") ?: fail(Reason.CORRUPT, "DOCX extension is missing.")
                "extension:${extension.lowercase(Locale.ROOT)}"
            }
            if (!seen.add(key)) fail(Reason.CORRUPT, "DOCX content types contain duplicate entries.")
        }
    }

    private class RelationshipsHandler(check: () -> Unit, events: EventBudget) : SecureHandler(check, events) {
        var mainFound = false
        private val ids = HashSet<String>()
        override fun onStart(uri: String, name: String, attributes: Attributes) {
            if (uri != RELATIONSHIPS || (depth == 1 && name != "Relationships") ||
                (depth == 2 && name != "Relationship") || depth > 2) {
                fail(Reason.CORRUPT, "DOCX package relationships are invalid.")
            }
            if (depth != 2) return
            val id = attributes.getValue("Id") ?: fail(Reason.CORRUPT, "DOCX relationship ID is missing.")
            if (!ids.add(id)) fail(Reason.CORRUPT, "DOCX relationships contain duplicate IDs.")
            val type = attributes.getValue("Type") ?: fail(Reason.CORRUPT, "DOCX relationship type is missing.")
            if (type == OFFICE_REL || type == STRICT_OFFICE_REL) {
                if (mainFound) fail(Reason.CORRUPT, "DOCX has multiple main document relationships.")
                val mode = attributes.getValue("TargetMode")
                val target = attributes.getValue("Target")
                if ((mode != null && mode != "Internal") || (target != MAIN && target != "/$MAIN")) {
                    fail(Reason.UNSUPPORTED, "Only a local, standard DOCX main document part is supported.")
                }
                mainFound = true
            }

        }
    }

    private class PreviewFull : SAXException()

    private class BodyHandler(check: () -> Unit, events: EventBudget) : SecureHandler(check, events) {
        val output = StringBuilder()
        private var namespace = ""
        private var inBody = false
        private var bodyFound = false
        private var ignoredDepth = 0
        private var paragraphDepth = 0
        private var textDepth = 0
        override fun onStart(uri: String, name: String, attributes: Attributes) {
            if (depth == 1) {
                if ((uri != W && uri != STRICT_W) || name != "document") {
                    fail(Reason.CORRUPT, "DOCX main document XML is invalid.")
                }
                namespace = uri
            }
            if (depth == 2 && uri == namespace && name == "body") {
                if (bodyFound) fail(Reason.CORRUPT, "DOCX contains multiple document bodies.")
                bodyFound = true; inBody = true
            }
            if (!inBody || ignoredDepth != 0) return
            if ((uri == MC && name == "AlternateContent") || (uri == namespace && name in IGNORED_BODY_ELEMENTS)) {
                ignoredDepth = depth
                return
            }
            if (uri != namespace) return
            when (name) {
                "p" -> {
                    if (paragraphDepth != 0) fail(Reason.CORRUPT, "DOCX has nested paragraphs.")
                    paragraphDepth = depth
                }
                "t" -> if (paragraphDepth != 0) textDepth = depth
                "tab" -> if (paragraphDepth != 0) append('\t')
                "br", "cr" -> if (paragraphDepth != 0) append('\n')
                "noBreakHyphen" -> if (paragraphDepth != 0) append('\u2011')
                "softHyphen" -> if (paragraphDepth != 0) append('\u00ad')
            }
        }
        override fun onEnd(uri: String, name: String) {
            if (ignoredDepth != 0) {
                if (depth == ignoredDepth) ignoredDepth = 0
                return
            }
            if (!inBody || uri != namespace) return
            when (name) {
                "t" -> if (depth == textDepth) textDepth = 0
                "p" -> if (depth == paragraphDepth) { append('\n'); paragraphDepth = 0 }
                "tc" -> { removeLast('\n'); append('\t') }
                "tr" -> { removeLast('\t'); append('\n') }
                "body" -> if (depth == 2) inBody = false
            }
        }
        override fun onText(ch: CharArray, start: Int, length: Int) {
            if (textDepth == 0 || ignoredDepth != 0 || !inBody) return
            val accepted = minOf(length, MAX_OUTPUT_CHARS - output.length)
            output.append(ch, start, accepted)
            if (accepted < length) full()
        }
        override fun endDocument() {
            super.endDocument()
            if (!bodyFound) fail(Reason.CORRUPT, "DOCX main document has no body.")
        }
        private fun append(char: Char) {
            if (output.length == MAX_OUTPUT_CHARS) full()
            output.append(char)
        }
        private fun removeLast(char: Char) {
            if (output.isNotEmpty() && output.last() == char) output.setLength(output.length - 1)
        }
        private fun full(): Nothing {
            if (output.isNotEmpty() && output.last().isHighSurrogate()) output.setLength(output.length - 1)
            throw PreviewFull()
        }
    }
}

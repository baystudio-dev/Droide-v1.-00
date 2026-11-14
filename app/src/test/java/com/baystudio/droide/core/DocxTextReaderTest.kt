package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.ext.DefaultHandler2
import org.xml.sax.helpers.XMLFilterImpl

class DocxTextReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val mainType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private val types = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="$mainType"/></Types>"""
    private val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    @Test fun readsParagraphsTablesUnicodeTabsAndBreaksInOrder() {
        val xml = document("""
            <w:p><w:r><w:t xml:space="preserve"> Hello 世界 😀 </w:t><w:tab/><w:t>tab</w:t><w:br/><w:t>next</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p><w:p><w:r><w:t>D</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>
            <w:p><w:r><w:t>End</w:t></w:r></w:p>
        """.trimIndent())
        val result = DocxTextReader.read(docx(xml))
        assertEquals(" Hello 世界 😀 \ttab\nnext\nA\tB\nC\nD\t\nEnd\n", result.text)
        assertFalse(result.truncated)
        assertTrue(result.notice.contains("Main-body text only"))
    }

    @Test fun supportsStrictNamespaceAndPredefinedXmlEntities() {
        val xml = document("<w:p><w:r><w:t>&lt;&gt;&amp;&quot;&apos;&#x1F642;</w:t></w:r></w:p>")
            .replace(w, "http://purl.oclc.org/ooxml/wordprocessingml/main")
        assertEquals("<>&\"'🙂\n", DocxTextReader.read(docx(xml)).text)
    }

    @Test fun acceptsUtf16DocumentsWithoutDtd() {
        for (encoding in listOf("UTF-16LE", "UTF-16BE")) {
            val xml = "<?xml version=\"1.0\" encoding=\"$encoding\"?>" + document(paragraph("你好"))
            assertEquals("你好\n", DocxTextReader.read(docxBytes(xml.toByteArray(Charset.forName(encoding)))).text)
        }
    }

    @Test fun ignoresExternalRelationshipsHeadersDrawingsDeletedTextAndFieldInstructions() {
        val xml = document("""<w:p><w:r><w:instrText>dangerous field command</w:instrText><w:t>Visible</w:t></w:r><w:del><w:r><w:t>Deleted</w:t></w:r></w:del><w:r><w:drawing><w:txbxContent><w:p><w:r><w:t>Box</w:t></w:r></w:p></w:txbxContent></w:drawing></w:r></w:p>""")
        val external = rels.replace("</Relationships>", """<Relationship Id="external" Type="image" Target="https://127.0.0.1:1/never" TargetMode="External"/></Relationships>""")
        val result = DocxTextReader.read(docx(xml, relationships = external,
            extras = listOf("word/header1.xml" to document(paragraph("Header")).toByteArray())))
        assertEquals("Visible\n", result.text)
    }

    @Test fun ignoresUnparsedHugeMediaWithoutInflatingIt() {
        val file = docx(document(paragraph("small")), extras = listOf("word/media/unused.bin" to ByteArray(10 * 1024 * 1024)))
        assertEquals("small\n", DocxTextReader.read(file).text)
    }

    @Test fun emptyBodyHasHonestNotice() {
        val result = DocxTextReader.read(docx(document("")))
        assertEquals("", result.text)
        assertFalse(result.truncated)
        assertTrue(result.notice.contains("No main-body text"))
    }

    @Test fun missingBodyAndInvalidXmlAreRejected() {
        expect(DocxTextReader.Reason.CORRUPT, docx("<w:document xmlns:w=\"$w\"/>"))
        expect(DocxTextReader.Reason.CORRUPT, docx(document(paragraph("broken")).dropLast(3)))
        expect(DocxTextReader.Reason.CORRUPT, docx("<wrong/>"))
        expect(DocxTextReader.Reason.CORRUPT, docx(document("") + "garbage"))
    }

    @Test fun rejectsDtdExternalGeneralParameterAndExpansionEntitiesAcrossEncodings() {
        val dtds = listOf(
            "<!DOCTYPE w:document>",
            "<!DOCTYPE w:document SYSTEM \"file:///never-read\">",
            "<!DOCTYPE w:document [<!ENTITY x SYSTEM \"http://127.0.0.1:1/never\">]>",
            "<!DOCTYPE w:document [<!ENTITY % x SYSTEM \"file:///never-read\">%x;]>",
            "<!DOCTYPE w:document [<!ENTITY a \"1234\"><!ENTITY b \"&a;&a;&a;&a;\">]>",
        )
        for (encoding in listOf("UTF-8", "UTF-16LE", "UTF-16BE")) {
            for (dtd in dtds) {
                val xml = "<?xml version=\"1.0\" encoding=\"$encoding\"?>$dtd" + document(paragraph("safe"))
                expect(DocxTextReader.Reason.UNSAFE_XML, docxBytes(xml.toByteArray(Charset.forName(encoding))))
            }
        }
    }

    @Test fun rejectsDtdInMetadataToo() {
        expect(DocxTextReader.Reason.UNSAFE_XML, docx(document(""), contentTypes = "<!DOCTYPE Types>" + types))
        expect(DocxTextReader.Reason.UNSAFE_XML, docx(document(""), relationships = "<!DOCTYPE Relationships>" + rels))
    }

    @Test fun rejectsTraversalAbsoluteWindowsAndEncodedPathsEvenWhenNotRead() {
        for (name in listOf("../evil", "/evil", "word/../../evil", "word\\evil", "C:/evil", "word//evil", "word/./evil", "word/%2e%2e/evil")) {
            expect(DocxTextReader.Reason.CORRUPT, docx(document(""), extras = listOf(name to byteArrayOf(1))))
        }
    }

    @Test fun rejectsExactAndCaseAliasedDuplicates() {
        val file = docx(document(""), extras = listOf("word/documenx.xml" to document("").toByteArray()))
        replaceAscii(file, "word/documenx.xml", "word/document.xml")
        expect(DocxTextReader.Reason.CORRUPT, file)
        expect(DocxTextReader.Reason.CORRUPT, docx(document(""), extras = listOf("word/DOCUMENT.xml" to byteArrayOf(1))))
    }

    @Test fun rejectsTooManyArchiveEntriesAndTooLargeArchive() {
        val extras = (0 until DocxTextReader.MAX_ENTRIES).map { "unused/$it" to byteArrayOf() }
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, docx(document(""), extras = extras))
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(DocxTextReader.MAX_ARCHIVE_BYTES + 1) }
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, file)
    }

    @Test fun preflightsActualCentralCountEvenWhenEocdCountLies() {
        val extras = (0 until 2100).map { "unused/$it" to byteArrayOf() }
        val file = docx(document(""), extras = extras)
        patchEocd(file, 8, 3, 2); patchEocd(file, 10, 3, 2)
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, file)
    }

    @Test fun rejectsMultiDiskZip64OversizedCentralAndFalseCentralBounds() {
        val multiDisk = docx(document("")); patchEocd(multiDisk, 4, 1, 2)
        expect(DocxTextReader.Reason.UNSUPPORTED, multiDisk)
        val zip64 = docx(document("")); patchEocd(zip64, 8, 0xffff, 2); patchEocd(zip64, 10, 0xffff, 2)
        expect(DocxTextReader.Reason.UNSUPPORTED, zip64)
        val bigCentral = docx(document("")); patchEocd(bigCentral, 12, DocxTextReader.MAX_CENTRAL_DIRECTORY_BYTES.toInt() + 1, 4)
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, bigCentral)
        val falseBounds = docx(document("")); patchEocd(falseBounds, 16, 1, 4)
        expect(DocxTextReader.Reason.CORRUPT, falseBounds)
        val falseCount = docx(document("")); patchEocd(falseCount, 8, 1, 2); patchEocd(falseCount, 10, 1, 2)
        expect(DocxTextReader.Reason.CORRUPT, falseCount)
    }

    @Test fun rejectsCentralAndLocalFilenameDisagreement() {
        val file = docx(document(""))
        val bytes = file.readBytes()
        // Change only the first local part's name, retaining its length and its central record.
        bytes[30] = 'X'.code.toByte()
        file.writeBytes(bytes)
        expect(DocxTextReader.Reason.CORRUPT, file)
    }

    @Test fun rejectsAdvertisedXmlBombAndOversizedMetadata() {
        val huge = document(" ".repeat(DocxTextReader.MAX_XML_BYTES.toInt()))
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, docx(huge))
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED,
            docx(document(""), contentTypes = types.replace("</Types>", " ".repeat(256 * 1024) + "</Types>")))
    }

    @Test fun boundsActualInflationEvenWhenZipSizeLies() {
        val huge = document(" ".repeat(DocxTextReader.MAX_XML_BYTES.toInt()))
        val file = docx(huge)
        patchCentral(file, "word/document.xml", size = 1)
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, file)
    }

    @Test fun detectsSelectedPartChecksumMismatch() {
        val file = docx(document(paragraph("CRC")))
        patchCentral(file, "word/document.xml", crc = 1)
        expect(DocxTextReader.Reason.CORRUPT, file)
    }

    @Test fun rejectsExcessiveCompressionRatioBelowAbsoluteXmlLimit() {
        val file = docx(document(" ".repeat(6 * 1024 * 1024)))
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, file)
    }

    @Test fun rejectsTooManySaxEvents() {
        val body = buildString {
            repeat(DocxTextReader.MAX_XML_EVENTS / 2 + 1) { i ->
                append('<'); append('a' + i % 26); append('a' + i / 26 % 26)
                append('a' + i / 676 % 26); append("/>")
            }
        }
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, docx(document(body)))
    }

    @Test fun rejectsTooManyAndOversizedAttributesAndNamespaceDeclarations() {
        val attrs = (0..DocxTextReader.MAX_ATTRIBUTES_PER_ELEMENT).joinToString(" ") { "a$it='x'" }
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, docx(document("<w:p $attrs/>")))
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, docx(document("<w:p a='" + "x".repeat(DocxTextReader.MAX_ATTRIBUTE_CHARS + 1) + "'/>")))
        val namespaces = (0..DocxTextReader.MAX_ATTRIBUTES_PER_ELEMENT).joinToString(" ") { "xmlns:n$it='urn:$it'" }
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, docx(document("<w:p $namespaces/>")))
        // JDK Xerces rejects huge namespace URIs before our callback; Android Expat uses our limit.
        try {
            DocxTextReader.read(docx(document("<w:p xmlns:n='urn:" + "x".repeat(DocxTextReader.MAX_ATTRIBUTE_CHARS) + "'/>")))
            fail("Oversized namespace must be rejected")
        } catch (e: DocxTextReader.ReadException) {
            assertTrue(e.reason == DocxTextReader.Reason.LIMIT_EXCEEDED || e.reason == DocxTextReader.Reason.CORRUPT)
        }
    }

    @Test fun rejectsExcessiveDepth() {
        val body = "<w:customXml>".repeat(DocxTextReader.MAX_DEPTH) + paragraph("deep") + "</w:customXml>".repeat(DocxTextReader.MAX_DEPTH)
        expect(DocxTextReader.Reason.LIMIT_EXCEEDED, docx(document(body)))
    }

    @Test fun truncatesOutputAndDoesNotSplitSurrogatePairs() {
        val text = "a".repeat(DocxTextReader.MAX_OUTPUT_CHARS - 1) + "😀tail"
        val result = DocxTextReader.read(docx(document(paragraph(text))))
        assertTrue(result.truncated)
        assertEquals(DocxTextReader.MAX_OUTPUT_CHARS - 1, result.text.length)
        assertFalse(result.text.last().isHighSurrogate())
        assertTrue(result.notice.contains("remainder was not validated"))
    }

    @Test fun outputLimitBoundsManyEmptyParagraphsToo() {
        val result = DocxTextReader.read(docx(document("<w:p/>".repeat(DocxTextReader.MAX_OUTPUT_CHARS + 1))))
        assertTrue(result.truncated)
        assertEquals(DocxTextReader.MAX_OUTPUT_CHARS, result.text.length)
    }

    @Test fun cancellationPropagatesAndFileRemainsUnchanged() {
        val file = docx(document(paragraph("safe")))
        val original = file.readBytes()
        val cancelled = CancellationException("test cancellation")
        var checks = 0
        try {
            DocxTextReader.read(file) { if (++checks == 20) throw cancelled }
            fail("Cancellation should propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertArrayEquals(original, file.readBytes())
        assertEquals("safe\n", DocxTextReader.read(file).text)
    }

    @Test fun cancellationRunsDuringLongInputReadsWithoutTextCallbacks() {
        val file = docx(document("<!--" + "x".repeat(100_000) + "-->" + paragraph("safe")))
        val cancelled = CancellationException("reading")
        var checks = 0
        try {
            DocxTextReader.read(file) { if (++checks == 80) throw cancelled }
            fail("Cancellation should propagate during input reads")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun rejectsLegacyEncryptedMacroAndNonstandardMainParts() {
        val ole = temp.newFile()
        ole.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        expect(DocxTextReader.Reason.UNSUPPORTED, ole)
        expect(DocxTextReader.Reason.UNSUPPORTED, docx(document(""), contentTypes = types.replace(mainType, "application/vnd.ms-word.document.macroEnabled.main+xml")))
        expect(DocxTextReader.Reason.UNSUPPORTED, docx(document(""), extras = listOf("word/vbaProject.bin" to byteArrayOf(1))))
        expect(DocxTextReader.Reason.UNSUPPORTED, docx(document(""), contentTypes = types.replace("word/document.xml", "other/document.xml")))
        expect(DocxTextReader.Reason.UNSUPPORTED, docx(document(""), relationships = rels.replace("word/document.xml", "https://example.com/remote.xml")))
        expect(DocxTextReader.Reason.UNSUPPORTED, docx(document(""), relationships = rels.replace("Target=", "TargetMode=\"External\" Target=")))
    }

    @Test fun rejectsMissingAndDuplicateRequiredMetadata() {
        expect(DocxTextReader.Reason.CORRUPT, archive(listOf("word/document.xml" to document("").toByteArray())))
        val twice = types.replace("</Types>", "<Override PartName=\"/word/document.xml\" ContentType=\"$mainType\"/></Types>")
        expect(DocxTextReader.Reason.CORRUPT, docx(document(""), contentTypes = twice))
        expect(DocxTextReader.Reason.CORRUPT, docx(document(""), relationships = rels.replace("</Relationships>", rels.substringAfter('>').substringBeforeLast("</Relationships>") + "</Relationships>")))
    }

    @Test fun rejectsEmptyCorruptAndTruncatedZip() {
        val empty = temp.newFile()
        expect(DocxTextReader.Reason.CORRUPT, empty)
        val garbage = temp.newFile().apply { writeText("not a zip") }
        expect(DocxTextReader.Reason.CORRUPT, garbage)
        val file = docx(document(""))
        file.writeBytes(file.readBytes().dropLast(30).toByteArray())
        expect(DocxTextReader.Reason.CORRUPT, file)
    }

    @Test fun secureParserFailsClosedOnUnsupportedProtection() {
        val real = SAXParserFactory.newInstance().newSAXParser().xmlReader
        val broken = object : XMLFilterImpl(real) {
            override fun setFeature(name: String, value: Boolean) {
                if (name.endsWith("external-general-entities")) throw SAXNotRecognizedException(name)
                super.setFeature(name, value)
            }
        }
        expectSecurityFailure { DocxTextReader.secureReader(broken, DefaultHandler2()) }
    }

    @Test fun secureParserFailsClosedOnIgnoredFeatureAndMissingLexicalHandler() {
        val real = SAXParserFactory.newInstance().newSAXParser().xmlReader
        val lying = object : XMLFilterImpl(real) {
            override fun getFeature(name: String): Boolean = if (name.endsWith("external-general-entities")) true else super.getFeature(name)
        }
        expectSecurityFailure { DocxTextReader.secureReader(lying, DefaultHandler2()) }
        val noLexical = object : XMLFilterImpl(real) {
            override fun setProperty(name: String, value: Any?) {
                if (name.endsWith("lexical-handler")) throw SAXNotRecognizedException(name)
                super.setProperty(name, value)
            }
        }
        expectSecurityFailure { DocxTextReader.secureReader(noLexical, DefaultHandler2()) }
    }

    private fun expectSecurityFailure(block: () -> Unit) {
        try { block(); fail("Expected security failure") } catch (e: DocxTextReader.ReadException) {
            assertEquals(DocxTextReader.Reason.SECURITY_UNAVAILABLE, e.reason)
        }
    }
    private fun expect(reason: DocxTextReader.Reason, file: File) {
        try { DocxTextReader.read(file); fail("Expected $reason for ${file.name}") }
        catch (e: DocxTextReader.ReadException) { assertEquals(e.message, reason, e.reason) }
    }
    private fun document(body: String) = "<w:document xmlns:w=\"$w\"><w:body>$body</w:body></w:document>"
    private fun paragraph(text: String) = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"
    private fun docx(xml: String, contentTypes: String = types, relationships: String = rels, extras: List<Pair<String, ByteArray>> = emptyList()) =
        docxBytes(xml.toByteArray(), contentTypes, relationships, extras)
    private fun docxBytes(xml: ByteArray, contentTypes: String = types, relationships: String = rels, extras: List<Pair<String, ByteArray>> = emptyList()) =
        archive(listOf("[Content_Types].xml" to contentTypes.toByteArray(), "_rels/.rels" to relationships.toByteArray(), "word/document.xml" to xml) + extras)
    private fun archive(parts: List<Pair<String, ByteArray>>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in parts) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
    private fun replaceAscii(file: File, from: String, to: String) {
        require(from.length == to.length)
        val bytes = file.readBytes()
        val find = from.toByteArray()
        val replacement = to.toByteArray()
        for (i in 0..bytes.size - find.size) {
            if (find.indices.all { bytes[i + it] == find[it] }) replacement.copyInto(bytes, i)
        }
        file.writeBytes(bytes)
    }
    private fun patchEocd(file: File, offset: Int, value: Int, width: Int) {
        val bytes = file.readBytes()
        val end = bytes.size - 22 // Fixtures have no ZIP comment.
        for (i in 0 until width) bytes[end + offset + i] = (value ushr (8 * i)).toByte()
        file.writeBytes(bytes)
    }
    private fun patchCentral(file: File, name: String, size: Int? = null, crc: Int? = null) {
        val bytes = file.readBytes()
        for (i in 0 until bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()) {
                val nameLength = (bytes[i + 28].toInt() and 255) or ((bytes[i + 29].toInt() and 255) shl 8)
                if (String(bytes, i + 46, nameLength, Charsets.UTF_8) == name) {
                    fun patch(offset: Int, value: Int) { for (n in 0..3) bytes[i + offset + n] = (value ushr (8 * n)).toByte() }
                    size?.let { patch(24, it) }; crc?.let { patch(16, it) }
                    file.writeBytes(bytes)
                    return
                }
            }
        }
        error("Missing central entry")
    }
}

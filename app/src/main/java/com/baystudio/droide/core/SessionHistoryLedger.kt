package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Cold exact history is immutable and lossless; only two bounded pages are decoded at once. */
@Serializable
data class SessionHistoryPage(val sha256: String, val bytes: Long, val entries: Int)

internal interface VerifiedSessionHistory { val exactVerified: Boolean }

internal class SessionHistoryCorruptionException(message: String, cause: Exception? = null) :
    IOException("Exact conversation history unavailable; existing data was preserved: $message", cause)

/** Streaming admission also applies to the main session JSON: never encode a whole oversized String. */
internal class BoundedSessionOutput(private val delegate: OutputStream, private val limit: Long,
    maxNestingDepth: Int? = null) : OutputStream() {
    // Opt in only at JSON publication sites; byte-only users retain their contract.
    private val nesting = maxNestingDepth?.let(::SessionJsonNesting)
    var bytesWritten = 0L
        private set
    override fun write(value: Int) {
        requireCapacity(1)
        nesting?.inspect(value and 0xff)
        delegate.write(value)
        bytesWritten++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        requireCapacity(length)
        if (nesting != null) for (index in offset until offset + length) nesting.inspect(bytes[index].toInt() and 0xff)
        delegate.write(bytes, offset, length)
        bytesWritten += length
    }
    private fun requireCapacity(additional: Int) {
        if (additional < 0 || bytesWritten > limit - additional)
            throw IOException("Session storage capacity reached; existing conversation was preserved")
    }
    override fun flush() = delegate.flush()
}

internal class BoundedSessionInput(private val delegate: InputStream, private val limit: Long,
    maxNestingDepth: Int = 128) : InputStream() {
    private var readBytes = 0L
    private val nesting = SessionJsonNesting(maxNestingDepth)
    val bytesRead: Long get() = readBytes
    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) { admit(1); nesting.inspect(value) }
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val result = delegate.read(bytes, offset, length.coerceAtMost((limit - readBytes + 1).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        if (result > 0) {
            admit(result)
            for (index in offset until offset + result) nesting.inspect(bytes[index].toInt() and 0xff)
        }
        return result
    }
    private fun admit(count: Int) {
        readBytes += count
        if (readBytes > limit) throw IOException("Conversation storage exceeds its declared capacity; existing data was preserved")
    }
}

/** Shared lexical depth accounting, including quotes/escapes split across I/O chunks. */
private class SessionJsonNesting(private val limit: Int) {
    init { require(limit > 0) }
    private var depth = 0
    private var inString = false
    private var escaped = false
    fun inspect(value: Int) {
        if (inString) {
            if (escaped) escaped = false
            else if (value == '\\'.code) escaped = true
            else if (value == '"'.code) inString = false
        } else when (value) {
            '"'.code -> inString = true
            '{'.code, '['.code -> {
                depth++
                if (depth > limit) throw IOException("Conversation storage nesting exceeds its safe restore capacity; existing data was preserved")
            }
            '}'.code, ']'.code -> depth--
        }
    }
}

internal class SessionHistoryLedger(workDir: File) {
    private val workspaceRoot = workDir.canonicalFile
    // An unavailable original binding stays unavailable; metadata-only construction
    // must neither throw here nor adopt a later workspace on the first page write.
    private val workspaceBinding: Pair<File, String>? = try { ProjectCopyEvidence.root(workspaceRoot) }
        catch (_: Exception) { null }
    private val root = PathSecurity.resolveWithin(workDir, ".droide/sessions/.history-pages")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(JsonObject.serializer())

    internal class Snapshot(
        val owner: SessionHistoryLedger,
        val pages: List<SessionHistoryPage>,
        val tail: List<JsonObject>,
        override val exactVerified: Boolean,
    ) : AbstractList<JsonObject>(), VerifiedSessionHistory {
        private val ends = IntArray(pages.size).also { values ->
            var sum = 0
            pages.forEachIndexed { index, page -> sum += page.entries; values[index] = sum }
        }
        val coldEntries = ends.lastOrNull() ?: 0
        override val size: Int = coldEntries + tail.size
        private val cache = object : LinkedHashMap<Int, List<JsonObject>>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<JsonObject>>): Boolean = size > 2
        }
        override fun get(index: Int): JsonObject {
            checkElementIndex(index)
            if (index >= coldEntries) return tail[index - coldEntries]
            val found = ends.binarySearch(index + 1)
            val pageIndex = if (found >= 0) found else -found - 1
            val start = if (pageIndex == 0) 0 else ends[pageIndex - 1]
            val page = synchronized(cache) { cache[pageIndex] ?: owner.readPage(pages[pageIndex]).also { cache[pageIndex] = it } }
            return page[index - start]
        }
        private fun checkElementIndex(index: Int) {
            if (index !in 0 until size) throw IndexOutOfBoundsException("History entry $index of $size")
        }
        internal val cachedPageCount: Int get() = synchronized(cache) { cache.size }
    }

    fun snapshot(pages: List<SessionHistoryPage>, tail: List<JsonObject>, verified: Boolean = false): Snapshot {
        validateDescriptors(pages)
        require(pages.sumOf { it.entries.toLong() } + tail.size <= MAX_HISTORY_ENTRIES) { "Exact conversation entry capacity reached" }
        return Snapshot(this, pages.toList(), tail.toList(), verified)
    }

    /** Reuse only pages owned by this ledger. A fork in another workspace must copy and verify them. */
    fun persist(history: List<JsonObject>): List<SessionHistoryPage> =
        ExportWriterAdmission.withWriter(workspaceRoot, "Session history persistence") {
        require(history.size.toLong() <= MAX_HISTORY_ENTRIES) { "Exact conversation entry capacity reached; existing data was preserved" }
        val pages = mutableListOf<SessionHistoryPage>()
        var source = history
        var start = if (history is Snapshot && history.owner === this) {
            pages += history.pages
            history.coldEntries
        } else 0
        val batch = mutableListOf<JsonObject>()
        var batchChars = 0L
        // A checkpoint seals even a tiny tail. Repeated complete turns can exhaust
        // descriptors long before byte/entry limits. Repack only at that boundary;
        // normal appends keep their existing immutable pages and write cost.
        fun plannedPageCount(): Int {
            var count = pages.size
            var batchBytes = 0L
            var batchEntries = 0
            var totalBytes = pages.sumOf { it.bytes }
            for (index in start until source.size) {
                val bytes = SessionJsonSize.estimatedBytes(source[index])
                require(bytes <= MAX_PAGE_BYTES - 2) { "Conversation entry exceeds the bounded history-page capacity; existing data was preserved" }
                if (batchEntries == 0 || batchBytes + bytes > HOT_HISTORY_BYTES || batchEntries >= MAX_PAGE_ENTRIES) {
                    count++
                    totalBytes += 2
                    batchBytes = 0
                    batchEntries = 0
                }
                require(totalBytes <= MAX_HISTORY_BYTES - bytes) {
                    "Exact conversation storage capacity reached; existing data was preserved"
                }
                totalBytes += bytes
                batchBytes += bytes
                batchEntries++
            }
            return count
        }
        var plannedPages = plannedPageCount()
        if (plannedPages > MAX_HISTORY_PAGES && start > 0 && history is Snapshot) {
            // Never use the caller's decoded cache as evidence of the files that
            // will be repacked. A fresh bounded snapshot verifies every old page
            // in the full admission pass before any replacement page is written.
            source = snapshot(history.pages, history.tail)
            pages.clear()
            start = 0
            plannedPages = plannedPageCount()
        }
        require(plannedPages <= MAX_HISTORY_PAGES) {
            "Exact conversation page capacity reached; existing data was preserved"
        }
        for (index in start until source.size) {
            val entry = source[index]
            val chars = SessionJsonSize.estimatedBytes(entry)
            if (batch.isNotEmpty() && (batchChars + chars > HOT_HISTORY_BYTES || batch.size >= MAX_PAGE_ENTRIES)) {
                require(pages.size < MAX_HISTORY_PAGES) { "Exact conversation page capacity reached; existing data was preserved" }
                pages += writePage(batch)
                batch.clear()
                batchChars = 0
            }
            batch += entry
            batchChars += chars
        }
        if (batch.isNotEmpty()) {
            require(pages.size < MAX_HISTORY_PAGES) { "Exact conversation page capacity reached; existing data was preserved" }
            pages += writePage(batch)
        }
        validateDescriptors(pages)
        pages
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun writePage(entries: List<JsonObject>): SessionHistoryPage =
        ExportWriterAdmission.withWriter(workspaceRoot, "Session history page") {
        require(entries.isNotEmpty() && entries.size <= MAX_PAGE_ENTRIES) { "Invalid conversation history page" }
        entries.forEach(SessionJsonSize::requireSupportedDepth)
        requireOwnedRoot()
        check(root.isDirectory || root.mkdirs()) { "Cannot create exact conversation storage; existing data was preserved" }
        val tmp = File(root, ".page-${java.util.UUID.randomUUID()}.tmp")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            FileOutputStream(tmp).use { stream ->
                val digest = java.security.DigestOutputStream(stream, hash)
                val bounded = BoundedSessionOutput(digest, MAX_PAGE_BYTES, SessionJsonSize.PAGE_NESTING_DEPTH)
                json.encodeToStream(serializer, entries, bounded)
                bounded.flush()
                count = bounded.bytesWritten
                stream.fd.sync()
            }
            val sha = hash.digest().joinToString("") { "%02x".format(it) }
            val page = SessionHistoryPage(sha, count, entries.size)
            val target = fileFor(page)
            if (target.exists()) {
                // Content addressing does not authorize silently replacing a damaged existing page.
                readPage(page)
            } else {
                try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), target.toPath()) }
            }
            page
        } finally { Files.deleteIfExists(tmp.toPath()) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readPage(page: SessionHistoryPage): List<JsonObject> {
        try {
            val file = fileFor(page)
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() == page.bytes) { "Missing or resized history page" }
            val hash = MessageDigest.getInstance("SHA-256")
            val decoded = file.inputStream().use { input ->
                // Authenticate the exact descriptor decoded, with admission before parser allocation.
                val bounded = BoundedSessionInput(java.security.DigestInputStream(input, hash), page.bytes, SessionJsonSize.PAGE_NESTING_DEPTH)
                val value = json.decodeFromStream(serializer, bounded)
                val remainder = ByteArray(8 * 1024)
                while (bounded.read(remainder) >= 0) { /* Include parser read-ahead and trailing bytes. */ }
                require(bounded.bytesRead == page.bytes) { "History page length changed" }
                value
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == page.sha256) { "History page checksum mismatch" }
            require(decoded.size == page.entries) { "History page entry count mismatch" }
            return decoded
        } catch (failure: Exception) {
            if (failure is SessionHistoryCorruptionException) throw failure
            throw SessionHistoryCorruptionException("history page could not be verified", failure)
        }
    }

    private fun fileFor(page: SessionHistoryPage): File {
        requireOwnedRoot()
        require(SHA256.matches(page.sha256) && page.bytes in 1..MAX_PAGE_BYTES && page.entries in 1..MAX_PAGE_ENTRIES) {
            "Invalid exact conversation page descriptor"
        }
        val file = File(root, "${page.sha256}.json")
        require(!Files.isSymbolicLink(file.toPath()) && PathSecurity.contains(root.canonicalFile, file.canonicalFile)) {
            "Invalid exact conversation page path"
        }
        return file
    }
    private fun requireOwnedRoot() {
        require(workspaceBinding != null && ProjectCopyEvidence.root(workspaceRoot) == workspaceBinding) {
            "Exact conversation workspace identity changed or is unavailable; existing data was preserved"
        }
        require(!Files.isSymbolicLink(root.toPath()) && PathSecurity.contains(workspaceRoot, root.canonicalFile)) {
            "Exact conversation storage path changed; existing data was preserved"
        }
    }
    private fun validateDescriptors(pages: List<SessionHistoryPage>) {
        require(pages.size <= MAX_HISTORY_PAGES && pages.sumOf { it.bytes } <= MAX_HISTORY_BYTES &&
            pages.sumOf { it.entries.toLong() } <= MAX_HISTORY_ENTRIES) { "Exact conversation storage capacity reached; existing data was preserved" }
        pages.forEach { page ->
            require(SHA256.matches(page.sha256) && page.bytes in 1..MAX_PAGE_BYTES && page.entries in 1..MAX_PAGE_ENTRIES) {
                "Invalid exact conversation page descriptor"
            }
        }
    }
    companion object {
        const val HOT_HISTORY_BYTES = 512L * 1024
        const val MAX_PAGE_BYTES = 4L * 1024 * 1024
        const val MAX_PAGE_ENTRIES = 4_096
        const val MAX_HISTORY_PAGES = 4_096
        const val MAX_HISTORY_BYTES = 256L * 1024 * 1024
        const val MAX_HISTORY_ENTRIES = 1_000_000L
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** Conservative UTF-8/JSON size count without allocating String/byte copies of the document. */
internal object SessionJsonSize {
    // Match the established estimator: root entry depth zero, including scalar
    // leaves and empty containers at depth 128. Wire containers need headroom.
    const val MAX_ENTRY_DEPTH = 128
    const val PAGE_NESTING_DEPTH = MAX_ENTRY_DEPTH + 2
    const val SESSION_NESTING_DEPTH = MAX_ENTRY_DEPTH + 3

    /** Iterative preflight runs before the recursive serializer or any publication. */
    fun requireSupportedDepth(value: kotlinx.serialization.json.JsonElement) {
        val pending = ArrayDeque<Pair<kotlinx.serialization.json.JsonElement, Int>>()
        pending.addLast(value to 0)
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            require(depth <= MAX_ENTRY_DEPTH) {
                "Conversation storage nesting exceeds its safe history capacity; existing data was preserved"
            }
            when (element) {
                is kotlinx.serialization.json.JsonObject -> element.values.forEach { pending.addLast(it to depth + 1) }
                is kotlinx.serialization.json.JsonArray -> element.forEach { pending.addLast(it to depth + 1) }
                else -> Unit
            }
        }
    }

    fun estimatedBytes(value: kotlinx.serialization.json.JsonElement): Long {
        val pending = ArrayDeque<Pair<kotlinx.serialization.json.JsonElement, Int>>()
        pending.addLast(value to 0)
        var bytes = 0L
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            // Prevent our admission walk or the subsequent JSON encoder from recursing on hostile depth.
            if (depth > MAX_ENTRY_DEPTH) return SessionHistoryLedger.MAX_PAGE_BYTES + 1
            when (element) {
                is kotlinx.serialization.json.JsonObject -> {
                    bytes += 2L
                    element.forEach { (key, item) -> bytes += escaped(key) + 2L; pending.addLast(item to depth + 1) }
                }
                is kotlinx.serialization.json.JsonArray -> {
                    bytes += 2L + element.size
                    element.forEach { pending.addLast(it to depth + 1) }
                }
                is kotlinx.serialization.json.JsonPrimitive -> bytes += if (element.isString) escaped(element.content) else element.content.length.toLong()
            }
            if (bytes > SessionHistoryLedger.MAX_PAGE_BYTES) return bytes
        }
        return bytes
    }
    private fun escaped(text: String): Long {
        var total = 2L
        for (ch in text) total += when {
            ch == '"' || ch == '\\' -> 2
            ch < ' ' -> 6
            ch.code < 128 -> 1
            ch.code < 2048 -> 2
            else -> 3 // Two UTF-16 surrogates cost six here, conservatively exceeding UTF-8's four.
        }
        return total
    }
}

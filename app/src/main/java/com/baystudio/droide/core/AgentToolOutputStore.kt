package com.baystudio.droide.core

import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Immutable model-output artifacts. Quotas affect retention only, never tool execution.
 * Published files and crash remnants are never automatically deleted. Ownership is a
 * private directory entry, not authority supplied in a model-generated reference.
 */
internal class AgentToolOutputStore(private val root: File, private val limits: Limits = Limits()) {
    data class Limits(val bytes: Long = 128L * 1024 * 1024, val entries: Int = 512,
        val outputBytes: Long = 16L * 1024 * 1024) {
        init { require(bytes > 0 && entries > 0 && outputBytes > 8 && outputBytes <= bytes) }
    }
    data class Retained(val reference: String, val chars: Int)
    companion object {
        const val PREFIX = "droide-tool-output://"
        const val PAGE_CHARS = 8_000
        private const val MAGIC = 0x44544f32
        private const val CHUNK_CHARS = 8192
        private val idPattern = Regex("[a-f0-9]{32}-[a-f0-9]{64}")
        private val locks = ConcurrentHashMap<String, Any>()
        fun forWorkspace(workDir: File): AgentToolOutputStore {
            val base = ChatMediaStoragePaths.baseDirectory(workDir)
            return AgentToolOutputStore(File(base.parentFile,
                "tool-output/${ChatMediaStoragePaths.workspaceKey(workDir)}"))
        }
        fun references(text: String): Set<String> = Regex("droide-tool-output://[a-f0-9]{32}-[a-f0-9]{64}")
            .findAll(text).map { it.value }.toSet()
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    internal var onReadBytes: ((Int) -> Unit)? = null
    private val lock get() = locks.getOrPut(root.absolutePath) { Any() }
    private fun safe(file: File): File {
        var current: File? = file.absoluteFile
        while (current != null) {
            require(!Files.isSymbolicLink(current.toPath())) { "Output storage contains a symbolic link" }
            current = current.parentFile
        }
        return file
    }
    private fun owner(session: String): File {
        require(session.isNotBlank()) { "Active conversation required" }
        return safe(File(root, digest(session)))
    }
    private fun resolve(session: String, reference: String): File {
        require(reference.startsWith(PREFIX)) { "Invalid output reference" }
        val id = reference.removePrefix(PREFIX)
        require(idPattern.matches(id)) { "Invalid output reference" }
        return safe(File(owner(session), "$id.bin"))
    }
    private fun mkdir(file: File) {
        safe(file)
        if (!file.isDirectory) {
            file.parentFile?.let(::mkdir)
            Files.createDirectory(file.toPath())
            file.parentFile?.let(::syncDirectory)
        }
        safe(file)
    }
    private fun syncDirectory(dir: File) {
        FileChannel.open(safe(dir).toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    /** Streams directory entries and retains at most the unique-blob quota in
     * memory. User-requested fork ownership links are session metadata, not new
     * retained content, and are never charged another logical storage quota. */
    private fun admit(bytes: Long, entries: Int = 1) {
        safe(root)
        var size = bytes
        var count = entries
        val identities = mutableSetOf<Any>()
        fun inspect(file: File) {
            safe(file)
            val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) throw IOException("Unexpected output storage entry")
            val identity = attributes.fileKey() ?: file.absolutePath
            if (identities.add(identity)) {
                count++
                size = Math.addExact(size, attributes.size())
            }
            if (count > limits.entries || size > limits.bytes)
                throw IOException("Output retention capacity reached; existing outputs preserved")
        }
        if (root.exists()) Files.newDirectoryStream(root.toPath()).use { owners ->
            for (owner in owners) {
                val dir = safe(owner.toFile())
                if (Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)) {
                    Files.newDirectoryStream(owner).use { files -> for (file in files) inspect(file.toFile()) }
                } else inspect(dir)
            }
        }
        if (count > limits.entries || size > limits.bytes) throw IOException("Output retention capacity reached; existing outputs preserved")
    }
    fun retain(session: String, output: String): Retained = synchronized(lock) {
        require(8L + output.length.toLong()*2 <= limits.outputBytes) { "Output exceeds archival capacity; operation was not blocked" }
        val chunks = (output.length.toLong() + CHUNK_CHARS - 1).div(CHUNK_CHARS).toInt()
        require(8L + chunks.toLong()*32 + output.length.toLong()*2 <= limits.outputBytes) { "Output exceeds archival capacity; operation was not blocked" }
        val indexBytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { index ->
                index.writeInt(MAGIC); index.writeInt(output.length)
                for (chunk in 0 until chunks) {
                    val start = chunk * CHUNK_CHARS
                    val content = utf16Bytes(output, start, minOf(output.length, start + CHUNK_CHARS))
                    index.write(MessageDigest.getInstance("SHA-256").digest(content))
                }
            }
        }.toByteArray()
        val bytes = indexBytes.size.toLong() + output.length.toLong() * 2
        require(bytes <= limits.outputBytes) { "Output exceeds archival capacity; operation was not blocked" }
        admit(bytes)
        val dir = owner(session); mkdir(dir)
        val temporary = safe(File(dir, "pending-${UUID.randomUUID()}.tmp"))
        // Temporary files are counted after a crash and kept for explicit recovery.
        check(temporary.createNewFile()) { "Output temporary entry collision" }
        FileOutputStream(temporary).use { file ->
            val out = DataOutputStream(BufferedOutputStream(file))
            out.write(indexBytes)
            for (char in output) out.writeChar(char.code)
            out.flush(); file.fd.sync()
        }
        val sha = hex(MessageDigest.getInstance("SHA-256").digest(indexBytes))
        val id = UUID.randomUUID().toString().replace("-", "") + "-" + sha
        val destination = safe(File(dir, "$id.bin"))
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(dir)
        syncDirectory(root)
        root.parentFile?.let(::syncDirectory)
        Retained(PREFIX + id, output.length)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun utf16Bytes(text: String, start: Int, end: Int): ByteArray = ByteArray((end - start) * 2).also { data ->
        for (i in start until end) { val c = text[i].code; data[(i-start)*2] = (c ushr 8).toByte(); data[(i-start)*2+1] = c.toByte() }
    }
    private data class Index(val chars: Int, val bytes: Int, val hashes: List<ByteArray>)
    private fun index(input: RandomAccessFile, reference: String): Index {
        require(input.length() in 8..limits.outputBytes) { "Output artifact length is invalid" }
        require(input.readInt() == MAGIC) { "Unsupported output artifact" }
        val chars = input.readInt()
        require(chars >= 0) { "Invalid output artifact" }
        val chunks = (chars.toLong() + CHUNK_CHARS - 1) / CHUNK_CHARS
        val indexSize = 8L + chunks * 32
        require(indexSize + chars.toLong()*2 == input.length() && indexSize <= Int.MAX_VALUE) { "Invalid output artifact length" }
        input.seek(0)
        val bytes = ByteArray(indexSize.toInt()); input.readFully(bytes); onReadBytes?.invoke(bytes.size)
        require(hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == reference.substringAfterLast('-')) {
            "Output index integrity check failed; existing data preserved"
        }
        return Index(chars, bytes.size, (0 until chunks.toInt()).map { bytes.copyOfRange(8+it*32, 8+(it+1)*32) })
    }
    private fun ownedFile(session: String, reference: String): File {
        val file = resolve(session, reference)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Output is unavailable or does not belong to this conversation; do not repeat the original operation"
        }
        return file
    }
    fun read(session: String, reference: String, offset: Int = 0): String = synchronized(lock) {
        RandomAccessFile(ownedFile(session, reference), "r").use { input ->
            val index = index(input, reference)
            require(offset in 0..index.chars) { "offset is outside the retained output" }
            val loaded = mutableMapOf<Int, CharArray>()
            fun charAt(position: Int): Char {
                val chunk = position / CHUNK_CHARS
                val chars = loaded.getOrPut(chunk) {
                    val count = minOf(CHUNK_CHARS, index.chars - chunk*CHUNK_CHARS)
                    val data = ByteArray(count*2)
                    input.seek(index.bytes.toLong() + chunk.toLong()*CHUNK_CHARS*2)
                    input.readFully(data); onReadBytes?.invoke(data.size)
                    require(MessageDigest.getInstance("SHA-256").digest(data).contentEquals(index.hashes[chunk])) {
                        "Output page integrity check failed; existing data preserved"
                    }
                    CharArray(count) { (((data[it*2].toInt() and 255) shl 8) or (data[it*2+1].toInt() and 255)).toChar() }
                }
                return chars[position % CHUNK_CHARS]
            }
            if (offset > 0 && offset < index.chars)
                require(!(charAt(offset-1).isHighSurrogate() && charAt(offset).isLowSurrogate())) { "offset splits a Unicode code point" }
            var end = minOf(index.chars.toLong(), offset.toLong()+PAGE_CHARS).toInt()
            if (end > offset && end < index.chars && charAt(end-1).isHighSurrogate() && charAt(end).isLowSurrogate()) end--
            val data = CharArray(end-offset) { charAt(offset+it) }
            "[DROIDE_RETAINED_OUTPUT offset=$offset next_offset=$end complete=${end == index.chars} original_utf16_chars=${index.chars}]\n" + String(data)
        }
    }
    /** Full verification is used once when granting ownership, never per retrieval page. */
    private fun verified(session: String, reference: String): File {
        val file = ownedFile(session, reference)
        RandomAccessFile(file, "r").use { input ->
            val index = index(input, reference)
            for (chunk in index.hashes.indices) {
                val count = minOf(CHUNK_CHARS, index.chars - chunk*CHUNK_CHARS)
                val data = ByteArray(count*2); input.readFully(data); onReadBytes?.invoke(data.size)
                require(MessageDigest.getInstance("SHA-256").digest(data).contentEquals(index.hashes[chunk])) { "Output integrity check failed" }
            }
        }
        return file
    }
    fun owns(session: String, reference: String): Boolean = synchronized(lock) {
        val file = resolve(session, reference)
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
    /** Called only by trusted session fork code, for references in the selected prefix. */
    fun copyOwner(source: String, target: String, references: Set<String>) = synchronized(lock) {
        if (references.isEmpty()) return@synchronized
        require(references.size <= limits.entries) { "Invalid retained-output fork reference count" }
        val originals = references.map { verified(source, it) }
        val dir = owner(target); mkdir(dir)
        val missing = originals.filter { !File(dir, it.name).exists() }
        // Explicit session forks grant metadata only. Never block a user fork
        // because the optional new-output retention quota is full.
        for (file in missing) {
            val destination = safe(File(dir, file.name))
            // Immutable hard link grants ownership without duplicating data. Unsupported
            // filesystems fail the fork visibly, rather than publishing broken references.
            Files.createLink(destination.toPath(), file.toPath())
        }
        syncDirectory(dir)
        syncDirectory(root)
    }
}

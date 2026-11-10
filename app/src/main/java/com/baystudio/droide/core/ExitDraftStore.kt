package com.baystudio.droide.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** User-authored unsent material only. No provider credentials, private thoughts, or terminal logs. */
internal data class ExitDraftAttachment(
    val name: String,
    val preview: String,
    val media: ChatMediaRef? = null,
    val previewTruncated: Boolean = false,
)
internal data class ExitDraftRecord(
    val backend: String, val session: String?, val text: String,
    val attachments: List<ExitDraftAttachment> = emptyList(),
) {
    val identity: String by lazy { MessageDigest.getInstance("SHA-256")
        .digest(ExitDraftCodec.utf8(ExitDraftCodec.encodeOne(this).toString()))
        .joinToString("") { "%02x".format(it.toInt() and 255) } }
}

internal object ExitDraftCodec {
    // Older accumulated saved copies still require explicit selected-copy deletion.

    const val MAX_BYTES = 24 * 1024 * 1024
    const val MAX_RECORDS = 64
    private const val CAPACITY_ERROR = "Saved unsent drafts are full. Review Saved drafts and explicitly delete a selected saved copy before retrying Exit. Current drafts are unchanged."
    private fun string(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("Invalid recovered draft field")
    internal fun utf8(text: String): ByteArray {
        // Never replace unpaired UTF-16 code units with '?' in a supposedly exact recovery.
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
    fun encodeOne(r: ExitDraftRecord): JsonObject = buildJsonObject {
        require(r.backend.length in 1..200 && r.text.length <= 500_000 && r.attachments.size <= 8) { "Unsent draft exceeds recovery limits; keep Droide open" }
        put("backend", r.backend)
        r.session?.let { require(it.length <= 200) { "Draft session metadata exceeds recovery limits" }; put("session", it) }
        put("text", r.text)
        put("attachments", JsonArray(r.attachments.map { a -> buildJsonObject {
            require(a.name.length <= 512 && a.preview.length <= 100_000) { "Attachment preview exceeds recovery limits; no text was shortened" }
            put("name", a.name); put("preview", a.preview)
            if (a.previewTruncated) put("previewTruncated", true)
            a.media?.let { m -> put("media", buildJsonObject {
                require(m.id.length in 1..200 && m.path.length in 1..2_048 && m.name.length <= 512 && m.mediaType.length in 1..200 &&
                    m.bytes >= 0L && m.createdAt >= 0L && m.expiresAt >= m.createdAt) { "Invalid attachment recovery metadata" }
                put("id", m.id); put("path", m.path); put("name", m.name); put("type", m.mediaType)
                put("bytes", m.bytes); put("created", m.createdAt); put("expires", m.expiresAt)
            }) }
        } }))
    }
    fun encode(records: List<ExitDraftRecord>): ByteArray {
        require(records.size <= MAX_RECORDS) { CAPACITY_ERROR }
        // Enforce the byte ceiling incrementally instead of first building an unbounded all-record JSON
        // string. Reject the whole change, never clip any user text.
        val output = java.io.ByteArrayOutputStream()
        fun append(bytes: ByteArray) {
            require(bytes.size <= MAX_BYTES - output.size()) { CAPACITY_ERROR }
            output.write(bytes)
        }
        append("{\"version\":1,\"drafts\":[".toByteArray(Charsets.UTF_8))
        records.forEachIndexed { index, record ->
            if (index != 0) append(byteArrayOf(','.code.toByte()))
            append(utf8(encodeOne(record).toString()))
        }
        append("]}".toByteArray(Charsets.UTF_8))
        return output.toByteArray()
    }
    fun decode(bytes: ByteArray): List<ExitDraftRecord> {
        require(bytes.size <= MAX_BYTES) { "Encrypted draft recovery exceeds its safety limit" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val o = Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid draft recovery")
        require((o["version"] as? JsonPrimitive)?.intOrNull == 1) { "Unsupported draft recovery version" }
        val list = o["drafts"] as? JsonArray ?: error("Invalid draft recovery entries")
        require(list.size <= MAX_RECORDS) { CAPACITY_ERROR }
        return list.map { raw ->
            val r = raw as? JsonObject ?: error("Invalid recovered draft")
            val session = r["session"]?.let { string(r, "session") }
            val attachments = (r["attachments"] as? JsonArray ?: error("Invalid recovered attachments")).also {
                require(it.size <= 8) { "Invalid recovered attachment count" }
            }.map { ar ->
                val a = ar as? JsonObject ?: error("Invalid recovered attachment")
                val m = a["media"]?.let { mr ->
                    val v = mr as? JsonObject ?: error("Invalid media reference")
                    fun number(k: String) = (v[k] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("Invalid media value")
                    ChatMediaRef(string(v,"id"),string(v,"path"),string(v,"name"),string(v,"type"),number("bytes"),number("created"),number("expires"))
                }
                val truncated = a["previewTruncated"]?.let { value ->
                    (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Invalid preview completeness")
                } ?: false
                ExitDraftAttachment(string(a,"name"),string(a,"preview"),m,truncated)
            }
            ExitDraftRecord(string(r,"backend"),session,string(r,"text"),attachments)
        }.also { encode(it) }
    }
    fun merge(previous: List<ExitDraftRecord>, current: List<ExitDraftRecord>): List<ExitDraftRecord> =
        (previous + current.filter { it.text.isNotEmpty() || it.attachments.isNotEmpty() }).distinctBy { it.identity }.also { encode(it) }
}

internal interface ExitDraftCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray
}
private class PlatformExitDraftCipher : ExitDraftCipher {
    private val delegate = KeystoreBlobCipher("droide_exit_drafts_v1")
    override fun encrypt(plain: ByteArray, aad: ByteArray) = delegate.encrypt(plain, aad)
    override fun decrypt(encrypted: ByteArray, aad: ByteArray) = delegate.decrypt(encrypted, aad)
}

/** App-private encrypted draft archive; explicit restore never sends or resumes a prompt. */
internal class ExitDraftStore internal constructor(
    context: Context,
    projectId: String,
    private val cipher: ExitDraftCipher,
    private val directorySyncOverride: ((File) -> Unit)? = null,
) {
    constructor(context: Context, projectId: String) : this(context, projectId, PlatformExitDraftCipher())
    private val applicationContext = context.applicationContext
    private val appFilesDir by lazy { applicationContext.filesDir.canonicalFile }
    private val metadataRoot by lazy { File(appFilesDir, ".droide") }
    private val root by lazy { File(metadataRoot, "exit-drafts") }
    private val project = PathSecurity.safeLeafName(projectId)
    private val file by lazy { File(root, "$project.bin") }
    private val aad = project.toByteArray(Charsets.UTF_8)
    // A second workspace instance for the same project cannot overwrite another instance's archive.
    private val mutex by lazy { locks.getOrPut(file.absolutePath) { Mutex() } }

    suspend fun load(): List<ExitDraftRecord> = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }
    suspend fun preserve(current: List<ExitDraftRecord>): List<ExitDraftRecord> = withContext(Dispatchers.IO) { mutex.withLock {
        val previous = loadLocked()
        val merged = ExitDraftCodec.merge(previous, current)
        if (merged != previous) publishLocked(merged)
        else if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            // Retry durability without serializing/encrypting the same accumulated drafts again.

            FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            syncDirectory(root)
        }
        merged
    } }
    /** Only the exact user-confirmed saved copy is removed; the live composer is never changed. */
    suspend fun remove(identity: String): List<ExitDraftRecord> = withContext(Dispatchers.IO) { mutex.withLock {
        require(identity.matches(Regex("[0-9a-f]{64}"))) { "Invalid saved draft selection" }
        loadLocked().filterNot { it.identity == identity }.also(::publishLocked)
    } }

    private fun publishLocked(records: List<ExitDraftRecord>) {
        val plain = ExitDraftCodec.encode(records)
        val encrypted = cipher.encrypt(plain, aad)
        require(encrypted.size.toLong() in 1L..MAX_ENCRYPTED_BYTES) { "Encrypted draft exceeds recovery capacity" }
        requireStorage(create = true)
        requireRegularOrAbsent(file)
        val temp = File(root, ".${project}-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                val bytes = ByteBuffer.wrap(encrypted)
                while (bytes.hasRemaining()) out.write(bytes)
                out.force(true)
            }
            requireStorage(create = false)
            requireRegularOrAbsent(file)
            check(readRecordsLocked(temp) == records) { "Draft recovery verification failed before publication; original data kept" }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            syncDirectory(root)
            check(loadLocked() == records) { "Draft recovery verification failed; keep Droide open" }
        } finally {
            requireStorage(create = false)
            if (Files.isRegularFile(temp.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(temp.toPath())
        }
    }
    private fun loadLocked(): List<ExitDraftRecord> {
        requireStorage(create = false)
        requireRegularOrAbsent(file)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return readRecordsLocked(file)
    }
    private fun readRecordsLocked(target: File): List<ExitDraftRecord> {
        requireStorage(create = false)
        requireRegularOrAbsent(target)
        val size = target.length()
        require(size in 1L..MAX_ENCRYPTED_BYTES) { "Draft recovery is invalid; original data kept" }
        val encrypted = Files.newInputStream(target.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "Draft recovery changed during reading; keep Droide open" }
                offset += count
            }
            check(input.read() == -1) { "Draft recovery changed during reading; keep Droide open" }
            bytes
        }
        return ExitDraftCodec.decode(cipher.decrypt(encrypted, aad))
    }
    private fun requireStorage(create: Boolean) {
        // Android's /data/data alias may be a system link. Trust its canonical files anchor only.
        check(appFilesDir.isDirectory && !Files.isSymbolicLink(appFilesDir.toPath())) { "Private draft storage is unavailable" }
        requireDirectory(metadataRoot, create)
        requireDirectory(root, create)
    }
    private fun requireDirectory(directory: File, create: Boolean) {
        check(!Files.isSymbolicLink(directory.toPath())) { "Draft recovery path is unsafe; original data kept" }
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Draft recovery path is not a directory" }
        } else if (create) {
            check(directory.mkdir() || Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Cannot create private draft recovery" }
            check(!Files.isSymbolicLink(directory.toPath())) { "Draft recovery path changed during creation" }
            syncDirectory(checkNotNull(directory.parentFile))
        }
    }
    private fun requireRegularOrAbsent(target: File) {
        check(!Files.isSymbolicLink(target.toPath())) { "Draft recovery file is unsafe; original data kept" }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Draft recovery file has an unsafe type" }
        }
    }
    private fun syncDirectory(directory: File) {
        directorySyncOverride?.let { sync -> sync(directory); return }
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Draft recovery sync target is not a directory" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
    private companion object {
        const val MAX_ENCRYPTED_BYTES = ExitDraftCodec.MAX_BYTES + 1024L
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

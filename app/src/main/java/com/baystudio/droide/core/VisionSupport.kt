package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

 
@Serializable
data class AgentImageRef(
    val path: String,
    val sha256: String,
    val mediaType: String,
    val bytes: Long,
    val mediaId: String? = null,
    val expiresAt: Long? = null,
)

 
internal object VisionSupport {
    const val MAX_IMAGES = 4
    const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    private const val REF_TYPE = "droide_image_ref"

    fun providerAcceptsImageUrl(config: AgentConfig): Boolean {
        ModelCapabilityRegistry.allowsImageInput(config.providerId, config.model)?.let { return it }
        return when (config.providerId.lowercase()) {
            "openai", "gemini", "openrouter" -> true
            "anthropic" -> true
            else -> false
        }
    }

    suspend fun stage(workDir: File, imagePaths: List<String>, config: AgentConfig, ownerId: String? = null): List<AgentImageRef> = withContext(Dispatchers.IO) {
        if (imagePaths.isEmpty()) return@withContext emptyList()
        require(providerAcceptsImageUrl(config)) {
            "Provider '${config.providerId}' does not support the required image-input format"
        }
        require(imagePaths.size <= MAX_IMAGES) { "At most $MAX_IMAGES images can be attached per prompt" }
        var total = 0L
        imagePaths.map { relative ->
            val mediaStore = if (ownerId != null || relative.startsWith(".droide/chat-media/blobs/")) ChatMediaStore(workDir) else null
            val managed = if (relative.startsWith(".droide/chat-media/blobs/")) mediaStore?.findByPath(relative) else null
            require(!relative.startsWith(".droide/chat-media/blobs/") || managed != null) { "Chat image is expired or unavailable; choose the image again." }
            val source = if (managed != null) {
                requireNotNull(if (relative == managed.path) mediaStore?.file(managed) else
                    mediaStore?.derivativeFile(managed, relative.substringAfterLast('/'))) { "Chat image is expired or unavailable; choose the image again." }
            } else PathSecurity.resolveWithin(workDir, relative)
            require(source.isFile) { "Image does not exist: $relative" }
            require(source.length() in 1..MAX_IMAGE_BYTES) { "Image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB limit: $relative" }
            val bytes = source.inputStream().use(::readBounded)
            total += bytes.size
            require(total <= MAX_TOTAL_BYTES) { "Attached images exceed ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB total limit" }
            val type = sniff(bytes) ?: throw IllegalArgumentException("Unsupported image format (PNG/JPEG/GIF/WebP only)")
            when {
                managed != null -> {
                    ownerId?.let { mediaStore!!.retain(it, listOf(managed)) }
                    AgentImageRef(relative, sha256(bytes), type, bytes.size.toLong(), managed.id, managed.expiresAt)
                }
                ownerId != null -> {
                    val ref = mediaStore!!.import(ownerId, source.name, type, ByteArrayInputStream(bytes))
                    AgentImageRef(ref.path, sha256(bytes), type, bytes.size.toLong(), ref.id, ref.expiresAt)
                }
                else -> snapshot(workDir, bytes)
            }
        }
    }

    suspend fun import(workDir: File, input: InputStream): AgentImageRef = withContext(Dispatchers.IO) {
        snapshot(workDir, readBounded(input))
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= MAX_IMAGE_BYTES) { "Image exceeds 4 MiB limit" }
            out.write(buffer, 0, count)
        }
        require(out.size() > 0) { "Image is empty" }
        return out.toByteArray()
    }

    @Synchronized private fun snapshot(workDir: File, bytes: ByteArray): AgentImageRef {
            PathSecurity.resolveWithin(workDir, ".droide/vision").apply {
                check(isDirectory || mkdirs()) { "Cannot create vision snapshot directory" }
            }
            val media = sniff(bytes) ?: throw IllegalArgumentException("Unsupported image format (PNG/JPEG/GIF/WebP only)")
            val sha = sha256(bytes)
            val ext = when (media) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/gif" -> "gif"; else -> "webp" }
            val snapshotRel = ".droide/vision/$sha.$ext"
            val snapshot = PathSecurity.resolveWithin(workDir, snapshotRel)
            if (!snapshot.exists()) snapshot.writeBytes(bytes)
            check(snapshot.length() == bytes.size.toLong() && sha256(snapshot.inputStream().use(::readBounded)) == sha) { "Vision snapshot integrity failure" }
            return AgentImageRef(snapshotRel, sha, media, bytes.size.toLong())
    }

    fun userMessage(text: String, images: List<AgentImageRef>, maxTextChars: Int): JsonObject = buildJsonObject {
        put("role", "user")
        if (images.isEmpty()) put("content", text.take(maxTextChars)) else put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text.take(maxTextChars)) })
            images.forEach { ref -> add(buildJsonObject {
                put("type", REF_TYPE)
                put("image_ref", buildJsonObject {
                    put("path", ref.path); put("sha256", ref.sha256); put("media_type", ref.mediaType); put("bytes", ref.bytes)
                    ref.mediaId?.let { put("media_id", it) }
                    ref.expiresAt?.let { put("expires_at", it) }
                })
            }) }
        })
    }

    suspend fun materializeHistory(history: List<JsonObject>, workDir: File): List<JsonObject> = withContext(Dispatchers.IO) {
        val refs = linkedMapOf<Pair<Int, Int>, MaterializedRef>()
        val store by lazy { ChatMediaStore(workDir) }
        history.forEachIndexed { messageIndex, message ->
            (message["content"] as? JsonArray)?.forEachIndexed { partIndex, part ->
                val obj = part as? JsonObject
                if (obj?.get("type")?.jsonPrimitive?.contentOrNull == REF_TYPE) {
                    refs[messageIndex to partIndex] = resolveRef(obj, workDir) { store }
                }
            }
        }
        var count = 0
        var bytes = 0L
        val admitted = refs.entries.toList().asReversed().mapNotNull { (position, resolved) ->
            if (resolved.file == null || count >= MAX_IMAGES || resolved.bytes > MAX_TOTAL_BYTES - bytes) null else {
                count++
                bytes += resolved.bytes
                position
            }
        }.toSet()
        history.mapIndexed { index, message -> materializeMessage(message, index, refs, admitted) }
    }

    private data class MaterializedRef(val path: String, val sha: String, val media: String, val bytes: Long,
        val file: File?, val missingReason: String? = null)

    private fun resolveRef(obj: JsonObject, workDir: File, store: () -> ChatMediaStore): MaterializedRef {
        val ref = obj["image_ref"] as? JsonObject ?: error("Malformed image reference")
        val path = ref["path"]?.jsonPrimitive?.contentOrNull ?: error("Missing image reference path")
        val expectedSha = ref["sha256"]?.jsonPrimitive?.contentOrNull ?: error("Missing image reference digest")
        val media = ref["media_type"]?.jsonPrimitive?.contentOrNull ?: error("Missing image reference media type")
        val bytes = ref["bytes"]?.jsonPrimitive?.longOrNull ?: error("Missing image reference size")
        require(bytes in 1..MAX_IMAGE_BYTES && Regex("[a-f0-9]{64}").matches(expectedSha) &&
            media in setOf("image/png", "image/jpeg", "image/gif", "image/webp")) { "Malformed image reference metadata" }
        val mediaId = ref["media_id"]?.jsonPrimitive?.contentOrNull
        val expiresAt = ref["expires_at"]?.jsonPrimitive?.longOrNull
        if (mediaId == null) {
            require(expiresAt == null) { "Image retention metadata has no managed identity" }
            require(!path.startsWith(".droide/chat-media/blobs/")) { "Managed image alias has no registered media identity" }
            val physical = PathSecurity.resolveWithin(workDir, path)
            require(physical.isFile && physical.length() in 1..MAX_IMAGE_BYTES) { "Vision snapshot unavailable: $path" }
            require(physical.length() == bytes) { "Vision snapshot size mismatch: $path" }
            return MaterializedRef(path, expectedSha, media, bytes, physical)
        }
        require(Regex("m-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(mediaId) &&
            path.startsWith(".droide/chat-media/blobs/$mediaId/") &&
            path.substringAfterLast('/').matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) &&
            path.substringAfter(".droide/chat-media/blobs/$mediaId/").indexOf('/') < 0 && expiresAt != null && expiresAt > 0) {
            "Invalid managed image reference"
        }
        val managed = store().ref(mediaId)
        require(managed == null || managed.expiresAt == expiresAt) { "Image retention metadata mismatch" }
        if (expiresAt <= System.currentTimeMillis()) return MaterializedRef(path, expectedSha, media, bytes, null, "expired after seven days")
        if (managed == null) return MaterializedRef(path, expectedSha, media, bytes, null, "unavailable; its chat-owned copy was removed")
        require(store().findByPath(path)?.id == mediaId) { "Managed image path is not registered" }
        val physical = ChatMediaStoragePaths.physicalPath(workDir, path)
        val available = store().resolvePath(path)
        if (available == null) {
            require(!physical.exists()) { "Vision snapshot size or integrity mismatch: $path" }
            return MaterializedRef(path, expectedSha, media, bytes, null, "unavailable; its chat-owned copy is missing")
        }
        require(available.length() == bytes) { "Vision snapshot size mismatch: $path" }
        return MaterializedRef(path, expectedSha, media, bytes, available)
    }

    private fun materializeMessage(message: JsonObject, messageIndex: Int,
        refs: Map<Pair<Int, Int>, MaterializedRef>, admitted: Set<Pair<Int, Int>>): JsonObject {
        val content = message["content"] as? JsonArray ?: return message
        if (content.none { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == REF_TYPE }) return message
        return JsonObject(message + ("content" to JsonArray(content.mapIndexed { partIndex, part ->
            val obj = part as? JsonObject ?: return@mapIndexed part
            if (obj["type"]?.jsonPrimitive?.contentOrNull != REF_TYPE) return@mapIndexed part
            val position = messageIndex to partIndex
            val ref = refs.getValue(position)
            if (ref.file == null || position !in admitted) return@mapIndexed buildJsonObject {
                put("type", "text")
                val reason = ref.missingReason ?: "omitted from this request to keep the most recent images within the 4-image / 8 MiB visual context budget"
                put("text", "[Image reference ${ref.path}: $reason. Image data is not included; do not claim to inspect its contents.]")
            }
            val bytes = ref.file.inputStream().use(::readBounded)
            require(ref.bytes == bytes.size.toLong()) { "Vision snapshot size mismatch: ${ref.path}" }
            val actualMedia = sniff(bytes) ?: error("Vision snapshot format became invalid: ${ref.path}")
            check(actualMedia == ref.media && sha256(bytes) == ref.sha) { "Vision snapshot integrity mismatch: ${ref.path}" }
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:$actualMedia;base64,${Base64.getEncoder().encodeToString(bytes)}") })
            }
        })))
    }

    internal fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

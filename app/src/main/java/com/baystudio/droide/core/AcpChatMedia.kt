package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.*

/** Capability negotiation is scoped to the existing ACP connection, never a model-name guess. */
internal data class AcpPromptCapabilities(val image: Boolean = false, val embeddedContext: Boolean = false) {
    fun admissionError(media: List<ChatMediaRef>, now: Long = System.currentTimeMillis(), fileBridgeAvailable: Boolean = false): String? = when {
        media.size > 4 -> "Attach at most four items to one CLI prompt."
        media.any { it.expiresAt <= now } -> "An attachment expired. Your draft was kept; choose the file again."
        media.any { it.mediaType.startsWith("image/") || it.mediaType.startsWith("video/") } && !image ->
            "This CLI connection does not advertise image input. Your media draft was kept."
        media.any { !it.mediaType.startsWith("image/") && !it.mediaType.startsWith("video/") } && !embeddedContext && !fileBridgeAvailable ->
            "This CLI connection does not advertise file context input. Your file draft was kept."
        !fileBridgeAvailable && media.filter { !it.mediaType.startsWith("image/") && !it.mediaType.startsWith("video/") && !AcpChatMedia.textFile(it) }
            .sumOf { it.bytes.coerceAtMost(VisionSupport.MAX_TOTAL_BYTES + 1) } > VisionSupport.MAX_TOTAL_BYTES ->
            "Binary CLI file attachments exceed eight MiB. Your draft was kept; use a smaller file or a file excerpt."
        else -> null
    }

    companion object {
        fun fromInitialize(value: JsonObject): AcpPromptCapabilities {
            val capabilities = ((value["agentCapabilities"] as? JsonObject)?.get("promptCapabilities") as? JsonObject)
            fun advertised(name: String) = (capabilities?.get(name) as? JsonPrimitive)?.let {
                !it.isString && it.booleanOrNull == true
            } == true
            return AcpPromptCapabilities(advertised("image"), advertised("embeddedContext"))
        }
    }
}

internal object AcpChatMedia {

    fun prompt(prompt: String, imagePaths: List<String>, files: List<Pair<ChatMediaRef, File>>,
               workDir: File, capabilities: AcpPromptCapabilities, attachmentAccess: AcpAttachmentAccess.Lease? = null,
               media: List<ChatMediaRef> = files.map { it.first }): JsonArray {
        require(prompt.isNotBlank() && prompt.length <= 204_096) { "CLI prompt is empty or too large" }
        require(files.size <= 4) { "Too many CLI file attachments" }
        require(imagePaths.isEmpty() || capabilities.image) { "CLI image input is unavailable" }
        require(files.isEmpty() || capabilities.embeddedContext || attachmentAccess != null) { "CLI file context input is unavailable" }
        require(imagePaths.size <= VisionSupport.MAX_IMAGES) { "Too many visual frames for this CLI prompt" }
        var total = 0L
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", listOf(AgentRequestScope.forCliPrompt(prompt), attachmentAccess?.promptReceipt(media).orEmpty())
                    .filter { it.isNotBlank() }.joinToString("\n\n"))
            })
            imagePaths.forEach { relative ->
                val file = if (relative.startsWith(".droide/chat-media/"))
                    requireNotNull(ChatMediaStore(workDir).resolvePath(relative)) { "CLI image attachment expired or is unavailable" }
                else PathSecurity.resolveWithin(workDir, relative)
                require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..VisionSupport.MAX_IMAGE_BYTES) {
                    "CLI image input is unavailable or too large"
                }
                total += file.length()
                require(total <= VisionSupport.MAX_TOTAL_BYTES) { "CLI visual input exceeds eight MiB" }
                val bytes = file.inputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= VisionSupport.MAX_IMAGE_BYTES) { "CLI image is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(bytes.size.toLong() == file.length() && bytes.size <= VisionSupport.MAX_IMAGE_BYTES) { "CLI image changed while reading" }
                val mime = requireNotNull(VisionSupport.sniff(bytes)) { "Unsupported CLI image format" }
                add(buildJsonObject {
                    put("type", "image"); put("mimeType", mime)
                    put("data", Base64.getEncoder().encodeToString(bytes))
                })
            }
            files.forEach { (ref, file) ->
                // A local CLI reads or promotes the authorized original through Droide's socket. Do not inline
                // base64 archives/documents or pretend an excerpt is the source file.
                if (attachmentAccess != null && (!capabilities.embeddedContext || !textFile(ref))) return@forEach
                require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == ref.bytes) { "CLI file attachment is unavailable" }
                val text = if (textFile(ref)) textExcerpt(file) else null
                val blob = if (text == null) {
                    require(file.length() in 1..(VisionSupport.MAX_TOTAL_BYTES - total)) { "CLI image and binary file input exceeds eight MiB" }
                    val bytes = boundedBytes(file, VisionSupport.MAX_TOTAL_BYTES - total)
                    total += bytes.size
                    Base64.getEncoder().encodeToString(bytes)
                } else null
                add(buildJsonObject {
                    put("type", "resource")
                    put("resource", buildJsonObject {
                        put("uri", "droide-media:${ref.id}"); put("mimeType", ref.mediaType)
                        if (text != null) put("text", "Attached file: ${ref.name}\n$text") else put("blob", requireNotNull(blob))
                    })
                })
            }
        }
    }

    fun typedOutput(content: JsonElement?): JsonObject? {
        val block = content as? JsonObject ?: return null
        return block.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull in setOf("image", "resource_link") }
    }

    fun localResource(block: JsonObject, mapper: WorkspacePathMapper): File? {
        if ((block["type"] as? JsonPrimitive)?.contentOrNull != "resource_link") return null
        val uri = (block["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
        if (parsed.scheme != "file" || !parsed.rawAuthority.isNullOrBlank() || parsed.rawQuery != null || parsed.rawFragment != null) return null
        return mapper.uriToLocal(uri)
    }

    fun textFile(ref: ChatMediaRef): Boolean = ref.mediaType.startsWith("text/") ||
        ref.mediaType in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml") ||
        ref.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "xml", "yaml", "yml", "csv", "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "rs", "go", "c", "cpp", "h", "hpp", "sh", "html", "css", "toml", "gradle")

    private fun boundedBytes(file: File, limit: Long): ByteArray = file.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "CLI file attachment is too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun textExcerpt(file: File): String = file.inputStream().use { input ->
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        InputStreamReader(input, decoder).use { reader ->
            val chars = CharArray(12_001)
            var count = 0
            while (count < chars.size) {
                val read = reader.read(chars, count, chars.size - count)
                if (read < 0) break
                count += read
            }
            val truncated = count > 12_000
            var end = minOf(count, 12_000)
            if (end > 0 && chars[end - 1].isHighSurrogate()) end--
            String(chars, 0, end) + if (truncated) "\n[Attachment excerpt truncated at 12,000 characters; the complete file remains in chat.]" else ""
        }
    }

    fun decodeImage(block: JsonObject): Pair<String, ByteArray> {
        require((block["type"] as? JsonPrimitive)?.contentOrNull == "image") { "Not an ACP image block" }
        val data = (block["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("ACP image has no data")
        val mime = (block["mimeType"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("ACP image has no media type")
        require(data.length <= ((VisionSupport.MAX_IMAGE_BYTES + 2) / 3 * 4)) { "ACP image is too large" }
        val bytes = Base64.getDecoder().decode(data)
        require(bytes.size.toLong() in 1..VisionSupport.MAX_IMAGE_BYTES && VisionSupport.sniff(bytes) == mime) { "ACP image format is invalid" }
        return mime to bytes
    }
}

package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** HTTPS-origin resources intercepted by WebView; no listening server or file:// access. */
internal class WorkspaceHtmlPreview(projectRoot: File) {
    private val root = projectRoot.canonicalFile
    private val originId = MessageDigest.getInstance("SHA-256").digest(root.path.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    val host = "droide-$originId.preview.invalid"

    data class Resource(
        val mime: String,
        val encoding: String?,
        val status: Int,
        val reason: String,
        val headers: Map<String, String>,
        val body: InputStream,
    )

    fun owns(raw: String): Boolean = runCatching { URI(raw).host.equals(host, ignoreCase = true) }.getOrDefault(false)

    fun urlFor(path: String): String {
        require(isHtml(path)) { "Open an HTML file to preview it" }
        val url = URI("https", null, host, -1, "/$path", null, null).toASCIIString()
        requirePage(url)
        return url
    }

    fun requirePage(raw: String) {
        val file = resolve(raw)
        require(file.isFile) { "Preview file does not exist" }
        require(file.length() <= MAX_RESOURCE_BYTES) { "Preview resource exceeds 64 MiB" }
    }

    fun open(raw: String, method: String = "GET", range: String? = null): Resource {
        if (method !in setOf("GET", "HEAD")) return error(405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD"))
        val file = try { resolve(raw) } catch (_: Exception) { return error(403, "Forbidden") }
        if (!file.isFile) return error(404, "Not Found")
        val length = file.length()
        if (length > MAX_RESOURCE_BYTES) return error(413, "Content Too Large")
        val span = if (range == null) null else parseRange(range, length)
            ?: return error(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length"))
        val start = span?.first ?: 0L
        val count = span?.let { it.last - it.first + 1L } ?: length
        val mime = MIME[file.extension.lowercase()] ?: "application/octet-stream"
        val headers = linkedMapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Accept-Ranges" to "bytes", "Content-Length" to count.toString())
        if (span != null) headers["Content-Range"] = "bytes ${span.first}-${span.last}/$length"
        val body = if (method == "HEAD") ByteArrayInputStream(ByteArray(0)) else try {
            val stream = file.inputStream()
            try {
                stream.channel.position(start)
                BoundedInput(stream, count)
            } catch (failure: Exception) { stream.close(); throw failure }
        } catch (_: Exception) { return error(404, "Not Found") }
        return Resource(mime, if (mime.startsWith("text/") || mime in setOf("application/javascript", "application/json", "image/svg+xml")) "UTF-8" else null,
            if (span == null) 200 else 206, if (span == null) "OK" else "Partial Content", headers, body)
    }

    private fun resolve(raw: String): File {
        val uri = URI(raw)
        require(uri.scheme.equals("https", true) && owns(raw) && uri.userInfo == null && uri.port in setOf(-1, 443))
        val path = requireNotNull(uri.path).removePrefix("/")
        require(path.isNotEmpty() && path.length <= 4096 && '\\' !in path && path.none { it.code < 32 || it.code == 127 })
        require(!SensitivePathPolicy.isSensitive(path)) { "Private workspace metadata cannot be previewed" }
        val file = PathSecurity.resolveWithin(root, path)
        val canonicalRelative = file.relativeTo(root).invariantSeparatorsPath
        require(!SensitivePathPolicy.isSensitive(canonicalRelative)) { "Private workspace metadata cannot be previewed" }
        return file
    }

    private fun error(status: Int, reason: String, extra: Map<String, String> = emptyMap()): Resource {
        val bytes = "$status $reason".toByteArray(Charsets.UTF_8)
        return Resource("text/plain", "UTF-8", status, reason,
            mapOf("Cache-Control" to "no-store", "Content-Length" to bytes.size.toString()) + extra, ByteArrayInputStream(bytes))
    }

    private fun parseRange(raw: String, length: Long): LongRange? {
        if (length <= 0L || !raw.startsWith("bytes=")) return null
        val bounds = raw.removePrefix("bytes=").split('-')
        if (bounds.size != 2) return null
        if (bounds[0].isEmpty()) {
            val count = bounds[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (length - minOf(count, length))..(length - 1L)
        }
        val start = bounds[0].toLongOrNull()?.takeIf { it in 0 until length } ?: return null
        val end = if (bounds[1].isEmpty()) length - 1L else bounds[1].toLongOrNull()?.takeIf { it >= start } ?: return null
        return start..minOf(end, length - 1L)
    }

    private class BoundedInput(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining == 0L) return -1
            return super.read().also { if (it >= 0) remaining-- }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            return `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
        }
        override fun skip(count: Long): Long = `in`.skip(minOf(count.coerceAtLeast(0L), remaining)).also { remaining -= it }
        override fun available(): Int = minOf(`in`.available().toLong(), remaining).toInt()
        override fun markSupported(): Boolean = false
    }

    companion object {
        private const val MAX_RESOURCE_BYTES = 64L * 1024L * 1024L
        fun isHtml(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("html", "htm")
        private val MIME = mapOf("html" to "text/html", "htm" to "text/html", "css" to "text/css",
            "js" to "application/javascript", "mjs" to "application/javascript", "json" to "application/json",
            "txt" to "text/plain", "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4",
            "mp4" to "video/mp4", "webm" to "video/webm", "woff" to "font/woff", "woff2" to "font/woff2",
            "wasm" to "application/wasm")
    }
}

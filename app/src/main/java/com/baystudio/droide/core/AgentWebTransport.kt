package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns

internal object AgentWebUrl {
    fun normalize(raw: String, keepFragment: Boolean = false): String {
        require(raw.length in 1..8_192) { "Web URL must be between 1 and 8192 characters" }
        val parsed = raw.trim().toHttpUrlOrNull() ?: error("Invalid HTTP(S) web URL")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Credentials in web URLs are not allowed" }
        // Public legacy links are attempted over HTTPS; Android's cleartext policy stays intact.
        val url = parsed.newBuilder().apply {
            if (parsed.scheme == "http") {
                require(parsed.port == 80 || parsed.port == 443) { "Use an explicit HTTPS URL for nonstandard ports" }
                scheme("https"); port(443)
            }
            if (!keepFragment) fragment(null)
        }.build()
        require(url.toString().length <= 8_192) { "Encoded web URL exceeds 8192 characters" }
        NetworkSecurity.validatePublicHttpsUrl(url.newBuilder().fragment(null).build().toString())
        return url.toString()
    }

    fun resolve(base: String, link: String): String? = runCatching {
        val resolved = base.toHttpUrlOrNull()?.resolve(link.trim()) ?: return null
        normalize(resolved.toString(), keepFragment = true)
    }.getOrNull()
}

internal data class AgentWebResponse(
    val status: Int,
    val bytes: ByteArray,
    val finalUrl: String,
    val contentType: String = "text/html",
    val charset: String = "UTF-8",
    val truncated: Boolean = false,
    val retryAfter: String? = null,
) {
    fun text(): String = bytes.toString(runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8))
    val isPdf: Boolean get() = bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())
    val isImage: Boolean get() = VisionSupport.sniff(bytes) != null
}

/** The callback runs before DNS/network I/O on every hop, including redirects. */
internal fun interface AgentWebTransport {
    suspend fun get(url: String, beforeRequest: suspend (String) -> Unit): AgentWebResponse

    /** Text batches never promote a transfer into a PDF/image download or renderer job. */
    suspend fun getText(url: String, beforeRequest: suspend (String) -> Unit): AgentWebResponse =
        get(url, beforeRequest).also { response ->
            require(isWebTextMedia(response.contentType) && !response.isPdf && !response.isImage) {
                WEB_TEXT_ONLY_MESSAGE
            }
        }
}

private const val WEB_TEXT_ONLY_MESSAGE =
    "This source is not a text document. Open it individually if its PDF or image content is needed."

private fun isWebTextMedia(media: String): Boolean = media.isBlank() ||
    media.startsWith("text/", true) || media.contains("html", true) ||
    media.contains("json", true) || media.contains("xml", true) || media.contains("javascript", true)

internal class SafeAgentWebTransport(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
    private val resolveTarget: (String) -> ValidatedNetworkTarget = NetworkSecurity::validatePublicHttpsTarget,
    private val timeoutMs: Long = 30_000,
) : AgentWebTransport {

    override suspend fun get(url: String, beforeRequest: suspend (String) -> Unit): AgentWebResponse =
        fetch(url, beforeRequest, textOnly = false)

    override suspend fun getText(url: String, beforeRequest: suspend (String) -> Unit): AgentWebResponse =
        fetch(url, beforeRequest, textOnly = true)

    private suspend fun fetch(url: String, beforeRequest: suspend (String) -> Unit,
        textOnly: Boolean): AgentWebResponse =
        withAgentWebDeadline(timeoutMs) { deadline -> withContext(Dispatchers.IO) {
            var next = AgentWebUrl.normalize(url)
            val seen = mutableSetOf<String>()
            repeat(6) {
                currentCoroutineContext().ensureActive()
                check(seen.add(next)) { "Web redirect loop" }
                deadline.withoutTime { beforeRequest(next) }
                currentCoroutineContext().ensureActive()
                val target = resolveTarget(next)
                val client = baseClient.newBuilder().dns(pinnedRouteDns(target, baseClient)).build()
                val request = Request.Builder().url(target.url)
                    .header("User-Agent", "Droide/1.00 (+public web research)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml,text/plain,application/json,application/pdf,image/*;q=0.8,*/*;q=0.2")
                    .get().build()
                val result = client.newCall(request).consumeCancellableResponse { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location") ?: error("Web redirect has no Location")
                        val resolved = target.url.toHttpUrlOrNull()?.resolve(location)?.toString()
                            ?: error("Invalid web redirect")
                        // Redirects may not downgrade transport. Only user-supplied legacy URLs are upgraded.
                        require(resolved.toHttpUrlOrNull()?.scheme == "https") { "Insecure web redirect is blocked" }
                        next = AgentWebUrl.normalize(resolved)
                        null
                    } else {
                        val body = response.body
                        val type = body?.contentType()
                        val media = type?.let { "${it.type}/${it.subtype}" }.orEmpty()
                        require(!textOnly || isWebTextMedia(media)) { WEB_TEXT_ONLY_MESSAGE }
                        var limit = if (textOnly) 2 * 1024 * 1024
                            else if (media == "application/pdf") 8 * 1024 * 1024
                            else if (media.startsWith("image/")) 4 * 1024 * 1024 else 2 * 1024 * 1024
                        val out = ByteArrayOutputStream()
                        var truncated = false
                        var typeSniffed = false
                        body?.byteStream()?.use { input ->
                            val buffer = ByteArray(8_192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                                if (count < 0) break
                                val retained = minOf(count, limit - out.size())
                                out.write(buffer, 0, retained)
                                if (retained < count) truncated = true
                                if (!typeSniffed) {
                                    val head = out.toByteArray()
                                    if (head.size >= 5 && head.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
                                        require(!textOnly) { WEB_TEXT_ONLY_MESSAGE }
                                        limit = 8 * 1024 * 1024; typeSniffed = true
                                    } else if (VisionSupport.sniff(head) != null) {
                                        require(!textOnly) { WEB_TEXT_ONLY_MESSAGE }
                                        limit = 4 * 1024 * 1024; typeSniffed = true
                                    } else if (head.size >= 12) typeSniffed = true
                                }
                                if (truncated) break
                            }
                        }
                        val bytes = out.toByteArray()
                        AgentWebResponse(response.code, bytes, target.url,
                            media, type?.charset(Charsets.UTF_8)?.name() ?: "UTF-8", truncated, response.header("Retry-After"))
                    }
                }
                if (result != null) return@withContext result
            }
            error("Too many web redirects")
        } }

    internal companion object {
        /** A configured OS/client proxy is a transport route, never a web destination supplied by a model. */
        fun pinnedRouteDns(target: ValidatedNetworkTarget, client: OkHttpClient, proxyDns: Dns = Dns.SYSTEM): Dns {
            val proxies = client.proxy?.let(::listOf)
                ?: client.proxySelector.select(URI(target.url)).orEmpty()
            val routeHosts = proxies.filter { it.type() == Proxy.Type.HTTP }
                .mapNotNull { (it.address() as? InetSocketAddress)?.hostString?.lowercase() }.toSet()
            val strict = NetworkSecurity.pinnedDns(target)
            return object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = when {
                    hostname.equals(target.host, true) -> target.addresses
                    hostname.lowercase() in routeHosts -> proxyDns.lookup(hostname)
                    else -> strict.lookup(hostname)
                }
            }
        }
    }
}

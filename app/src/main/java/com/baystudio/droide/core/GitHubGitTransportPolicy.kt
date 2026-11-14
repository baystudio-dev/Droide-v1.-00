package com.baystudio.droide.core

import java.net.URI
import java.net.URL








object GitHubGitTransportPolicy {
    internal fun isGitHubRequest(url: URL): Boolean =
        url.protocol.equals("https", true) && url.host.equals("github.com", true) &&
            url.port in listOf(-1, 443) && url.userInfo == null && url.ref == null &&
            url.toExternalForm().none { it == '\r' || it == '\n' || it == '\u0000' }

    fun isGitHubHttps(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        uri.scheme.equals("https", true) &&
            uri.userInfo == null &&
            uri.host.equals("github.com", true) &&
            uri.port in listOf(-1, 443) &&
            uri.query == null &&
            uri.fragment == null
    }.getOrDefault(false)

    fun mayUseConnectedAccount(urls: Collection<String>): Boolean {
        val normalized = urls.map(String::trim).filter(String::isNotEmpty).distinct()
        return normalized.isNotEmpty() && normalized.all(::isGitHubHttps)
    }
}

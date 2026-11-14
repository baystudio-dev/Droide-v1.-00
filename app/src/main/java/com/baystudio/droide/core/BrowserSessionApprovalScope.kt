package com.baystudio.droide.core

import java.net.URI
import java.util.Locale

/** Session-only browser capabilities. Parsing never performs DNS or creates wildcard grants. */
internal object BrowserSessionApprovalScope {
    private const val READ_PREFIX = "browser-read-origin:"

    fun canonicalOrigin(raw: String): String? {
        if (raw.length !in 1..8_192 || raw.any { it.code < 32 || it.code == 127 }) return null
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
            if (!uri.isAbsolute || uri.isOpaque || scheme !in setOf("http", "https") || uri.rawUserInfo != null) return@runCatching null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
            if (!AgentRepairCompletionPolicy.isLiteralLoopback(host)) return@runCatching null
            val port = if (uri.port == -1) {
                if (scheme == "http") 80 else 443
            } else uri.port
            if (port !in 1..65535) return@runCatching null
            // Keep literal hosts distinct: localhost, 127.0.0.1 and [::1] are separate grants.
            val authorityHost = if (host == "::1") "[::1]" else host
            "$scheme://$authorityHost:$port"
        }.getOrNull()
    }

    /** Only browser callers pass this identity; normal webfetch approvals remain exact URLs. */
    fun browserReadGrantResource(raw: String): String = canonicalOrigin(raw)?.let { READ_PREFIX + it } ?: raw
}

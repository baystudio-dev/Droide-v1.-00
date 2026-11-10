package com.baystudio.droide.core

import java.net.URI
import java.util.Locale

/** Explicit, in-memory consent for one origin in one agent session. Never a policy ALLOW. */
internal object BrowserSiteSession {
    const val ACTION = "browser_site_session"
    const val DESCRIPTION = "Allow reading and controlling this site for this session, including filling/submitting forms and sharing screenshots/console with embedded content. Visiting another site requires approval. Stop the agent or close its session to revoke access."

    fun origin(raw: String): String? = runCatching {
        if (raw.length !in 1..8192 || raw.any { it.code < 32 || it.code == 127 }) return null
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.isOpaque || uri.rawUserInfo != null || scheme !in setOf("https", "http")) return null
        if (scheme == "http" && !AgentRepairCompletionPolicy.isLiteralLoopback(host)) return null
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        if (port !in 1..65535) return null
        "$scheme://$host:$port"
    }.getOrNull()

    fun resource(url: String): String? = origin(url)?.let {
        "browser-site-v1:$it:${BrowserOutputConsent.CAPTURE_CONTRACT}"
    }
}

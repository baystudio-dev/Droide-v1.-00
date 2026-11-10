package com.baystudio.droide.core

import java.net.URI

/** Presentation only. Permission decisions and grants still use ApprovalRequest.action/resource. */
data class BrowserApprovalContext(
    val operation: String,
    val url: String,
    val target: String? = null,
    val reason: String,
    val outputScope: String? = null,
    val sessionAccess: Boolean = false,
) {
    val outputDisclosure: String? get() = outputScope?.let { BrowserOutputConsent.DISCLOSURE }
    val targetPreview: String? get() = target?.let { it.take(200) + if (it.length > 200) "… (target truncated)" else "" }
    val pagePreview: String get() = url.take(8_192) + if (url.length > 8_192) "… (URL truncated)" else ""

    val site: String get() = runCatching {
        if (url.length !in 1..8_192) return@runCatching null
        val uri = URI(url)
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) return@runCatching null
        if (uri.port !in -1..65535) return@runCatching null
        val host = uri.host ?: return@runCatching null
        "${uri.scheme}://$host${if (uri.port == -1) "" else ":${uri.port}"}"
    }.getOrNull() ?: "Site unavailable; see details"
}

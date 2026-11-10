// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb.cert.platform


internal fun formatDefaultDeviceName(loginName: String?, hostName: String?): String {
    fun normalize(component: String?): String {
        return component
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }

    return "${normalize(loginName)}@${normalize(hostName)}"
}

// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb.cert.platform

import android.os.Build

fun defaultDeviceName(software: String? = null): String {
    software
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    val userName = System.getProperty("user.name")
        ?: System.getenv("USER")
    val hostName = System.getenv("HOSTNAME")
        ?: Build.HOST
        ?: Build.DEVICE
    return formatDefaultDeviceName(userName, hostName)
}

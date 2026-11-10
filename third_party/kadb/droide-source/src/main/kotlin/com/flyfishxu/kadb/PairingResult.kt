// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb

/** Identity received inside the authenticated pairing exchange, never from mDNS or a host address. */
data class PairingResult(val deviceGuid: String)

internal fun decodePairedDeviceGuid(type: Byte, data: ByteArray): String {
    require(type.toInt() == 1) { "Pairing peer did not return a device GUID" }
    val end = data.indexOf(0)
    require(end in 1..255 && data.drop(end).all { it == 0.toByte() }) { "Invalid paired device GUID" }
    require(data.take(end).all { it.toInt() in 33..126 }) { "Invalid paired device GUID" }
    val guid = data.copyOf(end).toString(Charsets.US_ASCII)
    require(guid.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}"))) { "Invalid paired device GUID" }
    return guid
}

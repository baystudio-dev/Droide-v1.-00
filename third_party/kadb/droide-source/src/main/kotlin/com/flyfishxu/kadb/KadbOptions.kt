// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb

/**
 * Instance-scoped connection options that affect Kadb's host-side protocol behavior.
 */
data class KadbOptions(
    /**
     * Controls whether the host advertises the `delayed_ack` feature in CNXN.
     */
    val delayedAckMode: DelayedAckMode = DelayedAckMode.AOSP_DEFAULT,
    /** Reject legacy AUTH/plain CNXN when connecting a Wireless Debugging TLS endpoint. */
    val requireTls: Boolean = false
)

/**
 * Policy for host-side `delayed_ack` feature advertisement.
 */
enum class DelayedAckMode {
    /** Follow the platform default: JVM honors `ADB_BURST_MODE=1`, Android keeps it disabled. */
    AOSP_DEFAULT,

    /** Always advertise `delayed_ack` for this Kadb instance. */
    ENABLED,

    /** Never advertise `delayed_ack` for this Kadb instance. */
    DISABLED
}

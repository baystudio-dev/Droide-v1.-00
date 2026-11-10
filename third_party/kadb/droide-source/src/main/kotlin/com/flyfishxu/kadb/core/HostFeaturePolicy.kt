// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb.core

import com.flyfishxu.kadb.DelayedAckMode

internal fun shouldAdvertiseDelayedAck(mode: DelayedAckMode): Boolean = when (mode) {
    DelayedAckMode.AOSP_DEFAULT -> aospDefaultDelayedAckEnabled()
    DelayedAckMode.ENABLED -> true
    DelayedAckMode.DISABLED -> false
}


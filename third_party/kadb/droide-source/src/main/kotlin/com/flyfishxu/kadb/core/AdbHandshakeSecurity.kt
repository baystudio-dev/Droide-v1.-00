// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb.core

import java.io.IOException

/** Droide derivative: an advertised service type is not proof that encryption was negotiated. */
internal class AdbHandshakeSecurity(private val required: Boolean) {
    var encrypted: Boolean = false
        private set
    private var started = false

    fun before(command: Int) {
        if (command == AdbProtocol.CMD_STLS) {
            if (started) throw IOException("Repeated ADB TLS upgrade")
            started = true
        } else if (required && !encrypted &&
            (command == AdbProtocol.CMD_AUTH || command == AdbProtocol.CMD_CNXN)) {
            throw IOException("Wireless Debugging requires an encrypted TLS connection")
        }
    }

    fun handshakeCompleted() { check(started); encrypted = true }
}

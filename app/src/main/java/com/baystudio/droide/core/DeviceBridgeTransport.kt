package com.baystudio.droide.core

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.KadbConnectionControl
import com.flyfishxu.kadb.KadbOptions
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.shell.AdbShellPacket
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow
import okio.Buffer

internal interface DeviceBridgeTransport : Closeable {
    val kadb: Kadb?
    val open: Boolean
    val encrypted: Boolean
    fun probe(command: String, outputLimit: Int): BridgeShellResult
}

internal interface DeviceBridgeDiscovery : Closeable {
    val state: StateFlow<MdnsDiscoveryState>
    fun start()
}

internal data class DeviceBridgeTiming(
    val connectionMs: Long = 30_000L,
    val probeMs: Long = 3_000L,
    val discoveryMs: Long = 8_000L,
    val pollMs: Long = 200L,
) {
    init { require(connectionMs > 0 && probeMs > 0 && discoveryMs > 0 && pollMs > 0) }
}

internal class DeviceBridgeDependencies(
    val configureIdentity: () -> Unit,
    val create: (MdnsEndpoint) -> DeviceBridgeTransport,
    val pair: (MdnsEndpoint, String, KadbConnectionControl) -> String,
    val discovery: () -> DeviceBridgeDiscovery,
    val timing: DeviceBridgeTiming = DeviceBridgeTiming(),
    val resetIdentity: () -> Unit = { KadbCert.clear() },
) {
    companion object {
        fun live(context: Context) = DeviceBridgeDependencies(
            configureIdentity = {
                KadbCert.configure(EncryptedKadbIdentityStore(context), KadbCertPolicy(autoHealInvalidPrivateKey = false))
            },
            create = { endpoint -> KadbDeviceBridgeTransport(Kadb.create(endpoint.host, endpoint.port,
                connectTimeout = 8_000, socketTimeout = 0,
                options = KadbOptions(requireTls = DeviceBridgeConnectionPolicy.requiresTls(endpoint)))) },
            pair = { endpoint, code, control ->
                Kadb.pairWithResult(endpoint.host, endpoint.port, code, "Droide", control).deviceGuid
            },
            discovery = {
                val delegate = KadbMdnsAndroid(context)
                object : DeviceBridgeDiscovery {
                    override val state = delegate.state
                    override fun start() = delegate.start()
                    override fun close() = delegate.close()
                }
            },
        )
    }
}

private class KadbDeviceBridgeTransport(override val kadb: Kadb) : DeviceBridgeTransport {
    override val open get() = kadb.connectionCheck()
    override val encrypted get() = kadb.isTlsConnection()
    override fun close() = kadb.cancel()

    override fun probe(command: String, outputLimit: Int): BridgeShellResult {
        val stdout = Buffer()
        val stderr = Buffer()
        var total = 0
        fun append(bytes: ByteArray, error: Boolean) {
            total = Math.addExact(total, bytes.size)
            check(total <= outputLimit) { "Device connection probe exceeded output limit" }
            (if (error) stderr else stdout).write(bytes)
        }
        val exit = if (kadb.supportsFeature("shell_v2")) {
            kadb.openShell(command).use { stream ->
                var result = 1
                while (true) when (val packet = stream.read(outputLimit - total)) {
                    is AdbShellPacket.StdOut -> append(packet.payload, false)
                    is AdbShellPacket.StdError -> append(packet.payload, true)
                    is AdbShellPacket.Exit -> { result = packet.payload.firstOrNull()?.toUByte()?.toInt() ?: 1; break }
                }
                result
            }
        } else {
            kadb.open("shell:$command").use { stream ->
                val buffer = Buffer()
                while (true) {
                    val size = stream.source.read(buffer, 4_096)
                    if (size == -1L) break
                    append(buffer.readByteArray(), false)
                }
            }
            0
        }
        return BridgeShellResult(exit, stdout.readUtf8(), stderr.readUtf8())
    }
}

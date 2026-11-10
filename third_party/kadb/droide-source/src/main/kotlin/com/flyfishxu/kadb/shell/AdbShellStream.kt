// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb.shell

import com.flyfishxu.kadb.stream.AdbStream
import java.io.IOException
import okio.BufferedSource
import kotlin.Throws

class AdbShellStream(
    private val stream: AdbStream
) : AutoCloseable {
    @Throws(IOException::class)
    fun readAll(): AdbShellResponse {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        while (true) {
            when (val packet = read()) {
                is AdbShellPacket.Exit -> {
                    // Shell v2 exit code is stored as a single byte.
                    // https://android.googlesource.com/platform/system/core/+/c0e6e40/adb/shell_service.cpp
                    val exitCode = packet.payload[0].toUByte().toInt()
                    return AdbShellResponse(output.toString(), errorOutput.toString(), exitCode)
                }

                is AdbShellPacket.StdOut -> {
                    output.append(String(packet.payload))
                }

                is AdbShellPacket.StdError -> {
                    errorOutput.append(String(packet.payload))
                }
            }
        }
    }

    @Throws(IOException::class)
    fun read(): AdbShellPacket = read(Int.MAX_VALUE)

    @Throws(IOException::class)
    fun read(maxOutputPayloadBytes: Int): AdbShellPacket =
        readShellPacket(stream.source, maxOutputPayloadBytes)

    @Throws(IOException::class)
    fun write(string: String) {
        write(string.toByteArray())
    }

    @Throws(IOException::class)
    fun write(input: ByteArray) {
        // Shell v2 stdin data packet id.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/shell_protocol.h#46
        write(AdbShellPacketV2.ID_STDIN, input)
    }

    @Throws(IOException::class)
    fun closeStdin() {
        // Shell v2 close-stdin control packet id.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/shell_protocol.h#51
        write(AdbShellPacketV2.ID_CLOSE_STDIN)
    }

    @Throws(IOException::class)
    fun resize(rows: Int, cols: Int, xPixels: Int = 0, yPixels: Int = 0) {
        // AOSP shell daemon parses size payload as "<rows>x<cols>,<xpixels>x<ypixels>".
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/daemon/shell_service.cpp#687
        val payload = "${rows}x${cols},${xPixels}x${yPixels}".encodeToByteArray()
        write(AdbShellPacketV2.ID_WINDOW_SIZE_CHANGE, payload)
    }

    @Throws(IOException::class)
    fun write(id: Int, payload: ByteArray? = null) {
        stream.sink.apply {
            writeByte(id)
            writeIntLe(payload?.size ?: 0)
            if (payload != null) write(payload)
            flush()
        }
    }

    override fun close() {
        stream.close()
    }

}

/** Validate the complete header before allocating/buffering a peer-declared payload. */
internal fun readShellPacket(source: BufferedSource, maxOutputPayloadBytes: Int): AdbShellPacket {
    require(maxOutputPayloadBytes >= 0)
    val id = source.readByte().toInt()
    check(id == AdbShellPacketV2.ID_STDOUT || id == AdbShellPacketV2.ID_STDERR || id == AdbShellPacketV2.ID_EXIT) {
        "Invalid shell packet id: $id"
    }
    val length = source.readIntLe()
    check(length >= 0) { "Shell packet length must be >= 0: $length" }
    check(id != AdbShellPacketV2.ID_EXIT || length == 1) { "Shell exit packet must have length 1" }
    check(id == AdbShellPacketV2.ID_EXIT || length <= maxOutputPayloadBytes) {
        "Shell packet exceeds remaining output limit"
    }
    val payload = source.readByteArray(length.toLong())
    return when (id) {
        AdbShellPacketV2.ID_STDOUT -> AdbShellPacket.StdOut(payload)
        AdbShellPacketV2.ID_STDERR -> AdbShellPacket.StdError(payload)
        else -> AdbShellPacket.Exit(payload)
    }
}

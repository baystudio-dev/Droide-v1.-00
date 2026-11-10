package com.baystudio.droide.core

import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.CodingErrorAction

/** Bounded serialization only, not a JSON extractor or request builder. */

internal object OwnedProviderReplayWriter {

    fun writePublicText(
        session: OwnedResponseContentStore.Session,
        source: OwnedNormalizedResponse.PublicText,
        output: OutputStream,
        checkCancelled: () -> Unit = {},
    ) {
        checkCancelled()
        source.reference.verify(session)
        session.openBytes(source.reference.handle).use { input -> requireUtf8(input, checkCancelled) }
        session.openBytes(source.reference.handle).use { input ->
            checkCancelled()
            output.write('"'.code)
            val buffer = ByteArray(8192)
            while (true) {
                checkCancelled()
                val n = input.read(buffer); if (n < 0) break
                check(n > 0) { "Provider content read made no progress" }
                var span = 0
                for (index in 0 until n) {
                    val value = buffer[index].toInt() and 255
                    if (value == 34 || value == 92 || value < 32) {
                        if (index > span) output.write(buffer, span, index - span)
                        if (value == 34 || value == 92) { output.write('\\'.code); output.write(value) }
                        else {
                            output.write(ESCAPE_PREFIX)
                            output.write(HEX[value shr 4].code); output.write(HEX[value and 15].code)
                        }
                        span = index + 1
                    }
                }
                if (span < n) output.write(buffer, span, n - span)
            }
            checkCancelled(); output.write('"'.code)
        }
    }

    /** Writes the EXACT previously admitted native object, including order/signature bytes. */

    fun writePrivateObject(
        session: OwnedResponseContentStore.Session,
        source: OwnedNormalizedResponse.PrivateReplay,
        target: ProviderReplayTarget,
        output: OutputStream,
        checkCancelled: () -> Unit = {},
    ) {
        checkCancelled()
        require(source.target == target) { "Private replay provider/model/endpoint does not match" }
        source.reference.verify(session)
        session.openBytes(source.reference.handle).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                checkCancelled()
                val n = input.read(buffer); if (n < 0) break
                check(n > 0) { "Provider content read made no progress" }
                output.write(buffer, 0, n)
            }
        }
    }

    private fun requireUtf8(input: InputStream, checkCancelled: () -> Unit) {
        val reader = InputStreamReader(input, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
        val chars = CharArray(4096)
        while (true) { checkCancelled(); val n = reader.read(chars); if (n < 0) break; check(n > 0) { "UTF-8 reader made no progress" } }
    }
    private val ESCAPE_PREFIX = byteArrayOf(92, 117, 48, 48)
    private const val HEX = "0123456789abcdef"
}

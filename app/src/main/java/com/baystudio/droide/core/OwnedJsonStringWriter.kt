package com.baystudio.droide.core

import java.io.InputStream
import java.io.OutputStream

/** Encodes an already validated UTF-8 byte source as ONE JSON string without materializing it. */

internal object OwnedJsonStringWriter {
    fun writeValidatedUtf8(input: InputStream, output: OutputStream, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        output.write('"'.code)
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            check(n > 0) { "JSON string source made no progress" }
            var span = 0
            for (index in 0 until n) {
                val value = buffer[index].toInt() and 255
                if (value == 34 || value == 92 || value < 32) {
                    if (index > span) output.write(buffer, span, index - span)
                    if (value == 34 || value == 92) { output.write(92); output.write(value) }
                    else {
                        output.write(ESCAPE_PREFIX)
                        output.write(HEX[value shr 4].code); output.write(HEX[value and 15].code)
                    }
                    span = index + 1
                }
            }
            if (span < n) output.write(buffer, span, n - span)
        }
        checkCancelled()
        output.write('"'.code)
    }
    private val ESCAPE_PREFIX = byteArrayOf(92, 117, 48, 48)
    private const val HEX = "0123456789abcdef"
}

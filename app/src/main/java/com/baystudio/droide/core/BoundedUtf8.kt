package com.baystudio.droide.core

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded working buffers; the returned String still necessarily occupies memory. */
internal object BoundedUtf8 {
    private const val BUFFER_CHARS = 8_192

    /** Counts exactly the UTF-8 bytes written by [write], including replacement of lone surrogates. */
    suspend fun encodedSize(text: String, maxBytes: Long, onLimitExceeded: () -> Nothing): Long {
        require(maxBytes >= 0L)
        val context = currentCoroutineContext()
        val replacementBytes = Charsets.UTF_8.newEncoder().replacement().size
        var bytes = 0L
        var i = 0
        var nextCancellationCheck = 0
        while (i < text.length) {
            if (i >= nextCancellationCheck) {
                context.ensureActive()
                nextCancellationCheck = i + BUFFER_CHARS
            }
            val char = text[i]
            bytes += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                char.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }
                char.isSurrogate() -> replacementBytes
                else -> 3
            }
            if (bytes > maxBytes) onLimitExceeded()
            i++
        }
        context.ensureActive()
        return bytes
    }

    /** Closes [output]. One encoder retains surrogate pairs spanning successive character chunks. */
    suspend fun write(output: OutputStream, text: String) {
        val context = currentCoroutineContext()
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        OutputStreamWriter(output, encoder).use { writer ->
            val buffer = CharArray(BUFFER_CHARS)
            var offset = 0
            while (offset < text.length) {
                context.ensureActive()
                val count = minOf(BUFFER_CHARS, text.length - offset)
                text.toCharArray(buffer, 0, offset, offset + count)
                writer.write(buffer, 0, count)
                offset += count
            }
            context.ensureActive()
        }
        context.ensureActive() // Closing the encoder can flush bytes before publication is allowed.
    }

    /** Closes [input]; enforces the byte limit during decoding even if the source grows. */
    suspend fun read(input: InputStream, maxBytes: Long, onLimitExceeded: (Long) -> Nothing): String {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        val context = currentCoroutineContext()
        val bounded = object : FilterInputStream(input) {
            private var total = 0L

            override fun read(): Int {
                context.ensureActive()
                val value = `in`.read()
                if (value >= 0 && ++total > maxBytes) onLimitExceeded(total)
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                context.ensureActive()
                // At most one look-ahead byte is needed to distinguish exact-limit EOF from overflow.
                val count = `in`.read(buffer, offset, minOf(length.toLong(), maxBytes - total + 1L).toInt())
                if (count > 0) {
                    total += count
                    if (total > maxBytes) onLimitExceeded(total)
                }
                return count
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return InputStreamReader(bounded, decoder).use { reader ->
            val text = StringBuilder(minOf(maxBytes, 64L * 1024L).toInt())
            val buffer = CharArray(BUFFER_CHARS)
            while (true) {
                context.ensureActive()
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > 0) text.append(buffer, 0, count)
            }
            context.ensureActive()
            text.toString()
        }
    }
}

package com.baystudio.droide.core

import java.io.BufferedReader
import java.io.IOException

/** Enforce a protocol frame cap while reading, before readLine can allocate an unbounded String. */
internal class BoundedProtocolLineReader(
    private val reader: BufferedReader,
    private val maxChars: Int,
) {
    private var skipLf = false
    init { require(maxChars in 1..6_000_000) }

    fun readLine(): String? {
        val value = StringBuilder(minOf(maxChars, 4_096))
        var code = reader.read()
        if (skipLf && code == '\n'.code) code = reader.read()
        skipLf = false
        while (code >= 0) {
            if (code == '\r'.code || code == '\n'.code) {
                skipLf = code == '\r'.code
                return value.toString()
            }
            if (value.length >= maxChars) throw IOException("Agent protocol line exceeds $maxChars characters")
            value.append(code.toChar())
            code = reader.read()
        }
        return value.takeIf { it.isNotEmpty() }?.toString()
    }
}

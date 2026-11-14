package com.baystudio.droide.core

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/** Structural admission only. Does not grant tool permission or materialize values.
 * Bounded buffer + depth stack; a huge valid string/array does not become a huge String/JsonObject.
 * Duplicate member semantics are deliberately not resolved here. Tool-specific schema,
 * ownership, duplicate-sensitive fields and permission checks remain mandatory downstream.
 */
internal object StreamedJsonValidator {
    fun requireObject(input: InputStream, maxDepth: Int = 128) {
        val reader = InputStreamReader(input, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
        validate(reader, maxDepth)
    }

    internal fun validate(reader: Reader, maxDepth: Int = 128, checkCancelled: () -> Unit = {}) {
        require(maxDepth in 1..512)
        Parser(reader, maxDepth, checkCancelled).document()
    }

    private class Parser(private val reader: Reader, private val maxDepth: Int, private val checkCancelled: () -> Unit) {
        private val buffer = CharArray(4096)
        private var position = 0
        private var limit = 0
        private var next = Int.MIN_VALUE
        private var eof = false
        private fun read(): Int {
            if (eof) return -1
            if (position == limit) {
                checkCancelled()
                limit = reader.read(buffer)
                position = 0
                if (limit < 0) { eof = true; return -1 }
                check(limit > 0) { "JSON input made no progress" }
            }
            return buffer[position++].code
        }
        private fun peek(): Int { if (next == Int.MIN_VALUE) next = read(); return next }
        private fun take(): Int = peek().also { next = Int.MIN_VALUE }
        private fun fail(): Nothing = throw IllegalArgumentException("Invalid streamed JSON object")
        private fun expect(c: Char) { if (take() != c.code) fail() }
        private fun whitespace() { while (peek() == 32 || peek() == 9 || peek() == 10 || peek() == 13) take() }
        fun document() {
            whitespace()
            if (peek() != '{'.code) fail()
            value(1)
            whitespace()
            if (take() != -1) fail()
        }
        private fun value(depth: Int) {
            whitespace()
            when (peek()) {
                '{'.code -> {
                    check(depth <= maxDepth) { "Streamed JSON nesting exceeds supported depth" }
                    take(); whitespace()
                    if (peek() == '}'.code) { take(); return }
                    while (true) {
                        whitespace(); string(); whitespace(); expect(':'); value(depth + 1); whitespace()
                        when (take()) { '}'.code -> return; ','.code -> Unit; else -> fail() }
                    }
                }
                '['.code -> {
                    check(depth <= maxDepth) { "Streamed JSON nesting exceeds supported depth" }
                    take(); whitespace()
                    if (peek() == ']'.code) { take(); return }
                    while (true) {
                        value(depth + 1); whitespace()
                        when (take()) { ']'.code -> return; ','.code -> Unit; else -> fail() }
                    }
                }
                '"'.code -> string()
                't'.code -> literal("true")
                'f'.code -> literal("false")
                'n'.code -> literal("null")
                '-'.code, in '0'.code..'9'.code -> number()
                else -> fail()
            }
        }
        private fun literal(word: String) { for (c in word) expect(c) }
        private fun digits() { if (peek() !in '0'.code..'9'.code) fail(); while (peek() in '0'.code..'9'.code) take() }
        private fun number() {
            if (peek() == '-'.code) take()
            if (peek() == '0'.code) take() else digits()
            if (peek() == '.'.code) { take(); digits() }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                take(); if (peek() == '+'.code || peek() == '-'.code) take(); digits()
            }
        }
        private fun hex(): Int {
            var value = 0
            repeat(4) {
                val c = take()
                val digit = when (c) { in 48..57 -> c-48; in 65..70 -> c-55; in 97..102 -> c-87; else -> fail() }
                value = (value shl 4) or digit
            }
            return value
        }
        private fun string() {
            expect('"')
            var pendingHigh = false
            while (true) {
                var c = take()
                if (c == '"'.code) { if (pendingHigh) fail(); return }
                if (c < 32) fail()
                if (c == '\\'.code) c = when (val escaped = take()) {
                    '"'.code, '\\'.code, '/'.code -> escaped
                    'b'.code -> 8; 'f'.code -> 12; 'n'.code -> 10; 'r'.code -> 13; 't'.code -> 9
                    'u'.code -> hex()
                    else -> fail()
                }
                if (pendingHigh) {
                    if (c !in 0xDC00..0xDFFF) fail()
                    pendingHigh = false
                } else when (c) {
                    in 0xD800..0xDBFF -> pendingHigh = true
                    in 0xDC00..0xDFFF -> fail()
                }
            }
        }
    }
}

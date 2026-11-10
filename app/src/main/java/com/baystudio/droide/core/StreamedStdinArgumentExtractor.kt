package com.baystudio.droide.core

import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** Strict source compatibility with the existing single 64KiB UTF8 stdin offer. Prepared retains
 * no decoded input; queue admission is never proof of delivery. */
internal object StreamedStdinArgumentExtractor {
    const val MAX_INPUT_UTF8 = 65_536
    class Prepared internal constructor(
        val callId: String,
        val originalArguments: StreamedFileArgumentExtractor.Source,
    ) {
        val toolName: String get() = "write_stdin"
        val argumentIdentity: String get() = "write_stdin:sha256:" + originalArguments.sha256
        override fun toString() = "PreparedStdinArguments(arguments=redacted)"
    }
    class ExtractionFailure internal constructor(cause: Exception,
        val originalArguments: StreamedFileArgumentExtractor.Source) :
        IllegalArgumentException("Streamed stdin argument preparation failed; no tool was executed", cause)
    class ExtractionCancelled internal constructor(cause: Exception,
        val originalArguments: StreamedFileArgumentExtractor.Source) :
        java.util.concurrent.CancellationException("Streamed stdin argument preparation cancelled") {
        init { initCause(cause) }
    }
    /** Provider terminal admission and stable call identity are the caller's separate obligation. */
    fun extract(session: OwnedResponseContentStore.Session, originalArguments: OwnedResponseContentStore.Handle,
        callId: String, checkCancelled: () -> Unit = {}): Prepared {
        require(callId.isNotBlank() && callId.length <= 512)
        checkCancelled()
        val original = session.openCompleted(originalArguments, checkCancelled).content
        require(original.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
        val source = StreamedFileArgumentExtractor.Source(original.handle, original.byteLength,
            original.utf16Length, original.sha256, original.kind)
        return guarded(source) {
            decode(session, source, checkCancelled) // validate whole batch without retaining chars
            checkCancelled()
            Prepared(callId, source)
        }
    }

    /** Dispatch-time bounded compatibility value. Consume/release before dispatching another call. */
    fun materialize(session: OwnedResponseContentStore.Session, prepared: Prepared,
        checkCancelled: () -> Unit = {}): JsonObject = guarded(prepared.originalArguments) {
        decode(session, prepared.originalArguments, checkCancelled)
    }

    private fun decode(session: OwnedResponseContentStore.Session,
        source: StreamedFileArgumentExtractor.Source, checkCancelled: () -> Unit): JsonObject {
        checkCancelled()
        val actual = session.openCompleted(source.handle, checkCancelled).content
        require(source.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS && actual.kind == source.kind &&
            actual.byteLength == source.bytes && actual.utf16Length == source.utf16Units && actual.sha256 == source.sha256) {
            "Prepared stdin argument source changed"
        }
        val text = session.openBytes(source.handle, checkCancelled).use { input ->
            val reader = InputStreamReader(input, Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
            Parser(reader, checkCancelled).parse()
        }
        checkCancelled()
        return text
    }
    private inline fun <T> guarded(source: StreamedFileArgumentExtractor.Source, action: () -> T): T {
        try { return action() }
        catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException) throw ExtractionCancelled(failure, source)
            throw ExtractionFailure(failure, source)
        }
    }

    private class Parser(
        private val reader: Reader,
        private val checkCancelled: () -> Unit,
    ) {
        private val input = CharArray(4096)
        private var position = 0
        private var available = 0
        private var cached = Int.MIN_VALUE
        private var ended = false
        private fun read(): Int {
            if (ended) return -1
            if (position == available) {
                checkCancelled(); available = reader.read(input); position = 0
                if (available < 0) { ended = true; return -1 }
                require(available > 0) { "JSON input made no progress" }
            }
            return input[position++].code
        }
        private fun peek(): Int { if (cached == Int.MIN_VALUE) cached = read(); return cached }
        private fun take(): Int = peek().also { cached = Int.MIN_VALUE }
        private fun expect(char: Char) { require(take() == char.code) { "Malformed streamed stdin arguments" } }
        private fun whitespace() { while (peek() == 32 || peek() == 9 || peek() == 10 || peek() == 13) take() }

        fun parse(): JsonObject {
            whitespace(); expect('{'); whitespace()
            val values = linkedMapOf<String, JsonElement>()
            if (peek() != '}'.code) while (true) {
                val key = smallString(64)
                require(key !in values) { "Duplicate stdin argument" }
                whitespace(); expect(':'); whitespace()
                values[key] = when (key) {
                    "session_id" -> JsonPrimitive(smallString(512).also { require(it.isNotBlank()) })
                    "chars" -> JsonPrimitive(smallString(MAX_INPUT_UTF8).also {
                        require(it.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_UTF8) { "Stdin offer exceeds 65536 UTF8 bytes" }
                    })
                    "close_stdin", "interrupt", "wait_for_completion" -> JsonPrimitive(boolean())
                    "output_cursor" -> JsonPrimitive(integer().also { require(it >= 0) { "Invalid output cursor" } })
                    "yield_time_ms" -> JsonPrimitive(integer().also { require(it in 0..30_000) { "Invalid yield_time_ms" } })
                    else -> throw IllegalArgumentException("Unknown source stdin argument")
                }
                whitespace()
                if (peek() != ','.code) break
                take(); whitespace()
            }
            expect('}'); whitespace(); require(take() == -1) { "Trailing streamed stdin argument data" }
            require("session_id" in values) { "Missing session_id" }
            val chars = values["chars"]?.jsonPrimitive?.content.orEmpty()
            require(values["interrupt"]?.jsonPrimitive?.boolean != true ||
                (chars.isEmpty() && values["close_stdin"]?.jsonPrimitive?.boolean != true)) {
                "interrupt cannot be combined with stdin input"
            }
            checkCancelled()
            return JsonObject(values)
        }
        private fun boolean(): Boolean = when (peek()) {
            't'.code -> { "true".forEach(::expect); true }
            'f'.code -> { "false".forEach(::expect); false }
            else -> throw IllegalArgumentException("Expected JSON boolean")
        }
        private fun integer(): Long {
            val value = StringBuilder()
            if (peek() == '-'.code) value.append(take().toChar())
            require(peek() in '0'.code..'9'.code) { "Expected JSON integer" }
            if (peek() == '0'.code) value.append(take().toChar())
            else while (peek() in '0'.code..'9'.code) {
                require(value.length < 20) { "JSON integer overflow" }
                value.append(take().toChar())
            }
            return value.toString().toLongOrNull() ?: throw IllegalArgumentException("JSON integer overflow")
        }

        private fun smallString(maximum: Int): String {
            val text = StringBuilder()
            string { fragment ->
                if (fragment.length > maximum - text.length) throw IllegalArgumentException("Stdin argument exceeds supported schema")
                text.append(fragment)
            }
            return text.toString()
        }
        private fun hex(): Int {
            var value = 0
            repeat(4) {
                val c = take()
                val digit = when (c) { in 48..57 -> c - 48; in 65..70 -> c - 55; in 97..102 -> c - 87; else -> throw IllegalArgumentException("Malformed JSON Unicode escape") }
                value = (value shl 4) or digit
            }
            return value
        }
        private fun string(sink: (CharSequence) -> Unit): Long {
            expect('"')
            val chunk = StringBuilder(2048)
            var length = 0L
            var high = false
            while (true) {
                var char = take()
                if (char == '"'.code) {
                    require(!high) { "Unpaired JSON Unicode surrogate" }
                    if (chunk.isNotEmpty()) sink(chunk)
                    return length
                }
                require(char >= 32) { "Unescaped control or incomplete JSON string" }
                if (char == '\\'.code) char = when (val escaped = take()) {
                    '"'.code, '\\'.code, '/'.code -> escaped
                    'b'.code -> 8; 'f'.code -> 12; 'n'.code -> 10; 'r'.code -> 13; 't'.code -> 9
                    'u'.code -> hex()
                    else -> throw IllegalArgumentException("Invalid JSON string escape")
                }
                if (high) { require(char in 0xdc00..0xdfff) { "Unpaired JSON Unicode surrogate" }; high = false }
                else when (char) {
                    in 0xd800..0xdbff -> high = true
                    in 0xdc00..0xdfff -> throw IllegalArgumentException("Unpaired JSON Unicode surrogate")
                }
                chunk.append(char.toChar()); length = Math.addExact(length, 1)
                if (chunk.length >= 2048) { checkCancelled(); sink(chunk); chunk.setLength(0) }
            }
        }
    }
}

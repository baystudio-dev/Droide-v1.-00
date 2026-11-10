package com.baystudio.droide.core

import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/** Read-only materialization of the EXISTING bounded patch consumer input, not a patch executor. */

internal object StreamedPatchArgumentExtractor {
    const val MAX_PATCH_UTF16 = 2_000_000
    class Prepared internal constructor(
        val callId: String,
        val originalArguments: StreamedFileArgumentExtractor.Source,
    ) {
        val toolName: String get() = "apply_patch"
        val argumentIdentity: String get() = "apply_patch:sha256:" + originalArguments.sha256
        override fun toString() = "PreparedPatchArguments(arguments=redacted)"
    }
    class ExtractionFailure internal constructor(cause: Exception,
        val originalArguments: StreamedFileArgumentExtractor.Source) :
        IllegalArgumentException("Streamed patch argument preparation failed; no tool was executed", cause)
    class ExtractionCancelled internal constructor(cause: Exception,
        val originalArguments: StreamedFileArgumentExtractor.Source) :
        java.util.concurrent.CancellationException("Streamed patch argument preparation cancelled") {
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
            val text = decode(session, source, checkCancelled)
            // Whole-batch admission must reject later malformed syntax before earlier effects. Parsed ops
            // and decoded text are transient: neither is retained in Prepared.
            PatchTool.parse(text)
            checkCancelled()
            Prepared(callId, source)
        }
    }

    /** Dispatch-time bounded compatibility value. Consume/release before dispatching another call. */
    fun materialize(session: OwnedResponseContentStore.Session, prepared: Prepared,
        checkCancelled: () -> Unit = {}): String = guarded(prepared.originalArguments) {
        decode(session, prepared.originalArguments, checkCancelled)
    }

    private fun decode(session: OwnedResponseContentStore.Session,
        source: StreamedFileArgumentExtractor.Source, checkCancelled: () -> Unit): String {
        checkCancelled()
        val actual = session.openCompleted(source.handle, checkCancelled).content
        require(source.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS && actual.kind == source.kind &&
            actual.byteLength == source.bytes && actual.utf16Length == source.utf16Units && actual.sha256 == source.sha256) {
            "Prepared patch argument source changed"
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
        private fun expect(char: Char) { require(take() == char.code) { "Malformed streamed patch arguments" } }
        private fun whitespace() { while (peek() == 32 || peek() == 9 || peek() == 10 || peek() == 13) take() }

        fun parse(): String {
            whitespace(); expect('{'); whitespace()
            val key = smallString(64)
            require(key == "patchText") { "Only patchText is supported for source apply_patch arguments" }
            whitespace(); expect(':'); whitespace()
            val text = StringBuilder()
            string { fragment ->
                require(fragment.length <= MAX_PATCH_UTF16 - text.length) { "Patch too large" }
                text.append(fragment)
            }
            whitespace()
            // A second key, including a duplicate decoded key, is never silently discarded.
            expect('}'); whitespace(); require(take() == -1) { "Trailing streamed patch argument data" }
            checkCancelled()
            return text.toString()
        }

        private fun smallString(maximum: Int): String {
            val text = StringBuilder()
            string { fragment ->
                if (fragment.length > maximum - text.length) throw IllegalArgumentException("Patch argument metadata exceeds supported schema")
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

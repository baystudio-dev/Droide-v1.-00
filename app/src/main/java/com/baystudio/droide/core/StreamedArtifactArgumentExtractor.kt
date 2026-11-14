package com.baystudio.droide.core

import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/** Strict flat artifact schema. No execution/permission/publication authority is minted here.
 * Type metadata is bounded; content is file-backed and admitted by the owned store's disk policy.
 * Unknown fields fail explicitly, not silently ignored. Full syntax/schema/EOF precedes sealing.
 */
internal object StreamedArtifactArgumentExtractor {
    class Prepared internal constructor(
        val callId: String,
        val originalType: String,
        val originalArguments: StreamedFileArgumentExtractor.Source,
        val content: StreamedFileArgumentExtractor.Source,
    ) {
        val toolName: String get() = "artifact"
        /** Display/header only. NEVER use this value instead of originalType for permission. */
        val displayType: String get() = originalType.trim().ifBlank { "artifact" }.take(80).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.replace(Regex("[\\r\\n]"), " ")
        val argumentIdentity: String get() = "artifact:sha256:" + originalArguments.sha256
        override fun toString() = "PreparedArtifactArguments(arguments=redacted)"
    }
    class UnsupportedSchemaException : IllegalArgumentException("Unsupported field or metadata capacity for streamed artifact arguments")
    class ExtractionFailure internal constructor(cause: Exception, val recoveryHandles: List<OwnedResponseContentStore.Handle>) :
        IllegalArgumentException("Streamed artifact argument preparation failed; no tool was executed", cause)
    class ExtractionCancelled internal constructor(cause: Exception, val recoveryHandles: List<OwnedResponseContentStore.Handle>) :
        java.util.concurrent.CancellationException("Streamed artifact argument preparation cancelled") {
        init { initCause(cause) }
    }

    /** Caller must already have provider terminal admission. A complete store record is not that proof. */
    fun extract(session: OwnedResponseContentStore.Session, originalArguments: OwnedResponseContentStore.Handle,
        callId: String, checkCancelled: () -> Unit = {}): Prepared {
        require(callId.isNotBlank() && callId.length <= 512)
        checkCancelled()
        val original = session.openCompleted(originalArguments, checkCancelled).content
        require(original.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
        var stage: OwnedResponseContentStore.Session.Writer? = null
        try {
            val type = session.openBytes(originalArguments, checkCancelled).use { input ->
                val reader = InputStreamReader(input, Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
                Parser(reader, checkCancelled) { _, consume ->
                    checkCancelled()
                    val writer = session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT)
                    stage = writer
                    consume(writer::append)
                }.parse()
            }
            checkCancelled()
            val content = requireNotNull(stage).sealCompleted { input ->
                val buffer = ByteArray(8192)
                while (true) { checkCancelled(); val count = input.read(buffer); if (count < 0) break; check(count > 0) }
            }.content
            checkCancelled()
            return Prepared(callId, type, source(original), source(content))
        } catch (failure: Exception) {
            val retained = mutableListOf<OwnedResponseContentStore.Handle>()
            stage?.let { writer ->
                try { writer.close() } catch (close: Exception) { failure.addSuppressed(close) }
                try { session.discardStage(writer.recoveryHandle) }
                catch (cleanup: Exception) { retained += writer.recoveryHandle; failure.addSuppressed(cleanup) }
            }
            if (failure is java.util.concurrent.CancellationException) throw ExtractionCancelled(failure, retained)
            throw ExtractionFailure(failure, retained)
        } finally { try { stage?.close() } catch (_: Exception) { /* recovery reported above */ } }
    }
    private fun source(value: OwnedResponseContentStore.Content) = StreamedFileArgumentExtractor.Source(
        value.handle, value.byteLength, value.utf16Length, value.sha256, value.kind)

    private class Parser(
        private val reader: Reader,
        private val checkCancelled: () -> Unit,
        private val largeString: (String, (((CharSequence) -> Unit) -> Long)) -> Unit,
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
        private fun expect(char: Char) { require(take() == char.code) { "Malformed streamed artifact arguments" } }
        private fun whitespace() { while (peek() == 32 || peek() == 9 || peek() == 10 || peek() == 13) take() }

        fun parse(): String {
            val seen = hashSetOf<String>()
            var type: String? = null
            whitespace(); expect('{'); whitespace()
            if (peek() != '}'.code) while (true) {
                whitespace(); val name = smallString(64)
                require(seen.add(name)) { "Duplicate streamed artifact argument field" }
                if (name != "type" && name != "content") throw UnsupportedSchemaException()
                whitespace(); expect(':'); whitespace()
                if (name == "type") type = smallString(4096)
                else largeString(name) { sink -> string(sink) }
                whitespace(); if (peek() == '}'.code) break
                expect(',')
            }
            expect('}'); whitespace(); require(take() == -1) { "Trailing streamed artifact argument data" }
            require(seen == setOf("type", "content")) { "Missing required streamed artifact argument field" }
            return requireNotNull(type)
        }

        private fun smallString(maximum: Int): String {
            val text = StringBuilder()
            string { fragment ->
                if (fragment.length > maximum - text.length) throw UnsupportedSchemaException()
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

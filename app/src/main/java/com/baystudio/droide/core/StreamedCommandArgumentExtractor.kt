package com.baystudio.droide.core

import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Raw owned JSON is never materialized. Only decoded command reaches existing bounded consumers. */
internal object StreamedCommandArgumentExtractor {
    class Prepared internal constructor(val callId: String, val toolName: String,
        val originalArguments: StreamedFileArgumentExtractor.Source, val semanticDigest: String) {
        val argumentIdentity: String get() = "command:semantic-sha256:$semanticDigest"
        override fun toString() = "PreparedCommandArguments(tool=$toolName, arguments=redacted)"
    }
    class ExtractionFailure internal constructor(cause: Exception, val originalArguments: StreamedFileArgumentExtractor.Source) :
        IllegalArgumentException("Streamed command preparation failed; no tool was executed", cause)
    class ExtractionCancelled internal constructor(cause: Exception, val originalArguments: StreamedFileArgumentExtractor.Source) :
        java.util.concurrent.CancellationException("Streamed command preparation cancelled") { init { initCause(cause) } }
    private data class Parsed(val args: JsonObject, val digest: String)
    private val names = setOf("run_command", "bash", "exec_command")
    fun extract(session: OwnedResponseContentStore.Session, arguments: OwnedResponseContentStore.Handle,
        callId: String, toolName: String, checkCancelled: () -> Unit = {}): Prepared {
        require(toolName in names && callId.isNotBlank() && callId.length <= 512)
        checkCancelled()
        val actual = session.openCompleted(arguments, checkCancelled).content
        require(actual.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
        val source = StreamedFileArgumentExtractor.Source(actual.handle, actual.byteLength, actual.utf16Length, actual.sha256, actual.kind)
        return guarded(source) {
            val parsed = decode(session, source, toolName, false, checkCancelled)
            Prepared(callId, toolName, source, parsed.digest)
        }
    }
    fun materialize(session: OwnedResponseContentStore.Session, prepared: Prepared, checkCancelled: () -> Unit = {}): JsonObject =
        guarded(prepared.originalArguments) {
            require(prepared.toolName in names)
            val parsed = decode(session, prepared.originalArguments, prepared.toolName, true, checkCancelled)
            require(parsed.digest == prepared.semanticDigest) { "Prepared command semantics changed" }
            parsed.args
        }
    private fun decode(session: OwnedResponseContentStore.Session, source: StreamedFileArgumentExtractor.Source,
        tool: String, retain: Boolean, checkCancelled: () -> Unit): Parsed {
        checkCancelled()
        val actual = session.openCompleted(source.handle, checkCancelled).content
        require(source.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS && actual.kind == source.kind &&
            actual.byteLength == source.bytes && actual.utf16Length == source.utf16Units && actual.sha256 == source.sha256)
        val result = session.openBytes(source.handle, checkCancelled).use {
            Parser(InputStreamReader(it, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)), tool, retain, checkCancelled).parse()
        }
        checkCancelled(); return result
    }
    private inline fun <T> guarded(source: StreamedFileArgumentExtractor.Source, block: () -> T): T {
        try { return block() } catch(e: Exception) {
            if(e is java.util.concurrent.CancellationException) throw ExtractionCancelled(e, source)
            throw ExtractionFailure(e, source)
        }
    }
    private class Parser(private val reader: Reader, private val tool: String, private val retain: Boolean,
        private val checkCancelled: () -> Unit) {
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
        private fun expect(char: Char) { require(take() == char.code) { "Malformed streamed command arguments" } }
        private fun whitespace() { while (peek() == 32 || peek() == 9 || peek() == 10 || peek() == 13) take() }

        fun parse(): Parsed {
            whitespace(); expect('{'); whitespace()
            val values = linkedMapOf<String, JsonElement>()
            val seen = linkedSetOf<String>()
            val commandKey = if(tool == "exec_command") "cmd" else "command"
            val commandHash = MessageDigest.getInstance("SHA-256")
            var commandUnits = 0; var nonblank = false
            if(peek() != '}'.code) while(true) {
                val key = smallString(32); require(seen.add(key)) { "Duplicate command argument" }
                whitespace(); expect(':'); whitespace()
                when(key) {
                    commandKey -> {
                        val text = if(retain) StringBuilder() else null
                        string { chunk ->
                            require(chunk.length <= BackgroundCommandManager.MAX_COMMAND_CHARS - commandUnits) { "Decoded command exceeds manager limit" }
                            commandUnits += chunk.length
                            chunk.forEach { c ->
                                require(c != '\u0000') { "Command contains NUL" }
                                if(!c.isWhitespace()) nonblank = true
                                commandHash.update((c.code ushr 8).toByte()); commandHash.update(c.code.toByte())
                            }
                            text?.append(chunk)
                        }
                        if(text != null) values[key] = JsonPrimitive(text.toString())
                    }
                    "requires_tty" -> values[key] = JsonPrimitive(boolean())
                    "background" -> { require(tool != "exec_command"); values[key] = JsonPrimitive(boolean()) }
                    "keep_stdin_open" -> { require(tool == "exec_command"); values[key] = JsonPrimitive(boolean()) }
                    "timeout_ms" -> values[key] = if(peek() == 'n'.code) { "null".forEach(::expect); JsonNull } else JsonPrimitive(integer())
                    "yield_time_ms" -> values[key] = scalar()
                    else -> error("Unknown source command argument")
                }
                whitespace(); if(peek() != ','.code) break
                take(); whitespace()
            }
            expect('}'); whitespace(); require(take() == -1) { "Trailing command argument data" }
            require(commandKey in seen && nonblank) { "Missing or blank command" }
            val tty = values["requires_tty"]?.jsonPrimitive?.booleanOrNull == true
            require(!tty || commandUnits <= 32_000) { "Interactive command is too long" }
            val background = values["background"]?.jsonPrimitive?.booleanOrNull == true
            val timeout = values["timeout_ms"]?.jsonPrimitive?.longOrNull
            require(timeout == null || timeout in BackgroundCommandManager.MIN_TIMEOUT_MS..BackgroundCommandManager.MAX_TIMEOUT_MS)
            val keep = values["keep_stdin_open"]?.jsonPrimitive?.booleanOrNull == true
            val yieldMs = if(background) null else AgentCommandSessions.yieldMs(JsonObject(values),
                if(tool == "exec_command") 1000L else AgentTimeoutPolicy.COMMAND_YIELD_MS)
            val semantic = "v1|${if(tool == "exec_command") "exec" else "run"}|$commandUnits|${hexDigest(commandHash.digest())}|$tty|$background|$keep|$timeout|$yieldMs"
            checkCancelled()
            return Parsed(JsonObject(values), hexDigest(MessageDigest.getInstance("SHA-256").digest(semantic.toByteArray(Charsets.UTF_8))))
        }
        private fun hexDigest(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        /** Bounded primitive retained unchanged, so ignored background yield stays ignored. */
        private fun scalar(): JsonElement = when(peek()) {
            '"'.code -> JsonPrimitive(smallString(64))
            't'.code, 'f'.code -> JsonPrimitive(boolean())
            'n'.code -> { "null".forEach(::expect); JsonNull }
            else -> {
                val token = StringBuilder()
                while(peek() != -1 && peek() !in intArrayOf(','.code, '}'.code, 32, 9, 10, 13)) {
                    require(token.length < 64); token.append(take().toChar())
                }
                require(token.toString().matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                Json.parseToJsonElement(token.toString()).also { require(it is JsonPrimitive) }
            }
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
                if (fragment.length > maximum - text.length) throw IllegalArgumentException("Command option exceeds supported schema")
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

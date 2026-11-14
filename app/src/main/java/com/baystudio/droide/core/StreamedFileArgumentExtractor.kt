package com.baystudio.droide.core

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/** Narrow schema extractor, NOT a general JSON parser or mutation authority.
 * Parses complete stored write_file/edit_file arguments without materializing content/search/
 * replace Strings. Full syntax/schema/duplicate/EOF validation precedes derived content sealing.
 * No hooks, permission requests, file mutations or tool dispatch occur here.
 *
 * The legacy catalog permits unknown properties; this new route explicitly rejects unsupported
 * fields instead of silently dropping them. Legacy behavior and global 128k admission are unchanged.
 * A separately reviewed executor must still enforce ownership, hooks, permissions, document CAS,
 * disk receipts, undo and durable history before any project side effect.
 */
internal object StreamedFileArgumentExtractor {
    enum class Tool(val wireName: String) { WRITE_FILE("write_file"), EDIT_FILE("edit_file") }
    fun canonicalTool(wireName: String): Tool = when(wireName) {
        "write_file", "write" -> Tool.WRITE_FILE
        "edit_file", "edit" -> Tool.EDIT_FILE
        else -> throw IllegalArgumentException("Unsupported source-backed file tool name")
    }
    data class ExpectedDocument(val documentId: String, val revision: Int, val changeVersion: Int)

    class Source internal constructor(
        val handle: OwnedResponseContentStore.Handle,
        val bytes: Long,
        val utf16Units: Long,
        val sha256: String,
        val kind: OwnedResponseContentStore.Kind,
    ) {
        fun verify(session: OwnedResponseContentStore.Session) {
            val actual = session.openCompleted(handle).content
            check(actual.kind == kind && actual.byteLength == bytes && actual.utf16Length == utf16Units && actual.sha256 == sha256) {
                "Prepared file argument source changed"
            }
        }
        override fun toString() = "PreparedArgumentSource(bytes=$bytes, kind=$kind, handle=redacted)"
    }

    class Prepared internal constructor(
        val tool: Tool,
        val callId: String,
        val requestedPath: String,
        val expectedDocument: ExpectedDocument?,
        val originalArguments: Source,
        val content: Source?,
        val search: Source?,
        val replacement: Source?,
        val wireName: String = tool.wireName,
    ) {
        init { require(canonicalTool(wireName)==tool) { "Wire tool name does not match the decoded file operation" } }
        /** Digest-based repeat identity; not a permission or publication receipt. */
        val argumentIdentity: String get() = wireName + ":sha256:" + originalArguments.sha256
        override fun toString() = "PreparedFileArguments(tool=${wireName}, arguments=redacted)"
    }

    /** Exact advertised alias identity survives decoding; only the fixed operation mapping is allowed. */
    fun extractWire(session: OwnedResponseContentStore.Session, originalArguments: OwnedResponseContentStore.Handle,
        wireName: String, callId: String, checkCancelled: () -> Unit = {}): Prepared {
        val decoded=extract(session,originalArguments,canonicalTool(wireName),callId,checkCancelled)
        return Prepared(decoded.tool,decoded.callId,decoded.requestedPath,decoded.expectedDocument,
            decoded.originalArguments,decoded.content,decoded.search,decoded.replacement,wireName)
    }

    class UnsupportedSchemaException : IllegalArgumentException("Unsupported field or metadata capacity for streamed file arguments")
    class ExtractionFailure internal constructor(cause: Exception, val recoveryHandles: List<OwnedResponseContentStore.Handle>) :
        IllegalArgumentException("Streamed file argument preparation failed; no tool was executed", cause)
    class ExtractionCancelled internal constructor(cause: Exception, val recoveryHandles: List<OwnedResponseContentStore.Handle>) :
        java.util.concurrent.CancellationException("Streamed file argument preparation cancelled") {
        init { initCause(cause) }
    }

    /** The protocol adapter supplies a stable completed call identity. Storage completion alone
     * does not prove provider terminal admission; callers must satisfy that before invoking this.
     */
    fun extract(
        session: OwnedResponseContentStore.Session,
        originalArguments: OwnedResponseContentStore.Handle,
        tool: Tool,
        callId: String,
        checkCancelled: () -> Unit = {},
    ): Prepared {
        require(callId.isNotBlank() && callId.length <= 512) { "Invalid completed tool call identity" }
        checkCancelled()
        val original = session.openCompleted(originalArguments).content
        require(original.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS) { "Expected owned tool-argument source" }
        val stages = linkedMapOf<String, OwnedResponseContentStore.Session.Writer>()
        val scalarLengths = mutableMapOf<String, Long>()
        try {
            val parsed = session.openBytes(originalArguments).use { input ->
                val reader = InputStreamReader(input, Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
                Parser(reader, tool, checkCancelled) { name, consume ->
                    checkCancelled()
                    val writer = session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT)
                    stages[name] = writer
                    scalarLengths[name] = consume(writer::append)
                }.parse()
            }
            if (tool == Tool.EDIT_FILE) require(scalarLengths["search"] != 0L) { "edit_file search must not be empty" }
            checkCancelled()
            // All fields and EOF are now validated. Nothing was sealed before this boundary.
            val fields = stages.mapValues { (_, writer) ->
                checkCancelled()
                source(writer.sealCompleted { input ->
                    val buffer = ByteArray(8192)
                    while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; check(n > 0) }
                }.content)
            }
            checkCancelled() // sealing the final scalar can suspend/perform substantial IO
            return Prepared(tool, callId, parsed.path, parsed.expected, source(original),
                fields["content"], fields["search"], fields["replace"])
        } catch (failure: Exception) {
            // Only explicitly owned unpublished extraction stages are discarded. Published
            // artifacts survive an uncertain multi-field seal and must enter the owner's
            // recovery journal; they never become a Prepared execution candidate here.
            val retained = mutableListOf<OwnedResponseContentStore.Handle>()
            stages.values.forEach { writer ->
                try { writer.close() } catch (close: Exception) { failure.addSuppressed(close) }
                try { session.discardStage(writer.recoveryHandle) }
                catch (cleanup: Exception) { retained += writer.recoveryHandle; failure.addSuppressed(cleanup) }
            }
            if (failure is java.util.concurrent.CancellationException) throw ExtractionCancelled(failure, retained)
            throw ExtractionFailure(failure, retained)
        } finally {
            stages.values.forEach { try { it.close() } catch (_: Exception) { /* failure cleanup above owns recovery */ } }
        }
    }

    private fun source(content: OwnedResponseContentStore.Content) = Source(content.handle, content.byteLength,
        content.utf16Length, content.sha256, content.kind)
    private data class Metadata(val path: String, val expected: ExpectedDocument?)

    /** Deliberately flat: known string/integer fields only. No nested-value skip parser. */
    private class Parser(
        private val reader: Reader,
        private val tool: Tool,
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
        private fun expect(char: Char) { require(take() == char.code) { "Malformed streamed file arguments" } }
        private fun whitespace() { while (peek() == 32 || peek() == 9 || peek() == 10 || peek() == 13) take() }

        fun parse(): Metadata {
            val seen = hashSetOf<String>()
            val texts = mutableMapOf<String, String>()
            val numbers = mutableMapOf<String, Int>()
            val required = if (tool == Tool.WRITE_FILE) setOf("path", "content") else setOf("path", "search", "replace")
            val fields = required + setOf("expected_document_id", "expected_revision", "expected_change_version")
            whitespace(); expect('{'); whitespace()
            if (peek() != '}'.code) while (true) {
                whitespace()
                val name = smallString(64)
                require(seen.add(name)) { "Duplicate streamed file argument field" }
                if (name !in fields) throw UnsupportedSchemaException()
                whitespace(); expect(':'); whitespace()
                when (name) {
                    "path" -> texts[name] = smallString(4096)
                    "expected_document_id" -> texts[name] = smallString(128)
                    "expected_revision", "expected_change_version" -> numbers[name] = counter()
                    else -> largeString(name) { sink -> string(sink) }
                }
                whitespace()
                if (peek() == '}'.code) break
                expect(',')
            }
            expect('}'); whitespace(); require(take() == -1) { "Trailing streamed file argument data" }
            require(seen.containsAll(required)) { "Missing required streamed file argument field" }
            val path = checkNotNull(texts["path"])
            require(path.isNotBlank() && '\u0000' !in path) { "Invalid requested path" }
            val expectedNames = setOf("expected_document_id", "expected_revision", "expected_change_version")
            val expectedCount = expectedNames.count { it in seen }
            require(expectedCount == 0 || expectedCount == 3) { "Expected live document identity requires all three fields" }
            val expected = if (expectedCount == 0) null else {
                val id = checkNotNull(texts["expected_document_id"])
                require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid expected document identity" }
                ExpectedDocument(id, checkNotNull(numbers["expected_revision"]), checkNotNull(numbers["expected_change_version"]))
            }
            return Metadata(path, expected)
        }

        private fun smallString(maximum: Int): String {
            val text = StringBuilder()
            string { fragment ->
                if (fragment.length > maximum - text.length) throw UnsupportedSchemaException()
                text.append(fragment)
            }
            return text.toString()
        }
        private fun counter(): Int {
            // Match current WorkspaceDocumentVersion's non-negative Int semantics. Quoted
            // numbers, decimals/exponents, leading zeros and overflow are not coerced.
            val text = StringBuilder(10)
            if (peek() == '-'.code) text.append(take().toChar())
            if (peek() == '0'.code) text.append(take().toChar())
            else {
                require(peek() in '1'.code..'9'.code) { "Expected integer document counter" }
                while (peek() in '0'.code..'9'.code) {
                    require(text.length < 11) { "Document counter exceeds Int capacity" }
                    text.append(take().toChar())
                }
            }
            return (text.toString().toIntOrNull() ?: throw IllegalArgumentException("Invalid document counter")).also {
                require(it >= 0) { "Negative document counter" }
            }
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

package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class AgentLspResultPages(private val now: () -> Long = System::nanoTime) {
    private data class Request(val tool: String, val path: String, val operation: String, val line: Int, val col: Int,
        val query: String?)
    private data class Document(val version: WorkspaceDocumentVersion, val loaded: Boolean,
        val kind: WorkspaceDocumentKind, val dirty: Boolean)
    private data class Capture(val root: WorkspaceDirectoryIdentity, val disk: StreamFileVersion,
        val document: Document?, val directory: File)
    private data class Entry(val request: Request, val capture: Capture, val owner: String?, val permissionScope: String,
        val lsp: LspManager, val documents: WorkspaceDocumentAuthority?, val text: String, val originalChars: Int,
        val state: AgentToolEvidenceState, val created: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false

    suspend fun execute(tool: String, args: JsonObject, c: AgentTools.Ctx, produce: suspend () -> String): String =
        execute(tool, args, AgentReadContext(c), produce)

    suspend fun execute(tool: String, args: JsonObject, c: AgentReadContext, produce: suspend () -> String): String {
        currentCoroutineContext().ensureActive()
        val path = normalizedPath(args.getValue("path").jsonPrimitive.content)
        val request = Request(tool, path, if (tool == "lsp") args["operation"]?.jsonPrimitive?.contentOrNull ?: "hover" else "diagnose",
            if (tool == "lsp") args["line"]?.jsonPrimitive?.intOrNull ?: 1 else 1,
            if (tool == "lsp") args["col"]?.jsonPrimitive?.intOrNull ?: 1 else 1,
            if (tool == "lsp") args["query"]?.jsonPrimitive?.contentOrNull else null)
        val token = args["result_id"]?.let {
            val value = it as? JsonPrimitive
            require(value?.isString == true && value.content.matches(Regex("[a-f0-9-]{36}"))) { "Invalid LSP result_id" }
            value.content
        }
        val offset = args["offset"]?.let {
            val value = it as? JsonPrimitive
            require(value != null && !value.isString && value.intOrNull != null) { "LSP offset must be an integer" }
            requireNotNull(value.intOrNull)
        } ?: 0
        require(offset >= 0 && (token != null || offset == 0)) { "Use result_id with the returned next_offset" }
        if (token != null) {
            val entry = synchronized(entries) { checkOpen(); prune(); entries[token] }
                ?: return "ERROR: LSP result expired or belongs to another run. Repeat the original request without result_id or offset."
            require(entry.request == request && entry.owner == c.sessionId && entry.permissionScope == c.permissionScope &&
                entry.lsp === c.lsp && entry.documents === c.documentAuthority && entry.capture.directory == c.files.root.canonicalFile) {
                "LSP result does not belong to this request or workspace"
            }
            try { validate(entry.capture, path, c) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (changed: Exception) {
                synchronized(entries) { entries.remove(token) }
                return "ERROR: LSP result is stale; the document or workspace changed. Repeat the original request without result_id or offset."
            }
            currentCoroutineContext().ensureActive()
            return synchronized(entries) {
                checkOpen(); prune()
                check(entries[token] === entry) { "LSP result expired; repeat the original request" }
                render(token, entry, offset)
            }
        }
        synchronized(entries) { checkOpen() }
        val capture = capture(path, c)
        synchronized(entries) { checkOpen() }
        val text = produce()
        currentCoroutineContext().ensureActive()
        validate(capture, path, c)
        synchronized(entries) { checkOpen() }
        if (text.length <= PAGE_CHARS) return text
        val end = endAt(text, 0, MAX_RESULT_CHARS)
        val entry = Entry(request, capture, c.sessionId, c.permissionScope, c.lsp, c.documentAuthority,
            text.substring(0, end), text.length, AgentToolResultSemantics.classify(tool, text).state, now())
        currentCoroutineContext().ensureActive()
        return synchronized(entries) {
            checkOpen(); prune()
            while (entries.size >= MAX_RESULTS || entries.values.sumOf { it.text.length } + entry.text.length > MAX_TOTAL_CHARS)
                entries.remove(entries.keys.first())
            val id = UUID.randomUUID().toString()
            entries[id] = entry
            render(id, entry, 0)
        }
    }

    fun close() = synchronized(entries) { closed = true; entries.clear() }

    private fun checkOpen() = check(!closed) { "LSP result run has ended" }
    private fun prune() { val at = now(); entries.entries.removeAll { at - it.value.created >= MAX_AGE_NANOS } }

    private suspend fun capture(path: String, c: AgentReadContext): Capture {
        val document = document(c.documentAuthority?.snapshot(path))
        val captured = withContext(Dispatchers.IO) {
            val identity = WorkspaceDirectoryIdentity(c.files.root)
            val file = c.files.resolveChecked(path)
            require(file.length() <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) { "LSP document exceeds the bounded text limit" }
            val disk = StreamFileVersion.capture(c.files.root, path)
            identity.requireCurrent()
            Capture(identity, disk, document, c.files.root.canonicalFile)
        }
        validate(captured, path, c)
        return captured
    }

    private suspend fun validate(capture: Capture, path: String, c: AgentReadContext) {
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            capture.root.requireCurrent()
            capture.disk.requireMatches(c.files.root, path)
        }
        check(document(c.documentAuthority?.snapshot(path)) == capture.document) { "Editor document changed" }
        currentCoroutineContext().ensureActive()
    }

    private fun document(value: WorkspaceDocumentSnapshot?): Document? = value?.let {
        Document(it.version, it.loaded, it.kind, it.dirty)
    }

    private fun render(id: String, entry: Entry, offset: Int): String {
        require(offset <= entry.text.length) { "LSP offset is beyond the retained result" }
        require(offset == 0 || offset == entry.text.length || !entry.text[offset].isLowSurrogate() ||
            !entry.text[offset - 1].isHighSurrogate()) { "Use next_offset; offset splits a Unicode character" }
        val end = endAt(entry.text, offset, PAGE_CHARS)
        val metadata = buildJsonObject {
            put("result_id", id); put("offset", offset); put("next_offset", end)
            put("more_output", end < entry.text.length)
            put("retained_chars", entry.text.length); put("provider_chars", entry.originalChars)
            put("retention_truncated", entry.originalChars > entry.text.length)
            put("scope", "captured_provider_text"); put("upstream_completeness", "not_established")
        }
        val status = when (entry.state) {
            AgentToolEvidenceState.FAILED -> "ERROR: captured LSP response\n"
            AgentToolEvidenceState.DENIED -> "DENIED: captured LSP response\n"
            AgentToolEvidenceState.UNAVAILABLE -> "LSP unavailable: captured response\n"
            else -> ""
        }
        return status + "LSP_RESULT_PAGE\n" + metadata + "\n" +
            "Captured provider text; page completion does not certify all server results or the whole workspace. " +
            (if (end < entry.text.length) "Continue this same tool/path/operation/position/query with result_id and next_offset as offset.\n"
             else "End of retained text.\n") +
            (if (entry.originalChars > entry.text.length) "Result exceeds retention limit; omitted text requires a narrower request.\n" else "") +
            "LSP_PAGE_TEXT\n" + entry.text.substring(offset, end)
    }

    private fun normalizedPath(raw: String): String {
        require(!File(raw).isAbsolute && '\u0000' !in raw) { "Expected a relative LSP path" }
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." }) { "Invalid LSP path" }
        return parts.joinToString("/")
    }

    private fun endAt(text: String, offset: Int, size: Int): Int {
        var end = minOf(text.length, offset + size)
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return end
    }

    companion object {
        const val PAGE_CHARS = 5_000
        const val MAX_RESULT_CHARS = 64_000
        const val MAX_TOTAL_CHARS = 128_000
        const val MAX_RESULTS = 4
        const val MAX_AGE_NANOS = 3L * 60L * 1_000_000_000L
    }
}

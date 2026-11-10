package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class AgentSearchResultPages(private val now: () -> Long = System::nanoTime) {
    private data class Request(val query: String, val maxResults: Int, val options: TextSearchOptions, val dependencies: Boolean)
    private data class Entry(val request: Request, val page: TextSearchPage, val capturedHits: Int,
        val snapshot: AgentSearchSnapshot, val owner: String?, val scope: String, val root: File,
        val documents: WorkspaceDocumentAuthority?, val created: Long, val chars: Int)
    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false

    suspend fun execute(args: JsonObject, c: AgentTools.Ctx): String = execute(args, AgentReadContext(c))

    suspend fun execute(args: JsonObject, c: AgentReadContext): String {
        currentCoroutineContext().ensureActive()
        val query = string(args, "query") ?: error("Missing query")
        require(WorkspaceSearchInput.admitted(query)) { "query too long" }
        val request = Request(query, integer(args, "max_results") ?: 100, TextSearchOptions(
            boolean(args, "case_sensitive"), boolean(args, "whole_word"), string(args, "include_glob").orEmpty()),
            boolean(args, "include_dependencies"))
        require(request.maxResults in 1..500) { "max_results must be between 1 and 500" }
        val id = string(args, "result_id")?.also { require(it.matches(Regex("[a-f0-9-]{36}"))) { "Invalid search result_id" } }
        val offset = integer(args, "offset") ?: 0
        require(offset >= 0 && (id != null || offset == 0)) { "Use result_id with the returned next_offset" }
        synchronized(entries) { checkOpen(); prune() }
        val search = AgentWorkspaceSearch(c.files, c.documentAuthority)
        if (id != null) {
            val entry = synchronized(entries) { entries[id] }
                ?: return "ERROR: Search result expired or belongs to another run. Repeat the original query without result_id or offset."
            require(entry.request == request && entry.owner == c.sessionId && entry.scope == c.permissionScope &&
                entry.documents === c.documentAuthority && entry.root == c.files.root.absoluteFile) {
                "Search result does not belong to this request or workspace"
            }
            require(offset <= entry.page.hits.size) { "Search offset is beyond the retained matches" }
            val end = search.endOffset(entry.page, offset)
            val validation = validate(entry, offset, end, c)
            if (validation == null) {
                synchronized(entries) { entries.remove(id) }
                return stale()
            }
            return synchronized(entries) {
                checkOpen(); prune()
                check(entries[id] === entry) { "Search result expired; repeat the original query" }
                render(id, entry, offset, end, validation, search)
            }
        }
        if (query.length < 2) return "(query too short)"
        val snapshot = withContext(Dispatchers.IO) { AgentSearchSnapshot(c.files.root) }
        val page = search.capture(query, request.maxResults, request.options, request.dependencies, snapshot)
        currentCoroutineContext().ensureActive()
        var chars = 0
        val kept = page.hits.takeWhile { hit ->
            val size = search.line(hit).length + 1
            (chars + size <= MAX_RESULT_CHARS).also { if (it) chars += size }
        }
        withContext(Dispatchers.IO) { snapshot.retain(kept.mapTo(hashSetOf()) { it.path }) }
        val entry = Entry(request, page.copy(hits = kept), page.hits.size, snapshot, c.sessionId, c.permissionScope,
            c.files.root.absoluteFile, c.documentAuthority, now(), chars)
        val end = search.endOffset(entry.page, 0)
        val validation = validate(entry, 0, end, c) ?: return stale()
        return synchronized(entries) {
            checkOpen(); prune()
            if (end == page.hits.size) return@synchronized search.format(page)
            while (entries.size >= MAX_RESULTS || entries.values.sumOf { it.chars } + chars > MAX_TOTAL_CHARS)
                entries.remove(entries.keys.first())
            val token = UUID.randomUUID().toString()
            entries[token] = entry
            render(token, entry, 0, end, validation, search)
        }
    }

    fun close() = synchronized(entries) { closed = true; entries.clear() }

    private suspend fun validate(entry: Entry, offset: Int, end: Int, c: AgentReadContext): Long? = try {
        withContext(Dispatchers.IO) {
            entry.snapshot.validate(entry.page.hits.subList(offset, end).mapTo(linkedSetOf()) { it.path }, c.documentAuthority)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun render(id: String, entry: Entry, offset: Int, end: Int, validationBytes: Long, search: AgentWorkspaceSearch): String {
        check(end > offset || offset == entry.page.hits.size) { "Search match exceeds page size; narrow the query" }
        val metadata = buildJsonObject {
            put("result_id", id); put("offset", offset); put("next_offset", end); put("more_output", end < entry.page.hits.size)
            put("retained_matches", entry.page.hits.size); put("captured_matches", entry.capturedHits)
            put("retention_truncated", entry.page.hits.size < entry.capturedHits); put("validation_bytes", validationBytes)
            put("scope", "captured_matches"); put("freshness", "returned_sources_checked")
        }
        return "SEARCH_RESULT_PAGE\n$metadata\n" +
            "Captured matches; only returned sources were checked. This is not an atomic workspace snapshot. " +
            "New matches need a new search; use read_file before editing.\n" +
            (if (end < entry.page.hits.size) "Continue with the same query/options, result_id and next_offset as offset.\n" else "End of retained matches.\n") +
            (if (entry.capturedHits > entry.page.hits.size) "Retention limit reached; narrow the query to find omitted matches.\n" else "") +
            search.format(entry.page, offset, end)
    }

    private fun stale() = "ERROR: Search result is stale or its sources could not be verified. No workspace data was changed. Repeat the original query without result_id or offset."
    private fun checkOpen() = check(!closed) { "Search result run has ended" }
    private fun prune() { val time = now(); entries.entries.removeAll { time - it.value.created >= MAX_AGE_NANOS } }
    private fun string(args: JsonObject, key: String): String? = args[key]?.let {
        require(it is JsonPrimitive && it.isString) { "$key must be a string" }; it.content
    }
    private fun integer(args: JsonObject, key: String): Int? = args[key]?.let {
        require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "$key must be an integer" }; it.intOrNull
    }
    private fun boolean(args: JsonObject, key: String): Boolean = args[key]?.let {
        require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "$key must be a boolean" }; it.booleanOrNull
    } ?: false

    companion object {
        const val MAX_RESULT_CHARS = 128_000
        const val MAX_TOTAL_CHARS = 256_000
        const val MAX_RESULTS = 4
        const val MAX_AGE_NANOS = 3L * 60L * 1_000_000_000L
    }
}

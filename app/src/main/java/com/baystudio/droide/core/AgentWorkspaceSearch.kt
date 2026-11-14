package com.baystudio.droide.core

/** Shares the IDE's literal matcher, ignore rules and live-document authority. */
class AgentWorkspaceSearch(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
) {
    suspend fun search(query: String, maxResults: Int = 100, options: TextSearchOptions = TextSearchOptions(),
                       includeDependencies: Boolean = false): String {
        if (WorkspaceSearchInput.admitted(query) && query.length < 2) return "(query too short)"
        return format(capture(query, maxResults, options, includeDependencies))
    }

    internal suspend fun capture(query: String, maxResults: Int, options: TextSearchOptions,
                                 includeDependencies: Boolean, snapshot: AgentSearchSnapshot? = null): TextSearchPage {
        require(WorkspaceSearchInput.admitted(query)) { "query too long" }
        require(maxResults in 1..500) { "max_results must be between 1 and 500" }
        return files.searchPageBounded(query, maxResults, options = options,
            documents = snapshot?.observe(documents) ?: documents, includeDependencies = includeDependencies,
            openInput = { snapshot?.open(it) ?: it.inputStream() })
    }

    internal fun endOffset(page: TextSearchPage, offset: Int): Int {
        var size = 0
        var end = offset
        while (end < page.hits.size) {
            val length = line(page.hits[end]).length + 1
            if (size + length > 8_000) break
            size += length
            end++
        }
        return end
    }

    internal fun line(hit: TextSearchHit) = "${hit.path}:${hit.line}: ${hit.excerpt} [column=${hit.column}]"

    internal fun format(page: TextSearchPage, offset: Int = 0, end: Int = endOffset(page, offset)): String {
        val hits = page.hits.subList(offset, end).map(::line)
        val stats = page.searchStats
        return buildString {
            if (page.dirtyOverlays > 0 || page.diskExcluded > 0)
                appendLine("[DROIDE_SEARCH_STATE dirty_overlays=${page.dirtyOverlays} disk_excluded=${page.diskExcluded}]")
            appendLine("[DROIDE_SEARCH_PAGE returned=${hits.size} result_limit=${page.resultLimitReached} " +
                "scan_limit=${page.scanLimitReached} output_limit=${end < page.hits.size} " +
                "scanned_entries=${page.scannedEntries} " +
                "scan_stop=${page.scanStopReasons.joinToString(",") { it.name.lowercase() }.ifEmpty { "none" }} " +
                "search_stop=${page.searchStopReasons.joinToString(",") { it.name.lowercase() }.ifEmpty { "none" }} " +
                "capture_complete=${page.dirtyCaptureComplete} source_opens=${stats.sourceOpens} reads=${stats.readCalls} " +
                "disk_bytes=${stats.diskBytes} lines=${stats.lines} match_units=${stats.matchUnits} " +
                "documents=${stats.documentsInspected} snapshots=${stats.snapshotsCaptured} " +
                "snapshot_chars=${stats.snapshotChars} dirty_compare_units=${stats.dirtyComparisonUnits} " +
                "unreadable=${page.unreadableFiles} skipped_large=${page.skippedLargeFiles} " +
                "skipped_dirty=${page.skippedDirtyBuffers}]")
            hits.forEach(::appendLine)
            if (hits.isEmpty()) appendLine("(tidak ada hasil)")
            if (page.scanLimitReached) {
                appendLine("Results are incomplete because search stopped; use a known explicit path when available.")
            } else if (page.resultLimitReached || end < page.hits.size) {
                appendLine("Results are incomplete; narrow include_glob or query, or increase max_results up to 500.")
            }
        }.trimEnd()
    }
}

package com.baystudio.droide.core

enum class DiscoveryStopReason { PROBE_LIMIT, DIRECTORY_LIMIT, DEPTH_LIMIT, PATH_LIMIT, DEADLINE }

/** An incomplete enumeration cannot establish a correctly sorted directory page or cursor. */
class DirectoryScanLimitException(
    val relativePath: String,
    val stopReasons: Set<DiscoveryStopReason>,
    val scannedEntries: Int,
    val directoryProbes: Int,
    val directoryOpenAttempts: Int,
) : IllegalStateException(
    "Directory listing incomplete for ${relativePath.ifEmpty { "." }} after $scannedEntries entries " +
        "(${stopReasons.joinToString { it.name.lowercase() }}); no sorted page was returned. " +
        "Open a known subdirectory or try refreshing later.",
)

 
data class FileQueryPage(
    val paths: List<String>,
    val scannedEntries: Int,
    val scanLimitReached: Boolean,
    val resultLimitReached: Boolean,
    val scanStopReasons: Set<DiscoveryStopReason> = emptySet(),
    val searchStopReasons: Set<WorkspaceSearchStopReason> = emptySet(),
    val searchStats: WorkspaceSearchStats = WorkspaceSearchStats(),
)

data class TextSearchHit(val path: String, val line: Int, val column: Int, val excerpt: String)

data class TextSearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val includeGlob: String = "",
)

data class TextSearchPage(
    val hits: List<TextSearchHit>,
    val scannedEntries: Int,
    val scanLimitReached: Boolean,
    val resultLimitReached: Boolean,
    val unreadableFiles: Int,
    val skippedLargeFiles: Int = 0,
    val skippedDirtyBuffers: Int = 0,
    val scanStopReasons: Set<DiscoveryStopReason> = emptySet(),
    val searchStopReasons: Set<WorkspaceSearchStopReason> = emptySet(),
    val searchStats: WorkspaceSearchStats = WorkspaceSearchStats(),
    val dirtyOverlays: Int = 0,
    val diskExcluded: Int = 0,
    val dirtyCaptureComplete: Boolean = true,
)

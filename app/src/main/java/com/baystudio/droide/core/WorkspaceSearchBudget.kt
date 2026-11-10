package com.baystudio.droide.core

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

object WorkspaceSearchInput {
    const val MAX_RAW_INPUT_CHARS = 4_096
    fun admitted(value: String): Boolean = value.length <= MAX_RAW_INPUT_CHARS
}

enum class WorkspaceSearchStopReason {
    INPUT_LIMIT, EXCLUSION_LIMIT, UNSUPPORTED_AUTHORITY, DOCUMENT_LIMIT, SNAPSHOT_LIMIT,
    SNAPSHOT_PATH_LIMIT, SNAPSHOT_TEXT_LIMIT, DIRTY_COMPARISON_LIMIT,
    SOURCE_OPEN_LIMIT, READ_LIMIT, BYTE_LIMIT, FILE_BYTE_LIMIT, LINE_LIMIT, LINE_LENGTH_LIMIT,
    MATCH_LIMIT, DEADLINE,
}

data class WorkspaceSearchStats(
    val sourceOpens: Int = 0,
    val readCalls: Int = 0,
    val diskBytes: Long = 0,
    val lines: Int = 0,
    val matchUnits: Long = 0,
    val documentsInspected: Int = 0,
    val snapshotsCaptured: Int = 0,
    val snapshotChars: Long = 0,
    val dirtyComparisonUnits: Long = 0,
)

internal data class WorkspaceSearchLimits(
    val rawInputChars: Int = WorkspaceSearchInput.MAX_RAW_INPUT_CHARS,
    val excludedPaths: Int = 4_096,
    val excludedPathChars: Long = 1_048_576,
    val pathChars: Int = 4_096,
    val documents: Int = 4_096,
    val snapshots: Int = 256,
    val snapshotPathChars: Long = 262_144,
    val snapshotChars: Long = 33_554_432,
    val dirtyComparisonUnits: Long = 16_777_216,
    val sourceOpens: Int = 4_096,
    val readCalls: Int = 16_384,
    val diskBytes: Long = 67_108_864,
    val fileBytes: Long = 20_971_520,
    val lines: Int = 250_000,
    val lineChars: Int = 65_536,
    val matchUnits: Long = 50_000_000,
    val durationNanos: Long = 5_000_000_000L,
) {
    init {
        require(rawInputChars >= 1 && pathChars >= 1 && lineChars >= 1)
        require(excludedPaths >= 0 && excludedPathChars >= 0 && documents >= 0 && snapshots >= 0)
        require(snapshotPathChars >= 0 && snapshotChars >= 0 && dirtyComparisonUnits >= 0)
        require(sourceOpens >= 0 && readCalls >= 0 && diskBytes >= 0 && fileBytes >= 0)
        require(lines >= 0 && matchUnits >= 0 && durationNanos >= 0)
    }
}

/** All phases of a search share this epoch, including directory traversal. */
internal class WorkspaceRequestDeadline(
    private val durationNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    fun expired(): Boolean = nanoTime() - started >= durationNanos
}

internal class WorkspaceSearchLimitException(val reason: WorkspaceSearchStopReason) :
    RuntimeException("Search incomplete: ${reason.name.lowercase()}")

internal class WorkspaceSearchBudget(
    val limits: WorkspaceSearchLimits,
    private val context: CoroutineContext,
    val deadline: WorkspaceRequestDeadline = WorkspaceRequestDeadline(limits.durationNanos),
) {
    private val reasons = linkedSetOf<WorkspaceSearchStopReason>()
    private var sourceOpens = 0
    private var readCalls = 0
    private var diskBytes = 0L
    private var lines = 0
    private var matchUnits = 0L
    private var documents = 0
    private var snapshots = 0
    private var snapshotPathChars = 0L
    private var snapshotChars = 0L
    private var dirtyComparisonUnits = 0L
    val stopReasons: Set<WorkspaceSearchStopReason> get() = reasons.toSet()
    val incomplete: Boolean get() = reasons.isNotEmpty()
    val remainingBytes: Long get() = limits.diskBytes - diskBytes
    val stats: WorkspaceSearchStats get() = WorkspaceSearchStats(
        sourceOpens, readCalls, diskBytes, lines, matchUnits, documents, snapshots,
        snapshotChars, dirtyComparisonUnits,
    )

    fun stop(reason: WorkspaceSearchStopReason): Nothing {
        reasons += reason
        throw WorkspaceSearchLimitException(reason)
    }
    fun checkpoint() {
        context.ensureActive()
        if (deadline.expired()) stop(WorkspaceSearchStopReason.DEADLINE)
    }
    fun input(value: String) {
        checkpoint()
        if (value.length > limits.rawInputChars) stop(WorkspaceSearchStopReason.INPUT_LIMIT)
    }
    fun beforeOpen() {
        checkpoint()
        if (sourceOpens >= limits.sourceOpens) stop(WorkspaceSearchStopReason.SOURCE_OPEN_LIMIT)
        sourceOpens++
    }
    fun beforeRead() {
        checkpoint()
        if (readCalls >= limits.readCalls) stop(WorkspaceSearchStopReason.READ_LIMIT)
        readCalls++
    }
    fun bytesRead(count: Int) { if (count > 0) diskBytes += count.toLong() }
    fun beforeLine() {
        checkpoint()
        if (lines >= limits.lines) stop(WorkspaceSearchStopReason.LINE_LIMIT)
        lines++
    }
    fun beforeMatch(units: Long) {
        checkpoint()
        require(units >= 0)
        if (units > limits.matchUnits - matchUnits) stop(WorkspaceSearchStopReason.MATCH_LIMIT)
        matchUnits += units
    }
    fun beforeDocument() {
        checkpoint()
        if (documents >= limits.documents) stop(WorkspaceSearchStopReason.DOCUMENT_LIMIT)
        documents++
    }
    fun beforeSnapshot(path: String, content: String) {
        checkpoint()
        if (snapshots >= limits.snapshots) stop(WorkspaceSearchStopReason.SNAPSHOT_LIMIT)
        if (path.length > limits.pathChars || path.length.toLong() > limits.snapshotPathChars - snapshotPathChars)
            stop(WorkspaceSearchStopReason.SNAPSHOT_PATH_LIMIT)
        if (content.length.toLong() > limits.snapshotChars - snapshotChars)
            stop(WorkspaceSearchStopReason.SNAPSHOT_TEXT_LIMIT)
        snapshots++
        snapshotPathChars += path.length.toLong()
        snapshotChars += content.length.toLong()
    }
    fun sameContent(content: String, saved: String): Boolean {
        checkpoint()
        if (content === saved) return true
        if (content.length != saved.length) return false
        var offset = 0
        while (offset < content.length) {
            checkpoint()
            val count = minOf(4_096, content.length - offset)
            if (count.toLong() > limits.dirtyComparisonUnits - dirtyComparisonUnits)
                stop(WorkspaceSearchStopReason.DIRTY_COMPARISON_LIMIT)
            dirtyComparisonUnits += count.toLong()
            if (!content.regionMatches(offset, saved, offset, count, ignoreCase = false)) return false
            offset += count
        }
        return true
    }
}

/** A producer must admit entries before creating wrappers, not cap an already-materialized list. */
class WorkspaceSearchCaptureRequest internal constructor(private val budget: WorkspaceSearchBudget) {
    fun beforeDocument() = budget.beforeDocument()
    fun beforeSnapshot(path: String, content: String) = budget.beforeSnapshot(path, content)
    fun sameContent(content: String, saved: String): Boolean = budget.sameContent(content, saved)
    fun checkpoint() = budget.checkpoint()
}

data class WorkspaceSearchCapture(
    val snapshots: List<WorkspaceDocumentSnapshot> = emptyList(),
    val supported: Boolean = true,
)

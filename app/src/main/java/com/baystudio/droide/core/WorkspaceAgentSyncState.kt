package com.baystudio.droide.core

enum class WorkspaceAgentSyncPhase { ISOLATED, RUNNING, REVIEW, APPLYING, SYNCED, PENDING, INTERRUPTED }

data class WorkspaceAgentSyncState(
    val phase: WorkspaceAgentSyncPhase = WorkspaceAgentSyncPhase.ISOLATED,
    val sourceRoot: String,
    val executionRoot: String = "",
    val pendingPaths: List<String> = emptyList(),
    val pendingCount: Int = 0,
    val notice: String? = null,
) {
    val label: String get() = when (phase) {
        WorkspaceAgentSyncPhase.ISOLATED -> "CLI uses a separate review workspace"
        WorkspaceAgentSyncPhase.RUNNING -> "CLI working in its review workspace"
        WorkspaceAgentSyncPhase.REVIEW -> "Review changes before applying to the project"
        WorkspaceAgentSyncPhase.APPLYING -> "Applying approved files to the project…"
        WorkspaceAgentSyncPhase.SYNCED -> "Reviewed files synchronized"
        WorkspaceAgentSyncPhase.PENDING -> "$pendingCount file(s) need review · CLI copy retained"
        WorkspaceAgentSyncPhase.INTERRUPTED -> "Synchronization incomplete · CLI files retained"
    }
}

data class WorkspaceAgentSyncResult(val changedFiles: List<String>, val pendingFiles: List<String>)

package com.baystudio.droide.core

/** Explicit persistence is separate from buffer replacement. Never infer the current version. */
data class WorkspaceDocumentSaveRequest(
    val path: String,
    val expected: WorkspaceDocumentVersion,
    /** Present only for automatic persistence of an actual completed Agent edit. */
    val agentEdit: WorkspaceDocumentMutationResult.Applied? = null,
) {
    init {
        require(path.isNotBlank() && expected.documentId != null) { "Save requires a path and document identity" }
        agentEdit?.let {
            require(it.before.path == path && it.after.path == path && it.after.version == expected &&
                it.before.documentId == expected.documentId) { "Save does not match the applied edit" }
        }
    }
}

data class WorkspaceDocumentSaveIssue(val path: String, val message: String)

data class WorkspaceDocumentSaveReport(
    val savedPaths: List<String> = emptyList(),
    val issues: List<WorkspaceDocumentSaveIssue> = emptyList(),
) {
    val ready: Boolean get() = issues.isEmpty()
    fun summary(): String = buildString {
        append("[DROIDE_SAVE saved=").append(savedPaths.size).append(" attention=").append(issues.map { it.path }.distinct().size).append("]")
        if (savedPaths.isNotEmpty()) append("\nSaved: ").append(savedPaths.joinToString())
        issues.forEach { append("\n").append(it.path).append(": ").append(it.message) }
        if (!ready) append("\nEdits were kept. Do not repeat the edit. Read the current document and resolve Save before build/test; save_files accepts explicit paths and versions.")
    }
    companion object {
        fun unavailable(requests: List<WorkspaceDocumentSaveRequest>) = WorkspaceDocumentSaveReport(
            issues = requests.map { WorkspaceDocumentSaveIssue(it.path, "Editor save authority is unavailable; not saved to disk") })
    }
}

/** Call only after the edit transaction/history commits, never from rollback or cancellation cleanup. */
internal object AgentEditPersistence {
    suspend fun save(
        documents: WorkspaceDocumentAuthority?,
        edits: List<WorkspaceDocumentMutationResult.Applied>,
        requireCurrent: () -> Unit = {},
    ): String {
        if (edits.isEmpty()) return ""
        val report = runSuspendCatching {
            val requests = edits.map { WorkspaceDocumentSaveRequest(it.after.path, it.after.version, it) }
            documents?.saveDocuments(requests, requireCurrent) ?: WorkspaceDocumentSaveReport.unavailable(requests)
        }.getOrElse { failure -> WorkspaceDocumentSaveReport(issues = edits.map {
            WorkspaceDocumentSaveIssue(it.after.path, "Save did not complete: ${failure.message ?: failure.javaClass.simpleName}")
        }) }
        return "\n" + report.summary()
    }

    suspend fun commandBlocker(documents: WorkspaceDocumentAuthority?): String? {
        val paths = documents?.pendingAgentSavePaths().orEmpty()
        return if (paths.isEmpty()) null else "ERROR: AGENT_EDITS_NOT_SAVED. No command was launched. " +
            "Resolve Save for ${paths.joinToString()} before running against disk. Read current versions and use save_files; do not repeat the edits."
    }
}

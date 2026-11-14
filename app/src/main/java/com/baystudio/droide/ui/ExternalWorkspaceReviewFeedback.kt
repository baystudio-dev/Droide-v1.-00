package com.baystudio.droide.ui

import com.baystudio.droide.core.WorkspaceAgentSyncPhase
import com.baystudio.droide.core.WorkspaceAgentSyncState

/** Presentation only. Never changes reconciliation, permission, or retained-file state. */
internal class ExternalWorkspaceReviewFeedback {
    private val lastPending = linkedMapOf<String, String>()

    fun messages(key: String, changed: List<String>, pending: List<String>, state: WorkspaceAgentSyncState?, manual: Boolean): List<String> {
        val messages = mutableListOf<String>()
        if (changed.isNotEmpty()) messages += "Workspace changes applied: ${changed.joinToString(limit = 8)}"
        val interrupted = state?.phase == WorkspaceAgentSyncPhase.INTERRUPTED
        if (pending.isNotEmpty() || interrupted) {
            val detail = state?.notice?.trim()?.takeIf { it.isNotEmpty() }?.take(2_000)
            val heading = if (interrupted) "Workspace synchronization incomplete." else
                "Workspace changes need review: ${pending.joinToString(limit = 8)}."
            val text = listOfNotNull(heading, detail, "Pending CLI work is retained. Open Files to inspect the reason and review again after resolving it.").joinToString(" ")
            // Include the complete pending set, even when its visible summary is truncated.
            val identity = listOf(state?.sourceRoot, state?.executionRoot, state?.phase, pending.sorted(), detail).toString()
            if (manual || changed.isNotEmpty() || lastPending[key] != identity) messages += text
            lastPending.remove(key)
            lastPending[key] = identity
            if (lastPending.size > 32) lastPending.remove(lastPending.keys.first())
        } else {
            lastPending.remove(key)
            if (manual) messages += if (changed.isEmpty()) "Workspace review complete. No pending file changes remain."
                else "Workspace review complete. All reviewed changes were applied."
        }
        return messages
    }
}

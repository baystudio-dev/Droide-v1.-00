package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.baystudio.droide.core.WorkspaceAgentSyncPhase
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.WorkspaceAgentSyncState

@Composable
internal fun AgentWorkspaceSyncNotice(state: WorkspaceAgentSyncState, busy: Boolean, onReview: () -> Unit) {
    AgentWorkspaceSyncNotice(state, busy, onReview, null)
}

@Composable
internal fun AgentWorkspaceSyncNotice(state: WorkspaceAgentSyncState, busy: Boolean, onReview: () -> Unit,
                                     onRecovery: (() -> Unit)?) {
    var details by remember(state.sourceRoot) { mutableStateOf(false) }
    var reviewRequested by remember(state.sourceRoot) { mutableStateOf(false) }
    LaunchedEffect(busy) { if (!busy) reviewRequested = false }
    val needsAttention = state.pendingCount > 0 || state.phase == WorkspaceAgentSyncPhase.PENDING ||
        state.phase == WorkspaceAgentSyncPhase.INTERRUPTED || state.notice != null
    val filesLabel = if (state.pendingCount > 0) "Files (${state.pendingCount})" else "Files"
    TextButton(onClick = { details = true }, modifier = Modifier.semantics {
        contentDescription = if (needsAttention) "Review CLI files. ${state.label}" else "Review CLI files"
    }, colors = ButtonDefaults.textButtonColors(
        contentColor = if (needsAttention) DroideColors.Error else MaterialTheme.colorScheme.primary,
    )) { Text(filesLabel, maxLines = 1) }
    // Permission sheets own the modal surface while a requested review is running.
    if (details && !(reviewRequested && busy)) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text("CLI workspace synchronization") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.label)
                Text("Editor, Explorer, terminal and Droide Agent use:\n${state.sourceRoot}", fontFamily = FontFamily.Monospace)
                Text("External CLI works separately in:\n${state.executionRoot.ifBlank { "Not prepared yet" }}", fontFamily = FontFamily.Monospace)
                Text("CLI commands run in this copy. Approved edits may already be in the project. Review files syncs eligible changes without another prompt. Permitted edits apply immediately; others need approval. Unsaved or concurrent edits remain as conflicts.")
                Text("Automatic sync and Review files exclude dependency folders such as node_modules, Git and Gradle metadata, internal or sensitive files, and symbolic links. These stay in their original location.")
                state.notice?.let { Text(it) }
                onRecovery?.let { recover ->
                    TextButton(enabled = !busy, onClick = { details = false; recover() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Retained versions")
                    }
                }
                if (state.pendingPaths.isNotEmpty()) Text(state.pendingPaths.joinToString("\n") +
                    if (state.pendingCount > state.pendingPaths.size) "\n… ${state.pendingCount - state.pendingPaths.size} more" else "",
                    fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { reviewRequested = true; onReview() }) { Text("Review files") } },
        dismissButton = { TextButton(onClick = { details = false }) { Text("Close") } },
    )
}

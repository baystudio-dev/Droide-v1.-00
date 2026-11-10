package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.DroideSession
import com.baystudio.droide.core.SessionManager
import com.baystudio.droide.core.SessionSummary
import com.baystudio.droide.core.runSuspendCatching

// Local session browser: reopen, fork, and delete durable agent sessions.

@Composable
fun SessionDrawer(
    manager: SessionManager,
    onPick: (DroideSession) -> Unit,
    onDismiss: (() -> Unit)? = null,
    showHeader: Boolean = true,
    selectionEnabled: Boolean = true,
    onDeleted: (String) -> Unit = {},
) {
    var sessions by remember(manager) { mutableStateOf<List<SessionSummary>>(emptyList()) }
    val scope = rememberWorkspaceCoroutineScope()
    var actionError by remember(manager) { mutableStateOf<String?>(null) }
    var working by remember(manager) { mutableStateOf(false) }
    val titleRevision by manager.titleRevision.collectAsState()
    LaunchedEffect(manager, titleRevision) {
        runSuspendCatching { manager.listSummaries() }
            .onSuccess { sessions = it }.onFailure { actionError = it.message ?: "Cannot load saved sessions" }
    }

    fun runAction(block: suspend () -> Unit) {
        if (working) return
        working = true
        actionError = null
        scope.launchUiCatching(onError = { actionError = it.message ?: "Session action failed" }) {
            try { block() } finally { working = false }
        }
    }
    fun refresh() { runAction { sessions = manager.listSummaries() } }

    Column(Modifier.fillMaxWidth()) {
        if (showHeader) DroidePanelHeader(title = "Agent Sessions", onClose = onDismiss, actions = {
            IconButton(onClick = { refresh() }, enabled = !working) { Icon(Icons.Default.Refresh, "Refresh sessions") }
        })
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { refresh() }, enabled = !working) { Icon(Icons.Default.Refresh, "Refresh Droide history") }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actionError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Keep a viewport while the asynchronous disk read replaces the empty state. A zero-sized lazy
        // list cannot compose its incoming rows.
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sessions, key = { it.id }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ListItem(
                            headlineContent = { Text(s.title, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = {
                                Column {
                                    Text("${s.id} · ${s.messageCount} messages · ${s.mode}", style = MaterialTheme.typography.labelSmall)
                                    if (s.parentId != null) Text("fork of ${s.parentId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.clickable(enabled = !working && selectionEnabled) { runAction { onPick(manager.get(s.id) ?: error("This session is no longer available")) } }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { runAction {
                                checkNotNull(manager.fork(s.id)) { "Cannot fork this session. It needs a complete conversation history." }
                                sessions = manager.listSummaries()
                            } }, enabled = !working && selectionEnabled, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.CallSplit, "Fork", modifier = Modifier.size(18.dp)) }
                            IconButton(onClick = { runAction {
                                manager.delete(s.id)
                                onDeleted(s.id)
                                sessions = manager.listSummaries()
                            } }, enabled = !working && selectionEnabled, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            if (sessions.isEmpty()) item { Text("No saved sessions", style = MaterialTheme.typography.bodySmall) }
        }
        }
    }
}

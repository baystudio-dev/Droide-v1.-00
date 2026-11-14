package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** One workspace history entry point; the builtin protocol archive stays separate from CLI transcripts. */
@Composable
fun AgentHistoryDrawer(
    manager: SessionManager,
    pluginHistory: PluginConversationHistory?,
    selectedBackendId: String,
    selectionEnabled: Boolean,
    onPickDroide: (DroideSession) -> Unit,
    onPickPlugin: (PluginHistorySummary) -> Unit,
    onDismiss: () -> Unit,
    onDroideDeleted: (String) -> Unit = {},
    onPluginDeleted: (PluginHistorySummary) -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(if (selectedBackendId == "droide") 0 else 1) }
    Column(Modifier.fillMaxWidth()) {
        DroidePanelHeader(title = "Agent History", onClose = onDismiss)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Droide History") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Plugin History") })
        }
        if (!selectionEnabled) Text(
            "A task is running. You can browse history; finish it or use Stop before opening another conversation.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        )
        if (tab == 0) SessionDrawer(manager, onPickDroide, showHeader = false, selectionEnabled = selectionEnabled, onDeleted = onDroideDeleted)
        else if (pluginHistory != null) PluginHistoryDrawer(
            pluginHistory, selectedBackendId.takeUnless { it == "droide" }, selectionEnabled, onPickPlugin, onPluginDeleted,
        )
        else Text("Plugin history is unavailable in this workspace.", Modifier.padding(16.dp))
    }
}

@Composable
private fun PluginHistoryDrawer(
    history: PluginConversationHistory,
    initialAgentId: String?,
    selectionEnabled: Boolean,
    onPick: (PluginHistorySummary) -> Unit,
    onDeleted: (PluginHistorySummary) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(initialAgentId) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterMenu by remember { mutableStateOf(false) }
    var entries by remember(history) { mutableStateOf<List<PluginHistorySummary>>(emptyList()) }
    var cursor by remember(history) { mutableStateOf<PluginHistoryCursor?>(null) }
    var loading by remember(history) { mutableStateOf(false) }
    var actionWorking by remember(history) { mutableStateOf(false) }
    var error by remember(history) { mutableStateOf<String?>(null) }
    var refresh by remember(history) { mutableIntStateOf(0) }
    var olderPages by remember(history) { mutableIntStateOf(0) }
    var deleteTarget by remember(history) { mutableStateOf<PluginHistorySummary?>(null) }
    val scope = rememberWorkspaceCoroutineScope()
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val working = loading || actionWorking

    LaunchedEffect(history, filter, query, refresh) {
        loading = true
        error = null
        entries = emptyList()
        cursor = null
        olderPages = 0
        try {
            if (query.isNotEmpty()) delay(180)
            val result = history.listPage(filter, query)
            entries = result.entries
            cursor = result.nextCursor
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Cannot load saved plugin conversations" }
        finally { loading = false }
    }

    fun olderPage() {
        val next = cursor ?: return
        if (working) return
        val requestedFilter = filter
        val requestedQuery = query
        actionWorking = true
        scope.launchUiCatching(onError = { if (filter == requestedFilter && query == requestedQuery) error = it.message ?: "Cannot load older conversations" }) {
            try {
                val result = history.listPage(requestedFilter, requestedQuery, next)
                if (filter == requestedFilter && query == requestedQuery) {
                    entries = (entries + result.entries).distinctBy { it.id }.takeLast(160)
                    cursor = result.nextCursor
                    olderPages++
                }
            } finally { actionWorking = false }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                TextButton(onClick = { filterMenu = true }) {
                    Text(WorkspaceAgentPluginCatalog.entries.firstOrNull { it.id == filter }?.displayName ?: filter ?: "All plugins")
                    Icon(Icons.Default.ArrowDropDown, "Filter plugin history")
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    DropdownMenuItem(text = { Text("All plugins") }, onClick = { filter = null; filterMenu = false })
                    WorkspaceAgentPluginCatalog.entries.forEach { spec ->
                        DropdownMenuItem(text = { Text(spec.displayName) }, onClick = { filter = spec.id; filterMenu = false })
                    }
                }
            }
            IconButton(onClick = { refresh++ }, enabled = !working) { Icon(Icons.Default.Refresh, "Refresh plugin history") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(200) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search plugin history") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
        )
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
        if (olderPages > 0) TextButton(onClick = { refresh++ }, enabled = !working) { Text("Latest conversations") }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.id }) { entry ->
                val agentName = WorkspaceAgentPluginCatalog.entries.firstOrNull { it.id == entry.agentId }?.displayName ?: entry.agentId
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = {
                                Column {
                                    Text("$agentName · ${entry.messageCount} messages", style = MaterialTheme.typography.labelSmall)
                                    Text("${formatter.format(Date(entry.updatedAt))} · ${entry.status.name.lowercase().replaceFirstChar(Char::uppercaseChar)}", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            modifier = Modifier.weight(1f).clickable(enabled = selectionEnabled && !working) { onPick(entry) },
                        )
                        IconButton(
                            onClick = { deleteTarget = entry }, enabled = selectionEnabled && !working && entry.status != PluginHistoryStatus.RUNNING,
                        ) { Icon(Icons.Default.Delete, "Delete plugin conversation ${entry.title}") }
                    }
                }
            }
            if (entries.isEmpty() && !working && error == null) item {
                Text("No saved plugin conversations match this filter.", style = MaterialTheme.typography.bodySmall)
            }
            if (cursor != null) item {
                TextButton(onClick = { olderPage() }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Older conversations") }
            }
        }
    }
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!actionWorking) deleteTarget = null },
            title = { Text("Delete saved conversation?") },
            text = { Text("Delete “${entry.title}” from Droide history? The CLI's own history and project files are kept.") },
            confirmButton = {
                TextButton(enabled = selectionEnabled && !actionWorking, onClick = {
                    actionWorking = true
                    scope.launchUiCatching(onError = { error = it.message ?: "Cannot delete saved conversation" }) {
                        try { history.delete(entry.id); onDeleted(entry); deleteTarget = null; refresh++ }
                        finally { actionWorking = false }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(enabled = !actionWorking, onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

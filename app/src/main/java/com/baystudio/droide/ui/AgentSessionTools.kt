package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baystudio.droide.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal enum class AgentSessionTool { RESULTS, WORKFLOWS, RESUME }

@Composable
private fun AgentSessionDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.9f),
                color = DroideColors.Surface, shape = MaterialTheme.shapes.large) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, "Close $title")
                        }
                    }
                    HorizontalDivider(color = DroideColors.Border)
                    content()
                }
            }
        }
    }
}

@Composable
internal fun AgentResultsDialog(
    messages: List<AgentMessage>, live: List<AgentStreamPart>, media: List<ChatMediaRef>, workDir: File,
    historyIsWindowed: Boolean, onOpen: ((String) -> Unit)?, onExport: ((String) -> Unit)?, onDismiss: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    val snapshot = remember(refresh) { Triple(messages, live, media) }
    val page by produceState<AgentSessionResultPage?>(null, snapshot, offset) {
        value = null
        value = withContext(Dispatchers.Default) {
            AgentSessionResults.page(snapshot.first, snapshot.second, snapshot.third, offset)
        }
    }
    AgentSessionDialog("Session results", onDismiss) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (historyIsWindowed) "Results from loaded messages. Load earlier messages to see older results."
                else "Recorded files and media. Project files open their current contents.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
            TextButton(onClick = { offset = 0; refresh++ }) { Text("Refresh") }
        }
        val current = page
        if (current == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current.entries.isEmpty()) item { Text("No recorded results yet.", color = DroideColors.Muted) }
            items(current.entries, key = { it.key }) { result ->
                when (result) {
                    is AgentSessionResult.Media -> AgentMediaCard(result.ref, workDir)
                    is AgentSessionResult.File -> AgentResultFile(result, workDir, onOpen, onExport)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Page ${offset / AgentSessionResults.PAGE_SIZE + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { offset -= AgentSessionResults.PAGE_SIZE }, enabled = current != null && offset > 0) { Text("Previous") }
            TextButton(onClick = { offset += AgentSessionResults.PAGE_SIZE }, enabled = current?.hasNext == true) { Text("Next") }
        }
    }
}

@Composable
private fun AgentResultFile(result: AgentSessionResult.File, root: File, onOpen: ((String) -> Unit)?, onExport: ((String) -> Unit)?) {
    val scope = rememberWorkspaceCoroutineScope()
    var error by remember(result.path) { mutableStateOf<String?>(null) }
    var opening by remember(result.path) { mutableStateOf(false) }
    fun act(action: (String) -> Unit) {
        if (opening) return
        opening = true
        scope.launchUiCatching(onError = { error = "This file is no longer available. Check its location in Explorer." }) {
            try {
                withContext(Dispatchers.IO) { AgentWorkspaceArtifacts.requireRegular(root, File(root, result.path)) }
                action(result.path)
            } finally { opening = false }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        FileIdentityLabel(result.path, modifier = Modifier.fillMaxWidth())
        Text("${result.activity.replace('_', ' ')} · Step ${result.step}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        Row {
            if (onOpen != null) TextButton(onClick = { act(onOpen) }, enabled = !opening) { Text("Open") }
            if (onExport != null) TextButton(onClick = { act(onExport) }, enabled = !opening) { Text("Export") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider(color = DroideColors.Border)
    }
}

@Composable
internal fun AgentWorkflowsDialog(loadTemplates: suspend () -> List<AgentWorkflowTemplates.Template>,
    onSelect: (String) -> Unit, onManageSkills: (() -> Unit)?, onDismiss: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    val templates by produceState<List<AgentWorkflowTemplates.Template>?>(null) {
        try { value = loadTemplates() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; value = emptyList() }
    }
    AgentSessionDialog("Workflows", onDismiss) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Choose a reusable plan, then describe your task before sending.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted) }
            if (templates == null) item { CircularProgressIndicator(Modifier.size(24.dp)) }
            if (failed) item { Text("Could not load workflows. Check Manage Skills and reopen this view.") }
            else if (templates?.isEmpty() == true) item { Text("No invocable workflow templates. Check Manage Skills.") }
            items(templates.orEmpty(), key = { it.skill }) { template ->
                Column {
                    Text(template.title, style = MaterialTheme.typography.titleSmall)
                    Text(template.description, style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                    TextButton(onClick = { onSelect(template.invocation) }) { Text("Use template") }
                }
            }
            if (onManageSkills != null) item { TextButton(onClick = onManageSkills) { Text("Add or manage skills") } }
        }
    }
}

@Composable
internal fun AgentContinuationDialog(
    availability: AgentContinuation, messages: List<AgentMessage>, onPrepare: () -> Unit, onDismiss: () -> Unit,
) {
    val recent = remember(messages) { messages.asReversed().asSequence().mapNotNull { it.streamPart }
        .filter { it.kind == AgentStreamPartKind.TOOL }.take(12).toList().asReversed() }
    AgentSessionDialog("Continue task", onDismiss) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(availability.description, style = MaterialTheme.typography.bodyMedium) }
            if (recent.isNotEmpty()) item { Text("Recent recorded activities", style = MaterialTheme.typography.titleSmall) }
            items(recent) { part ->
                Column {
                    Text(part.toolName.ifBlank { part.title }.replace('_', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(when (part.state) {
                        AgentStreamPartState.COMPLETED -> "Completed"
                        AgentStreamPartState.ERROR -> "Failed · inspect the result"
                        AgentStreamPartState.CANCELLED -> "Stopped · check any effects"
                        else -> "Unfinished · check the current state"
                    }, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
            }
        }
        TextButton(onClick = onPrepare, enabled = availability == AgentContinuation.READY,
            modifier = Modifier.align(Alignment.End).padding(8.dp)) { Text("Prepare continuation") }
    }
}

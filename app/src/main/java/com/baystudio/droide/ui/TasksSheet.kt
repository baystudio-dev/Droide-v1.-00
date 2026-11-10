package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException

@Composable
fun TasksSheet(manager: TaskManager, onDismiss: () -> Unit, beforeRun: suspend () -> Unit, controller: WorkspaceTaskController) {
    val operation by controller.state.collectAsState()
    var tasks by remember(manager) { mutableStateOf<List<IdeTask>>(emptyList()) }
    var loading by remember(manager) { mutableStateOf(true) }
    val running = operation.activeTask
    val result = operation.lastResult
    var error by remember(manager) { mutableStateOf<String?>(null) }
    val stopping = operation.stopping

    LaunchedEffect(manager) {
        loading = true
        try {
            tasks = manager.list()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tasks = emptyList()
            error = "Could not load tasks: ${failure.message ?: failure::class.java.simpleName}"
        } finally { loading = false }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f)) {
        DroidePanelHeader(title = "Tasks", onClose = onDismiss, actions = {
            running?.let {
                OutlinedButton(onClick = {
                    controller.stop()
                }, enabled = !stopping) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp)); Text(if (stopping) "Stopping…" else "Stop")
                }
            }
        })
        if (running != null) Text(operation.message + " · continues when this panel closes", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                if (tasks.isEmpty() && error?.startsWith("Could not load tasks:") != true) item {
                    Text("No tasks found. Add tasks with command arguments to .droide/tasks.json.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(tasks) { task ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                        headlineContent = { Text(task.label) },
                        supportingContent = {
                            Column {
                                Text(task.argv.joinToString(" ").take(300), fontFamily = FontFamily.Monospace, maxLines = 2)
                                Text(
                                    when (task.executionScope) {
                                        TaskExecutionScope.AUTO -> "Auto execution environment"
                                        TaskExecutionScope.LOCAL -> "Local"
                                        TaskExecutionScope.LOCAL_LINUX_ARM64 -> "Local Linux ARM64"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = { Text(task.group, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable(enabled = running == null) {
                            error = null
                            controller.start(listOf(task), manager, beforeRun)
                        },
                    )
                    HorizontalDivider()
                }
            }
            result?.let { r ->
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.task.label}  •  exit ${r.exitCode}  •  ${r.durationMs} ms", style = MaterialTheme.typography.labelLarge)
                    Surface(Modifier.fillMaxWidth().heightIn(max = 240.dp), tonalElevation = 1.dp) {
                        Text(r.output.ifBlank { "(no output)" }, Modifier.verticalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            (error ?: operation.error)?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

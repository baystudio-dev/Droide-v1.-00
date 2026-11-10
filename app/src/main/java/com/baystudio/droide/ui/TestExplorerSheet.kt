package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.IdeTask
import com.baystudio.droide.core.TaskManager
import com.baystudio.droide.core.WorkspaceTaskController
import kotlinx.coroutines.CancellationException

@Composable
fun TestExplorerSheet(manager: TaskManager, onDismiss: () -> Unit, beforeRun: suspend () -> Unit, controller: WorkspaceTaskController) {
    val operation by controller.state.collectAsState()
    var tests by remember(manager) { mutableStateOf<List<IdeTask>>(emptyList()) }
    var loading by remember(manager) { mutableStateOf(true) }
    val running = operation.activeTask?.label
    val results = operation.results
    var error by remember(manager) { mutableStateOf<String?>(null) }
    val stopping = operation.stopping

    suspend fun refresh() {
        loading = true
        try {
            tests = manager.list().filter {
                it.group.equals("test", ignoreCase = true) || it.label.contains("test", ignoreCase = true) || it.label.contains("pytest", ignoreCase = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tests = emptyList()
            error = "Could not load test tasks: ${failure.message ?: failure::class.java.simpleName}"
        } finally { loading = false }
    }
    fun runOne(test: IdeTask) {
        error = null
        controller.start(listOf(test), manager, beforeRun)
    }
    fun runAll() {
        if (tests.isEmpty()) return
        error = null
        controller.start(tests.toList(), manager, beforeRun)
    }

    LaunchedEffect(manager) { refresh() }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
        DroidePanelHeader(title = "Test Tasks", onClose = onDismiss, actions = {
            if (running != null) TextButton(onClick = {
                controller.stop()
            }, enabled = !stopping) { Text(if (stopping) "Stopping…" else "Stop") }
            else TextButton(onClick = ::runAll, enabled = tests.isNotEmpty() && !loading) { Text("Run all") }
        })
        HorizontalDivider()
        if (running != null) Text(operation.message + " · continues when this panel closes", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        Text(
            "Runs project test tasks and shows their exit status, not individual test results.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = DroideColors.Muted,
        )
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(Modifier.weight(1f)) {
            if (tests.isEmpty() && error?.startsWith("Could not load test tasks:") != true) item { Text("No test tasks found. Supports Gradle, npm, Cargo, Go, Pytest and project tasks.", Modifier.padding(16.dp), color = DroideColors.Muted) }
            items(tests, key = { it.label }) { test ->
                val result = results[test.label]
                ListItem(
                    headlineContent = { Text(test.label) },
                    supportingContent = {
                        Column {
                            Text(test.argv.joinToString(" ").take(300), fontFamily = FontFamily.Monospace, maxLines = 2)
                            val status = when {
                                running == test.label -> if (stopping) "Stopping" else "Running"
                                result == null -> "Not run"
                                result.exitCode == 0 && !result.timedOut -> "Passed · ${result.durationMs} ms"
                                result.timedOut -> "Timed out"
                                else -> "Failed · exit ${result.exitCode} · ${result.durationMs} ms"
                            }
                            Text(status, color = if (result?.let { it.exitCode == 0 && !it.timedOut } == true) MaterialTheme.colorScheme.primary else DroideColors.Muted)
                        }
                    },
                    trailingContent = { IconButton(onClick = { runOne(test) }, enabled = !operation.busy) { Icon(Icons.Default.PlayArrow, "Run ${test.label}") } },
                )
                result?.output?.takeIf { it.isNotBlank() }?.let { output ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), tonalElevation = 1.dp) {
                        Text(output.takeLast(8_000), Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
        (error ?: operation.error)?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
    }
}

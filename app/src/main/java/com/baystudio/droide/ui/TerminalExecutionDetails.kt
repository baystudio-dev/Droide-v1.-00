package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TerminalExecutionDetails(session: TerminalDiagnosticsSession, onDismiss: () -> Unit) {
    val launches by ExecutionDiagnostics.launches.collectAsState()
    val recording by session.recording.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember(session) { mutableStateOf<String?>(null) }
    var starting by remember(session) { mutableStateOf(false) }
    var consent by remember(session) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Execution details") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recent launch settings for this workspace. A launch entry does not prove that the program started or is still running.", style = MaterialTheme.typography.bodySmall)
                val entries = launches.filter { it.workspace == session.diagnosticsWorkspace.absolutePath }.asReversed()
                if (entries.isEmpty()) Text("No local launch metadata captured yet.")
                entries.forEach { launch ->
                    SelectionContainer { Text(launch.describe(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                }
                Text("Output recording", style = MaterialTheme.typography.titleSmall)
                Text("Records future output until stopped, up to 64 MiB per file. Saved under terminal-logs in this workspace; export it from Explorer. ANSI control sequences are preserved.", style = MaterialTheme.typography.bodySmall)
                recording.path?.let { path -> SelectionContainer { Text("$path\n${recording.bytes} bytes written", style = MaterialTheme.typography.bodySmall) } }
                recording.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!session.recordingSupported) Text("Raw recording requires the current source-built terminal engine.")
                else TextButton(enabled = !starting && !recording.saving, onClick = {
                    if (recording.active) session.recording.stop() else consent = true
                }) { Text(when { recording.active -> "Stop recording"; recording.saving -> "Saving recording…"; starting -> "Starting…"; else -> "Start recording…" }) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Record terminal output?") },
        text = { Text("Output can contain passwords, tokens, private code, and commands echoed by the shell. This log is not redacted. It stays in your workspace until you delete or export it. Recording is optional and starts from now.") },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            consent = false; starting = true; error = null
            scope.launch {
                try { session.recording.start() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Could not start recording. Check workspace storage." }
                finally { starting = false }
            }
        }) { Text("Start recording") } })
}

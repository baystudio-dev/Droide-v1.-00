package com.baystudio.droide.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.baystudio.droide.core.ProposedFileContent
import kotlinx.coroutines.*

@Composable
internal fun AgentProposedContentReview(content: ProposedFileContent) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewing by remember(content) { mutableStateOf(false) }
    var error by remember(content) { mutableStateOf<String?>(null) }
    var exporting by remember(content) { mutableStateOf(false) }
    var pending by remember(content) { mutableStateOf<ProposedFileContent?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val captured = pending; pending = null
        if (uri != null && captured != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val task = currentCoroutineContext()
                    task.ensureActive()
                    captured.verify { task.ensureActive() }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { captured.export(it) { task.ensureActive() } }
                        ?: error("Destination unavailable")
                }
                error = "Proposed file exported."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Export failed. The selected file may be incomplete; the proposal was not applied." }
            finally { exporting = false }
        }
    }
    Column {
        Text("Preview only · ${content.byteLength} bytes proposed", style = MaterialTheme.typography.labelSmall)
        Row {
            TextButton(onClick = { viewing = true }) { Text("Read full proposed file") }
            TextButton(onClick = { pending = content; export.launch("droide-proposed-file.txt") }, enabled = !exporting) { Text("Export proposal") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
    if (viewing) {
        var page by remember(content) { mutableIntStateOf(0) }
        var loaded by remember(content, page) { mutableStateOf<ProposedFileContent.Page?>(null) }
        var failed by remember(content, page) { mutableStateOf(false) }
        LaunchedEffect(content, page) {
            try { loaded = withContext(Dispatchers.IO) { val task = currentCoroutineContext(); content.readPage(page) { task.ensureActive() } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        Dialog(onDismissRequest = { viewing = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 600.dp).padding(16.dp)) {
                    Text("Proposed file · page ${page + 1}", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(key(page) { rememberScrollState() })) {
                        SelectionContainer { Text(if (failed) "Proposal unavailable. No change has been applied." else loaded?.text ?: "Loading…") }
                    }
                    Row {
                        TextButton({ page-- }, enabled = page > 0 && loaded != null) { Text("Previous") }
                        TextButton({ page++ }, enabled = loaded?.hasNext == true && page < Int.MAX_VALUE - 1) { Text("Next") }
                        TextButton({ viewing = false }) { Text("Close") }
                    }
                }
            }
        }
    }
}

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
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Descriptor-aware display: never label/copy a bounded preview as the complete response. */
@Composable
internal fun AgentArchivedPublicAnswer(message: AgentMessage, workDir: File, sessionId: String?) {
    val ref = requireNotNull(message.publicContent)
    val workspaceIdentity = workDir.absoluteFile.path
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var answer by remember(ref, sessionId, workspaceIdentity) { mutableStateOf<ArchivedPublicAnswer?>(null) }
    var error by remember(ref, sessionId, workspaceIdentity) { mutableStateOf<String?>(null) }
    var viewing by remember(ref, sessionId, workspaceIdentity) { mutableStateOf(false) }
    var exporting by remember(ref, sessionId, workspaceIdentity) { mutableStateOf(false) }
    var exported by remember(ref, sessionId, workspaceIdentity) { mutableStateOf(false) }
    LaunchedEffect(ref, sessionId, workspaceIdentity) {
        try {
            answer = withContext(Dispatchers.IO) {
                require(message.role == "assistant" && sessionId != null)
                require(message.publicAbort == null || message.publicAbort.reference == ref)
                val task = currentCoroutineContext()
                AgentPublicContentStores.forWorkspace(workDir, sessionId) { task.ensureActive(); false }.reopen(ref)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Full response is unavailable. Existing data was preserved." }
    }
    var pendingExport by remember(ref, sessionId, workspaceIdentity) { mutableStateOf<PublicAnswerExportRequest?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) scope.launch {
            exporting = true; exported = false
            try {
                withContext(Dispatchers.IO) {
                    val task = currentCoroutineContext()
                    val archive = AgentPublicContentStores.forWorkspace(request.workDir, request.sessionId) { task.ensureActive(); false }
                    // Fail on stale/foreign content before opening a destination in truncate mode.
                    archive.reopen(request.reference)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { archive.export(request.reference, it) }
                        ?: throw java.io.IOException("Export destination is unavailable")
                }
                exported = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Export interrupted. The selected file may be incomplete; your saved response is unchanged." }
            finally { exporting = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val ready = answer
        if (ready == null && error == null) Text("Loading response…", style = MaterialTheme.typography.labelSmall)
        if (ready != null) {
            if (ready.textPreviewAvailable) AgentPublicTextContent(
                markdown = ready.preview, presentation = message.textPresentation,
                presentationId = message.presentationId, publicTextSpans = emptyList(), showActions = false,
            ) else Text("Text preview is unavailable. Export preserves the received bytes.", style = MaterialTheme.typography.bodySmall)
            if (!ref.complete) Text(when (message.publicAbort?.tailStatus) {
                PublicAnswerAbortTailStatus.FAILED_INPUT_PREFIX_ONLY -> "Partial response. Only the saved input prefix is available."
                PublicAnswerAbortTailStatus.UNPAIRED_UTF16_NOT_REPRESENTABLE -> if (message.publicAbort.inputHadFailed)
                    "Partial response. Only the saved prefix is available; an unfinished character could not be saved."
                else "Partial response. An unfinished character could not be saved."
                else -> "Partial response."
            }, style = MaterialTheme.typography.labelSmall)
            if (ready.previewIsPartial) Text("Preview shown. Read or export the full saved response.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewing = true }, enabled = ready.textPreviewAvailable) { Text("Read response") }
                TextButton(onClick = {
                    if (sessionId != null) {
                        pendingExport = PublicAnswerExportRequest(workDir, sessionId, ref)
                        export.launch(if (ref.complete) "droide-response.txt" else "droide-response-incomplete.txt")
                    }
                }, enabled = !exporting && sessionId != null) {
                    Text(if (exporting) "Exporting…" else "Export response")
                }
                if (exported) Text("Exported", style = MaterialTheme.typography.labelSmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (viewing && sessionId != null) PublicAnswerPages(workDir, sessionId, ref) { viewing = false }
}

private data class PublicAnswerExportRequest(val workDir: File, val sessionId: String, val reference: AgentPublicContentRef)

@Composable
private fun PublicAnswerPages(workDir: File, sessionId: String, ref: AgentPublicContentRef, dismiss: () -> Unit) {
    val workspaceIdentity = workDir.absoluteFile.path
    var nextUnavailable by remember(ref, sessionId, workspaceIdentity) { mutableStateOf(false) }
    var page by remember(ref, sessionId, workspaceIdentity) { mutableIntStateOf(0) }
    var text by remember(ref, sessionId, workspaceIdentity) { mutableStateOf<String?>(null) }
    var hasNext by remember(ref, sessionId, workspaceIdentity) { mutableStateOf(false) }
    var failure by remember(ref, sessionId, workspaceIdentity) { mutableStateOf(false) }
    LaunchedEffect(ref, page, sessionId, workspaceIdentity) {
        text = null; failure = false; nextUnavailable = false
        try {
            val loaded = withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                val archive = AgentPublicContentStores.forWorkspace(workDir, sessionId) { task.ensureActive(); false }
                archive.openPages(ref).use { pages ->
                    var content: String? = null
                    repeat(page + 1) { task.ensureActive(); content = pages.readPage() }
                    var malformedNext = false
                    val more = try { pages.readPage() != null }
                    catch (invalid: java.nio.charset.CharacterCodingException) {
                        if (ref.complete) throw invalid
                        malformedNext = true
                        false
                    }
                    Triple(content.orEmpty(), more, malformedNext)
                }
            }
            text = loaded.first; hasNext = loaded.second; nextUnavailable = loaded.third
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true; hasNext = false }
    }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 600.dp).padding(16.dp)) {
                Text("Response · page ${page + 1}", style = MaterialTheme.typography.titleMedium)
                if (!ref.complete) Text("Incomplete response", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(when {
                        failure -> "This text page is unavailable. Export retains the saved bytes."
                        text == null -> "Loading…"
                        else -> text.orEmpty()
                    }, style = MaterialTheme.typography.bodyMedium) }
                }
                if (nextUnavailable) Text("Remaining bytes are not valid text. Export preserves them.", style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { page-- }, enabled = page > 0 && text != null) { Text("Previous") }
                    TextButton(onClick = { page++ }, enabled = hasNext && text != null && page < Int.MAX_VALUE - 1) { Text("Next") }
                    TextButton(onClick = dismiss) { Text("Close") }
                }
            }
        }
    }
}

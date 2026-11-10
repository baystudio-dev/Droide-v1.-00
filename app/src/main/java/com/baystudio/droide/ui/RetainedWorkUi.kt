package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.RetainedWorkCandidate
import com.baystudio.droide.core.RetainedWorkInventory
import com.baystudio.droide.core.RetainedWorkKind
import com.baystudio.droide.core.RetainedWorkNotice
import com.baystudio.droide.core.RetainedWorkSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class RetainedWorkUiState(
    val open: Boolean = false,
    val directory: String = "",
    val busy: Boolean = false,
    val inventory: RetainedWorkInventory? = null,
    val notices: List<RetainedWorkNotice> = emptyList(),
    val omitted: Long = 0L,
    val error: String? = null,
)

internal class RetainedWorkController(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val snapshot: () -> RetainedWorkSnapshot,
    private val acknowledge: (String) -> Unit,
    private val inspectDirectory: suspend (String) -> RetainedWorkInventory,
) {
    var state by mutableStateOf(RetainedWorkUiState())
        private set
    private var disposed = false
    private var lifetime = 0L
    private var request = 0L
    private var inspection: Job? = null

    fun open() {
        if (disposed || !allowed() || state.open) return
        val current = snapshot()
        state = RetainedWorkUiState(open = true, notices = current.notices.toList(), omitted = current.omitted)
    }

    fun directory(value: String) {
        if (disposed || !allowed() || !state.open || value == state.directory || value.length > 500) return
        cancelInspection()
        state = state.copy(directory = value, busy = false, inventory = null, error = null)
    }

    fun inspect() {
        if (disposed || !allowed() || !state.open || state.busy) return
        val path = state.directory
        val selected = ++request
        state = state.copy(busy = true, inventory = null, error = null)
        inspection = scope.launch {
            fun current() = !disposed && allowed() && state.open && selected == request && state.directory == path
            try {
                val result = inspectDirectory(path)
                currentCoroutineContext().ensureActive()
                if (current()) state = state.copy(busy = false, inventory = result)
            } catch (cancelled: CancellationException) {
                if (current()) state = state.copy(busy = false, error = "Inspection interrupted. No candidate was changed.")
                throw cancelled
            } catch (failure: Exception) {
                if (current()) state = state.copy(busy = false,
                    error = failure.message?.take(500) ?: "This folder could not be inspected. No candidate was changed.")
            } catch (_: LinkageError) {
                if (current()) state = state.copy(busy = false,
                    error = "Inspection is unavailable on this device. Retained work was not changed.")
            }
        }
    }

    suspend fun presentNotice(current: RetainedWorkSnapshot, onReview: () -> Unit = {},
        show: suspend (String) -> Boolean): Boolean {
        if (disposed || !allowed() || current.notices.isEmpty() && current.omitted == 0L || snapshot().token != current.token) return false
        val owner = lifetime
        val review = try { show("Temporary work was retained. Review locations before cleanup.") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        currentCoroutineContext().ensureActive()
        if (disposed || !allowed() || owner != lifetime || snapshot().token != current.token) return false
        if (review) {
            state = RetainedWorkUiState(open = true, notices = current.notices.toList(), omitted = current.omitted)
            onReview()
        }
        acknowledge(current.token)
        return review
    }

    fun pause() {
        lifetime++
        cancelInspection()
        state = state.copy(busy = false)
    }

    fun close() {
        cancelInspection()
        state = RetainedWorkUiState()
    }

    fun dispose() { disposed = true; lifetime++; close() }

    private fun cancelInspection() {
        request++
        inspection?.cancel()
        inspection = null
    }
}

@Composable
internal fun RetainedWorkHost(
    files: FileRepository,
    snackbarHostState: SnackbarHostState,
    uiStarted: Boolean,
    showInventory: Boolean,
    onShowInventory: (Boolean) -> Unit,
) {
    val scope = rememberWorkspaceCoroutineScope()
    val owner = rememberUpdatedState(files)
    val started = rememberUpdatedState(uiStarted)
    val show = rememberUpdatedState(onShowInventory)
    val controller = remember(files) {
        RetainedWorkController(scope, { owner.value === files && started.value }, { files.retainedWork.value },
            files::acknowledgeRetainedWork, files::inspectRetainedWork)
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(controller, uiStarted, showInventory) {
        when {
            !uiStarted -> controller.pause()
            showInventory -> controller.open()
            else -> controller.close()
        }
    }
    LaunchedEffect(controller, uiStarted, showInventory) {
        if (!uiStarted || showInventory) return@LaunchedEffect
        files.retainedWork.collectLatest { current ->
            controller.presentNotice(current, onReview = {
                if (owner.value === files && started.value) show.value(true)
            }) { message ->
                snackbarHostState.showSnackbar(message, actionLabel = "Review", withDismissAction = true,
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
            }
        }
    }
    if (showInventory && uiStarted) RetainedWorkDialog(controller.state, controller::directory, controller::inspect) {
        controller.close()
        show.value(false)
    }
}

@Composable
internal fun RetainedWorkDialog(
    state: RetainedWorkUiState,
    onDirectory: (String) -> Unit,
    onInspect: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Retained temporary work") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Read-only review. This does not restore, delete, or repeat a write.")
                if (state.notices.isNotEmpty()) {
                    Text("Recent notifications", style = MaterialTheme.typography.titleSmall)
                    state.notices.forEach { notice ->
                        SelectionContainer { Text("Target: ${notice.target}", fontFamily = FontFamily.Monospace) }
                        RetainedCandidateRow(notice.candidate)
                        if (notice.historicalWriteReturned) Text("An earlier write returned. This review does not change that result.")
                    }
                }
                if (state.omitted > 0L) Text("More notifications are available than shown. Inspect the relevant folder.")
                OutlinedTextField(value = state.directory, onValueChange = onDirectory,
                    label = { Text("Workspace folder (blank for project root)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("retained-work-folder"))
                Text("Lists possible temporary files in this folder only. Does not search subfolders or verify ownership.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onInspect, enabled = !state.busy,
                    modifier = Modifier.testTag("retained-work-inspect")) { Text("Inspect folder") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
                state.inventory?.let { inventory ->
                    Text("Inspected folder: ${inventory.directory.ifBlank { "/" }}", fontFamily = FontFamily.Monospace)
                    if (!inventory.complete) Text("Partial inspection. Other candidates may be present.", color = MaterialTheme.colorScheme.error)
                    inventory.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (inventory.candidates.isEmpty()) Text("No candidates were listed in this inspection.")
                    inventory.candidates.forEach { RetainedCandidateRow(it) }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
private fun RetainedCandidateRow(candidate: RetainedWorkCandidate) {
    SelectionContainer { Text("Location: ${candidate.path}", fontFamily = FontFamily.Monospace) }
    Text(when (candidate.kind) {
        RetainedWorkKind.PRESENT -> "Candidate present; this is not permission to remove it."
        RetainedWorkKind.FOREIGN -> "Entry is not verified as this write's working file; keep it for review."
        RetainedWorkKind.UNVERIFIABLE -> "This location could not be verified."
    }, style = MaterialTheme.typography.bodySmall)
    candidate.bytes?.let { Text("$it bytes", style = MaterialTheme.typography.bodySmall) }
    candidate.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

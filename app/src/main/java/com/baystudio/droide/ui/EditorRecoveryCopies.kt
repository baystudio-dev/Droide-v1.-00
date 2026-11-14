package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.EditorRecoveryEvidenceIssue
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorStateStore
import com.baystudio.droide.core.ExplorerFilePolicy
import com.baystudio.droide.core.FileRepository
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Creates an unsaved editor copy, never a file or a release receipt. Owned by one UI/workspace lifetime. */
internal class EditorRecoveryCopies(private val state: EditorWorkspaceState, private val files: FileRepository) {
    private var closed = false
    fun close() { closed = true }
    private fun current() { check(!closed) { "Workspace changed. Reopen recovery copies." } }
    private fun snapshot() = state.recoverySnapshot(emptyList(), "")
        ?: EditorRecoverySnapshot(emptyList(), "", emptyMap())

    internal class Prepared internal constructor(
        private val action: () -> EditorDocument,
        val destination: String,
    ) {
        private var used = false
        fun open(): EditorDocument {
            check(!used) { "This copy was already opened. Choose another copy explicitly." }
            return action().also { used = true }
        }
    }

    suspend fun prepare(copy: EditorWorkspaceState.RetainedCopy, destination: String): Prepared {
        current(); state.requireRetainedCopy(copy)
        val caller = currentCoroutineContext()
        val before = snapshot()
        val (path, text) = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            fun validateDestination(): String {
                require(EditorStateStore.isRecoverablePath(destination)) { "Choose a recoverable relative filename of at most 1024 characters" }
                val path = ExplorerFilePolicy.requireUserTarget(files.root, destination)
                require(path.isNotBlank() && path == destination && path != copy.path) { "Use a new plain relative project path without aliases" }
                val target = files.root.resolve(path)
                require(Files.isDirectory(target.parentFile.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Choose an existing project folder" }
                require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "That file already exists. Choose another filename." }
                return path
            }
            val path = validateDestination()
            // The original reference retains its original path/AAD and authenticated loader.
            val text = copy.text ?: requireNotNull(copy.deferred).readText()
            currentCoroutineContext().ensureActive()
            EditorStateStore.requireAdditionalCopyCapacity(before, text.toByteArray(Charsets.UTF_8).size.toLong())
            check(validateDestination() == path) { "Destination changed while reading recovery" }
            path to text
        }
        currentCoroutineContext().ensureActive()
        current(); state.requireRetainedCopy(copy)
        check(snapshot() == before) { "Editor recovery changed while reading. Try opening the copy again." }
        return Prepared({
            caller.ensureActive()
            current(); state.requireRetainedCopy(copy)
            check(snapshot() == before) { "Editor recovery changed. Try opening the copy again." }
            // A raced filesystem creation is harmless here: later Save requires known absence.
            state.registerRetainedCopy(copy, path, text)
        }, path)
    }
}

/** Synchronous owner-thread admission: failed native capture or full tabs cannot register a copy. */
internal fun openRecoveryCopyTab(
    state: EditorWorkspaceState,
    prepared: EditorRecoveryCopies.Prepared,
    tabs: List<String>,
    active: String,
    capture: () -> Unit,
): List<String> {
    capture()
    val next = EditorTabRetention.open(tabs, prepared.destination, active) {
        state.peek(it)?.blocksPersistedPathMutation == true
    } ?: error("Editor tabs are full. Save or close a tab before opening a recovery copy.")
    prepared.open()
    EditorTabRetention.closeEvicted(tabs, next, state) { }
    return next
}

/** Keep the existing read/open-only entry point; reclaim is opt-in from its workspace owner. */
@Composable
internal fun EditorRecoveryCopiesDialog(
    state: EditorWorkspaceState,
    files: FileRepository,
    allowed: Boolean,
    onOpen: (EditorRecoveryCopies.Prepared) -> Unit,
    onDismiss: () -> Unit,
    archivedIssues: List<EditorRecoveryEvidenceIssue> = emptyList(),
) = EditorRecoveryCopiesDialog(state, files, allowed, onOpen, onDismiss, archivedIssues, discardActions = null)

@Composable
internal fun EditorRecoveryCopiesDialog(
    state: EditorWorkspaceState,
    files: FileRepository,
    allowed: Boolean,
    onOpen: (EditorRecoveryCopies.Prepared) -> Unit,
    onDismiss: () -> Unit,
    archivedIssues: List<EditorRecoveryEvidenceIssue> = emptyList(),
    discardActions: EditorRecoveryDiscardActions?,
) {
    val recovery = remember(state, files) { EditorRecoveryCopies(state, files) }
    DisposableEffect(recovery) { onDispose { recovery.close() } }
    val discardStatus = discardActions?.state?.collectAsState()?.value ?: EditorRecoveryDiscardUiState()
    val copies = remember(recovery, discardStatus.revision) { state.retainedCopies() }
    var selected by remember(recovery) { mutableStateOf<EditorWorkspaceState.RetainedCopy?>(null) }
    var destination by remember(recovery) { mutableStateOf("") }
    var busy by remember(recovery) { mutableStateOf(false) }
    var error by remember(recovery) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentAllowed by rememberUpdatedState(allowed)
    val currentOpen by rememberUpdatedState(onOpen)
    val currentDiscard by rememberUpdatedState(discardActions)
    var discardPreview by remember(recovery, discardActions) { mutableStateOf<EditorRecoveryDiscardPreview?>(null) }
    var prepareJob by remember(recovery, discardActions) { mutableStateOf<Job?>(null) }
    var prepareGeneration by remember(recovery, discardActions) { mutableLongStateOf(0L) }
    var preparationBusy by remember(recovery, discardActions) { mutableStateOf(false) }
    var closed by remember(recovery, discardActions) { mutableStateOf(false) }
    fun revokePreview() {
        prepareGeneration++
        prepareJob?.cancel(); prepareJob = null; preparationBusy = false
        discardPreview?.let { discardActions?.revoke(it) }; discardPreview = null
    }
    DisposableEffect(recovery, discardActions) {
        onDispose { closed = true; revokePreview() }
    }
    LaunchedEffect(discardStatus.mode, discardStatus.revision) {
        if (discardStatus.mode == EditorRecoveryDiscardMode.REMOVED) {
            selected = null; destination = ""
        }
    }
    val actionBusy = busy || preparationBusy || discardStatus.blocksNewAction
    // Revoke the prepared-copy capability immediately; disposal happens on a later frame.
    val dismiss = { closed = true; revokePreview(); recovery.close(); onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Older editor recovery copies") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open an older recovery copy in a new unsaved tab. The original recovery copy and current edits stay unchanged. Nothing is written to the project until you Save.")
                if (archivedIssues.isNotEmpty()) {
                    Text("Archived recovery evidence needs attention", color = MaterialTheme.colorScheme.error)
                    Text("Healthy drafts remain available. These archived records were kept unchanged and cannot be opened or removed here.")
                    archivedIssues.forEach { issue ->
                        Text("${if (issue.missing) "Missing" else "Changed"} archived evidence: ${issue.blobName}")
                    }
                }
                if (copies.isEmpty()) Text("No older editor recovery copies are retained.")
                copies.forEach { copy ->
                    OutlinedButton(enabled = !actionBusy && discardPreview == null, onClick = {
                        revokePreview()
                        selected = copy; destination = copy.path + ".recovered"; error = null
                    }) { Text((if (selected === copy) "Selected: " else "") + copy.path) }
                }
                if (selected != null) OutlinedTextField(destination, { destination = it }, enabled = !actionBusy,
                    label = { Text("New filename in an existing folder") }, singleLine = true)
                if (discardActions != null && selected != null) {
                    TextButton(enabled = allowed && !actionBusy && discardPreview == null, onClick = {
                        val copy = selected ?: return@TextButton
                        val actions = discardActions
                        if (closed || !currentAllowed || busy || preparationBusy || actions.state.value.blocksNewAction) return@TextButton
                        revokePreview(); val request = prepareGeneration
                        preparationBusy = true; error = null
                        prepareJob = scope.launch {
                            var acquired: EditorRecoveryDiscardPreview? = null
                            try {
                                val prepared = actions.prepare(copy)
                                acquired = prepared
                                currentCoroutineContext().ensureActive()
                                check(!closed && currentAllowed && currentDiscard === actions && request == prepareGeneration && selected === copy) {
                                    "Recovery selection changed. Review the copy again."
                                }
                                check(prepared.copy === copy) { "Removal preview belongs to another copy" }
                                discardPreview = prepared; acquired = null
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { if (!closed && request == prepareGeneration) error = failure.message ?: "Cannot prepare removal. Nothing was removed." }
                            finally {
                                acquired?.let(actions::revoke)
                                if (request == prepareGeneration) preparationBusy = false
                            }
                        }
                    }) { Text("Prepare permanent removal") }
                }
                when (discardStatus.mode) {
                    EditorRecoveryDiscardMode.WORKING -> Text("Removing the confirmed older recovery copy…")
                    EditorRecoveryDiscardMode.PENDING -> {
                        Text("Confirmed removal needs reconciliation. Completion is not yet verified.", color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = allowed && !busy && !preparationBusy, onClick = { discardActions?.reconcile() }) { Text("Finish confirmed removal") }
                    }
                    EditorRecoveryDiscardMode.REMOVED -> Text("Selected older recovery copy removed. Physical disk space released was not measured.")
                    EditorRecoveryDiscardMode.KEPT -> Text("Older recovery copy kept. No completed removal is reported.")
                    EditorRecoveryDiscardMode.IDLE -> Unit
                }
                discardStatus.path?.let { if (discardStatus.mode != EditorRecoveryDiscardMode.IDLE) Text("Selected copy: $it") }
                discardStatus.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) Text("Reading the retained copy…")
                if (preparationBusy) Text("Checking the selected recovery copy…")
            }
        },
        confirmButton = {
            TextButton(enabled = allowed && !actionBusy && discardPreview == null && selected != null && destination.isNotBlank(), onClick = {
                val copy = selected ?: return@TextButton
                busy = true; error = null
                scope.launch {
                    try {
                        val prepared = recovery.prepare(copy, destination)
                        currentCoroutineContext().ensureActive()
                        check(currentAllowed) { "Workspace is not active. Try again when it is open." }
                        currentOpen(prepared)
                        onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Cannot open recovery copy. Original was kept." }
                    finally { busy = false }
                }
            }) { Text("Open unsaved copy") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
    discardPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { revokePreview() },
            title = { Text("Permanently remove older copy?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Older recovery copy: ${preview.path}")
                    Text("Encrypted copy size: ${preview.ciphertextBytes} bytes")
                    Text("This permanently removes only the selected older recovery copy. It cannot be undone. Current editor contents and other recovery copies are not selected.")
                    Text("Preparing this action checkpoints encrypted recovery only; it does not Save files to your project. Physical disk space released is not guaranteed.")
                }
            },
            confirmButton = {
                TextButton(enabled = allowed && !actionBusy && !closed, onClick = {
                    val actions = discardActions ?: return@TextButton
                    if (closed || !currentAllowed || currentDiscard !== actions || actions.state.value.blocksNewAction || discardPreview !== preview) return@TextButton
                    discardPreview = null; prepareGeneration++
                    try { actions.confirm(preview) }
                    catch (failure: Exception) { error = failure.message ?: "Removal could not start. Review its status before retrying." }
                }) { Text("Permanently remove") }
            },
            dismissButton = { TextButton(onClick = { revokePreview() }) { Text("Keep copy") } },
        )
    }
}

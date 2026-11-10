package com.baystudio.droide.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

internal data class ExplorerExportActions(
    val open: (String, Boolean) -> Unit,
    val showStatus: (() -> Unit)?,
    val statusLabel: String?,
    val dismissStatus: (() -> Unit)?,
)

private data class ExportConfiguration(
    val admission: ExportSourceAdmission,
    val ticket: ExportSourceTicket,
    val workspaceName: String,
    val directory: Boolean,
    val linked: Boolean,
    val format: ExportFormat,
    val names: ExportOutputNames,
    val saveChanges: Boolean? = null,
    val quiescent: Boolean = false,
) {
    val name: String get() = names.selected(format)
}
private data class ExportPickerRequest(val configuration: ExportConfiguration)
private data class ExportPickedDestination(val request: ExportPickerRequest, val uri: Uri, val flags: Int)

/** Job ownership is in ExportController, not this sheet, a tab, or the editor's coroutine scope. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberExplorerExportUi(
    admission: ExportSourceAdmission,
    workspaceName: String,
    safLinked: Boolean,
    saveScope: suspend (String) -> EditorSaveReport,
    captureEditor: () -> Unit,
): ExplorerExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resumed = rememberUiResumed()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val job by ExportController.state.collectAsState()
    val openRequest by ExportController.openRequest.collectAsState()
    var opened by remember { mutableStateOf(false) }
    var dismissedStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var configuration by remember(admission) { mutableStateOf<ExportConfiguration?>(null) }
    var picker by remember { mutableStateOf<ExportPickerRequest?>(null) }
    var destination by remember { mutableStateOf<ExportPickedDestination?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preflightBlockers by remember(admission) { mutableStateOf<List<String>>(emptyList()) }
    var discardJobId by remember { mutableStateOf<String?>(null) }
    var historyVisible by remember { mutableStateOf(false) }
    var historyLoading by remember { mutableStateOf(false) }
    var previousJobs by remember { mutableStateOf<List<ExportJobState>>(emptyList()) }
    var reviewUri by remember { mutableStateOf<Uri?>(null) }

    fun reviewDestination(id: String) {
        scope.launch {
            try {
                val recorded = ExportController.destinationForReview(context, id)
                check(recorded == null || recorded.scheme == "content") { "Destination record is not a document-provider URI" }
                reviewUri = recorded
                if (reviewUri == null) error = "No destination URI was recorded for this job. If the picker created a file, inspect the folder you selected in your Files app."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Destination record is unavailable; no files were removed" }
        }
    }

    LaunchedEffect(context) {
        try { withContext(Dispatchers.IO) { ExportController.initialize(context) { ensureActive() } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Previous export status is unavailable; retained data was not removed" }
    }

    fun startPicked(picked: ExportPickedDestination) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val config = picked.request.configuration
                val prepared = config.admission.prepare(config.ticket, allowDirtySavedVersion = config.saveChanges != true)
                ExportController.start(context, config.admission, prepared, config.format, picked.uri,
                    picked.flags, config.quiescent, outputName = config.name)
                destination = null; configuration = null; error = null; opened = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = "${failure.message ?: "Export could not start"}. The picker may have created an empty file; inspect the selected destination before trying again."
                destination = null
            } finally { busy = false }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = picker
        picker = null
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            if (request == null) {
                error = "This destination belongs to an expired export request. No export was started. The picker may have created an empty file."
                opened = true
            } else {
                destination = ExportPickedDestination(request, checkNotNull(result.data?.data), result.data?.flags ?: 0)
                opened = true
            }
        }
        // Cancellation leaves the original configuration intact; it is not an export success/failure.
    }
    // Returning from DocumentsUI may precede onResume. Do not start a foreground service in
    // background.
    LaunchedEffect(destination, resumed) {
        val selected = destination
        if (selected != null && resumed) startPicked(selected)
    }
    LaunchedEffect(openRequest) { if (openRequest > 0L) opened = true }
    LaunchedEffect(opened) { if (opened) { focus.clearFocus(); keyboard?.hide() } }

    val open: (String, Boolean) -> Unit = { path, directory ->
        if (!ExportRolloutPolicy.newExportsEnabled) {
            configuration = null; opened = true
            error = "New exports are disabled in this build pending Android acceptance. Existing export recovery and history remain available."
        } else if (exportNeedsPriorResolution(job) || busy || picker != null || destination != null) { configuration = null; opened = true }
        else {
            opened = true; error = null; preflightBlockers = emptyList(); busy = true; configuration = null
            scope.launch {
                try {
                    captureEditor()
                    val ticket = admission.ticket(ExportScope(path))
                    val baseName = ExportFileNames.suggested(path, workspaceName)
                    configuration = ExportConfiguration(admission, ticket, workspaceName, directory, safLinked,
                        if (directory) ExportFormat.TAR else ExportFormat.RAW_FILE,
                        ExportOutputNames(baseName))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Export source is unavailable" }
                finally { busy = false }
            }
        }
    }

    if (opened) DroideModalBottomSheet(
        onDismissRequest = { opened = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DroideColors.Surface,
    ) { dismiss ->
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
            .heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("explorer-export-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val config = configuration
            Text(if (config != null) exportScopeTitle(config.ticket.scope.relativePath, config.directory) else "Export status",
                style = MaterialTheme.typography.titleLarge)
            if (config != null && job?.terminal != false && job?.retainedCapture != true) {
                Text("${config.workspaceName} · /${config.ticket.scope.relativePath}", style = MaterialTheme.typography.bodyMedium)
                Text("Exports saved files in this selection, including hidden files, .git, .env, dependencies and build output.",
                    style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                if (config.linked) Text("Exports the local copy, not the linked folder’s latest files. Pending sync, conflicts and recovery remain unresolved.", style = MaterialTheme.typography.bodySmall)
                Text("Unsynced CLI files are excluded. Check pending CLI changes before exporting.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                Text("Format", style = MaterialTheme.typography.titleSmall)
                val formats = if (config.directory) listOf(ExportFormat.TAR, ExportFormat.TREE) else listOf(ExportFormat.RAW_FILE, ExportFormat.TAR)
                formats.forEach { format ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy && picker == null) {
                        configuration = config.copy(format = format)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = config.format == format, onClick = null)
                        Text(when (format) { ExportFormat.RAW_FILE -> "Original file"; ExportFormat.TREE -> "Ordinary folder"; ExportFormat.TAR -> "TAR archive" })
                    }
                }
                Text(when (config.format) {
                    ExportFormat.RAW_FILE -> "File contents only; permissions, ownership and timestamps are not preserved."
                    ExportFormat.TREE -> "Creates a new folder. Symlinks are unsupported; hardlink identity, permissions, ownership and timestamps are not preserved. Use TAR for Linux metadata."
                    ExportFormat.TAR -> "Preserves supported host file metadata and links, not virtual PRoot ownership. ACLs, xattrs and sparse allocation are not preserved; special entries may be rejected."
                }, style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                OutlinedTextField(config.name, { configuration = config.copy(names = config.names.renamed(config.format, it)) },
                    label = { Text(if (config.format == ExportFormat.TREE) "Folder name" else "File name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy && picker == null,
                    isError = !validExportName(config.name))
                if (config.format == ExportFormat.TREE) Text("Creates a new folder at your chosen location. The provider may rename it if the name is taken; existing folders are untouched.", style = MaterialTheme.typography.bodySmall)
                if (config.ticket.dirtyVersions.isNotEmpty()) {
                    Text("${config.ticket.dirtyVersions.size} unsaved editor buffer(s) in this scope", style = MaterialTheme.typography.titleSmall)
                    listOf(true to "Save scoped changes, then export", false to "Export saved disk version").forEach { (save, label) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy && picker == null) { configuration = config.copy(saveChanges = save) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(config.saveChanges == save, onClick = null); Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("Exporting the saved version excludes unsaved edits and new files without a saved path.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                }
                if (config.ticket.concurrentFileCapture) {
                    Text("Other tasks can keep running. If this file changes during export, retry after the write finishes.", style = MaterialTheme.typography.bodySmall)
                    Text("Exports this saved file only. Choose a trusted destination; your storage provider may sync it to the cloud.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                } else {
                    Text("Stop tools writing to this selection before export. Project changes pause while files are copied; other workspaces can keep running.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy && picker == null) { configuration = config.copy(quiescent = !config.quiescent) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(config.quiescent, onCheckedChange = null)
                        Text("External tools will not write during capture", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("External writes cannot be ruled out automatically. Choose a trusted destination; your provider may sync it to the cloud.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                }
                Text("Cancelled or failed exports may leave an empty or partial file. Existing files are never overwritten.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                if (preflightBlockers.isNotEmpty()) {
                    Text("Destination picker was not opened", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("export-preflight-blocked"))
                    preflightBlockers.forEach { blocker ->
                        Text(blocker, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Resolve these issues, then retry. No tasks were stopped automatically.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(if (config.ticket.concurrentFileCapture) "File readiness is checked again before export." else "Keep external tools from writing to this selection until copying finishes.",
                    style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                Button(onClick = {
                    busy = true; error = null; preflightBlockers = emptyList()
                    scope.launch {
                        try {
                            ExportRolloutPolicy.requireNewExportEnabled()
                            withContext(Dispatchers.IO) { config.admission.validate(config.ticket) }
                            var sourceTicket = config.ticket
                            if (config.saveChanges == true) {
                                val report = saveScope(config.ticket.scope.relativePath)
                                check(report.ready) { report.continuationFailure()?.second?.message ?: "Scoped save needs attention" }
                                sourceTicket = config.admission.refreshAfterScopedSave(config.ticket, report.savedPaths.toSet())
                                configuration = config.copy(ticket = sourceTicket)
                            }
                            val prepared = config.admission.prepare(sourceTicket, config.saveChanges != true)
                            val request = ExportPickerRequest(config.copy(ticket = prepared))
                            val intent = exportPickerIntent(config.format, config.name, config.ticket.scope.relativePath)
                            val readiness = chooseExportDestinationIfReady(config.admission, prepared) {
                                picker = request
                                launcher.launch(intent)
                            }
                            preflightBlockers = readiness.blockers
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { picker = null; error = failure.message ?: "Cannot open export destination" }
                        finally { busy = false }
                    }
                }, enabled = !busy && picker == null && destination == null && (config.ticket.concurrentFileCapture || config.quiescent) && validExportName(config.name) &&
                    (config.ticket.dirtyVersions.isEmpty() || config.saveChanges != null), modifier = Modifier.fillMaxWidth()) {
                    Text(when {
                        picker != null -> "Choosing destination…"
                        preflightBlockers.isNotEmpty() -> "Retry checks and choose destination"
                        else -> "Choose destination and start"
                    })
                }
            } else if (job != null && (job?.terminal == false || job?.retainedCapture == true || (!busy && error == null))) {
                ExportJobSummary(checkNotNull(job))
                if (job?.terminal == true) TextButton(onClick = { reviewDestination(checkNotNull(job).id) }, enabled = !busy) { Text("Review destination") }
                if (job?.terminal == true && job?.retainedCapture == true) {
                    Text("A private capture is retained for this job. Discard it to free local storage before starting another export. This will not remove partial destination files.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { discardJobId = checkNotNull(job).id }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("discard-export-staging")) { Text("Discard local export staging") }
                }
                if (job?.terminal == false) TextButton(onClick = { ExportController.cancel(checkNotNull(job).id) },
                    enabled = job?.phase != ExportJobPhase.CANCELLING, modifier = Modifier.fillMaxWidth()) { Text("Cancel export") }
            }
            if (busy || destination != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (discardJobId != null) "Discarding local staging…" else if (!resumed && destination != null) "Return to this app to start export" else "Preparing export…", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                historyVisible = !historyVisible
                if (historyVisible) {
                    historyLoading = true
                    scope.launch {
                        try {
                            val snapshot = ExportController.historySnapshot(context)
                            previousJobs = snapshot.jobs.filter { it.id != job?.id }
                            if (snapshot.currentUnreadable || snapshot.unreadableRecords > 0 || snapshot.preservedPointers > 0) {
                                error = (if (snapshot.preservedPointers > 0) "Earlier damaged export metadata is preserved for review. " else "Some export recovery records could not be read. ") +
                                    "Healthy history is shown; all local staging and output are preserved." +
                                    if (snapshot.currentUnreadable) " Current export recovery needs review before starting another export." else ""
                            }
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Export history could not be read" }
                        finally { historyLoading = false }
                    }
                }
            }, enabled = !historyLoading) { Text(if (historyVisible) "Hide previous exports" else "Previous exports") }
            if (historyVisible) {
                if (historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (previousJobs.isEmpty()) Text("No previous export records", style = MaterialTheme.typography.bodySmall)
                previousJobs.forEach { previous ->
                    HorizontalDivider()
                    Text(previous.sourceLabel.ifBlank { "Previous export" }, style = MaterialTheme.typography.titleSmall)
                    Text(exportPhaseLabel(previous.phase), style = MaterialTheme.typography.bodySmall)
                    if (previous.providerAuthority.isNotBlank()) Text(previous.providerAuthority, style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                    if (previous.detail.isNotBlank()) Text(previous.detail, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { reviewDestination(previous.id) }) { Text("Review destination") }
                }
            }
            error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text(if (job?.terminal == false) "Minimize" else "Close") }
        }
    }
    reviewUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { reviewUri = null },
            title = { Text("Export destination") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This is the recorded output document or export folder, not a filesystem path. Partial files are preserved. Access may expire after the app restarts; use your Files app if it cannot open here.")
                SelectionContainer { Text(uri.toString(), style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); reviewUri = null }
                catch (failure: Exception) { error = "Could not open this destination. Review it in your Files app: ${failure.message ?: "access unavailable"}"; reviewUri = null }
            }) { Text("Open destination") } },
            dismissButton = { TextButton(onClick = { reviewUri = null }) { Text("Close") } },
        )
    }
    discardJobId?.let { selectedId ->
        AlertDialog(
            onDismissRequest = { if (!busy) discardJobId = null },
            title = { Text("Discard local export staging?") },
            text = { Text("Delete only Droide’s private captured copy for this export. Your project files, destination output and output ownership records are preserved. Partial destination files must be reviewed separately. This local capture cannot be recovered after deletion.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        ExportController.discardRetainedCapture(context, selectedId)
                        discardJobId = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Could not discard local staging; retained data needs review"; discardJobId = null }
                    finally { busy = false }
                }
            }) { Text("Discard local copy") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { discardJobId = null }) { Text("Keep") } },
        )
    }
    val statusKey = job?.let { "${it.id}:${it.phase.name}" }
    val statusVisible = statusKey != null && statusKey != dismissedStatus
    return ExplorerExportActions(open, if (job != null || configuration != null || error != null) ({ opened = true }) else null,
        job?.takeIf { statusVisible }?.let { exportPhaseLabel(it.phase) },
        if (statusVisible && job?.terminal == true) ({ dismissedStatus = statusKey }) else null)
}

internal fun exportNeedsPriorResolution(job: ExportJobState?): Boolean =
    job != null && (!job.terminal || job.retainedCapture)

internal fun exportPhaseLabel(phase: ExportJobPhase): String = when (phase) {
    ExportJobPhase.QUEUED -> "Preparing export"
    ExportJobPhase.CAPTURING -> "Capturing stable copy"
    ExportJobPhase.WRITING -> "Writing export"
    ExportJobPhase.VERIFYING -> "Verifying destination bytes"
    ExportJobPhase.COMPLETED_VERIFIED -> "Completed · bytes verified"
    ExportJobPhase.CANCELLING -> "Cancelling export"
    ExportJobPhase.CANCELLED -> "Export cancelled"
    ExportJobPhase.FAILED -> "Export failed"
    ExportJobPhase.PARTIAL -> "Partial export · incomplete output"
    ExportJobPhase.UNVERIFIED -> "Unverified · integrity not established"
    ExportJobPhase.INTERRUPTED -> "Export interrupted"
}

@Composable
private fun ExportJobSummary(job: ExportJobState) {
    Text(exportPhaseLabel(job.phase), style = MaterialTheme.typography.titleMedium)
    if (job.sourceLabel.isNotBlank()) Text(job.sourceLabel, style = MaterialTheme.typography.bodyMedium)
    if (job.providerAuthority.isNotBlank()) Text("Destination provider: ${job.providerAuthority}", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
    if (job.createdCount > 0) Text("${job.createdCount} destination object(s) recorded for this job", style = MaterialTheme.typography.bodySmall)
    Text("${job.entries} entries · ${job.bytes} bytes", style = MaterialTheme.typography.bodyMedium)
    if (!job.terminal) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (job.detail.isNotBlank()) Text(job.detail, style = MaterialTheme.typography.bodySmall)
    Text("Closing this sheet does not cancel the job or mark it complete.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
    if (job.phase == ExportJobPhase.CANCELLING) Text("A provider operation may take time to return. Partial output is not automatically removed.", style = MaterialTheme.typography.bodySmall)
    if (job.phase == ExportJobPhase.COMPLETED_VERIFIED) Text("Exported contents verified. The selected format’s metadata limits still apply.", style = MaterialTheme.typography.bodySmall)
    if (job.terminal && job.phase != ExportJobPhase.COMPLETED_VERIFIED) Text("Check the destination for partial files. To retry, start a new export to a new destination.", style = MaterialTheme.typography.bodySmall)
}

package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baystudio.droide.core.AnchoredWorkspaceFiles
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.core.CanonicalFileOperationJournal
import com.baystudio.droide.core.CanonicalFileSnapshot
import com.baystudio.droide.core.ProjectManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectCatalogRecoveryEntry(manager: ProjectManager) {
    if (!manager.supportsCatalogRecoveryReview) return
    var open by rememberSaveable(manager) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Review project changes") }
    if (open) ProjectCatalogRecoveryDialog(manager, onDismiss = { open = false })
}

private sealed interface CatalogRecoveryPage {
    data object Loading : CatalogRecoveryPage
    data class History(val value: CanonicalFileOperationJournal.RecoveryMetadata) : CatalogRecoveryPage
    data class Operation(val value: CanonicalFileOperationJournal.OperationRecoveryReview) : CatalogRecoveryPage
    data class Unavailable(val reason: String) : CatalogRecoveryPage
}

@Composable
internal fun ProjectCatalogRecoveryDialog(manager: ProjectManager, onDismiss: () -> Unit) {
    var operationId by rememberSaveable(manager) { mutableStateOf<String?>(null) }
    var refresh by remember(manager) { mutableLongStateOf(0L) }
    var page by remember(manager, operationId, refresh) { mutableStateOf<CatalogRecoveryPage>(CatalogRecoveryPage.Loading) }
    var inspectedAt by remember(manager, operationId, refresh) { mutableLongStateOf(0L) }
    var inspectionEpoch by remember(manager, operationId, refresh) { mutableStateOf<String?>(null) }
    var copying by remember(manager, operationId, refresh) { mutableStateOf(false) }
    var copyMessage by remember(manager, operationId, refresh) { mutableStateOf<String?>(null) }
    var copyAttempt by remember { mutableLongStateOf(0L) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(manager, operationId, refresh) {
        val observed = try {
            val epoch = ApplicationWorkAdmission.requireOpen()
            inspectionEpoch = epoch
            val observed = operationId?.let { CatalogRecoveryPage.Operation(manager.inspectCatalogOperationRecovery(it)) }
                ?: CatalogRecoveryPage.History(manager.inspectCatalogRecovery())
            currentCoroutineContext().ensureActive()
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "This workspace session closed. Open recovery again." }
            observed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            CatalogRecoveryPage.Unavailable(failure.message?.take(4_096)?.takeIf { it.isNotBlank() }
                ?: "Saved project changes could not be inspected.")
        } catch (_: LinkageError) {
            currentCoroutineContext().ensureActive()
            CatalogRecoveryPage.Unavailable("Project recovery is unavailable on this device. Retained files were not changed.")
        }
        inspectedAt = System.currentTimeMillis()
        page = observed
    }
    LaunchedEffect(manager, operationId, refresh, copying) {
        if (!copying) return@LaunchedEffect
        try {
            val epoch = inspectionEpoch
            check(epoch != null && ApplicationWorkAdmission.isCurrentOpen(epoch)) {
                "This workspace session closed. Open recovery again."
            }
            val observed = page
            val completedAt = inspectedAt
            val requestedOperation = operationId
            val report = withContext(Dispatchers.Default) {
                when (observed) {
                    is CatalogRecoveryPage.History -> ProjectCatalogRecoveryReport.history(observed.value, completedAt)
                    is CatalogRecoveryPage.Operation -> ProjectCatalogRecoveryReport.operation(observed.value, completedAt)
                    is CatalogRecoveryPage.Unavailable -> ProjectCatalogRecoveryReport.unavailable(requestedOperation, observed.reason, completedAt)
                    CatalogRecoveryPage.Loading -> error("Inspection is still running. Try again when it finishes.")
                }
            }
            currentCoroutineContext().ensureActive()
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "This workspace session closed. Open recovery again." }
            clipboard.setText(AnnotatedString(report))
            copyMessage = "Inspection report copied. File contents are not included."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            copyMessage = failure.message?.take(160)?.takeIf { it.isNotBlank() }
                ?: "Could not copy the report. Your files are unchanged."
        } catch (_: LinkageError) {
            currentCoroutineContext().ensureActive()
            copyMessage = "Report copying is unavailable on this device. Your files are unchanged."
        } finally {
            copying = false
        }
    }

    val displayedPage = page
    val displayedCopyMessage = copyMessage
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(16.dp).testTag("catalog-recovery")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved project changes", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close project recovery")
                    }
                }
                displayedCopyMessage?.let { message ->
                    DismissibleNotice(message, ownerKey = copyAttempt,
                        modifier = Modifier.heightIn(max = 80.dp).verticalScroll(rememberScrollState()))
                }
                key(manager, operationId, refresh) {
                    CatalogRecoveryBody(displayedPage, Modifier.weight(1f), onInspect = { operationId = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (operationId != null) TextButton(onClick = { operationId = null }, modifier = Modifier.weight(1f)) {
                        Text("Back")
                    }
                    TextButton(onClick = { copyAttempt++; copyMessage = null; copying = true },
                        enabled = displayedPage !is CatalogRecoveryPage.Loading && !copying,
                        modifier = Modifier.weight(1f)) { Text("Copy report") }
                    TextButton(onClick = { refresh++ }, enabled = displayedPage !is CatalogRecoveryPage.Loading && !copying,
                        modifier = Modifier.weight(1f)) { Text("Refresh") }
                }
            }
        }
    }
}

@Composable
private fun CatalogRecoveryBody(page: CatalogRecoveryPage, modifier: Modifier, onInspect: (String) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item(key = "scope") {
            Text("Retained changes to your saved project list. Inspection does not restore or delete files.",
                style = MaterialTheme.typography.bodySmall)
        }
        when (page) {
            CatalogRecoveryPage.Loading -> item(key = "loading") {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Inspecting saved changes…")
                }
            }
            is CatalogRecoveryPage.Unavailable -> item(key = "unavailable") {
                SelectionContainer {
                    Column {
                        Text("Inspection unavailable", color = MaterialTheme.colorScheme.error)
                        Text(page.reason)
                        Text("No recovery action was performed. Refresh to inspect again.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is CatalogRecoveryPage.History -> {
                val history = page.value
                item(key = "retention-capacity") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Recovery storage budget", style = MaterialTheme.typography.titleMedium)
                        val usage = history.retentionUsage
                        if (usage == null) Text("Completion retention is not enabled for this journal.")
                        else {
                            Text("Completed entries: ${usage.completedEntries} / ${usage.maxEntries}")
                            Text("Retained bytes: ${usage.retainedBytes} / ${usage.maxRetainedBytes}")
                            Text("Metadata bytes: ${usage.metadataBytes} / ${usage.maxMetadataBytes}")
                            Text("Admission budgets up to ${usage.nextRecordReserveBytes} metadata bytes for the next record.")
                        }
                        if (history.pending != null) Text("A pending change must be reviewed before another update.")
                        Text("Observed journal accounting only, not available disk space or permission to update. " +
                            "Each update rechecks its full budget. Completed-entry release is not available; " +
                            "default journaled catalog migration remains disabled.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (history.pending == null && history.completed.isEmpty()) item(key = "empty") {
                    Text("No recorded project-list changes in this journal.")
                }
                history.pending?.let { pending -> item(key = "pending:${pending.id}") {
                    CatalogRecoveryOperationRow(pending, "Pending change", onInspect)
                } }
                items(history.completed, key = { "completed:${it.id}" }) { completed ->
                    CatalogRecoveryOperationRow(completed, "Recorded completion", onInspect)
                }
            }
            is CatalogRecoveryPage.Operation -> {
                val review = page.value
                item(key = "operation") {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (review.archived) "Recorded completion" else "Pending change", style = MaterialTheme.typography.titleMedium)
                            Text("Operation: ${review.intent.id}")
                            Text("Recorded stage: ${review.intent.phase.name}")
                            Text("These are observations from this inspection. A recorded version does not make a file immutable.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item(key = "canonical") {
                    CatalogRecoveryFile("Project catalog", review.canonicalPath, review.canonical, null)
                }
                items(review.custody, key = { it.role }) { file ->
                    val title = when (file.role) {
                        CanonicalFileOperationJournal.CustodyRole.SNAPSHOT -> "Original copy"
                        CanonicalFileOperationJournal.CustodyRole.PROPOSAL -> "Proposed copy"
                        CanonicalFileOperationJournal.CustodyRole.PUBLICATION_STAGE -> "Prepared publication"
                        CanonicalFileOperationJournal.CustodyRole.RETIRED -> "Retained original"
                        CanonicalFileOperationJournal.CustodyRole.CONFLICT -> "Conflict copy"
                    }
                    CatalogRecoveryFile(title, "${review.custodyPath}/${file.name}", file.observed, file.recordedVersion, custody = true)
                }
            }
        }
    }
}

@Composable
private fun CatalogRecoveryOperationRow(intent: CanonicalFileOperationJournal.Intent, title: String, onInspect: (String) -> Unit) {
    OutlinedButton(onClick = { onInspect(intent.id) }, modifier = Modifier.fillMaxWidth().testTag("inspect-catalog:${intent.id}")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(intent.id)
            Text("Recorded stage: ${intent.phase.name}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CatalogRecoveryFile(title: String, path: String, observed: CanonicalFileOperationJournal.FileObservation,
                                recorded: CanonicalFileSnapshot.Version?, custody: Boolean = false) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(path, style = MaterialTheme.typography.bodySmall)
            val status = when (observed.kind) {
                AnchoredWorkspaceFiles.Kind.ABSENT -> "Currently missing"
                AnchoredWorkspaceFiles.Kind.SYMLINK -> "Symbolic link; not followed"
                AnchoredWorkspaceFiles.Kind.DIRECTORY -> "Currently a directory"
                AnchoredWorkspaceFiles.Kind.OTHER -> "Unsupported file type"
                AnchoredWorkspaceFiles.Kind.FILE -> when {
                    recorded == null -> if (custody) "Present without a recorded copy version" else "Current file; no recorded version compared"
                    recorded == observed.version -> "Matches the recorded version at inspection"
                    else -> "Differs from the recorded file version"
                }
            }
            Text(status)
            recorded?.let { CatalogRecoveryVersion("Recorded", it) }
            observed.version?.let { CatalogRecoveryVersion("Observed", it) }
        }
    }
}

@Composable
private fun CatalogRecoveryVersion(label: String, version: CanonicalFileSnapshot.Version) {
    Text("$label: ${version.size} bytes · permissions 0${version.mode.toString(8)}\nIdentity: ${version.identity}\nSHA-256: ${version.sha256}",
        style = MaterialTheme.typography.bodySmall)
}

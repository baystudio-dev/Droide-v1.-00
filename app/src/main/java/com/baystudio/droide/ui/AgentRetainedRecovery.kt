package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.MirrorRetainedRecovery
import com.baystudio.droide.core.MirrorInterruptedRecovery
import com.baystudio.droide.core.MirrorMetadataAuthority
import com.baystudio.droide.core.MirrorConflictRecovery
import com.baystudio.droide.core.AnchoredWorkspaceFiles
import com.baystudio.droide.core.WorkspaceAgentPluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal interface AgentRetainedRecoveryActions {
    val supportsSqliteEvidence: Boolean get() = false
    suspend fun captureSqliteEvidence(): String = error("Rollback evidence capture unavailable")
    suspend fun buildSqliteCandidate(): String = error("Forensic candidate construction unavailable")
    val supportsDirectoryArchive: Boolean get() = false
    val supportsConflictReview: Boolean get() = false
    val supportsRetentionReview: Boolean get() = false
    suspend fun reviewRetention(item: MirrorRetainedRecovery.Item): MirrorRetainedRecovery.RetentionReview = error("Retention review unavailable")
    suspend fun list(cursor: MirrorRetainedRecovery.Cursor?): MirrorRetainedRecovery.Page
    suspend fun inspect(item: MirrorRetainedRecovery.Item): MirrorRetainedRecovery.Inspection
    suspend fun copy(inspection: MirrorRetainedRecovery.Inspection, destination: String): Long
    suspend fun archive(item: MirrorRetainedRecovery.Item, destination: String): Long =
        error("Folder recovery is unavailable for this workspace")
    suspend fun inspectInterrupted(operation: MirrorMetadataAuthority.Intent): MirrorInterruptedRecovery.Review =
        error("Interrupted recovery is unavailable for this workspace")
    suspend fun finishInterrupted(review: MirrorInterruptedRecovery.Review, complete: Boolean): Unit =
        error("Interrupted recovery is unavailable for this workspace")
    suspend fun inspectVersion(item: MirrorRetainedRecovery.Item): MirrorConflictRecovery.VersionReview = error("Version review unavailable")
    suspend fun acknowledge(review: MirrorConflictRecovery.VersionReview): Unit = error("Version review unavailable")
    suspend fun inspectConflict(path: String): MirrorConflictRecovery.Review = error("Conflict review unavailable")
    suspend fun resolveConflict(review: MirrorConflictRecovery.Review): Unit = error("Conflict review unavailable")
}

internal fun WorkspaceAgentPluginHost.retainedRecoveryActions() = object : AgentRetainedRecoveryActions {
    override val supportsSqliteEvidence = true
    override suspend fun captureSqliteEvidence(): String {
        val review = captureSqliteRollbackEvidence()
        return "Raw database and rollback journal preserved at ${review.evidenceDirectory.name}. " +
            "Writer exclusion is unproven. Replay, activation and cleanup remain blocked."
    }
    override suspend fun buildSqliteCandidate(): String {
        val candidate = buildSqliteForensicCandidate()
        return "Unverified forensic candidate at ${candidate.directory.parentFile.name}/${candidate.directory.name}/candidate.sqlite. " +
            "${candidate.bytes} bytes; ${candidate.restoredPages} page images copied; SHA-256 ${candidate.sha256}. " +
            "Journal subset: ${candidate.journalShape.name.lowercase().replace('_', ' ')}. " +
            "Source coherence and writer exclusion are unproven. Originals and evidence are preserved. " +
            "This is not live recovery: workspace activation and cleanup remain blocked."
    }
    override val supportsDirectoryArchive = true
    override val supportsConflictReview = true
    override val supportsRetentionReview = true
    override suspend fun reviewRetention(item: MirrorRetainedRecovery.Item) = reviewRetainedVersionRetention(item)
    override suspend fun list(cursor: MirrorRetainedRecovery.Cursor?) = retainedWorkspaceVersions(cursor)
    override suspend fun inspect(item: MirrorRetainedRecovery.Item) = inspectRetainedWorkspaceVersion(item)
    override suspend fun copy(inspection: MirrorRetainedRecovery.Inspection, destination: String) = saveRetainedWorkspaceCopy(inspection, destination)
    override suspend fun archive(item: MirrorRetainedRecovery.Item, destination: String) = archiveRetainedWorkspaceFolder(item, destination)
    override suspend fun inspectInterrupted(operation: MirrorMetadataAuthority.Intent) = inspectInterruptedWorkspaceChange(operation)
    override suspend fun finishInterrupted(review: MirrorInterruptedRecovery.Review, complete: Boolean) = finishInterruptedWorkspaceChange(review, complete)
    override suspend fun inspectVersion(item: MirrorRetainedRecovery.Item) = inspectRetainedAcknowledgement(item)
    override suspend fun acknowledge(review: MirrorConflictRecovery.VersionReview) = acknowledgeRetainedVersion(review)
    override suspend fun inspectConflict(path: String) = inspectWorkspaceConflict(path)
    override suspend fun resolveConflict(review: MirrorConflictRecovery.Review) = resolveWorkspaceConflict(review)
}

internal data class AgentRetainedRecoveryState(
    val open: Boolean = false,
    val busy: Boolean = false,
    val page: MirrorRetainedRecovery.Page? = null,
    val inspection: MirrorRetainedRecovery.Inspection? = null,
    val interrupted: MirrorInterruptedRecovery.Review? = null,
    val directory: MirrorRetainedRecovery.Item? = null,
    val versionReview: MirrorConflictRecovery.VersionReview? = null,
    val conflictReview: MirrorConflictRecovery.Review? = null,
    val retentionReview: MirrorRetainedRecovery.RetentionReview? = null,
    val destination: String = "",
    val error: String? = null,
    val notice: String? = null,
)

internal class AgentRetainedRecoveryController(
    private val scope: CoroutineScope,
    private val actions: AgentRetainedRecoveryActions,
    private val allowed: () -> Boolean,
) {
    var state by mutableStateOf(AgentRetainedRecoveryState())
        private set
    private var generation = 0L
    private var task: Job? = null

    fun open() {
        if (state.busy || !allowed()) return
        state = AgentRetainedRecoveryState(open = true)
        page(null)
    }

    fun page(cursor: MirrorRetainedRecovery.Cursor?) = operate {
        state.copy(page = actions.list(cursor), retentionReview = null, inspection = null, interrupted = null, directory = null,
            versionReview = null, conflictReview = null, destination = "", notice = null)
    }

    val supportsSqliteEvidence get() = actions.supportsSqliteEvidence
    fun captureSqliteEvidence() {
        if (!actions.supportsSqliteEvidence || state.error == null) return
        operate { state.copy(notice = actions.captureSqliteEvidence()) }
    }

    fun buildSqliteCandidate() {
        if (!actions.supportsSqliteEvidence || state.error == null) return
        operate { state.copy(notice = actions.buildSqliteCandidate()) }
    }

    fun inspectInterrupted(operation: MirrorMetadataAuthority.Intent) {
        if (state.page?.interrupted?.contains(operation) != true) return
        operate { state.copy(interrupted = actions.inspectInterrupted(operation), inspection = null, notice = null) }
    }

    fun finishInterrupted(complete: Boolean) {
        val review = state.interrupted ?: return
        if (complete && !review.canComplete) return
        operate {
            actions.finishInterrupted(review, complete)
            refreshedAfterMutation(if (complete) "Verified change completed. Retained versions and existing conflicts were kept."
                else "Interrupted change is now protected for manual review. Every file version was kept.")
        }
    }

    fun canReview(item: MirrorRetainedRecovery.Item) = actions.supportsConflictReview &&
        item.role == MirrorRetainedRecovery.Role.ORIGINAL && item.operation.phase in setOf(
            MirrorMetadataAuthority.Phase.APPLIED, MirrorMetadataAuthority.Phase.CONFLICT) &&
        item.kind in setOf(AnchoredWorkspaceFiles.Kind.FILE, AnchoredWorkspaceFiles.Kind.DIRECTORY)

    val supportsRetentionReview get() = actions.supportsRetentionReview
    fun reviewRetention(item: MirrorRetainedRecovery.Item) {
        if (!actions.supportsRetentionReview || item !in state.page?.items.orEmpty()) return
        operate { state.copy(retentionReview = actions.reviewRetention(item), inspection = null, directory = null,
            versionReview = null, conflictReview = null, interrupted = null, notice = null) }
    }

    val supportsConflictReview get() = actions.supportsConflictReview

    fun inspectVersion(item: MirrorRetainedRecovery.Item) {
        if (!canReview(item) || item !in state.page?.items.orEmpty()) return
        operate { state.copy(versionReview = actions.inspectVersion(item), inspection = null, directory = null, interrupted = null, conflictReview = null, notice = null) }
    }

    fun acknowledge() {
        val review = state.versionReview ?: return
        operate { actions.acknowledge(review); refreshedAfterMutation("Version review saved. Retained bytes were kept; later changes will need review again.") }
    }

    fun inspectConflict(path: String) {
        if (!actions.supportsConflictReview || path !in state.page?.conflicts.orEmpty()) return
        operate { state.copy(conflictReview = actions.inspectConflict(path), inspection = null, directory = null, interrupted = null, versionReview = null, notice = null) }
    }

    fun resolveConflict() {
        val review = state.conflictReview?.takeIf { it.canResolve } ?: return
        val previousAttempt = review.lastResolutionAttempt
        operate(onInterrupted = {
            when (review.lastResolutionAttempt?.takeUnless { it === previousAttempt }?.outcome) {
                MirrorConflictRecovery.ResolutionAttempt.Outcome.CONFIRMED -> state.copy(page = null, conflictReview = null,
                    notice = "Saved-file status was committed before interruption. Unsaved edits and retained versions were kept. Refresh recovery.", error = null)
                MirrorConflictRecovery.ResolutionAttempt.Outcome.UNKNOWN -> state.copy(page = null, conflictReview = null,
                    notice = "Saved-file status may have changed. Unsaved edits and retained versions were kept. Refresh before retrying.", error = null)
                else -> null
            }
        }) { actions.resolveConflict(review); refreshedAfterMutation("Matching saved files were resolved. Unsaved editor changes remain separate; all retained versions were kept.") }
    }

    private suspend fun refreshedAfterMutation(notice: String): AgentRetainedRecoveryState {
        val completed = state.copy(page = null, inspection = null, interrupted = null, directory = null,
            versionReview = null, conflictReview = null, destination = "", notice = notice)
        return try { completed.copy(page = actions.list(null)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { completed.copy(error = "Recovery status was saved. Refresh to reload the list.") }
    }

    fun inspect(item: MirrorRetainedRecovery.Item) {
        if (!canSelect(item) || state.page?.items?.contains(item) != true) return
        if (canArchive(item)) {
            if (!state.open || state.busy || !allowed()) return
            state = state.copy(directory = item, inspection = null, interrupted = null,
                destination = item.operation.path.substringAfterLast('/') + "-recovered.tar", notice = null, error = null)
            return
        }
        operate {
            val inspection = actions.inspect(item)
            val name = item.operation.path.substringAfterLast('/')
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            state.copy(inspection = inspection, destination = name.take(dot) + "-recovered" + name.substring(dot), notice = null)
        }
    }

    fun canArchive(item: MirrorRetainedRecovery.Item) = actions.supportsDirectoryArchive && item.kind == AnchoredWorkspaceFiles.Kind.DIRECTORY
    fun canSelect(item: MirrorRetainedRecovery.Item) = item.canCopy || canArchive(item)

    fun destination(value: String) {
        if (state.open && !state.busy) state = state.copy(destination = value, error = null)
    }

    fun save() {
        val directory = state.directory
        if (directory != null) {
            val destination = state.destination
            if (destination.isBlank()) return
            operate {
                actions.archive(directory, destination)
                state.copy(directory = null, destination = "", notice = "Saved $destination. Retained folders and unresolved conflicts were kept.")
            }
            return
        }
        val selection = state.inspection ?: return
        val destination = state.destination
        if (destination.isBlank()) return
        operate {
            actions.copy(selection, destination)
            state.copy(inspection = null, destination = "", notice = "Saved $destination. Original versions and unresolved conflicts were kept.")
        }
    }

    fun close() {
        generation++
        task?.cancel()
        task = null
        state = AgentRetainedRecoveryState()
    }

    private fun operate(onInterrupted: () -> AgentRetainedRecoveryState? = { null },
        action: suspend () -> AgentRetainedRecoveryState) {
        if (!state.open || state.busy || !allowed()) return
        val request = ++generation
        state = state.copy(busy = true, error = null)
        task = scope.launch {
            try {
                check(allowed()) { "Workspace recovery is temporarily unavailable" }
                val result = action()
                coroutineContext.ensureActive()
                if (generation == request && state.open && allowed()) state = result.copy(busy = false)
            } catch (cancelled: CancellationException) {
                if (generation == request && state.open) onInterrupted()?.let { state = it.copy(busy = false) }
                throw cancelled
            } catch (failure: Exception) {
                if (generation == request && state.open) state = onInterrupted()?.copy(busy = false) ?: state.copy(busy = false,
                    error = (failure.message ?: "Recovery could not finish. Original versions were kept.").take(2_000))
            } catch (_: LinkageError) {
                if (generation == request && state.open) state = onInterrupted()?.copy(busy = false) ?: state.copy(busy = false,
                    error = "File recovery is unavailable on this device. Original versions were kept.")
            } finally {
                if (generation == request) {
                    if (state.open) state = state.copy(busy = false)
                    task = null
                }
            }
        }
    }
}

@Composable
internal fun AgentRetainedRecoveryDialog(controller: AgentRetainedRecoveryController) {
    val state = controller.state
    if (!state.open) return
    Dialog(onDismissRequest = controller::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 600.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Retained CLI versions", style = MaterialTheme.typography.headlineSmall)
                val selection = state.inspection
                val interrupted = state.interrupted
                val versionReview = state.versionReview
                val conflictReview = state.conflictReview
                val retentionReview = state.retentionReview
                if (retentionReview != null) {
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentRetainedRecoveryNotices(state)
                        Text("Why this version is kept", style = MaterialTheme.typography.titleMedium)
                        Text("${retentionReview.item.operation.path} · ${retentionReview.item.role.label}")
                        Text("Observed history revision ${retentionReview.revision} · SHA-256 ${retentionReview.sha256.take(16)}…")
                        Text(if (retentionReview.editorAvailable) "${retentionReview.documentVersions.size} overlapping editor document version(s) checked."
                            else "Editor versions could not be checked.")
                        retentionReview.blockers.forEach { Text("• $it") }
                        Text("Read-only check, not approval to release storage. Changes require a new check. All versions are kept; no space was freed.")
                    }
                } else if (versionReview != null || conflictReview != null) {
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentRetainedRecoveryNotices(state)
                        if (versionReview != null) {
                            Text(versionReview.item.operation.path)
                            Text(if (versionReview.item.kind == AnchoredWorkspaceFiles.Kind.FILE) "${versionReview.bytes} bytes" else "Folder · ${versionReview.files} files")
                            Text("SHA-256 ${versionReview.digest.take(16)}…", style = MaterialTheme.typography.bodySmall)
                            Text("Confirm after reviewing this version. Data is kept; changes require a new review.")
                            TextButton(onClick = controller::acknowledge, enabled = !state.busy) { Text("Acknowledge reviewed version") }
                        } else if (conflictReview != null) {
                            Text(conflictReview.path)
                            Text(if (conflictReview.matchingFiles) "Saved project and CLI files match."
                                else "Saved project and CLI files differ. Merge or copy the preferred contents before resolving.")
                            if (conflictReview.unreviewedVersions > 0) Text("Review ${conflictReview.unreviewedVersions} changed retained versions first.")
                            Text("Use matching saved files as the sync baseline. Unsaved editor changes are neither saved nor discarded. All retained versions are kept.")
                            TextButton(onClick = controller::resolveConflict, enabled = !state.busy && conflictReview.canResolve) { Text("Resolve matching saved files") }
                        }
                    }
                } else if (interrupted != null) {
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentRetainedRecoveryNotices(state)
                        Text(interrupted.operation.path)
                        Text(if (interrupted.canComplete) "Change and retained versions verified. Complete the interrupted record to resume sync."
                            else "Publication could not be verified. Keep this path protected as a conflict so other work can continue.")
                        Text("Only recovery status changes. Files and retained versions are kept.")
                        TextButton(onClick = { controller.finishInterrupted(true) }, enabled = !state.busy && interrupted.canComplete) {
                            Text("Complete verified change")
                        }
                        TextButton(onClick = { controller.finishInterrupted(false) }, enabled = !state.busy) {
                            Text("Keep as conflict")
                        }
                    }
                } else if (selection != null || state.directory != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AgentRetainedRecoveryNotices(state)
                        Text((selection?.item ?: state.directory!!).operation.path)
                        Text(selection?.let { "${it.item.role.label} · ${it.version.size} bytes" }
                            ?: "Folder · TAR archive with all contents, empty folders and links")
                        OutlinedTextField(
                            value = state.destination,
                            onValueChange = controller::destination,
                            label = { Text("New project path") },
                            enabled = !state.busy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val selectedItem = selection?.item ?: state.directory
                        if (selectedItem != null && controller.supportsRetentionReview) {
                            TextButton(onClick = { controller.reviewRetention(selectedItem) }, enabled = !state.busy) { Text("Check why this version is kept") }
                        }
                        if (selectedItem != null && controller.canReview(selectedItem)) {
                            TextButton(onClick = { controller.inspectVersion(selectedItem) }, enabled = !state.busy) { Text("Review this version") }
                        }
                    }
                } else {
                    val listState = remember(state.page) { LazyListState() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "notice") { AgentRetainedRecoveryNotices(state) }
                        items(if (controller.supportsConflictReview) state.page?.conflicts.orEmpty() else emptyList(), key = { "conflict:$it" }) { path ->
                            TextButton(onClick = { controller.inspectConflict(path) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review conflict · $path") }
                        }
                        items(state.page?.interrupted.orEmpty(), key = { "interrupted:${it.id}" }) { operation ->
                            TextButton(onClick = { controller.inspectInterrupted(operation) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("Review interrupted change · ${operation.path}")
                            }
                        }
                        items(state.page?.items.orEmpty(), key = { "${it.operation.id}:${it.role}" }) { item ->
                            TextButton(
                                onClick = { controller.inspect(item) },
                                enabled = !state.busy && controller.canSelect(item),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(item.operation.path)
                                    Text("${item.role.label} · ${item.bytes?.let { "$it bytes" } ?: if (controller.canArchive(item)) "Folder (save TAR copy)" else "${item.kind} (not available for copying)"}")
                                }
                            }
                        }
                        if (state.page?.items?.isEmpty() == true && state.page.interrupted.isEmpty())
                            item(key = "empty") { Text("No retained files on this page.") }
                        if (state.error != null && controller.supportsSqliteEvidence) item(key = "sqlite-evidence") {
                            Text("Stops active CLI sessions. Keeps the workspace blocked and saves raw evidence without replaying the database.")
                            TextButton(onClick = controller::captureSqliteEvidence, enabled = !state.busy) {
                                Text("Preserve rollback evidence (does not resume workspace)")
                            }
                            Text("Copies database pages to a separate candidate without opening it in SQLite. The result is unverified and cannot resume this workspace.")
                            TextButton(onClick = controller::buildSqliteCandidate, enabled = !state.busy) {
                                Text("Build unverified forensic candidate")
                            }
                        }
                        item(key = "navigation") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { controller.page(null) }, enabled = !state.busy) { Text("Refresh") }
                                state.page?.next?.let { cursor ->
                                    TextButton(onClick = { controller.page(cursor) }, enabled = !state.busy) { Text("Next") }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (retentionReview != null || versionReview != null || conflictReview != null || interrupted != null) {
                        TextButton(onClick = { controller.page(null) }, enabled = !state.busy) { Text("Back") }
                    }
                    TextButton(onClick = controller::close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                    if (state.inspection != null || state.directory != null) {
                        TextButton(
                            onClick = controller::save,
                            enabled = !state.busy && state.destination.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(if (state.directory != null) "Save TAR copy" else "Save copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRetainedRecoveryNotices(state: AgentRetainedRecoveryState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Save a file or folder archive with a new project name. Originals and conflicts are kept. Review and resolve separately.")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { DismissibleNotice(it, color = DroideColors.Error) }
        state.notice?.let { Text(it) }
    }
}

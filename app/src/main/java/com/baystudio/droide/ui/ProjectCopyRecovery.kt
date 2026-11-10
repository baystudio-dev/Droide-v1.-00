package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentService
import com.baystudio.droide.core.ProjectCopyEvidence
import com.baystudio.droide.core.ProjectCopyJournal
import com.baystudio.droide.core.WorkspaceImportController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal interface ProjectCopyRecoveryAccess : AutoCloseable {
    suspend fun list(): ProjectCopyJournal.Recovery
    suspend fun inspect(transactionId: String): ProjectCopyJournal.Inspection
    suspend fun acknowledge(inspection: ProjectCopyJournal.Inspection)
    val supportsHistoryRecovery: Boolean get() = false
    suspend fun inspectHistory(): ProjectCopyJournal.RetirementInspection = error("Open import recovery from Files to inspect project history")
    suspend fun finishHistory(inspection: ProjectCopyJournal.RetirementInspection): Unit = error("Project history recovery is unavailable")
    /** Idempotent, nonthrowing invalidation. This never copies or revokes an already accepted decision. */
    override fun close()
}

internal class ProjectCopyRecoveryActions(val open: suspend () -> ProjectCopyRecoveryAccess)

internal fun agentCopyRecoveryActions(agent: AgentService, sessionId: String) = ProjectCopyRecoveryActions {
    val bound = agent.openMediaRecovery()
    if (agent.currentSessionId != sessionId) {
        bound.close()
        error("The active conversation changed. Open recovery again.")
    }
    object : ProjectCopyRecoveryAccess {
        override suspend fun list() = bound.recovery()
        override suspend fun inspect(transactionId: String) = bound.inspect(transactionId)
        override suspend fun acknowledge(inspection: ProjectCopyJournal.Inspection) = bound.acknowledge(inspection.token)
        override fun close() = bound.close()
    }
}

internal fun importCopyRecoveryActions(imports: WorkspaceImportController) = ProjectCopyRecoveryActions {
    object : ProjectCopyRecoveryAccess {
        private val closed = AtomicBoolean(false)
        private val inspected = AtomicReference<ProjectCopyJournal.Inspection?>(null)
        private val history = AtomicReference<ProjectCopyJournal.RetirementInspection?>(null)
        override val supportsHistoryRecovery: Boolean get() = true
        private fun requireOpen() {
            check(!closed.get() && !imports.state.value.closed) { "The project recovery view was closed. Open it again." }
        }
        override suspend fun list(): ProjectCopyJournal.Recovery {
            requireOpen()
            return imports.recovery().also { currentCoroutineContext().ensureActive(); requireOpen() }
        }
        override suspend fun inspect(transactionId: String): ProjectCopyJournal.Inspection {
            requireOpen()
            inspected.set(null)
            return imports.inspectRecovery(transactionId).also {
                currentCoroutineContext().ensureActive()
                requireOpen()
                inspected.set(it)
            }
        }
        override suspend fun acknowledge(inspection: ProjectCopyJournal.Inspection) {
            requireOpen()
            currentCoroutineContext().ensureActive()
            check(inspected.getAndSet(null) === inspection) { "Inspection belongs to another recovery view; inspect again" }
            imports.acknowledgeRecovery(inspection.token)
            currentCoroutineContext().ensureActive()
            requireOpen()
        }
        override suspend fun inspectHistory(): ProjectCopyJournal.RetirementInspection {
            requireOpen()
            history.set(null)
            return imports.inspectRetirementRecovery().also {
                currentCoroutineContext().ensureActive()
                requireOpen()
                history.set(it)
            }
        }
        override suspend fun finishHistory(inspection: ProjectCopyJournal.RetirementInspection) {
            requireOpen()
            currentCoroutineContext().ensureActive()
            check(history.getAndSet(null) === inspection) { "Inspect project history again before confirming" }
            imports.finishRetirementRecovery(inspection.token, ::requireOpen)
        }
        override fun close() { closed.set(true); inspected.set(null); history.set(null) }
    }
}

internal data class ProjectCopyRecoveryUiState(
    val open: Boolean = false,
    val working: Boolean = false,
    val records: List<ProjectCopyJournal.Record> = emptyList(),
    val blockingProblem: String? = null,
    val inspection: ProjectCopyJournal.Inspection? = null,
    val error: String? = null,
    val notice: String? = null,
    val grantedTransaction: String? = null,
    val supportsHistoryRecovery: Boolean = false,
    val historyInspection: ProjectCopyJournal.RetirementInspection? = null,
)

/** This state is never saved or restored. Closing/replacing its owner invalidates every callback. */
internal class ProjectCopyRecoveryController(
    private val owner: ProjectCopyJournal.Scope,
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val actions: () -> ProjectCopyRecoveryActions,
) {
    var state by mutableStateOf(ProjectCopyRecoveryUiState())
        private set
    private var generation = 0L
    private var task: Job? = null
    private var access: ProjectCopyRecoveryAccess? = null
    private var disposed = false

    fun open() {
        if (disposed || !allowed() || state.working) return
        closeAccess()
        val request = generation + 1
        val factory = actions()
        val initial = ProjectCopyRecoveryUiState(open = true, working = true)
        operate(initial) {
            val opened = factory.open()
            var adopted = false
            try {
                currentCoroutineContext().ensureActive()
                check(!disposed && generation == request && allowed() && state.open) { "Recovery owner changed" }
                access = opened
                adopted = true
                val recovery = opened.list()
                check(recovery.records.all(::owns)) { "Recovery belongs to a different project or conversation" }
                val recoverable = recovery.records.filter { it.status == ProjectCopyJournal.Status.UNKNOWN || it.needsStageInspection }
                check(recoverable.map { it.intent.id }.distinct().size == recoverable.size) { "Recovery contains duplicate transactions" }
                initial.copy(working = false, records = recoverable, blockingProblem = recovery.blockingProblem,
                    supportsHistoryRecovery = owner.kind == ProjectCopyJournal.Kind.EXPLORER_IMPORT && opened.supportsHistoryRecovery)
            } finally {
                if (!adopted) opened.close()
            }
        }
    }

    fun inspect(transactionId: String) {
        if (!canAct() || state.blockingProblem != null) return
        val ownedAccess = access ?: return
        val selected = state.records.singleOrNull { it.intent.id == transactionId } ?: return
        if (selected.active || selected.problem != null || transactionId == state.grantedTransaction) return
        val initial = state.copy(working = true, inspection = null, error = null, notice = null)
        operate(initial) {
            val inspection = ownedAccess.inspect(transactionId)
            check(owns(inspection.record) && inspection.record.intent == selected.intent &&
                inspection.record.evidenceHash == selected.evidenceHash &&
                inspection.record.status == selected.status &&
                (inspection.record.status == ProjectCopyJournal.Status.UNKNOWN || inspection.record.needsStageInspection) &&
                !inspection.record.active && inspection.record.problem == null) {
                "The selected recovery transaction changed. Refresh and inspect again."
            }
            initial.copy(working = false, inspection = inspection)
        }
    }

    fun confirm() {
        if (!canAct() || state.blockingProblem != null) return
        val ownedAccess = access ?: return
        val inspection = state.inspection ?: return
        if (!owns(inspection.record) || inspection.record.status != ProjectCopyJournal.Status.UNKNOWN ||
            inspection.stage?.status == ProjectCopyEvidence.StageStatus.UNSAFE) return
        val initial = state.copy(working = true, error = null, notice = null)
        operate(initial) {
            // Carry the same opaque inspection; the backend rechecks ownership, lifetime and evidence.
            ownedAccess.acknowledge(inspection)
            initial.copy(working = false, inspection = null, grantedTransaction = inspection.record.intent.id,
                notice = "Single-use permission granted for one NEW manual request. " +
                    "No copy, retry, overwrite, or deletion was performed. The previous outcome remains UNKNOWN. " +
                    "Existing queued requests or open pickers are not authorized. " +
                    "Permission expires within five minutes of inspection and is rejected if evidence changes.")
        }
    }

    fun back() {
        if (canAct()) state = state.copy(inspection = null, historyInspection = null, error = null, notice = null)
    }

    fun inspectHistory() {
        if (!canAct() || !state.supportsHistoryRecovery || state.blockingProblem == null) return
        val ownedAccess = access ?: return
        val initial = state.copy(working = true, inspection = null, historyInspection = null, error = null, notice = null)
        operate(initial) { initial.copy(working = false, historyInspection = ownedAccess.inspectHistory()) }
    }

    fun finishHistory() {
        if (!canAct() || !state.supportsHistoryRecovery) return
        val ownedAccess = access ?: return
        val inspected = state.historyInspection ?: return
        val initial = state.copy(working = true, error = null, notice = null)
        operate(initial) {
            ownedAccess.finishHistory(inspected)
            val recovery = ownedAccess.list()
            check(recovery.records.all(::owns)) { "Recovery belongs to a different project" }
            initial.copy(working = false, historyInspection = null, blockingProblem = recovery.blockingProblem,
                records = recovery.records.filter { it.status == ProjectCopyJournal.Status.UNKNOWN || it.needsStageInspection },
                notice = "Verified history archiving completed. Review any remaining recovery items before a new import.")
        }
    }

    fun close() {
        generation++
        task?.cancel()
        task = null
        closeAccess()
        state = ProjectCopyRecoveryUiState()
    }

    fun dispose() { disposed = true; close() }

    private fun closeAccess() { access?.close(); access = null }

    private fun owns(record: ProjectCopyJournal.Record) =
        record.intent.owner.kind == owner.kind && record.intent.owner.sessionId == owner.sessionId

    private fun canAct() = !disposed && allowed() && state.open && !state.working

    private fun operate(initial: ProjectCopyRecoveryUiState, operation: suspend () -> ProjectCopyRecoveryUiState) {
        val ticket = ++generation
        task?.cancel()
        state = initial
        task = scope.launch {
            fun current() = !disposed && generation == ticket && state.open && allowed()
            try {
                val next = operation()
                currentCoroutineContext().ensureActive()
                if (current()) state = next
            } catch (cancelled: CancellationException) {
                if (current()) state = state.copy(working = false, inspection = null, historyInspection = null,
                    error = "Recovery was interrupted. A confirmation may already have been accepted; no copy was started here. Reopen recovery before deciding again.")
                throw cancelled
            } catch (failure: Exception) {
                if (current()) state = state.copy(working = false, inspection = null, historyInspection = null,
                    error = failure.message?.take(1_200) ?: "Recovery could not be verified. Refresh and inspect again.")
            } catch (failure: LinkageError) {
                if (current()) state = state.copy(working = false, inspection = null, historyInspection = null,
                    error = "Recovery is unavailable on this device. No successful confirmation was verified.")
            }
        }
    }
}

@Composable
internal fun rememberProjectCopyRecoveryController(
    instance: Any,
    projectKey: String,
    owner: ProjectCopyJournal.Scope,
    enabled: Boolean,
    actions: ProjectCopyRecoveryActions,
): ProjectCopyRecoveryController {
    val scope = rememberWorkspaceCoroutineScope()
    val currentEnabled = rememberUpdatedState(enabled)
    val currentActions = rememberUpdatedState(actions)
    val currentInstance = rememberUpdatedState(instance)
    val currentProject = rememberUpdatedState(projectKey)
    val currentOwner = rememberUpdatedState(owner)
    val controller = remember(instance, projectKey, owner) {
        ProjectCopyRecoveryController(owner, scope, allowed = {
            currentEnabled.value && currentInstance.value === instance && currentProject.value == projectKey && currentOwner.value == owner
        }, actions = { currentActions.value })
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(controller, enabled) { if (!enabled) controller.close() }
    return controller
}

/** Exactly one entry belongs in each existing menu; this does not add a toolbar button or banner. */
@Composable
internal fun ProjectCopyRecoveryMenuItem(imports: Boolean, enabled: Boolean, onOpen: () -> Unit) {
    DropdownMenuItem(text = { Text(if (imports) "Import recovery" else "Media recovery") },
        onClick = onOpen, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectCopyRecoverySheet(
    controller: ProjectCopyRecoveryController,
    imports: Boolean,
    projectKey: String,
    sessionId: String? = null,
    enabled: Boolean,
) {
    if (!enabled || !controller.state.open) return
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(controller) { focus.clearFocus(); keyboard?.hide() }
    ModalBottomSheet(onDismissRequest = controller::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = DroideColors.Surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            ProjectCopyRecoveryContent(controller.state, imports, projectKey, sessionId, maxHeight * .92f,
                controller::inspect, controller::confirm, controller::back, controller::open, controller::close,
                controller::inspectHistory, controller::finishHistory)
        }
    }
}

@Composable
internal fun ProjectCopyRecoveryContent(
    state: ProjectCopyRecoveryUiState,
    imports: Boolean,
    projectKey: String,
    sessionId: String?,
    maxHeight: Dp,
    onInspect: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onInspectHistory: () -> Unit = {},
    onFinishHistory: () -> Unit = {},
) {
    val action = if (imports) "Allow a new import" else "Allow a new copy"
    val contentScroll = key(state.inspection?.token, state.historyInspection?.token) { rememberScrollState() }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("project-copy-recovery")
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (imports) "Import recovery" else "Media recovery", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(contentScroll),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Project: $projectKey", style = MaterialTheme.typography.labelSmall)
            sessionId?.let { Text("Original conversation: $it", style = MaterialTheme.typography.labelSmall) }
            state.blockingProblem?.let { DismissibleNotice("Recovery is blocked: $it", color = DroideColors.Error, ownerKey = projectKey) }
            state.error?.let { DismissibleNotice(it, color = DroideColors.Error, ownerKey = projectKey) }
            state.notice?.let { DismissibleNotice(it, ownerKey = projectKey) }
            val inspection = state.inspection
            if (state.historyInspection != null && imports) {
                Text("Interrupted history archiving", style = MaterialTheme.typography.titleSmall)
                Text("Transaction: ${state.historyInspection.transactionId}", style = MaterialTheme.typography.bodySmall)
                Text(if (state.historyInspection.location == ProjectCopyJournal.RetirementLocation.ACTIVE_HISTORY)
                    "The verified completed record is still in active history."
                else "The verified completed record is already in the history archive.", style = MaterialTheme.typography.bodySmall)
                Text("Finish archiving this completed history record. All evidence is checked again before confirmation. " +
                    "Project files and retained copies remain preserved.", style = MaterialTheme.typography.bodySmall)
                Text("Confirmation expires after five minutes. Changed evidence requires another inspection.",
                    style = MaterialTheme.typography.labelSmall)
            } else if (inspection != null) {
                Text("Selected transaction", style = MaterialTheme.typography.titleSmall)
                Text("Transaction: ${inspection.record.intent.id}\nDestination: ${inspection.record.intent.destination}\n" +
                    "Previous outcome: ${inspection.record.status}", style = MaterialTheme.typography.bodySmall)
                Text("Current destination evidence", style = MaterialTheme.typography.labelLarge)
                Text(projectCopyEvidenceText(inspection.current), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
                inspection.stage?.let { retained ->
                    Text("Retained copy", style = MaterialTheme.typography.labelLarge)
                    Text(projectCopyStageEvidenceText(retained), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
                if (inspection.stage?.status == ProjectCopyEvidence.StageStatus.UNSAFE) {
                    Text("Resolve the recovery location before allowing a new copy.",
                        color = DroideColors.Error, style = MaterialTheme.typography.bodySmall)
                }
                if (inspection.record.status == ProjectCopyJournal.Status.UNKNOWN &&
                    inspection.stage?.status != ProjectCopyEvidence.StageStatus.UNSAFE) {
                    Text("Current evidence does not prove the earlier copy succeeded. Inspection reads at most 64 MiB. " +
                        "Confirmation is rejected if project, transaction, or destination evidence changes.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("$action grants a single-use permission for a separately requested NEW manual operation. " +
                        "It does not copy, retry, overwrite, delete, or mark the old transaction successful. " +
                        "Existing queued requests or open pickers do not receive this permission.",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("Permission expires within five minutes of inspection. If needed, inspect again.",
                        style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Read-only inspection. The recorded outcome is preserved. " +
                        "File contents are checked only within the 64 MiB inspection limit.",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(if (state.records.any { it.needsStageInspection })
                    "Retained copies and uncertain past copies. Inspect a transaction to check the available files."
                else "Uncertain past copies. Inspect one transaction before allowing a new manual request.",
                    style = MaterialTheme.typography.bodySmall)
                if (!state.working && state.records.isEmpty() && state.error == null && state.blockingProblem == null) {
                    Text("No uncertain copies in this ${if (imports) "project" else "conversation"}.", style = MaterialTheme.typography.bodySmall)
                }
                state.records.forEach { record ->
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, DroideColors.Border), color = DroideColors.Surface2) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.intent.destination, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("Transaction: ${record.intent.id}\nPrevious outcome: ${record.status}", style = MaterialTheme.typography.labelSmall)
                            record.intent.plannedStagePath?.let { path ->
                                Text("Recovery location: $path", style = MaterialTheme.typography.labelSmall)
                            }
                            record.problem?.let { Text(it, color = DroideColors.Error, style = MaterialTheme.typography.bodySmall) }
                            if (record.active) Text("This operation is still active. Finish or stop it before inspecting.", style = MaterialTheme.typography.labelSmall)
                            if (state.grantedTransaction == record.intent.id) Text("Single-use permission granted; old outcome remains UNKNOWN.", style = MaterialTheme.typography.labelSmall)
                            OutlinedButton(onClick = { onInspect(record.intent.id) },
                                enabled = !state.working && state.blockingProblem == null && !record.active && record.problem == null &&
                                    state.grantedTransaction != record.intent.id,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("inspect-copy:${record.intent.id}")) { Text("Inspect") }
                        }
                    }
                }
            }
        }
        if (state.working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Checking recovery…", style = MaterialTheme.typography.labelSmall)
        }
        if (imports && state.supportsHistoryRecovery && state.blockingProblem != null) {
            if (state.historyInspection != null) {
                Button(onClick = onFinishHistory, enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish archiving history") }
            } else {
                OutlinedButton(onClick = onInspectHistory, enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Inspect interrupted history") }
            }
        }
        // Separate confirmation is fixed below the scrollable evidence, never an automatic continuation.
        if (state.inspection?.record?.status == ProjectCopyJournal.Status.UNKNOWN &&
            state.inspection?.stage?.status != ProjectCopyEvidence.StageStatus.UNSAFE) {
            Button(onClick = onConfirm, enabled = !state.working && state.blockingProblem == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val inspected = state.inspection != null || state.historyInspection != null
            TextButton(onClick = if (inspected) onBack else onRefresh, enabled = !state.working,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (inspected) "Back to transactions" else "Refresh") }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }
    }
}

internal fun projectCopyEvidenceText(current: ProjectCopyEvidence.Current): String = buildString {
    val target = current.target
    if (target == null) append("Destination: absent at inspection\n")
    else append("Destination: ${target.type}\nBytes: ${target.size}\nModified: ${target.modified}\n" +
        "Identity: ${target.key}\nSHA-256: ${current.sha256 ?: "not available"}\n")
    append("Parent identities:\n")
    current.parents.forEach { (path, identity) -> append("${path.ifEmpty { "/ (project)" }}: $identity\n") }
}

internal fun projectCopyStageEvidenceText(stage: ProjectCopyEvidence.StageInspection): String = buildString {
    append("Recovery location: ${stage.relativePath ?: "not recorded"}\n")
    append("Check: ")
    append(when (stage.status) {
        ProjectCopyEvidence.StageStatus.LEGACY_UNAVAILABLE -> "Location was not recorded for this copy"
        ProjectCopyEvidence.StageStatus.ABSENT -> "No file found at inspection"
        ProjectCopyEvidence.StageStatus.UNVERIFIED -> "File found; contents are not verified"
        ProjectCopyEvidence.StageStatus.VERIFIED -> "Matches the recorded copy at inspection"
        ProjectCopyEvidence.StageStatus.MISMATCH -> "File differs from the recorded copy"
        ProjectCopyEvidence.StageStatus.TOO_LARGE -> "File exceeds the content-check limit; contents are not verified"
        ProjectCopyEvidence.StageStatus.UNSAFE -> "Location could not be safely checked"
    })
    append('\n')
    stage.bytes?.let { append("Bytes: $it\n") }
    stage.identity?.let { append("Identity: $it\n") }
    stage.sha256?.let { append("SHA-256: $it\n") }
    stage.detail?.let { append("Details: $it\n") }
}

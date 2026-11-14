package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@Serializable
enum class SubagentTaskState { QUEUED, STARTING, RUNNING, IDLE, ERROR, KILLED, INTERRUPTED }

@Serializable
enum class SubagentDeliveryState { NONE, PENDING, DELIVERED }

@Serializable
enum class SubagentWorkspaceMode { SHARED, ISOLATED }

@Serializable
enum class SubagentIntegrationState { NONE, PENDING, MERGED, CONFLICT, DISCARDED }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SubagentTaskRecord(
    val id: String,
    val parentSessionId: String,
    val childSessionId: String,
    val profile: String,
    val profileRevision: String = "",
    val description: String,
    val background: Boolean,
    val state: SubagentTaskState,
    val delivery: SubagentDeliveryState,
    val workspaceMode: SubagentWorkspaceMode = SubagentWorkspaceMode.SHARED,
    val workspaceName: String = "",
    val workspacePath: String = "",
    val workspaceBranch: String = "",
    val workspaceBaseCommit: String = "",
    val workspaceParentBranch: String = "",
    val workspaceParentRepositoryIdentity: String = "",
    val integration: SubagentIntegrationState = SubagentIntegrationState.NONE,
    val attempt: Int = 1,
    val providerId: String = "",
    val modelId: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    val lastPrompt: String = "",
    val result: String = "",
    val error: String = "",
    @EncodeDefault val createdAtMs: Long = System.currentTimeMillis(),
    @EncodeDefault val updatedAtMs: Long = System.currentTimeMillis(),
)

data class SubagentCompletion(val record: SubagentTaskRecord, val config: AgentConfig, val activeSiblings: Int = 0)

 
data class SubagentTaskView(
    val id: String,
    val childSessionId: String,
    val profile: String,
    val description: String,
    val state: SubagentTaskState,
    val background: Boolean,
    val workspaceMode: SubagentWorkspaceMode,
    val workspaceName: String,
    val integration: SubagentIntegrationState,
    val attempt: Int,
    val providerId: String,
    val modelId: String,
    val createdAtMs: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val updatedAtMs: Long,
    val activity: String = "",
    val activityTool: String = "",
    val activityStep: Int = 0,
    val resultPreview: String = "",
    val errorPreview: String = "",
    val queuePosition: Int = 0,
) {
    val active: Boolean get() = state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)
    val terminal: Boolean get() = !active
}

data class SubagentDashboardState(
    val parentSessionId: String = "",
    val tasks: List<SubagentTaskView> = emptyList(),
    val error: String = "",
) {
    val running: Int get() = tasks.count { it.state == SubagentTaskState.RUNNING || it.state == SubagentTaskState.STARTING }
    val queued: Int get() = tasks.count { it.state == SubagentTaskState.QUEUED }
    val completed: Int get() = tasks.count { it.state == SubagentTaskState.IDLE }
    val failed: Int get() = tasks.count { it.state in setOf(SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED) }
}

private data class SubagentLiveActivity(
    val detail: String = "",
    val tool: String = "",
    val step: Int = 0,
)

internal data class SubagentTaskTransition(val record: SubagentTaskRecord, val applied: Boolean)
internal class SubagentOwnedJob(val attempt: Int, val job: Job) {
    internal var resource: SubagentOwnedResources? = null
    internal var resourceRoot: String? = null
}

// Only resources acquired by this execution are registered here. Every registered
// disposer runs, even when another disposer throws during cancellation or startup.
internal class SubagentOwnedResources(private val reportFailure: (String, Exception) -> Unit) {
    private class Entry(val operation: String, val dispose: suspend () -> Unit,
                        val terminal: TerminalOwnershipObservationSource? = null, val root: String? = null)
    private val ownerLock = Any()
    private var version: Any = Any()
    private var ended = false
    private var disposing = false
    private val disposers = mutableListOf<Entry>()
    private var finishing: List<Entry> = emptyList()
    private var captured: Pair<Any, List<TerminalOwnershipObservation>>? = null

    fun register(operation: String, dispose: suspend () -> Unit): Unit = synchronized(ownerLock) {
        check(!ended && !disposing) { "Child resource acquisition has ended" }
        version = Any(); disposers.add(Entry(operation, dispose))
    }

    fun registerTerminal(operation: String, terminal: TerminalOwnershipObservationSource, root: String,
                         dispose: suspend () -> Unit): Unit = synchronized(ownerLock) {
        check(!ended && !disposing) { "Child resource acquisition has ended" }
        version = Any(); disposers.add(Entry(operation, dispose, terminal, root))
    }

    internal suspend fun observeTerminals(): ChildOwnershipObservation {
        currentCoroutineContext().ensureActive()
        val initial = synchronized(ownerLock) {
            check(!ended) { "Child resource lifetime ended; history is unavailable" }
            Triple(version, (disposers + finishing).toList(), disposing)
        }
        val observations = initial.second.mapNotNull { it.terminal?.observeTerminalOwnership() }
        val slot = Any()
        synchronized(ownerLock) {
            check(!ended && version === initial.first) { "Child resources changed during collection" }
            // One bounded slot per resource scope; newer collection invalidates older results.
            captured = slot to observations
        }
        val weak = java.lang.ref.WeakReference(this)
        val token = initial.first
        val retained = buildList {
            if (initial.second.isNotEmpty()) add("Child owned resource callbacks: ${initial.second.size}")
            if (initial.third) add("Child resource disposal is in progress")
            initial.second.filter { it.terminal != null }.forEach { add("Child terminal owner root: ${it.root}") }
            observations.forEach { addAll(it.retainedOwners) }
        }
        return ChildOwnershipObservation(retained) {
            val owner = weak.get() ?: error("Child resource authority unavailable")
            owner.validateCaptured(token, slot)
        }.also { it.validateMetadata() }
    }

    private fun validateCaptured(token: Any, slot: Any) {
        fun current(): List<TerminalOwnershipObservation> = synchronized(ownerLock) {
            check(!ended && version === token && captured?.first === slot) { "Child resource observation changed or ended" }
            checkNotNull(captured).second
        }
        val observations = current()
        observations.forEach { it.validateMetadata() }
        current()
    }

    suspend fun <T> use(action: suspend () -> T): T {
        var primary: Throwable? = null
        try {
            return action()
        } catch (failure: Throwable) {
            // This catch only retains ownership of the original failure; it never recovers it.
            primary = failure
            throw failure
        } finally {
            try { finish(primary) }
            finally { synchronized(ownerLock) {
                version = Any(); ended = true; captured = null; finishing = emptyList(); disposers.clear()
            } }
        }
    }

    private suspend fun finish(primary: Throwable?) {
        var outgoing = primary
        fun retain(failure: Throwable) {
            val current = outgoing
            if (current === failure) return
            if (current == null) {
                if (failure is CancellationException || failure !is Exception) outgoing = failure
            } else if (failure !is Exception && current is Exception) {
                // Fatal cleanup errors must not be hidden inside a recoverable exception.
                failure.addSuppressed(current)
                outgoing = failure
            } else current.addSuppressed(failure)
        }
        val pending = synchronized(ownerLock) {
            version = Any(); captured = null; disposing = true
            disposers.asReversed().toList().also { finishing = it; disposers.clear() }
        }
        for (entry in pending) {
            synchronized(ownerLock) { version = Any(); captured = null }
            val operation = entry.operation
            val dispose = entry.dispose
            try {
                withContext(NonCancellable) { dispose() }
            } catch (failure: Throwable) {
                synchronized(ownerLock) { version = Any(); captured = null }
                retain(failure)
                if (failure is Exception && failure !is CancellationException) {
                    try { reportFailure(operation, failure) }
                    catch (reportingFailure: Throwable) { retain(reportingFailure) }
                }
            }
        }
        synchronized(ownerLock) { version = Any(); captured = null }
        val failure = outgoing
        if (failure != null && failure !== primary) throw failure
    }
}

// Atomic, bounded on-disk task records so child/parent linkage survives process death.
internal class SubagentTaskStore(
    private val workDir: File,
    private val publishRecord: (File, File) -> Unit = { temporary, target ->
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    },
) {
    private val dir = PathSecurity.resolveWithin(workDir, ".droide/subagents")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var recovered = false

    private suspend fun <T> taskWriterIo(action: suspend () -> T): T =
        ExportWriterAdmission.withSuspendingWriter(workDir, "Subagent task metadata") {
            mutex.withLock { action() }
        }

    private fun ensureDir(): File = dir.apply { check(isDirectory || mkdirs()) { "Cannot create subagent task directory" } }

    suspend fun create(record: SubagentTaskRecord): SubagentTaskRecord = taskWriterIo withLock@ {
        ensureRecoveredLocked()
        saveLocked(record)
        record
    }

    suspend fun get(id: String): SubagentTaskRecord? = taskWriterIo withLock@ {
        ensureRecoveredLocked()
        readLocked(id)
    }

    suspend fun update(id: String, transform: (SubagentTaskRecord) -> SubagentTaskRecord): SubagentTaskRecord? = taskWriterIo withLock@ {
        ensureRecoveredLocked()
        val current = readLocked(id) ?: return@withLock null
        val transformed = transform(current)
        if (transformed == current) return@withLock current
        val next = transformed.copy(updatedAtMs = System.currentTimeMillis())
        saveLocked(next)
        next
    }

    suspend fun transition(
        id: String, attempt: Int, expectedState: SubagentTaskState,
        transform: (SubagentTaskRecord) -> SubagentTaskRecord,
    ): SubagentTaskTransition? = taskWriterIo withLock@ {
        ensureRecoveredLocked()
        val current = readLocked(id) ?: return@withLock null
        if (current.attempt != attempt || current.state != expectedState) {
            return@withLock SubagentTaskTransition(current, applied = false)
        }
        val next = transform(current).copy(updatedAtMs = System.currentTimeMillis())
        saveLocked(next)
        SubagentTaskTransition(next, applied = true)
    }

    suspend fun cancelAttempt(admitted: SubagentTaskRecord): SubagentTaskTransition? {
        var applied = false
        val record = update(admitted.id) { current ->
            if (current.attempt != admitted.attempt || current.parentSessionId != admitted.parentSessionId ||
                current.childSessionId != admitted.childSessionId || current.state !in setOf(
                    SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING,
                )) current
            else {
                applied = true
                current.copy(
                    state = SubagentTaskState.KILLED,
                    error = "Cancelled by parent agent",
                    delivery = SubagentDeliveryState.DELIVERED,
                    endedAtMs = System.currentTimeMillis(),
                )
            }
        }
        return record?.let { SubagentTaskTransition(it, applied) }
    }

    suspend fun list(parentSessionId: String): List<SubagentTaskRecord> = taskWriterIo withLock@ {
        ensureRecoveredLocked()
        filesLocked().mapNotNull(::readFileLocked)
            .filter { it.parentSessionId == parentSessionId }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_TASKS_PER_PARENT)
    }

    suspend fun pendingDeliveries(parentSessionId: String, excludeTaskId: String? = null): List<SubagentTaskRecord> =
        list(parentSessionId).filter {
            it.id != excludeTaskId && it.background && it.delivery == SubagentDeliveryState.PENDING &&
                it.state in setOf(SubagentTaskState.IDLE, SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED, SubagentTaskState.KILLED)
        }.sortedBy { it.updatedAtMs }

    private fun ensureRecoveredLocked() {
        if (recovered) return
        filesLocked().forEach { file ->
            val record = readFileLocked(file) ?: return@forEach
            if (record.state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)) {
                saveLocked(record.copy(
                    state = SubagentTaskState.INTERRUPTED,
                    error = "Subagent execution was interrupted by process/workspace restart.",
                    delivery = if (record.background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
                    endedAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                ))
            }
        }
        // Failed enumeration/publication must remain retryable. Previously the first failure
        // permanently skipped recovery, leaving old RUNNING rows looking live.
        recovered = true
    }

    private fun readLocked(id: String): SubagentTaskRecord? {
        val safe = PathSecurity.safeLeafName(id)
        return readFileLocked(File(dir, "$safe.json"))
    }

    private fun readFileLocked(file: File): SubagentTaskRecord? {
        if (!file.isFile || file.length() > MAX_RECORD_BYTES) return null
        val encoded = file.readText()
        return try { json.decodeFromString<SubagentTaskRecord>(encoded) }
        catch (_: SerializationException) { null }
    }

    private fun filesLocked(): List<File> = checkNotNull(ensureDir().listFiles()) {
        "Cannot read subagent task directory"
    }.asSequence()
        .filter { it.isFile && it.name.startsWith("task-") && it.extension == "json" && it.length() <= MAX_RECORD_BYTES }
        .sortedByDescending { it.lastModified() }
        .take(MAX_TOTAL_TASK_RECORDS)
        .toList()

    private fun saveLocked(record: SubagentTaskRecord) {
        val target = File(ensureDir(), "${PathSecurity.safeLeafName(record.id)}.json")
        val tmp = File(dir, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            val encoded = json.encodeToString(SubagentTaskRecord.serializer(), record)
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) { "Subagent task record too large" }
            tmp.writeText(encoded)
            publishRecord(tmp, target)
        } finally {
            // Permission loss while cleaning a temporary file must not replace the
            // original publication failure (especially CancellationException).
            try { if (tmp.exists()) tmp.delete() } catch (_: SecurityException) { }
        }
    }

    companion object {
        private const val MAX_RECORD_BYTES = 64 * 1024L
        private const val MAX_TOTAL_TASK_RECORDS = 256
        private const val MAX_TASKS_PER_PARENT = 64
    }
}

// Fresh tasks create durable child sessions.


class SubagentManager(
    private val files: FileRepository,
    private val terminal: ITerminalSession,
    private val git: GitManager,
    private val approvals: ApprovalManager,
    private val lsp: LspManager,
    private val questions: QuestionManager,
    private val parentPermissions: PermissionEngine,
    private val workDir: File,
    private val depth: Int,
    private val maxDepth: Int,
    private val pluginSource: AgentPluginSource = AgentPluginSource.EMPTY,
    private val documentAuthority: WorkspaceDocumentAuthority? = null,
    runtimeContext: CoroutineContext = Dispatchers.Default,
    private val providerConfiguration: ProviderRegistry.ConfiguredSnapshot? = null,
    private val codeStyleDefaults: suspend () -> CodeStyleDefaults = { CodeStyleDefaults() },
    private val onBackgroundCompletion: suspend (SubagentCompletion) -> Unit,
) {
    private val store = SubagentTaskStore(workDir)
    private val workspaceManager = WorktreeManager(workDir)
    private val profiles = AgentProfileManager(workDir, pluginSource)
    private val scope = CoroutineScope(runtimeContext + SupervisorJob(runtimeContext[Job]))
    private val slots = Semaphore(MAX_CONCURRENT_SUBAGENTS)
    
    private val sharedMutationSlot = Semaphore(1)
    private val jobs = SubagentJobOwners()
    private val liveChildren = ConcurrentHashMap<String, AgentService>()
    private val liveConfigs = ConcurrentHashMap<String, AgentConfig>()
    private val liveActivity = ConcurrentHashMap<String, SubagentLiveActivity>()
    private val dashboardLock = Any()
    private val exitFence = ExitOperationFence(dashboardLock)
    private val exitMutex = Mutex()
    private val failedChildCheckpoints = ConcurrentHashMap<String, AgentService>()
    private val unsettledRecords = ConcurrentHashMap<String, SubagentTaskRecord>()
    // A failed write must still be visible, even if another child refreshes the dashboard.
    // These records are retried on the next refresh and never presented as durable.
    private val unsavedTerminalRecords = LinkedHashMap<String, SubagentTaskRecord>()
    private var closed = false
    @Volatile private var boundParentSessionId: String? = null
    private val _dashboard = MutableStateFlow(SubagentDashboardState())
    val dashboard: StateFlow<SubagentDashboardState> = _dashboard.asStateFlow()

    // Switches the monitoring surface to one parent session and restores durable task rows after restart.
    fun bindParent(parentSessionId: String?) {
        synchronized(dashboardLock) {
            if (closed || !exitFence.isOpen) return
            boundParentSessionId = parentSessionId
            if (_dashboard.value.parentSessionId != parentSessionId.orEmpty()) {
                _dashboard.value = SubagentDashboardState(parentSessionId = parentSessionId.orEmpty())
            }
        }
        if (parentSessionId != null) launchOwned(parentSessionId, "Could not load subagent tasks") {
            refreshDashboard(parentSessionId)
        }
    }

    fun profileSummaries(): List<AgentProfileSummary> = profiles.subagentSummaries()
    fun profileMode(name: String): AgentMode? = profiles.get(name)?.takeIf { it.subagent }?.mode

    suspend fun run(
        parentSessionId: String,
        description: String,
        prompt: String,
        profileName: String,
        taskId: String?,
        background: Boolean,
        workspace: String? = null,
        config: AgentConfig,
        onProgress: (AgentToolProgress) -> Unit = {},
    ): String {
        // Admit before provisioning/session creation/QUEUED publication. Background
        // work takes ownership of this same lease through delivery and job completion.
        val exportWriter = ExportWriterAdmission.beginWriter(files.root, "Subagent admission/execution/delivery")
        var writerTransferred = false
        try {
        val registration = exitFence.track(currentCoroutineContext().job)
        try {
        check(synchronized(dashboardLock) { unsavedTerminalRecords.isEmpty() } && failedChildCheckpoints.isEmpty()) { "Save failed subagent records before launching more work" }
        require(depth < maxDepth) { "Subagent depth limit reached ($maxDepth)" }
        require(description.isNotBlank() && description.length <= 160) { "Subagent description must be 1..160 chars" }
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) { "Subagent prompt must be 1..$MAX_PROMPT_CHARS chars" }
        val profile = profiles.get(profileName)?.takeIf { it.subagent }
            ?: return "ERROR: unknown subagent_type '$profileName'. Available: ${profiles.subagents().joinToString(", ") { it.name }}"
        val effectiveConfig = profile.applyModel(config)
        runCatching { ProviderRuntimeGuard.requireCertifiedSelection(effectiveConfig.providerId, effectiveConfig.baseUrl, effectiveConfig.model) }
            .getOrElse { return "ERROR: subagent model selection is not live-certified: ${it.message}" }

        val record = if (taskId.isNullOrBlank()) {
            val id = "task-${UUID.randomUUID()}"
            val requestedMode = resolveWorkspaceMode(profile, workspace)
            val workspaceInfo = if (requestedMode == SubagentWorkspaceMode.ISOLATED) {
                runCatching {
                    workspaceManager.provision(
                        name = id,
                        branch = "droide-agent/${id.removePrefix("task-").take(12)}",
                    )
                }.getOrElse { e ->
                    return "ERROR: isolated subagent workspace could not be created: ${e.message}"
                }
            } else null
            val childRoot = workspaceInfo?.directory?.let(::File) ?: workDir
            val sessionManager = SessionManager(childRoot)
            val child = try {
                sessionManager.create(title = "${profile.name}: ${description.take(80)}", parentId = parentSessionId)
            } catch (t: Throwable) {
                if (workspaceInfo != null) runCatching { workspaceManager.remove(workspaceInfo.name, force = true) }
                throw t
            }
            try {
                store.create(SubagentTaskRecord(
                    id = id,
                    parentSessionId = parentSessionId,
                    childSessionId = child.id,
                    profile = profile.name,
                    profileRevision = profile.revision,
                    description = description,
                    background = background,
                    state = SubagentTaskState.QUEUED,
                    delivery = if (background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
                    workspaceMode = requestedMode,
                    workspaceName = workspaceInfo?.name.orEmpty(),
                    workspacePath = workspaceInfo?.directory.orEmpty(),
                    workspaceBranch = workspaceInfo?.branch.orEmpty(),
                    workspaceBaseCommit = workspaceInfo?.baseCommit.orEmpty(),
                    workspaceParentBranch = workspaceInfo?.parentBranch.orEmpty(),
                    workspaceParentRepositoryIdentity = workspaceInfo?.parentRepositoryIdentity.orEmpty(),
                    integration = if (workspaceInfo != null) SubagentIntegrationState.PENDING else SubagentIntegrationState.NONE,
                    providerId = effectiveConfig.providerId,
                    modelId = effectiveConfig.model,
                    lastPrompt = prompt.take(MAX_PERSISTED_PROMPT_CHARS),
                ))
            } catch (t: Throwable) {
                try { sessionManager.delete(child.id) } catch (_: Throwable) { }
                if (workspaceInfo != null) runCatching { workspaceManager.remove(workspaceInfo.name, force = true) }
                throw t
            }
        } else {
            val existing = store.get(taskId) ?: return "ERROR: subagent task not found: $taskId"
            if (existing.parentSessionId != parentSessionId) return "ERROR: task $taskId does not belong to this parent session"
            if (existing.profile != profile.name) return "ERROR: task $taskId uses subagent_type '${existing.profile}', not '${profile.name}'"
            if (!profile.acceptsStoredRevision(existing.profileRevision)) {
                return if (existing.profileRevision.isBlank())
                    "ERROR: task $taskId predates verifiable custom Agent profile identity; start a new task."
                else "ERROR: Agent profile '${profile.name}' changed since task $taskId was created; start a new task instead of resuming under different instructions/capabilities."
            }
            if (existing.state == SubagentTaskState.KILLED) return "ERROR: task $taskId was killed and cannot be resumed; start a new task."
            if (existing.integration == SubagentIntegrationState.MERGED || existing.integration == SubagentIntegrationState.DISCARDED) {
                return "ERROR: task $taskId workspace is ${existing.integration.name.lowercase()} and cannot be resumed; start a new task."
            }
            if (existing.state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.RUNNING, SubagentTaskState.STARTING)) {
                return "ERROR: task $taskId is already running; do not duplicate work."
            }
            if (jobs[taskId]?.job?.isCompleted == false) {
                return "ERROR: task $taskId is still finishing its previous execution; wait before resuming."
            }
            if (workspace != null && workspace.isNotBlank() && resolveWorkspaceMode(profile, workspace) != existing.workspaceMode) {
                return "ERROR: task $taskId workspace mode is fixed to ${existing.workspaceMode.name.lowercase()} for its lifetime"
            }
            val childRoot = workspaceRoot(existing)
                ?: return "ERROR: isolated workspace missing for task $taskId: ${existing.workspaceName}"
            val childSession = SessionManager(childRoot).get(existing.childSessionId)
                ?: return "ERROR: child session missing for task $taskId: ${existing.childSessionId}"
            if (childSession.parentId != parentSessionId) {
                return "ERROR: child session ownership mismatch for task $taskId"
            }
            val resumed = store.transition(taskId, existing.attempt, existing.state) { it.copy(
                background = background,
                state = SubagentTaskState.QUEUED,
                delivery = if (background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
                attempt = it.attempt + 1,
                profileRevision = profile.revision,
                providerId = effectiveConfig.providerId,
                modelId = effectiveConfig.model,
                startedAtMs = 0L,
                endedAtMs = 0L,
                lastPrompt = prompt.take(MAX_PERSISTED_PROMPT_CHARS),
                result = "",
                error = "",
            ) } ?: return "ERROR: subagent task disappeared: $taskId"
            if (!resumed.applied) return "ERROR: task $taskId changed while resuming; inspect its current status."
            resumed.record
        }

        unsettledRecords[record.id] = record
        refreshDashboard(record.parentSessionId)

        if (background) {
            val job = launchOwned(record.parentSessionId,
                "Background task ${record.id} could not notify its parent", start = CoroutineStart.LAZY) {
                val terminalRecord = executeScheduled(record, prompt, profile, effectiveConfig, onProgress)
                if (!canDeliverCompletion(record, terminalRecord)) return@launchOwned
                val activeSiblings = store.list(terminalRecord.parentSessionId).count {
                    it.id != terminalRecord.id && it.state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)
                }
                val current = store.get(record.id) ?: return@launchOwned
                currentCoroutineContext().ensureActive()
                if (exitFence.isOpen && canDeliverCompletion(record, current) && current == terminalRecord) {
                    onBackgroundCompletion(SubagentCompletion(current, effectiveConfig, activeSiblings))
                }
            }
            val owned = SubagentOwnedJob(record.attempt, job)
            jobs[record.id] = owned
            // Completion also runs when cancellation wins before LAZY dispatch.
            // No writer may enter the publication-to-dispatch or completion-callback gap.
            job.invokeOnCompletion {
                try { jobs.remove(record.id, owned) }
                finally { exportWriter.close() }
            }
            writerTransferred = true
            job.start()
            return "SUBAGENT_STARTED task_id=${record.id} child_session_id=${record.childSessionId} profile=${profile.name} state=queued " +
                "workspace=${record.workspaceMode.name.lowercase()}${if (record.workspaceName.isNotBlank()) " workspace_id=${record.workspaceName}" else ""}\n" +
                "The task runs independently. Do not poll or duplicate its work; continue non-overlapping work. Its result will be delivered automatically."
        }

        return coroutineScope {
            // Foreground tasks own a child Job too: their terminal row can become
            // visible before disposal finishes, so resume/discard must still wait.
            val owned = SubagentOwnedJob(record.attempt, checkNotNull(currentCoroutineContext()[Job]))
            jobs[record.id] = owned
            try {
                renderResult(executeScheduled(record, prompt, profile, effectiveConfig, onProgress))
            } finally {
                jobs.remove(record.id, owned)
            }
        }

        } finally { registration.close() }
        } finally { if (!writerTransferred) exportWriter.close() }
    }

    private suspend fun executeScheduled(
        initial: SubagentTaskRecord, prompt: String, profile: AgentProfile, config: AgentConfig, onProgress: (AgentToolProgress) -> Unit,
    ): SubagentTaskRecord = ExportWriterAdmission.withSuspendingWriter(files.root, "Subagent execution/finalization") {
        // Nested inside run's transferred lease: capture cannot enter between
        // durable QUEUED publication and this execution/finalization span.
        executeScheduledAdmitted(initial, prompt, profile, config, onProgress)
    }

    private suspend fun executeScheduledAdmitted(
        initial: SubagentTaskRecord, prompt: String, profile: AgentProfile, config: AgentConfig, onProgress: (AgentToolProgress) -> Unit,
    ): SubagentTaskRecord = try {
        exitFence.requireOpen()
        slots.withPermit {
            exitFence.requireOpen()
            val transition = store.transition(initial.id, initial.attempt, SubagentTaskState.QUEUED) {
                it.copy(state = SubagentTaskState.STARTING)
            }
                ?: error("Subagent task disappeared: ${initial.id}")
            val record = transition.record
            refreshDashboard(record.parentSessionId)
            if (!transition.applied) return@withPermit record
            if (profile.mode == AgentMode.BUILD && record.workspaceMode == SubagentWorkspaceMode.SHARED) {
                sharedMutationSlot.withPermit { execute(record, prompt, profile, config, onProgress) }
            } else {
                execute(record, prompt, profile, config, onProgress)
            }
        }
    } catch (cancel: CancellationException) {
        // A queued task can be cancelled before execute() is entered. Finalize it here too,
        // without replacing the original cancellation with a storage/cleanup exception.
        try {
            withContext(NonCancellable) {
                withTimeout(FINALIZATION_TIMEOUT_MS) {
                    recordTerminalFailure(initial, SubagentTaskState.KILLED, cancel.message ?: "Subagent cancelled")
                }
            }
        } catch (failure: Exception) {
            if (failure !== cancel) cancel.addSuppressed(failure)
            reportDashboardFailure(initial.parentSessionId, "Could not save cancellation for ${initial.id}", failure)
        }
        throw cancel
    } catch (failure: Exception) {
        val failed = recordTerminalFailure(initial, SubagentTaskState.ERROR, failureDetail(failure))
        reportProgress(initial.parentSessionId, onProgress,
            AgentToolProgress(title = "${profile.name} subagent", detail = "Failed: ${failed.error.take(160)}"))
        failed
    }

    suspend fun control(parentSessionId: String, action: String, taskId: String?, message: String? = null): String {
        val exportWriter = if (action in setOf("cancel", "merge", "discard", "message"))
            ExportWriterAdmission.beginWriter(files.root, "Subagent control $action") else null
        try {
        val registration = exitFence.track(currentCoroutineContext().job)
        try {
        if (taskId == DASHBOARD_FAILURE_ID) return synchronized(dashboardLock) {
            if (_dashboard.value.parentSessionId == parentSessionId) {
                "ERROR: ${_dashboard.value.error.ifBlank { "Subagent dashboard is available again." }}"
            } else "ERROR: dashboard notice does not belong to this parent session"
        }
        return when (action) {
        "list" -> store.list(parentSessionId).joinToString("\n") { summary(it) }.ifBlank { "No subagent tasks." }
        "status" -> {
            val id = taskId ?: return "ERROR: task_control status requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            val workspaceStatus = if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED && record.workspaceName.isNotBlank() &&
                record.integration !in setOf(SubagentIntegrationState.MERGED, SubagentIntegrationState.DISCARDED)
            ) "\n${workspaceManager.status(record.workspaceName)}" else ""
            summary(record) + workspaceStatus +
                if (record.result.isNotBlank()) "\nresult=${SubagentTextProjection.bounded(record.result, 4000)}"
                else if (record.error.isNotBlank()) "\nerror=${SubagentTextProjection.bounded(record.error, 2000)}" else ""
        }
        "review" -> {
            val id = taskId ?: return "ERROR: task_control review requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (record.workspaceMode != SubagentWorkspaceMode.ISOLATED || record.workspaceName.isBlank()) {
                return "Task $id uses shared workspace; review the parent Git diff directly."
            }
            "SUBAGENT_REVIEW task_id=$id\n${workspaceManager.reviewChanges(record.workspaceName)}"
        }
        "transcript" -> {
            val id = taskId ?: return "ERROR: task_control transcript requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            renderTranscript(record)
        }
        "message" -> {
            val id = taskId ?: return "ERROR: task_control message requires task_id"
            val text = message?.trim().orEmpty()
            if (text.isBlank()) return "ERROR: task_control message requires non-empty message"
            if (text.length > MAX_STEER_MESSAGE_CHARS) return "ERROR: subagent message exceeds $MAX_STEER_MESSAGE_CHARS characters"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (!record.background || record.state != SubagentTaskState.RUNNING) {
                return "ERROR: direct steer is available only for an actively running background child. ${summary(record)}"
            }
            val child = liveChildren[id] ?: return "ERROR: task $id is transitioning; steer again when it reports Running"
            val config = liveConfigs[id] ?: return "ERROR: task $id runtime configuration is unavailable"
            if (!child.steerActive(text, config, record.childSessionId)) {
                return "ERROR: task $id reached a provider-turn boundary and is no longer accepting live steer; resume it with task(...) instead"
            }
            liveActivity[id] = SubagentLiveActivity("Steer admitted · ${text.take(96)}")
            publishLive(id, parentSessionId, liveActivity.getValue(id))
            "SUBAGENT_STEER_ADMITTED task_id=$id child_session_id=${record.childSessionId}"
        }
        "cancel" -> {
            val id = taskId ?: return "ERROR: task_control cancel requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            val owned = jobs[id]
            if (owned == null || owned.attempt != record.attempt || owned.job.isCompleted) {
                return "Task $id is not running in the inspected attempt. ${summary(record)}"
            }
            val stopped = try { store.cancelAttempt(record) }
            finally { owned.job.cancel(CancellationException("Cancelled by parent agent")) }
            if (stopped?.applied == true) liveActivity.remove(id)
            if (stopped != null) refreshDashboard(stopped.record.parentSessionId)
            when {
                stopped == null -> "ERROR: cancellation requested for task $id, but its task record disappeared."
                stopped.applied -> "SUBAGENT_CANCELLED task_id=$id"
                stopped.record.attempt != record.attempt || stopped.record.parentSessionId != record.parentSessionId ||
                    stopped.record.childSessionId != record.childSessionId ->
                    "SUBAGENT_CANCEL_REQUESTED task_id=$id attempt=${record.attempt}; task ownership changed and the current record was not changed."
                else -> "SUBAGENT_TERMINAL_PRESERVED task_id=$id state=${stopped.record.state.name.lowercase()} " +
                    "delivery=${stopped.record.delivery.name.lowercase()}\nCancellation requested for the remaining execution; the committed task record was preserved."
            }
        }
        "merge" -> {
            val id = taskId ?: return "ERROR: task_control merge requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (record.workspaceMode != SubagentWorkspaceMode.ISOLATED) return "ERROR: task $id uses shared workspace; there is nothing isolated to merge"
            if (jobs[id]?.job?.isCompleted == false) return "ERROR: wait for task $id execution and cleanup before merging its workspace"
            if (record.state != SubagentTaskState.IDLE) return "ERROR: task $id must complete successfully before merge (state=${record.state.name.lowercase()})"
            if (record.integration == SubagentIntegrationState.MERGED) return "Task $id is already merged."
            if (record.integration == SubagentIntegrationState.DISCARDED) return "ERROR: task $id workspace was discarded"
            val merged = workspaceManager.merge(
                name = record.workspaceName,
                expectedBaseCommit = record.workspaceBaseCommit,
                expectedParentBranch = record.workspaceParentBranch,
                expectedParentRepositoryIdentity = record.workspaceParentRepositoryIdentity,
                expectedChildBranch = record.workspaceBranch,
                commitMessage = "Droide subagent ${record.description.take(120)}",
            )
            if (merged.merged) {
                val cleanup = runCatching { workspaceManager.remove(record.workspaceName, force = true) }.getOrNull()
                val mergedRecord = store.update(id) { it.copy(integration = SubagentIntegrationState.MERGED) }
                if (mergedRecord != null) refreshDashboard(mergedRecord.parentSessionId)
                "SUBAGENT_MERGED task_id=$id no_changes=${merged.noChanges}\n${merged.message}${cleanup?.let { "\n$it" }.orEmpty()}"
            } else {
                val conflicted = store.update(id) { it.copy(integration = SubagentIntegrationState.CONFLICT) }
                if (conflicted != null) refreshDashboard(conflicted.parentSessionId)
                "SUBAGENT_MERGE_BLOCKED task_id=$id\n${merged.message}"
            }
        }
        "discard" -> {
            val id = taskId ?: return "ERROR: task_control discard requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (record.workspaceMode != SubagentWorkspaceMode.ISOLATED) return "ERROR: task $id uses shared workspace; nothing to discard"
            if (jobs[id]?.job?.isCompleted == false) return "ERROR: wait for task $id execution and cleanup before discarding its workspace"
            if (record.integration == SubagentIntegrationState.MERGED) return "ERROR: task $id was already merged"
            val removed = workspaceManager.remove(record.workspaceName, force = true)
            val discarded = store.update(id) { it.copy(integration = SubagentIntegrationState.DISCARDED) }
            if (discarded != null) refreshDashboard(discarded.parentSessionId)
            "SUBAGENT_DISCARDED task_id=$id\n$removed"
        }
        else -> "ERROR: unsupported task_control action: $action"
        }

        } finally { registration.close() }
        } finally { exportWriter?.close() }
    }

    suspend fun pendingDeliveries(parentSessionId: String, excludeTaskId: String? = null): List<SubagentTaskRecord> =
        store.pendingDeliveries(parentSessionId, excludeTaskId)

    suspend fun markDelivered(receipt: SubagentDeliveryReceipt) {
        require(receipt.isValid()) { "Invalid subagent completion identity" }
        store.update(receipt.taskId) { current ->
            if (receipt.matches(current) && current.background && current.delivery == SubagentDeliveryState.PENDING &&
                current.state in setOf(SubagentTaskState.IDLE, SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED, SubagentTaskState.KILLED))
                current.copy(delivery = SubagentDeliveryState.DELIVERED)
            else current
        }
    }

    internal suspend fun observeChildOwnership(): ChildOwnershipObservation = jobs.observe()

    internal fun beginExit() {
        val owned = synchronized(dashboardLock) { exitFence.beginExit() + jobs.values.map { it.job } }
        owned.distinct().forEach { it.cancel(CancellationException("Application Exit")) }
        scope.cancel("Application Exit")
    }

    /** Stop exact owned jobs, then retry only durable records and child checkpoints. */
    suspend fun closeAndJoin() = exitMutex.withLock {
        val caller = currentCoroutineContext().job
        val owned = synchronized(dashboardLock) {
            (exitFence.beginExit() + jobs.values.map { it.job }).distinct().filter { it !== caller }
        }
        owned.forEach { it.cancel(CancellationException("Application Exit")) }
        scope.cancel("Application Exit")
        owned.joinAll()
        scope.coroutineContext.job.join()
        val failures = mutableListOf<Throwable>()
        for ((id, child) in (liveChildren.toMap() + failedChildCheckpoints.toMap())) {
            try {
                child.stopAndCheckpointForExit()
                child.shutdown()
                failedChildCheckpoints.remove(id, child)
                liveChildren.remove(id, child)
                liveConfigs.remove(id)
            } catch (failure: Exception) {
                failedChildCheckpoints[id] = child
                failures += failure
            }
        }
        // A LAZY child cancelled before its body runs cannot execute its own finally.
        for (record in unsettledRecords.values.toList()) {
            try { recordTerminalFailure(record, SubagentTaskState.KILLED, "Application Exit") }
            catch (failure: Exception) { failures += failure }
        }
        val pending = synchronized(dashboardLock) { unsavedTerminalRecords.values.toList() }
        for (record in pending) {
            try {
                store.update(record.id) { current ->
                    if (current.attempt == record.attempt && current.state in ACTIVE_TASK_STATES) record else current
                } ?: error("Subagent task disappeared: ${record.id}")
                synchronized(dashboardLock) { unsavedTerminalRecords.remove(record.id, record) }
                unsettledRecords.remove(record.id)
            } catch (failure: Exception) { failures += failure }
        }
        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("Subagent Exit checkpoint failed; pending records were retained", failures.first())
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    fun close() {
        synchronized(dashboardLock) {
            closed = true
            boundParentSessionId = null
            // Failed records are the only retryable copy; Exit must checkpoint before disposal.
            _dashboard.value = SubagentDashboardState()
        }
        jobs.values.forEach { it.job.cancel(CancellationException("Workspace closed")) }
        jobs.clear()
        liveChildren.clear()
        liveConfigs.clear()
        liveActivity.clear()
        scope.cancel("Workspace closed")
    }

    private suspend fun execute(
        starting: SubagentTaskRecord,
        prompt: String,
        profile: AgentProfile,
        config: AgentConfig,
        onProgress: (AgentToolProgress) -> Unit,
    ): SubagentTaskRecord {
        exitFence.requireOpen()
        val taskId = starting.id
        val transition = store.transition(taskId, starting.attempt, SubagentTaskState.STARTING) { it.copy(
            state = SubagentTaskState.RUNNING,
            startedAtMs = if (it.startedAtMs > 0L) it.startedAtMs else System.currentTimeMillis(),
            endedAtMs = 0L,
        ) } ?: error("Subagent task disappeared: $taskId")
        val running = transition.record
        refreshDashboard(running.parentSessionId)
        if (!transition.applied) return running
        onProgress(AgentToolProgress(
            title = "${profile.name} subagent",
            detail = "Running · ${starting.childSessionId} · ${starting.workspaceMode.name.lowercase()}",
        ))
        currentCoroutineContext().ensureActive()

        val childHardRules = if (depth + 1 >= maxDepth)
            listOf(PermRule("subagent", "*", PermEffect.DENY)) else emptyList()
        val childRoot = workspaceRoot(starting)
            ?: throw IllegalStateException("Isolated workspace missing for task $taskId: ${starting.workspaceName}")
        val childPermissions = parentPermissions.fork(childHardRules, profile.tools)
        val isolated = starting.workspaceMode == SubagentWorkspaceMode.ISOLATED
        val childFiles = if (isolated) FileRepository(childRoot) else files
        if (isolated) files.copyRecoveryHome?.let(childFiles::configureCopyRecoveryHome)
        val resources = SubagentOwnedResources { operation, failure ->
            reportDashboardFailure(starting.parentSessionId, operation, failure)
        }
        val resourceBinding = jobs.attachResources(taskId, starting.attempt, currentCoroutineContext().job, childRoot.absolutePath, resources)
        return try { resources.use {
            val childTerminal: ITerminalSession = if (isolated) {
                TerminalSession(childRoot, scope).also { owned ->
                    resources.registerTerminal("Could not close child terminal", owned, childRoot.absolutePath) { owned.destroy() }
                }
            } else terminal
            val childGit = if (isolated) git.forWorkDir(childRoot) else git
            val childLsp: LspManager = if (isolated) {
                ProfessionalLspManager(scope, childFiles, CliLspManager(childTerminal, childFiles)).also { owned ->
                    resources.register("Could not close child language servers") { owned.close() }
                }
            } else lsp
            val workspaceOverlay = if (isolated) """
                You are running inside an isolated Droide child workspace.
                Workspace root: ${starting.workspacePath}
                Branch: ${starting.workspaceBranch}
                Base commit: ${starting.workspaceBaseCommit}
                Make and verify changes only in this isolated workspace. Do not attempt to merge into or edit the parent workspace yourself.
                The parent agent will inspect and integrate your branch through task_control after you finish.
            """.trimIndent() else ""
            if (isolated && pluginSource === AgentPluginRuntime) {
                resources.register("Could not clean up child plugins") { AgentPluginRuntime.cleanupWorkspace(childRoot) }
            }
            val child = AgentService(
                files = childFiles,
                terminal = childTerminal,
                git = childGit,
                approvals = approvals,
                lsp = childLsp,
                questions = questions,
                perms = childPermissions,
                workDir = childRoot,
                subagentDepth = depth + 1,
                maxSubagentDepth = maxDepth,
                systemOverlay = listOf(profile.systemInstructions, workspaceOverlay).filter { it.isNotBlank() }.joinToString("\n\n"),
                executionIdentity = AgentExecutionIdentity.subagent(
                    agentType = profile.name,
                    taskId = taskId,
                    parentSessionId = starting.parentSessionId,
                    depth = depth + 1,
                ),
                agentPluginSource = pluginSource,
                documentAuthority = if (isolated) null else documentAuthority,
                providerConfiguration = providerConfiguration,
                codeStyleDefaults = codeStyleDefaults,
            ).also { owned ->
                resources.register("Could not shut down child $taskId") {
                    if (exitFence.isExiting || !ApplicationWorkAdmission.isOpen || owned.persistenceError.value != null) {
                        try { owned.stopAndCheckpointForExit() }
                        catch (failure: Exception) {
                            failedChildCheckpoints[taskId] = owned
                            throw failure
                        }
                    }
                    if (liveChildren.remove(taskId, owned)) liveConfigs.remove(taskId, config)
                    owned.shutdown()
                }
            }
            exitFence.requireOpen()
            liveChildren[taskId] = child
            liveConfigs[taskId] = config
            val session = child.sessions.get(starting.childSessionId)
                ?: throw IllegalStateException("Child session missing: ${starting.childSessionId}")
            child.loadSession(session)
            child.setMode(profile.mode)
            coroutineScope {
                val monitor = launch {
                    child.streamState.collect { state ->
                        val part = state.parts.lastOrNull { it.state == AgentStreamPartState.RUNNING || it.state == AgentStreamPartState.PENDING }
                            ?: state.parts.lastOrNull()
                        val activity = when (part?.kind) {
                            AgentStreamPartKind.TOOL -> SubagentLiveActivity(
                                detail = part.detail.ifBlank { "Using ${part.title.ifBlank { part.toolName }}" },
                                tool = part.toolName.ifBlank { part.title },
                                step = part.step,
                            )
                            AgentStreamPartKind.TEXT -> SubagentLiveActivity("Responding", step = part.step)
                            AgentStreamPartKind.STEP -> SubagentLiveActivity(part.detail.ifBlank { part.title.ifBlank { "Model step ${part.step}" } }, step = part.step)
                            AgentStreamPartKind.RETRY -> SubagentLiveActivity(part.detail.ifBlank { "Retrying" }, step = part.step)
                            AgentStreamPartKind.COMPACTION -> SubagentLiveActivity(part.detail.ifBlank { "Compacting context" }, step = part.step)
                            else -> SubagentLiveActivity(if (state.status == AgentStreamState.Status.BUSY) "Working" else "")
                        }
                        liveActivity[taskId] = activity
                        publishLive(taskId, running.parentSessionId, activity)
                    }
                }
                try { child.chat(prompt, config, maxTurns = SUBAGENT_MAX_TURNS) } finally { monitor.cancel() }
            }
            val lastPlain = child.messages.value.asReversed().firstOrNull {
                it.role == "assistant" && it.streamPart == null && it.content.isNotBlank()
            }
            if (lastPlain?.content == "Stopped by user.") throw CancellationException("Subagent stopped")
            if (lastPlain?.content?.startsWith("Error:") == true) {
                throw IllegalStateException(lastPlain.content.removePrefix("Error:").trim().ifBlank { "Subagent failed" })
            }
            val result = lastPlain?.content?.let { SubagentTextProjection.bounded(it, MAX_RESULT_CHARS) }.orEmpty()
                .ifBlank { "Subagent completed without a textual final report." }
            currentCoroutineContext().ensureActive()
            val completion = store.transition(taskId, starting.attempt, SubagentTaskState.RUNNING) { it.copy(
                state = SubagentTaskState.IDLE,
                result = result,
                endedAtMs = System.currentTimeMillis(),
                error = "",
                delivery = if (it.background) SubagentDeliveryState.PENDING else SubagentDeliveryState.DELIVERED,
            ) } ?: error("Subagent task disappeared after completion")
            val completed = completion.record
            unsettledRecords.remove(taskId)
            if (completed.attempt == starting.attempt) liveActivity.remove(taskId)
            refreshDashboard(completed.parentSessionId)
            if (completion.applied) onProgress(AgentToolProgress(
                title = "${profile.name} subagent",
                detail = "Completed · ${starting.childSessionId}${if (starting.workspaceMode == SubagentWorkspaceMode.ISOLATED) " · awaiting merge" else ""}",
            ))
            completed
        } } finally { resourceBinding.close() }
    }

    // SupervisorJob isolates siblings; it does not consume an unhandled launch exception.
    // Keep every manager-owned root launch behind this boundary. Reporting is memory-only
    // so an unavailable task directory cannot make the error handler fail recursively.
    private fun launchOwned(
        parentSessionId: String, operation: String, start: CoroutineStart = CoroutineStart.DEFAULT,
        action: suspend () -> Unit,
    ): Job = scope.launch(start = start) {
        try {
            exitFence.requireOpen()
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            reportDashboardFailure(parentSessionId, operation, failure)
        }
    }

    private fun failureDetail(failure: Exception): String =
        (failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName).take(MAX_ERROR_CHARS)

    private fun reportDashboardFailure(parentSessionId: String, operation: String, failure: Exception) {
        val detail = "$operation: ${failureDetail(failure)}".take(MAX_ERROR_CHARS)
        synchronized(dashboardLock) {
            if (closed || boundParentSessionId != parentSessionId) return
            val current = _dashboard.value
            val unsaved = unsavedTerminalRecords.values.filter { it.parentSessionId == parentSessionId }
            val taskViews = current.tasks.filterNot { task ->
                task.id == DASHBOARD_FAILURE_ID || unsaved.any { it.id == task.id }
            } + unsaved.map { toView(it, null, 0) }
            val notice = SubagentTaskRecord(
                id = DASHBOARD_FAILURE_ID, parentSessionId = parentSessionId, childSessionId = "",
                profile = "dashboard", description = "Subagent task storage / delivery needs attention",
                background = false, state = SubagentTaskState.ERROR, delivery = SubagentDeliveryState.NONE,
                error = detail, endedAtMs = System.currentTimeMillis(),
            )
            _dashboard.value = current.copy(
                error = detail,
                tasks = taskViews + toView(notice, null, 0),
            )
        }
    }

    private suspend fun recordTerminalFailure(
        initial: SubagentTaskRecord, state: SubagentTaskState, detail: String,
    ): SubagentTaskRecord {
        val fallback = initial.copy(
            state = state, error = detail.take(MAX_ERROR_CHARS),
            delivery = if (initial.background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
            endedAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis(),
        )
        val terminal = try {
            store.update(initial.id) { current ->
                // A late progress/cleanup failure must not erase a committed result or
                // overwrite an explicit parent's KILLED/DELIVERED cancellation record.
                if (current.state !in ACTIVE_TASK_STATES || current.attempt != initial.attempt) current
                else fallback.copy(startedAtMs = current.startedAtMs)
            }?.also { unsettledRecords.remove(initial.id) } ?: error("Subagent task disappeared: ${initial.id}")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (storageFailure: Exception) {
            val unsaved = fallback.copy(error = (
                "${fallback.error}\nTask status could not be saved during failure cleanup: ${failureDetail(storageFailure)}"
            ).take(MAX_ERROR_CHARS))
            synchronized(dashboardLock) {
                if (!closed) {
                    unsavedTerminalRecords[initial.id] = unsaved
                    // Never evict the sole copy of a failed terminal receipt.
                    // Further task admissions are fenced while storage needs repair.
                }
            }
            reportDashboardFailure(initial.parentSessionId, "Could not save status for ${initial.id}", storageFailure)
            unsaved
        }
        if (terminal.attempt == initial.attempt) liveActivity.remove(initial.id)
        synchronized(dashboardLock) {
            if (!closed && boundParentSessionId == initial.parentSessionId) {
                val current = _dashboard.value
                _dashboard.value = current.copy(tasks =
                    current.tasks.filterNot { it.id == initial.id } + toView(terminal, null, 0))
            }
        }
        return terminal
    }

    private fun reportProgress(parentSessionId: String, callback: (AgentToolProgress) -> Unit, progress: AgentToolProgress) {
        try { callback(progress) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { reportDashboardFailure(parentSessionId, "Could not report child progress", failure) }
    }

    private suspend fun refreshDashboard(parentSessionId: String) {
        if (boundParentSessionId != parentSessionId) return
        try {
            val unsaved = synchronized(dashboardLock) {
                unsavedTerminalRecords.values.filter { it.parentSessionId == parentSessionId }
            }
            for (record in unsaved) {
                // Retry only the failed status write, never rerun a child's tools or callback.
                store.update(record.id) { current ->
                    if (current.attempt == record.attempt && current.state in ACTIVE_TASK_STATES) record
                    else current
                } ?: error("Subagent task disappeared: ${record.id}")
                synchronized(dashboardLock) { unsavedTerminalRecords.remove(record.id, record) }
            }
            val records = store.list(parentSessionId)
            val queued = records.filter { it.state == SubagentTaskState.QUEUED }.sortedBy { it.createdAtMs }
            val queueIndex = queued.mapIndexed { index, record -> record.id to index + 1 }.toMap()
            synchronized(dashboardLock) {
                if (closed || boundParentSessionId != parentSessionId) return
                val retained = unsavedTerminalRecords.values.filter { it.parentSessionId == parentSessionId }
                val visible = records.filterNot { record -> retained.any { it.id == record.id } } + retained
                _dashboard.value = SubagentDashboardState(
                    parentSessionId = parentSessionId,
                    tasks = visible.map { toView(it, liveActivity[it.id], queueIndex[it.id] ?: 0) }
                        .sortedWith(compareBy<SubagentTaskView>({ if (it.active) 0 else 1 }, { it.createdAtMs })),
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            reportDashboardFailure(parentSessionId, "Could not refresh subagent tasks", failure)
        }
    }

    private fun publishLive(taskId: String, parentSessionId: String, activity: SubagentLiveActivity) {
        synchronized(dashboardLock) {
            if (closed || boundParentSessionId != parentSessionId) return
            _dashboard.update { current ->
                current.copy(tasks = current.tasks.map { task ->
                    if (task.id == taskId) task.copy(
                        activity = activity.detail, activityTool = activity.tool, activityStep = activity.step,
                    ) else task
                })
            }
        }
    }

    private fun toView(record: SubagentTaskRecord, live: SubagentLiveActivity?, queuePosition: Int): SubagentTaskView = SubagentTaskView(
        id = record.id,
        childSessionId = record.childSessionId,
        profile = record.profile,
        description = record.description,
        state = record.state,
        background = record.background,
        workspaceMode = record.workspaceMode,
        workspaceName = record.workspaceName,
        integration = record.integration,
        attempt = record.attempt,
        providerId = record.providerId,
        modelId = record.modelId,
        createdAtMs = record.createdAtMs,
        startedAtMs = record.startedAtMs,
        endedAtMs = record.endedAtMs,
        updatedAtMs = record.updatedAtMs,
        activity = live?.detail.orEmpty(),
        activityTool = live?.tool.orEmpty(),
        activityStep = live?.step ?: 0,
        resultPreview = record.result.take(1200),
        errorPreview = record.error.take(800),
        queuePosition = queuePosition,
    )

    private suspend fun ownedTask(parentSessionId: String, taskId: String): SubagentTaskRecord? =
        store.get(taskId)?.takeIf { it.parentSessionId == parentSessionId }

    private suspend fun renderTranscript(record: SubagentTaskRecord): String {
        val root = workspaceRoot(record) ?: return "ERROR: child workspace missing for task ${record.id}"
        val session = SessionManager(root).get(record.childSessionId) ?: return "ERROR: child session missing: ${record.childSessionId}"
        val retained = session.messages.takeLast(MAX_TRANSCRIPT_MESSAGES)
        val windowNotice = if (retained.size < session.messages.size)
            "[SUBAGENT_TRANSCRIPT_WINDOW: older messages omitted; showing last ${retained.size} of ${session.messages.size} messages]\n" else ""
        val body = retained.joinToString("\n") { message ->
            val part = message.streamPart
            if (part != null) {
                val detail = listOf(part.title, part.detail, part.text).filter { it.isNotBlank() }.joinToString(" · ")
                "[${part.kind.name.lowercase()}:${part.state.name.lowercase()}] ${SubagentTextProjection.bounded(detail, 1200)}"
            } else "${message.role.uppercase()}: ${SubagentTextProjection.bounded(message.content, 2400)}"
        }.trim()
        return "SUBAGENT_TRANSCRIPT task_id=${record.id} child_session_id=${record.childSessionId} messages=${session.messages.size}\n" +
            SubagentTextProjection.bounded(windowNotice + body.ifBlank { "(no persisted transcript yet)" }, MAX_TRANSCRIPT_CHARS)
    }

    private fun resolveWorkspaceMode(profile: AgentProfile, requested: String?): SubagentWorkspaceMode = when (requested?.trim()?.lowercase()) {
        null, "", "auto" -> if (profile.mode == AgentMode.BUILD) SubagentWorkspaceMode.ISOLATED else SubagentWorkspaceMode.SHARED
        "isolated", "branch", "worktree" -> SubagentWorkspaceMode.ISOLATED
        "shared", "inherit" -> SubagentWorkspaceMode.SHARED
        else -> throw IllegalArgumentException("workspace must be auto|isolated|shared")
    }

    private fun workspaceRoot(record: SubagentTaskRecord): File? {
        if (record.workspaceMode == SubagentWorkspaceMode.SHARED) return workDir
        if (record.workspaceName.isBlank() || record.workspacePath.isBlank()) return null
        val managed = workspaceManager.directory(record.workspaceName) ?: return null
        val expected = runCatching { File(record.workspacePath).canonicalFile }.getOrNull() ?: return null
        return managed.canonicalFile.takeIf { it == expected }
    }

    private fun renderResult(record: SubagentTaskRecord): String = when (record.state) {
        SubagentTaskState.IDLE -> buildString {
            append("SUBAGENT_COMPLETED task_id=${record.id} child_session_id=${record.childSessionId} workspace=${record.workspaceMode.name.lowercase()}")
            if (record.workspaceName.isNotBlank()) append(" workspace_id=${record.workspaceName} integration=${record.integration.name.lowercase()}")
            append("\n${record.result}")
            if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED) {
                append("\nChanges remain isolated. Inspect status, then use task_control(action=\"merge\", task_id=\"${record.id}\") or discard explicitly.")
            }
        }
        else -> "SUBAGENT_${record.state.name} task_id=${record.id} child_session_id=${record.childSessionId} workspace=${record.workspaceMode.name.lowercase()}\n${record.error.ifBlank { record.result }}"
    }

    private fun summary(record: SubagentTaskRecord): String =
        "task_id=${record.id} child_session_id=${record.childSessionId} profile=${record.profile} state=${record.state.name.lowercase()} " +
            "background=${record.background} delivery=${record.delivery.name.lowercase()} workspace=${record.workspaceMode.name.lowercase()} " +
            "workspace_id=${record.workspaceName.ifBlank { "-" }} integration=${record.integration.name.lowercase()} attempt=${record.attempt} " +
            "description=${record.description.take(120)}"

    companion object {
        const val DEFAULT_MAX_DEPTH = 1
        const val MAX_CONCURRENT_SUBAGENTS = 4
        // Child sessions get a separate bounded turn budget for substantial work.
        const val SUBAGENT_MAX_TURNS = 48
        const val MAX_PROMPT_CHARS = 24_000
        const val MAX_STEER_MESSAGE_CHARS = 8_000
        const val MAX_TRANSCRIPT_MESSAGES = 60
        const val MAX_TRANSCRIPT_CHARS = 16_000
        private const val MAX_PERSISTED_PROMPT_CHARS = 12_000
        private const val MAX_RESULT_CHARS = 16_000
        private const val MAX_ERROR_CHARS = 2_000
        private const val FINALIZATION_TIMEOUT_MS = 5_000L
        private const val DASHBOARD_FAILURE_ID = "subagent-dashboard-error"
        private val ACTIVE_TASK_STATES = setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)

        internal fun canDeliverCompletion(admitted: SubagentTaskRecord, current: SubagentTaskRecord): Boolean =
            admitted.background && current.background && current.id == admitted.id &&
                current.parentSessionId == admitted.parentSessionId && current.childSessionId == admitted.childSessionId &&
                current.attempt == admitted.attempt && current.delivery == SubagentDeliveryState.PENDING &&
                current.state !in ACTIVE_TASK_STATES
    }
}

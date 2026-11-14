package com.baystudio.droide.core
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
@Serializable
data class AgentConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "",
    val providerId: String = "",
    val reasoningEffort: ReasoningEffort? = null,
)


class AgentService(
    private val files: FileRepository,
    private val terminal: ITerminalSession,
    private val git: GitManager,
    val approvals: ApprovalManager = ApprovalManager(),
    val lsp: LspManager = CliLspManager(terminal, files),
    val questions: QuestionManager = QuestionManager(),
    val perms: PermissionEngine = PermissionEngine(),
    val workDir: File = files.root,
    private val subagentDepth: Int = 0,
    private val maxSubagentDepth: Int = SubagentManager.DEFAULT_MAX_DEPTH,
    private val systemOverlay: String = "",
    private val executionIdentity: AgentExecutionIdentity = AgentExecutionIdentity.primary(),
    private val agentPluginSource: AgentPluginSource = AgentPluginSource.EMPTY, private val documentAuthority: WorkspaceDocumentAuthority? = null,
    private val ideActions: AgentIdeActions? = null,
    private val toolchainActions: AgentToolchainActions? = null,
    private val capabilityActions: AgentCapabilityActions? = null, val browserController: AgentBrowserController? = null,
    internal val mcpProcessHost: StdioProcessHost? = null,
    private val appContext: android.content.Context? = null,
    private val runtimeScope: CoroutineScope? = null,
    private val providerConfiguration: ProviderRegistry.ConfiguredSnapshot? = null,
    private val codeStyleDefaults: suspend () -> CodeStyleDefaults = {
        appContext?.let { WorkbenchPreferences.flow(it).first().codeStyleDefaults } ?: CodeStyleDefaults()
    },
) {
    /** Availability and dispatch share the same external staging boundary. No storage is created here. */
    private fun localFolderRecoveryCache(): File? {
        val context = appContext ?: return null
        return runCatching {
            context.cacheDir.canonicalFile.takeUnless { PathSecurity.contains(files.root.canonicalFile, it) }
        }.getOrNull()
    }
    val skillManagement: SkillManagement by lazy { SkillManagement(files, agentPluginSource, SkillManagement.personalRoot(appContext)) }
    private val llm = LlmClient(providerConfiguration)
    private val hooks = HookManager(
        workDir, terminal, perms, approvals,
        provenanceProvider = { requestProvenance() }, permissionScopeProvider = { permissionScope() },
        plugins = agentPluginSource,
    )
    private val lifecycleHooks by lazy { AgentHookLifecycleBridge(hooks, { currentSessionId.orEmpty() }, { _mode.value }) }
    val sessions = SessionManager(workDir, llm).also {
        it.toolOutputStore = { AgentToolOutputStore.forWorkspace(workDir) }
    }
    private val promptCoordinator = AgentPromptCoordinator(sessions, MAX_USER_MESSAGE_CHARS, MAX_AGENT_TURNS)
    val pendingInputs: StateFlow<List<AgentPendingInput>> = promptCoordinator.pendingInputs
    val todos = TodoManager()
    val tokens = TokenTracker()
    private val json = Json { ignoreUnknownKeys = true }
    private val editHistory = EditHistory(files, documentAuthority, lsp)
    private val lspEditActions: AgentLspEditActions? = documentAuthority?.let { AgentLspEditActions(files, lsp, editHistory, it, codeStyleDefaults) }
    internal fun externalIdeTools(): ExternalIdeToolRegistry.Provider = ExternalIdeTools(
        files, documentAuthority, lsp, perms, approvals, requireOwner = { exitFence.requireOpen() },
        handbook = appContext?.let { context -> { context.assets.open(DroideHandbookPolicy.ASSET)
            .bufferedReader(Charsets.UTF_8).use { it.readText() } } },
        status = ideActions?.let { actions -> { actions.execute("status") } },
        skills = skillManagement,
        media = appContext?.let { context -> MediaAuthoringTools(files, terminal,
            { context.assets.open(MediaAuthoringTools.ASSET).bufferedReader().use { it.readText() } }, documentAuthority, { exitFence.requireOpen() }) },
        toolchain = toolchainActions?.let { actions -> { args, provenance, current ->
            actions.execute(args, AgentMode.BUILD, perms, approvals, "external-cli-ide", provenance, current)
        } },
    )
    private val backgroundCommands = BackgroundCommandManager(terminal, onCommandFinished = {
        documentAuthority?.reconcileAfterExternalCommand()
    })
    private val webResearch = AgentWebResearch(workDir)
    private val lifecycleScope = CoroutineScope(
        (runtimeScope?.coroutineContext?.minusKey(Job) ?: Dispatchers.Default) +
            SupervisorJob(runtimeScope?.coroutineContext?.get(Job))
    )
    private val sessionTitles by lazy {
        SessionTitleController(sessions, lifecycleScope, llm::sessionTitleCompletion,
            mayRun = { exitFence.isOpen && !mediaRecoveryClosed })
    }
    private val subagentBridge = AgentSubagentBridge(
        files = files, terminal = terminal, git = git, approvals = approvals, lsp = lsp, questions = questions,
        permissions = perms, workDir = workDir, depth = subagentDepth, maxDepth = maxSubagentDepth,
        pluginSource = agentPluginSource, documentAuthority = documentAuthority,
        providerConfiguration = providerConfiguration,
        codeStyleDefaults = codeStyleDefaults,
        maxAssistantChars = MAX_ASSISTANT_CHARS, maxApiMessageChars = MAX_API_MESSAGE_CHARS,
        onBackgroundCompletion = { completion ->
            val record = completion.record
            if (exitFence.isOpen && record.delivery == SubagentDeliveryState.PENDING && currentSessionId == record.parentSessionId) {
                submitAsync(
                    lifecycleScope, renderSubagentCompletionText(record, MAX_API_MESSAGE_CHARS, completion.activeSiblings), completion.config, AgentPromptDelivery.STEER,
                    inputOrigin = AgentInputOrigin.SUBAGENT, sourceId = record.id, requiredSessionId = record.parentSessionId,
                    sourceDelivery = SubagentDeliveryReceipt.from(record),
                )
            }
        },
    )
    private val subagentManager: SubagentManager? get() = subagentBridge.manager
    internal suspend fun observeChildOwnership(): ChildOwnershipObservation =
        subagentManager?.observeChildOwnership() ?: ChildOwnershipObservation(emptyList(), listOf("Child resource producer is unavailable at this depth limit")) {}

    val subagentDashboard: StateFlow<SubagentDashboardState> get() = subagentBridge.dashboard
    suspend fun controlSubagentFromUi(action: String, taskId: String, message: String? = null): String {
        val exitRegistration = exitFence.track(currentCoroutineContext().job)
        try {
        val sessionId = currentSessionId ?: return "No active parent session."
        return try {
            subagentBridge.controlFromUi(sessionId, action, taskId, message).take(5_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            "ERROR: subagent action failed: ${error.message?.take(320) ?: error::class.java.simpleName}"
        }

        } finally { exitRegistration.close() }
    }
    suspend fun steerActive(userText: String, config: AgentConfig, requiredSessionId: String, ideContext: AgentIdeContextSnapshot? = null): Boolean {
        val exitRegistration = exitFence.track(currentCoroutineContext().job)
        try {
        val acceptedAt = captureMediaRequestAdmission(AgentInputOrigin.USER)
        return currentSessionId == requiredSessionId && promptCoordinator.admitSteerIfActive(currentSessionId, userText, config,
            ideContext, sessionIsCurrent = { exitFence.isOpen && currentSessionId == requiredSessionId }, requestAdmittedAtNanos = acceptedAt)

        } finally { exitRegistration.close() }
    }
    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    // Historical metadata only. Accessed under admissionLock; never model history.
    private var textEditEvidence: SessionTextEditEvidence.Evidence? = null
    val messages: StateFlow<List<AgentMessage>> = _messages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _mode = MutableStateFlow(AgentMode.BUILD)
    val mode: StateFlow<AgentMode> = _mode.asStateFlow(); internal fun mcpPluginSource(): AgentPluginSource = agentPluginSource
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()
    private val _streamState = MutableStateFlow(AgentStreamState())
    private val streamProjector = AgentStreamProjector(_streamState, MAX_ASSISTANT_CHARS, MAX_TOOL_ARGUMENT_CHARS)
    val streamState: StateFlow<AgentStreamState> = _streamState.asStateFlow()
    private val _contextUsage = MutableStateFlow<AgentContextUsage?>(null)
    val contextUsage: StateFlow<AgentContextUsage?> = _contextUsage.asStateFlow()
    private val _compactionActivity = MutableStateFlow<AgentCompactionActivity?>(null)
    val compactionActivity: StateFlow<AgentCompactionActivity?> = _compactionActivity.asStateFlow()
    private val compactionSequence = AtomicLong()
    private val manualCompactionMutex = Mutex()
    private var manualCompactionJob: Job? = null
    private var manualCompactionProviderPhase = false
    fun cancelContextCompaction() = synchronized(admissionLock) {
        manualCompactionJob?.cancel(CancellationException("Context compaction stopped"))
    }
    fun setMode(m: AgentMode) { synchronized(admissionLock) { exitFence.requireOpen(); _mode.value = m } }
    private val conversationHistory = AgentConversationHistory(sessions.historyLedger)
    private val _apiHistory: MutableList<JsonObject> get() = conversationHistory.active
    // Canonical once activated. The legacy ledger is never a substitute for source-backed history.
    @Volatile private var typedCheckpoint: TypedChatCheckpoint? = null
    private data class OwnedServiceTurn(val result: OwnedChatResult, val resources: TypedChatResources,
        val responseOwner: OwnedResponseContentStore.Owner, val responseSession: OwnedResponseContentStore.Session)
    private data class PrivateServiceAssistant(val received: OwnedServiceTurn, val registryId: String)
    @Volatile var currentSessionId: String? = null
        private set
     
    private var pendingSessionStartTrigger: String? = null
    private var job: Job? = null
    private val runGeneration = AtomicLong(0L)
    private val admissionLock = Any()
    private val exitFence = ExitOperationFence(admissionLock)
    private val exitCheckpointMutex = Mutex()
    private val exitUncheckpointedInputs = LinkedHashMap<String, AgentPendingInput>()
    private val historyAdmittedExitInputs = LinkedHashMap<String, Long>()
    private val exitCleanupJobs = LinkedHashSet<Job>()
    private val exitRunFinalizations = LinkedHashSet<CompletableDeferred<Unit>>()
    fun snapshotUncheckpointedInputsForExit(): List<AgentPendingInput> = synchronized(admissionLock) {
        exitUncheckpointedInputs.values.filter { it.origin == AgentInputOrigin.USER }
    }
    // No-op unless a focused fixture widens a publication boundary before admission.
    internal enum class PublicationBoundary {
        CREATED_SESSION, INITIAL_STREAM, PROVIDER_EVENT, PROVIDER_RESULT, ASSISTANT_HISTORY_APPEND, SOURCE_PREFLIGHT_RETURN,
    }
    @Volatile internal var publicationBoundaryProbe: (suspend (PublicationBoundary) -> Unit)? = null
    @Volatile private var loadingSessionGeneration: Long? = null
    @Volatile private var mediaRecoveryClosed = false
    private var loadingPreviousInputs: List<AgentPendingInput>? = null
    private data class SessionRestoreAdmission(
        val previousSessionId: String?, val previousPendingInputs: List<AgentPendingInput>,
        val loadOwner: Long, val historyTicket: Long,
    )
    private data class OwnedSessionSnapshot(
        val id: String, val generation: Long, val messages: List<AgentMessage>, val apiHistory: List<JsonObject>,
        val todos: List<Todo>, val mode: AgentMode, val pending: List<AgentPendingInput>, val historyVersion: Long,
        val textEdits: SessionTextEditEvidence.Evidence?, val typedChat: TypedChatCheckpoint? = null,
    )
    private data class RunPublicationOwner(val generation: Long, val sessionId: String, val historyVersion: Long)
    /** A run never becomes newer when older queued work or a later steer is promoted. */
    internal class MediaRequestAdmission(initialNanos: Long?) {
        var admittedAtNanos: Long? = initialNanos
            private set
        fun includePromoted(originalNanos: Long?) {
            admittedAtNanos = admittedAtNanos?.let { current -> originalNanos?.let { minOf(current, it) } }
        }
        companion object {
            fun capture(origin: AgentInputOrigin, depth: Int, overlay: String, kind: AgentRequesterKind,
                clock: () -> Long = System::nanoTime): Long? =
                if (origin == AgentInputOrigin.USER && depth == 0 && overlay.isBlank() && kind == AgentRequesterKind.PRIMARY) clock() else null
        }
    }
    private fun isPrimaryMediaUser(origin: AgentInputOrigin): Boolean = origin == AgentInputOrigin.USER &&
        subagentDepth == 0 && systemOverlay.isBlank() && executionIdentity.kind == AgentRequesterKind.PRIMARY
    private fun captureMediaRequestAdmission(origin: AgentInputOrigin): Long? =
        if (mediaRecoveryClosed) null else MediaRequestAdmission.capture(origin, subagentDepth, systemOverlay, executionIdentity.kind)

    /** UI-only owner-bound access. Creating it reads root identity but does not scan recovery. */
    internal suspend fun openMediaRecovery(): MediaRecoveryAccess {
        val owner = synchronized(admissionLock) {
            val session = requireNotNull(currentSessionId) { "Open a conversation before media recovery" }
            requireMediaRecoveryOwner(session, runGeneration.get())
            session to runGeneration.get()
        }
        val access = withContext(Dispatchers.IO) {
            synchronized(admissionLock) { requireMediaRecoveryOwner(owner.first, owner.second) }
            val journal = files.copyRecoveryJournal()
            synchronized(admissionLock) {
                requireMediaRecoveryOwner(owner.first, owner.second)
                MediaRecoveryAccess(owner.first, owner.second, journal)
            }
        }
        synchronized(admissionLock) { requireMediaRecoveryOwner(owner.first, owner.second) }
        return access
    }
    internal inner class MediaRecoveryAccess internal constructor(
        private val sessionId: String,
        private val generation: Long,
        private val journal: ProjectCopyJournal,
    ) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private var inspectionToken: String? = null
        private val recoveryScope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, sessionId)
        private fun requireCurrent() {
            check(!closed.get()) { "Media recovery was closed; reopen it" }
            requireMediaRecoveryOwner(sessionId, generation)
        }
        suspend fun recovery(): ProjectCopyJournal.Recovery {
            val result = withContext(Dispatchers.IO) {
                synchronized(admissionLock) { requireCurrent() }
                journal.recovery(recoveryScope).also { synchronized(admissionLock) { requireCurrent() } }
            }
            synchronized(admissionLock) { requireCurrent() }
            return result
        }
        suspend fun inspect(transactionId: String): ProjectCopyJournal.Inspection {
            val result = withContext(Dispatchers.IO) {
                synchronized(admissionLock) { requireCurrent() }
                journal.inspect(recoveryScope, transactionId).also { synchronized(admissionLock) { requireCurrent() } }
            }
            synchronized(admissionLock) { requireCurrent(); inspectionToken = result.token }
            return result
        }
        suspend fun acknowledge(inspectionToken: String) {
            withContext(Dispatchers.IO) {
                synchronized(admissionLock) {
                    requireCurrent()
                    check(this@MediaRecoveryAccess.inspectionToken == inspectionToken) { "Inspection belongs to another recovery view; inspect again" }
                    this@MediaRecoveryAccess.inspectionToken = null
                }
                // Read/hash evidence without the lifecycle monitor. Only the final in-memory
                // grant is admitted under the same short lock as session switch and shutdown.
                journal.acknowledge(recoveryScope, inspectionToken) { grant ->
                    synchronized(admissionLock) { requireCurrent(); grant() }
                }
                synchronized(admissionLock) { requireCurrent() }
            }
            synchronized(admissionLock) { requireCurrent() }
        }
        // Never block main-thread disposal behind the bounded acknowledgement evidence read.
        // A confirmation already accepted in flight may finish granting its one-shot permission;
        // closing the view never copies or promises that an accepted grant was rolled back.
        override fun close() { closed.set(true) }
    }
    private fun requireMediaRecoveryOwner(sessionId: String, generation: Long) {
        check(exitFence.isOpen && !mediaRecoveryClosed && lifecycleScope.isActive && isPrimaryMediaUser(AgentInputOrigin.USER) &&
            currentSessionId == sessionId && runGeneration.get() == generation && loadingSessionGeneration == null &&
            !_busy.value && job?.isActive != true && !promptCoordinator.hasReservedRun) { "Media recovery owner changed or an Agent task is running; reopen it when idle" }
    }

    private fun requestProvenance(sessionId: String? = currentSessionId): AgentRequestProvenance = executionIdentity.request(sessionId)
    private fun permissionScope(): String = AgentPermissionScope.id(_mode.value, requestProvenance())
    fun ownedProcessDiagnostics(): OwnedProcessDiagnostics = OwnedProcessDiagnostics(backgroundCommands)
    fun cancel() { job?.cancel(); backgroundCommands.cancelForegroundCommands() }
    /** Explicit Exit only. Keep the history ticket until the last real save receipt succeeds. */
    suspend fun stopAndCheckpointForExit(): Unit = exitCheckpointMutex.withLock {
        val caller = currentCoroutineContext().job
        val owned = synchronized(admissionLock) {
            (exitFence.beginExit() + listOfNotNull(job)).distinct().filter { it !== caller }
        }
        owned.forEach { it.cancel(CancellationException("Application Exit")) }
        // Fence every descendant before the first join; slow cleanup cannot admit another paid turn.
        backgroundCommands.close()
        webResearch.beginExit()
        subagentBridge.beginExit()
        backgroundCommands.closeAndJoin()
        webResearch.closeAndJoin()
        var childFailure: Exception? = null
        try { subagentBridge.closeAndJoin() } catch (failure: Exception) { childFailure = failure }
        owned.joinAll()
        synchronized(admissionLock) { exitRunFinalizations.toList() }.awaitAll()
        synchronized(admissionLock) { exitCleanupJobs.toList() }.joinAll()
        lifecycleScope.coroutineContext.job.cancelAndJoin()
        val owner = synchronized(admissionLock) {
            check(!mediaRecoveryClosed) { "Agent history authority was retired before its Exit checkpoint" }
            check(loadingSessionGeneration == null) { "Agent session restore has not settled" }
            currentSessionId?.let { it to runGeneration.get() }
        }
        if (owner != null) {
            check(persistCurrent("Agent conversation", owner.first, owner.second)) {
                _persistenceError.value ?: "Agent history checkpoint did not commit"
            }
        }
        childFailure?.let { throw it }
    }

    /** Finite retirement at committed workspace handoff; resource cleanup remains separate. */
    internal fun retireAuthority() {
        synchronized(admissionLock) {
            if (!mediaRecoveryClosed) {
                mediaRecoveryClosed = true
                // Revoke an append already spilling outside this monitor without clearing
                // the transcript. Ordinary Stop deliberately retains its history ticket.
                conversationHistory.beginRestore()
            }
        }
    }
    fun shutdown() {
        retireAuthority()
        cancelContextCompaction()
        currentSessionId?.let(approvals::clearSessionApprovals); job?.cancel(); McpManager.shutdownSessions(workDir, mcpProcessHost)
        job = null
        subagentBridge.close()
        backgroundCommands.close()
        webResearch.close()
        lifecycleScope.cancel("Agent service shut down")
    }
    fun clear() = synchronized(admissionLock) {
        exitFence.requireOpen()
        cancelContextCompaction()
        currentSessionId?.let(approvals::clearSessionApprovals); cancel()
        job = null
        runGeneration.incrementAndGet()
        loadingSessionGeneration = null
        loadingPreviousInputs = null
        _busy.value = false
        currentSessionId = null
        _contextUsage.value = null
        _compactionActivity.value = null
        promptCoordinator.clear()
        exitUncheckpointedInputs.clear(); historyAdmittedExitInputs.clear()
        _messages.value = emptyList()
        textEditEvidence = null
        conversationHistory.resetNew()
        typedCheckpoint = null
        pendingSessionStartTrigger = null
        subagentBridge.bindParent(null)
        perms.clearSessionAllows()
        todos.clear()
        _persistenceError.value = null
        _streamState.value = AgentStreamState()
    }
    internal fun continuationAvailability(expectedSessionId: String?): AgentContinuation = synchronized(admissionLock) {
        when {
            expectedSessionId != currentSessionId -> AgentContinuation.CHANGED
            currentSessionId == null || _messages.value.isEmpty() -> AgentContinuation.EMPTY
            _busy.value || job?.isActive == true || loadingSessionGeneration != null || manualCompactionProviderPhase -> AgentContinuation.WORKING
            _persistenceError.value != null || typedCheckpoint?.let {
                runCatching { TypedChatForkBoundary.completedEnd(it.exact) == null }.getOrDefault(true)
            } == true -> AgentContinuation.NEEDS_REVIEW
            else -> AgentContinuation.READY
        }
    }

    suspend fun loadSession(s: DroideSession, requiredGeneration: Long? = null, requiredSessionId: String? = null, requireIdle: Boolean = false) {
        val exitRegistration = exitFence.track(currentCoroutineContext().job)
        try {
        val admission = synchronized(admissionLock) {
            check(!requireIdle || !_busy.value && job?.isActive != true && loadingSessionGeneration == null) {
                "An Agent task is still running. Finish it or use Stop before opening another conversation."
            }
            if (requiredGeneration != null && (runGeneration.get() != requiredGeneration || currentSessionId != requiredSessionId || loadingSessionGeneration != null))
                throw CancellationException("Agent session changed before restoring compaction")
            // Snapshot failures keep their original diagnostic identity. Once provider
            // work may be in flight, a user-directed load cancels that owned operation.
            if (requiredGeneration == null && manualCompactionProviderPhase) cancelContextCompaction()
            val previous = currentSessionId
            val inputs = loadingPreviousInputs ?: pendingInputs.value
            cancel()
            job = null
            val owner = runGeneration.incrementAndGet()
            loadingPreviousInputs = inputs
            loadingSessionGeneration = owner
            _busy.value = false
            promptCoordinator.clear()
            SessionRestoreAdmission(previous, inputs, owner, conversationHistory.beginRestore())
        }
        val (previousSessionId, previousPendingInputs, loadOwner, historyTicket) = admission
        var loaded = false
        try {
        // Disk reads and metadata validation must not run inside publishTransaction.
        val restoredTextEdits = sessions.readTextEditEvidence(s)
        val restoredTyped = s.typedChat?.let { checkpoint -> withContext(Dispatchers.IO) {
            val history = sessions.typedChatResources().history
            history.verify(checkpoint, s.id)
            history.reconcileSettledSources(checkpoint, s.id)
        } }
        val preservedTypedEnds = restoredTyped?.let { checkpoint -> withContext(Dispatchers.IO) {
            TypedRestoreForkAnchors.validatedEnds(s.messages,checkNotNull(s.typedChat),checkpoint)
        } }
        conversationHistory.load(s.copy(typedChat = restoredTyped),
            shouldPublish = { runGeneration.get() == loadOwner && loadingSessionGeneration == loadOwner },
            restoreTicket = historyTicket,
            publishTransaction = { commit ->
        synchronized(admissionLock) {
        if (runGeneration.get() != loadOwner || loadingSessionGeneration != loadOwner) throw CancellationException("Agent session changed during restore")
        val loadedMessages = commit()
        val restoredMessages = if (restoredTyped == null) loadedMessages else
            TypedRestoreForkAnchors.restore(s.messages,loadedMessages,preservedTypedEnds.orEmpty())
        promptCoordinator.load(s.pendingInputs, s.id)
        _mode.value = s.mode
        textEditEvidence = restoredTextEdits
        typedCheckpoint = restoredTyped
        _messages.value = AgentTextEditProjection.apply(restoredMessages, restoredTextEdits)
        currentSessionId = s.id
        _contextUsage.value = null
        if (_compactionActivity.value?.sessionId != s.id) _compactionActivity.value = null
        subagentBridge.bindParent(s.id)
        if (previousSessionId != s.id) { previousSessionId?.let(approvals::clearSessionApprovals); perms.clearSessionAllows() }
        todos.set(s.todos, s.id)
        _persistenceError.value = null
        _streamState.value = AgentStreamState()
        pendingSessionStartTrigger = "resume"
        loaded = true
        restoredMessages
        }
        })
        } finally {
            synchronized(admissionLock) {
            if (loadingSessionGeneration == loadOwner) {
                if (!loaded && runGeneration.get() == loadOwner && currentSessionId == previousSessionId)
                    promptCoordinator.load(previousPendingInputs, previousSessionId)
                loadingSessionGeneration = null
                loadingPreviousInputs = null
            }
            }
        }

        } finally { exitRegistration.close() }
    }
    suspend fun compactCurrent(config: AgentConfig): String = coroutineScope {
        if (!manualCompactionMutex.tryLock()) return@coroutineScope "Context compaction is already running."
        val compactJob = currentCoroutineContext().job
        var owner: Pair<AgentCompactionActivity, Long>? = null
        try {
            owner = synchronized(admissionLock) {
                exitFence.requireOpen()
                manualCompactionJob = compactJob
                currentSessionId?.takeIf { typedCheckpoint == null }?.let { sid ->
                    val activity = AgentCompactionActivity(compactionSequence.incrementAndGet(), sid, true,
                        AgentCompactionBudget.historyTokens(_apiHistory))
                    _compactionActivity.value = activity
                    activity to runGeneration.get()
                }
            }
            val result = try {
                ExportWriterAdmission.withSuspendingWriter(workDir, "Manual conversation compaction") {
                    compactCurrentAdmitted(config)
                }
            } catch (busy: ExportCaptureBusyException) { "Compaction failed: ${busy.message}" }
            if (owner != null) synchronized(admissionLock) {
                val (activity, generation) = owner
                val changed = result == "Session compacted."
                // Successful manual compaction restores its own session exactly once.
                if (_compactionActivity.value?.id == activity.id && currentSessionId == activity.sessionId &&
                    runGeneration.get() == generation + (if (changed) 1L else 0L)) {
                    _compactionActivity.value = activity.copy(running = false,
                        afterTokens = if (changed) AgentCompactionBudget.historyTokens(_apiHistory) else null, detail = result)
                }
            }
            return@coroutineScope result
        } finally {
            synchronized(admissionLock) {
                if (manualCompactionJob === compactJob) {
                    manualCompactionJob = null
                    manualCompactionProviderPhase = false
                }
                if (owner != null && _compactionActivity.value?.id == owner.first.id && _compactionActivity.value?.running == true)
                    _compactionActivity.value = null
            }
            manualCompactionMutex.unlock()
        }
    }

    private suspend fun compactCurrentAdmitted(config: AgentConfig): String {
        if (typedCheckpoint != null) return compactTypedManually(config)
        val exitRegistration = exitFence.track(currentCoroutineContext().job)
        try {
        var failureOwner: Pair<Long, String>? = null
        return try {
        val snapshot = synchronized(admissionLock) {
            val sid = currentSessionId ?: return@synchronized null
            // Also bind precondition failures before any checkpoint/provider suspension.
            failureOwner = runGeneration.get() to sid
            if (loadingSessionGeneration != null) throw CancellationException("Agent session is loading")
            check(!_busy.value && job?.isActive != true) {
                "Finish the running Agent task or use Stop before compacting this conversation manually."
            }
            OwnedSessionSnapshot(sid, runGeneration.get(), _messages.value, completeApiHistoryForModel(), todos.get(sid), _mode.value, pendingInputs.value,
                conversationHistory.ownershipVersion, textEditEvidence)
        }
        if (snapshot == null) "No active session." else {
        val exact = conversationHistory.completeExactOrNull(snapshot.historyVersion)
        synchronized(admissionLock) {
            ensureRunCurrent(snapshot.generation, snapshot.id)
            manualCompactionProviderPhase = true
        }
        exitFence.requireOpen()
        AgentManualCompaction.run(
            snapshot.id, sessions, snapshot.messages, snapshot.apiHistory, exact,
            snapshot.todos, snapshot.mode, snapshot.pending, config, tokens, lifecycleHooks,
            load = { session -> loadSession(session, snapshot.generation, snapshot.id, requireIdle = true) },
            hookOwner = captureHookAuthority(snapshot.generation, snapshot.id, snapshot.mode),
            commitAdmission = { publish -> synchronized(admissionLock) {
                ensureRunCurrent(snapshot.generation, snapshot.id)
                if (loadingSessionGeneration != null) throw CancellationException("Agent session is loading")
                check(_mode.value == snapshot.mode && todos.get(snapshot.id) == snapshot.todos) {
                    "Task state changed during compaction; existing conversation was preserved. Retry compaction"
                }
                publish()
            } },
        )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val message = "Compaction failed: ${error.message?.take(240) ?: error::class.java.simpleName}"
        synchronized(admissionLock) {
            failureOwner?.let { (generation, sessionId) ->
                if (isRunCurrent(generation, sessionId)) _persistenceError.value = message
            }
        }
        message
    }

        } finally { exitRegistration.close() }
    }
    suspend fun undoLast(): String {
        val registration = exitFence.track(currentCoroutineContext().job)
        try { return editHistory.undo() } finally { registration.close() }
    }


    fun submitAsync(
        scope: CoroutineScope,
        userText: String,
        config: AgentConfig,
        delivery: AgentPromptDelivery = AgentPromptDelivery.STEER,
        maxTurns: Int = MAX_AGENT_TURNS,
        inputOrigin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        requiredSessionId: String? = null,
        imagePaths: List<String> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
        onAccepted: () -> Unit = {},
        media: List<ChatMediaRef> = emptyList(),
        mediaOwnerId: String? = null,
        requestAdmittedAtNanos: Long? = captureMediaRequestAdmission(inputOrigin),
        sourceDelivery: SubagentDeliveryReceipt? = null,
    ): Job? = synchronized(admissionLock) {
        if (!exitFence.isOpen || mediaRecoveryClosed) return null
        // UI passes its Send-gesture time before asynchronous model/config preflight.
        // Explicit null remains unknown; child/system provenance never gains authority.
        val originalAdmission = requestAdmittedAtNanos.takeIf { isPrimaryMediaUser(inputOrigin) && !mediaRecoveryClosed }
        if (loadingSessionGeneration != null) {
            _persistenceError.value = "Session is still loading. Wait for restore to finish before sending a prompt."
            return null
        }
        // Runtime-backed services own accepted work; caller/panel disposal is never Stop.
        val ownerScope = if (runtimeScope != null) lifecycleScope else scope
        val initialSession = currentSessionId
        require(media.size <= VisionSupport.MAX_IMAGES) { "Attach at most four items to one prompt" }
        val admissionOwner = "admission-${java.util.UUID.randomUUID()}"
        val mediaReady = CompletableDeferred<Unit>()
        var startedRun = false
        // Acquired before inbox/media preparation, then transferred to the owned run.
        // Refusal leaves onAccepted uncalled, so the user's draft stays intact.
        var exportWriter: AutoCloseable? = null
        var preparedImages: List<AgentImageRef> = emptyList()
        val hasMedia = media.isNotEmpty() || imagePaths.isNotEmpty()
        val admittedJob = promptCoordinator.submit(
        scope = ownerScope, userText = userText, config = config, delivery = delivery, maxTurns = maxTurns,
        currentSessionId = { exitFence.requireOpen(); currentSessionId },
        startRun = { lease ->
            synchronized(admissionLock) {
            exitFence.requireOpen()
            promptCoordinator.requireStartRun(lease)
            startedRun = true
            exitUncheckpointedInputs[admissionOwner] = AgentPendingInput(
                id = admissionOwner, text = userText, delivery = delivery, providerId = config.providerId,
                modelId = config.model, reasoningEffort = config.reasoningEffort, origin = inputOrigin,
                sourceId = sourceId, images = preparedImages, ideContext = ideContext, media = media,
                sourceDelivery = sourceDelivery,
            )
            // Install ownership before dispatch: even immediate startup failure or
            // Stop must refer to this run, never the preceding conversation.
            lateinit var ownedRun: Job
            val finalized = java.util.concurrent.atomic.AtomicBoolean(false)
            val finalizationReceipt = CompletableDeferred<Unit>()
            exitRunFinalizations += finalizationReceipt
            finalizationReceipt.invokeOnCompletion { failure -> if (failure == null) synchronized(admissionLock) { exitRunFinalizations -= finalizationReceipt } }
            fun reportRunFailure(failure: Throwable) {
                if (job !== ownedRun) return
                _persistenceError.value = "Agent operation failed: ${failure.message?.take(320) ?: failure::class.java.simpleName}"
            }
            suspend fun finishOwnedRun() {
                if (!finalized.compareAndSet(false, true)) return
                if (!mediaReady.isCompleted) mediaReady.completeExceptionally(IllegalStateException("The media prompt was not saved; the draft was kept"))
                withContext(NonCancellable) {
                    try {
                        if (hasMedia) withContext(Dispatchers.IO) { ChatMediaStore(workDir).releaseOwner(admissionOwner) }
                    } catch (failure: Exception) {
                        reportRunFailure(failure)
                    } finally {
                        try {
                            promptCoordinator.markRunInactive(lease)
                            synchronized(admissionLock) { if (job === ownedRun) _busy.value = false }
                            finalizationReceipt.complete(Unit)
                        } catch (failure: Throwable) {
                            finalizationReceipt.completeExceptionally(failure)
                            throw failure
                        } finally {
                            exportWriter?.close()
                        }
                    }
                }
            }
            ownedRun = ownerScope.launch(start = CoroutineStart.LAZY) {
                try {
                    chat(userText, config, maxTurns, lease, inputOrigin, sourceId, requiredSessionId,
                        imagePaths, ideContext, media, admissionOwner, preparedImages, mediaReady,
                        requestAdmittedAtNanos = originalAdmission, sourceDelivery = sourceDelivery, exitInputId = admissionOwner)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportRunFailure(failure)
                } catch (failure: LinkageError) {
                    reportRunFailure(failure)
                } finally {
                    finishOwnedRun()
                }
            }
            job = ownedRun
            ownedRun.invokeOnCompletion {
                // Stop can win before a dispatched coroutine enters its try/finally.
                // Release that STARTING lease too; only one cleanup owns the draft.
                if (!finalized.get()) synchronized(admissionLock) {
                    val cleanup = ownerScope.launch(NonCancellable) { finishOwnedRun() }
                    exitCleanupJobs += cleanup
                    cleanup.invokeOnCompletion { synchronized(admissionLock) { exitCleanupJobs -= cleanup } }
                }
            }
            ownedRun.start()
            }
        },
        origin = inputOrigin,
        requestAdmittedAtNanos = originalAdmission,
        sourceId = sourceId,
        sourceDelivery = sourceDelivery,
        requiredSessionId = requiredSessionId,
        prepareImages = {
            exitFence.requireOpen()
            exportWriter = ExportWriterAdmission.beginWriter(workDir, "Agent prompt/run")
            require(currentSessionId == initialSession) { "The conversation changed before the attachment was submitted" }
            if (hasMedia) {
                require(media.none { it.mediaType.startsWith("image/") || it.mediaType.startsWith("video/") } ||
                    VisionSupport.providerAcceptsImageUrl(config)) { "The selected model does not accept images; the media draft was kept" }
                withContext(Dispatchers.IO) { ChatMediaStore(workDir).retain(admissionOwner, media) }
                val prepared = ChatMediaProcessing.prepareModelImages(workDir, media, admissionOwner)
                preparedImages = VisionSupport.stage(workDir, imagePaths + prepared.imagePaths, config, ownerId = admissionOwner)
            }
            exitFence.requireOpen()
            require(currentSessionId == initialSession) { "The conversation changed while the attachment was prepared" }
            preparedImages
        },
        ideContext = ideContext,
        media = media,
        reportError = { _persistenceError.value = it },
        onAccepted = {
            if (startedRun && (media.isNotEmpty() || preparedImages.isNotEmpty())) mediaReady.await()
            if (hasMedia) withContext(Dispatchers.IO) { mediaOwnerId?.let { ChatMediaStore(workDir).releaseOwner(it) } }
            onAccepted()
        },
    )
        val admissionRegistration = admittedJob?.let(exitFence::track)
        admittedJob?.invokeOnCompletion {
            admissionRegistration?.close()
            if (!startedRun) exportWriter?.close()
            if (!startedRun && hasMedia) ownerScope.launch(Dispatchers.IO) { runCatching { ChatMediaStore(workDir).releaseOwner(admissionOwner) } }
        }
        return admittedJob
    }
    fun chatAsync(scope: CoroutineScope, userText: String, config: AgentConfig, maxTurns: Int = MAX_AGENT_TURNS) =
        submitAsync(scope, userText, config, AgentPromptDelivery.STEER, maxTurns)
    fun chatAsyncWithImages(scope: CoroutineScope, userText: String, config: AgentConfig, imagePaths: List<String>, maxTurns: Int = MAX_AGENT_TURNS) = submitAsync(scope, userText, config, AgentPromptDelivery.STEER, maxTurns, imagePaths = imagePaths)
    suspend fun removePendingInput(id: String): Boolean {
        val exitRegistration = exitFence.track(currentCoroutineContext().job)
        try {
        val sid = currentSessionId ?: return false
        return try {
            promptCoordinator.remove(sid, id, sessionIsCurrent = { exitFence.isOpen && currentSessionId == sid })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _persistenceError.value = "Could not remove pending prompt: ${error.message?.take(240) ?: error::class.java.simpleName}"
            false
        }

        } finally { exitRegistration.close() }
    }
    suspend fun chat(
        userText: String,
        config: AgentConfig,
        maxTurns: Int = MAX_AGENT_TURNS,
        admissionLease: Long? = null,
        inputOrigin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        requiredSessionId: String? = null,
        imagePaths: List<String> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
        media: List<ChatMediaRef> = emptyList(),
        mediaOwnerId: String? = null,
        preparedImages: List<AgentImageRef>? = null,
        mediaReady: CompletableDeferred<Unit>? = null,
        requestAdmittedAtNanos: Long? = captureMediaRequestAdmission(inputOrigin),
        sourceDelivery: SubagentDeliveryReceipt? = null,
        exitInputId: String? = null,
    ) {
        val exportWriter = ExportWriterAdmission.beginWriter(workDir, "Agent conversation run")
        try {
        val exitRegistration = exitFence.track(currentCoroutineContext().job)
        try {
        val mediaAdmission = MediaRequestAdmission(requestAdmittedAtNanos.takeIf {
            isPrimaryMediaUser(inputOrigin) && !mediaRecoveryClosed
        })
        if (loadingSessionGeneration != null) throw CancellationException("Agent session is loading")
        require(ProviderRegistry.findById(config.providerId, providerConfiguration)?.let { !it.needsKey || config.apiKey.isNotBlank() || ProviderAuthMethods.canResolveWithoutStoredSecret(it) } == true) { "Connect an AI provider and enter its API key first" }
        val modelId = ProviderModelIdentity.requireCanonical(config.model)
        ProviderRuntimeGuard.requireCertifiedSelection(config.providerId, config.baseUrl, modelId, providerConfiguration)
        require(userText.length <= MAX_USER_MESSAGE_CHARS) { "Message exceeds $MAX_USER_MESSAGE_CHARS characters" }
        require(maxTurns in 1..MAX_AGENT_TURNS) { "maxTurns must be between 1 and $MAX_AGENT_TURNS" }
        require(inputOrigin == AgentInputOrigin.USER || imagePaths.isEmpty() && media.isEmpty()) { "Synthetic inputs cannot attach user media" }
        SubagentDeliveryHistory.boundReceipt(inputOrigin, sourceId, requiredSessionId ?: currentSessionId, sourceDelivery)
        

        val runLease = admissionLease ?: promptCoordinator.reserveDirectRun()
        // Recheck state around cancellation-sensitive boundaries.

        val admission = synchronized(admissionLock) {
            if (!exitFence.isOpen || mediaRecoveryClosed || loadingSessionGeneration != null) null
            else {
                cancelContextCompaction()
                Triple(runGeneration.incrementAndGet(), currentSessionId, conversationHistory.beginRestore())
            }
        } ?: run {
            withContext(NonCancellable) { promptCoordinator.markRunInactive(runLease) }
            throw CancellationException("Agent session is loading or closed")
        }
        val (generation, initialSessionId, admittedHistoryVersion) = admission
        val creatingSession = initialSessionId == null
        var greetingOnly = AgentRequestScope.initialGreetingOnly(
            text = userText,
            newConversation = creatingSession,
            userInitiated = inputOrigin == AgentInputOrigin.USER && subagentDepth == 0 && systemOverlay.isBlank(),
            hasAttachments = imagePaths.isNotEmpty() || media.isNotEmpty() || !preparedImages.isNullOrEmpty(),
            hasPendingWork = pendingInputs.value.isNotEmpty(),
        )
        val owner = try {
            if (requiredSessionId != null && initialSessionId != requiredSessionId) {
                throw CancellationException("Target parent session is no longer active")
            }
            if (initialSessionId == null) {
                val created = sessions.create(title = userText.take(40), parentId = null)
                publicationBoundaryProbe?.invoke(PublicationBoundary.CREATED_SESSION)
                synchronized(admissionLock) {
                    if (!exitFence.isOpen || mediaRecoveryClosed || loadingSessionGeneration != null ||
                        runGeneration.get() != generation || currentSessionId != null)
                        throw CancellationException("Agent session changed")
                    currentSessionId = created.id
                    textEditEvidence = SessionTextEditEvidence.Evidence(created.id, created.createdAt, emptyList(), emptyList())
                    conversationHistory.resetNew()
                    typedCheckpoint = null
                    subagentBridge.bindParent(created.id)
                    RunPublicationOwner(generation, created.id, conversationHistory.ownershipVersion)
                }
            } else synchronized(admissionLock) {
                ensureRunCurrent(generation, initialSessionId)
                RunPublicationOwner(generation, initialSessionId, admittedHistoryVersion)
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) { promptCoordinator.markRunInactive(runLease) }
            throw t
        }
        val runSessionId = owner.sessionId
        val stagedImages = try {
            val images = preparedImages ?: if (media.isEmpty() && imagePaths.isEmpty()) emptyList() else run {
                require(media.none { it.mediaType.startsWith("image/") || it.mediaType.startsWith("video/") } ||
                    VisionSupport.providerAcceptsImageUrl(config)) { "The selected model does not accept images" }
                val prepared = ChatMediaProcessing.prepareModelImages(workDir, media, runSessionId)
                VisionSupport.stage(workDir, imagePaths + prepared.imagePaths, config, ownerId = runSessionId)
            }
            if (media.isNotEmpty() || images.isNotEmpty()) withContext(Dispatchers.IO) {
                val store = ChatMediaStore(workDir)
                store.retain(runSessionId, media + images.mapNotNull { it.mediaId?.let(store::ref) })
            }
            images
        } catch (failure: Throwable) {
            withContext(NonCancellable) { promptCoordinator.markRunInactive(runLease) }
            throw failure
        }
        if (!promptCoordinator.markRunReady(runLease)) {
            throw CancellationException("Agent admission lease expired")
        }
        publicationBoundaryProbe?.invoke(PublicationBoundary.INITIAL_STREAM)
        // One durable request owner and one replay budget for this admitted run.
        val mediaRunId = java.util.UUID.randomUUID().toString()
        val mediaCopyAttempts = MediaCopyAttemptLedger()
        val lspResultPages = AgentLspResultPages()
        val searchResultPages = AgentSearchResultPages()
        withRunPublication(owner) {
            _compactionActivity.value = null
            _streamState.value = AgentStreamState(
                runId = generation,
                sessionId = runSessionId,
                status = AgentStreamState.Status.BUSY,
                providerId = config.providerId,
                modelId = modelId,
                baseMessageCount = _messages.value.size,
                startedAtMs = System.currentTimeMillis(),
            )
            _busy.value = true
        }


        val unresolvedApiToolCalls = linkedMapOf<String, String>()
        val mixedSourceCalls = linkedMapOf<String, SourceCallLifecycle>()
        val interruptedSourceResults = linkedMapOf<String, JsonObject>()
        val unknownSourceExecutions = linkedSetOf<String>()
        suspend fun checkpointMixedInterrupted(failure: Throwable) {
            for ((id, source) in mixedSourceCalls) {
                try {
                    val result = source.checkpointInterrupted()
                    if (result != null) interruptedSourceResults[id] = result else unknownSourceExecutions += id
                } catch (checkpointFailure: Exception) {
                    unknownSourceExecutions += id
                    if (checkpointFailure !== failure) failure.addSuppressed(checkpointFailure)
                }
            }
        }
        var foreground: DevelopmentForegroundService.Handle? = null
        try {
            if (!typedHistoryHasCompleteTail(owner)) {
                if (inputOrigin == AgentInputOrigin.USER) withRunPublication(owner) {
                    // Preserve the user's query/attachments for review without corrupting the
                    // unfinished private protocol group or treating this as a provider admission.
                    _messages.value = _messages.value + AgentMessage("user", userText, media = media)
                }
                throw IllegalStateException("An earlier tool execution is unresolved. Check the affected command or files before resending. Your new message is kept here and was not sent to the model.")
            }
            appContext?.let { context ->
                val owner = currentCoroutineContext().job
                foreground = checkNotNull(DevelopmentForegroundService.startWithoutCpu(context, "Agent task") { owner.cancel() }) {
                    "Android did not allow the required foreground Agent task to start"
                }
            }
            val resumeTrigger = withRunPublication(owner) {
                pendingSessionStartTrigger.also { pendingSessionStartTrigger = null }
            }
            val promptHook = lifecycleHooks.beforePrompt(creatingSession, resumeTrigger, userText,
                owner = captureHookAuthority(generation, runSessionId))
            if (promptHook.blocked) {
                withRunPublication(owner) {
                    _messages.value = _messages.value + AgentMessage("assistant", "Prompt blocked by project hook: ${promptHook.reason}")
                }
                return
            }
            
            val toolSelection = AgentToolSelection()
            val promptHookContext = promptHook.additionalContext.takeIf { it.isNotBlank() }
                ?.let { "\n\n[Lifecycle hook context]\n$it" }.orEmpty()
            ensureRunCurrent(generation, runSessionId)
            

            val uiHistory = withRunPublication(owner) { _messages.value.toMutableList() }
            val initialProjection = subagentBridge.projectInitial(inputOrigin, sourceId, userText, generation, stagedImages, ideContext, media,
                parentSessionId = runSessionId, sourceDelivery = sourceDelivery)
            uiHistory += initialProjection.messages
            appendOwnedHistory(owner, initialProjection.apiEntries)
            stampTypedProjectionTail(uiHistory, initialProjection.messages.size, initialProjection.apiEntries, owner)
            val recoveryHistory = withRunPublication(owner) {
                typedCheckpoint?.exact?.filterIsInstance<TypedChatEntry.Legacy>()?.map { it.message } ?: _apiHistory.toList()
            }
            val recoveredProjection = subagentBridge.recover(runSessionId, recoveryHistory)
            ensureRunCurrent(generation, runSessionId)
            uiHistory += recoveredProjection.messages
            appendOwnedHistory(owner, recoveredProjection.apiEntries)
            stampTypedProjectionTail(uiHistory, recoveredProjection.messages.size, recoveredProjection.apiEntries, owner)
            publishUiHistory(uiHistory, owner)
            synchronized(admissionLock) {
                exitInputId?.let { historyAdmittedExitInputs[it] = generation }
            }
            if (media.isNotEmpty() || stagedImages.isNotEmpty()) {
                check(withContext(NonCancellable) { persistCurrent(userText.take(40), runSessionId, generation) }) {
                    "The attachment prompt could not be saved; the draft was kept"
                }
                mediaReady?.complete(Unit)
                appContext?.let { context -> ChatMediaCleanup.schedule(context, workDir) }
            }
            withRunPublication(owner) {
                _streamState.value = _streamState.value.copy(baseMessageCount = _messages.value.size)
            }
            val initialDeliveries = (initialProjection.deliveryReceipts + recoveredProjection.deliveryReceipts).distinct()
            if (initialDeliveries.isNotEmpty()) {
                greetingOnly = false
                // Newly delivered recovered/synthetic work is not a fresh human copy request.
                mediaAdmission.includePromoted(null)
            }
            if (initialDeliveries.isNotEmpty()) {
                // Parent persistence must win before the child is marked delivered.


                val persisted = withContext(NonCancellable) {
                    persistCurrent(userText.take(40), runSessionId, generation)
                }
                if (persisted) subagentBridge.markDelivered(initialDeliveries)
            }
            // Keep the model/tool loop in its own suspend method. Inlining the whole
            // admission, loop and cleanup into chat exceeds the JVM's 64 KiB method limit.
            // A local suspend function retains this run's owners without creating a new Job.
            suspend fun runModelSteps() {
            var lastSig = ""
            var repeat = 0
            var activeConfig = config
            var modelStepsInSegment = 0
            var turn = 0
            while (true) {
                exitFence.requireOpen()
                currentCoroutineContext().ensureActive()
                ensureRunCurrent(generation, runSessionId)
                conversationHistory.ensureStorageAvailable(owner.historyVersion)
                promotePendingAtBoundary(
                    runLease = runLease,
                    allowQueue = false,
                    uiHistory = uiHistory,
                    activeConfig = activeConfig,
                    runSessionId = runSessionId,
                    generation = generation,
                    step = turn + 1,
                    mediaAdmission = mediaAdmission, owner = owner,
                )?.let { promotedConfig ->
                    activeConfig = promotedConfig
                    modelStepsInSegment = 0
                    // Steering/queued work supplies fresh task intent, even when the run began with "hi".
                    greetingOnly = false
                }
                turn += 1
                modelStepsInSegment += 1
                

                ProviderRuntimeGuard.requireCertifiedSelection(activeConfig.providerId, activeConfig.baseUrl, activeConfig.model, providerConfiguration)
                val stepBudgetReached = modelStepsInSegment == maxTurns
                val finalStep = stepBudgetReached || !ModelCapabilityRegistry.allowsToolUse(activeConfig.providerId, activeConfig.model)
                // Context compaction happens at this safe model-step boundary and never consumes an additional agent step.


                var preflightCompacted = false
                var overflowRecovered = false
                var reqJson: JsonObject
                var resp: String
                var ownedTurn: OwnedServiceTurn? = null
                var privateSmallTurn: PrivateServiceAssistant? = null
                var mixedPrepared: List<PreparedToolCall>? = null
                var mixedPublicAnswer: AgentPublicAnswerPublication.Result? = null
                var privateAssistantRecorded = false
                var streamMode = LlmStreamMode.NATIVE
                var stepAccounting = TokenUsage()
                val stepId = "step:$turn"
                val publicPresentationId = "public:${java.util.UUID.randomUUID()}"
                withRunPublication(owner) {
                    _streamState.value = _streamState.value.copy(status = AgentStreamState.Status.BUSY, activeStep = turn, modelActivity = AgentModelActivity.WORKING)
                    upsertStreamPart(
                        AgentStreamPart(
                            id = stepId,
                            step = turn,
                            kind = AgentStreamPartKind.STEP,
                            state = AgentStreamPartState.RUNNING,
                            title = if (finalStep) "Final response" else "Step $turn",
                            detail = "${activeConfig.model} · ${activeConfig.providerId.ifBlank { "provider" }}",
                        )
                    )
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    ensureRunCurrent(generation, runSessionId)
                    // Fresh rules for every request attempt, including post-compaction retries.
                    // Reuse hook output without re-running hooks or accumulating their context.
                    val projectBrief = AgentRequestInstructions.refresh(promptHookContext,
                        ensureCurrent = { ensureRunCurrent(generation, runSessionId) },
                        load = { loadProjectBrief() })
                    val requestMode = _mode.value
                    val requestPermissionScope = AgentPermissionScope.id(requestMode, requestProvenance())
                    val modeInstruction = AgentRequestScope.modeInstruction(requestMode)
                    val toolDefinitions = if (finalStep || greetingOnly) null else {
                        val pluginSurface = AgentPluginToolSurfaceFactory.create(workDir, agentPluginSource, perms, requestPermissionScope, false, mcpProcessHost)
                        val available = AgentTools.definitionsWithCustom(
                            skillManagement.manager(), CustomToolManager(workDir), allowSubagents = subagentManager != null, mode = requestMode,
                            permissions = perms, permissionScope = requestPermissionScope, subagentProfiles = subagentManager?.profileSummaries().orEmpty(),
                            mcpTools = pluginSurface.mcpTools, rules = pluginSurface.rules, pluginTools = pluginSurface.pluginTools,
                            ideAvailable = ideActions != null, lspEditAvailable = lspEditActions != null,
                            toolchainAvailable = toolchainActions != null, capabilityAvailable = capabilityActions != null,
                            browserAvailable = browserController != null,
                            commandSessionsAvailable = true, visionAvailable = VisionSupport.providerAcceptsImageUrl(activeConfig),
                            mediaAvailable = appContext != null,
                            generationAvailable = appContext != null,
                            handbookAvailable = appContext != null, authoringAvailable = appContext != null, localArchiveAvailable = localFolderRecoveryCache() != null)
                        toolSelection.project(available, perms.toolAllowed("tool_search") &&
                            perms.actionVisible("read", requestPermissionScope),
                            AgentToolSelection.extraBudget(ContextBudget.effectiveInputTokens(activeConfig.providerId,
                                activeConfig.model, AgentRequestSupport.outputTokens(activeConfig))))
                    }
                    // Discovery can suspend; discard an obsolete mode surface before any request.
                    ensureRunCurrent(generation, runSessionId)
                    if (_mode.value != requestMode) continue
                    val systemText = AgentRuntimePrompt.compose(requestMode, modeInstruction, projectBrief, systemOverlay,
                        finalStep, toolDefinitions, greetingOnly, stepBudgetReached = stepBudgetReached)
                    val typedRequest = withRunPublication(owner) { typedCheckpoint }
                    val activeHistory = withRunPublication(owner) {
                        if (typedRequest == null) conversationHistory.strictActiveForProvider() else emptyList()
                    }
                    val estimate = llm.estimateContext(systemText, activeHistory, toolDefinitions, activeConfig.providerId, activeConfig.baseUrl, activeConfig.model,
                        AgentRequestSupport.outputTokens(activeConfig))
                    val calibrated = AgentCompactionBudget.calibrated(estimate, _contextUsage.value, runSessionId,
                        activeConfig.providerId, activeConfig.model)
                    if (typedRequest == null && calibrated.shouldCompact && !preflightCompacted) {
                        val compactId = "compaction:$turn:preflight"
                        withRunPublication(owner) {
                            upsertStreamPart(
                                AgentStreamPart(
                                    id = compactId,
                                    step = turn,
                                    kind = AgentStreamPartKind.COMPACTION,
                                    state = AgentStreamPartState.RUNNING,
                                    title = "Compacting context",
                                    detail = "Preserving complete recent conversation groups",
                                )
                            )
                        }
                        val changed = compactActiveContext(activeConfig, owner, overflowRecovery = false,
                            systemText = systemText, tools = toolDefinitions)
                        withRunPublication(owner) {
                            updateStreamPart(compactId) {
                                it.copy(
                                    state = AgentStreamPartState.COMPLETED,
                                    detail = if (changed) "Context compacted" else "No compaction required",
                                    endedAtMs = System.currentTimeMillis(),
                                )
                            }
                            streamPart(compactId)?.let {
                                uiHistory += AgentMessage("assistant", "", streamPart = it)
                                publishUiHistory(uiHistory, owner)
                            }
                        }
                        preflightCompacted = true
                        // Even a no-op compaction may suspend while instructions change.
                        // Re-read once; the flag prevents another preflight compaction here.
                        continue
                    }
                    ProviderRuntimeGuard.requireCertifiedSelection(activeConfig.providerId, activeConfig.baseUrl, activeConfig.model, providerConfiguration)
                    reqJson = AgentRequestSupport.build(activeConfig, systemText,
                        VisionSupport.materializeHistory(activeHistory, workDir), toolDefinitions)
                    // Materializing attachments can suspend too. Rebuild on a mode change.
                    ensureRunCurrent(generation, runSessionId)
                    if (_mode.value != requestMode) continue
                    val requestContext = AgentContextUsage(runSessionId, activeConfig.providerId, activeConfig.model, estimate)
                    withRunPublication(owner) { _contextUsage.value = if (typedRequest == null) requestContext else null }
                    val streamedText = StringBuilder()
                    val textPartId = "text:$turn"
                    val requestAccounting = ProviderRequestAccounting(activeConfig.providerId) { sample ->
                        tokens.addSample(activeConfig.model, sample)
                        publishOwnedStream(generation, runSessionId) {
                            if (_contextUsage.value === requestContext) _contextUsage.value = requestContext.withReport(sample)
                        }
                    }
                    var responseAccounted = false
                    try {
                        exitFence.requireOpen()
                        val onProviderEvent: suspend (LlmStreamEvent) -> Unit = { event ->
                            publicationBoundaryProbe?.invoke(PublicationBoundary.PROVIDER_EVENT)
                            publishOwnedStream(generation, runSessionId) {
                                when (event) {
                                    LlmStreamEvent.Thinking -> modelActivity(AgentModelActivity.THINKING)
                                    is LlmStreamEvent.RetryScheduled -> {
                                        val untilMs = System.currentTimeMillis() + event.delayMs
                                        _streamState.value = _streamState.value.copy(
                                            status = AgentStreamState.Status.RETRYING,
                                            retryAttempt = event.attempt,
                                            retryAtMs = untilMs,
                                        )
                                        upsertStreamPart(
                                            AgentStreamPart(
                                                id = "retry:$turn:provider",
                                                step = turn,
                                                kind = AgentStreamPartKind.RETRY,
                                                state = AgentStreamPartState.RUNNING,
                                                title = "Retrying provider request",
                                                detail = "HTTP ${event.statusCode} · retry ${event.attempt}/${ProviderRetryPolicy.MAX_RETRIES}",
                                                attempt = event.attempt,
                                                nextRetryAtMs = untilMs,
                                            )
                                        )
                                    }
                                    is LlmStreamEvent.TextDelta -> {
                                        if (_streamState.value.transport == AgentStreamTransport.UNKNOWN) {
                                            _streamState.value = _streamState.value.copy(transport = AgentStreamTransport.NATIVE)
                                        }
                                        val remaining = MAX_ASSISTANT_CHARS - streamedText.length
                                        if (remaining > 0) {
                                            val accepted = event.text.take(remaining)
                                            streamedText.append(accepted)
                                            appendTextPart(textPartId, turn, accepted, buffered = false, phase = event.phase, presentationId = publicPresentationId)
                                        }
                                    }
                                    is LlmStreamEvent.BufferedResponse -> {
                                        _streamState.value = _streamState.value.copy(transport = AgentStreamTransport.BUFFERED)
                                        val remaining = MAX_ASSISTANT_CHARS - streamedText.length
                                        if (remaining > 0) {
                                            val accepted = event.text.take(remaining)
                                            streamedText.append(accepted)
                                            appendTextPart(textPartId, turn, accepted, buffered = true, presentationId = publicPresentationId)
                                        }
                                    }
                                    is LlmStreamEvent.ToolCallDelta -> {
                                        if (_streamState.value.transport == AgentStreamTransport.UNKNOWN) {
                                            _streamState.value = _streamState.value.copy(transport = AgentStreamTransport.NATIVE)
                                        }
                                        applyToolCallDelta(turn, event)
                                    }
                                }
                            }
                        }
                        val target = llm.ownedChatTarget(activeConfig.providerId, activeConfig.baseUrl, activeConfig.model)
                        check(typedRequest == null || target == typedRequest.target.runtime()) {
                            "This source-backed conversation requires its original provider, model and endpoint; no legacy projection was sent"
                        }
                        val streamResult = if (target == null) {
                            TypedChatHistory.requireLegacy(typedRequest)
                            llm.chatCompletionsStream(activeConfig.providerId, activeConfig.baseUrl, activeConfig.apiKey,
                                activeConfig.model, reqJson.toString(), onProviderEvent)
                        } else {
                            val task = currentCoroutineContext()
                            val checkCurrent = { task.ensureActive(); ensureRunCurrent(generation, runSessionId) }
                            val resources = sessions.typedChatResources(checkCurrent)
                            val responseOwner = OwnedResponseContentStore.Owner(runSessionId, java.util.UUID.randomUUID().toString(), "response")
                            val responseSession = resources.owned.authorize(responseOwner)
                            val history = if (typedRequest == null) {
                                reqJson.getValue("messages").jsonArray.map { SourceAwareChatHistoryWriter.Item.Legacy(it.jsonObject) }
                            } else {
                                val canonical = withContext(Dispatchers.IO) {
                                    resources.history.validateCompleteRequest(typedRequest, runSessionId, target, checkCurrent)
                                }
                                val materialized = canonical.map { item -> when (item) {
                                    is SourceAwareChatHistoryWriter.Item.Legacy -> SourceAwareChatHistoryWriter.Item.Legacy(
                                        VisionSupport.materializeHistory(listOf(item.message), workDir).single())
                                    is SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup -> item
                                    is SourceAwareChatHistoryWriter.Item.OwnedAssistant -> item
                                } }
                                listOf(SourceAwareChatHistoryWriter.Item.Legacy(reqJson.getValue("messages").jsonArray.first().jsonObject)) + materialized
                            }
                            val accepted = llm.streamOwnedChat(activeConfig.providerId, activeConfig.baseUrl, activeConfig.apiKey,
                                activeConfig.model, JsonObject(reqJson.filterKeys { it != "messages" }), history,
                                responseSession, { id -> resources.owned.authorize(responseOwner.copy(callId = id)) },
                                onRecovery = { recovery -> receivedRecoveryCheckpoint(resources, responseOwner, target, recovery) }, onEvent = onProviderEvent)
                            // Provider usage belongs to this completed request even if later projection or owner admission fails.
                            stepAccounting = requestAccounting.response(buildJsonObject {
                                accepted.usage?.let { put("usage", it) }
                                accepted.reportedModel?.let { put("model", it) }
                            }.toString())
                            responseAccounted = true
                            checkCurrent()
                            val needsOwned = accepted.calls.any { it.arguments.utf16Units > MAX_TOOL_ARGUMENT_CHARS } ||
                                (accepted.calls.isEmpty() && (typedRequest != null || accepted.publicText.utf16Units > MAX_ASSISTANT_CHARS))
                            if (needsOwned && accepted.calls.isNotEmpty()) {
                                require(!finalStep && !greetingOnly && accepted.calls.size <= MAX_TOOL_CALLS_PER_TURN)
                                val received = OwnedServiceTurn(accepted, resources, responseOwner, responseSession)
                                // Ownership lives outside the dispatcher: prompt cancellation may
                                // discard a successful IO return before the caller receives its value.
                                val derived = mutableListOf<OwnedChatStreamAccumulator.ReceivedStage>()
                                try {
                                    mixedPrepared = withContext(Dispatchers.IO) {
                                        val preparedBatch = MixedOwnedToolPlanner.prepare(accepted,
                                            { id -> resources.owned.authorize(responseOwner.copy(callId = id)) }, json,
                                            turn, MAX_TOOL_ARGUMENT_CHARS, AgentToolRequestPolicy.snapshot(reqJson), lastSig, repeat, checkCurrent,
                                            onArtifactRecovery = { id, handles -> derived += handles.map { OwnedChatStreamAccumulator.ReceivedStage(id,it) } })
                                        publicationBoundaryProbe?.invoke(PublicationBoundary.SOURCE_PREFLIGHT_RETURN)
                                        preparedBatch
                                    }
                                } catch (failure: Exception) {
                                    // Informational private references only, including a successful
                                    // preparation whose return was cancelled. No execution grant.
                                    val original = accepted.calls.map { OwnedChatStreamAccumulator.ReceivedStage(it.id,it.arguments.handle) } +
                                        (accepted.auxiliarySources + accepted.publicText + accepted.nativeAssistantMessage).map {
                                            OwnedChatStreamAccumulator.ReceivedStage(null,it.handle)
                                        }
                                    try {
                                        withContext(NonCancellable + Dispatchers.IO) {
                                            receivedRecoveryCheckpoint(resources,responseOwner,target,OwnedChatStreamAccumulator.Recovery(
                                                emptyList(),(original+derived).distinctBy { it.handle.serialized }))
                                        }
                                    } catch (checkpointFailure: Exception) { failure.addSuppressed(checkpointFailure) }
                                    throw failure
                                }
                                // All input schemas validate before any journal or workspace effect.
                                withContext(Dispatchers.IO) {
                                    val sourcePending = checkNotNull(mixedPrepared).mapNotNull { call ->
                                        when (val input = call.input) {
                                            is PreparedToolInput.SourceFile -> resources.journal.recordCompleted(responseOwner.copy(callId = call.id), call.name, input.original).also { pending ->
                                                mixedSourceCalls[call.id] = MixedSourceWriteCall(input, resources.journal, pending)
                                            }
                                            is PreparedToolInput.SourceCommand -> resources.journal.recordCompletedCommand(responseOwner.copy(callId=call.id),call.name,input.original).also {pending ->
                                                mixedSourceCalls[call.id]=SourceCommandCall(input,resources.journal,pending)
                                            }
                                            is PreparedToolInput.SourceStdin -> resources.journal.recordCompletedStdin(responseOwner.copy(callId = call.id), input.original).also { pending ->
                                                mixedSourceCalls[call.id] = SourceStdinCall(input, resources.journal, pending)
                                            }
                                            is PreparedToolInput.SourcePatch -> resources.journal.recordCompletedPatch(responseOwner.copy(callId = call.id), input.original).also { pending ->
                                                mixedSourceCalls[call.id] = SourcePatchCall(input, resources.journal, pending)
                                            }
                                            is PreparedToolInput.SourceArtifact -> resources.journal.recordCompletedArtifact(responseOwner.copy(callId = call.id), input.original).also { pending ->
                                                mixedSourceCalls[call.id] = SourceArtifactCall(input, resources.journal, pending)
                                            }
                                            is PreparedToolInput.Legacy -> null
                                        }
                                    }
                                    val id = resources.nativeAssistants.recordAccepted(accepted, responseOwner, sourcePending)
                                    privateSmallTurn = PrivateServiceAssistant(received, id)
                                    val reference = AgentPublicContentRef(opaqueHandle = accepted.publicText.handle.serialized, runId = responseOwner.runId,
                                        itemId = responseOwner.callId, bytes = accepted.publicText.bytes, utf16Chars = accepted.publicText.utf16Units,
                                        sha256 = accepted.publicText.sha256, complete = true)
                                    val public = sessions.publicContentArchive(runSessionId) { !task.isActive || !isRunCurrent(generation, runSessionId) }.reopen(reference)
                                    mixedPublicAnswer = AgentPublicAnswerPublication.Result(public.preview, reference)
                                }
                                // Control-flow metadata only. It contains NO argument placeholder; execution and
                                // durable provider history exclusively use the typed batch/native source above.
                                LlmStreamResult(buildJsonObject {
                                    accepted.usage?.let { put("usage", it) }
                                    put("choices", buildJsonArray { add(buildJsonObject {
                                        put("finish_reason", "tool_calls")
                                        put("message", buildJsonObject {
                                            put("role", "assistant"); put("content", checkNotNull(mixedPublicAnswer).display)
                                            put("tool_calls", buildJsonArray { accepted.calls.forEach { call -> add(buildJsonObject {
                                                put("id", call.id); put("type", "function")
                                                put("function", buildJsonObject { put("name", call.name) })
                                            }) } })
                                        })
                                    }) })
                                }.toString(), LlmStreamMode.NATIVE)
                            } else if (needsOwned) {
                                ownedTurn = OwnedServiceTurn(accepted, resources, responseOwner, responseSession)
                                // This metadata-only accounting envelope is never a history or execution input.
                                LlmStreamResult(buildJsonObject { accepted.usage?.let { put("usage", it) } }.toString(), LlmStreamMode.NATIVE)
                            } else {
                                val provider = checkNotNull(ProviderRegistry.findById(activeConfig.providerId, providerConfiguration))
                                val projection = withContext(Dispatchers.IO) {
                                    if (accepted.hasPrivateContinuation) {
                                        val id = resources.nativeAssistants.recordAccepted(accepted, responseOwner)
                                        val bound = resources.nativeAssistants.reopen(id, responseOwner, accepted.target)
                                        privateSmallTurn = PrivateServiceAssistant(OwnedServiceTurn(accepted, resources, responseOwner, responseSession), id)
                                        OwnedChatLegacyProjection.publicResponse(accepted, bound, provider,
                                            { id -> resources.owned.authorize(responseOwner.copy(callId = id)) }, checkCurrent)
                                    } else OwnedChatLegacyProjection.response(accepted, responseSession, provider, checkCancelled = checkCurrent)
                                } ?: error("This tool turn exceeds the bounded legacy route and is not a supported single streamed write; no tool was executed")
                                LlmStreamResult(projection, LlmStreamMode.NATIVE)
                            }
                        }
                        resp = streamResult.responseBody
                        streamMode = streamResult.mode
                        // The provider work happened even if its owner is now obsolete.
                        // Record the returned usage once, independently of preview admission.
                        // Accounting consumes provider metadata, not a second serialization
                        // of the potentially multimodal request body.
                        if (!responseAccounted) stepAccounting = requestAccounting.response(resp)
                        publicationBoundaryProbe?.invoke(PublicationBoundary.PROVIDER_RESULT)
                        withRunPublication(owner) {
                            _streamState.value = _streamState.value.copy(
                                status = AgentStreamState.Status.BUSY,
                                retryAttempt = 0,
                                retryAtMs = null,
                                modelActivity = AgentModelActivity.WORKING,
                                transport = if (streamResult.mode == LlmStreamMode.NATIVE) AgentStreamTransport.NATIVE else AgentStreamTransport.BUFFERED,
                            )
                            streamPart("retry:$turn:provider")?.let { retry ->
                                if (retry.state == AgentStreamPartState.RUNNING) {
                                    updateStreamPart(retry.id) {
                                        it.copy(state = AgentStreamPartState.COMPLETED,
                                            detail = "Provider request recovered",
                                            nextRetryAtMs = null,
                                            endedAtMs = System.currentTimeMillis())
                                    }
                                }
                            }
                        }
                        break
                    } catch (t: Throwable) {
                        // Accounting is independent of whether this run may still publish UI/history.
                        requestAccounting.failure(t)?.let { stepAccounting = it }
                        if (requestAccounting.isCallbackFailure(t)) throw t
                        val failureSnapshot = (t as? LlmProviderStreamException)?.publicSnapshot
                        val overflow = t is LlmRequestTooLargeException || (t is LlmHttpException && t.isContextOverflow())
                        if (!overflow || overflowRecovered) {
                            // Public partial text must survive persistence and the next
                            // user-directed continuation in this same session. Streamed
                            // tool/secret continuation fragments are never admitted here.
                            val partial = synchronized(admissionLock) {
                                if (!isRunCurrent(generation, runSessionId)) null else {
                                    val part = if (failureSnapshot != null) {
                                        // Full emitted-order snapshot is already credential-redacted.
                                        // Replace, never append to the possibly truncated live preview.
                                        AgentStreamPart(id = textPartId, step = turn,
                                            kind = AgentStreamPartKind.TEXT, state = AgentStreamPartState.RUNNING,
                                            text = failureSnapshot.publicText.take(MAX_ASSISTANT_CHARS),
                                            textPresentation = AgentTextPresentation.RESPONSE, presentationId = publicPresentationId)
                                    } else streamPart(textPartId)
                                    InterruptedAgentStream.snapshot(part, t is CancellationException, generation)
                                        ?.takeIf { candidate -> _messages.value.none { it.streamPart?.id == candidate.message.streamPart?.id } }
                                }
                            }
                            if (partial != null || failureSnapshot != null) try {
                                withRunPublication(owner) {
                                    // An empty safe snapshot also replaces an unsafe live preview.
                                    if (failureSnapshot != null) removeStreamPart(textPartId)
                                    if (partial != null) _messages.value = _messages.value + partial.message
                                }
                                if (partial != null) {
                                    appendOwnedHistory(owner, partial.apiEntry)
                                    stampTypedPublishedTail(owner)
                                }
                                withRunPublication(owner) { removeStreamPart(textPartId) }
                            } catch (obsolete: CancellationException) {
                                if (isRunCurrent(generation, runSessionId)) throw obsolete
                                // Revocation during append must not replace the originating failure.
                                if (obsolete !== t) t.addSuppressed(obsolete)
                            }
                            throw t
                        }
                        val retryId = "retry:$turn:context"
                        val compactId = "compaction:$turn:overflow"
                        val retryAt = System.currentTimeMillis()
                        withRunPublication(owner) {
                            _streamState.value = _streamState.value.copy(
                                status = AgentStreamState.Status.RETRYING,
                                activeStep = turn,
                                retryAttempt = 1,
                                retryAtMs = retryAt,
                            )
                            upsertStreamPart(
                                AgentStreamPart(
                                    id = retryId,
                                    step = turn,
                                    kind = AgentStreamPartKind.RETRY,
                                    state = AgentStreamPartState.RUNNING,
                                    title = "Retrying model step",
                                    detail = "Context overflow · compacting before retry",
                                    attempt = 1,
                                    nextRetryAtMs = retryAt,
                                )
                            )
                            upsertStreamPart(
                                AgentStreamPart(
                                    id = compactId,
                                    step = turn,
                                    kind = AgentStreamPartKind.COMPACTION,
                                    state = AgentStreamPartState.RUNNING,
                                    title = "Recovering context overflow",
                                    detail = "Compacting active history without consuming another agent step",
                                )
                            )
                            removeStreamPart(textPartId)
                        }
                        val changed = compactActiveContext(activeConfig, owner, overflowRecovery = true,
                            systemText = systemText, tools = toolDefinitions)
                        withRunPublication(owner) {
                            updateStreamPart(compactId) {
                                it.copy(
                                    state = if (changed) AgentStreamPartState.COMPLETED else AgentStreamPartState.ERROR,
                                    detail = if (changed) "Context compacted" else "Unable to compact further",
                                    endedAtMs = System.currentTimeMillis(),
                                )
                            }
                            streamPart(compactId)?.let {
                                uiHistory += AgentMessage("assistant", "", streamPart = it)
                                publishUiHistory(uiHistory, owner)
                            }
                        }
                        overflowRecovered = true
                        if (!changed) throw t
                        withRunPublication(owner) {
                            updateStreamPart(retryId) { it.copy(detail = "Retrying now") }
                            _streamState.value = _streamState.value.copy(
                                status = AgentStreamState.Status.BUSY,
                                activeStep = turn,
                                retryAtMs = null,
                                modelActivity = AgentModelActivity.WORKING,
                            )
                        }
                        
                    }
                }


                ownedTurn?.let { received ->
                    val accepted = received.result
                    require(!accepted.hasRefusal) { "Provider refused this response; no tool was executed" }
                    check(accepted.calls.isEmpty()) { "Source tool calls must use the unified typed dispatcher" }
                    val task = currentCoroutineContext()
                    val publicRef = AgentPublicContentRef(opaqueHandle = accepted.publicText.handle.serialized,
                        runId = received.responseOwner.runId, itemId = received.responseOwner.callId,
                        bytes = accepted.publicText.bytes, utf16Chars = accepted.publicText.utf16Units,
                        sha256 = accepted.publicText.sha256, complete = true)
                    val publicAnswer = withContext(Dispatchers.IO) {
                        sessions.publicContentArchive(runSessionId) { !task.isActive || !isRunCurrent(generation, runSessionId) }.reopen(publicRef)
                    }
                    appendCanonicalOwnedTurn(owner, received, emptyList(), uiHistory)
                    val boundary = withRunPublication(owner) { TypedChatHistory.protocolWidth(checkNotNull(typedCheckpoint).exact) }
                    if (accepted.hasMeaningfulPublicText) {
                        uiHistory += AgentMessage("assistant", publicAnswer.preview,
                            textPresentation = AgentTextPresentation.RESPONSE,
                            presentationId = publicPresentationId, publicContent = publicRef, forkHistoryEnd = boundary)
                        publishUiHistory(uiHistory, owner)
                    }
                    withRunPublication(owner) { removeStreamPart("text:$turn") }
                    check(persistCurrent(userText.take(40), runSessionId, generation)) { "Source-backed response could not be saved" }
                    withRunPublication(owner) { finishStep(turn, "Response complete") }
                }
                if (ownedTurn != null) {
                    ensureRunCurrent(generation, runSessionId)
                    val stopHook = lifecycleHooks.stop(captureHookAuthority(generation, runSessionId))
                    ensureRunCurrent(generation, runSessionId)
                    if (stopHook.blocked && !finalStep) {
                        classifyPublicResponse(uiHistory, publicPresentationId, "text:$turn", AgentTextPresentation.UPDATE, owner)
                        appendOwnedHistory(owner, buildJsonObject { put("role", "system"); put("content", "Lifecycle Stop hook requested continuation: ${stopHook.reason}\n${stopHook.additionalContext}") })
                        continue
                    }
                    val nextConfig = promotePendingAtBoundary(runLease = runLease, allowQueue = true, uiHistory = uiHistory,
                        activeConfig = activeConfig, runSessionId = runSessionId, generation = generation, step = turn + 1,
                        mediaAdmission = mediaAdmission, owner = owner)
                    if (nextConfig != null) {
                        activeConfig = nextConfig; modelStepsInSegment = 0; greetingOnly = false; continue
                    }
                    break
                }

                // Restore the donor contract: authority is the exact successful request,
                // not a later mode/catalog or a canonically equivalent alias.
                val requestToolNames = AgentToolRequestPolicy.snapshot(reqJson)
                val stepPromptTokens = stepAccounting.prompt
                val stepCompletionTokens = stepAccounting.completion
                val stepTotalTokens = stepAccounting.total
                val stepReasoningTokens = stepAccounting.reasoning
                val stepCacheReadTokens = stepAccounting.cacheRead
                val stepCacheWriteTokens = stepAccounting.cacheWrite
                val stepCostUsd = stepAccounting.costUsd
                val root = json.parseToJsonElement(resp) as? JsonObject
                    ?: throw IllegalStateException("Provider returned non-object JSON")
                val choices = root["choices"] as? JsonArray
                    ?: throw IllegalStateException("Provider response is missing choices")
                val choice = choices.firstOrNull() as? JsonObject
                    ?: throw IllegalStateException("Provider response has no choice")
                val msg = choice["message"] as? JsonObject
                    ?: throw IllegalStateException("Provider response is missing message")
                val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                withRunPublication(owner) {
                    updateStreamPart(stepId) {
                        it.copy(
                            finishReason = finishReason,
                            promptTokens = stepPromptTokens,
                            completionTokens = stepCompletionTokens,
                            totalTokens = stepTotalTokens,
                            reasoningTokens = stepReasoningTokens,
                            cacheReadTokens = stepCacheReadTokens,
                            cacheWriteTokens = stepCacheWriteTokens,
                            costUsd = stepCostUsd,
                            tokenUsageAuthority = stepAccounting.tokenAuthority,
                            costAuthority = stepAccounting.costAuthority,
                        )
                    }
                }
                val fullPublicContent = (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                var content = fullPublicContent.take(MAX_ASSISTANT_CHARS)
                // Bound UI text separately from the complete provider continuation.
                val apiContent = if (msg[OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD] != null ||
                    msg[ChatReasoningContinuation.PRIVATE_CONTENT_FIELD] != null) {
                    (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                } else fullPublicContent.take(MAX_ASSISTANT_CHARS)
                val toolCalls = (msg["tool_calls"] as? JsonArray) ?: JsonArray(emptyList())
                val outcome = AgentResponseOutcome.evaluate(choice)
                if (!outcome.accepted) {
                    // Usage was already recorded. Preserve only public partial text, never
                    // incomplete tool calls or provider-private reasoning/signatures.
                    val publicPartial = outcome.publicText.ifBlank {
                        streamPart("text:$turn")?.takeIf { it.kind == AgentStreamPartKind.TEXT }?.text.orEmpty()
                    }.take(MAX_ASSISTANT_CHARS)
                    if (publicPartial.isNotBlank()) {
                        val part = AgentStreamPart(id = "text:$turn", step = turn,
                            kind = AgentStreamPartKind.TEXT, state = AgentStreamPartState.RUNNING,
                            text = publicPartial, finishReason = finishReason,
                            textPresentation = AgentTextPresentation.RESPONSE, presentationId = publicPresentationId)
                        InterruptedAgentStream.snapshot(part, cancelled = false, runId = generation)?.let { partial ->
                            withRunPublication(owner) { _messages.value = _messages.value + partial.message }
                            appendOwnedHistory(owner, partial.apiEntry)
                            stampTypedPublishedTail(owner)
                            withRunPublication(owner) { removeStreamPart("text:$turn") }
                        }
                    }
                    throw AgentResponseOutcome.Failure(outcome.diagnostic)
                }
                require(toolCalls.isEmpty() || finishReason in setOf("tool_calls", "function_call")) {
                    "Provider returned tools without a completed tool turn (finish reason: ${finishReason.ifBlank { "missing" }}). No tool was executed."
                }
                require(toolCalls.size <= MAX_TOOL_CALLS_PER_TURN) {
                    "Provider requested ${toolCalls.size} tools in one turn; maximum is $MAX_TOOL_CALLS_PER_TURN"
                }
                // Validate the batch before allocating a durable public blob. Final-only
                // turns reject tool requests without handing them to the executor.
                val preparedTools = mixedPrepared ?: if (toolCalls.isNotEmpty() && !finalStep && !greetingOnly)
                    AgentToolTurnPlanner.prepare(toolCalls, json, turn, MAX_TOOL_ARGUMENT_CHARS, lastSig, repeat)
                    else null
                if (privateSmallTurn != null && (toolCalls.isEmpty() || (!finalStep && !greetingOnly))) {
                    appendPrivateNativeAssistant(owner, checkNotNull(privateSmallTurn), uiHistory)
                    privateAssistantRecorded = true
                    // The native source already owns these IDs. Any later failure must close them with ordinary results.
                    unresolvedApiToolCalls.clear()
                    checkNotNull(privateSmallTurn).received.result.calls.forEach { unresolvedApiToolCalls[it.id] = it.name }
                    check(persistCurrent(userText.take(40), runSessionId, generation)) {
                        "Private native turn could not be saved; no tool was executed"
                    }
                }
                // Outcome and structural batch admission must succeed before a
                // public blob is published. Failed provider snapshots retain their existing
                // credential-redacted path; raw failed streams never replace safe snapshots.
                val publicationJob = currentCoroutineContext()[Job]
                val publicAnswer = mixedPublicAnswer ?: withContext(Dispatchers.IO) {
                    AgentPublicAnswerPublication.complete(
                        fullPublicContent, MAX_ASSISTANT_CHARS,
                        archive = { sessions.publicContentArchive(runSessionId) {
                            publicationJob?.isActive == false || !isRunCurrent(generation, runSessionId)
                        } },
                        runId = publicPresentationId, itemId = "text:$turn",
                        checkOwner = { ensureRunCurrent(generation, runSessionId); publicationJob?.ensureActive() },
                    )
                }
                ensureRunCurrent(generation, runSessionId)
                content = publicAnswer.display
                val publicTextSpans = if (ProviderRegistry.findById(activeConfig.providerId, providerConfiguration)?.let {
                    ProviderModelRequestPolicy.protocol(it, activeConfig.model)
                } == ProviderWireProtocol.OPENAI_RESPONSES)
                    OpenAiResponsesAdapter.publicTextSpans(msg, content) else emptyList()
                val publicPresentation = AgentTextPresentation.RESPONSE
                withRunPublication(owner) {
                    updateStreamPart("text:$turn") {
                        it.copy(
                            state = AgentStreamPartState.COMPLETED,
                            textPresentation = publicPresentation,
                            presentationId = publicPresentationId,
                            publicTextSpans = publicTextSpans,
                            text = content.ifBlank { it.text },
                            detail = if (streamMode == LlmStreamMode.NATIVE) "Complete" else "Complete · buffered provider",
                            finishReason = finishReason,
                            endedAtMs = System.currentTimeMillis(),
                        )
                    }
                    updateStreamPart("retry:$turn:context") {
                        it.copy(state = AgentStreamPartState.COMPLETED, detail = "Retry succeeded", endedAtMs = System.currentTimeMillis())
                    }
                    streamPart("retry:$turn:context")?.takeIf { it.state == AgentStreamPartState.COMPLETED }?.let {
                        if (uiHistory.none { message -> message.streamPart?.id == it.id }) {
                            uiHistory += AgentMessage("assistant", "", streamPart = it)
                            publishUiHistory(uiHistory, owner)
                        }
                    }
                }
                if (toolCalls.isEmpty()) {
                    uiHistory += AgentMessage("assistant", content, textPresentation = publicPresentation,
                        presentationId = publicPresentationId, publicTextSpans = publicTextSpans, publicContent = publicAnswer.reference)
                    publishUiHistory(uiHistory, owner)
                    publicationBoundaryProbe?.invoke(PublicationBoundary.ASSISTANT_HISTORY_APPEND)
                    if (!privateAssistantRecorded) appendOwnedHistory(owner, buildJsonObject {
                        put("role", "assistant"); put("content", apiContent)
                        msg[OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD]?.let { put(OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD, it) }
                        msg[ChatReasoningContinuation.PRIVATE_CONTENT_FIELD]?.let { put(ChatReasoningContinuation.PRIVATE_CONTENT_FIELD, it) }
                    })
                    stampTypedMessage(uiHistory, uiHistory.lastIndex, owner)
                    publishUiHistory(uiHistory, owner)
                    withRunPublication(owner) {
                        finishStep(turn, "Response complete").let { stepPart ->
                            uiHistory += AgentMessage("assistant", "", streamPart = stepPart)
                            publishUiHistory(uiHistory, owner)
                        }
                    }
                    ensureRunCurrent(generation, runSessionId)
                    val stopHook = lifecycleHooks.stop(captureHookAuthority(generation, runSessionId))
                    ensureRunCurrent(generation, runSessionId)
                    if (stopHook.blocked && !finalStep) {
                        classifyPublicResponse(uiHistory, publicPresentationId, "text:$turn", AgentTextPresentation.UPDATE, owner)
                        appendOwnedHistory(owner, buildJsonObject { put("role", "system"); put("content", "Lifecycle Stop hook requested continuation: ${stopHook.reason}\n${stopHook.additionalContext}") })
                        continue
                    }
                    val nextConfig = promotePendingAtBoundary(
                        runLease = runLease,
                        allowQueue = true,
                        uiHistory = uiHistory,
                        activeConfig = activeConfig,
                        runSessionId = runSessionId,
                        generation = generation,
                        step = turn + 1,
                        mediaAdmission = mediaAdmission, owner = owner,
                        onPromoted = { inputs ->
                            classifyPublicResponse(uiHistory, publicPresentationId, "text:$turn",
                                AgentPublicTextPresentation.afterBoundary(stopHook.blocked,
                                    inputs.any { it.origin == AgentInputOrigin.USER },
                                    inputs.any { it.origin == AgentInputOrigin.SUBAGENT }, promotionAbsent = false), owner)
                        },
                    )
                    if (nextConfig != null) {
                        activeConfig = nextConfig
                        modelStepsInSegment = 0
                        greetingOnly = false
                        continue
                    }
                    classifyPublicResponse(uiHistory, publicPresentationId, "text:$turn",
                        if (stopHook.blocked) AgentTextPresentation.RESPONSE else
                            AgentPublicTextPresentation.afterBoundary(false, false, false, promotionAbsent = true), owner)
                    break
                }
                if (finalStep || greetingOnly) {
                    // Never execute a tool that this text-only response did not advertise.

                    val finalContent = if (fullPublicContent.isNotBlank()) content else run {
                        when {
                            greetingOnly -> "Hi! What would you like help with?"
                            stepBudgetReached -> "The configured agent step limit was reached before a valid final response. No additional tools were executed."
                            else -> "The selected model does not support tools in this session. No tools were executed; choose a tool-capable model for project actions."
                        }
                    }
                    uiHistory += AgentMessage("assistant", finalContent, publicContent = publicAnswer.reference)
                    publishUiHistory(uiHistory, owner)
                    appendOwnedHistory(owner, buildJsonObject { put("role", "assistant"); put("content", if (fullPublicContent.isBlank()) finalContent else apiContent) })
                    stampTypedMessage(uiHistory, uiHistory.lastIndex, owner)
                    publishUiHistory(uiHistory, owner)
                    withRunPublication(owner) {
                        finishStep(turn, "Tool request rejected on text-only response").let { stepPart ->
                            uiHistory += AgentMessage("assistant", "", streamPart = stepPart)
                            publishUiHistory(uiHistory, owner)
                        }
                    }
                    val nextConfig = promotePendingAtBoundary(
                        runLease = runLease,
                        allowQueue = true,
                        uiHistory = uiHistory,
                        activeConfig = activeConfig,
                        runSessionId = runSessionId,
                        generation = generation,
                        step = turn + 1,
                        mediaAdmission = mediaAdmission, owner = owner,
                    )
                    if (nextConfig != null) {
                        activeConfig = nextConfig
                        modelStepsInSegment = 0
                        greetingOnly = false
                        continue
                    }
                    break
                }
                

                // Reject malformed batches before either history owns their tool IDs.
                // Invalid arguments remain failed per-call outcomes in the prepared batch.
                val prepared = requireNotNull(preparedTools)
                withRunPublication(owner) {
                    updateStreamPart("text:$turn") { it.copy(textPresentation = AgentTextPresentation.UPDATE) }
                }
                if (fullPublicContent.isNotBlank() || (mixedPrepared != null && privateSmallTurn?.received?.result?.hasMeaningfulPublicText == true)) {
                    uiHistory += AgentMessage("assistant", content, textPresentation = AgentTextPresentation.UPDATE,
                        presentationId = publicPresentationId, publicTextSpans = publicTextSpans, publicContent = publicAnswer.reference)
                    publishUiHistory(uiHistory, owner)
                }
                val assistantWithTools = buildJsonObject {
                    put("role", "assistant"); put("content", apiContent)
                    put("tool_calls", toolCalls); msg[AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD]?.let { put(AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD, it) }
                    msg[GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD]?.let { put(GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD, it) }
                    msg[OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD]?.let { put(OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD, it) }
                    msg[ChatReasoningContinuation.PRIVATE_CONTENT_FIELD]?.let { put(ChatReasoningContinuation.PRIVATE_CONTENT_FIELD, it) }
                }
                check(mixedPrepared == null || privateAssistantRecorded) { "Mixed metadata cannot become provider history" }
                if (!privateAssistantRecorded) appendOwnedHistory(owner, assistantWithTools)
                unresolvedApiToolCalls.clear()
                prepared.forEach { call ->
                    unresolvedApiToolCalls[call.id] = call.name
                }
                conversationHistory.ensureStorageAvailable(owner.historyVersion)
                val browserCalls = prepared.filter { it.name == "browser" && it.name in requestToolNames }
                // One Stop epoch owns the entire response, including calls that are
                // still queued behind permission prompts or unrelated tools.
                val skippedForUserSteer = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val browserToolWork = if (browserCalls.isEmpty()) emptyList()
                    else browserController?.registerToolWorkBatch(browserCalls.size).orEmpty()
                try {
                val browserWorkByIndex = browserCalls.mapIndexedNotNull { index, call ->
                    browserToolWork.getOrNull(index)?.let { call.index to it }
                }.toMap()
                fun publishToolPart(id: String, transform: (AgentStreamPart) -> AgentStreamPart) =
                    publishOwnedStream(generation, runSessionId) { update(id, transform) }
                prepared.forEach { call ->
                    publishOwnedStream(generation, runSessionId) {
                        val existingPart = part(call.partId)
                        upsert((existingPart ?: AgentStreamPart(
                            id = call.partId, step = turn, kind = AgentStreamPartKind.TOOL,
                            state = AgentStreamPartState.PENDING,
                        )).copy(
                            state = AgentStreamPartState.PENDING, title = call.name.replace('_', ' '),
                            toolName = call.name, toolCallId = call.id,
                            input = call.displayInput().take(MAX_TOOL_ARGUMENT_CHARS),
                            detail = "Queued · ${call.index + 1}/${prepared.size}",
                        ))
                    }
                }
                val schedulingFacts = AgentToolTurnPlanner.schedulingFacts(
                    prepared,
                    permissionAllows = { access -> perms.decide(access.action, access.resource, permissionScope()) == PermEffect.ALLOW },
                    hasLifecycleHook = { tool -> lifecycleHooks.requiresSerializedTool(tool) },
                )
                val publication = AgentMediaPublication.begin(workDir, runSessionId)
                val toolImages = AgentToolImages()
                val handbookBudget = DroideHandbookBudget()
                val committedImages = AgentToolImages()
                // Receipts belong to one prepared call, never to mutable browser state.
                val browserPublications = java.util.concurrent.ConcurrentHashMap<Int, AgentTools.BrowserPublicationAuthority>()
                suspend fun executePrepared(call: PreparedToolCall): ToolOutcome {
                    exitFence.requireOpen()
                    currentCoroutineContext().ensureActive()
                    // A newer Steer can arrive while the provider is generating this batch.
                    // Close its old calls without executing them, then consume the user's
                    // correction at the normal next model boundary. QUEUE does not preempt.
                    if (pendingInputs.value.any { it.origin == AgentInputOrigin.USER && it.delivery == AgentPromptDelivery.STEER }) {
                        skippedForUserSteer.add(call.id)
                        val reason = "ERROR: USER_STEER_PENDING: This tool was not executed because a newer user instruction is waiting. Re-evaluate the task after that instruction; do not replay this call automatically."
                        val evidence = AgentToolEvidence(AgentToolEvidenceState.DENIED, operationStarted = false,
                            success = false, detail = "Not executed: user steering pending")
                        val result = evidence.annotate(call.name, reason)
                        publishToolPart(call.partId) {
                            it.copy(state = AgentStreamPartState.CANCELLED, detail = "Not executed · user steering pending",
                                output = result, endedAtMs = System.currentTimeMillis())
                        }
                        return ToolOutcome(call, result, failed = true)
                    }
                    
                    val fname = call.name
                    publishToolPart(call.partId) {
                        it.copy(state = AgentStreamPartState.RUNNING, detail = "Running · ${call.index + 1}/${prepared.size}")
                    }
                    val callImages = mutableListOf<AgentImageRef>()
                    val callMedia = mutableListOf<ChatMediaRef>()
                    val ctx = AgentTools.Ctx(
                        files, terminal, git, approvals, questions, editHistory, perms,
                        _mode.value, llm, activeConfig, workDir, todos, runSessionId, lsp, backgroundCommands, subagentManager,
                        provenance = requestProvenance(runSessionId),
                        pluginSource = agentPluginSource, documentAuthority = documentAuthority,
                        activeToolNames = requestToolNames,
                        ideActions = ideActions, lspEditActions = lspEditActions,
                        toolchainActions = toolchainActions, capabilityActions = capabilityActions,
                        browserController = browserController,
                        browserToolWork = browserWorkByIndex[call.index],
                        browserRequestCurrent = { isRunCurrent(generation, runSessionId) },
                        browserRunId = mediaRunId,
                        browserCallId = "${call.index}:${call.id}",
                        mediaRunId = mediaRunId,
                        mediaRequestCurrent = { !mediaRecoveryClosed && isRunCurrent(generation, runSessionId) },
                        mediaRequestAdmittedAtNanos = mediaAdmission.admittedAtNanos,
                        mediaCopyAttempts = mediaCopyAttempts,
                        mcpProcessHost = mcpProcessHost,
                        webResearch = webResearch,
                        onImageReady = { publication.producedImages(it); toolImages.add(it); callImages.addAll(it) },
                        onMediaReady = { ref -> publication.produced(ref); callMedia.add(ref) },
                        appContext = appContext, providerConfiguration = providerConfiguration,
                    ) { progress ->
                        publishToolPart(call.partId) { part ->
                            val mergedOutput = if (progress.outputDelta.isBlank()) part.output else
                                (part.output + progress.outputDelta).takeLast(MAX_STREAM_TOOL_OUTPUT_CHARS)
                            part.copy(
                                title = progress.title.ifBlank { part.title },
                                detail = progress.detail.ifBlank { part.detail },
                                output = mergedOutput,
                                fileChanges = progress.fileChanges ?: part.fileChanges,
                                producedFiles = progress.producedFiles ?: part.producedFiles,
                            )
                        }
                    }
                    ctx.toolSearch = { args ->
                        ensureRunCurrent(generation, runSessionId)
                        toolSelection.search(args)
                    }
                    ctx.handbookBudget = handbookBudget
                    ctx.lspResultPages = lspResultPages
                    ctx.searchResultPages = searchResultPages
                    if (call.input is PreparedToolInput.SourceCommand) {
                        check(privateAssistantRecorded) {"Source execution requires durable native history"}
                        val source=checkNotNull(mixedSourceCalls[call.id] as? SourceCommandCall)
                        val received=checkNotNull(privateSmallTurn).received
                        val hookOwner=captureHookAuthority(generation,runSessionId,ctx.mode,ctx.provenance)
                        val task=currentCoroutineContext()
                        val result=approvals.withApprovalOwner(requireNotNull(hookOwner.approvalAdmission)) {
                            source.execute(ctx,lifecycleHooks,hookOwner,received.responseOwner.copy(callId=call.id),call.repeatOrdinal) {
                                task.ensureActive();exitFence.requireOpen();ensureRunCurrent(generation,runSessionId)
                            }
                        }
                        publishToolPart(call.partId){it.copy(state=if(result.rejected)AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED,
                            output=result.output,detail=AgentToolResultSemantics.classify(call.name,result.output).uiDetail,endedAtMs=System.currentTimeMillis())}
                        return ToolOutcome(call,result.output,result.rejected)
                    }
                    if (call.input is PreparedToolInput.SourceStdin) {
                        check(privateAssistantRecorded) { "Source execution requires durable native history" }
                        val source = checkNotNull(mixedSourceCalls[call.id] as? SourceStdinCall)
                        val received = checkNotNull(privateSmallTurn).received
                        val hookOwner = captureHookAuthority(generation, runSessionId, ctx.mode, ctx.provenance)
                        val task = currentCoroutineContext()
                        val result = approvals.withApprovalOwner(requireNotNull(hookOwner.approvalAdmission)) {
                            source.execute(ctx, lifecycleHooks, hookOwner,
                                received.responseOwner.copy(callId = call.id), call.repeatOrdinal) {
                                task.ensureActive(); exitFence.requireOpen(); ensureRunCurrent(generation, runSessionId)
                            }
                        }
                        publishToolPart(call.partId) { it.copy(
                            state = if (result.rejected) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED,
                            output = result.output, detail = AgentToolResultSemantics.classify("write_stdin",result.output).uiDetail, endedAtMs = System.currentTimeMillis()) }
                        return ToolOutcome(call,result.output,result.rejected)
                    }
                    if (call.input is PreparedToolInput.SourcePatch) {
                        check(privateAssistantRecorded) { "Source execution requires durable native history" }
                        val source = checkNotNull(mixedSourceCalls[call.id] as? SourcePatchCall)
                        val received = checkNotNull(privateSmallTurn).received
                        val hookOwner = captureHookAuthority(generation, runSessionId, ctx.mode, ctx.provenance)
                        val task = currentCoroutineContext()
                        val result = approvals.withApprovalOwner(requireNotNull(hookOwner.approvalAdmission)) {
                            source.execute(ctx, lifecycleHooks, hookOwner,
                                received.responseOwner.copy(callId = call.id), call.repeatOrdinal) {
                                task.ensureActive(); exitFence.requireOpen(); ensureRunCurrent(generation, runSessionId)
                            }
                        }
                        publishToolPart(call.partId) { it.copy(
                            state = if (result.rejected) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED,
                            output = result.output, endedAtMs = System.currentTimeMillis()) }
                        return ToolOutcome(call,result.output,result.rejected)
                    }
                    if (call.input is PreparedToolInput.SourceArtifact) {
                        check(privateAssistantRecorded) { "Source execution requires durable native history" }
                        val source = checkNotNull(mixedSourceCalls[call.id] as? SourceArtifactCall)
                        val received = checkNotNull(privateSmallTurn).received
                        val hookOwner = captureHookAuthority(generation, runSessionId, ctx.mode, ctx.provenance)
                        val task = currentCoroutineContext()
                        val result = approvals.withApprovalOwner(requireNotNull(hookOwner.approvalAdmission)) {
                            source.execute(ctx, lifecycleHooks, hookOwner,
                                received.responseOwner.copy(callId = call.id), call.repeatOrdinal) {
                                task.ensureActive(); exitFence.requireOpen(); ensureRunCurrent(generation, runSessionId)
                            }
                        }
                        publishToolPart(call.partId) { it.copy(
                            state = if (result.rejected) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED,
                            output = result.output, producedFiles = if (result.rejected) emptyList() else listOfNotNull(source.producedPath),
                            endedAtMs = System.currentTimeMillis()) }
                        return ToolOutcome(call,result.output,result.rejected)
                    }
                    if (call.input is PreparedToolInput.SourceFile) {
                        check(privateAssistantRecorded) { "Source execution requires durable native history" }
                        val source = checkNotNull(mixedSourceCalls[call.id] as? MixedSourceWriteCall)
                        val received = checkNotNull(privateSmallTurn).received
                        val hookOwner = captureHookAuthority(generation, runSessionId, ctx.mode, ctx.provenance)
                        val textCall = AgentStreamedTextEditCall(sessions,
                            SessionTextEditEvidence.Owner(runSessionId, received.responseOwner.runId, call.id, call.partId, fname),
                            isCurrent = { isRunCurrent(generation, runSessionId) }) { record, association ->
                            synchronized(admissionLock) {
                                if (isRunCurrent(generation, runSessionId)) {
                                    val old = textEditEvidence?.takeIf { it.sessionId == runSessionId && it.createdAt == record.intent.sessionCreatedAt }
                                        ?: SessionTextEditEvidence.Evidence(runSessionId, record.intent.sessionCreatedAt, emptyList(), emptyList())
                                    textEditEvidence = old.copy(records = old.records.filterNot { it.intent.operationId == record.intent.operationId } + record)
                                    streamProjector.update(call.partId) { it.copy(textEdit = association) }
                                }
                            }
                        }
                        val task = currentCoroutineContext()
                        val result = approvals.withApprovalOwner(requireNotNull(hookOwner.approvalAdmission)) {
                            try {
                                source.execute(ctx, lifecycleHooks, hookOwner, textCall, call.repeatOrdinal) {
                                    task.ensureActive(); exitFence.requireOpen(); ensureRunCurrent(generation, runSessionId)
                                }
                            } catch (failure: Exception) {
                                val recovery = when (failure) {
                                    is StreamedDiskEditPreparation.Failure -> listOfNotNull(failure.recovery.snapshot, failure.recovery.candidate)
                                    is StreamedDiskEditPreparation.Cancelled -> listOfNotNull(failure.recovery.snapshot, failure.recovery.candidate)
                                    is StreamedLiveEditPreparation.Failure -> listOfNotNull(failure.recovery.snapshot, failure.recovery.candidate)
                                    is StreamedLiveEditPreparation.Cancelled -> listOfNotNull(failure.recovery.snapshot, failure.recovery.candidate)
                                    else -> null
                                }
                                if (recovery != null) try {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        receivedRecoveryCheckpoint(received.resources, received.responseOwner, received.result.target,
                                            OwnedChatStreamAccumulator.Recovery(emptyList(),
                                                recovery.distinct().map {
                                                    OwnedChatStreamAccumulator.ReceivedStage(call.id, it)
                                                }))
                                    }
                                } catch (checkpointFailure: Exception) { failure.addSuppressed(checkpointFailure) }
                                throw failure
                            }
                        }
                        val failed = result.rejected
                        val terminal = if (failed) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED
                        val association = textCall.terminal(terminal)
                        publishToolPart(call.partId) { it.copy(state = terminal, output = result.output,
                            textEdit = association, endedAtMs = System.currentTimeMillis()) }
                        return ToolOutcome(call, result.output, failed)
                    }
                    val rawArgs = call.rawArgs
                    val fargs = call.args
                    if (fname == "write_file" || fname == "edit_file") {
                        ctx.textEditCall = sessions.textEditCall(
                            SessionTextEditEvidence.Owner(runSessionId, mediaRunId, call.id, call.partId, fname),
                            isCurrent = { synchronized(admissionLock) { isRunCurrent(generation, runSessionId) } },
                        ) { record, association, terminal ->
                            synchronized(admissionLock) {
                                if (isRunCurrent(generation, runSessionId) && _streamState.value.runId == generation &&
                                    _streamState.value.sessionId == runSessionId) {
                                    if (terminal) {
                                        val prior = textEditEvidence?.takeIf { it.sessionId == runSessionId && it.createdAt == record.intent.sessionCreatedAt }
                                            ?: SessionTextEditEvidence.Evidence(runSessionId, record.intent.sessionCreatedAt, emptyList(), emptyList())
                                        textEditEvidence = prior.copy(records = prior.records.filterNot {
                                            it.intent.operationId == record.intent.operationId
                                        } + record)
                                    }
                                    streamProjector.update(call.partId) { it.copy(textEdit = association) }
                                }
                            }
                        }
                    }
                    if (fname == "browser") browserPublications[call.index] = ctx.browserPublication
                    var sampledBrowserUrl: String? = null
                    fun browserDenial(): String? = if (fname != "browser") null else
                        if (!ctx.browserRequestCurrent()) "DENIED: browser request is no longer current"
                        else if (ctx.browserPublication.captured) ctx.browserPublication.denial()
                        else AgentTools.browserRequestPreflight(fname, fargs, ctx, sampledBrowserUrl)
                    suspend fun refreshBrowserPreflight(): String? {
                        if (fname != "browser" || ctx.browserPublication.captured) return browserDenial()
                        // Cached DENY is cheap and wins without any main-thread work.
                        AgentTools.browserRequestPreflight(fname, fargs, ctx)?.let { return it }
                        sampledBrowserUrl = ctx.browserController?.sampleCurrentUrlForPreflight()
                        return browserDenial()
                    }
                    val browserDispatchCheck: (suspend () -> Unit)? = if (fname != "browser") null else ({
                        refreshBrowserPreflight()?.let { throw BrowserPublicationRevokedException(it) }
                    })
                    return try {
                        // policy remains authoritative before hooks or execution.
                        val policyDenied = AgentToolRequestPolicy.denial(fname, requestToolNames)
                            ?: AgentModePolicy.check(ctx.mode, fname)
                            ?: if (!ctx.perms.toolAllowed(fname)) "DENIED (agent profile): tool '$fname' is outside this Agent profile capability set." else null
                        val rawResult = if (call.argumentError != null) {
                            call.argumentError
                        } else if (policyDenied != null) {
                            policyDenied
                        } else {
                            val toolOwner = captureHookAuthority(generation, runSessionId, ctx.mode, ctx.provenance)
                            ctx.localRecoveryOwner = toolOwner
                            ctx.localRecoveryCache = localFolderRecoveryCache()
                            approvals.withApprovalOwner(requireNotNull(toolOwner.approvalAdmission)) {
                                AgentTools.withBrowserToolWork(fname, ctx) browserWork@{
                                    refreshBrowserPreflight()?.let { return@browserWork it }
                                    val doomDenied = call.repeatOrdinal >= 3 && approvals.withBrowserValidity({ browserDenial() == null }, admission = { refreshBrowserPreflight() == null }) {
                                        AgentApprovalSupport.doomLoopDenied(
                                            approvals, perms, toolOwner.permissionScope, fname, rawArgs, toolOwner.provenance,
                                            arguments = fargs,
                                            commandSnapshot = if (fname == "write_stdin")
                                                (fargs["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                                    ?.let(backgroundCommands::snapshot) else null)
                                    }
                                    if (doomDenied) {
                                        "DENIED (doom-loop guard): stop repetition $fname"
                                    } else {
                                        ensureRunCurrent(generation, runSessionId)
                                        refreshBrowserPreflight()?.let { return@browserWork it }
                                        approvals.withBrowserValidity({ browserDenial() == null }, admission = { refreshBrowserPreflight() == null }) {
                                            lifecycleHooks.tool(fname, rawArgs, beforeDispatch = browserDispatchCheck,
                                                owner = toolOwner) {
                                                ensureRunCurrent(generation, runSessionId)
                                                AgentTools.browserRequestPreflight(fname, fargs, ctx)
                                                    ?: AgentTools.execute(fname, fargs, ctx)
                                            }
                                        }.also { ensureRunCurrent(generation, runSessionId) }
                                    }
                                }
                            }
                        }
                        // POST_TOOL_USE may have suspended after the observation passed.
                        // Do not publish raw text or attachment refs with revoked authority.
                        val result = ctx.browserPublication.denial()?.also {
                            callMedia.clear(); callImages.clear()
                        } ?: rawResult
                        val evidence = if (call.argumentError != null)
                            AgentToolEvidence(AgentToolEvidenceState.FAILED, false, false, "Invalid provider tool arguments; execution refused")
                            else AgentToolResultSemantics.classify(fname, result, AgentToolResultSemantics.operationFor(fname, fargs))
                        val evidencedResult = evidence.annotate(fname, result)
                        val terminalState = if (evidence.isFailure) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED
                        val textEdit = ctx.textEditCall?.terminal(terminalState)
                        publishToolPart(call.partId) {
                            it.copy(state = terminalState, textEdit = textEdit,
                                output = AgentWebTools.forAgentHistory(fname, evidencedResult),
                                detail = textEdit?.detail(terminalState, evidence.uiDetail) ?: evidence.uiDetail, endedAtMs = System.currentTimeMillis())
                        }
                        ToolOutcome(call, evidencedResult, failed = evidence.isFailure,
                            media = if (evidence.isFailure) emptyList() else callMedia.toList(),
                            images = if (evidence.isFailure) emptyList() else callImages.toList())
                    } catch (toolCancelled: CancellationException) {
                        val textEdit = ctx.textEditCall?.terminal(AgentStreamPartState.CANCELLED)
                        publishToolPart(call.partId) {
                            it.copy(state = AgentStreamPartState.CANCELLED, textEdit = textEdit,
                                detail = textEdit?.detail(AgentStreamPartState.CANCELLED, "Cancelled by user") ?: "Cancelled by user",
                                endedAtMs = System.currentTimeMillis())
                        }
                        throw toolCancelled
                    } catch (toolError: Throwable) {
                        val message = if (toolError is BrowserPublicationRevokedException) toolError.message.orEmpty()
                            else "ERROR (${toolError::class.java.simpleName}): ${toolError.message ?: "Tool failed"}".take(12_000)
                        val evidence = AgentToolResultSemantics.classify(fname, message, AgentToolResultSemantics.operationFor(fname, fargs))
                        val evidencedMessage = evidence.annotate(fname, message)
                        val textEdit = ctx.textEditCall?.terminal(AgentStreamPartState.ERROR)
                        publishToolPart(call.partId) {
                            it.copy(state = AgentStreamPartState.ERROR, textEdit = textEdit, output = evidencedMessage,
                                detail = textEdit?.detail(AgentStreamPartState.ERROR, evidence.uiDetail) ?: evidence.uiDetail,
                                endedAtMs = System.currentTimeMillis())
                        }
                        ToolOutcome(call, evidencedMessage, failed = true)
                    } finally {
                        // Argument/policy rejections never enter the browser scope.
                        ctx.browserToolWork?.let { browserController?.releaseToolWork(it) }
                    }
                }
                fun publicationSafe(outcome: ToolOutcome): ToolOutcome {
                    val denial = browserPublications[outcome.call.index]?.denial() ?: return outcome
                    val evidence = AgentToolResultSemantics.classify(outcome.call.name, denial,
                        AgentToolResultSemantics.operationFor(outcome.call.name, outcome.call.args))
                    val result = evidence.annotate(outcome.call.name, denial)
                    publishToolPart(outcome.call.partId) {
                        it.copy(state = AgentStreamPartState.ERROR, output = result, detail = evidence.uiDetail)
                    }
                    return outcome.copy(result = result, failed = true, media = emptyList(), images = emptyList())
                }
                suspend fun commitOutcome(produced: ToolOutcome) {
                    ensureRunCurrent(generation, runSessionId)
                    var outcome = publicationSafe(produced)
                    val call = outcome.call
                    val id = call.id
                    // Archive the complete returned text before constructing a bounded model
                    // preview. Retention refusal is not execution failure and must not replay it.
                    val retainedPreview = withContext(Dispatchers.IO) {
                        AgentRetainedToolOutputProjection.project(call.name, outcome.result) {
                            AgentToolOutputStore.forWorkspace(workDir).retain(runSessionId, it)
                        }
                    }
                    ensureRunCurrent(generation, runSessionId)
                    val retainedOriginal = outcome.result
                    fun toolEntry(): JsonObject {
                        // Source-call replay is bound to the exact private settled receipt, never
                        // a workspace-authored or retained-preview approximation of that receipt.
                        mixedSourceCalls[id]?.let { return checkNotNull(it.settledResult) }
                        return buildJsonObject {
                            put("role", "tool"); put("tool_call_id", id)
                            put("content", if (outcome.result == retainedOriginal) retainedPreview else AgentWebTools.forAgentHistory(call.name, outcome.result))
                        }
                    }
                    mixedSourceCalls[call.id]?.let { source ->
                        if (source.settledResult == null) withContext(Dispatchers.IO) { source.settleSkipped(outcome.result) }
                    }
                    try {
                        appendOwnedHistory(owner, toolEntry(), beforeAppend = {
                            ensureRunCurrent(generation, runSessionId)
                            browserPublications[call.index]?.denial()?.let { throw BrowserPublicationRevokedException(it) }
                        })
                    } catch (revoked: BrowserPublicationRevokedException) {
                        // Only a pre-admission rejection reaches here. Close the one
                        // unresolved tool call with safe text; no raw append was committed.
                        ensureRunCurrent(generation, runSessionId)
                        outcome = publicationSafe(produced)
                        appendOwnedHistory(owner, toolEntry(), beforeAppend = { ensureRunCurrent(generation, runSessionId) })
                    }
                    // Persistence admitted while allowed is not rolled back if policy
                    // changed during IO. Subsequent UI/media still require permission.
                    outcome = publicationSafe(outcome)
                    val toolDisplay = "TOOL|${call.name}|${call.displayInput()}|${outcome.result.take(2000)}"
                    val durablePart = withRunPublication(owner) { streamPart(call.partId) }
                    uiHistory += AgentMessage("assistant", toolDisplay, streamPart = durablePart)
                    val toolMessageIndex = uiHistory.lastIndex
                    if (outcome.media.isNotEmpty()) {
                        uiHistory += AgentMessage("assistant", "", media = outcome.media)
                        appContext?.let { context -> ChatMediaCleanup.schedule(context, workDir) }
                    }
                    unresolvedApiToolCalls.remove(id)
                    mixedSourceCalls.remove(id)
                    if (unresolvedApiToolCalls.isEmpty()) stampTypedMessage(uiHistory, toolMessageIndex, owner)
                    committedImages.add(outcome.images)
                    publishUiHistory(uiHistory, owner)
                    publication.commit(outcome.media, outcome.images)
                    if (call.id !in skippedForUserSteer) {
                        lastSig = call.signature
                        repeat = call.repeatOrdinal
                    }
                }
                try {
                for (batch in AgentToolScheduler.batches(schedulingFacts)) {
                    currentCoroutineContext().ensureActive()
                    conversationHistory.ensureStorageAvailable(owner.historyVersion)
                    val calls = batch.indices.map { prepared[it] }
                    val outcomes = if (batch.parallel) {
                        coroutineScope { calls.map { call -> async { executePrepared(call) } }.awaitAll() }
                    } else {
                        calls.map { call -> executePrepared(call) }
                    }
                    outcomes.sortedBy { it.call.index }.forEach { commitOutcome(it) }
                }
                check(unresolvedApiToolCalls.isEmpty()) { "Tool results must complete before image context is delivered" }
                committedImages.message()?.let { appendOwnedHistory(owner, it) }
                } finally {
                    withContext(NonCancellable) { publication.discardUnpublished() }
                }
                } finally {
                    // Covers setup failure, STEER-skipped calls and full Agent cancellation.
                    browserToolWork.forEach { browserController?.releaseToolWork(it) }
                }
                withRunPublication(owner) {
                    finishStep(turn, if (skippedForUserSteer.isNotEmpty()) "Prior tools skipped for user steering" else "Tools complete").let { stepPart ->
                        uiHistory += AgentMessage("assistant", "", streamPart = stepPart)
                        publishUiHistory(uiHistory, owner)
                    }
                }
                
            }
            }
            runModelSteps()
        } catch (e: CancellationException) {
            try { withContext(NonCancellable) {
                checkpointMixedInterrupted(e)
                if (isRunCurrent(generation, runSessionId)) {
                    withRunPublication(owner) { terminateOpenStreamParts(AgentStreamPartState.CANCELLED, "Cancelled by user") }
                    closeUnresolvedApiToolCalls(unresolvedApiToolCalls, "Tool execution was interrupted by the user", AgentStreamPartState.CANCELLED, owner,
                        interruptedSourceResults, unknownSourceExecutions)
                    withRunPublication(owner) {
                        _streamState.value = _streamState.value.copy(
                            status = AgentStreamState.Status.CANCELLED,
                            endedAtMs = System.currentTimeMillis(),
                        )
                        _messages.value = _messages.value + AgentMessage("assistant", "Stopped by user.")
                    }
                    if (typedHistoryHasCompleteTail(owner)) {
                        appendOwnedHistory(owner, buildJsonObject { put("role", "assistant"); put("content", "Stopped by user.") })
                        stampTypedPublishedTail(owner)
                    }
                }
            } } catch (obsolete: CancellationException) {
                if (obsolete !== e) e.addSuppressed(obsolete)
            }
            throw e
        } catch (e: Exception) {
            checkpointMixedInterrupted(e)
            if (isRunCurrent(generation, runSessionId)) {
                val msg = e.message ?: "Unknown error"
                withRunPublication(owner) { terminateOpenStreamParts(AgentStreamPartState.ERROR, msg.take(240)) }
                closeUnresolvedApiToolCalls(unresolvedApiToolCalls, "Tool execution was interrupted by agent error: ${msg.take(240)}", AgentStreamPartState.ERROR, owner,
                    interruptedSourceResults, unknownSourceExecutions)
                
                ProviderFailure.limitWarning(e)?.let { hint ->
                    withRunPublication(owner) { tokens.warnQuota(hint) }
                }
                withRunPublication(owner) {
                    upsertStreamPart(
                        AgentStreamPart(
                            id = "error:${generation}",
                            step = 0,
                            kind = AgentStreamPartKind.STATUS,
                            state = AgentStreamPartState.ERROR,
                            title = "Agent error",
                            detail = msg.take(500),
                            endedAtMs = System.currentTimeMillis(),
                        )
                    )
                    _streamState.value = _streamState.value.copy(status = AgentStreamState.Status.ERROR, endedAtMs = System.currentTimeMillis())
                    _messages.value = _messages.value + AgentMessage("assistant", "Error: $msg")
                }
                // Unusable responses and transport diagnostics belong in the UI transcript.
                // Safe partial provider text was already admitted above; an error event is
                // not an additional model answer for the next user-directed continuation.
                if (e !is AgentResponseOutcome.Failure && e !is LlmProviderStreamException && typedHistoryHasCompleteTail(owner)) {
                    appendOwnedHistory(owner, buildJsonObject { put("role", "assistant"); put("content", "Error: $msg") })
                    stampTypedPublishedTail(owner)
                }
            }
        } finally {
            lspResultPages.close()
            searchResultPages.close()
            appContext?.let { DevelopmentForegroundService.finish(it, foreground) }
            if (isRunCurrent(generation, runSessionId)) {
                val saved = withContext(NonCancellable) { persistCurrent(userText.take(40), runSessionId, generation) }
                if (saved && currentCoroutineContext().isActive && subagentDepth == 0 &&
                    _streamState.value.status !in setOf(AgentStreamState.Status.ERROR, AgentStreamState.Status.CANCELLED)) {
                    sessionTitles.schedule(runSessionId, config)
                }
            }
            if (isRunCurrent(generation, runSessionId)) {
                withContext(NonCancellable) { promptCoordinator.markRunInactive(runLease) }
                synchronized(admissionLock) {
                    if (isRunCurrent(generation, runSessionId)) {
                        _streamState.value = AgentStreamState(runId = generation, sessionId = runSessionId, status = AgentStreamState.Status.IDLE)
                        _busy.value = false
                    }
                }
            }
        }

        } finally { exitRegistration.close() }
        } finally { exportWriter.close() }
    }
    /** Finite in-memory publication only; callers must finish suspension before entering. */
    private fun <T> withRunPublication(owner: RunPublicationOwner, publish: () -> T): T = synchronized(admissionLock) {
        ensureRunCurrent(owner.generation, owner.sessionId)
        publish()
    }
    private fun receivedRecoveryCheckpoint(resources: TypedChatResources, responseOwner: OwnedResponseContentStore.Owner,
        target: ProviderReplayTarget, recovery: OwnedChatStreamAccumulator.Recovery) {
        // Informational private evidence only, including after Stop. Never reopens a cancelled store or grants execution.
        resources.recovery.persist(responseOwner, target, recovery)
    }

    private suspend fun appendCanonicalOwnedTurn(owner: RunPublicationOwner, received: OwnedServiceTurn,
        calls: List<ToolCallHistoryJournal.Locator>, uiHistory: MutableList<AgentMessage>) {
        var legacyUi: List<AgentMessage>? = null
        val previous = withRunPublication(owner) { typedCheckpoint }
        val initial = if (previous != null) previous else {
            val exact = checkNotNull(conversationHistory.completeExactOrNull(owner.historyVersion)) {
                "Exact history is unavailable; source-backed activation was not admitted"
            }
            val live = withRunPublication(owner) { conversationHistory.strictActiveForProvider() }
            val displayed = withRunPublication(owner) { uiHistory.toList() }
            legacyUi = withContext(Dispatchers.IO) { SessionForkHistory.annotate(displayed, exact) }
            TypedChatCheckpoint(target = TypedChatTarget.of(received.result.target),
                live = live.map { TypedChatEntry.Legacy(it) }, exact = exact.map { TypedChatEntry.Legacy(it) })
        }
        require(initial.target.runtime() == received.result.target)
        val next = withContext(Dispatchers.IO) {
            val registryId = received.resources.registry.recordAccepted(received.result, received.responseOwner, calls)
            received.resources.history.appendOwned(initial, TypedChatEntry.OwnedTurn(registryId,
                received.responseOwner.runId, received.responseOwner.callId, calls.size), owner.sessionId)
        }
        withRunPublication(owner) {
            check(typedCheckpoint === previous) { "Canonical history changed during native turn admission" }
            typedCheckpoint = next
            legacyUi?.let { annotated ->
                uiHistory.clear(); uiHistory.addAll(annotated); publishUiHistory(uiHistory, owner)
            }
        }
    }

    private suspend fun appendPrivateNativeAssistant(owner: RunPublicationOwner, native: PrivateServiceAssistant,
        uiHistory: MutableList<AgentMessage>) {
        val received = native.received
        val previous = withRunPublication(owner) { typedCheckpoint }
        var legacyUi: List<AgentMessage>? = null
        val initial = if (previous != null) previous else {
            val exact = checkNotNull(conversationHistory.completeExactOrNull(owner.historyVersion)) {
                "Exact history is unavailable; private native admission was not completed"
            }
            val live = withRunPublication(owner) { conversationHistory.strictActiveForProvider() }
            val displayed = withRunPublication(owner) { uiHistory.toList() }
            legacyUi = withContext(Dispatchers.IO) { SessionForkHistory.annotate(displayed, exact) }
            TypedChatCheckpoint(target = TypedChatTarget.of(received.result.target),
                live = live.map { TypedChatEntry.Legacy(it) }, exact = exact.map { TypedChatEntry.Legacy(it) })
        }
        require(initial.target.runtime() == received.result.target)
        val next = withContext(Dispatchers.IO) {
            received.resources.history.appendNative(initial, TypedChatEntry.OwnedAssistant(native.registryId,
                received.responseOwner.runId, received.responseOwner.callId, received.result.calls.map { it.id }), owner.sessionId)
        }
        withRunPublication(owner) {
            check(typedCheckpoint === previous) { "Canonical history changed during private native admission" }
            typedCheckpoint = next
            legacyUi?.let { annotated -> uiHistory.clear(); uiHistory.addAll(annotated); publishUiHistory(uiHistory, owner) }
        }
    }

    private suspend fun appendOwnedHistory(owner: RunPublicationOwner, entry: JsonObject, beforeAppend: (() -> Unit)? = null) =
        appendOwnedHistory(owner, listOf(entry), beforeAppend)

    // Empty-queue promotion can release a run before final persistence completes.
    // Every new run, clear/load and shutdown revokes this captured history ticket,
    // including a checkpoint already waiting or spilling outside the monitor.
    private suspend fun appendOwnedHistory(owner: RunPublicationOwner, entries: List<JsonObject>, beforeAppend: (() -> Unit)? = null) {
        currentCoroutineContext().ensureActive()
        val typed = withRunPublication(owner) { typedCheckpoint }
        if (typed != null) {
            entries.forEach { SessionJsonSize.requireSupportedDepth(it) }
            withRunPublication(owner) {
                beforeAppend?.invoke()
                check(typedCheckpoint === typed) { "Canonical history changed during append" }
                val additions = entries.map { TypedChatEntry.Legacy(it) }
                typedCheckpoint = typed.copy(live = typed.live + additions, exact = typed.exact + additions)
            }
            return
        }
        conversationHistory.append(entries, expectedOwnerVersion = owner.historyVersion, beforeAppend = {
            ensureRunCurrent(owner.generation, owner.sessionId)
            beforeAppend?.invoke()
        })
    }
    /** Pending native calls must not acquire unrelated user/diagnostic API entries. UI diagnostics
     * remain visible, but neither a new model request nor a fork may infer missing source outcomes. */
    private fun typedHistoryHasCompleteTail(owner: RunPublicationOwner): Boolean = withRunPublication(owner) {
        val checkpoint=typedCheckpoint ?: return@withRunPublication true
        runCatching { TypedChatForkBoundary.completedEnd(checkpoint.exact) != null }.getOrDefault(false)
    }

    /** Stamp only the semantic UI publication that owns this completed append, never match across native groups. */
    private fun stampTypedMessage(history: MutableList<AgentMessage>, index: Int, owner: RunPublicationOwner) =
        withRunPublication(owner) {
            val checkpoint = typedCheckpoint ?: return@withRunPublication
            if (index in history.indices) history[index] = TypedChatForkBoundary.stamp(history[index], checkpoint.exact)
        }

    private fun stampTypedPublishedTail(owner: RunPublicationOwner) = withRunPublication(owner) {
        val checkpoint = typedCheckpoint ?: return@withRunPublication
        val messages = _messages.value
        if (messages.isNotEmpty()) _messages.value = messages.dropLast(1) + TypedChatForkBoundary.stamp(messages.last(), checkpoint.exact)
    }

    /** Existing projection grammar operates only on this new Legacy slice, never on owned/private content. */
    private fun typedProjectionMessages(messages: List<AgentMessage>, entries: List<JsonObject>, owner: RunPublicationOwner): List<AgentMessage> =
        withRunPublication(owner) {
            val checkpoint = typedCheckpoint ?: return@withRunPublication messages
            if (messages.isEmpty() || entries.isEmpty()) return@withRunPublication messages
            val end = TypedChatForkBoundary.completedEnd(checkpoint.exact) ?: return@withRunPublication messages
            val offset = end - entries.size
            check(offset >= 0)
            SessionForkHistory.annotate(messages, entries).map { message ->
                message.copy(forkHistoryEnd = message.forkHistoryEnd?.let { Math.addExact(offset, it) })
            }
        }

    private fun stampTypedProjectionTail(history: MutableList<AgentMessage>, count: Int, entries: List<JsonObject>, owner: RunPublicationOwner) {
        if (count == 0) return
        check(count <= history.size)
        val start = history.size - count
        val stamped = typedProjectionMessages(history.subList(start, history.size).toList(), entries, owner)
        stamped.forEachIndexed { index, message -> history[start + index] = message }
    }

    private fun publishUiHistory(history: MutableList<AgentMessage>, owner: RunPublicationOwner) {
        val sourceSize = history.size
        val retained = AgentHistory.boundedUi(history, MAX_UI_MESSAGES, MAX_UI_MESSAGE_CHARS)
        withRunPublication(owner) {
            val displayed = textEditEvidence?.takeIf { it.sessionId == owner.sessionId }?.let {
                AgentTextEditProjection.apply(retained, it)
            } ?: retained
            history.clear()
            history.addAll(displayed)
            rebaseLiveHistoryBoundary(sourceSize, retained.size)
            _messages.value = displayed
        }
    }

    private fun rebaseLiveHistoryBoundary(sourceSize: Int, retainedSize: Int) {
        val stream = _streamState.value
        _streamState.value = stream.copy(baseMessageCount = AgentHistory.retainedBaseMessageCount(
            stream.baseMessageCount, sourceSize, retainedSize))
    }
    /** Presentation-only update; exact provider history and content remain untouched. */
    private fun classifyPublicResponse(history: MutableList<AgentMessage>, presentationId: String,
        partId: String, presentation: AgentTextPresentation, owner: RunPublicationOwner) = withRunPublication(owner) {
        val index = history.indexOfLast { it.presentationId == presentationId }
        if (index >= 0) history[index] = history[index].copy(textPresentation = presentation)
        updateStreamPart(partId) { it.copy(textPresentation = presentation) }
        publishUiHistory(history, owner)
    }

    private suspend fun promotePendingAtBoundary(
        runLease: Long, allowQueue: Boolean, uiHistory: MutableList<AgentMessage>, activeConfig: AgentConfig,
        runSessionId: String, generation: Long, step: Int, mediaAdmission: MediaRequestAdmission, owner: RunPublicationOwner,
        onPromoted: (List<AgentPendingInput>) -> Unit = {},
    ): AgentConfig? {
        exitFence.requireOpen()
        ensureRunCurrent(generation, runSessionId)
        val batch = promptCoordinator.promoteAtBoundary(runLease, allowQueue, activeConfig, step, MAX_API_MESSAGE_CHARS,
            parentSessionId = runSessionId,
            prepare = { prepared ->
                exitFence.requireOpen()
                val mediaInputs = prepared.promotedInputs.filter { it.media.isNotEmpty() || it.images.any { image -> image.mediaId != null } }
                if (mediaInputs.isNotEmpty()) withContext(Dispatchers.IO) {
                    val store = ChatMediaStore(workDir)
                    store.retain(runSessionId, mediaInputs.flatMap { it.media + it.images.mapNotNull { image -> image.mediaId?.let(store::ref) } })
                }
                currentCoroutineContext().ensureActive()
                ensureRunCurrent(generation, runSessionId)
                // A spill may suspend, but append publishes memory only after its IO return
                // succeeds and fences its exact owner. Keep the original inbox until then.
                appendOwnedHistory(owner, prepared.apiEntries)
            },
            commit = { prepared, acknowledge -> synchronized(admissionLock) {
                exitFence.requireOpen()
                ensureRunCurrent(generation, runSessionId)
                acknowledge()
                mediaAdmission.includePromoted(prepared.requestAdmittedAtNanos)
                onPromoted(prepared.promotedInputs)
                uiHistory += typedProjectionMessages(prepared.userMessages, prepared.apiEntries, owner)
                publishUiHistory(uiHistory, owner)
                prepared.streamParts.forEach(::upsertStreamPart)
                _streamState.value = _streamState.value.copy(providerId = prepared.nextConfig.providerId, modelId = prepared.nextConfig.model)
            } },
        ) ?: return null
        val mediaInputs = batch.promotedInputs.filter { it.media.isNotEmpty() || it.images.any { image -> image.mediaId != null } }
        val persisted = withContext(NonCancellable) { persistCurrent(batch.fallbackTitle, runSessionId, generation) }
        if (persisted) {
            subagentBridge.markDelivered(batch.deliveredSourceReceipts)
            if (mediaInputs.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) {
                val store = ChatMediaStore(workDir)
                mediaInputs.forEach { store.releaseDeletedOwner(ChatMediaStore.pendingOwner(runSessionId, it.id)) }
            }
        }
        return batch.nextConfig
    }
    private suspend fun closeUnresolvedApiToolCalls(
        unresolved: MutableMap<String, String>,
        reason: String,
        terminalState: AgentStreamPartState,
        owner: RunPublicationOwner,
        verifiedResults: Map<String, JsonObject> = emptyMap(),
        unknownExecutions: Set<String> = emptySet(),
    ) {
        if (unresolved.isEmpty()) return
        val closable = unresolved.filterKeys { it !in unknownExecutions }.toMutableMap()
        if (closable.isEmpty()) return
        val closure = withRunPublication(owner) {
            AgentHistory.closeInterruptedTools(
                closable, reason, terminalState, _streamState.value.parts, _streamState.value.activeStep,
            )
        }
        appendOwnedHistory(owner, closure.apiEntries.map { entry ->
            val id = (entry["tool_call_id"] as? JsonPrimitive)?.contentOrNull
            verifiedResults[id] ?: entry
        })
        withRunPublication(owner) {
            if (closure.messages.isNotEmpty()) {
                val messages = closure.messages.map { message ->
                    val part = message.streamPart
                    val result = verifiedResults[part?.toolCallId]
                    val text = (result?.get("content") as? JsonPrimitive)?.contentOrNull
                    if (part == null || text == null) message else message.copy(
                        content = "TOOL|${part.toolName}|${part.input}|${text.take(2_000)}",
                        streamPart = part.copy(output = text,
                            detail = part.textEdit?.detail(terminalState, text.take(500)) ?: text.take(500)))
                }
                _messages.value = _messages.value + messages
                stampTypedPublishedTail(owner)
            }
            closable.keys.forEach(unresolved::remove)
        }
    }
    /** Stop retains this owner; replacement, restore and shutdown revoke its publications. */
    private fun publishOwnedStream(
        generation: Long,
        sessionId: String,
        publish: AgentStreamProjector.() -> Unit,
    ) = synchronized(admissionLock) {
        val current = _streamState.value
        if (!mediaRecoveryClosed && loadingSessionGeneration == null &&
            isRunCurrent(generation, sessionId) && current.runId == generation && current.sessionId == sessionId) {
            // Check and projector mutation share the lifecycle monitor. A stale callback
            // must not even read a replacement part with the same tool:step:index ID.
            streamProjector.publish()
        }
    }

    private fun terminateOpenStreamParts(state: AgentStreamPartState, detail: String) =
        streamProjector.terminateOpen(state, detail)
    private fun upsertStreamPart(part: AgentStreamPart) = streamProjector.upsert(part)
    private fun updateStreamPart(id: String, transform: (AgentStreamPart) -> AgentStreamPart) = streamProjector.update(id, transform)
    private fun removeStreamPart(id: String) = streamProjector.remove(id)
    private fun appendTextPart(id: String, step: Int, delta: String, buffered: Boolean,
        phase: ProviderPublicTextPhase = ProviderPublicTextPhase.UNSPECIFIED, presentationId: String = "") =
        streamProjector.appendText(id, step, delta, buffered, phase, presentationId)
    private fun applyToolCallDelta(step: Int, event: LlmStreamEvent.ToolCallDelta) = streamProjector.applyToolCallDelta(step, event)
    private fun streamPart(id: String): AgentStreamPart? = streamProjector.part(id)
    private fun finishStep(step: Int, reason: String): AgentStreamPart = streamProjector.finishStep(step, reason)
    private suspend fun compactTypedContext(config: AgentConfig, owner: RunPublicationOwner,
        trigger: String, systemText: String, tools: JsonArray?): HookedCompaction {
        val task = currentCoroutineContext()
        val original = withRunPublication(owner) { checkNotNull(typedCheckpoint) }
        val mode = withRunPublication(owner) { _mode.value }
        val taskTodos = todos.get(owner.sessionId)
        fun current() {
            task.ensureActive(); exitFence.requireOpen(); ensureRunCurrent(owner.generation, owner.sessionId)
            check(typedCheckpoint === original && _mode.value == mode && todos.get(owner.sessionId) == taskTodos) {
                "Conversation or task state changed during typed compaction; existing history was preserved"
            }
        }
        current()
        val target = llm.ownedChatTarget(config.providerId, config.baseUrl, config.model)
        require(target == original.target.runtime()) { "Typed compaction requires the original provider, model and endpoint" }
        val activity = withRunPublication(owner) {
            AgentCompactionActivity(compactionSequence.incrementAndGet(), owner.sessionId, true, 0,
                afterTokens = null, detail = "Compacting source-backed history").also { _compactionActivity.value = it }
        }
        try {
        val result = lifecycleHooks.compact(trigger, owner = captureHookAuthority(owner.generation, owner.sessionId, mode)) {
            current()
            check(persistCurrent("Conversation", owner.sessionId, owner.generation)) { "Conversation could not be checkpointed before compaction" }
            current()
            val saved = checkNotNull(sessions.snapshotForUpdate(owner.sessionId))
            require(saved.session.typedChat == original)
            val request = AgentRequestSupport.build(config, systemText, emptyList(), tools)
            val options = JsonObject(request.filterKeys { it != "messages" })
            val prefix = request.getValue("messages").jsonArray.map { SourceAwareChatHistoryWriter.Item.Legacy(it.jsonObject) }
            val provider = checkNotNull(ProviderRegistry.findById(config.providerId, providerConfiguration))
            val resources = sessions.typedChatResources(::current)
            val candidate = TypedChatCompactor.compact(original, owner.sessionId, resources, llm, config,
                provider, options, prefix, taskTodos, ::current, onUsage = { usage ->
                    tokens.addSample(config.model, usage)
                })
            current()
            if (!candidate.changed) false else {
                sessions.commitTypedCompaction(owner.sessionId, candidate, saved.revision,
                    commitAdmission = { publish -> synchronized(admissionLock) {
                        current(); publish()
                        typedCheckpoint = candidate.checkpoint
                        _contextUsage.value = null
                    } })
                true
            }
        }
        withRunPublication(owner) {
            if (_compactionActivity.value?.id == activity.id) _compactionActivity.value = activity.copy(
                running = false, afterTokens = null, detail = when {
                    result.blockedReason.isNotBlank() -> "Compaction blocked by project hook"
                    result.changed -> "Session compacted; full history preserved"
                    else -> "No compaction required"
                })
        }
        return result
        } finally {
            synchronized(admissionLock) {
                if (isRunCurrent(owner.generation, owner.sessionId) && _compactionActivity.value?.id == activity.id &&
                    _compactionActivity.value?.running == true) {
                    _compactionActivity.value = activity.copy(running = false, afterTokens = null,
                        detail = "Compaction stopped; exact history preserved")
                }
            }
        }
    }

    private suspend fun compactTypedManually(config: AgentConfig): String {
        val registration = exitFence.track(currentCoroutineContext().job)
        try {
            val owner = synchronized(admissionLock) {
                check(!_busy.value && job?.isActive != true && loadingSessionGeneration == null) {
                    "Finish the running Agent task or use Stop before compacting this conversation manually"
                }
                val sid = currentSessionId ?: return "No active session."
                manualCompactionProviderPhase = true
                RunPublicationOwner(runGeneration.get(), sid, conversationHistory.ownershipVersion)
            }
            val mode = _mode.value
            val projectBrief = loadProjectBrief()
            val system = AgentRuntimePrompt.compose(mode, AgentRequestScope.modeInstruction(mode), projectBrief,
                systemOverlay, false, null, false, stepBudgetReached = false)
            val result = compactTypedContext(config, owner, "manual", system, null)
            return when {
                result.blockedReason.isNotBlank() -> "Compaction blocked by project hook: ${result.blockedReason}"
                result.changed -> "Session compacted."
                else -> "Session is already compact enough."
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return "Compaction failed: ${failure.message?.take(240) ?: failure::class.java.simpleName}" }
        finally { registration.close() }
    }

    private suspend fun compactActiveContext(config: AgentConfig, owner: RunPublicationOwner, overflowRecovery: Boolean,
        systemText: String, tools: JsonArray?): Boolean {
        if (typedCheckpoint != null) return compactTypedContext(config, owner,
            if (overflowRecovery) "overflow" else "auto", systemText, tools).changed
        val activity = withRunPublication(owner) {
            AgentCompactionActivity(compactionSequence.incrementAndGet(), owner.sessionId, true,
                AgentCompactionBudget.historyTokens(_apiHistory)).also { _compactionActivity.value = it }
        }
        try {
        val budget = AgentCompactionBudget.forRequest(config, systemText, tools)
        val result = lifecycleHooks.compact(if (overflowRecovery) "overflow" else "auto",
            owner = captureHookAuthority(owner.generation, owner.sessionId)) {
            exitFence.requireOpen()
            AgentRequestSupport.compact(
                sessions, tokens, todos, _mode.value, pendingInputs.value, _messages.value, _apiHistory,
                exactForkHistory = conversationHistory.completeExactOrNull(owner.historyVersion), config, owner.sessionId, overflowRecovery,
                ensureCurrent = { exitFence.requireOpen(); ensureRunCurrent(owner.generation, owner.sessionId) },
                commitAdmission = { publish -> synchronized(admissionLock) {
                    ensureRunCurrent(owner.generation, owner.sessionId)
                    if (loadingSessionGeneration != null) throw CancellationException("Agent session is loading")
                    publish()
                    // Invalidate the old window before post-compaction hooks can fail.
                    _contextUsage.value = null
                } },
                continuationBudget = budget,
            )
        }.changed
        withRunPublication(owner) {
            if (result) _contextUsage.value = null
            _compactionActivity.value = activity.copy(running = false,
                afterTokens = if (result) AgentCompactionBudget.historyTokens(_apiHistory) else null,
                detail = if (result) "Context compacted" else "No compaction required")
        }
        return result
        } catch (failure: Exception) {
            synchronized(admissionLock) {
                if (isRunCurrent(owner.generation, owner.sessionId) && _compactionActivity.value?.id == activity.id)
                    _compactionActivity.value = activity.copy(running = false, detail =
                        if (failure is CancellationException) "Compaction cancelled" else "Compaction failed")
            }
            throw failure
        }
    }
    private fun captureHookAuthority(generation: Long, sessionId: String, mode: AgentMode? = null,
        provenance: AgentRequestProvenance? = null): HookInvocationAuthority = synchronized(admissionLock) {
        ensureRunCurrent(generation, sessionId)
        val capturedMode = mode ?: _mode.value
        if (_mode.value != capturedMode) throw CancellationException("Agent hook mode changed")
        val capturedProvenance = provenance ?: requestProvenance(sessionId)
        check(capturedProvenance.sessionId == sessionId) { "Hook provenance belongs to another session" }
        val current = { exitFence.isOpen && isRunCurrent(generation, sessionId) && _mode.value == capturedMode }
        HookInvocationAuthority(capturedMode, capturedProvenance,
            AgentPermissionScope.id(capturedMode, capturedProvenance),
            isStillCurrent = { synchronized(admissionLock) { current() } },
            approvalAdmission = ApprovalOwnerAdmission(admissionLock, current),
        )
    }
    private fun completeApiHistoryForModel(): List<JsonObject> {
        TypedChatHistory.requireLegacy(typedCheckpoint)
        return conversationHistory.completeActive()
    }
    private fun isRunCurrent(generation: Long, sessionId: String): Boolean = !mediaRecoveryClosed && loadingSessionGeneration == null &&
        runGeneration.get() == generation && currentSessionId == sessionId
    private fun ensureRunCurrent(generation: Long, sessionId: String) {
        if (!isRunCurrent(generation, sessionId)) throw CancellationException("Agent session changed")
    }
    private suspend fun persistCurrent(fallbackTitle: String, sid: String, generation: Long): Boolean {
        // Snapshot before suspension so a later session switch cannot redirect this write.
        val snapshot = synchronized(admissionLock) {
            if (!isRunCurrent(generation, sid) || loadingSessionGeneration != null) return false
            OwnedSessionSnapshot(sid, generation, _messages.value, if (typedCheckpoint == null) completeApiHistoryForModel() else emptyList(), todos.get(sid), _mode.value, pendingInputs.value,
                conversationHistory.ownershipVersion, textEditEvidence, typedCheckpoint)
        }
        try {
            val exactFork = if (snapshot.typedChat == null) conversationHistory.completeExactOrNull(snapshot.historyVersion) else null
            synchronized(admissionLock) { if (!isRunCurrent(generation, sid) || loadingSessionGeneration != null) return false }
            val annotatedMessages = withContext(Dispatchers.IO) {
                if (snapshot.typedChat != null) snapshot.messages
                else if (exactFork != null) SessionForkHistory.annotate(snapshot.messages, exactFork)
                else snapshot.messages.map { it.copy(forkHistoryEnd = null) }
            }
            val boundedMessages = AgentHistory.boundedUi(annotatedMessages, MAX_UI_MESSAGES, MAX_UI_MESSAGE_CHARS)
            val compactMessages = snapshot.textEdits?.let { AgentTextEditProjection.apply(boundedMessages, it) } ?: boundedMessages
            promptCoordinator.withPendingSnapshot { latestPending ->
                sessions.update(sid, commitAdmission = { publish -> synchronized(admissionLock) {
                    ensureRunCurrent(generation, sid)
                    if (loadingSessionGeneration != null) throw CancellationException("Agent session is loading")
                    publish()
                } }) { existing ->
                    checkNotNull(existing).copy(
                        messages = compactMessages,
                        apiHistory = snapshot.apiHistory,
                        typedChat = snapshot.typedChat,
                        forkHistory = exactFork ?: emptyList(),
                        forkHistoryVersion = if (exactFork != null) SessionForkHistory.SCHEMA_VERSION else 0,
                        todos = snapshot.todos,
                        mode = snapshot.mode,
                        pendingInputs = latestPending,
                    )
                }
            }
            synchronized(admissionLock) { if (isRunCurrent(generation, sid) && loadingSessionGeneration == null) {
                _apiHistory.clear()
                _apiHistory.addAll(snapshot.apiHistory)
                rebaseLiveHistoryBoundary(snapshot.messages.size, boundedMessages.size)
                _messages.value = compactMessages
                val savedTerminalIds = compactMessages.mapNotNull { message -> message.streamPart?.takeIf {
                    it.state == AgentStreamPartState.COMPLETED && it.textEdit?.let { association ->
                        association.normalTerminal && association.metadataAcknowledged && association.repositoryReturn == "RETURNED"
                    } == true
                }?.textEdit?.operationId }.toSet()
                textEditEvidence = textEditEvidence?.let { evidence ->
                    evidence.copy(records = evidence.records.filterNot { it.intent.operationId in savedTerminalIds })
                }
                _persistenceError.value = null
                val committedInputs = historyAdmittedExitInputs.filterValues { it == generation }.keys.toList()
                committedInputs.forEach { exitUncheckpointedInputs.remove(it); historyAdmittedExitInputs.remove(it) }
            } }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            synchronized(admissionLock) { if (isRunCurrent(generation, sid)) {
                _persistenceError.value = "Session could not be saved: ${t.message ?: t.javaClass.simpleName}"
            } }
            return false
        }
    }


    internal fun boundedApiHistory(
        source: List<JsonObject> = _apiHistory,
        maxEntries: Int = 96,
        maxChars: Int = 240_000,
    ): List<JsonObject> = AgentHistory.bounded(source, maxEntries, maxChars)
     
    private suspend fun loadProjectBrief(): String = AgentProjectInstructions.load(files, workDir, agentPluginSource)
    companion object {
        const val MAX_USER_MESSAGE_CHARS = 64_000
        const val MAX_API_MESSAGE_CHARS = 32_000
        const val MAX_ASSISTANT_CHARS = 100_000
        const val MAX_TOOL_ARGUMENT_CHARS = 128_000
        const val MAX_STREAM_TOOL_OUTPUT_CHARS = 20_000
        const val MAX_TOOL_CALLS_PER_TURN = 16
        // The limit bounds costs/looping, not wall-clock time. Long tasks need
        // enough turns to read, edit, test and recover without premature handoff.
        const val MAX_AGENT_TURNS = 96
        const val MAX_UI_MESSAGES = 500
        const val MAX_UI_MESSAGE_CHARS = 1_000_000
        const val FINAL_STEP_INSTRUCTION = """
            FINAL AGENT STEP: Tools are intentionally unavailable on this step. Do not request or imply another tool call.
            Stay within the user's actual intent. For conversation or a question, answer normally; do not invent a project task or work report.
            If an authorized task is active, give a concise, truthful handoff of actual work, observed verification and remaining work or blockers.
            Preserve all user restrictions and do not claim work or verification that did not actually happen.
        """
    }
}

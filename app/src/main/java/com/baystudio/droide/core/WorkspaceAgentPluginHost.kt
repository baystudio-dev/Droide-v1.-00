package com.baystudio.droide.core

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

 
enum class WorkspaceAgentTransport { ACP_V1 }

data class WorkspaceAgentPluginSpec(
    val id: String,
    val familyId: String,
    val displayName: String,
    val command: String,
    val args: List<String>,
    val transport: WorkspaceAgentTransport = WorkspaceAgentTransport.ACP_V1,
    val planModeCandidates: List<String> = listOf("plan", "read-only", "ask", "architect"),
    // Only documented, non-escalating defaults belong here. A prior native profile
    // is restored separately; the advertised list is never an escalation menu.
    val editModeCandidates: List<String> = emptyList(),
    val cliCommand: String = command,
    val collaborationPlanModeCandidates: List<String> = emptyList(),
    val collaborationEditModeCandidates: List<String> = emptyList(),
)

object WorkspaceAgentPluginCatalog {
    val entries = listOf(
        WorkspaceAgentPluginSpec("opencode", "plugin.opencode", "OpenCode", "opencode", listOf("acp"), editModeCandidates = listOf("build")),
        WorkspaceAgentPluginSpec("codex", "plugin.codex", "Codex", "codex-acp", emptyList(), planModeCandidates = listOf("read-only", "plan"), editModeCandidates = listOf("workspace-write"), cliCommand = "codex",
            collaborationPlanModeCandidates = listOf("plan"), collaborationEditModeCandidates = listOf("default")),
        WorkspaceAgentPluginSpec("claude", "plugin.claude-code", "Claude Agent", "claude-agent-acp", emptyList(), planModeCandidates = listOf("plan"), editModeCandidates = listOf("default"), cliCommand = "claude"),
        WorkspaceAgentPluginSpec("gemini", "plugin.gemini-cli", "Gemini CLI", "gemini", listOf("--acp")),
        WorkspaceAgentPluginSpec("copilot", "plugin.github-copilot-cli", "GitHub Copilot CLI", "copilot", listOf("--acp", "--stdio")),
        WorkspaceAgentPluginSpec("antigravity", LocalUbuntuAgentDistributions.ANTIGRAVITY, "Antigravity", "agy",
            listOf("acp", "--process-per-session"), planModeCandidates = listOf("plan"), editModeCandidates = listOf("default")),
        WorkspaceAgentPluginSpec("muse", LocalUbuntuMusePolicy.FAMILY, "Muse Code", "muse-code-acp", emptyList(),
            planModeCandidates = listOf("plan", "readOnly"), editModeCandidates = listOf("default"), cliCommand = "muse"),
        WorkspaceAgentPluginSpec("kimi", "plugin.kimi-code", "Kimi Code", "kimi", listOf("acp"),
            planModeCandidates = listOf("plan"), editModeCandidates = listOf("default")),
        WorkspaceAgentPluginSpec("deepseek", "plugin.deepseek-harness", "DeepSeek Harness", "dsh",
            listOf("--profile", "acp"), planModeCandidates = emptyList()),
    )
}

data class WorkspaceAgentAvailability(
    val spec: WorkspaceAgentPluginSpec,
    val executable: String?,
    val cliExecutable: String? = executable,
) {
    val available: Boolean get() = executable != null
    val needsAdapter: Boolean get() = !available && cliExecutable != null
}

sealed interface WorkspaceAgentEvent {
    data class Text(val text: String) : WorkspaceAgentEvent
    data class Thought(val text: String) : WorkspaceAgentEvent
    data class Tool(val title: String, val raw: String, val update: ExternalAgentToolUpdate?) : WorkspaceAgentEvent {
        constructor(title: String, raw: String) : this(title, raw, ExternalAgentToolUpdate.fromLegacy(title, raw))
        companion object {
            fun fromAcp(value: JsonObject, terminalOutput: (String) -> String? = { null }): Tool {
                val update = ExternalAgentToolUpdate.fromAcp(value, terminalOutput)
                return Tool(update?.title ?: update?.kind ?: "Agent tool", boundedJsonDiagnostic(value), update)
            }
        }
    }
    data class Plan(val entries: List<ExternalAgentPlanEntry>) : WorkspaceAgentEvent
    data class Status(val message: String) : WorkspaceAgentEvent
    data class Usage(val snapshot: WorkspaceAgentUsageSnapshot) : WorkspaceAgentEvent
    data class SessionBound(val sessionId: String) : WorkspaceAgentEvent
    data class Configuration(val sessionId: String, val value: WorkspaceAgentSessionConfiguration) : WorkspaceAgentEvent
    data class Media(val ref: ChatMediaRef) : WorkspaceAgentEvent
}

data class WorkspaceAgentTurnResult(
    val agentId: String,
    val sessionId: String,
    val text: String,
    val stopReason: String,
    val changedFiles: List<String>,
    val conflicts: List<String>,
)


class WorkspaceAgentPluginHost internal constructor(
    private val projectRoot: File,
    private val cacheDir: File,
    private val files: FileRepository,
    private val processHost: StdioProcessHost,
    private val approvals: ApprovalManager,
    private val permissions: PermissionEngine,
    parentScope: CoroutineScope,
    private val workspaceIo: AgentWorkspaceIo,
    private val onFirstMediaImported: suspend () -> Unit = {},
) : Closeable, WorkspaceAgentChatBackend {
    constructor(
        projectRoot: File,
        cacheDir: File,
        files: FileRepository,
        processHost: StdioProcessHost,
        androidDevelopment: AndroidDevelopmentManager,
        bridge: DeviceBridgeManager,
        approvals: ApprovalManager,
        permissions: PermissionEngine,
        parentScope: CoroutineScope,
        onFirstMediaImported: suspend () -> Unit = {},
    ) : this(
        projectRoot, cacheDir, files, processHost, approvals, permissions, parentScope,
        (processHost as? LocalAgentWorkspaceProcessHost)?.workspaceIo
            ?: BridgeAgentWorkspaceIo(bridge) { androidDevelopment.prepareInteractiveShell(syncProject = true); Unit },
        onFirstMediaImported,
    )

    private data class LiveSession(
        val spec: WorkspaceAgentPluginSpec,
        val process: HostedStdioProcess,
        val rpc: AcpNdjsonConnection,
        val sessionId: String, val approvalSessionId: String,
        val mapper: WorkspacePathMapper,
        @Volatile var configuration: WorkspaceAgentSessionConfiguration,
        val promptCapabilities: AcpPromptCapabilities,
        val lifetime: WorkspaceAgentSessionLifetime,
        val modeSelection: WorkspaceAgentModeSelection = WorkspaceAgentModeSelection(spec.planModeCandidates, spec.editModeCandidates,
            spec.collaborationPlanModeCandidates, spec.collaborationEditModeCandidates),
        var configurationRevision: Long = 0L,
        val mutex: Mutex = Mutex(),
        val steering: AcpSteerBoundary = AcpSteerBoundary(),
        @Volatile var attachments: AcpAttachmentAccess.Lease? = null,
    ) {
        /** Called under this session's monitor, including from the ordered RPC reader. */
        fun replaceConfiguration(value: WorkspaceAgentSessionConfiguration) {
            if (configuration.currentModeId != value.currentModeId || configuration.modeConfigId != value.modeConfigId ||
                configuration.modes != value.modes || configuration.collaborationModes != value.collaborationModes ||
                configuration.currentCollaborationModeId != value.currentCollaborationModeId ||
                configuration.collaborationModeConfigId != value.collaborationModeConfigId ||
                configuration.collaborationModeObserved != value.collaborationModeObserved) configurationRevision++
            configuration = value
        }
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val sessionOwners = ConcurrentHashMap<String, WorkspaceAgentSessionLifetime>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hostClosed = CompletableDeferred<Unit>()
    private val workspaceOwnerKey = projectRoot.canonicalPath
    private val sessionLifecycle = Mutex()
    private val workspaceTurns = Mutex()
    @Volatile private var recoveryDocuments: WorkspaceDocumentAuthority? = null

    internal fun bindRecoveryDocuments(documents: WorkspaceDocumentAuthority) {
        check(recoveryDocuments == null || recoveryDocuments === documents) { "Recovery documents already belong to another editor" }
        recoveryDocuments = documents
    }
    private val syncState = kotlinx.coroutines.flow.MutableStateFlow(WorkspaceAgentSyncState(sourceRoot = projectRoot.absolutePath))
    override val workspaceSyncState: kotlinx.coroutines.flow.StateFlow<WorkspaceAgentSyncState> = syncState

    internal suspend fun retainedWorkspaceVersions(cursor: MirrorRetainedRecovery.Cursor? = null): MirrorRetainedRecovery.Page =
        withRetainedRecovery { recovery, _ -> recovery.list(cursor) }

    internal suspend fun inspectRetainedWorkspaceVersion(item: MirrorRetainedRecovery.Item): MirrorRetainedRecovery.Inspection =
        withRetainedRecovery { recovery, _ -> recovery.inspect(item) }

    internal suspend fun saveRetainedWorkspaceCopy(inspection: MirrorRetainedRecovery.Inspection, path: String): Long =
        withRetainedRecovery { recovery, admission -> recovery.copy(inspection, files, path, admission) }

    internal suspend fun captureSqliteRollbackEvidence(): MirrorSqliteRollbackRecovery.Review =
        withRetainedRecovery(quiescent = true) { recovery, admission ->
            recovery.interrupted.captureSqliteRollbackEvidence(admission)
        }

    internal suspend fun buildSqliteForensicCandidate(): MirrorSqliteForensicCandidate.Result =
        withRetainedRecovery(quiescent = true) { recovery, admission ->
            recovery.interrupted.buildSqliteForensicCandidate(admission)
        }

    internal suspend fun inspectInterruptedWorkspaceChange(operation: MirrorMetadataAuthority.Intent): MirrorInterruptedRecovery.Review =
        withRetainedRecovery { recovery, _ -> recovery.interrupted.inspect(operation) }

    internal suspend fun finishInterruptedWorkspaceChange(review: MirrorInterruptedRecovery.Review, complete: Boolean) {
        withRetainedRecovery(quiescent = true) { recovery, admission -> recovery.interrupted.finish(review, complete, admission) }
    }

    internal suspend fun archiveRetainedWorkspaceFolder(item: MirrorRetainedRecovery.Item, path: String): Long =
        withRetainedRecovery { recovery, admission -> recovery.archive(item, files, path, cacheDir, admission) }

    internal suspend fun reviewRetainedVersionRetention(item: MirrorRetainedRecovery.Item): MirrorRetainedRecovery.RetentionReview =
        withRetainedRecovery { recovery, admission -> recovery.reviewRetention(item, recoveryDocuments, admission) }

    internal suspend fun inspectRetainedAcknowledgement(item: MirrorRetainedRecovery.Item): MirrorConflictRecovery.VersionReview =
        withRetainedRecovery { recovery, _ -> recovery.conflicts.inspectVersion(item) }

    internal suspend fun acknowledgeRetainedVersion(review: MirrorConflictRecovery.VersionReview) {
        withRetainedRecovery(quiescent = true) { recovery, admission -> recovery.conflicts.acknowledge(review, admission) }
    }

    internal suspend fun inspectWorkspaceConflict(path: String): MirrorConflictRecovery.Review =
        withRetainedRecovery { recovery, _ -> recovery.conflicts.inspect(path) }

    internal suspend fun resolveWorkspaceConflict(review: MirrorConflictRecovery.Review) {
        // This records matching saved files, not an editor-clean or payload-release
        // decision. Newer unsaved buffers remain owned by their editor generation.
        withRetainedRecovery(quiescent = true) { recovery, admission -> recovery.conflicts.resolve(review, admission) }
    }

    private suspend fun <T> withRetainedRecovery(quiescent: Boolean = false, action: suspend (MirrorRetainedRecovery, () -> Unit) -> T): T {
        val epoch = ApplicationWorkAdmission.requireOpen()
        val caller = currentCoroutineContext()
        fun requireCurrent() {
            caller.ensureActive()
            scope.ensureActive()
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "Recovery belongs to a closed application session" }
        }
        requireCurrent()
        return workspaceTurns.withLock {
            requireCurrent()
            val host = processHost as? LocalAgentWorkspaceProcessHost ?: error("Retained recovery is unavailable for this workspace")
            if (quiescent) {
                claimWorkspace()
                sessionLifecycle.withLock {
                    val closing = sessionOwners.keys.toList()
                    sessionOwners.values.forEach { it.processes.closeAdmission() }
                    OwnedResourceCleanup.runSuspend(*closing.map { id -> suspend { retireSessionLocked(id) } }.toTypedArray()).getOrThrow()
                    if (closing.isNotEmpty()) syncPhase(WorkspaceAgentSyncPhase.ISOLATED,
                        notice = "CLI sessions stopped for recovery. Reconnect before running another turn.")
                }
                requireCurrent()
            }
            action(host.retainedRecovery, ::requireCurrent).also { requireCurrent() }
        }
    }

    private fun syncPhase(phase: WorkspaceAgentSyncPhase, mapper: WorkspacePathMapper? = null,
                          pending: List<String>? = null, notice: String? = null) {
        syncState.value = WorkspaceAgentSyncState(phase, projectRoot.absolutePath,
            mapper?.remoteRoot ?: syncState.value.executionRoot, pending?.take(128) ?: syncState.value.pendingPaths,
            pending?.size ?: syncState.value.pendingCount,
            listOfNotNull(notice, workspaceIo.synchronizationNotice()).distinct().joinToString("\n").take(2_000).ifBlank { null })
    }

    private suspend fun refreshForAdmission() {
        scope.ensureActive()
        try { workspaceIo.refresh() }
        catch (failure: Throwable) {
            syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, notice = failure.message ?: "Workspace is unavailable; existing files were retained")
            throw failure
        }
    }

    private suspend fun refreshForExecution(): String? {
        try {
            refreshForAdmission()
            return null
        } catch (capacity: WorkspaceInventoryCapacityException) {
            val admission = workspaceIo as? LocalAgentExecutionAdmission ?: throw capacity
            try {
                admission.requireReadyLocalExecution()
            } catch (failure: Throwable) {
                syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, notice = failure.message ?: "CLI workspace admission failed; files were retained")
                throw failure
            }
            return deferredInventoryNotice(capacity)
        }
    }

    private fun deferredInventoryNotice(capacity: WorkspaceInventoryCapacityException): String =
        "Synchronization is pending: ${capacity.message}. CLI work remains in its working copy. " +
            "Host edits may not yet be visible there; review is incomplete until inventory succeeds."

    private fun synchronizationFailureNotice(failure: Exception): String =
        if (failure is WorkspaceInventoryCapacityException) deferredInventoryNotice(failure)
        else "Workspace synchronization is incomplete: ${(failure.message ?: failure.javaClass.simpleName).take(1_000)}. " +
            "Review the project and CLI working copy before retrying synchronization."

    private suspend fun deferSynchronization(
        mapper: WorkspacePathMapper,
        notice: String,
        onEvent: (WorkspaceAgentEvent) -> Unit,
        applied: List<String> = emptyList(),
        pending: List<String> = syncState.value.pendingPaths,
    ): Pair<List<String>, List<String>> {
        currentCoroutineContext().ensureActive()
        scope.ensureActive()
        val detail = "Applied ${applied.distinct().size} reviewed file(s). $notice"
        syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, mapper, pending, detail)
        onEvent(WorkspaceAgentEvent.Status(detail))
        return applied.distinct() to pending.distinct()
    }

    override suspend fun reviewWorkspaceChanges(agentId: String): WorkspaceAgentSyncResult = workspaceTurns.withLock {
        scope.ensureActive()
        require(WorkspaceAgentPluginCatalog.entries.any { it.id == agentId }) { "Unknown CLI agent" }
        try {
            refreshForAdmission()
            val mapper = requireNotNull(processHost.pathMapper()) { "CLI workspace mapping is unavailable; files were retained" }
            syncPhase(WorkspaceAgentSyncPhase.REVIEW, mapper)
            val before = requireAuthoritativeBaseline()
            val result = reconcileRemoteWorkspace(mapper, before, true,
                "workspace-review-${java.util.UUID.randomUUID()}", sessions[agentId]?.sessionId ?: agentId, onEvent = {})
            WorkspaceAgentSyncResult(result.first, result.second)
        } catch (failure: Throwable) {
            syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, notice = failure.message ?: "Review stopped; CLI files were retained")
            throw failure
        }
    }

    suspend fun availability(): List<WorkspaceAgentAvailability> =
        WorkspaceAgentAvailabilityProbe.inspect(processHost::resolveExecutable)

    override suspend fun runTurn(
        agentId: String,
        prompt: String,
        mode: WorkspaceAgentMode,
        resumeSessionId: String?,
        onEvent: (WorkspaceAgentEvent) -> Unit,
    ): WorkspaceAgentTurnResult = runMediaTurn(agentId, prompt, emptyList(), null, mode, resumeSessionId, onEvent)

    override fun mediaAdmissionError(agentId: String, media: List<ChatMediaRef>): String? {
        if (media.isEmpty()) return null
        val session = sessions[agentId]?.takeIf { it.process.isAlive && !it.lifetime.processes.isClosing }
            ?: return "Connect this CLI before sending media. Your draft was kept."
        return session.promptCapabilities.admissionError(media, fileBridgeAvailable = processHost is LocalAgentWorkspaceProcessHost)
    }

    override suspend fun runMediaTurn(
        agentId: String, prompt: String, media: List<ChatMediaRef>, mediaOwnerId: String?,
        mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit,
    ): WorkspaceAgentTurnResult {
        require(prompt.isNotBlank() && prompt.length <= 200_000) { "Agent prompt is empty or too large" }
        return workspaceTurns.withLock {
            val deferredSync = refreshForExecution()
            val session = ensureSession(agentId, resumeSessionId, onEvent)
            syncPhase(WorkspaceAgentSyncPhase.RUNNING, session.mapper, notice = deferredSync)
            deferredSync?.let { onEvent(WorkspaceAgentEvent.Status(it)) }
            session.mutex.withLock {
            // Reject native command/media combinations before touching attachment files or decoders.
            if (media.isNotEmpty()) AcpSessionCommands.nativePrompt(prompt, true, session.rpc.commands.snapshot(session.sessionId))
            session.promptCapabilities.admissionError(media, fileBridgeAvailable = processHost is LocalAgentWorkspaceProcessHost)?.let { error(it) }
            val owner = mediaOwnerId ?: "cli-turn-${java.util.UUID.randomUUID()}"
            val prepared = ChatMediaProcessing.prepareModelImages(projectRoot, media, owner)
            val store = ChatMediaStore(projectRoot)
            val fileInputs = withContext(Dispatchers.IO) {
                media.filterNot { it.mediaType.startsWith("image/") || it.mediaType.startsWith("video/") }
                    .map { it to requireNotNull(store.file(it)) { "Attached CLI file expired or is unavailable" } }
            }
            onEvent(WorkspaceAgentEvent.SessionBound(session.sessionId))
            onEvent(WorkspaceAgentEvent.Configuration(session.sessionId, session.configuration))
            // Preparation is read-only. Native mode confirmation happens below, immediately
            // before this turn receives mutation authority and the prompt is admitted.
            val preparationEpoch = session.rpc.prepareTurn()
            val turnGeneration = session.steering.begin()
            var steerSignal: Job? = null
            var attachmentAccess: AcpAttachmentAccess.Lease? = null
            try {
                if (processHost is LocalAgentWorkspaceProcessHost) {
                    val attachmentTurnJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                    attachmentAccess = withContext(Dispatchers.IO) {
                        AcpAttachmentAccess.bind(AndroidDevelopmentManager.stableProjectId(projectRoot), store, owner,
                            files, mode, turnJob = attachmentTurnJob, permission = { action, resource ->
                                PermissionApprovalGate.approved(permissions, approvals, action, resource,
                                    EXTERNAL_ACP_PERMISSION_SCOPE, "agent_plugin_attachment",
                                    if (action == "read") "Read attached file" else "Save attachment to $resource",
                                    "External CLI requested access to an original attachment in this conversation.",
                                    AgentRequestProvenance.primary(session.sessionId), session.approvalSessionId)
                            }, saved = { relative ->
                                session.rpc.recordAttachmentWrite(relative)
                                workspaceIo.pushNew(PathSecurity.resolveWithin(projectRoot, relative), session.mapper.localRelativeToRemote(relative))
                            }, destinationAvailable = { relative ->
                                workspaceIo.requireNewPath(session.mapper.localRelativeToRemote(relative))
                            })
                    }
                }
                session.attachments = attachmentAccess
                val ordinaryContent = withContext(Dispatchers.IO) {
                    AcpChatMedia.prompt(listOfNotNull(prompt, prepared.description, deferredSync).filter { it.isNotBlank() }.joinToString("\n\n"),
                        prepared.imagePaths, fileInputs, projectRoot, session.promptCapabilities, attachmentAccess, media)
                }
                val before = requireAuthoritativeBaseline()
                val selectedMode = selectTurnMode(session, mode, preparationEpoch, onEvent)
                val (activeEpoch, modeAdmission) = synchronized(session) {
                    val admission = session.modeSelection.admission(selectedMode, session.configuration, session.configurationRevision)
                    session.rpc.activateTurn(mode, preparationEpoch) to admission
                }
                // Mode selection may replace advertised commands. Admit on this same live session
                // after the last preparatory suspension, with no change to mode/permission gates.
                val content = AcpSessionCommands.nativePrompt(prompt, media.isNotEmpty(), session.rpc.commands.snapshot(session.sessionId))
                    ?: ordinaryContent
                val streamed = StringBuilder()
                val answerBoundary = AcpMessageBoundary()
                val thoughtBoundary = AcpMessageBoundary()
                // The RPC reader never decodes images or copies files. One bounded IO consumer owns that work.
                val mediaOutput = Channel<JsonObject>(4)
                val mediaRetention = AcpMediaOutputRetention(onFirstMediaImported)
                var mediaCount = 0
                var encodedImageChars = 0L
                val mediaConsumer = scope.async(Dispatchers.IO) {
                    for (block in mediaOutput) {
                        try {
                            readMediaOutput(block, store, owner, session, mediaRetention::imported)?.let { onEvent(WorkspaceAgentEvent.Media(it)) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { onEvent(WorkspaceAgentEvent.Status("CLI media output was unavailable or exceeded the supported limit.")) }
                    }
                }
                val terminalTools = mutableMapOf<String, List<String>>()
                val updateListener: (JsonObject) -> Unit = listener@{ update ->
                    when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
                        "agent_message_chunk" -> {
                            extractContentText(update["content"])?.let {
                                val separator = answerBoundary.prefix(messageId(update), it)
                                check(streamed.length.toLong() + separator.length + it.length <= 8_000_000) { "CLI reply exceeds the supported response size; partial history is preserved" }
                                streamed.append(separator).append(it)
                                onEvent(WorkspaceAgentEvent.Text(if (separator.isEmpty()) it else separator + it))
                            }
                            AcpChatMedia.typedOutput(update["content"])?.let { block ->
                                val imageChars = if ((block["type"] as? JsonPrimitive)?.contentOrNull == "image")
                                    (block["data"] as? JsonPrimitive)?.contentOrNull?.length?.toLong() ?: Long.MAX_VALUE else 0L
                                val encodedBudget = (VisionSupport.MAX_TOTAL_BYTES + 2) / 3 * 4
                                if (mediaCount < 4 && imageChars <= (VisionSupport.MAX_IMAGE_BYTES + 2) / 3 * 4 &&
                                    imageChars <= encodedBudget - encodedImageChars && mediaOutput.trySend(block).isSuccess) {
                                    mediaCount++; encodedImageChars += imageChars
                                }
                                else onEvent(WorkspaceAgentEvent.Status("Additional CLI media was skipped to protect device memory."))
                            }
                        }
                        "agent_thought_chunk" -> extractContentText(update["content"])?.let {
                            val separator = thoughtBoundary.prefix(messageId(update), it)
                            onEvent(WorkspaceAgentEvent.Thought(if (separator.isEmpty()) it else separator + it))
                        }
                        "tool_call", "tool_call_update" -> {
                            val decoded = WorkspaceAgentEvent.Tool.fromAcp(update, session.rpc::terminalPreview)
                            val event = decoded.copy(update = decoded.update?.withWorkspacePaths(session.mapper))
                            val patch = event.update
                            val references = (update["content"] as? JsonArray)?.mapNotNull { item ->
                                val block = item as? JsonObject ?: return@mapNotNull null
                                if ((block["type"] as? JsonPrimitive)?.contentOrNull != "terminal") null
                                else (block["terminalId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 256 }
                            }?.take(8)
                            if (patch != null && !references.isNullOrEmpty() && (patch.id in terminalTools || terminalTools.size < 256)) terminalTools[patch.id] = references
                            val output = patch?.let { terminalTools[it.id] }?.mapNotNull(session.rpc::terminalPreview)?.takeIf { it.isNotEmpty() }?.joinToString("\n")?.take(32_000)
                            onEvent(if (patch != null && output != null && !update.containsKey("content") && !update.containsKey("rawOutput")) event.copy(update = patch.copy(output = output)) else event)
                        }
                        "plan", "plan_update" -> onEvent(WorkspaceAgentEvent.Plan(
                            (update["entries"] as? JsonArray).orEmpty().take(64).mapNotNull { value ->
                                val entry = value as? JsonObject ?: return@mapNotNull null
                                val content = (entry["content"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                                ExternalAgentPlanEntry(content.take(1_000), (entry["status"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                            }))
                        "usage_update" -> WorkspaceAgentUsageSnapshot.fromUpdate(update)?.let { onEvent(WorkspaceAgentEvent.Usage(it)) }
                        "config_option_update", "current_model_update", "current_mode_update", "available_commands_update" ->
                            onEvent(WorkspaceAgentEvent.Configuration(session.sessionId, session.configuration))
                    }
                    session.steering.update(update, session.rpc.hasPendingClientCalls)?.let { generation ->
                        val signal = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            try {
                                if (session.steering.isOpen(generation)) {
                                    session.rpc.notify("session/cancel", buildJsonObject { put("sessionId", session.sessionId) })
                                    onEvent(WorkspaceAgentEvent.Status("Steering at a safe CLI boundary; continuing this session."))
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { onEvent(WorkspaceAgentEvent.Status("Steer remains pending until the CLI turn ends.")) }
                        }
                        steerSignal = signal
                        signal.start()
                    }
                }
                session.rpc.updateListener = updateListener
                val result = try {
                    session.rpc.request(
                        "session/prompt",
                        buildJsonObject {
                            put("sessionId", session.sessionId)
                            put("prompt", content)
                        },
                        timeoutMs = 0L,
                        beforeWrite = {
                            synchronized(session) {
                                session.modeSelection.requireCurrent(modeAdmission, session.configuration, session.configurationRevision)
                                session.rpc.requireTurn(activeEpoch)
                            }
                        },
                    ).also { response ->
                        require((response["stopReason"] as? JsonPrimitive)?.isString == true) {
                            "ACP prompt response requires a string stopReason"
                        }
                    }
                } catch (failure: Throwable) {
                    // A local cancellation or failed await is not the ACP turn's terminal response.
                    // V1 updates carry only sessionId: retire this exact transport before allowing
                    // another prompt to bind a listener. Keep the saved native ID for supported load.
                    try { retireSession(agentId, session) }
                    catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                    throw failure
                } finally {
                    session.rpc.updateListener = null
                    mediaOutput.close()
                    val finishingNormally = kotlinx.coroutines.currentCoroutineContext().isActive
                    if (!finishingNormally) mediaConsumer.cancel()
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (finishingNormally) mediaConsumer.await() else mediaConsumer.join()
                    }
                    session.steering.end(turnGeneration)
                    withContext(kotlinx.coroutines.NonCancellable) { steerSignal?.join() }
                }
                val stop = result.getValue("stopReason").jsonPrimitive.content
                attachmentAccess?.finish()
                val mirrorEditGrant = if (stop == "end_turn") session.rpc.freezeForReconciliation()
                    else { session.rpc.freezeMutations(); null }
                val authorizedWrites = session.rpc.drainDirectWrites()
                val reconciliation = try { if (deferredSync != null) {
                    deferSynchronization(session.mapper, deferredSync, onEvent)
                } else try {
                    reconcileRemoteWorkspace(
                        session.mapper,
                        before,
                        allowWorkspaceMutation = mode == WorkspaceAgentMode.EDIT,
                        approvalSessionId = session.approvalSessionId, provenanceSessionId = session.sessionId,
                        mirrorEditGrant = mirrorEditGrant, onEvent = onEvent,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    deferSynchronization(session.mapper, synchronizationFailureNotice(failure), onEvent)
                } } finally { mirrorEditGrant?.revoke() }
                WorkspaceAgentTurnResult(
                    agentId = agentId,
                    sessionId = session.sessionId,
                    text = streamed.toString(),
                    stopReason = stop,
                    changedFiles = (authorizedWrites + reconciliation.first).distinct(),
                    conflicts = reconciliation.second,
                )
            } catch (failure: Throwable) {
                // Returning from cleanup can rethrow cancellation before the next statement runs.
                syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, session.mapper,
                    notice = if (sessionOwners[agentId] === session.lifetime && session.lifetime.processes.isClosing)
                        "CLI cleanup is not confirmed. The session is still owned; retry cleanup before reconnecting."
                    else "The turn ended before synchronization completed. Review the retained CLI files before retrying.")
                attachmentAccess?.finish()
                session.rpc.drainDirectWrites()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { kotlinx.coroutines.withTimeout(15_000) { workspaceIo.refresh() } }
                }
                throw failure
            } finally {
                attachmentAccess?.finish()
                if (session.attachments === attachmentAccess) session.attachments = null
                session.steering.end(turnGeneration)
                withContext(kotlinx.coroutines.NonCancellable) { steerSignal?.join() }
                session.rpc.endTurn()
            }
        }
        }
    }

    override suspend fun cancel(agentId: String, expectedSessionId: String?) {
        val session = sessions[agentId] ?: return
        if (expectedSessionId != null && expectedSessionId != session.sessionId) return
        session.attachments?.close()
        try {
            session.rpc.cancelTurn(session.sessionId)
        } catch (cancelled: CancellationException) {
            // Stop can race the prompt's retirement, which cancels the transport's
            // pending write. Propagate cancellation only when this caller is cancelled.
            currentCoroutineContext().ensureActive()
            retireSession(agentId, session)
        } catch (_: Throwable) {
            // A transport failure can be retired, but a cleanup failure must remain visible.
            retireSession(agentId, session)
        }
    }

    override suspend fun requestSteer(agentId: String, expectedSessionId: String): Boolean {
        val session = sessions[agentId]?.takeIf { it.process.isAlive && it.sessionId == expectedSessionId } ?: return false
        return session.steering.request()
    }

    override suspend fun clearSteerRequest(agentId: String, expectedSessionId: String) {
        sessions[agentId]?.takeIf { it.sessionId == expectedSessionId }?.steering?.clearRequest()
    }

    override suspend fun newSession(
        agentId: String,
        onEvent: (WorkspaceAgentEvent) -> Unit,
    ): String = workspaceTurns.withLock {
        claimWorkspace()
        sessionLifecycle.withLock {
            retireSessionLocked(agentId)
            retireClosingSessionsLocked()
            currentCoroutineContext().ensureActive()
            startSession(agentId, onEvent, resumeSessionId = null).sessionId
        }
    }

    override suspend fun discardSession(agentId: String, expectedSessionId: String) = workspaceTurns.withLock {
        sessionLifecycle.withLock lifecycle@{
            val old = sessions[agentId]?.takeIf { it.sessionId == expectedSessionId } ?: return@lifecycle
            retireSessionLocked(agentId, old)
        }
    }

    /** Prepare once, then reuse the same live Agent session for models and later turns. */
    override suspend fun prepareSession(
        agentId: String,
        resumeSessionId: String?,
        onEvent: (WorkspaceAgentEvent) -> Unit,
    ): WorkspaceAgentEvent.Configuration = workspaceTurns.withLock {
        refreshForAdmission()
        val session = ensureSession(agentId, resumeSessionId, onEvent)
        WorkspaceAgentEvent.Configuration(session.sessionId, session.configuration).also {
            onEvent(WorkspaceAgentEvent.SessionBound(session.sessionId))
            onEvent(it)
        }
    }

    override suspend fun selectModel(
        agentId: String,
        expectedSessionId: String,
        modelId: String,
    ): WorkspaceAgentEvent.Configuration = workspaceTurns.withLock {
        val session = sessions[agentId]?.takeIf { !it.lifetime.processes.isClosing && it.process.isAlive && it.sessionId == expectedSessionId }
            ?: error("Agent session is not ready; refresh models before changing the model")
        session.mutex.withLock {
            val (method, params) = WorkspaceAgentSessionConfigurations.modelRequest(session.sessionId, session.configuration, modelId)
            try {
                session.rpc.request(method, params, AgentTimeoutPolicy.ACP_CONFIGURATION_MS, onResponse = { result ->
                    synchronized(session) {
                        session.replaceConfiguration(if (method == "session/set_config_option") {
                            require(result["configOptions"] is JsonArray) { "Agent did not return updated model configuration" }
                            WorkspaceAgentSessionConfigurations.merge(result, session.configuration)
                        } else {
                            WorkspaceAgentSessionConfigurations.merge(result, session.configuration).copy(currentModelId = modelId)
                        })
                    }
                })
            } catch (failure: Throwable) { retireUncertainConfiguration(session, failure) }
            WorkspaceAgentEvent.Configuration(session.sessionId, session.configuration)
        }
    }

    override suspend fun selectReasoning(agentId: String, expectedSessionId: String, value: String): WorkspaceAgentEvent.Configuration = workspaceTurns.withLock {
        val session = sessions[agentId]?.takeIf { !it.lifetime.processes.isClosing && it.sessionId == expectedSessionId && it.process.isAlive }
            ?: error("The CLI session changed; refresh configuration")
        session.mutex.withLock {
            val (method, params) = WorkspaceAgentSessionConfigurations.reasoningRequest(session.sessionId, session.configuration, value)
            try {
                session.rpc.request(method, params, AgentTimeoutPolicy.ACP_CONFIGURATION_MS, onResponse = { result ->
                    synchronized(session) {
                        require(result["configOptions"] is JsonArray) { "Agent did not return updated Reasoning configuration" }
                        session.replaceConfiguration(WorkspaceAgentSessionConfigurations.merge(result, session.configuration))
                    }
                })
            } catch (failure: Throwable) { retireUncertainConfiguration(session, failure) }
            WorkspaceAgentEvent.Configuration(session.sessionId, session.configuration)
        }
    }

    private suspend fun ensureSession(
        agentId: String,
        resumeSessionId: String?,
        onEvent: (WorkspaceAgentEvent) -> Unit,
    ): LiveSession {
        claimWorkspace()
        return sessionLifecycle.withLock {
            check(!closed.get()) { "CLI workspace host is closed" }
            scope.ensureActive()
            retireClosingSessionsLocked()
            currentCoroutineContext().ensureActive()
            sessions[agentId]?.takeIf { live ->
                !live.lifetime.processes.isClosing && live.process.isAlive && (resumeSessionId == null || resumeSessionId == live.sessionId)
            } ?: run {
                retireSessionLocked(agentId)
                currentCoroutineContext().ensureActive()
                startSession(agentId, onEvent, resumeSessionId)
            }
        }
    }

    private suspend fun claimWorkspace() = kotlinx.coroutines.coroutineScope {
        val caller = currentCoroutineContext()
        // A retiring host must also release a caller queued behind a replacement's claim.
        // Cancel this admission child, never the caller's unrelated parent work.
        val retirement = hostClosed.invokeOnCompletion { caller[Job]?.cancel(CancellationException("CLI workspace host is closed")) }
        try {
            WorkspaceAgentHostOwnership.claim(workspaceOwnerKey, this@WorkspaceAgentPluginHost) {
                caller.ensureActive()
                scope.ensureActive()
                check(!closed.get()) { "CLI workspace host is closed" }
            }
        } finally { retirement.dispose() }
    }

    private suspend fun startSession(
        agentId: String,
        onEvent: (WorkspaceAgentEvent) -> Unit,
        resumeSessionId: String?,
    ): LiveSession {
        val spec = WorkspaceAgentPluginCatalog.entries.firstOrNull { it.id == agentId }
            ?: error("Unknown workspace agent: $agentId")
        check(!closed.get()) { "CLI workspace host is closed" }
        scope.ensureActive()
        val lifetime = WorkspaceAgentSessionLifetime(scope)
        check(sessionOwners.putIfAbsent(agentId, lifetime) == null) { "Previous CLI session cleanup is still pending" }
        try {
            check(!closed.get()) { "CLI workspace host closed during startup" }
            val boot = WorkspaceAgentSessionBootstrap.start(
                spec, projectRoot, files, processHost, approvals, permissions, scope, resumeSessionId, onEvent, workspaceIo, lifetime,
            )
            currentCoroutineContext().ensureActive()
            scope.ensureActive()
            check(!closed.get()) { "CLI workspace host closed during startup" }
            return LiveSession(
                spec, boot.process, boot.rpc, boot.sessionId, boot.approvalSessionId, boot.mapper, boot.configuration, boot.promptCapabilities,
                lifetime,
            ).also { live ->
                // Do not hold live's monitor while entering delivery: reader delivery owns
                // delivery -> live -> RPC order. Seed and subscribe are one atomic handoff.
                live.rpc.installConfigurationListener(live.sessionId, onSnapshot = { configuration ->
                    synchronized(live) { live.replaceConfiguration(configuration) }
                }, onUpdate = { update ->
                    synchronized(live) {
                        val updated = WorkspaceAgentSessionConfigurations.update(update, live.configuration)
                        // Every mode replacement passes through the revision owner. A change
                        // away and back cannot resurrect an older prompt admission.
                        if (updated !== live.configuration) live.replaceConfiguration(updated)
                    }
                })
                sessions[agentId] = live
                if (syncState.value.executionRoot.isBlank()) syncPhase(WorkspaceAgentSyncPhase.ISOLATED, live.mapper)
            }
        } catch (failure: Throwable) {
            try { retireSessionLocked(agentId) }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private suspend fun selectTurnMode(session: LiveSession, mode: WorkspaceAgentMode, preparationEpoch: Long,
                                       onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentModeSelection.Selection {
        session.rpc.requireTurn(preparationEpoch)
        val (selection, selectedRevision) = synchronized(session) {
            session.modeSelection.resolve(mode, session.configuration) to session.configurationRevision
        }
        if (selection.needsRequest) {
            val (method, params) = synchronized(session) {
                check(session.configurationRevision == selectedRevision) { "CLI configuration changed before mode selection. No prompt was sent." }
                WorkspaceAgentSessionConfigurations.modeRequest(session.sessionId, session.configuration, selection.id, selection.collaborationMode)
            }
            try {
                session.rpc.request(method, params, AgentTimeoutPolicy.CLI_CONTROL_MS,
                    beforeWrite = {
                        synchronized(session) {
                            check(session.configurationRevision == selectedRevision) { "CLI configuration changed before mode selection. No prompt was sent." }
                            session.rpc.requireTurn(preparationEpoch)
                        }
                    }, onResponse = { result ->
                    // Apply in wire order, before a later config/mode update can overtake the response.
                    synchronized(session) {
                        session.replaceConfiguration(WorkspaceAgentSessionConfigurations.modeResponse(
                            result, session.configuration, selection.id, method == "session/set_config_option", selection.collaborationMode))
                        session.modeSelection.accepted(selection, session.configuration)
                    }
                })
                synchronized(session) { session.modeSelection.accepted(selection, session.configuration) }
            } catch (failure: Throwable) {
                // A cancelled/timed-out set request may have executed. Never reuse uncertain
                // native state, retry it blindly, or continue by sending the user's prompt.
                retireUncertainConfiguration(session, failure)
            }
        } else synchronized(session) { session.modeSelection.accepted(selection, session.configuration) }
        onEvent(WorkspaceAgentEvent.Configuration(session.sessionId, session.configuration))
        if (selection.nativeDefault) onEvent(WorkspaceAgentEvent.Status(
            "${session.spec.displayName} does not expose mode selection; using its native defaults. Droide file approvals remain active."))
        else if (selection.usedSafeFallback) onEvent(WorkspaceAgentEvent.Status(
            "The previous CLI edit profile is unavailable; using ${session.spec.displayName}'s safe default: ${selection.id}."))
        else if (selection.needsRequest) onEvent(WorkspaceAgentEvent.Status("${session.spec.displayName} mode: ${selection.id}"))
        return selection
    }

    private suspend fun retireUncertainConfiguration(session: LiveSession, failure: Throwable): Nothing {
        // Model and reasoning responses can also replace mode metadata. A late response
        // must not leave an apparently confirmed mode on a transport we continue to use.
        try { retireSession(session.spec.id, session) }
        catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        throw failure
    }

    private enum class ReconcileOperation { IMPORT_FILE, DELETE }

    private suspend fun readMediaOutput(block: JsonObject, store: ChatMediaStore, owner: String,
                                        session: LiveSession, onImported: suspend (ChatMediaRef) -> Unit): ChatMediaRef? {
        return AcpMediaOutputImporter.read(block, store, owner, session.mapper, workspaceIo, authorizeRead = { relative ->
            PermissionApprovalGate.approved(
                permissions = permissions, approvals = approvals, action = "read", resource = relative,
                permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE, kind = "agent_media_read",
                summary = "Show agent file in chat", detail = relative,
                provenance = AgentRequestProvenance.primary(session.sessionId), approvalSessionId = session.approvalSessionId,
            )
        }, onImported = onImported)
    }

    private data class ReconcileCandidate(
        val relativePath: String,
        val expectedRemote: WorkspaceSyncManifest.Entry?,
        val operation: ReconcileOperation,
        val localVersion: StreamFileVersion,
    )

    private suspend fun retainedConflictPaths(mapper: WorkspacePathMapper): Set<String> {
        val paths = linkedSetOf<String>()
        for (path in workspaceIo.retainedConflictPaths()) {
            require(paths.size < 128 && AgentWorkspacePathPolicy.visible(path) &&
                mapper.remoteToLocal("${mapper.remoteRoot}/$path") != null) {
                "Retained CLI conflict metadata is invalid; workspace publication stopped without clearing it"
            }
            paths += path
        }
        return paths
    }

    private fun overlapsRetainedConflict(path: String, conflicts: Set<String>): Boolean =
        conflicts.any { it == path || it.startsWith("$path/") || path.startsWith("$it/") }

    private suspend fun snapshotWorkspaceEntries(remote: String): Map<String, WorkspaceSyncManifest.Entry> {
        val current = workspaceIo.snapshotEntries() ?: run {
            val scan = workspaceIo.shellBounded(
                AgentWorkspacePathPolicy.hashScanCommand(remote, workspaceIo.checksumCommand),
                maxOutputBytes = 2_000_000,
            )
            check(scan.exitCode == 0) { "Could not inspect agent workspace changes: ${scan.combined.takeLast(4_000)}" }
            require(!scan.truncated) { "Agent workspace hash listing exceeds the supported output limit; CLI files were retained" }
            parseHashListing(scan.stdout, manifestFormat = false).mapValues { WorkspaceSyncManifest.Entry(it.value, null) }
        }
        require(current.size <= 20_000) { "Agent workspace produced too many files; CLI files were retained" }
        return current
    }

    // The editor workspace remains authoritative; inspect and approve mirror changes first.
    private suspend fun reconcileRemoteWorkspace(
        mapper: WorkspacePathMapper,
        before: Map<String, WorkspaceSyncManifest.Entry>,
        allowWorkspaceMutation: Boolean,
        approvalSessionId: String,
        provenanceSessionId: String,
        mirrorEditGrant: AcpMirrorEditGrant? = null,
        onEvent: (WorkspaceAgentEvent) -> Unit,
    ): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val remote = mapper.remoteRoot
        val observedBaseline = requireAuthoritativeBaseline()
        val retained = retainedConflictPaths(mapper)
        val current = snapshotWorkspaceEntries(remote)

        val candidates = mutableListOf<ReconcileCandidate>()
        val conflicts = retained.toMutableList()
        val reviewReasons = linkedMapOf<String, String>()
        fun explain(path: String, reason: String) {
            if (reviewReasons.size < 5 || path in reviewReasons) {
                reviewReasons[path] = reason.replace('\n', ' ').replace('\r', ' ').take(180)
            }
        }
        fun conflict(path: String, reason: String) {
            conflicts += path
            explain(path, reason)
        }
        fun failureReason(failure: Exception): String {
            fun message(error: Throwable) = (error as? java.nio.file.FileSystemException)?.reason
                ?: error.message ?: error.javaClass.simpleName
            val reason = message(failure)
            val cause = failure.cause?.takeIf { it !== failure } ?: return reason
            return "${reason.take(96)} Cause: ${message(cause).take(76)}"
        }
        retained.forEach { explain(it, "A retained CLI version needs recovery; applying the current file cannot resolve it.") }
        fun same(a: WorkspaceSyncManifest.Entry?, b: WorkspaceSyncManifest.Entry?): Boolean {
            if (a == null || b == null) return a == b
            return a.sha256 == b.sha256 && (a.mode == null || b.mode == null || a.mode == b.mode)
        }
        val touched = (before.keys + current.keys).toSortedSet()
        for (rel in touched) {
            currentCoroutineContext().ensureActive()
            if (same(before[rel], current[rel]) || !isSafeAgentRelativePath(rel) || overlapsRetainedConflict(rel, retained)) continue
            if (mapper.remoteToLocal("$remote/$rel") == null) continue
            val version = try { StreamFileVersion.capture(projectRoot, rel) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { conflict(rel, failureReason(failure)); continue }
            val local = if (version.existed) WorkspaceSyncManifest.Entry(version.sha256, if (version.executable) 493 else 420) else null
            val base = before[rel]
            val now = current[rel]
            // A prior publication (including ACP writes or a provider-sync failure)
            // may already have committed these bytes. Convergence is not a conflict.
            if (same(local, now)) continue
            if (base != observedBaseline[rel]) {
                conflict(rel, "Synchronization baseline changed for this file; review it again against the current project.")
                continue
            }
            if (!same(base, local)) {
                conflict(rel, "Project and CLI both changed since synchronization; both versions were preserved.")
                continue
            }
            candidates += ReconcileCandidate(rel, now,
                if (now == null) ReconcileOperation.DELETE else ReconcileOperation.IMPORT_FILE, version)
        }

        suspend fun refreshPending(existing: List<String>): List<String> {
            workspaceIo.refresh()
            val refreshedBase = requireAuthoritativeBaseline()
            val refreshedCurrent = snapshotWorkspaceEntries(remote)
            val pending = (existing + retainedConflictPaths(mapper)).toMutableSet()
            for (rel in (refreshedBase.keys + refreshedCurrent.keys).toSortedSet()) {
                currentCoroutineContext().ensureActive()
                if (same(refreshedBase[rel], refreshedCurrent[rel]) || !isSafeAgentRelativePath(rel) ||
                    mapper.remoteToLocal("$remote/$rel") == null) continue
                val local = try {
                    val version = StreamFileVersion.capture(projectRoot, rel)
                    if (version.existed) WorkspaceSyncManifest.Entry(version.sha256, if (version.executable) 493 else 420) else null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { pending += rel; continue }
                if (!same(local, refreshedCurrent[rel])) pending += rel
            }
            return pending.toList()
        }

        if (candidates.isNotEmpty() && !allowWorkspaceMutation) {
            val pending = refreshPending(conflicts + candidates.map { it.relativePath })
            syncPhase(WorkspaceAgentSyncPhase.PENDING, mapper, pending,
                "Plan mode kept these changes separate. Use Review files to explicitly apply them.")
            return@withContext emptyList<String>() to pending
        }

        val resource = candidates.take(24).joinToString(",") { it.relativePath }
            .let { paths -> "${candidates.size} files:$paths".take(4_096) }
        val exactEditApproved = candidates.singleOrNull()?.takeIf { it.operation == ReconcileOperation.IMPORT_FILE }
            ?.let { mirrorEditGrant?.consume("$remote/${it.relativePath}", it.relativePath, it.expectedRemote) } == true &&
            ApprovalPolicyGuard.remainsPermitted(permissions, requireNotNull(mirrorEditGrant).action,
                mirrorEditGrant.resource, EXTERNAL_ACP_PERMISSION_SCOPE)
        if (!exactEditApproved) mirrorEditGrant?.revoke()
        val publicationContext = currentCoroutineContext()
        fun requirePublication() {
            publicationContext.ensureActive()
            scope.ensureActive()
            if (exactEditApproved) {
                requireNotNull(mirrorEditGrant).requireCurrent()
                check(ApprovalPolicyGuard.remainsPermitted(permissions, mirrorEditGrant.action, mirrorEditGrant.resource,
                    EXTERNAL_ACP_PERMISSION_SCOPE)) { "Exact CLI edit is no longer permitted" }
            }
            check(ApprovalPolicyGuard.remainsPermitted(permissions, "edit_workspace", resource, EXTERNAL_ACP_PERMISSION_SCOPE)) {
                "Workspace application is no longer permitted; CLI files were retained"
            }
        }
        if (candidates.isNotEmpty()) {
            syncPhase(WorkspaceAgentSyncPhase.REVIEW, mapper, (conflicts + candidates.map { it.relativePath }).distinct())
            val preview = candidates.take(80).joinToString("\n") { candidate ->
                val verb = if (candidate.operation == ReconcileOperation.DELETE) "delete" else "write"
                "$verb ${candidate.relativePath}"
            } + if (candidates.size > 80) "\n… and ${candidates.size - 80} more" else ""
            val reviewDigest = MessageDigest.getInstance("SHA-256")
            fun reviewField(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                reviewDigest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                reviewDigest.update(':'.code.toByte())
                reviewDigest.update(bytes)
            }
            // Session consent binds both sides of this diff; filesystem admission remains separate.
            reviewField("workspace-reconcile-v2")
            reviewField(projectRoot.absolutePath)
            reviewField(remote)
            for (candidate in candidates) {
                reviewField(candidate.operation.name)
                reviewField(candidate.relativePath)
                reviewField(candidate.localVersion.existed.toString())
                reviewField(candidate.localVersion.sha256)
                reviewField(candidate.localVersion.executable.toString())
                reviewField(candidate.expectedRemote?.sha256.orEmpty())
                reviewField(candidate.expectedRemote?.mode?.toString().orEmpty())
            }
            val reconcileIdentity = reviewDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val diffs = if (permissions.decide("edit_workspace", resource, EXTERNAL_ACP_PERMISSION_SCOPE) == PermEffect.ASK) buildString {
                for (candidate in candidates.take(6)) {
                    val executable = when (candidate.expectedRemote?.mode) { 493 -> "yes"; 420 -> "no"; else -> "unchanged/deleted" }
                    append("\n\n${candidate.relativePath} · executable=$executable\n")
                    try {
                        val old = if (!candidate.localVersion.existed) "" else
                            WorkspaceReviewPreview.text(projectRoot, candidate.relativePath, candidate.localVersion.sha256)
                        val new = if (candidate.expectedRemote == null) "" else
                            workspaceIo.reviewText("$remote/${candidate.relativePath}", candidate.expectedRemote.sha256)
                        append(if (old != null && new != null) DiffUtil.unified(old, new, maxChangedLines = 24).take(1_500)
                            else "Binary/large file · SHA-256 ${candidate.expectedRemote?.sha256 ?: "deleted"}")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { append("Preview unavailable or changed; publication will recheck the approved bytes.") }
                    if (length >= 5_000) break
                }
            } else ""
            val approved = PermissionApprovalGate.approved(
                permissions = permissions,
                approvals = approvals,
                action = "edit_workspace",
                resource = resource,
                permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
                kind = "agent_workspace_reconcile",
                summary = "Apply ${candidates.size} workspace change${if (candidates.size == 1) "" else "s"} from external agent",
                detail = "Destination: ${projectRoot.absolutePath}\nCLI review workspace: $remote\n" +
                    "This approval applies files to the project; CLI tool approvals are separate.\n\n$preview\n" +
                    "Bounded preview (up to 6 files):$diffs",
                provenance = AgentRequestProvenance.primary(provenanceSessionId),
                approvalSessionId = approvalSessionId,
                sessionGrantResource = reconcileIdentity,
                preapproved = exactEditApproved,
            )
            if (!approved) {
                // Keep unapproved CLI changes pending; never publish or discard them.
                val pending = refreshPending(conflicts + candidates.map { it.relativePath })
                syncPhase(WorkspaceAgentSyncPhase.PENDING, mapper, pending,
                    "Project application was not approved. CLI files are retained; use Review files to retry.")
                return@withContext emptyList<String>() to pending
            }
        }

        val retainedAtPublication = if (candidates.isEmpty()) retained else retainedConflictPaths(mapper)
        val publicationBaseline = if (candidates.isEmpty()) observedBaseline else requireAuthoritativeBaseline()
        conflicts += retainedAtPublication.filterNot { it in conflicts }
        retainedAtPublication.forEach { explain(it, "A retained CLI version needs recovery; applying the current file cannot resolve it.") }
        val changed = mutableListOf<String>()
        if (candidates.isNotEmpty()) syncPhase(WorkspaceAgentSyncPhase.APPLYING, mapper,
            (conflicts + candidates.map { it.relativePath }).distinct())
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val rel = candidate.relativePath
            if (before[rel] != publicationBaseline[rel]) {
                conflict(rel, "Synchronization baseline changed during approval; review this file again.")
                continue
            }
            if (overlapsRetainedConflict(rel, retainedAtPublication)) {
                if (rel !in conflicts) conflicts += rel
                continue
            }
            val remotePath = "$remote/$rel"
            try {
                workspaceIo.requirePath(remotePath)
                requirePublication()
                when (candidate.operation) {
                    ReconcileOperation.DELETE -> {
                        if (files.deleteFileIfUnchanged(rel, candidate.localVersion) {
                            requirePublication()
                            workspaceIo.requireExecutionAbsent(remotePath)
                        }) changed += rel
                    }
                    ReconcileOperation.IMPORT_FILE -> {
                        val transferDirectory = File(cacheDir, "agent-reconcile")
                        require(!Files.isSymbolicLink(transferDirectory.toPath())) { "Invalid transfer directory" }
                        check(transferDirectory.isDirectory || transferDirectory.mkdirs()) { "Transfer storage is unavailable" }
                        val temp = Files.createTempFile(transferDirectory.toPath(), "artifact-", ".tmp").toFile()
                        try {
                            onEvent(WorkspaceAgentEvent.Status("Applying workspace file: $rel"))
                            workspaceIo.pull(remotePath, temp)
                            val expected = requireNotNull(candidate.expectedRemote)
                            if (expected.mode != null) check((java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE in
                                    Files.getPosixFilePermissions(temp.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) == (expected.mode == 493)) {
                                "CLI executable state changed during review; the file was retained"
                            }
                            val expectedHash = expected.sha256
                            files.writeStream(rel,
                                Files.newInputStream(temp.toPath(), java.nio.file.StandardOpenOption.READ,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS),
                                expectedBytes = temp.length(), requireAbsent = !candidate.localVersion.existed,
                                expectedVersion = candidate.localVersion,
                                executable = expected.mode?.let { it == 493 },
                                publicationObserver = object : StreamPublicationObserver {
                                    override fun beforePublish(preparation: StreamPublicationPreparation) {
                                        requirePublication()
                                        check(preparation.sha256 == expectedHash) {
                                            "CLI output changed during transfer; it remains in the CLI workspace"
                                        }
                                    }
                                    override fun onPublished(receipt: StreamPublicationReceipt) = Unit
                                })
                            changed += rel
                        } finally { temp.delete() }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                conflict(rel, failureReason(failure))
                onEvent(WorkspaceAgentEvent.Status("Workspace file needs review: $rel — " +
                    failureReason(failure).take(400)))
            }
        }
        // Three-way refresh advances successful publications and preserves pending
        // CLI bytes/deletions. It never resets the CLI tree after a conflict or denial.
        val pending = try {
            refreshPending(conflicts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withContext deferSynchronization(mapper, synchronizationFailureNotice(failure), onEvent, changed, conflicts)
        }
        syncPhase(if (pending.isEmpty()) WorkspaceAgentSyncPhase.SYNCED else WorkspaceAgentSyncPhase.PENDING,
            mapper, pending,
            if (pending.isEmpty()) "Applied ${changed.distinct().size} reviewed file(s). Excluded paths are not part of this synchronization."
            else buildString {
                append("Applied ${changed.distinct().size} file(s); ${pending.size} still need review. CLI files were preserved.")
                for ((path, reason) in reviewReasons) {
                    if (path in pending) append("\n${path.replace('\n', ' ').replace('\r', ' ').take(160)}: $reason")
                }
                if (pending.any { it !in reviewReasons }) append("\nOther pending paths are listed below. Review them against the current project.")
            })
        changed.distinct() to pending
    }

    private suspend fun requireAuthoritativeBaseline(): Map<String, WorkspaceSyncManifest.Entry> =
        workspaceIo.baselineEntries() ?: error(
            "Authoritative host workspace baseline is unavailable. Project and CLI files were preserved; " +
                "a guest manifest cannot authorize publication. Restore the host metadata or use the existing Ubuntu workspace."
        )

    private fun parseHashListing(text: String, manifestFormat: Boolean): Map<String, String> {
        return if (manifestFormat) AgentWorkspacePathPolicy.manifest(text) else AgentWorkspacePathPolicy.hashListing(text)
    }

    private fun isSafeAgentRelativePath(path: String): Boolean = AgentWorkspacePathPolicy.visible(path)

    private fun messageId(update: JsonObject): String? {
        val id = update["messageId"] ?: return null
        if (id == JsonNull) return null
        require(id is JsonPrimitive && id.isString) { "CLI message identifier is not a string" }
        return id.content
    }

    private fun extractContentText(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull
        else -> null
    }

    private fun sha256String(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun retireSession(agentId: String, expected: LiveSession) = withContext(kotlinx.coroutines.NonCancellable) {
        sessionLifecycle.withLock { retireSessionLocked(agentId, expected) }
    }

    private suspend fun retireClosingSessionsLocked() {
        // Switching the selected CLI must not bypass a failed cleanup from another agent
        // in this workspace. Healthy sibling sessions remain owned and are not stopped.
        for ((id, owner) in sessionOwners.entries.toList()) {
            if (owner.processes.isClosing) retireSessionLocked(id)
        }
    }

    private suspend fun retireSessionLocked(agentId: String, expected: LiveSession? = null) {
        val session = sessions[agentId]
        if (expected != null && session !== expected) return
        val owner = sessionOwners[agentId] ?: return
        try {
            withContext(kotlinx.coroutines.NonCancellable) {
                OwnedResourceCleanup.runSuspend(
                    { session?.attachments?.close() },
                    { session?.let { approvals.clearSessionApprovals(it.approvalSessionId, EXTERNAL_ACP_PERMISSION_SCOPE) } },
                    { owner.closeAndAwait() },
                ).getOrThrow()
            }
            if (session != null) sessions.remove(agentId, session)
            sessionOwners.remove(agentId, owner)
        } catch (failure: Throwable) {
            syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, notice =
                "CLI cleanup failed or is incomplete. The session is still owned; retry cleanup before reconnecting. ${failure.message.orEmpty().take(500)}")
            throw failure
        }
    }

    suspend fun closeAndAwait() = withContext(kotlinx.coroutines.NonCancellable) {
        closed.set(true)
        hostClosed.complete(Unit)
        sessionOwners.values.forEach { it.processes.closeAdmission() }
        scope.cancel()
        sessionLifecycle.withLock {
            OwnedResourceCleanup.runSuspend(*sessionOwners.keys.toList().map { id -> suspend {
                retireSessionLocked(id)
            } }.toTypedArray()).getOrThrow()
        }
        // Process/RPC completion also has to precede the old turn's reconciliation and
        // attachment finalizers. A replacement host must not race those workspace writes.
        try {
            kotlinx.coroutines.withTimeout(15_000) {
                workspaceTurns.withLock { Unit }
                scope.coroutineContext[Job]?.join()
            }
        } catch (failure: Exception) {
            syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, notice = "CLI workspace finalization is incomplete; the old host remains owned.")
            throw IllegalStateException("CLI workspace finalization is not confirmed; retry cleanup before reconnecting", failure)
        }
        WorkspaceAgentHostOwnership.release(workspaceOwnerKey, this@WorkspaceAgentPluginHost)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        hostClosed.complete(Unit)
        val stopped = OwnedResourceCleanup.run(*sessionOwners.values.map { owner -> { owner.requestStop().getOrThrow() } }.toTypedArray())
        scope.cancel()
        // Closeable requests stop. Graceful workspace shutdown uses closeAndAwait; this retained
        // worker also drains ordinary close, and keeps failure visible in synchronization state.
        scope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            runCatching { closeAndAwait() }.onFailure {
                syncPhase(WorkspaceAgentSyncPhase.INTERRUPTED, notice = "CLI cleanup failed: ${it.message}; process ownership was retained.")
            }
        }
        stopped.getOrThrow()
    }
}

 
internal class AcpNdjsonConnection(
    private val process: HostedStdioProcess,
    private val files: FileRepository,
    private val mapper: WorkspacePathMapper,
    private val processHost: StdioProcessHost,
    private val approvals: ApprovalManager,
    private val permissions: PermissionEngine,
    private val scope: CoroutineScope,
    private val workspaceIo: AgentWorkspaceIo,
    val commands: AcpSessionCommands = AcpSessionCommands(),
    maxInboundRetainedChars: Long = 8_000_000L,
    private val maxInboundIdChars: Int = 512,
    maxInboundRetainedRequests: Int = MAX_INBOUND_REQUESTS,
) : Closeable {
    private data class InboundCall(val method: String, val job: Job)

    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong(1)
    private val outboundInFlight = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Long, AcpOutboundCall>()
    private val inbound = ConcurrentHashMap<String, InboundCall>()
    private val inboundRetention = RetainedPayloadBudget(maxInboundRetainedChars, maxInboundRetainedRequests)
    internal val retainedInboundChars: Long get() = inboundRetention.retainedChars
    init { require(maxInboundIdChars > 0 && maxInboundRetainedRequests in 1..MAX_INBOUND_REQUESTS) }
    private val directWrites = ConcurrentHashMap.newKeySet<String>()
    private val permissionLedger = AcpPermissionLedger()
    private var mirrorEdit: AcpMirrorEditGrant.Proposal? = null
    private var mirrorRevision = 0L
    private val accessPolicy = WorkspaceAgentAccessPolicy()
    private val terminal = AcpTerminalController(processHost, mapper, approvals, permissions, permissionLedger, accessPolicy, scope,
        lifecycleLock = this)
    private val writer = OutputStreamWriter(process.stdin, Charsets.UTF_8).buffered()
    private val writeMutex = Mutex()
    private val writes = ConcurrentHashMap.newKeySet<Job>()
    private var writerClose: kotlinx.coroutines.Deferred<Unit>? = null
    private val stderrLog = InstallerProgressLog()
    @Volatile private var stderrTail = ""
    private val stderrJob: Job
    private val readerJob: Job
    @Volatile private var sessionId: String? = null
    @Volatile private var approvalSessionId: String? = null
    @Volatile private var readSessionBinding: Any? = null
    @Volatile private var mutationTurnBinding: Any? = null
    private val authorityEpoch = AtomicLong(0)
    @Volatile var updateListener: ((JsonObject) -> Unit)? = null
    private val configurationDelivery = AcpStartupConfigurationHandoff(commands,
        getSessionId = { sessionId }, hasReadBinding = { readSessionBinding != null },
        getTransportFailure = { transportFailure })
    var configurationListener: ((JsonObject) -> Unit)?
        get() = configurationDelivery.listener
        set(value) { configurationDelivery.listener = value }
    val hasPendingClientCalls: Boolean get() = inbound.isNotEmpty()
    @Volatile private var transportFailure: Throwable? = null

    init {
        // Keep the diagnostic pipe flowing independently of ACP stdout. A CLI
        // can otherwise block before replying when its stderr pipe fills up.
        stderrJob = scope.launch(Dispatchers.IO) {
            try {
                InputStreamReader(process.stderr, Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2_048)
                    while (isActive) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (count > 0) stderrTail = stderrLog.append(String(buffer, 0, count))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Closing an owned diagnostic pipe is expected. */ }
        }
        readerJob = scope.launch(Dispatchers.IO) {
            var readerFailure: Throwable? = null
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                BufferedReader(InputStreamReader(process.stdout, decoder)).use { reader ->
                    // One four-MiB ACP image is roughly 5.6 million base64 characters.
                    val lines = BoundedProtocolLineReader(reader, 6_000_000)
                    while (isActive) {
                        val line = lines.readLine() ?: break
                        if (line.isBlank()) continue
                        // Guard depth outside parse recovery: an unsafe frame must retire the transport.
                        ProviderPayloadLimits.checkedJson(line, maxChars = 6_000_000)
                        val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        val rawId = msg["id"]
                        val numericId = rawId?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        if (rawId != null && ("result" in msg || "error" in msg)) {
                            if (numericId != null) pending.remove(numericId)?.let { call ->
                                AcpResponseCompletion.complete(msg, call, configurationDelivery::retainStartupConfiguration)
                            }
                            continue
                        }
                        val method = msg["method"]?.jsonPrimitive?.contentOrNull ?: continue
                        val params = (msg["params"] as? JsonObject) ?: JsonObject(emptyMap())
                        if (rawId == null) {
                            if (method == "session/update") {
                                commands.observe(params)
                                val reportedSessionId = (params["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                                val update = params["update"] as? JsonObject
                                if (update != null && reportedSessionId != null) {
                                    configurationDelivery.observe(reportedSessionId, update)
                                    if (sessionId != null && reportedSessionId == sessionId) updateListener?.invoke(update)
                                }
                            }
                        } else {
                            // Earlier dispatch already requires a primitive ID. Bound it
                            // before escaping a scalar into the cancellation lookup key.
                            check(rawId.jsonPrimitive.content.length <= maxInboundIdChars) {
                                "ACP request identifier exceeds the supported size"
                            }
                            val key = rawId.toString()
                            if (inbound.size >= MAX_INBOUND_REQUESTS || inbound.containsKey(key)) {
                                send(buildJsonObject {
                                    put("jsonrpc", "2.0")
                                    put("id", rawId)
                                    put("error", buildJsonObject {
                                        put("code", -32000)
                                        put("message", if (inbound.containsKey(key)) "Duplicate ACP request id" else "Too many concurrent ACP client requests")
                                    })
                                })
                                continue
                            }
                            // Charge the already bounded wire payload without serializing
                            // the parsed params. Map removal does not end a job's lifetime.
                            val lease = inboundRetention.tryAcquire(line.length.toLong())
                            if (lease == null) {
                                send(buildJsonObject {
                                    put("jsonrpc", "2.0")
                                    put("id", rawId)
                                    put("error", buildJsonObject {
                                        put("code", -32000)
                                        put("message", "ACP client request buffer is full; request was not started")
                                    })
                                })
                                continue
                            }
                            val receivedEpoch = authorityEpoch.get()
                            val job = try {
                                scope.launch(start = CoroutineStart.LAZY) {
                                    handleInboundRequest(rawId, method, params, receivedEpoch)
                                }
                            } catch (failure: Throwable) {
                                lease.release()
                                throw failure
                            }
                            val call = InboundCall(method, job)
                            job.invokeOnCompletion {
                                inbound.remove(key, call)
                                lease.release()
                            }
                            if (inbound.putIfAbsent(key, call) == null) {
                                // An already cancelled scope may complete the lazy job and
                                // its hook before this map insertion.
                                if (!job.start()) inbound.remove(key, call)
                            } else {
                                job.cancel()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                readerFailure = cancelled
                throw cancelled
            } catch (error: Exception) {
                readerFailure = error
            } finally {
                val failure = IllegalStateException(diagnosticMessage(readerFailure?.message ?: "ACP agent process closed"), readerFailure)
                transportFailure = failure
                pending.values.forEach { it.deferred.completeExceptionally(failure) }
                pending.clear()
                inbound.values.forEach { it.job.cancel() }
                inbound.clear()
                // EOF can occur while the underlying host remains alive. Reap this
                // connection's processes; IO failure must not escape its launch job.
                synchronized(this@AcpNdjsonConnection) {
                    authorityEpoch.incrementAndGet()
                    mutationTurnBinding = null
                    readSessionBinding = null
                    permissionLedger.clear()
                    mirrorEdit = null; mirrorRevision++
                    accessPolicy.setMode(WorkspaceAgentMode.PLAN)
                }
                runCatching { terminal.close() }
                stderrJob.cancel()
                runCatching { process.close() }
            }
        }
    }

    fun installConfigurationListener(expectedSessionId: String,
                                     onSnapshot: (WorkspaceAgentSessionConfiguration) -> Unit,
                                     onUpdate: (JsonObject) -> Unit) =
        configurationDelivery.installConfigurationListener(expectedSessionId, onSnapshot, onUpdate)

    @Synchronized
    fun bindSession(value: String, approvalOwner: String) {
        require(value.isNotBlank() && value.length <= 512) { "Invalid ACP session id" }; require(approvalOwner.isNotBlank() && approvalOwner.length <= 512) { "Invalid ACP approval session id" }
        // A connection owns one native session. Retire its outstanding authority
        // before binding can reject reuse; queued file replies/writes must not
        // retain authority from a failed ownership transition.
        authorityEpoch.incrementAndGet()
        readSessionBinding = null
        mutationTurnBinding = null
        permissionLedger.clear()
        mirrorEdit = null; mirrorRevision++
        terminal.cancelActive()
        accessPolicy.setMode(WorkspaceAgentMode.PLAN)
        commands.bind(value)
        approvals.clearSessionApprovals(approvalOwner, EXTERNAL_ACP_PERMISSION_SCOPE)
        approvalSessionId = approvalOwner; terminal.bindApprovalSession(approvalOwner)
        directWrites.clear()
        sessionId = value
        readSessionBinding = Any()
    }

    @Synchronized
    fun beginTurn(mode: WorkspaceAgentMode) {
        authorityEpoch.incrementAndGet()
        mutationTurnBinding = null
        permissionLedger.clear()
        mirrorEdit = null; mirrorRevision++
        terminal.cancelActive()
        directWrites.clear()
        accessPolicy.setMode(mode)
        if (mode == WorkspaceAgentMode.EDIT) mutationTurnBinding = Any()
    }

    @Synchronized
    fun prepareTurn(): Long {
        beginTurn(WorkspaceAgentMode.PLAN)
        return authorityEpoch.get()
    }

    @Synchronized
    fun activateTurn(mode: WorkspaceAgentMode, preparationEpoch: Long): Long {
        requireTurn(preparationEpoch)
        beginTurn(mode)
        return authorityEpoch.get()
    }

    @Synchronized
    fun requireTurn(epoch: Long) {
        check(authorityEpoch.get() == epoch && transportFailure == null && readSessionBinding != null) {
            "CLI turn was stopped or replaced. No new prompt was sent."
        }
    }

    @Synchronized
    fun freezeMutations() {
        authorityEpoch.incrementAndGet()
        mutationTurnBinding = null
        permissionLedger.clear()
        mirrorEdit = null; mirrorRevision++
        terminal.cancelActive()
        accessPolicy.setMode(WorkspaceAgentMode.PLAN)
    }

    /** Only normal completion may transfer unused exact edit consent to the frozen turn. */
    @Synchronized
    fun freezeForReconciliation(): AcpMirrorEditGrant? {
        val proposal = mirrorEdit
        val binding = readSessionBinding
        val sid = sessionId
        val owner = approvalSessionId
        val eligible = mutationTurnBinding != null && accessPolicy.allowsWorkspaceMutation() &&
            binding != null && sid != null && owner != null && transportFailure == null
        freezeMutations()
        val frozenEpoch = authorityEpoch.get()
        val frozenRevision = mirrorRevision
        return if (!eligible || proposal == null) null else AcpMirrorEditGrant(proposal) {
            synchronized(this) {
                authorityEpoch.get() == frozenEpoch && mirrorRevision == frozenRevision && transportFailure == null &&
                    readSessionBinding === binding && sessionId == sid && approvalSessionId == owner && scope.isActive
            }
        }
    }

    fun endTurn() = freezeMutations()

    fun terminalPreview(id: String): String? = terminal.preview(id)

    fun recordAttachmentWrite(relativePath: String) {
        accessPolicy.requireMutation("attachment save")
        directWrites += relativePath
    }

    fun drainDirectWrites(): Set<String> {
        val out = directWrites.toSet()
        directWrites.removeAll(out)
        return out
    }

    suspend fun request(method: String, params: JsonObject, timeoutMs: Long,
                        onResponse: ((JsonObject) -> Unit)? = null, beforeWrite: (() -> Unit)? = null): JsonObject {
        transportFailure?.let { throw IllegalStateException("ACP agent transport is closed", it) }
        val inFlight = outboundInFlight.incrementAndGet()
        if (inFlight > MAX_PENDING_REQUESTS) {
            outboundInFlight.decrementAndGet()
            error("Too many pending ACP requests")
        }
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = AcpOutboundCall(deferred, onResponse, method,
            (params["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
        return try {
            // Close may race insertion into pending after the first check.
            transportFailure?.let { throw IllegalStateException("ACP agent transport is closed", it) }
            val call: suspend () -> JsonObject = {
                send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }, beforeWrite)
                deferred.await()
            }
            if (timeoutMs > 0) kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { call() }
                ?: throw AcpRequestTimeoutException(diagnosticMessage("ACP $method did not respond within ${timeoutMs / 1_000}s"))
            else call()
        } finally {
            pending.remove(id)
            outboundInFlight.decrementAndGet()
        }
    }

    suspend fun notify(method: String, params: JsonObject) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })
    }

    suspend fun cancelTurn(expectedSessionId: String) {
        require(sessionId == expectedSessionId) { "ACP session mismatch" }
        synchronized(this) {
            authorityEpoch.incrementAndGet()
            mutationTurnBinding = null
            accessPolicy.setMode(WorkspaceAgentMode.PLAN)
            permissionLedger.clear()
            mirrorEdit = null; mirrorRevision++
        }
        terminal.cancelActive()
        val calls = inbound.entries.toList()
        for ((key, call) in calls) {
            if (!inbound.remove(key, call)) continue
            call.job.cancel()
            val id = runCatching { json.parseToJsonElement(key) }.getOrNull() ?: continue
            if (call.method == "session/request_permission") {
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", buildJsonObject {
                        put("outcome", buildJsonObject { put("outcome", "cancelled") })
                    })
                })
            } else {
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", buildJsonObject { put("code", -32800); put("message", "ACP request cancelled") })
                })
            }
        }
        notify("session/cancel", buildJsonObject { put("sessionId", expectedSessionId) })
    }

    private suspend fun handleInboundRequest(id: JsonElement, method: String, params: JsonObject, receivedEpoch: Long) {
        val turnBound = method == "session/request_permission" || method == "fs/write_text_file" || method == "terminal/create"
        fun requireReceivedAuthority() {
            if (turnBound) synchronized(this) {
                check(authorityEpoch.get() == receivedEpoch) {
                    "ACP client request belongs to a retired turn or session"
                }
            }
        }
        try {
            requireReceivedAuthority()
            validateSession(params)
            val result = when (method) {
                "session/request_permission" -> handlePermission(params, receivedEpoch)
                "fs/read_text_file" -> { handleRead(id, params); return }
                "fs/write_text_file" -> handleWrite(params, receivedEpoch)
                "terminal/create" -> terminal.create(params) { authorityEpoch.get() == receivedEpoch }
                "terminal/output" -> terminal.output(params)
                "terminal/wait_for_exit" -> terminal.waitForExit(params)
                "terminal/kill" -> terminal.kill(params)
                "terminal/release" -> terminal.release(params)
                else -> throw IllegalArgumentException("Droide does not expose ACP client method: $method")
            }
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) },
                beforeWrite = ::requireReceivedAuthority)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            try {
                send(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    put("error", buildJsonObject { put("code", -32603); put("message", boundedDiagnosticText(failure.message ?: "ACP client request failed", 2_000)) })
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (transportError: Exception) {
                transportFailure = transportFailure ?: transportError
                pending.values.forEach { it.deferred.completeExceptionally(transportError) }
                pending.clear()
                runCatching { process.close() }
            }
        }
    }

    private fun validateSession(params: JsonObject) {
        val expected = sessionId ?: error("ACP client request arrived before session binding")
        val supplied = params["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: error("ACP client request is missing required sessionId")
        require(supplied == expected) { "ACP request targeted a different session" }
    }

    private suspend fun handlePermission(params: JsonObject, receivedEpoch: Long): JsonObject {
        val proposalRevision = synchronized(this) { mirrorEdit = null; ++mirrorRevision }
        val binding = readSessionBinding ?: error("ACP session is not bound")
        val permissionEpoch = authorityEpoch.get()
        val turn = mutationTurnBinding
        val owner = approvalSessionId ?: error("ACP approval session is not bound")
        val sid = sessionId ?: error("ACP session is not bound")
        fun isCurrent(): Boolean = readSessionBinding === binding && sessionId == sid &&
            approvalSessionId == owner && mutationTurnBinding === turn && authorityEpoch.get() == permissionEpoch && permissionEpoch == receivedEpoch
        val options = (params["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val tool = params["toolCall"] as? JsonObject
        val toolCallId = tool?.get("toolCallId")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: error("ACP permission request is missing valid toolCallId")
        val rawTitle = tool["title"]?.jsonPrimitive?.contentOrNull
            ?: tool["kind"]?.jsonPrimitive?.contentOrNull
            ?: "Agent tool request"
        val title = boundedDiagnosticText(rawTitle)
        val detail = boundedJsonDiagnostic(tool)
        val toolKind = tool["kind"]?.jsonPrimitive?.contentOrNull
        val requestedGrant = permissionIdentity(tool)
        val exactMirrorEdit = if (requestedGrant?.kind == AcpPermissionKind.MUTATION && turn != null &&
            accessPolicy.allowsWorkspaceMutation() && isCurrent() && transportFailure == null) {
            try {
                val raw = tool["rawInput"] as? JsonObject
                val content = raw?.let { firstString(it, "content", "text", "newText", "new_text") }
                val path = raw?.let { firstString(it, "path", "filePath", "file_path") }
                    ?.let(::normalizeAgentPathForFingerprint)
                if (content == null || path == null || content.toByteArray(Charsets.UTF_8).size > 2_000_000) null
                else {
                    val local = mapper.remoteToLocal(path) ?: error("Edit escaped workspace")
                    val rel = local.relativeTo(files.root).invariantSeparatorsPath
                    AcpMirrorEditGrant.Proposal(files.root, path, rel, AcpMirrorEditGrant.contentHash(content),
                        AcpWorkspaceText.version(files, rel), requestedGrant.action, toolCallId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // Not an exact text proposal: retain the separate diff review.
        } else null
        if (!accessPolicy.allowsWorkspaceMutation() && (requestedGrant != null || accessPolicy.rejectsPermissionKind(toolKind))) {
            return permissionOutcome(options, allowed = false)
        }
        val approved = approvals.withApprovalOwner(ApprovalOwnerAdmission(this, ::isCurrent)) {
            if (requestedGrant != null) {
                PermissionApprovalGate.approved(
                    permissions = permissions,
                    approvals = approvals,
                    action = requestedGrant.action,
                    resource = requestedGrant.policyResource,
                    permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
                    kind = "agent_plugin_tool",
                    summary = title,
                    detail = detail,
                    provenance = AgentRequestProvenance.primary(sid), approvalSessionId = owner,
                    sessionGrantAction = requestedGrant.action,
                    sessionGrantResource = requestedGrant.sessionGrantResource,
                )
            } else {
                PermissionApprovalGate.approved(
                    permissions = permissions,
                    approvals = approvals,
                    action = "agent_tool",
                    resource = rawTitle.take(4_096),
                    permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
                    kind = "agent_plugin_tool",
                    summary = title,
                    detail = detail,
                    provenance = AgentRequestProvenance.primary(sid), approvalSessionId = owner,
                )
            }
        }
        // A user response may arrive after Stop, a new turn, or failed rebind.
        // It must neither authorize the old CLI nor seed the next turn's ledger.
        val stillApproved = synchronized(this) {
            val current = approved && isCurrent() &&
                (requestedGrant == null || accessPolicy.allowsWorkspaceMutation()) &&
                !accessPolicy.rejectsPermissionKind(toolKind)
            if (current) requestedGrant?.let { permissionLedger.grant(it.kind, toolCallId, it.operationFingerprint) }
            if (current && proposalRevision == mirrorRevision && turn != null && transportFailure == null && options.firstOrNull {
                    it["kind"]?.jsonPrimitive?.contentOrNull == "allow_once"
                }?.get("optionId")?.jsonPrimitive?.contentOrNull != null) mirrorEdit = exactMirrorEdit

            current
        }
        return permissionOutcome(options, stillApproved)
    }

    private fun permissionOutcome(options: List<JsonObject>, allowed: Boolean): JsonObject {
        val wanted = if (allowed) "allow_once" else "reject_once"
        val selected = options.firstOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == wanted }
            ?.get("optionId")?.jsonPrimitive?.contentOrNull
        val outcome = if (selected != null) {
            buildJsonObject { put("outcome", "selected"); put("optionId", selected) }
        } else {
            buildJsonObject { put("outcome", "cancelled") }
        }
        return buildJsonObject { put("outcome", outcome) }
    }

    private fun permissionIdentity(tool: JsonObject): AcpPermissionIdentity? {
        val raw = tool["rawInput"] as? JsonObject ?: return null
        return when (tool["kind"]?.jsonPrimitive?.contentOrNull) {
            "edit" -> {
                val path = firstString(raw, "path", "filePath", "file_path") ?: return null
                val content = firstString(raw, "content", "text", "newText", "new_text") ?: return null
                val canonicalPath = normalizeAgentPathForFingerprint(path) ?: return null
                val local = mapper.remoteToLocal(canonicalPath) ?: return null
                val rel = local.relativeTo(files.root).invariantSeparatorsPath
                AcpPermissionIdentities.mutation(canonicalPath, content, rel)
            }
            "execute" -> {
                val command = firstString(raw, "command", "executable") ?: return null
                if (runCatching { ProcessSecurityPolicy.requireExecutableName(command) }.isFailure) return null
                val args = when (val rawArgs = raw["args"]) {
                    null, JsonNull -> emptyList()
                    is JsonArray -> rawArgs.map { element ->
                        (element as? JsonPrimitive)?.contentOrNull ?: return null
                    }
                    else -> return null
                }
                val environment = parsePermissionEnvironment(raw["env"] ?: raw["environment"]) ?: return null
                val rawCwd = firstString(raw, "cwd", "workingDirectory", "working_directory") ?: mapper.remoteRoot
                val canonicalCwd = normalizeAgentPathForFingerprint(rawCwd) ?: return null
                AcpPermissionIdentities.execute(command, args, environment, canonicalCwd)
            }
            else -> null
        }
    }

    private fun normalizeAgentPathForFingerprint(value: String): String? {
        mapper.remoteToLocal(value)?.let { return mapper.localToRemote(it) }
        if (value.startsWith('/')) return null
        return runCatching { mapper.localRelativeToRemote(value) }.getOrNull()
    }

    private fun parsePermissionEnvironment(value: JsonElement?): Map<String, String>? {
        return when (value) {
        null, JsonNull -> emptyMap()
        is JsonObject -> {
            if (value.size > 64) return null
            value.entries.associate { (key, element) ->
                if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) return null
                key to ((element as? JsonPrimitive)?.contentOrNull ?: return null)
            }
        }
        is JsonArray -> {
            if (value.size > 64) return null
            val result = linkedMapOf<String, String>()
            value.forEach { element ->
                val obj = element as? JsonObject ?: return null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
                val envValue = obj["value"]?.jsonPrimitive?.contentOrNull ?: return null
                if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) || result.put(name, envValue) != null) return null
            }
            result
        }
        else -> null
        }
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }

    private suspend fun handleRead(id: JsonElement, params: JsonObject) {
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: error("ACP read path is missing")
        val local = mapper.remoteToLocal(path) ?: error("ACP read escaped the workspace")
        val rel = local.relativeTo(files.root).invariantSeparatorsPath
        val binding = readSessionBinding ?: error("ACP session is not bound")
        val sid = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: error("ACP read session id is missing")
        val owner = approvalSessionId ?: error("ACP approval session is not bound")
        val caller = currentCoroutineContext()
        fun requireCurrentRead() {
            caller.ensureActive()
            check(readSessionBinding === binding && sessionId == sid && approvalSessionId == owner) {
                "ACP file read session is no longer current"
            }
            require(ApprovalPolicyGuard.remainsPermitted(permissions, "read", rel, EXTERNAL_ACP_PERMISSION_SCOPE)) {
                "ACP file read denied"
            }
        }
        requireCurrentRead()
        val approved = approvals.withApprovalOwner(ApprovalOwnerAdmission(this) {
            caller.isActive && transportFailure == null && readSessionBinding === binding && sessionId == sid && approvalSessionId == owner
        }) { PermissionApprovalGate.approved(
            permissions = permissions,
            approvals = approvals,
            action = "read",
            resource = rel,
            permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
            kind = "agent_plugin_read",
            summary = "Read $rel",
            detail = "External ACP agent requested a direct text read.",
            provenance = AgentRequestProvenance.primary(sid),
            approvalSessionId = owner,
        ) }
        require(approved) { "ACP file read denied" }
        requireCurrentRead()
        var content = workspaceIo.readExecutionText(path)
            ?: files.readTextForEdit(rel, maxBytes = AcpWorkspaceText.MAX_BYTES)
        val line = params["line"]?.jsonPrimitive?.intOrNull
        val limit = params["limit"]?.jsonPrimitive?.intOrNull
        if (line != null || limit != null) {
            val start = (line ?: 1).coerceAtLeast(1) - 1
            val count = (limit ?: 2_000).coerceIn(1, 10_000)
            content = content.lineSequence().drop(start).take(count).joinToString("\n")
        }
        // IO and the owned writer may both suspend. Revalidate at the writer's
        // publication boundary so a queued response cannot outlive its authority.
        requireCurrentRead()
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("result", buildJsonObject { put("content", content) })
        }, beforeWrite = ::requireCurrentRead)
    }

    private suspend fun handleWrite(params: JsonObject, receivedEpoch: Long): JsonObject {
        accessPolicy.requireMutation("filesystem write")
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: error("ACP write path is missing")
        val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("ACP write content is missing")
        require(content.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "ACP write is too large" }
        val local = mapper.remoteToLocal(path) ?: error("ACP write escaped the workspace")
        val rel = local.relativeTo(files.root).invariantSeparatorsPath
        val canonicalRemotePath = mapper.localToRemote(local)
        val identity = AcpPermissionIdentities.mutation(canonicalRemotePath, content, rel)
        val preapproved = synchronized(this) {
            mirrorEdit = null; mirrorRevision++ // The direct-write path and mirror path cannot spend the same approval.
            permissionLedger.consume(identity.kind, identity.operationFingerprint)
        }
        val sid = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: error("ACP write session id is missing")
        val binding = readSessionBinding ?: error("ACP session is not bound")
        val turn = mutationTurnBinding ?: error("ACP write has no active edit turn")
        val owner = approvalSessionId ?: error("ACP approval session is not bound")
        val caller = currentCoroutineContext()
        fun requireCurrentWrite() {
            caller.ensureActive()
            check(authorityEpoch.get() == receivedEpoch && transportFailure == null && readSessionBinding === binding &&
                mutationTurnBinding === turn && sessionId == sid && approvalSessionId == owner) {
                "ACP file write no longer belongs to the active session and turn"
            }
            accessPolicy.requireMutation("filesystem write")
            require(ApprovalPolicyGuard.remainsPermitted(permissions, identity.action, identity.policyResource,
                EXTERNAL_ACP_PERMISSION_SCOPE)) { "ACP file write denied" }
        }
        requireCurrentWrite()
        val expected = AcpWorkspaceText.version(files, rel)
        val projection = workspaceIo.prepareTextProjection(canonicalRemotePath, expected)
        requireCurrentWrite()
        val approved = approvals.withApprovalOwner(ApprovalOwnerAdmission(this) {
            caller.isActive && authorityEpoch.get() == receivedEpoch && transportFailure == null && readSessionBinding === binding && mutationTurnBinding === turn && sessionId == sid && approvalSessionId == owner && accessPolicy.allowsWorkspaceMutation()
        }) { PermissionApprovalGate.approved(
            permissions = permissions,
            approvals = approvals,
            action = identity.action,
            resource = identity.policyResource,
            permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
            kind = "agent_plugin_write",
            summary = "Edit $rel",
            detail = "External ACP agent requested a direct text write (${content.length} characters).",
            provenance = AgentRequestProvenance.primary(sid), approvalSessionId = owner,
            sessionGrantAction = identity.action,
            sessionGrantResource = identity.sessionGrantResource,
            preapproved = preapproved,
        ) }
        require(approved) { "ACP file write denied" }
        requireCurrentWrite()
        projection?.requireUnchanged()
        val bytes = content.toByteArray(Charsets.UTF_8)
        files.writeStream(rel, bytes.inputStream(), expectedBytes = bytes.size.toLong(),
            requireAbsent = !expected.existed, expectedVersion = expected,
            beforePublication = {
                requireCurrentWrite()
                projection?.requireUnchanged()
                requireCurrentWrite()
            },
            publicationObserver = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) = requireCurrentWrite()
                override fun onPublished(receipt: StreamPublicationReceipt) { directWrites += rel }
            })
        try {
            requireCurrentWrite()
            if (projection != null) projection.publish(content, ::requireCurrentWrite)
            else workspaceIo.push(local, canonicalRemotePath)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw IllegalStateException("Project file was saved at $rel, but CLI projection needs review. " +
                "Inspect the retained CLI workspace before retrying; do not repeat this write automatically.", failure)
        }
        return JsonObject(emptyMap())
    }

    private fun diagnosticMessage(message: String): String = boundedDiagnosticText(message, 2_000) + stderrTail.takeIf { it.isNotBlank() }
        ?.let { "\nCLI output:\n${it.takeLast(2_000)}" }.orEmpty()

    private suspend fun send(message: JsonObject, beforeWrite: (() -> Unit)? = null) {
        transportFailure?.let { throw IllegalStateException("ACP agent transport is closed", it) }
        // The owned writer runs off the caller/UI thread; waiting remains
        // cancellable even when the child has stopped reading its input pipe.
        val write = scope.async(Dispatchers.IO) {
            writeMutex.withLock {
                transportFailure?.let { throw IllegalStateException("ACP agent transport is closed", it) }
                beforeWrite?.invoke()
                writer.write(message.toString())
                writer.write("\n")
                writer.flush()
            }
        }
        writes += write
        try { write.await() }
        finally {
            writes -= write
            if (!write.isCompleted) {
                transportFailure = transportFailure ?: IllegalStateException("ACP input write interrupted")
                write.cancel()
                runCatching { process.close() }
            }
        }
    }

    private companion object {
        const val MAX_PENDING_REQUESTS = 64
        const val MAX_INBOUND_REQUESTS = 32
    }

    @Synchronized
    override fun close() {
        authorityEpoch.incrementAndGet()
        mutationTurnBinding = null
        readSessionBinding = null
        transportFailure = transportFailure ?: IllegalStateException("ACP agent connection closed")
        inbound.values.forEach { it.job.cancel() }
        inbound.clear()
        try { terminal.close() } finally {
            permissionLedger.clear()
            mirrorEdit = null; mirrorRevision++
            readerJob.cancel()
            stderrJob.cancel()
            writes.forEach { it.cancel() }
            writes.clear()
            pending.values.forEach { it.deferred.cancel() }
            pending.clear()
            if (writerClose == null) writerClose = scope.async(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { writer.close() }
            process.close()
        }
    }

    suspend fun awaitClosed() {
        val closing = synchronized(this) { checkNotNull(writerClose) { "Request ACP close before awaiting it" } }
        closing.await()
    }
}

internal class AcpRequestTimeoutException(message: String) : IllegalStateException(message)

package com.baystudio.droide.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.launch






@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPane(
    agent: AgentService,
    uiState: AgentWorkspaceUiState,
    providerConnections: ProviderConnectionBackend,
    localLlamaModels: LocalLlamaAgentModelController,
    agentPlugins: WorkspaceAgentPluginHost? = null,
    approvals: ApprovalManager = agent.approvals,
    captureIdeContext: () -> AgentIdeContextSnapshot? = { null },
    onSessions: () -> Unit = {},
    onUiError: (String) -> Unit = {},
    onComposerFocusChanged: (Boolean) -> Unit = {},
    onClose: (() -> Unit)? = null,
    onOpenAgentExtensions: ((String) -> Unit)? = null,
    onManageMcp: (() -> Unit)? = null,
    onManagePlugins: (() -> Unit)? = null,
    onManageSkills: (() -> Unit)? = null,
    onOpenResult: ((String) -> Unit)? = null,
    onExportResult: ((String) -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberWorkspaceCoroutineScope()
    val messages by agent.messages.collectAsState()
    val busy by agent.busy.collectAsState()
    val titleRevision by agent.sessions.titleRevision.collectAsState()
    val mode by agent.mode.collectAsState()
    val persistenceError by agent.persistenceError.collectAsState()
    val streamState by agent.streamState.collectAsState()
    val localLlamaState by localLlamaModels.state.collectAsState()
    val parallelAgents by agent.subagentDashboard.collectAsState()
    val pendingInputs by agent.pendingInputs.collectAsState()
    val streamParts = streamState.parts
    val baseDisplayedMessages = remember(messages, busy, streamState.baseMessageCount) {
        if (busy && streamState.baseMessageCount in 0..messages.size) messages.take(streamState.baseMessageCount) else messages
    }
    val nativeLiveMedia = remember(messages, busy, streamState.baseMessageCount) {
        projectAgentNativeLiveMedia(messages, busy, streamState.baseMessageCount)
    }
    val inputMediaByPrompt = remember(messages) { agentInputMediaByPrompt(messages) }
    val pendingQuestion by agent.questions.pending.collectAsState()
    val pendingApproval by approvals.pending.collectAsState()
    val tokens by agent.tokens.usage.collectAsState()
    val lastContextUsage by agent.contextUsage.collectAsState()
    val compactionActivity by agent.compactionActivity.collectAsState()
    val todos by agent.todos.flow(agent.currentSessionId).collectAsState()
    var agentBackendId by uiState.backendId
    var showAgentBackendMenu by remember { mutableStateOf(false) }
    val externalController = uiState.externalController
    val retainedRecoveryAllowed by rememberUpdatedState(!busy && !externalController.busy)
    val retainedRecovery = remember(agentPlugins, agentBackendId) {
        agentPlugins?.let { AgentRetainedRecoveryController(scope, it.retainedRecoveryActions()) { retainedRecoveryAllowed } }
    }
    DisposableEffect(retainedRecovery) { onDispose { retainedRecovery?.close() } }
    val pluginAvailability = rememberWorkspaceAgentAvailability(agentPlugins, showAgentBackendMenu) {
        agentPlugins?.availability().orEmpty()
    }
    val selectedPlugin = pluginAvailability.firstOrNull { it.spec.id == agentBackendId }
    val selectedExternalSpec = WorkspaceAgentPluginCatalog.entries.firstOrNull { it.id == agentBackendId }
    // Detection is a UI observation, never permission to silently change the selected backend.
    val usingExternalAgent = agentBackendId != "droide"
    val externalState = externalController[agentBackendId]
    val displayedQuestion = pendingQuestion.takeUnless { usingExternalAgent }
    val workspaceSync = agentPlugins?.workspaceSyncState?.collectAsState()?.value
    val conversationKey = AgentChatConversationKey(agentBackendId, if (usingExternalAgent) externalState.historyId ?: externalState.sessionId else agent.currentSessionId)
    val savedChatPosition = remember(uiState, conversationKey) { uiState.chatPosition(conversationKey) }
    val externalBusy = externalController.busy
    val effectiveBusy = if (usingExternalAgent) externalBusy else busy
    LaunchedEffect(agentPlugins, agentBackendId, selectedPlugin?.available) {
        if (usingExternalAgent && selectedPlugin?.available == true && agentPlugins != null) {
            externalController.prepare(agentPlugins, selectedPlugin.spec)
        }
    }
    var composerFocused by remember { mutableStateOf(false) }
    var landscapeComposerImeSession by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onComposerFocusChanged(false) } }

    var input by uiState.input
    var providerId by rememberSaveable { mutableStateOf("pollinations") }
    var model by rememberSaveable { mutableStateOf(ProviderRegistry.byId("pollinations").model) }
    var reasoningEffort by remember { mutableStateOf<ReasoningEffort?>(null) }
    var taskCollapsed by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var taskHidden by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var customAnswer by rememberSaveable(pendingQuestion?.id) { mutableStateOf("") }
    var sessionTitle by remember(agent.currentSessionId) { mutableStateOf("Agent session") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var connectProviderId by rememberSaveable { mutableStateOf("pollinations") }
    var showModeMenu by remember { mutableStateOf(false) }
    var showReasoningMenu by remember { mutableStateOf(false) }
    var showUsageMenu by remember(uiState, conversationKey) { mutableStateOf(false) }
    var showBillingSheet by remember(uiState, conversationKey) { mutableStateOf(false) }
    var agentPreflightJob by uiState.preflightJob
    val submissionError by uiState.submissionError
    val recoveredExitDrafts by uiState.recoveredExitDrafts
    val exitDraftError by uiState.exitDraftError
    val exitDraftNotice by uiState.exitDraftNotice
    var showSavedExitDrafts by remember(uiState) { mutableStateOf(false) }
    var sessionTool by remember(uiState, conversationKey) { mutableStateOf<AgentSessionTool?>(null) }
    var showStreamDetails by remember(uiState, conversationKey) { mutableStateOf(savedChatPosition?.showDetails ?: false) }
    val visibleStreamParts = remember(streamParts, showStreamDetails) { visibleAgentStreamParts(streamParts, showStreamDetails) }
    val visibleExternalStreamParts = remember(externalState.streamParts, showStreamDetails) {
        visibleAgentStreamParts(externalState.streamParts, showStreamDetails)
    }
    val displayedTodos = if (usingExternalAgent) remember(externalState.streamParts, externalState.messages) {
        externalAgentTasks(externalState)
    } else todos
    var promptDelivery by rememberSaveable(agentBackendId) { mutableStateOf(AgentPromptDelivery.STEER) }
    val baseTranscriptMessages = if (usingExternalAgent) externalState.messages else baseDisplayedMessages
    val visibleMessageIndices = remember(baseTranscriptMessages, showStreamDetails) {
        baseTranscriptMessages.indices.filter { index ->
            val part = baseTranscriptMessages[index].streamPart
            part == null || part.visibleInChat(showStreamDetails)
        }
    }
    val transcriptMessages = remember(baseTranscriptMessages, visibleMessageIndices) { visibleMessageIndices.map(baseTranscriptMessages::get) }
    val transcriptKeys = remember(transcriptMessages, visibleMessageIndices, usingExternalAgent, externalState.historyWindow) {
        transcriptMessages.mapIndexed { index, message ->
            val sourceIndex = visibleMessageIndices[index]
            val savedKey = if (usingExternalAgent) externalState.historyWindow?.messageKeys?.getOrNull(sourceIndex) else null
            val identity = if (usingExternalAgent && externalState.historyWindow != null && savedKey == null) "pending:${externalController.activeRun?.runId}:$sourceIndex" else (savedKey ?: sourceIndex).toString()
            "message:$identity:${message.role}:${message.streamPart?.id.orEmpty()}"
        }
    }
    var attachedContexts by uiState.attachments
    val mediaImporting by uiState.mediaImporting
    val mediaPickerPending by uiState.mediaPickerPending
    val mediaImportError by uiState.mediaImportError
    SideEffect { uiState.bindMediaConversation(agent.workDir, conversationKey) }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importPickedAgentMedia(ctx.applicationContext, uiState, uri)
    }
    val visualAttachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        importPickedAgentMedia(ctx.applicationContext, uiState, uri)
    }
    fun launchAttachmentPicker(visual: Boolean) {
        if (mediaImporting || mediaPickerPending || agentPreflightJob?.isCompleted == false) return
        runUiCatching(onError = {
            uiState.pendingMediaPicker = null
            uiState.mediaPickerPending.value = false
            uiState.mediaImportError.value = "Cannot open the attachment picker. Try again."
        }) {
            uiState.bindMediaConversation(agent.workDir, conversationKey)
            uiState.pendingMediaPicker = uiState.mediaDraftToken()
            uiState.mediaPickerPending.value = true
            if (visual) visualAttachLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            else attachLauncher.launch(arrayOf("*/*"))
        }
    }

    LaunchedEffect(localLlamaModels) { localLlamaModels.refresh() }
    val preferenceRead = rememberPreferenceReadRecovery(ctx, AgentPreferences.Snapshot("pollinations", "", "", null)) { AgentPreferences.observe(ctx) }
    LaunchedEffect(preferenceRead.state) {
        if (preferenceRead.state.ready) {
            val snapshot = preferenceRead.state.value
            providerId = snapshot.providerId
            model = snapshot.model
            reasoningEffort = snapshot.reasoningEffort
        }
    }
    LaunchedEffect(agent.currentSessionId, messages.size, titleRevision) {
        sessionTitle = runSuspendCatching { agent.currentSessionId?.let { agent.sessions.getSummary(it)?.title } }
            .getOrNull() ?: "New agent session"
    }
    val streamTextChars = streamParts.sumOf { if (it.kind == AgentStreamPartKind.TEXT) it.text.length else 0 }
    val streamOutputChars = streamParts.sumOf { if (it.kind == AgentStreamPartKind.TOOL) it.output.length else 0 }
    val streamTerminalCount = streamParts.count { it.state == AgentStreamPartState.COMPLETED || it.state == AgentStreamPartState.ERROR || it.state == AgentStreamPartState.CANCELLED }
    val parallelActivityBucket = parallelAgents.tasks.sumOf { task ->
        ((task.updatedAtMs / 1_000L) % 10_000L).toInt() + task.activityStep + task.activity.hashCode() + task.state.ordinal * 17 + task.integration.ordinal * 31
    }
    val showActivityTail = (!usingExternalAgent && (busy || streamParts.isNotEmpty())) ||
            (usingExternalAgent && (externalController.activeRun?.agentId == agentBackendId ||
                externalController.lifecycleBusyAgentId == agentBackendId))
    val activityTailKey = if (usingExternalAgent) "external-status-tail:$agentBackendId" else "native-status-tail:${streamState.runId}"
    val chatKeys = buildList {
        if (transcriptMessages.isEmpty()) add("welcome")
        addAll(transcriptKeys)
        if (!usingExternalAgent && parallelAgents.tasks.isNotEmpty()) add("parallel-agents:${parallelAgents.parentSessionId}")
        if (!usingExternalAgent) visibleStreamParts.forEach { add(agentActivityItemKey(streamState.runId, it.id)) }
        if (!usingExternalAgent && nativeLiveMedia.hidden > 0) add("older-live-media:${streamState.runId}")
        if (!usingExternalAgent) nativeLiveMedia.entries.forEach { add(it.key(streamState.runId)) }
        if (usingExternalAgent && externalController.activeRun?.agentId == agentBackendId) {
            visibleExternalStreamParts.forEach { add(it.id) }
            if (externalState.liveMedia.isNotEmpty()) add("external-agent-media")
        }
        if (showActivityTail) add(activityTailKey)
    }
    val chatActivity = AgentChatActivity(
        messages = if (usingExternalAgent) transcriptMessages.size else baseDisplayedMessages.size,
        parts = if (usingExternalAgent) externalState.streamParts.size + externalState.liveMedia.size else streamParts.size + nativeLiveMedia.total,
        textBucket = if (usingExternalAgent) (externalState.liveText.length + externalState.liveThought.length) / 160 else streamTextChars / 160,
        outputBucket = if (usingExternalAgent) externalState.streamParts.sumOf { it.output.length } / 1024 else streamOutputChars / 1024,
        terminalParts = if (usingExternalAgent) externalState.streamParts.count { it.state in setOf(AgentStreamPartState.COMPLETED, AgentStreamPartState.ERROR, AgentStreamPartState.CANCELLED) } else streamTerminalCount,
        parallel = if (usingExternalAgent) 0 else parallelActivityBucket,
        external = if (usingExternalAgent) 31 * externalState.activity.hashCode() + externalState.liveMedia.hashCode() else 0,
        runId = if (usingExternalAgent) externalController.activeRun?.runId?.toString() else streamState.runId.toString(),
        busy = effectiveBusy,
    )
    val chatViewport = rememberAgentChatViewport(uiState, conversationKey, savedChatPosition, chatKeys, chatActivity, showStreamDetails)
    val listState = chatViewport.listState
    val newActivityCount = chatViewport.unread
    val activityEntrances = remember(uiState, conversationKey) {
        AgentActivityEntrancePolicy(buildList {
            streamParts.filter { it.kind == AgentStreamPartKind.TOOL }.forEach { add(agentActivityItemKey(streamState.runId, it.id)) }
            externalState.streamParts.filter { it.kind == AgentStreamPartKind.TOOL }.forEach { add(it.id) }
        })
    }
    val entranceEnabled = effectiveBusy && chatViewport.ready && chatViewport.following && !chatViewport.dragging

    val localProviderSelected = providerId.equals(LocalLlamaServerPolicy.PROVIDER_ID, true)
    val provider = ProviderRegistry.findById(providerId) ?: if (localProviderSelected) {
        AiProvider(
            id = LocalLlamaServerPolicy.PROVIDER_ID,
            name = "Droide Local llama.cpp",
            baseUrl = "http://127.0.0.1",
            model = model,
            free = true,
            needsKey = true,
            helpUrl = "https://github.com/ggml-org/llama.cpp/tree/master/tools/server",
            note = "Certified local GGUF; endpoint exists only while Droide owns the 08ay lifecycle.",
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )
    } else ProviderRegistry.byId(providerId)
    val providerConnected = rememberAgentProviderConnected(providerConnections, provider.id)
    val localModelSelectable = localProviderSelected && localLlamaState.models.any { it.id == model }
    val configured = model.isNotBlank() && (providerConnected || localModelSelectable)
    val preflightBusy = agentPreflightJob?.isCompleted == false
    val mediaRecoverySession = if (usingExternalAgent) null else agent.currentSessionId
    val mediaRecoveryEnabled = mediaRecoverySession != null && !busy && !externalBusy && !preflightBusy
    val mediaRecovery = mediaRecoverySession?.let { sessionId ->
        rememberProjectCopyRecoveryController(agent, agent.workDir.absolutePath,
            ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, sessionId), mediaRecoveryEnabled,
            agentCopyRecoveryActions(agent, sessionId))
    }
    val canSendWithBackend = if (usingExternalAgent) selectedPlugin?.available == true else preferenceRead.state.ready && configured && !preflightBusy
    val hasDraft = input.isNotBlank() || attachedContexts.isNotEmpty()
    val canDeliverToActiveRun = if (usingExternalAgent) externalController.activeRun?.agentId == agentBackendId else busy
    // Suggestions and dispatch share one list so local actions cannot shadow CLI commands.
    val composerCommands = buildList {
        if (onManageMcp != null) add(AgentComposerCommand.MCP)
        if (onManagePlugins != null) add(AgentComposerCommand.PLUGINS)
        if (onManageSkills != null) add(AgentComposerCommand.SKILLS)
        if (!usingExternalAgent) {
            add(AgentComposerCommand.COMPACT)
            add(AgentComposerCommand.RESULTS)
        }
        add(AgentComposerCommand.WORKFLOWS)
        if (!usingExternalAgent) add(AgentComposerCommand.RESUME)
    }
    fun draftCommand() = AgentComposerShortcuts.command(input, attachedContexts.isNotEmpty(), composerCommands)
    val localNavigation = when (draftCommand()) {
        AgentComposerCommand.MCP -> onManageMcp != null
        AgentComposerCommand.PLUGINS -> onManagePlugins != null
        AgentComposerCommand.SKILLS -> onManageSkills != null
        AgentComposerCommand.RESULTS, AgentComposerCommand.WORKFLOWS, AgentComposerCommand.RESUME -> true
        else -> false
    }
    val canSubmitDraft = (localNavigation && !mediaImporting && !mediaPickerPending) || hasDraft && !preflightBusy && !mediaImporting && !mediaPickerPending && canSendWithBackend &&
        (!externalBusy || usingExternalAgent && externalController.canAcceptWhileRunning(agentBackendId))

    fun stopActiveRun() {
        runUiCatching(onError = { onUiError("Could not finish stopping the Agent: ${it.message ?: it::class.java.simpleName}") }) {
            when {
                preflightBusy -> { agentPreflightJob?.cancel(); agent.cancel() }
                usingExternalAgent -> externalController.cancel(agentPlugins)
                !busy && compactionActivity?.running == true -> agent.cancelContextCompaction()
                else -> agent.cancel()
            }
        }
    }

    fun compactContext(fromCommand: Boolean = false) {
        val compactSessionId = agent.currentSessionId
        if (usingExternalAgent || !configured || compactSessionId == null || busy || preflightBusy || compactionActivity?.running == true) {
            if (fromCommand) uiState.submissionError.value = "Open an idle Agent conversation before compacting context."
            return
        }
        val selectedProvider = providerId
        val selectedModel = model
        val selectedReasoning = reasoningEffort
        fun ownsSession() = uiState.backendId.value == "droide" && agent.currentSessionId == compactSessionId
        if (fromCommand) { input = ""; uiState.submissionError.value = null }
        scope.launchUiCatching(onError = { if (ownsSession()) onUiError("Could not compact context: ${it.message ?: it::class.java.simpleName}") }) {
            if (ownsSession()) {
                val config = localLlamaModels.resolveAgentConfig(selectedProvider, selectedModel, selectedReasoning)
                if (ownsSession()) agent.compactCurrent(config)
            }
        }
    }

    fun send() {
        if (mediaImporting || mediaPickerPending) return
        val localAction = when (draftCommand()) {
            AgentComposerCommand.MCP -> onManageMcp
            AgentComposerCommand.PLUGINS -> onManagePlugins
            AgentComposerCommand.SKILLS -> onManageSkills
            AgentComposerCommand.RESULTS -> { { sessionTool = AgentSessionTool.RESULTS } }
            AgentComposerCommand.WORKFLOWS -> { { sessionTool = AgentSessionTool.WORKFLOWS } }
            AgentComposerCommand.RESUME -> { { sessionTool = AgentSessionTool.RESUME } }
            else -> null
        }
        if (localAction != null) {
            localAction()
            input = ""
            uiState.submissionError.value = null
            return
        }
        if (!usingExternalAgent && !preferenceRead.state.ready) return
        if (mediaImporting || mediaPickerPending || input.isBlank() && attachedContexts.isEmpty()) return
        val submittedInput = input
        val submittedAttachments = attachedContexts
        val text = try {
            composeAgentPrompt(submittedInput, submittedAttachments)
        } catch (limit: AgentPromptLimitException) {
            uiState.submissionError.value = limit.message
            return
        }
        if (uiState.submissionError.value == AGENT_DRAFT_LIMIT_ERROR || uiState.submissionError.value == AGENT_PROMPT_LIMIT_ERROR) {
            uiState.submissionError.value = null
        }
        val imagePaths = submittedAttachments.mapNotNull { it.imagePath }
        val media = submittedAttachments.mapNotNull { it.media }
        uiState.bindMediaConversation(agent.workDir, conversationKey)
        val mediaToken = uiState.mediaDraftToken()
        val submittedSession = agent.currentSessionId
        if (usingExternalAgent) {
            val host = agentPlugins ?: return
            val plugin = selectedPlugin ?: return
            if (!plugin.available || externalController.busy && !externalController.canAcceptWhileRunning(plugin.spec.id)) return
            uiState.beginMediaSubmission(mediaToken)
            val accepted = externalController.submit(
                host = host,
                spec = plugin.spec,
                prompt = text,
                mode = if (mode == AgentMode.PLAN || mode == AgentMode.EXPLORE || mode == AgentMode.REVIEW) WorkspaceAgentMode.PLAN else WorkspaceAgentMode.EDIT,
                delivery = if (externalController.activeRun?.agentId == plugin.spec.id) promptDelivery else AgentPromptDelivery.STEER,
                media = media,
                mediaOwnerId = mediaToken.ownerId,
            )
            uiState.finishMediaSubmission(mediaToken, accepted)
            if (accepted) input = ""
            return
        }
        if (!configured) {
            if (localProviderSelected) {
                showModelPicker = true
            } else {
                connectProviderId = provider.id
                showConnect = true
            }
            return
        }
        if (agentPreflightJob?.isCompleted == false) return
        if (isCompactContextCommand(submittedInput, submittedAttachments.isNotEmpty(), usingExternalAgent)) {
            compactContext(fromCommand = true)
            return
        }
        // Preserve the human Send time across delayed provider preparation and admission.
        val requestAdmittedAtNanos = System.nanoTime()

        val ideContext = captureIdeContext()
        val appContext = ctx.applicationContext
        val selectedProviderId = providerId
        val selectedModel = model
        val selectedReasoning = reasoningEffort
        val delivery = if (busy) promptDelivery else AgentPromptDelivery.STEER
        uiState.submissionError.value = null
        uiState.beginMediaSubmission(mediaToken)
        val preparation = uiState.scope.launchUiCatching(
            onError = { uiState.submissionError.value = "Could not prepare ${provider.name}: ${it.message ?: it::class.java.simpleName}" },
        ) {
            var admission: kotlinx.coroutines.Job? = null
            try {
                OwnedDevelopmentOperation.runWithoutCpu(appContext, "Agent preparation") {
                    val config = localLlamaModels.resolveAgentConfig(selectedProviderId, selectedModel, selectedReasoning)
                    if (agent.currentSessionId != submittedSession || !uiState.isCurrentMediaDraft(mediaToken)) return@runWithoutCpu
                    admission = agent.submitAsync(
                        uiState.scope,
                        text,
                        config,
                        delivery,
                        ideContext = ideContext,
                        imagePaths = imagePaths,
                        media = media,
                        mediaOwnerId = mediaToken.ownerId,
                        requestAdmittedAtNanos = requestAdmittedAtNanos,
                        onAccepted = {
                            if (agent.currentSessionId == submittedSession || submittedSession == null) {
                                if (input == submittedInput) input = ""
                                uiState.finishMediaSubmission(mediaToken, accepted = true)
                            }
                        },
                    )
                    admission?.join()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Stop owns preparation and its pending admission. Panel disposal owns neither.
                admission?.cancel()
                if (admission != null) agent.cancel()
                throw cancelled
            } finally {
                uiState.finishMediaSubmission(mediaToken, accepted = false)
            }
        }
        uiState.ownPreparation(preparation)
    }




    val physicalLandscape = droidePhysicalLandscape()
    val imeVisible = rememberDroideImeVisible()
    LaunchedEffect(physicalLandscape, imeVisible, composerFocused) {
        if (!physicalLandscape || !imeVisible) landscapeComposerImeSession = false
        else if (composerFocused) landscapeComposerImeSession = true
    }
    val landscapeImeTyping = physicalLandscape && imeVisible && landscapeComposerImeSession
    val shouldApplyAgentImeInset = !physicalLandscape || landscapeImeTyping
    val agentImeInsetModifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && shouldApplyAgentImeInset) Modifier.imePadding() else Modifier
    AgentPublicTextInteractionHost(conversationKey) {
    BoxWithConstraints(Modifier.fillMaxSize().background(DroideColors.Surface).then(agentImeInsetModifier)) {
        val denseComposer = maxHeight < 560.dp || landscapeImeTyping
        val compactControls = denseComposer || maxWidth < 430.dp
        // IME height changes must not change transcript text width/row spacing mid-animation.
        // Narrow panes keep their compact margins, including before the keyboard opens.
        val compactTranscript = maxWidth < 430.dp
        val paneWidth = maxWidth
        val composerOuterPadding = if (denseComposer) PaddingValues(horizontal = 7.dp, vertical = 5.dp) else PaddingValues(horizontal = 9.dp, vertical = 7.dp)
        val composerInnerPadding = if (denseComposer) 6.dp else 8.dp
        val controlHeight = 24.dp
        val sendSize = 31.dp
        // Grow with wrapped text; budget against the actual pane after its existing IME inset.
        // BasicTextField owns cursor-following and scrolling once this viewport is full.
        val composerInputMaxHeight = (maxHeight * .30f).coerceIn(48.dp, 180.dp)
        val logoSize = if (compactControls) 15.dp else 18.dp
        val statusDetailMaxHeight = (maxHeight * .16f).coerceAtMost(112.dp)
        val taskDockMaxHeight = maxHeight * if (denseComposer) .10f else .13f
        val interactionDockMaxHeight = maxHeight * if (denseComposer) .16f else .20f

        val workspaceFilesControl: @Composable () -> Unit = {
            if (usingExternalAgent && workspaceSync != null && agentPlugins != null && selectedExternalSpec != null) {
                AgentWorkspaceSyncNotice(workspaceSync, externalBusy || busy, onReview = {
                    externalController.reviewWorkspaceChanges(agentPlugins, selectedExternalSpec)
                }, onRecovery = retainedRecovery?.let { recovery -> { recovery.open() } })
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (!landscapeImeTyping) DroidePanelHeader(
                title = if (usingExternalAgent) externalState.historyWindow?.summary?.title
                    ?: externalState.messages.firstOrNull { it.role == "user" }?.content?.take(80)
                    ?: "New ${selectedExternalSpec?.displayName ?: "plugin"} session" else sessionTitle,
                subtitle = if (usingExternalAgent) null
                    else "${agentModeLabel(mode)} · Droide Agent · current project",
                onClose = onClose,
                actions = {
                    workspaceFilesControl()
                    Box {
                        val onUsageClick = {
                            showModeMenu = false
                            showReasoningMenu = false
                            showUsageMenu = !showUsageMenu
                        }
                        if (usingExternalAgent) {
                            ExternalAgentUsageHeaderButton(externalState.usage, paneWidth, onUsageClick)
                        } else {
                            AgentUsageHeaderButton(lastContextUsage?.forSelection(providerId, model), paneWidth, onUsageClick,
                                compactionActivity?.takeIf { it.sessionId == agent.currentSessionId })
                        }
                        DropdownMenu(
                            expanded = showUsageMenu,
                            onDismissRequest = { showUsageMenu = false },
                            modifier = Modifier.widthIn(min = 224.dp, max = 280.dp),
                        ) {
                            if (usingExternalAgent) {
                                ExternalAgentUsageQuickMenu(externalState.usage)
                            } else AgentUsageQuickMenu(
                                usage = tokens,
                                context = lastContextUsage?.forSelection(providerId, model),
                                compaction = compactionActivity?.takeIf { it.sessionId == agent.currentSessionId },
                                canCompact = configured && agent.currentSessionId != null && !busy && !preflightBusy,
                                onViewBilling = {
                                    showUsageMenu = false
                                    if (uiState.backendId.value == "droide") showBillingSheet = true
                                },
                                onCompact = {
                                    showUsageMenu = false
                                    compactContext()
                                },
                            )
                            if (!usingExternalAgent) ProjectCopyRecoveryMenuItem(imports = false,
                                enabled = mediaRecoveryEnabled, onOpen = {
                                    showUsageMenu = false
                                    mediaRecovery?.open()
                                })
                        }
                    }
                    IconButton(
                        onClick = {
                            if (conversationKey.sessionId == null) uiState.forgetChatPosition(conversationKey)
                            if (usingExternalAgent) {
                                val host = agentPlugins
                                val plugin = selectedPlugin
                                if (host != null && plugin != null) externalController.newSession(host, plugin.spec)
                            } else agent.clear()
                            input = ""
                            customAnswer = ""
                            uiState.discardMediaDraft()
                            taskCollapsed = false
                            taskHidden = false
                        },
                        enabled = !busy && !externalBusy && !preflightBusy && (!usingExternalAgent || selectedPlugin?.available == true),
                    ) { Icon(Icons.Default.Add, "New agent session") }
                    IconButton(onClick = onSessions) { Icon(Icons.Default.History, "Agent sessions") }
                },
            )

            persistenceError?.takeUnless { usingExternalAgent }?.let {
                DismissibleNotice(it, color = DroideColors.Error, ownerKey = conversationKey)
            }
            submissionError?.let {
                DismissibleNotice(it, color = DroideColors.Error, ownerKey = conversationKey)
            }
            exitDraftError?.let {
                DismissibleNotice(it, color = DroideColors.Error, ownerKey = conversationKey)
            }
            exitDraftNotice?.let {
                DismissibleNotice(it, ownerKey = conversationKey)
            }
            if (usingExternalAgent && workspaceSync != null && agentPlugins != null && selectedExternalSpec != null) {
                retainedRecovery?.let { AgentRetainedRecoveryDialog(it) }
            }
            if (usingExternalAgent) externalState.connectionError?.let {
                DismissibleNotice(it.take(2_000), color = DroideColors.Error, ownerKey = conversationKey)
            }
            if (usingExternalAgent) externalState.persistenceError?.let {
                DismissibleNotice(it, color = DroideColors.Error, ownerKey = conversationKey)
            }
            if (usingExternalAgent) externalState.historyWindow?.let { window ->
                if (window.hasEarlier || window.hasLater) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { externalController.pageHistory(agentBackendId, earlier = true) }, enabled = !externalBusy && window.hasEarlier) { Text("Earlier messages") }
                    TextButton(onClick = { externalController.pageHistory(agentBackendId, earlier = false) }, enabled = !externalBusy && window.hasLater) { Text("Latest messages") }
                }
            }
            if (usingExternalAgent && externalController.historyLoadingAgentId == agentBackendId) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(DroideColors.Background)) {
                // Keep the latest output clear of the composer without adding another keyboard
                // inset. Scrollable padding uses the transcript's actual remaining height.
                val conversationBottomClearance = bottomTouchClearance(maxHeight)
                val disclosureClearance = with(LocalDensity.current) { chatViewport.disclosureSpacePx.toDp() }.coerceAtMost(maxHeight)
                val density = LocalDensity.current
                SideEffect {
                    val base = with(density) { conversationBottomClearance.roundToPx() }
                    val total = with(density) { (conversationBottomClearance + disclosureClearance).roundToPx() }
                    chatViewport.measuredDisclosurePadding(base, total - base)
                }
                CompositionLocalProvider(
                    LocalAgentDisclosureStarted provides chatViewport::beginDisclosure,
                    LocalAgentDisclosureMotion provides chatViewport::disclosureMotion,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Agent conversation" },
                        contentPadding = PaddingValues(
                            start = if (compactTranscript) 11.dp else 14.dp,
                            end = if (compactTranscript) 11.dp else 14.dp,
                            top = if (compactTranscript) 8.dp else 12.dp,
                            bottom = conversationBottomClearance + disclosureClearance,
                        ),
                        verticalArrangement = Arrangement.spacedBy(if (compactTranscript) 10.dp else 14.dp),
                    ) {
                        if (transcriptMessages.isEmpty()) {
                            item(key = "welcome") {
                                Column(Modifier.fillMaxWidth().padding(top = if (compactTranscript) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text((selectedPlugin?.spec?.displayName ?: "DROIDE AGENT").uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                                    Text("Ask about the project, edit files, or run tools.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                                }
                            }
                        }
                        itemsIndexed(transcriptMessages, key = { index, _ -> transcriptKeys[index] }) { _, message ->
                            AgentTranscriptItem(message, showStreamDetails, agent.workDir, if (usingExternalAgent) null else agent.currentSessionId,
                                legacyAssistantPresentation = if (usingExternalAgent) AgentTextPresentation.RESPONSE else AgentTextPresentation.LEGACY)
                        }
                        if (!usingExternalAgent && parallelAgents.tasks.isNotEmpty()) {
                            item(key = "parallel-agents:${parallelAgents.parentSessionId}") {
                                AgentParallelAgentsPanel(parallelAgents, agent::controlSubagentFromUi)
                            }
                        }
                        if (!usingExternalAgent) itemsIndexed(visibleStreamParts, key = { _, part -> agentActivityItemKey(streamState.runId, part.id) }) { _, part ->
                            val activityModifier = if (part.kind == AgentStreamPartKind.TOOL) {
                                Modifier.agentActivityEntrance(agentActivityItemKey(streamState.runId, part.id), activityEntrances,
                                    enabled = entranceEnabled)
                            } else Modifier
                            AgentStreamPartWithMedia(part, showStreamDetails,
                                if (part.kind == AgentStreamPartKind.INPUT) inputMediaByPrompt[part.text].orEmpty() else emptyList(),
                                agent.workDir, activityModifier)
                        }
                        if (!usingExternalAgent && nativeLiveMedia.hidden > 0) item(key = "older-live-media:${streamState.runId}") {
                            Text("Showing the latest 16 attachments; ${nativeLiveMedia.hidden} earlier attachments remain in the completed conversation.",
                                style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                        }
                        if (!usingExternalAgent) itemsIndexed(nativeLiveMedia.entries, key = { _, entry -> entry.key(streamState.runId) }) { _, entry ->
                            AgentMediaCard(entry.ref, agent.workDir)
                        }
                        if (usingExternalAgent && externalController.activeRun?.agentId == agentBackendId) {
                            itemsIndexed(visibleExternalStreamParts, key = { _, part -> part.id }) { _, part ->
                                AgentStreamPartWithMedia(part, showStreamDetails, emptyList(), agent.workDir,
                                    if (part.kind == AgentStreamPartKind.TOOL) Modifier.agentActivityEntrance(part.id, activityEntrances, entranceEnabled) else Modifier)
                            }
                            if (externalState.liveMedia.isNotEmpty()) item(key = "external-agent-media") {
                                AgentMessageMedia(externalState.liveMedia, agent.workDir)
                            }
                        }

                        // A single status follows the last activity in the same scroll owner.
                        // It never pins to the composer or requests a scroll on status updates.
                        if (showActivityTail) item(key = activityTailKey) {
                            CompositionLocalProvider(LocalAgentStatusDetailMaxHeight provides statusDetailMaxHeight) {
                    if (!usingExternalAgent && (busy || streamParts.isNotEmpty())) {
                        AgentStreamRunHeader(
                            state = streamState,
                            tokenTotal = tokens.total,
                            showDetails = showStreamDetails,
                            onToggleDetails = { showStreamDetails = !showStreamDetails },
                            waitingForApproval = pendingApproval?.provenance?.let { !it.isSubagent && it.sessionId.isNotBlank() && it.sessionId == streamState.sessionId } == true,
                            waitingForInput = pendingQuestion?.provenance?.let { !it.isSubagent && it.sessionId.isNotBlank() && it.sessionId == streamState.sessionId } == true,
                        )
                    }
                    if (usingExternalAgent && externalController.activeRun?.agentId == agentBackendId) {
                        ExternalAgentLiveStream(selectedPlugin?.spec?.displayName ?: "Agent", externalState, "external:${externalController.activeRun?.runId}",
                            showDetails = showStreamDetails, onToggleDetails = { showStreamDetails = !showStreamDetails },
                            waitingForApproval = pendingApproval?.provenance?.let { !it.isSubagent && it.sessionId.isNotBlank() && it.sessionId == externalState.sessionId } == true,
                            renderTranscript = false)
                    }
                    if (usingExternalAgent && externalController.lifecycleBusyAgentId == agentBackendId &&
                        externalController.activeRun?.agentId != agentBackendId) {
                        AgentActivityStatus(AgentActivityPresentation(AgentActivityKind.WORKING,
                            externalState.activity.lastOrNull() ?: "Loading ${selectedPlugin?.spec?.displayName} models…"),
                            Modifier.padding(vertical = 8.dp))
                    }

                            }
                        }

                    }
                }
                if (newActivityCount > 0 || !chatViewport.following) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(MaterialTheme.shapes.small)
                            .semantics { contentDescription = "Scroll to latest activity" }.clickable {
                            scope.launch {
                                chatViewport.returnToLatest()
                            }
                        },
                        color = DroideColors.Surface3,
                        border = BorderStroke(1.dp, DroideColors.BorderStrong),
                        tonalElevation = 2.dp,
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.ArrowDownward, null, Modifier.size(14.dp), tint = DroideColors.Primary)
                            Text(if (newActivityCount == 0) "Back to latest" else "$newActivityCount new activit${if (newActivityCount == 1) "y" else "ies"}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            AgentTaskDock(displayedTodos, taskDockMaxHeight, denseComposer,
                taskCollapsed, taskHidden, displayedQuestion != null || pendingApproval != null || landscapeImeTyping,
                onCollapsedChange = { taskCollapsed = it }, onHiddenChange = { taskHidden = it })

            displayedQuestion?.let { question ->
                InteractionDock(title = question.header.ifBlank { "Question" }, maxHeight = interactionDockMaxHeight) {
                    Text(question.question, style = MaterialTheme.typography.bodySmall)
                    question.options.forEach { option ->
                        OutlinedButton(onClick = { agent.questions.answer(question.id, option) }, Modifier.fillMaxWidth()) { Text(option) }
                    }
                    OutlinedTextField(customAnswer, { customAnswer = it.take(1_000) }, Modifier.fillMaxWidth(), placeholder = { Text("Custom answer") }, singleLine = true)
                    Button(onClick = { agent.questions.answer(question.id, customAnswer.ifBlank { "—" }) }, Modifier.fillMaxWidth()) { Text("Send answer") }
                }
            }

            HorizontalDivider(color = DroideColors.Border)
            Column(Modifier.fillMaxWidth().background(DroideColors.Surface).padding(composerOuterPadding)) {
                if (!usingExternalAgent) PreferenceReadFailureRow("Provider preferences", preferenceRead.state.failed, preferenceRead::retry)
                if (recoveredExitDrafts.isNotEmpty() || exitDraftError != null) {
                    TextButton(onClick = { showSavedExitDrafts = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("Saved drafts (${recoveredExitDrafts.size})", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (landscapeImeTyping) workspaceFilesControl()
                val visiblePendingInputs = if (usingExternalAgent) externalController.pendingInputsFor(agentBackendId) else pendingInputs
                if (visiblePendingInputs.isNotEmpty()) {
                    AgentPendingPromptStrip(
                        inputs = visiblePendingInputs,
                        onRemove = { id -> if (usingExternalAgent) externalController.removePendingInput(id)
                            else scope.launchUiCatching(onError = { onUiError("Cannot remove queued input: ${it.message}") }) { agent.removePendingInput(id) } },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Surface(
                    Modifier.fillMaxWidth().heightIn(min = 74.dp),
                    color = DroideColors.Background,
                    border = BorderStroke(1.dp, DroideColors.BorderStrong),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.padding(composerInnerPadding)) {
                        AgentDraftMediaRow(attachedContexts, mediaImporting, mediaImportError, agent.workDir, uiState::removeMediaAttachment, uiState::cancelMediaImport)
                        val attachControl: @Composable () -> Unit = {
                            AgentAttachmentMenu(
                                enabled = !mediaImporting && !mediaPickerPending && !preflightBusy && attachedContexts.size < 4,
                                onVisual = { launchAttachmentPicker(visual = true) },
                                onFile = { launchAttachmentPicker(visual = false) },
                            )
                        }
                        val modeControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(
                                    label = agentModeLabel(mode),
                                    icon = agentModeIcon(mode),
                                    height = controlHeight,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    showReasoningMenu = false
                                    showUsageMenu = false
                                    showModeMenu = !showModeMenu
                                }
                                DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }) {
                                    listOf(AgentMode.BUILD, AgentMode.PLAN, AgentMode.EXPLORE, AgentMode.REVIEW).forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(agentModeLabel(option))
                                                    Text(agentModeHint(option), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                                                }
                                            },
                                            leadingIcon = { Icon(agentModeIcon(option), null) },
                                            trailingIcon = if (mode == option) {{ Icon(Icons.Default.Check, null) }} else null,
                                            onClick = { agent.setMode(option); showModeMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                        val modelControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentModelControl(
                                    providerId = provider.id,
                                    model = model,
                                    height = controlHeight,
                                    logoSize = logoSize,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    showModeMenu = false
                                    showReasoningMenu = false
                                    showUsageMenu = false
                                    showModelPicker = !showModelPicker
                                }
                                AgentModelPickerMenu(
                                    expanded = showModelPicker,
                                    currentProviderId = providerId,
                                    currentModel = model,
                                    connectionBackend = providerConnections,
                                    localModels = localLlamaModels,
                                    onPick = { selectedProvider, selectedModel ->
                                        scope.launchUiCatching(onError = { onUiError("Could not select model: ${it.message ?: it::class.java.simpleName}") }) {
                                            localLlamaModels.selectRemote(selectedProvider, selectedModel)
                                        }
                                    },
                                    onConnectProvider = { requestedProviderId ->
                                        connectProviderId = requestedProviderId
                                        showModelPicker = false
                                        showConnect = true
                                    },
                                    onDismiss = { showModelPicker = false },
                                )
                            }
                        }
                        val externalModelControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(
                                    label = externalState.configuration?.currentModelName ?: externalState.historyWindow?.summary?.modelId ?: "Models",
                                    icon = Icons.Default.AutoAwesome,
                                    height = controlHeight, compact = compactControls, modifier = Modifier.fillMaxWidth(),
                                ) {
                                    showModeMenu = false; showReasoningMenu = false; showUsageMenu = false
                                    showModelPicker = !showModelPicker
                                    if (externalState.configuration == null && !externalBusy) {
                                        val plugin = selectedPlugin
                                        if (agentPlugins != null && plugin != null) externalController.prepare(agentPlugins, plugin.spec)
                                    }
                                }
                                selectedPlugin?.spec?.let { spec ->
                                    ExternalAgentModelMenu(showModelPicker, externalState, externalBusy, spec,
                                        onPick = { id ->
                                            showModelPicker = false
                                            agentPlugins?.let { externalController.selectModel(it, spec, id) }
                                        },
                                        onRefresh = { agentPlugins?.let { externalController.prepare(it, spec, force = true) } },
                                        onDismiss = { showModelPicker = false })
                                }
                            }
                        }
                        val reasoningControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(
                                    label = "Reason ${reasoningEffort?.label ?: "Auto"}",
                                    icon = Icons.Default.Psychology,
                                    height = controlHeight,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    showModeMenu = false
                                    showUsageMenu = false
                                    showReasoningMenu = !showReasoningMenu
                                }
                                DropdownMenu(expanded = showReasoningMenu, onDismissRequest = { showReasoningMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Default") },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                        trailingIcon = if (reasoningEffort == null) {{ Icon(Icons.Default.Check, null) }} else null,
                                        onClick = {
                                            reasoningEffort = null
                                            scope.launchUiCatching(onError = { onUiError("Could not update reasoning: ${it.message ?: it::class.java.simpleName}") }) { AgentPreferences.saveReasoningEffort(ctx, null) }
                                            showReasoningMenu = false
                                        },
                                    )
                                    ReasoningEffort.entries.forEach { effort ->
                                        val wire = ReasoningSupport.wire(provider.id, model, effort)
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(effort.label)
                                                    if (wire == null) Text("Not supported by this model/provider", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                                                }
                                            },
                                            enabled = wire != null,
                                            trailingIcon = if (reasoningEffort == effort) {{ Icon(Icons.Default.Check, null) }} else null,
                                            onClick = {
                                                reasoningEffort = effort
                                                scope.launchUiCatching(onError = { onUiError("Could not update reasoning: ${it.message ?: it::class.java.simpleName}") }) { AgentPreferences.saveReasoningEffort(ctx, effort) }
                                                showReasoningMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        val externalReasoningControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(label = "Reason ${externalState.configuration?.currentReasoningName ?: "CLI default"}",
                                    icon = Icons.Default.Psychology, height = controlHeight,
                                    modifier = Modifier.fillMaxWidth(), compact = compactControls) {
                                    showReasoningMenu = !showReasoningMenu
                                    showModelPicker = false; showModeMenu = false; showUsageMenu = false
                                    if (externalState.configuration == null && !externalBusy) {
                                        val plugin = selectedPlugin
                                        if (agentPlugins != null && plugin != null) externalController.prepare(agentPlugins, plugin.spec, force = true)
                                    }
                                }
                                ExternalAgentReasoningMenu(showReasoningMenu, externalState, externalBusy,
                                    onPick = { value ->
                                        showReasoningMenu = false
                                        val spec = selectedExternalSpec
                                        if (agentPlugins != null && spec != null) externalController.selectReasoning(agentPlugins, spec, value)
                                    },
                                    onRefresh = { val spec = selectedExternalSpec; if (agentPlugins != null && spec != null) externalController.prepare(agentPlugins, spec, force = true) },
                                    onDismiss = { showReasoningMenu = false })
                            }
                        }
                        val backendControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(
                                    label = selectedExternalSpec?.displayName ?: "Droide",
                                    icon = Icons.Default.SmartToy,
                                    height = controlHeight,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { showAgentBackendMenu = !showAgentBackendMenu }
                                DropdownMenu(expanded = showAgentBackendMenu, onDismissRequest = { showAgentBackendMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Column { Text("Droide Agent"); Text("Built-in", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) } },
                                        enabled = !busy && !externalBusy && !preflightBusy,
                                        trailingIcon = if (agentBackendId == "droide") {{ Icon(Icons.Default.Check, null) }} else null,
                                        onClick = { agentBackendId = "droide"; showAgentBackendMenu = false },
                                    )
                                    pluginAvailability.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Column { Text(item.spec.displayName); Text(when {
                                                item.available -> "ACP · detected in Linux"
                                                item.needsAdapter -> "CLI detected · add ACP adapter"
                                                else -> "Install from Extensions or Linux terminal"
                                            }, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) } },
                                            enabled = !busy && !externalBusy && !preflightBusy && (item.available || onOpenAgentExtensions != null),
                                            trailingIcon = if (agentBackendId == item.spec.id) {{ Icon(Icons.Default.Check, null) }} else null,
                                            onClick = {
                                                showAgentBackendMenu = false
                                                if (item.available) agentBackendId = item.spec.id
                                                else onOpenAgentExtensions?.invoke(item.spec.familyId)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        val primaryControl: @Composable () -> Unit = {
                            val interactionSource = remember { MutableInteractionSource() }
                            // Draft content owns Send, including while the current run is working.
                            // A blocked/preparing draft stays a disabled Send, never a destructive tap.
                            val showsStop = (effectiveBusy || preflightBusy || !usingExternalAgent && compactionActivity?.running == true) && !hasDraft
                            val enabled = showsStop || canSubmitDraft
                            val actionLabel = when {
                                localNavigation -> draftCommand()?.description ?: "Open feature"
                                showsStop && preflightBusy -> "Cancel provider preflight"
                                showsStop && !usingExternalAgent && !busy && compactionActivity?.running == true -> "Cancel context compaction"
                                showsStop -> "Stop active agent run"
                                canDeliverToActiveRun && promptDelivery == AgentPromptDelivery.QUEUE -> "Queue instruction"
                                canDeliverToActiveRun -> "Send instruction"
                                else -> "Send"
                            }
                            FilledIconButton(
                                onClick = {
                                    if (showsStop) {
                                        // An input commit can precede recomposition of this callback.
                                        // Never let the old Stop action cancel a newly typed instruction.
                                        if (input.isBlank() && attachedContexts.isEmpty()) stopActiveRun()
                                    } else if (canSubmitDraft) send()
                                },
                                // With a draft, explicit Stop remains in the run-options menu below.
                                enabled = enabled,
                                modifier = Modifier.size(48.dp),
                                interactionSource = interactionSource,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    contentColor = if (showsStop) DroideColors.Error else DroideColors.Text,
                                ),
                            ) {
                                if (showsStop) {
                                    AgentComposerStopIcon(sendSize, actionLabel, interactionSource)
                                } else Box(
                                    Modifier.size(sendSize).background(
                                        when {
                                            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
                                            else -> DroideColors.Primary
                                        },
                                        CircleShape,
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Send,
                                        actionLabel,
                                        Modifier.size(if (denseComposer) 16.dp else 18.dp),
                                    )
                                }
                            }
                        }

                        AgentComposerInput(
                            value = input,
                            catalogKey = agent,
                            commands = composerCommands,
                            loadMentions = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                AgentMentionCatalog.load(agent.workDir, agent.mcpPluginSource(), SkillManagement.personalRoot(ctx))
                            } },
                            onValueChange = { candidate ->
                                if (composerFocused) onComposerFocusChanged(true)
                                if (candidate.length > AgentService.MAX_USER_MESSAGE_CHARS) {
                                    uiState.submissionError.value = AGENT_DRAFT_LIMIT_ERROR
                                    false
                                } else {
                                    input = candidate
                                    if (uiState.submissionError.value == AGENT_DRAFT_LIMIT_ERROR || uiState.submissionError.value == AGENT_PROMPT_LIMIT_ERROR) {
                                        uiState.submissionError.value = null
                                    }
                                    true
                                }
                            },
                            onCommandSelected = { if (attachedContexts.isEmpty()) send() },
                            onFocusChanged = { focused -> composerFocused = focused; onComposerFocusChanged(focused) },
                            placeholder = if (effectiveBusy) "Add instruction…" else if (usingExternalAgent)
                                "Ask ${selectedPlugin?.spec?.displayName ?: "agent"}…" else "Ask Droide…",
                            maxHeight = composerInputMaxHeight,
                            dense = denseComposer,
                            trailingControl = primaryControl,
                        )
                        Spacer(Modifier.height(if (denseComposer) 2.dp else 4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Keep attachments available while the remaining controls scroll.
                            attachControl()
                            Row(
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (!usingExternalAgent) {
                                    modelControl(Modifier.widthIn(min = 104.dp, max = 176.dp))
                                    reasoningControl(Modifier.widthIn(min = 86.dp, max = 128.dp))
                                } else {
                                    externalModelControl(Modifier.widthIn(min = 104.dp, max = 176.dp))
                                    externalReasoningControl(Modifier.widthIn(min = 86.dp, max = 140.dp))
                                }
                                modeControl(Modifier.widthIn(min = 70.dp, max = 108.dp))
                                backendControl(Modifier.widthIn(min = 82.dp, max = 140.dp))
                                if (effectiveBusy || preflightBusy) AgentDeliveryControl(
                                    delivery = promptDelivery,
                                    height = controlHeight,
                                    compact = compactControls,
                                    canSubmit = canSubmitDraft,
                                    onSubmit = ::send,
                                    onSelect = { promptDelivery = it },
                                    canChooseDelivery = canDeliverToActiveRun,
                                    onStop = ::stopActiveRun,
                                    stopLabel = if (preflightBusy) "Cancel provider preflight" else "Stop active agent run",
                                    steerDescription = if (usingExternalAgent) "Redirect after active CLI tools finish; continue the same session" else "Promote at the next safe model-step boundary",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    }

    if (showConnect) {
        ProviderConnectionSheet(
            connectionBackend = providerConnections,
            initialProviderId = connectProviderId,
            onConnected = { id, validatedModel ->
                scope.launchUiCatching(onError = { onUiError("Cannot connect model: ${it.message ?: "unavailable"}") }) {
                    localLlamaModels.selectRemote(id, validatedModel)
                    ProviderAuthManager.cleanupLegacyWorkspaceSecrets(agent.workDir)
                }
            },
            onDismiss = { showConnect = false },
        )
    }


    mediaRecovery?.let { controller ->
        ProjectCopyRecoverySheet(controller, imports = false, projectKey = agent.workDir.absolutePath,
            sessionId = mediaRecoverySession, enabled = mediaRecoveryEnabled)
    }

    if (showSavedExitDrafts) RecoveredExitDraftsDialog(uiState) { showSavedExitDrafts = false }

    fun prepareSessionDraft(text: String) {
        val next = if (input.isBlank()) text else input.trimEnd() + "\n\n" + text
        if (next.length > AgentService.MAX_USER_MESSAGE_CHARS) {
            uiState.submissionError.value = AGENT_DRAFT_LIMIT_ERROR
        } else {
            input = next
            sessionTool = null
        }
    }
    fun continuationAvailability(): AgentContinuation = when {
        usingExternalAgent -> AgentContinuation.NEEDS_REVIEW
        preflightBusy || effectiveBusy || compactionActivity?.running == true -> AgentContinuation.WORKING
        else -> agent.continuationAvailability(conversationKey.sessionId)
    }
    when (sessionTool) {
        AgentSessionTool.RESULTS -> if (!usingExternalAgent) AgentResultsDialog(
            messages = messages,
            live = if (streamState.sessionId == conversationKey.sessionId) streamParts else emptyList(),
            media = emptyList(), workDir = agent.workDir, historyIsWindowed = false,
            onOpen = onOpenResult?.let { open -> { path -> open(path); sessionTool = null } },
            onExport = onExportResult?.let { export -> { path -> export(path); sessionTool = null } },
            onDismiss = { sessionTool = null },
        )
        AgentSessionTool.WORKFLOWS -> AgentWorkflowsDialog(
            loadTemplates = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val available = SkillManagement(FileRepository(agent.workDir), agent.mcpPluginSource(), SkillManagement.personalRoot(ctx))
                    .manager().invocationCatalog().map { it.name }.toSet()
                AgentWorkflowTemplates.entries.filter { it.skill in available }
            } },
            onSelect = ::prepareSessionDraft,
            onManageSkills = onManageSkills?.let { manage -> { sessionTool = null; manage() } },
            onDismiss = { sessionTool = null },
        )
        AgentSessionTool.RESUME -> if (!usingExternalAgent) AgentContinuationDialog(
            availability = continuationAvailability(),
            messages = messages,
            onPrepare = {
                if (continuationAvailability() == AgentContinuation.READY) prepareSessionDraft(AGENT_CONTINUATION_PROMPT)
            },
            onDismiss = { sessionTool = null },
        )
        null -> Unit
    }

    if (!usingExternalAgent && showBillingSheet) {
        DroideModalBottomSheet(onDismissRequest = { showBillingSheet = false }) { dismiss ->
            BillingSheet(tracker = agent.tokens, onDismiss = dismiss)
        }
    }
}

@Composable
internal fun AgentTranscriptItem(message: AgentMessage, showStreamDetails: Boolean, workDir: java.io.File, sessionId: String? = null,
    legacyAssistantPresentation: AgentTextPresentation = AgentTextPresentation.LEGACY) {
    if (message.publicContent != null) {
        AgentArchivedPublicAnswer(message, workDir, sessionId)
        AgentMessageMedia(message.media, workDir)
        return
    }
    if (message.role == "user" && message.streamPart == null) {
        AgentUserMessage(message.content, message.media, workDir)
        return
    }
    message.streamPart?.let { part ->
        val displayedPart = if (part.kind == AgentStreamPartKind.TEXT && part.textPresentation == AgentTextPresentation.LEGACY &&
            legacyAssistantPresentation != AgentTextPresentation.LEGACY) part.copy(textPresentation = legacyAssistantPresentation) else part
        AgentStreamPartWithMedia(displayedPart, showStreamDetails, message.media, workDir)
        return
    }
    if (legacyAssistantPresentation == AgentTextPresentation.LEGACY &&
        message.textPresentation == AgentTextPresentation.LEGACY && message.content.startsWith("TOOL|")) {
        val parts = message.content.split("|", limit = 4)
        val tool = parts.getOrNull(1) ?: "tool"
        val args = parts.getOrNull(2).orEmpty()
        val result = parts.getOrNull(3).orEmpty()
        AgentCompletedToolPart(tool, args, result)
        AgentMessageMedia(message.media, workDir)
        return
    }

    val presentation = message.textPresentation.takeUnless { it == AgentTextPresentation.LEGACY } ?: legacyAssistantPresentation
    if (message.role == "assistant" && presentation != AgentTextPresentation.LEGACY) {
        AgentPublicTextContent(
            markdown = message.content,
            presentation = presentation,
            presentationId = message.presentationId,
            publicTextSpans = message.publicTextSpans,
            modifier = Modifier.fillMaxWidth(),
        )
        AgentMessageMedia(message.media, workDir)
        return
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text("DROIDE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
        Spacer(Modifier.height(5.dp))
        AgentMarkdownContent(message.content, Modifier.fillMaxWidth())
        AgentMessageMedia(message.media, workDir)
    }
}

@Composable
private fun InteractionDock(title: String, maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).background(DroideColors.Surface).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Surface(Modifier.fillMaxWidth(), color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.BorderStrong), shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                content()
            }
        }
    }
}

@Composable
internal fun AgentTaskDock(todos: List<Todo>, taskDockMaxHeight: Dp, denseComposer: Boolean,
    taskCollapsed: Boolean, taskHidden: Boolean, forceCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit, onHiddenChange: (Boolean) -> Unit) {
    if (todos.none { it.status !in setOf("completed", "cancelled") }) return
    val done = todos.count { it.status == "completed" }
    val autoCollapsed = forceCollapsed
    val effectiveCollapsed = taskCollapsed || autoCollapsed
    if (taskHidden) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 30.dp).background(DroideColors.Surface)
                .clickable { onHiddenChange(false) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(14.dp), tint = DroideColors.Muted)
            Spacer(Modifier.width(5.dp))
            Text("Tasks", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("$done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        }
    } else {
        Column(Modifier.fillMaxWidth().background(DroideColors.Surface)) {
            HorizontalDivider(color = DroideColors.Border)
            Row(
                Modifier.fillMaxWidth().height(textControlHeight(MaterialTheme.typography.labelMedium,
                    if (denseComposer) 31.dp else 34.dp)).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.extraSmall)
                        .clickable { onCollapsedChange(!taskCollapsed) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (effectiveCollapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (effectiveCollapsed) "Expand tasks" else "Collapse tasks", Modifier.size(15.dp), tint = DroideColors.Muted)
                    Spacer(Modifier.width(4.dp))
                    Text("Tasks", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text("$done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
                IconButton(onClick = { onHiddenChange(true) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Close tasks", Modifier.size(14.dp), tint = DroideColors.Muted)
                }
            }
            if (!effectiveCollapsed) {
                Column(
                    Modifier
                        .heightIn(max = taskDockMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (denseComposer) 3.dp else 5.dp),
                ) {
                    todos.forEach { todo ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(
                                when (todo.status) {
                                    "completed" -> Icons.Default.CheckCircle
                                    "in_progress" -> Icons.Default.RadioButtonChecked
                                    else -> Icons.Default.RadioButtonUnchecked
                                },
                                todo.status,
                                Modifier.size(14.dp),
                                tint = when (todo.status) {
                                    "completed" -> DroideColors.Success
                                    "in_progress" -> DroideColors.Primary
                                    else -> DroideColors.Muted
                                },
                            )
                            Text(todo.content, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = if (denseComposer) 1 else 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

}

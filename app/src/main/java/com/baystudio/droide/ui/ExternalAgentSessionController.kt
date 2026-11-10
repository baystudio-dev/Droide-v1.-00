package com.baystudio.droide.ui

import android.content.Context
import com.baystudio.droide.core.OwnedDevelopmentOperation
import com.baystudio.droide.core.ApplicationWorkAdmission
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.baystudio.droide.core.AgentStreamPart
import com.baystudio.droide.core.ExternalTranscriptUpdate
import com.baystudio.droide.core.AgentStreamPartKind
import com.baystudio.droide.core.AgentMessage
import com.baystudio.droide.core.ChatMediaRef
import com.baystudio.droide.core.ChatMediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import com.baystudio.droide.core.AgentPendingInput
import com.baystudio.droide.core.AgentPromptDelivery
import com.baystudio.droide.core.WorkspaceAgentEvent
import com.baystudio.droide.core.WorkspaceAgentSyncPhase
import com.baystudio.droide.core.WorkspaceAgentMode
import com.baystudio.droide.core.WorkspaceAgentChatBackend
import com.baystudio.droide.core.WorkspaceAgentPluginSpec
import com.baystudio.droide.core.WorkspaceAgentSessionConfiguration
import com.baystudio.droide.core.WorkspaceAgentUsageSnapshot
import com.baystudio.droide.core.WorkspaceProcessRecoveryRequiredException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.baystudio.droide.core.PluginHistoryCheckpointStore
import com.baystudio.droide.core.PluginHistoryTurnIdentity
import com.baystudio.droide.core.PluginHistoryFinishRequest
import com.baystudio.droide.core.PluginConversationHistory
import com.baystudio.droide.core.PluginHistoryStatus
import com.baystudio.droide.core.PluginHistoryWindow
import com.baystudio.droide.core.RetainedEventChannel
import com.baystudio.droide.core.retainedChars
import com.baystudio.droide.core.appendBoundedTextTail

internal data class ExternalAgentConversationState(
    val sessionId: String? = null,
    val messages: List<AgentMessage> = emptyList(),
    val liveText: String = "",
    val liveThought: String = "",
    val activity: List<String> = emptyList(),
    val usage: WorkspaceAgentUsageSnapshot? = null,
    val configuration: WorkspaceAgentSessionConfiguration? = null,
    val connectionError: String? = null,
    val historyId: String? = null,
    val historyWindow: PluginHistoryWindow? = null,
    val historyRestored: Boolean = false,
    val persistenceError: String? = null,
    val liveMedia: List<ChatMediaRef> = emptyList(),
    val progress: ExternalAgentActivity = ExternalAgentActivity(),
    val streamParts: List<AgentStreamPart> = emptyList(),
)

internal data class ExternalAgentActiveRun(
    val runId: Long,
    val agentId: String,
    val sessionId: String?,
)

internal data class ExternalAgentPendingPrompt(
    val input: AgentPendingInput,
    val agentId: String,
    val originRunId: Long,
    val sessionId: String?,
    val historyId: String?,
    val mode: WorkspaceAgentMode,
    val modelId: String?,
    val reasoningId: String?,
    val configurationKnown: Boolean,
    val media: List<ChatMediaRef> = emptyList(),
    val mediaOwnerId: String? = null,
)

internal class ExternalAgentSessionController(
    private val scope: CoroutineScope,
    context: Context? = null,
    internal val history: PluginConversationHistory? = null,
    private val workDir: File? = null,
    private val maxEventRetainedChars: Long = 8_000_000L,
    private val workAdmission: () -> Boolean = { ApplicationWorkAdmission.isOpen },
) : AutoCloseable {
    init { require(maxEventRetainedChars > 0L) }

    private val appContext = context?.applicationContext
    private var closed = false
    private var exiting = false
    private var exitCheckpointSucceeded = false
    private val exitMutex = Mutex()
    private fun admissionOpen(): Boolean = !closed && !exiting && workAdmission()
    private fun requireAdmissionOpen() {
        if (!admissionOpen()) throw CancellationException("Application Exit has paused plugin work")
    }
    private val mediaStore by lazy { workDir?.let { ChatMediaStore(it) } }

    private fun releaseMediaOwner(owner: String?) {
        if (owner == null) return
        val cleanup = scope.launch(start = CoroutineStart.LAZY) { withContext(NonCancellable + Dispatchers.IO) {
            try { mediaStore?.releaseOwner(owner) } catch (_: Exception) {   }
        } }
        mediaCleanupJobs += cleanup
        cleanup.invokeOnCompletion { mediaCleanupJobs -= cleanup }
        cleanup.start()
    }

    private suspend fun <T> foreground(label: String, block: suspend () -> T): T =
        appContext?.let { OwnedDevelopmentOperation.run(it, label, block) } ?: block()

    override fun close() {
        closed = true
        lifecycleId = null
        val ownedRun = runJob
        val ownedLifecycle = lifecycleJob
        val ownedHistory = historyJob
        ownedRun?.cancel()
        ownedLifecycle?.cancel()
        ownedHistory?.cancel()
        livePreviewJob?.cancel()
        steerRequestJob?.cancel()
        pendingPrompts.mapNotNull { it.mediaOwnerId }.distinct().forEach(::releaseMediaOwner)
        pendingPrompts = emptyList(); activeBackend = null
        activeRun = null
        lifecycleBusyAgentId = null
        historyLoadingAgentId = null
        if (history != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                ownedRun?.join(); ownedLifecycle?.join(); ownedHistory?.join()
                checkpointJob?.cancel(); checkpointJob?.join()
                try { history.close() } catch (_: Exception) { /* Never delete a failed database. */ }
            }
        }
    }

    private val states = mutableStateMapOf<String, ExternalAgentConversationState>()
    private val workspaceReviewFeedback = ExternalWorkspaceReviewFeedback()
    private val runIds = AtomicLong(1)
    private var runJob: Job? = null
    private var lifecycleJob: Job? = null
    private var lifecycleId: Long? = null
    private var historyJob: Job? = null
    private val restoredAgents = mutableSetOf<String>()
    // Failed or cancelled initial reads are not evidence of an empty archive. In particular, a null
    // native ID must not fall through to a backend's existing live session.
    private val unverifiedInitialHistoryReads = mutableSetOf<String>()
    private val deletedNativeSessions = mutableMapOf<String, String>()

    private suspend fun discardDeleted(host: WorkspaceAgentChatBackend, agentId: String) {
        deletedNativeSessions[agentId]?.let { oldSession ->
            host.discardSession(agentId, oldSession)
            if (deletedNativeSessions[agentId] == oldSession) deletedNativeSessions.remove(agentId)
        }
    }

    /** Called only after the local history delete committed. The next send cannot reuse that native context. */
    fun resetDeletedConversation(id: String, agentId: String, host: WorkspaceAgentChatBackend? = null): Boolean {
        val state = this[agentId]
        if (state.historyId != id) return false
        if (busy || pendingPrompts.isNotEmpty() || !admissionOpen() || pendingFinish != null) return false
        state.sessionId?.let { deletedNativeSessions[agentId] = it }
        states[agentId] = ExternalAgentConversationState()
        restoredAgents += agentId
        val nativeId = deletedNativeSessions[agentId]
        if (host != null && nativeId != null) launchAuxiliary {
            requireAdmissionOpen()
            try {
                host.discardSession(agentId, nativeId)
                if (deletedNativeSessions[agentId] == nativeId) deletedNativeSessions.remove(agentId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retry before the next send; never reuse the deleted context. */ }
        }
        return true
    }
    private fun launchAuxiliary(block: suspend () -> Unit) {
        val owned = scope.launch(start = CoroutineStart.LAZY) { block() }
        auxiliaryJobs += owned
        owned.invokeOnCompletion { auxiliaryJobs -= owned }
        owned.start()
    }

    private val checkpointMutex = Mutex()
    private var checkpointJob: Job? = null
    private var checkpointRunId: Long? = null
    private var checkpointIdentity: PluginHistoryTurnIdentity? = null
    private data class PendingFinish(
        val runId: Long, val agentId: String, val historyId: String, val reply: String?,
        val notices: List<String>, val status: PluginHistoryStatus, val media: List<ChatMediaRef>,
        val identity: PluginHistoryTurnIdentity?,
    )
    private var pendingFinish: PendingFinish? = null
    private var uncheckpointedPrompt: ExternalAgentPendingPrompt? = null
    private val pendingOutput = ArrayDeque<ExternalTranscriptUpdate>()
    private var transcript = ExternalAgentTranscript()
    private var pendingOutputChars = 0
    private var pendingBinding: Pair<String, String?>? = null
    private var livePreviewJob: Job? = null
    private var livePreviewRunId: Long? = null
    private val liveAnswerPreview = StringBuilder()
    private val liveThoughtPreview = StringBuilder()
    private var activeBackend: WorkspaceAgentChatBackend? = null
    private var steerRequestJob: Job? = null
    private var stopJob: Job? = null
    private val auxiliaryJobs = linkedSetOf<Job>()
    private val mediaCleanupJobs = linkedSetOf<Job>()
    var pendingPrompts by mutableStateOf<List<ExternalAgentPendingPrompt>>(emptyList())
        private set

    /** Caller persists this snapshot before disposal; queued prompts are never auto-restarted on Exit. */
    fun snapshotPendingPromptsForExit(): List<ExternalAgentPendingPrompt> = listOfNotNull(uncheckpointedPrompt) + pendingPrompts

    suspend fun stopAndCheckpointForExit(host: WorkspaceAgentChatBackend?) = exitMutex.withLock {
        check(!closed) { "Plugin history was closed before its Exit checkpoint" }
        exiting = true
        exitCheckpointSucceeded = false
        val run = activeRun
        val backend = host ?: activeBackend
        val owned = (listOfNotNull(runJob, lifecycleJob, historyJob, steerRequestJob, stopJob) + auxiliaryJobs).distinct()
        owned.forEach { it.cancel(CancellationException("Application Exit")) }
        // Only the protocol cancel handshake is bounded. Exact owned cleanup has no arbitrary deadline.
        try {
            if (backend != null && run?.sessionId != null) {
                withTimeoutOrNull(3_000) { backend.cancel(run.agentId, run.sessionId) }
            }
        } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
        catch (_: Exception) { /* The exact owned run remains cancelled and is joined below. */ }
        owned.joinAll()
        mediaCleanupJobs.toList().joinAll()
        if (activeRun?.runId == run?.runId) { activeRun = null; runJob = null; activeBackend = null }
        lifecycleBusyAgentId = null; lifecycleId = null; lifecycleJob = null
        historyLoadingAgentId = null; historyJob = null; stoppingAgentId = null
        livePreviewJob?.cancel(); livePreviewJob?.join()
        checkpointJob?.cancel(); checkpointJob?.join()
        pendingFinish?.let { finish ->
            check(finishHistory(finish.runId, finish.agentId, finish.historyId, finish.reply, finish.notices, finish.status)) {
                this[finish.agentId].persistenceError ?: "Plugin Exit checkpoint did not commit"
            }
        }
        check(pendingFinish == null && pendingOutput.isEmpty() && pendingBinding == null) {
            "Plugin Exit checkpoint is incomplete; unsaved output was retained"
        }
        exitCheckpointSucceeded = true
    }

    /** Host calls only after the pending prompt snapshot has been archived durably. */
    suspend fun closeAfterExitCheckpoint() = exitMutex.withLock {
        if (closed) return@withLock
        check(exiting && exitCheckpointSucceeded && pendingFinish == null) { "Checkpoint plugin history before closing it" }
        (listOfNotNull(runJob, lifecycleJob, historyJob, checkpointJob, livePreviewJob, steerRequestJob, stopJob) + auxiliaryJobs + mediaCleanupJobs).distinct().joinAll()
        history?.close()
        closed = true
        pendingPrompts = emptyList(); uncheckpointedPrompt = null
        activeBackend = null; activeRun = null
        liveAnswerPreview.setLength(0); liveThoughtPreview.setLength(0)
    }

    fun pendingInputsFor(agentId: String): List<AgentPendingInput> = pendingPrompts.filter { it.agentId == agentId }.map { it.input }
    fun removePendingInput(id: String) {
        if (!admissionOpen()) return
        pendingPrompts.firstOrNull { it.input.id == id }?.mediaOwnerId?.let(::releaseMediaOwner)
        pendingPrompts = pendingPrompts.filterNot { it.input.id == id }
        val run = activeRun ?: return
        if (pendingPrompts.any { it.agentId == run.agentId && it.input.delivery == AgentPromptDelivery.STEER }) return
        val host = activeBackend ?: return
        val sessionId = run.sessionId ?: return
        steerRequestJob?.cancel()
        steerRequestJob = scope.launch {
            try { requireAdmissionOpen(); if (activeRun?.runId == run.runId) host.clearSteerRequest(run.agentId, sessionId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Removing a pending input never closes a healthy CLI. */ }
        }
    }
    fun canAcceptWhileRunning(agentId: String): Boolean = admissionOpen() && scope.isActive && stoppingAgentId == null &&
        lifecycleBusyAgentId == null && historyLoadingAgentId == null && activeRun?.agentId == agentId

    var activeRun by mutableStateOf<ExternalAgentActiveRun?>(null)
        private set
    var lifecycleBusyAgentId by mutableStateOf<String?>(null)
        private set
    private var stoppingAgentId by mutableStateOf<String?>(null)
    var historyLoadingAgentId by mutableStateOf<String?>(null)
        private set

    val busy: Boolean get() = activeRun != null || lifecycleBusyAgentId != null || stoppingAgentId != null || historyLoadingAgentId != null

    operator fun get(agentId: String): ExternalAgentConversationState =
        states[agentId] ?: ExternalAgentConversationState()

    private fun publishWindow(agentId: String, window: PluginHistoryWindow, restored: Boolean) {
        clearUnverifiedInitialHistory(agentId)
        val current = this[agentId]
        val readOnly = window.readOnlyReason != null
        states[agentId] = current.copy(
            // A foreign live/unverified identity is retained in the archive, but must never become authority
            // to reconnect its CLI or change its configuration.
            sessionId = if (readOnly) null else window.summary.nativeSessionId,
            usage = if (!readOnly && current.sessionId == window.summary.nativeSessionId) current.usage else null,
            configuration = if (readOnly) null else current.configuration,
            connectionError = if (readOnly || current.historyWindow?.readOnlyReason != null) window.readOnlyReason else current.connectionError,
            messages = window.messages,
            historyId = window.summary.id,
            historyWindow = window,
            historyRestored = restored,
            liveText = "",
            liveThought = "",
            liveMedia = emptyList(),
            streamParts = emptyList(),
        )
    }

    /** Called on backend selection; the owned disk read survives removal of the panel. */
    fun restoreLatest(spec: WorkspaceAgentPluginSpec, afterEmpty: (() -> Unit)? = null): Boolean {
        val store = history ?: return false
        if (spec.id in restoredAgents || busy || !admissionOpen() || pendingFinish != null || !scope.isActive) return false
        historyLoadingAgentId = spec.id
        historyJob = scope.launch(start = CoroutineStart.LAZY) {
            var empty = false
            try {
                val saved = store.latest(spec.id)
                if (closed) return@launch
                if (saved != null) {
                    restoredAgents += spec.id
                    publishWindow(spec.id, saved, restored = true)
                } else if (spec.id in unverifiedInitialHistoryReads) {
                    // An empty retry does not prove the backend has no old live context. Only a verified selection
                    // or explicit New Session clears this gate.
                    states[spec.id] = this@ExternalAgentSessionController[spec.id].copy(persistenceError = null)
                    rejectUnverifiedInitialHistory(spec.id)
                } else {
                    restoredAgents += spec.id
                    empty = true
                }
            } catch (cancelled: CancellationException) {
                recordUnverifiedInitialHistory(spec.id)
                throw cancelled
            } catch (failure: Exception) {
                recordUnverifiedInitialHistory(spec.id)
                states[spec.id] = this@ExternalAgentSessionController[spec.id].copy(persistenceError = "Cannot read plugin history: ${failure.message ?: "storage error"}")
            } finally { historyLoadingAgentId = null; historyJob = null }
            if (empty && admissionOpen()) afterEmpty?.invoke()
        }
        historyJob?.start()
        return true
    }

    fun openSaved(window: PluginHistoryWindow): Boolean {
        if (busy || !admissionOpen() || pendingFinish != null || !scope.isActive) return false
        restoredAgents += window.summary.agentId
        states[window.summary.agentId] = ExternalAgentConversationState()
        publishWindow(window.summary.agentId, window, restored = true)
        return true
    }

    suspend fun openSaved(id: String, agentId: String): PluginHistoryWindow {
        val store = checkNotNull(history) { "Plugin history is unavailable" }
        check(!busy && pendingPrompts.isEmpty() && admissionOpen() && pendingFinish == null && scope.isActive) { "Finish the active task and remove pending inputs before opening another conversation." }
        val reader = kotlinx.coroutines.currentCoroutineContext()[Job]
        historyJob = reader
        historyLoadingAgentId = agentId
        try {
            val saved = checkNotNull(store.load(id)) { "Saved plugin conversation is unavailable" }
            check(admissionOpen() && scope.isActive) { "Workspace changed while history was loading" }
            require(saved.summary.agentId == agentId) { "Saved conversation belongs to another plugin" }
            restoredAgents += agentId
            states[agentId] = ExternalAgentConversationState()
            publishWindow(agentId, saved, restored = true)
            return saved
        } catch (cancelled: CancellationException) {
            recordUnverifiedInitialHistory(agentId)
            throw cancelled
        } catch (failure: Exception) {
            recordUnverifiedInitialHistory(agentId)
            throw failure
        } finally { historyLoadingAgentId = null; if (historyJob === reader) historyJob = null }
    }

    fun pageHistory(agentId: String, earlier: Boolean) {
        val store = history ?: return
        val window = this[agentId].historyWindow ?: return
        if (busy || !admissionOpen() || pendingFinish != null || !scope.isActive || earlier && !window.hasEarlier) return
        historyLoadingAgentId = agentId
        historyJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = store.load(window.summary.id, if (earlier) window.firstPart else null)
                    ?: error("Saved plugin conversation is unavailable")
                if (!closed) publishWindow(agentId, page, restored = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                states[agentId] = this@ExternalAgentSessionController[agentId].copy(persistenceError = "Cannot read older messages: ${failure.message ?: "storage error"}")
            } finally { historyLoadingAgentId = null; historyJob = null }
        }
        historyJob?.start()
    }

    private fun checkpointSoon(runId: Long, agentId: String) {
        if (history == null || checkpointJob != null || closed) return
        checkpointJob = scope.launch(start = CoroutineStart.LAZY) {
            var failed = false
            try {
                if (pendingOutputChars < 64_000) delay(1_000)
                flushCheckpoint(runId, agentId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                failed = true
                states[agentId] = this@ExternalAgentSessionController[agentId].copy(persistenceError = "Cannot save plugin progress: ${failure.message ?: "storage error"}")
            } finally { checkpointJob = null }
            if (!failed && !closed && activeRun?.runId == runId && (pendingOutput.isNotEmpty() || pendingBinding != null)) checkpointSoon(runId, agentId)
        }
        checkpointJob?.start()
    }

    private suspend fun bindCheckpoint(store: PluginConversationHistory, id: String, identity: PluginHistoryTurnIdentity?, binding: Pair<String, String?>) {
        if (store is PluginHistoryCheckpointStore) store.bindCheckpoint(checkNotNull(identity) { "Plugin turn receipt is unavailable" }, binding.first, binding.second)
        else store.bind(id, binding.first, binding.second)
    }

    private suspend fun flushCheckpoint(runId: Long, agentId: String, historyId: String? = this[agentId].historyId) {
        val store = history ?: return
        val id = historyId ?: return
        checkpointMutex.withLock {
            if (checkpointRunId != runId) return@withLock
            val binding = pendingBinding
            val fragments = pendingOutput.toList()
            // The database commit and queue acknowledgement must not be split by Stop.
            withContext(NonCancellable) {
                if (binding != null) {
                    bindCheckpoint(store, id, checkpointIdentity, binding)
                    if (pendingBinding == binding) pendingBinding = null
                }
                if (fragments.isNotEmpty()) {
                    if (!transcript.hasActivity && fragments.all { it.textDelta != null }) {
                        val text = fragments.map { checkNotNull(it.textDelta) }
                        if (store is PluginHistoryCheckpointStore) store.appendCheckpointPartial(checkNotNull(checkpointIdentity), text)
                        else store.appendPartial(id, text)
                    } else if (store is PluginHistoryCheckpointStore) store.appendCheckpointTranscript(checkNotNull(checkpointIdentity), fragments)
                    else store.appendTranscript(id, fragments)
                    repeat(fragments.size) { pendingOutputChars -= pendingOutput.removeFirst().retainedChars }
                }
            }
        }
    }

    private suspend fun finishHistory(runId: Long, agentId: String, id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus): Boolean {
        val store = history ?: return true

        val finish = pendingFinish?.also {
            check(it.runId == runId && it.historyId == id) { "A prior plugin checkpoint still needs retry" }
        } ?: PendingFinish(runId, agentId, id, reply, notices.toList(), status, this[agentId].liveMedia.toList(),
            checkpointIdentity?.takeIf { checkpointRunId == runId && it.conversationId == id })
            .also { pendingFinish = it }
        return withContext(NonCancellable) {
            checkpointJob?.cancel(); checkpointJob?.join()
            try {
                if (finish.reply == null || transcript.hasActivity) flushCheckpoint(runId, agentId, id)
                else pendingBinding?.let { binding ->
                    bindCheckpoint(store, id, finish.identity, binding)
                    // A lost final receipt must not reapply a previously acknowledged binding.
                    if (pendingBinding == binding) pendingBinding = null
                }
                val saved = if (store is PluginHistoryCheckpointStore) store.finishCheckpoint(PluginHistoryFinishRequest(
                    checkNotNull(finish.identity) { "Plugin turn receipt is unavailable" }, finish.reply, finish.notices, finish.status, finish.media))
                    else store.finishMediaTurn(id, finish.reply, finish.notices, finish.status, finish.media)
                if (!closed && this@ExternalAgentSessionController[agentId].historyId == id) {
                    publishWindow(agentId, saved, restored = false)
                    states[agentId] = this@ExternalAgentSessionController[agentId].copy(persistenceError = null)
                }
                checkpointRunId = null; checkpointIdentity = null; pendingOutput.clear(); pendingOutputChars = 0; pendingBinding = null
                if (pendingFinish === finish) pendingFinish = null
                true
            } catch (failure: Exception) {
                if (!closed && this@ExternalAgentSessionController[agentId].historyId == id) {
                    val current = this@ExternalAgentSessionController[agentId]
                    val visiblePartial = current.liveText.takeIf { finish.reply == null && it.isNotBlank() }
                    val activity = transcript.messages(finish.reply, finish.status, finish.media)
                    val missingActivity = activity.any { part -> part.streamPart != null && current.messages.none { it.streamPart?.id == part.streamPart.id } }
                    states[agentId] = current.copy(
                        messages = if (missingActivity) current.messages + activity
                            else if (visiblePartial != null && current.messages.lastOrNull()?.content != visiblePartial)
                                current.messages + AgentMessage("assistant", visiblePartial, media = finish.media) else current.messages,
                        persistenceError = "Cannot save plugin conversation: ${failure.message ?: "storage error"}. Unsaved output is retained for retry.",
                    )
                }
                false
            }
        }
    }

    /** An explicit Send or New Session can retry local storage, but never the requested backend
     * action. */
    private fun retryPendingFinish(): Boolean {
        val finish = pendingFinish ?: return false
        if (busy || !admissionOpen() || !scope.isActive) return true
        val operationId = runIds.getAndIncrement()
        lifecycleId = operationId
        lifecycleBusyAgentId = finish.agentId
        lifecycleJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                requireAdmissionOpen()
                finishHistory(finish.runId, finish.agentId, finish.historyId, finish.reply, finish.notices, finish.status)
            } finally {
                if (lifecycleId == operationId) { lifecycleBusyAgentId = null; lifecycleId = null; lifecycleJob = null }
            }
        }
        lifecycleJob?.start()
        return true
    }

    fun submit(
        host: WorkspaceAgentChatBackend,
        spec: WorkspaceAgentPluginSpec,
        prompt: String,
        mode: WorkspaceAgentMode,
        delivery: AgentPromptDelivery = AgentPromptDelivery.STEER,
        media: List<ChatMediaRef> = emptyList(),
        mediaOwnerId: String? = null,
    ): Boolean {
        if (!admissionOpen() || !scope.isActive || prompt.isBlank() || prompt.length > 200_000) return false
        if (rejectUnverifiedInitialHistory(spec.id)) return false
        if (rejectReadOnlyHistory(spec.id)) return false
        if (retryPendingFinish()) return false
        if (media.isNotEmpty() && mediaStore == null) {
            states[spec.id] = this[spec.id].copy(connectionError = "Chat media storage is unavailable. Your draft was kept.")
            return false
        }
        host.mediaAdmissionError(spec.id, media)?.let {
            states[spec.id] = this[spec.id].copy(connectionError = it)
            return false
        }
        if (busy) return enqueue(host, spec, prompt, mode, delivery, media, mediaOwnerId)
        val agentId = spec.id
        val initial = this[agentId]
        if (initial.historyRestored && initial.sessionId == null) {
            states[agentId] = initial.copy(connectionError = "This saved conversation has no resumable CLI session. Start a new session to continue working.")
            return false
        }
        val runId = runIds.getAndIncrement()
        transcript = ExternalAgentTranscript()
        states[agentId] = initial.copy(
            messages = initial.messages + AgentMessage("user", prompt, media = media),
            liveText = "",
            liveThought = "",
            activity = emptyList(),
            progress = ExternalAgentActivity(),
            connectionError = null,
            persistenceError = null,
            liveMedia = emptyList(),
            streamParts = emptyList(),
        )
        uncheckpointedPrompt = ExternalAgentPendingPrompt(
            AgentPendingInput("plugin-input-$runId", prompt, delivery, agentId, initial.configuration?.currentModelId.orEmpty(), media = media),
            agentId, runId, initial.sessionId, initial.historyId, mode, initial.configuration?.currentModelId,
            initial.configuration?.currentReasoningId, initial.configuration != null, media, mediaOwnerId,
        )
        activeRun = ExternalAgentActiveRun(runId, agentId, initial.sessionId)
        activeBackend = host
        livePreviewJob?.cancel(); livePreviewJob = null
        livePreviewRunId = runId; liveAnswerPreview.setLength(0); liveThoughtPreview.setLength(0)
        runJob = scope.launch(start = CoroutineStart.LAZY) {
            var savedTurn = false
            var savedHistoryId: String? = null
            val eventOverflow = AtomicBoolean(false)
            val eventDeliveryFailure = AtomicBoolean(false)
            var completedNormally = false
            try {
                requireAdmissionOpen()
                discardDeleted(host, agentId)
                currentCoroutineContext().ensureActive()
                if (history != null) {
                    // Only admission and its receipt are protected. Returning from the store's IO dispatcher must
                    // not discard an already committed conversation when Stop wins.
                    val window = withContext(NonCancellable) {
                        val admitted = if (history is PluginHistoryCheckpointStore) {
                            val receipt = history.beginCheckpointTurn(initial.historyId, agentId, prompt, mode, initial.sessionId, media)
                            checkpointIdentity = receipt.identity
                            receipt.window
                        } else {
                            checkpointIdentity = null
                            history.beginMediaTurn(initial.historyId, agentId, prompt, mode, initial.sessionId, media)
                        }
                        savedHistoryId = admitted.summary.id
                        savedTurn = true
                        // Preserve admission identity even if Stop/close suppresses presentation.
                        checkpointRunId = runId
                        if (uncheckpointedPrompt?.originRunId == runId) uncheckpointedPrompt = null
                        if (!closed && activeRun?.runId == runId) {
                            publishWindow(agentId, admitted, restored = false)
                            restoredAgents += agentId
                        }
                        admitted
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) {
                        if (media.isNotEmpty()) mediaStore?.retain(window.summary.id, media)
                        if (mediaOwnerId != null && mediaOwnerId != window.summary.id) mediaStore?.releaseOwner(mediaOwnerId)
                    }
                }
                currentCoroutineContext().ensureActive()
                val events = RetainedEventChannel<WorkspaceAgentEvent>(128, maxEventRetainedChars)
                val ownedRun = kotlinx.coroutines.currentCoroutineContext()[Job]
                val eventRelay = scope.launch {
                    try { events.consume { event -> applyEvent(runId, agentId, event) } }
                    catch (cancelled: CancellationException) {
                        if (ownedRun?.isActive == true && scope.isActive && !closed) {
                            eventDeliveryFailure.set(true)
                            ownedRun.cancel(CancellationException("Agent update delivery stopped"))
                        }
                        throw cancelled
                    } catch (_: Exception) {
                        // This is an independent supervisor child. An ordinary delivery failure must stop its exact run,
                        // not escape as an Android crash.
                        eventDeliveryFailure.set(true)
                        ownedRun?.cancel(CancellationException("Agent update delivery failed"))
                    }
                }
                val result = try {
                    if (history == null && uncheckpointedPrompt?.originRunId == runId) uncheckpointedPrompt = null
                    foreground("${spec.displayName} task") {
                        requireAdmissionOpen()
                        host.runMediaTurn(agentId, prompt, media, this@ExternalAgentSessionController[agentId].historyId ?: mediaOwnerId,
                            mode, resumeSessionId = initial.sessionId) { event ->
                            when (events.tryOffer(event, event.retainedChars())) {
                                RetainedEventChannel.Admission.ACCEPTED, RetainedEventChannel.Admission.CLOSED -> Unit
                                RetainedEventChannel.Admission.OVERFLOW -> {
                                    eventOverflow.set(true)
                                    ownedRun?.cancel(CancellationException("Agent update buffer is full"))
                                }
                            }
                        }
                    }
                } finally {
                    events.close()
                    withContext(NonCancellable) { eventRelay.join() }
                }
                // Draining accepted updates must not turn cancellation into a normal completion when a backend
                // returns without suspending.
                currentCoroutineContext().ensureActive()
                if (activeRun?.runId != runId) return@launch
                flushLivePreview(runId, agentId)
                val current = this@ExternalAgentSessionController[agentId]
                val redirected = result.stopReason == "cancelled" && pendingPrompts.any { it.agentId == agentId && it.input.delivery == AgentPromptDelivery.STEER }
                val terminalStatus = when (result.stopReason) {
                    "end_turn" -> PluginHistoryStatus.COMPLETED
                    "cancelled", "max_tokens", "max_turn_requests", "refusal" -> PluginHistoryStatus.STOPPED
                    else -> PluginHistoryStatus.FAILED
                }
                val stopNotice = when (result.stopReason) {
                    "end_turn", "cancelled" -> null
                    "max_tokens" -> "${spec.displayName} reached a token limit for this turn (max_tokens). Pending inputs are paused."
                    "max_turn_requests" -> "${spec.displayName} reached the limit on model requests within this turn (max_turn_requests). Pending inputs are paused."
                    "refusal" -> "${spec.displayName} refused to continue this turn (refusal). Pending inputs are paused."
                    else -> "${spec.displayName} returned an unrecognized stop reason (${result.stopReason.take(120).ifBlank { "empty" }}). Pending inputs are paused."
                }
                val reply = result.text.ifBlank { current.liveText }.ifBlank {
                    when {
                        redirected -> "${spec.displayName} redirected; continuing this session."
                        result.stopReason == "cancelled" || stopNotice != null -> "${spec.displayName} stopped the turn."
                        else -> "${spec.displayName} completed the turn."
                    }
                }
                val completedMessages = transcript.messages(reply, terminalStatus, current.liveMedia)
                var messages = current.messages + completedMessages
                if (stopNotice != null) messages += AgentMessage("assistant", stopNotice)
                messages += workspaceReviewFeedback.messages(
                    key = "$agentId:${result.sessionId}", changed = result.changedFiles,
                    pending = result.conflicts, state = host.workspaceSyncState?.value, manual = false,
                ).map { AgentMessage("assistant", it) }
                states[agentId] = current.copy(sessionId = result.sessionId, messages = messages, liveText = "", liveThought = "", streamParts = emptyList(),
                    progress = current.progress.finish(when (terminalStatus) {
                        PluginHistoryStatus.COMPLETED -> AgentActivityKind.COMPLETED
                        PluginHistoryStatus.STOPPED -> AgentActivityKind.STOPPED
                        else -> AgentActivityKind.FAILED
                    }),
                    usage = if (current.sessionId == result.sessionId) current.usage else null)
                if (savedTurn) {
                    pendingBinding = result.sessionId to current.configuration?.currentModelId
                    finishHistory(runId, agentId, checkNotNull(savedHistoryId), reply,
                        messages.drop(current.messages.size + completedMessages.size).map { it.content }, terminalStatus)
                }
                completedNormally = host.workspaceSyncState?.value?.phase != WorkspaceAgentSyncPhase.INTERRUPTED &&
                    (result.stopReason == "end_turn" || result.stopReason == "cancelled" &&
                        pendingPrompts.any { it.agentId == agentId && it.input.delivery == AgentPromptDelivery.STEER })
            } catch (cancelled: CancellationException) {
                flushLivePreview(runId, agentId)
                val resourceFailure = eventOverflow.get() || this@ExternalAgentSessionController[agentId].persistenceError?.startsWith("Output arrived faster") == true
                val deliveryFailure = eventDeliveryFailure.get()
                val failed = resourceFailure || deliveryFailure
                val stopMessage = when {
                    deliveryFailure -> "Agent updates could not be delivered. Work was stopped; saved partial history was kept."
                    resourceFailure -> "The Agent produced updates faster than they could be saved. Work was stopped to protect device memory; saved history was kept."
                    else -> "Stopped."
                }
                if (savedTurn) finishHistory(runId, agentId, checkNotNull(savedHistoryId), null, listOf(stopMessage), if (failed) PluginHistoryStatus.FAILED else PluginHistoryStatus.STOPPED)
                if (activeRun?.runId == runId) {
                    val current = this@ExternalAgentSessionController[agentId]
                    states[agentId] = current.copy(
                        configuration = null,
                        messages = if (!savedTurn) current.messages + transcript.messages(current.liveText.takeIf { it.isNotBlank() },
                            if (failed) PluginHistoryStatus.FAILED else PluginHistoryStatus.STOPPED, current.liveMedia) else current.messages,
                        streamParts = emptyList(),
                        progress = current.progress.finish(if (failed) AgentActivityKind.FAILED else AgentActivityKind.STOPPED),
                        liveText = "", liveThought = "", activity = listOf(stopMessage), persistenceError = if (failed) stopMessage else current.persistenceError)
                }
                throw cancelled
            } catch (error: Exception) {
                if (activeRun?.runId != runId) return@launch
                flushLivePreview(runId, agentId)
                val current = this@ExternalAgentSessionController[agentId]
                val message = if (error is WorkspaceProcessRecoveryRequiredException) {
                    error.message ?: "CLI session recovery is required. Your workspace files were preserved."
                } else "${spec.displayName} error: ${error.message ?: "unknown error"}"
                states[agentId] = current.copy(
                    configuration = null,
                    messages = current.messages + (if (!savedTurn) transcript.messages(current.liveText.takeIf { it.isNotBlank() },
                        PluginHistoryStatus.FAILED, current.liveMedia) else emptyList()) + AgentMessage("assistant", message),
                    streamParts = emptyList(),
                    liveText = "", liveThought = "",
                    progress = current.progress.finish(AgentActivityKind.FAILED),
                    persistenceError = if (history != null && !savedTurn) "The prompt was not sent because history could not be saved: ${error.message ?: "storage error"}" else current.persistenceError,
                )
                if (savedTurn) finishHistory(runId, agentId, checkNotNull(savedHistoryId), null, listOf(message), PluginHistoryStatus.FAILED)
            } finally {
                if (livePreviewRunId == runId) {
                    livePreviewJob?.cancel(); livePreviewJob = null; livePreviewRunId = null
                    liveAnswerPreview.setLength(0); liveThoughtPreview.setLength(0)
                }
                if (activeRun?.runId == runId) {
                    activeRun = null; runJob = null; activeBackend = null
                    steerRequestJob?.cancel(); steerRequestJob = null
                    if (completedNormally && stoppingAgentId == null && admissionOpen() && pendingFinish == null) promoteNext(host, spec)
                }
            }
        }
        runJob?.start()
        return true
    }

    fun prepare(host: WorkspaceAgentChatBackend, spec: WorkspaceAgentPluginSpec, force: Boolean = false) {
        if (!admissionOpen() || pendingFinish != null) return
        if (rejectReadOnlyHistory(spec.id)) return
        if (history != null && spec.id !in restoredAgents) {
            restoreLatest(spec) { prepare(host, spec, force) }
            return
        }
        if (!force && this[spec.id].historyRestored) return
        if (this[spec.id].historyRestored && this[spec.id].sessionId == null) {
            states[spec.id] = this[spec.id].copy(connectionError = "This saved conversation has no resumable CLI session. Start a new session to continue working.")
            return
        }
        if (busy || !force && this[spec.id].configuration != null) return
        lifecycle(spec) { operationId ->
            discardDeleted(host, spec.id)
            // A synchronous prepare result must not overtake its admitted session updates.
            coroutineScope {
                requireAdmissionOpen()
                host.prepareSession(spec.id, this@ExternalAgentSessionController[spec.id].sessionId) { event ->
                    launch { applyLifecycleEvent(operationId, spec.id, event) }
                }
            }
        }
    }

    fun selectModel(host: WorkspaceAgentChatBackend, spec: WorkspaceAgentPluginSpec, modelId: String) {
        if (rejectUnverifiedInitialHistory(spec.id)) return
        if (rejectReadOnlyHistory(spec.id)) return
        val state = this[spec.id]
        val sessionId = state.sessionId ?: return
        if (configurationChangeBlocked(spec.id) || state.configuration?.models?.none { it.id == modelId } != false) return
        lifecycle(spec) { host.selectModel(spec.id, sessionId, modelId) }
    }

    fun selectReasoning(host: WorkspaceAgentChatBackend, spec: WorkspaceAgentPluginSpec, value: String) {
        if (rejectUnverifiedInitialHistory(spec.id)) return
        if (rejectReadOnlyHistory(spec.id)) return
        val state = this[spec.id]
        val sessionId = state.sessionId ?: return
        if (configurationChangeBlocked(spec.id) || state.configuration?.reasoningOptions?.none { it.id == value } != false) return
        lifecycle(spec) { host.selectReasoning(spec.id, sessionId, value) }
    }

    private fun recordUnverifiedInitialHistory(agentId: String) {
        val state = this[agentId]
        if (state.historyId != null || state.sessionId != null) return
        unverifiedInitialHistoryReads += agentId
        restoredAgents -= agentId // A later prepare may retry the read, but not skip it.
    }

    private fun clearUnverifiedInitialHistory(agentId: String) {
        if (unverifiedInitialHistoryReads.remove(agentId)) {
            states[agentId] = this[agentId].copy(connectionError = null, persistenceError = null)
        }
    }

    private fun rejectUnverifiedInitialHistory(agentId: String): Boolean {
        if (agentId !in unverifiedInitialHistoryReads) return false
        states[agentId] = this[agentId].copy(connectionError = "Saved history could not be verified. Retry opening it or use New Session to start independent work.")
        return true
    }

    private fun rejectReadOnlyHistory(agentId: String): Boolean {
        val state = this[agentId]
        val reason = state.historyWindow?.readOnlyReason ?: return false
        states[agentId] = state.copy(connectionError = reason)
        return true
    }

    private fun configurationChangeBlocked(agentId: String): Boolean {
        if (!admissionOpen() || pendingFinish != null) return true
        if (busy) return true
        if (pendingInputsFor(agentId).isEmpty()) return false
        states[agentId] = this[agentId].copy(connectionError = "Remove pending inputs before changing the CLI model or Reasoning.")
        return true
    }

    private fun lifecycle(spec: WorkspaceAgentPluginSpec,
                          operation: suspend (Long) -> WorkspaceAgentEvent.Configuration) {
        if (busy || !admissionOpen() || pendingFinish != null || !scope.isActive) return
        val agentId = spec.id
        val operationId = runIds.getAndIncrement()
        lifecycleId = operationId
        lifecycleBusyAgentId = agentId
        states[agentId] = this[agentId].copy(connectionError = null, activity = emptyList())
        lifecycleJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                requireAdmissionOpen()
                val config = foreground("${spec.displayName} preparation") { requireAdmissionOpen(); operation(operationId) }
                if (lifecycleId != operationId) return@launch
                val current = this@ExternalAgentSessionController[agentId]
                states[agentId] = current.copy(sessionId = config.sessionId, configuration = config.value, connectionError = null,
                    usage = if (current.sessionId == config.sessionId) current.usage else null)
                current.historyId?.let { history?.bind(it, config.sessionId, config.value.currentModelId) }
            } catch (cancelled: CancellationException) {
                if (lifecycleId == operationId) states[agentId] = this@ExternalAgentSessionController[agentId].copy(configuration = null, activity = listOf("Stopped."))
                throw cancelled
            } catch (error: Exception) {
                if (lifecycleId == operationId) states[agentId] = this@ExternalAgentSessionController[agentId].copy(configuration = null, connectionError = error.message ?: "Could not connect to ${spec.displayName}")
            } finally {
                if (lifecycleId == operationId) { lifecycleBusyAgentId = null; lifecycleId = null; lifecycleJob = null }
            }
        }
        lifecycleJob?.start()
    }

    fun reviewWorkspaceChanges(host: WorkspaceAgentChatBackend, spec: WorkspaceAgentPluginSpec) {
        if (busy || !admissionOpen() || pendingFinish != null || !scope.isActive) return
        val operationId = runIds.getAndIncrement()
        lifecycleId = operationId
        lifecycleBusyAgentId = spec.id
        states[spec.id] = this[spec.id].copy(connectionError = null)
        lifecycleJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = foreground("Review CLI workspace changes") {
                    requireAdmissionOpen()
                    host.reviewWorkspaceChanges(spec.id)
                }
                currentCoroutineContext().ensureActive()
                if (lifecycleId != operationId) return@launch
                val current = this@ExternalAgentSessionController[spec.id]
                val feedback = workspaceReviewFeedback.messages(
                    key = "${spec.id}:${current.sessionId}", changed = result.changedFiles,
                    pending = result.pendingFiles, state = host.workspaceSyncState?.value, manual = true,
                )
                // Transient review feedback, not a model turn or a fabricated history completion. Current
                // persisted workspace state remains available through Files after reopening.
                states[spec.id] = current.copy(messages = current.messages + feedback.map { AgentMessage("assistant", it) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (lifecycleId == operationId) states[spec.id] = this@ExternalAgentSessionController[spec.id]
                    .copy(connectionError = "Workspace review failed: ${failure.message ?: "files retained"}")
            } finally {
                if (lifecycleId == operationId) { lifecycleBusyAgentId = null; lifecycleId = null; lifecycleJob = null }
            }
        }
        lifecycleJob?.start()
    }

    fun newSession(host: WorkspaceAgentChatBackend, spec: WorkspaceAgentPluginSpec) {
        if (!admissionOpen() || retryPendingFinish()) return
        if (busy) return
        if (pendingInputsFor(spec.id).isNotEmpty()) {
            states[spec.id] = this[spec.id].copy(connectionError = "Remove pending inputs before starting a new CLI session.")
            return
        }
        lifecycle(spec) {
            discardDeleted(host, spec.id)
            requireAdmissionOpen()
            val sessionId = host.newSession(spec.id)
            requireAdmissionOpen()
            val config = host.prepareSession(spec.id, sessionId)
            unverifiedInitialHistoryReads -= spec.id
            states[spec.id] = ExternalAgentConversationState(sessionId = config.sessionId, configuration = config.value)
            restoredAgents += spec.id
            config
        }
    }

    fun cancel(host: WorkspaceAgentChatBackend?) {
        if (exiting || closed) return
        if (stoppingAgentId != null) return
        val run = activeRun
        val agentId = run?.agentId ?: lifecycleBusyAgentId ?: historyLoadingAgentId ?: return
        val job = if (run != null) runJob else if (lifecycleBusyAgentId != null) lifecycleJob else historyJob
        // Stop may win before the lazy history job reaches its cancellation catch.
        if (run == null && lifecycleBusyAgentId == null) recordUnverifiedInitialHistory(agentId)
        stoppingAgentId = agentId
        states[agentId] = this[agentId].let { it.copy(configuration = null, progress = it.progress.finish(AgentActivityKind.STOPPED)) }
        if (run == null) lifecycleId = null
        // Cancelling the owned operation also reaches a bootstrap that has not published a session id
        // yet, including a job not yet dispatched.
        job?.cancel()
        stopJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (host != null && run?.sessionId != null) {
                    withTimeoutOrNull(3_000) { host.cancel(run.agentId, run.sessionId) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Stop still cancels the exact UI-owned operation. */ }
            finally {
                try { job?.join() } finally {
                    states[agentId] = this@ExternalAgentSessionController[agentId].let { it.copy(configuration = null, liveText = "", liveThought = "", activity = listOf("Stopped."), progress = it.progress.finish(AgentActivityKind.STOPPED)) }
                    if (run == null) { lifecycleBusyAgentId = null; lifecycleJob = null; historyLoadingAgentId = null; historyJob = null }
                    else if (activeRun?.runId == run.runId) { activeRun = null; runJob = null }
                    stoppingAgentId = null
                }
            }
        }
    }

    private fun applyLifecycleEvent(operationId: Long, agentId: String, event: WorkspaceAgentEvent) {
        if (closed || lifecycleId != operationId || lifecycleBusyAgentId != agentId) return
        val current = this[agentId]
        when (event) {
            is WorkspaceAgentEvent.Status -> states[agentId] = current.copy(activity = (current.activity + event.message).takeLast(6))
            is WorkspaceAgentEvent.SessionBound -> states[agentId] = current.copy(sessionId = event.sessionId,
                configuration = current.configuration.takeIf { current.sessionId == event.sessionId },
                usage = if (current.sessionId == event.sessionId) current.usage else null)
            is WorkspaceAgentEvent.Configuration -> if (stoppingAgentId != agentId) {
                states[agentId] = current.copy(sessionId = event.sessionId, configuration = event.value,
                    usage = if (current.sessionId == event.sessionId) current.usage else null)
            }
            is WorkspaceAgentEvent.Usage -> states[agentId] = current.copy(usage = event.snapshot)
            else -> Unit
        }
    }

    private fun applyEvent(runId: Long, agentId: String, event: WorkspaceAgentEvent) {
        if (closed) return
        val run = activeRun?.takeIf { it.runId == runId && it.agentId == agentId } ?: return
        val update = transcript.accept(event)
        if (update != null && history != null && checkpointRunId == runId) {
            pendingOutput.addLast(update); pendingOutputChars += update.retainedChars
            checkpointSoon(runId, agentId)
            if (pendingOutputChars > 2_000_000) {
                states[agentId] = this[agentId].copy(persistenceError = "Output arrived faster than it could be saved. The turn was stopped to protect device memory; saved history was preserved.")
                runJob?.cancel()
            }
        }
        val previous = this[agentId]
        val current = previous.copy(progress = previous.progress.accept(event),
            streamParts = if (event is WorkspaceAgentEvent.Tool || event is WorkspaceAgentEvent.Plan) transcript.liveParts else previous.streamParts)
        if (current != previous) states[agentId] = current
        when (event) {
            is WorkspaceAgentEvent.SessionBound -> {
                activeRun = run.copy(sessionId = event.sessionId)
                val bound = current.copy(sessionId = event.sessionId,
                    configuration = current.configuration.takeIf { current.sessionId == event.sessionId && stoppingAgentId != agentId },
                    usage = if (current.sessionId == event.sessionId) current.usage else null)
                states[agentId] = bound
                if (history != null) { pendingBinding = event.sessionId to bound.configuration?.currentModelId; checkpointSoon(runId, agentId) }
                if (stoppingAgentId != agentId) {
                    bindPending(runId, agentId)
                    activeBackend?.let { requestSteerIfPending(it, runId, agentId) }
                }
            }
            is WorkspaceAgentEvent.Text -> {
                appendLivePreview(runId, agentId, event.text, thought = false)
            }
            is WorkspaceAgentEvent.Plan -> Unit
            is WorkspaceAgentEvent.Status -> states[agentId] = current.copy(activity = (current.activity + event.message).takeLast(6))
            is WorkspaceAgentEvent.Tool -> states[agentId] = current.copy(activity = (current.activity + event.title).takeLast(6))
            is WorkspaceAgentEvent.Usage -> states[agentId] = current.copy(usage = event.snapshot)
            is WorkspaceAgentEvent.Configuration -> if (event.sessionId == current.sessionId && stoppingAgentId != agentId) {
                states[agentId] = current.copy(configuration = event.value)
                bindPending(runId, agentId)
                if (history != null) { pendingBinding = event.sessionId to event.value.currentModelId; checkpointSoon(runId, agentId) }
            }
            is WorkspaceAgentEvent.Thought -> appendLivePreview(runId, agentId, event.text, thought = true)
            is WorkspaceAgentEvent.Media -> if (current.liveMedia.size < 4 && current.liveMedia.none { it.id == event.ref.id }) {
                states[agentId] = current.copy(liveMedia = current.liveMedia + event.ref)
            }
        }
    }

    private fun appendLivePreview(runId: Long, agentId: String, text: String, thought: Boolean) {
        if (text.isEmpty() || livePreviewRunId != runId) return
        val buffer = if (thought) liveThoughtPreview else liveAnswerPreview
        val limit = if (thought) 16_000 else 256_000
        val wasEmpty = buffer.isEmpty()
        appendBoundedTextTail(buffer, text, limit)

        if (wasEmpty) publishLivePreview(runId, agentId)
        if (livePreviewJob == null) {
            lateinit var preview: Job
            preview = scope.launch(start = CoroutineStart.LAZY) {
                try { delay(32); publishLivePreview(runId, agentId) }
                finally { if (livePreviewJob === preview) livePreviewJob = null }
            }
            livePreviewJob = preview
            preview.start()
        }
    }

    private fun publishLivePreview(runId: Long, agentId: String) {
        if (closed || livePreviewRunId != runId || activeRun?.runId != runId) return
        val current = this[agentId]
        states[agentId] = current.copy(liveText = liveAnswerPreview.toString(), liveThought = liveThoughtPreview.toString(), streamParts = transcript.liveParts)
    }

    private fun flushLivePreview(runId: Long, agentId: String) {
        livePreviewJob?.cancel(); livePreviewJob = null
        publishLivePreview(runId, agentId)
    }

    private fun enqueue(host: WorkspaceAgentChatBackend, spec: WorkspaceAgentPluginSpec, prompt: String,
                        mode: WorkspaceAgentMode, delivery: AgentPromptDelivery, media: List<ChatMediaRef>, mediaOwnerId: String?): Boolean {
        if (!canAcceptWhileRunning(spec.id)) return false
        val run = activeRun ?: return false
        val state = this[spec.id]
        if (pendingPrompts.size >= 16 || pendingPrompts.sumOf { it.input.text.length }.toLong() + prompt.length > 512_000) {
            states[spec.id] = state.copy(connectionError = "The pending prompt queue is full. Remove an input or wait; this draft was not sent.")
            return false
        }
        val input = AgentPendingInput(id = "plugin-input-${runIds.getAndIncrement()}", text = prompt,
            delivery = delivery, providerId = spec.id, modelId = state.configuration?.currentModelId.orEmpty(), media = media)
        pendingPrompts = pendingPrompts + ExternalAgentPendingPrompt(input, spec.id, run.runId, state.sessionId,
            state.historyId, mode, state.configuration?.currentModelId, state.configuration?.currentReasoningId, state.configuration != null, media, mediaOwnerId)
        if (delivery == AgentPromptDelivery.STEER) requestSteerIfPending(host, run.runId, spec.id)
        return true
    }

    private fun bindPending(runId: Long, agentId: String) {
        val state = this[agentId]
        pendingPrompts = pendingPrompts.map { pending ->
            if (pending.agentId != agentId || pending.originRunId != runId) pending else pending.copy(
                sessionId = pending.sessionId ?: state.sessionId,
                historyId = pending.historyId ?: state.historyId,
                modelId = if (pending.configurationKnown) pending.modelId else state.configuration?.currentModelId,
                reasoningId = if (pending.configurationKnown) pending.reasoningId else state.configuration?.currentReasoningId,
                configurationKnown = pending.configurationKnown || state.configuration != null)
        }
    }

    private fun requestSteerIfPending(host: WorkspaceAgentChatBackend, runId: Long, agentId: String) {
        if (!admissionOpen()) return
        val run = activeRun?.takeIf { it.runId == runId && it.agentId == agentId } ?: return
        val sessionId = run.sessionId ?: return
        if (steerRequestJob?.isActive == true || pendingPrompts.none { it.agentId == agentId && it.input.delivery == AgentPromptDelivery.STEER }) return
        steerRequestJob = scope.launch {
            try {
                if (activeRun?.runId != runId || pendingPrompts.none { it.agentId == agentId && it.input.delivery == AgentPromptDelivery.STEER }) return@launch
                requireAdmissionOpen()
                val accepted = host.requestSteer(agentId, sessionId)
                if (!accepted && activeRun?.runId == runId) {
                    val state = this@ExternalAgentSessionController[agentId]
                    states[agentId] = state.copy(activity = (state.activity + "Steer pending until the next CLI turn boundary.").takeLast(6))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (activeRun?.runId == runId) {
                    val state = this@ExternalAgentSessionController[agentId]
                    states[agentId] = state.copy(activity = (state.activity + "Steer remains pending; the current task is preserved.").takeLast(6))
                }
            }
        }
    }

    private fun promoteNext(host: WorkspaceAgentChatBackend, spec: WorkspaceAgentPluginSpec) {
        if (!admissionOpen() || pendingFinish != null) return
        val candidates = pendingPrompts.filter { it.agentId == spec.id }
        val next = candidates.firstOrNull { it.input.delivery == AgentPromptDelivery.STEER } ?: candidates.firstOrNull() ?: return
        val state = this[spec.id]
        if (state.persistenceError != null || next.sessionId == null || next.sessionId != state.sessionId || next.historyId != state.historyId ||
            next.configurationKnown && (next.modelId != state.configuration?.currentModelId || next.reasoningId != state.configuration?.currentReasoningId)) {
            states[spec.id] = state.copy(connectionError = "Pending inputs are paused because CLI session, model, Reasoning, or history state changed. Review or remove them before continuing.")
            return
        }
        pendingPrompts = pendingPrompts.filterNot { it.input.id == next.input.id }
        if (!submit(host, spec, next.input.text, next.mode, media = next.media, mediaOwnerId = next.mediaOwnerId)) pendingPrompts = listOf(next) + pendingPrompts
    }
}

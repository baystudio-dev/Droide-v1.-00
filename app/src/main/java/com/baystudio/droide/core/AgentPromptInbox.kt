package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.util.UUID

// Durable busy-session prompt admission inbox.


class AgentPromptInbox(private val sessions: SessionManager) {
    data class Promoted(val input: AgentPendingInput, val runtimeConfig: AgentConfig?, val requestAdmittedAtNanos: Long? = null)
    private data class RuntimeInput(val config: AgentConfig, val requestAdmittedAtNanos: Long?)

    sealed interface SubmissionDecision {
        data class StartRun(val lease: Long) : SubmissionDecision
        data class WaitForStartup(val lease: Long) : SubmissionDecision
        data class Admitted(val input: AgentPendingInput) : SubmissionDecision
    }

    private val mutex = Mutex()
    private val ownershipLock = Any()
    private var ownershipEpoch = 0L
    private var boundSessionId: String? = null
    private val drain = AgentPromptDrainState()
    // Process-local evidence only: restored inputs lose recovery-permit authority.
    private val runtimeInputs = mutableMapOf<String, RuntimeInput>()
    private val _inputs = MutableStateFlow<List<AgentPendingInput>>(emptyList())
    private val _drainState = MutableStateFlow(drain.snapshot)
    val inputs: StateFlow<List<AgentPendingInput>> = _inputs.asStateFlow()
    internal val drainState: StateFlow<AgentPromptDrainSnapshot> = _drainState.asStateFlow()

    // Lock order: inbox, then session write transaction. Never acquire this from a
    // session transaction or hold a JVM monitor while this suspending block runs.
    internal suspend fun <T> withPendingSnapshot(block: suspend (List<AgentPendingInput>) -> T): T = mutex.withLock {
        block(_inputs.value)
    }

    suspend fun routeSubmission(
        sessionId: String?,
        text: String,
        delivery: AgentPromptDelivery,
        config: AgentConfig,
        origin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        images: List<AgentImageRef> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
        media: List<ChatMediaRef> = emptyList(),
        sessionIsCurrent: () -> Boolean = { true },
        requestAdmittedAtNanos: Long? = null,
        sourceDelivery: SubagentDeliveryReceipt? = null,
    ): SubmissionDecision {
        val epoch = synchronized(ownershipLock) { ownershipEpoch }
        return mutex.withLock {
        SubagentDeliveryHistory.boundReceipt(origin, sourceId, sessionId, sourceDelivery)
        when (val route = synchronized(ownershipLock) {
            if (epoch != ownershipEpoch || !sessionIsCurrent()) throw CancellationException("The conversation changed; the draft was kept")
            drain.routeSubmission()
        }) {
            is AgentPromptDrainRoute.Start -> {
                publishDrainState()
                SubmissionDecision.StartRun(route.lease)
            }
            is AgentPromptDrainRoute.WaitForStartup -> SubmissionDecision.WaitForStartup(route.lease)
            is AgentPromptDrainRoute.Admit -> {
                check(sessionId != null) { "Agent session became ready without a session id" }
                SubmissionDecision.Admitted(admitLocked(sessionId, text, delivery, config, origin, sourceId, images, ideContext, media, epoch, sessionIsCurrent, requestAdmittedAtNanos, sourceDelivery))
            }
        }
        }
    }

    suspend fun awaitStartupResolution(lease: Long) {
        drainState.filter { state ->
            state.lease != lease || state.phase != AgentPromptDrainPhase.STARTING
        }.first()
    }

    // Never starts a new run.
    suspend fun admitSteerIfReady(
        sessionId: String?,
        text: String,
        config: AgentConfig,
        origin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        images: List<AgentImageRef> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
        sessionIsCurrent: () -> Boolean = { true },
        requestAdmittedAtNanos: Long? = null,
        sourceDelivery: SubagentDeliveryReceipt? = null,
    ): AgentPendingInput? {
        val epoch = synchronized(ownershipLock) { ownershipEpoch }
        return mutex.withLock {
        if (sessionId == null || synchronized(ownershipLock) { drain.snapshot.phase != AgentPromptDrainPhase.READY }) return@withLock null
        admitLocked(sessionId, text, AgentPromptDelivery.STEER, config, origin, sourceId, images, ideContext,
            expectedEpoch = epoch, sessionIsCurrent = sessionIsCurrent, requestAdmittedAtNanos = requestAdmittedAtNanos, sourceDelivery = sourceDelivery)
        }
    }

    suspend fun reserveDirectRun(): Long = mutex.withLock {
        val lease = synchronized(ownershipLock) { drain.reserveDirectRun() }
        publishDrainState()
        lease
    }

    suspend fun markReady(lease: Long): Boolean = mutex.withLock {
        val changed = synchronized(ownershipLock) { drain.markReady(lease) }
        if (changed) publishDrainState()
        changed
    }

    suspend fun markIdle(lease: Long): Boolean = mutex.withLock {
        val changed = synchronized(ownershipLock) { drain.markIdle(lease) }
        if (changed) publishDrainState()
        changed
    }

    suspend fun takeSteers(runLease: Long, activeConfig: AgentConfig): List<Promoted> = mutex.withLock {
        synchronized(ownershipLock) {
        if (!drain.isReady(runLease)) return@withLock emptyList()
        val selected = _inputs.value.filter {
            it.delivery == AgentPromptDelivery.STEER && canPromote(it, activeConfig)
        }
        if (selected.isEmpty()) return@withLock emptyList()
        removeSelected(selected)
        }
    }


    suspend fun takeForIdle(runLease: Long, activeConfig: AgentConfig): List<Promoted> = mutex.withLock {
        synchronized(ownershipLock) {
        if (!drain.isReady(runLease)) return@withLock emptyList()
        val steers = _inputs.value.filter {
            it.delivery == AgentPromptDelivery.STEER && canPromote(it, activeConfig)
        }
        val nextQueue = _inputs.value.firstOrNull { it.delivery == AgentPromptDelivery.QUEUE }
        val selected = if (steers.isNotEmpty()) steers else
            listOfNotNull(nextQueue?.takeIf { canPromote(it, activeConfig) })
        if (selected.isEmpty()) {
            if (drain.markIdle(runLease)) publishDrainState()
            return@withLock emptyList()
        }
        removeSelected(selected)
        }
    }

    /**
     * Keep accepted prompts recoverable while local attachment/history preparation suspends.
     * The final commit callback must acknowledge without suspending; no provider request belongs
     * in this transaction. Clear/load may replace ownership while preparation is in flight.
     */
    internal suspend fun <T> promoteAtBoundary(
        runLease: Long,
        allowQueue: Boolean,
        activeConfig: AgentConfig,
        prepare: suspend (List<Promoted>) -> T,
        commit: (T, acknowledge: () -> Unit) -> Unit,
    ): T? = mutex.withLock {
        val (epoch, sessionId, selected) = synchronized(ownershipLock) {
            if (!drain.isReady(runLease)) return@withLock null
            val steers = _inputs.value.filter {
                it.delivery == AgentPromptDelivery.STEER && canPromote(it, activeConfig)
            }
            val nextQueue = if (allowQueue && steers.isEmpty()) _inputs.value
                .firstOrNull { it.delivery == AgentPromptDelivery.QUEUE }?.takeIf { canPromote(it, activeConfig) } else null
            val inputs = if (steers.isNotEmpty()) steers else listOfNotNull(nextQueue)
            if (inputs.isEmpty()) {
                if (allowQueue && drain.markIdle(runLease)) publishDrainState()
                return@withLock null
            }
            Triple(ownershipEpoch, boundSessionId, inputs.map { input -> runtimeInputs[input.id].let { Promoted(input, it?.config, it?.requestAdmittedAtNanos) } })
        }
        val prepared = prepare(selected)
        var acknowledged = false
        commit(prepared) {
            synchronized(ownershipLock) {
                if (ownershipEpoch != epoch || boundSessionId != sessionId || !drain.isReady(runLease))
                    throw CancellationException("The conversation changed before queued prompt delivery")
                check(!acknowledged) { "Prompt promotion was already acknowledged" }
                removeSelected(selected.map { it.input })
                acknowledged = true
            }
        }
        check(acknowledged) { "Prompt promotion must acknowledge its prepared inputs" }
        prepared
    }

    suspend fun remove(sessionId: String, id: String, sessionIsCurrent: () -> Boolean = { true }): Boolean = mutex.withLock {
        val epoch = synchronized(ownershipLock) { bindOwner(sessionId, ownershipEpoch, sessionIsCurrent) }
        val before = _inputs.value
        if (before.none { it.id == id }) return@withLock false
        val next = before.filterNot { it.id == id }
        // Persist enough state to recover safely after process death.

        sessions.savePendingInputs(sessionId, next, commitAdmission = { publish -> synchronized(ownershipLock) {
            requireOwner(sessionId, epoch, sessionIsCurrent)
            publish()
            // Primary JSON is committed. Publish ownership state before returning across
            // the cancellable IO boundary so a later checkpoint cannot revive this input.
            runtimeInputs.remove(id)
            _inputs.value = next
        } })
        synchronized(ownershipLock) {
            requireOwner(sessionId, epoch, sessionIsCurrent)
        }
        true
    }

    fun load(inputs: List<AgentPendingInput>, sessionId: String? = null) = synchronized(ownershipLock) {
        ownershipEpoch++
        boundSessionId = sessionId
        runtimeInputs.clear()
        _inputs.value = inputs.take(MAX_PENDING_INPUTS)
        drain.reset()
        publishDrainState()
    }

    fun clear() = synchronized(ownershipLock) {
        ownershipEpoch++
        boundSessionId = null
        runtimeInputs.clear()
        _inputs.value = emptyList()
        drain.reset()
        publishDrainState()
    }

    internal fun requireStartRun(lease: Long) = synchronized(ownershipLock) {
        if (drain.snapshot.lease != lease || drain.snapshot.phase != AgentPromptDrainPhase.STARTING)
            throw CancellationException("The conversation changed before this Agent run started; the draft was kept")
    }

    private suspend fun admitLocked(
        sessionId: String,
        text: String,
        delivery: AgentPromptDelivery,
        config: AgentConfig,
        origin: AgentInputOrigin,
        sourceId: String?,
        images: List<AgentImageRef>,
        ideContext: AgentIdeContextSnapshot?,
        media: List<ChatMediaRef> = emptyList(),
        expectedEpoch: Long,
        sessionIsCurrent: () -> Boolean,
        requestAdmittedAtNanos: Long? = null,
        sourceDelivery: SubagentDeliveryReceipt? = null,
    ): AgentPendingInput {
        val epoch = synchronized(ownershipLock) { bindOwner(sessionId, expectedEpoch, sessionIsCurrent) }
        val receipt = SubagentDeliveryHistory.boundReceipt(origin, sourceId, sessionId, sourceDelivery)
        require(text.isNotBlank()) { "Prompt is empty" }
        require(text.length <= AgentService.MAX_USER_MESSAGE_CHARS) {
            "Message exceeds ${AgentService.MAX_USER_MESSAGE_CHARS} characters"
        }
        require(_inputs.value.size < MAX_PENDING_INPUTS) { "Pending prompt inbox is full" }
        val modelId = ProviderModelIdentity.requireCanonical(config.model)
        ProviderRuntimeGuard.requireCertifiedSelection(config.providerId, config.baseUrl, modelId)
        val admitted = AgentPendingInput(
            id = "in-${UUID.randomUUID()}",
            text = text,
            delivery = delivery,
            providerId = config.providerId,
            modelId = modelId,
            reasoningEffort = config.reasoningEffort,
            origin = origin,
            sourceId = sourceId,
            sourceDelivery = receipt,
            images = images,
            ideContext = ideContext,
            media = media,
        )
        val next = _inputs.value + admitted
        sessions.savePendingInputs(sessionId, next, commitAdmission = { publish -> synchronized(ownershipLock) {
            requireOwner(sessionId, epoch, sessionIsCurrent)
            publish()
            // Publication is durable even if cancellation wins the dispatcher handoff.
            // Keep the runtime configuration and visible inbox at that same commit point.
            runtimeInputs[admitted.id] = RuntimeInput(config, requestAdmittedAtNanos.takeIf { origin == AgentInputOrigin.USER })
            _inputs.value = next
        } })
        synchronized(ownershipLock) {
            requireOwner(sessionId, epoch, sessionIsCurrent)
        }
        return admitted
    }

    private fun bindOwner(sessionId: String, epoch: Long, sessionIsCurrent: () -> Boolean): Long {
        if (ownershipEpoch != epoch || boundSessionId != null && boundSessionId != sessionId || !sessionIsCurrent())
            throw CancellationException("The conversation changed before prompt admission; the draft was kept")
        boundSessionId = sessionId
        return epoch
    }

    private fun requireOwner(sessionId: String, epoch: Long, sessionIsCurrent: () -> Boolean) {
        if (ownershipEpoch != epoch || boundSessionId != sessionId || !sessionIsCurrent())
            throw CancellationException("The conversation changed during prompt admission; the draft was kept")
    }

    private fun removeSelected(selected: List<AgentPendingInput>): List<Promoted> {
        val ids = selected.mapTo(hashSetOf()) { it.id }
        _inputs.value = _inputs.value.filterNot { it.id in ids }
        return selected.map { input -> runtimeInputs.remove(input.id).let { Promoted(input, it?.config, it?.requestAdmittedAtNanos) } }
    }

    private fun canPromote(input: AgentPendingInput, activeConfig: AgentConfig): Boolean {
        if (runtimeInputs[input.id] != null) return true
        return input.providerId == activeConfig.providerId &&
            input.modelId == activeConfig.model &&
            input.reasoningEffort == activeConfig.reasoningEffort
    }

    private fun publishDrainState() {
        _drainState.value = drain.snapshot
    }

    companion object {
        const val MAX_PENDING_INPUTS = 32
    }
}

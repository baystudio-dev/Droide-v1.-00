package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class AgentPromptCoordinator(
    sessions: SessionManager,
    private val maxUserMessageChars: Int,
    private val maxAgentTurns: Int,
    private val admissionClockNanos: () -> Long = System::nanoTime,
) {
    private val inbox = AgentPromptInbox(sessions)

    data class PromotionBatch(
        val nextConfig: AgentConfig,
        val userMessages: List<AgentMessage>,
        val apiEntries: List<JsonObject>,
        val streamParts: List<AgentStreamPart>,
        val deliveredSourceReceipts: List<SubagentDeliveryReceipt>,
        val fallbackTitle: String,
        val promotedInputs: List<AgentPendingInput> = emptyList(),
        val requestAdmittedAtNanos: Long? = null,
    )

    val pendingInputs = inbox.inputs
    // StateFlow read only: safe under Service admissionLock, with no inbox lock acquisition.
    internal val hasReservedRun: Boolean get() = inbox.drainState.value.phase != AgentPromptDrainPhase.IDLE

    internal suspend fun <T> withPendingSnapshot(block: suspend (List<AgentPendingInput>) -> T): T =
        inbox.withPendingSnapshot(block)

    fun load(inputs: List<AgentPendingInput>, sessionId: String? = null) = inbox.load(inputs, sessionId)
    fun clear() = inbox.clear()

    fun submit(
        scope: CoroutineScope,
        userText: String,
        config: AgentConfig,
        delivery: AgentPromptDelivery,
        maxTurns: Int,
        currentSessionId: () -> String?,
        startRun: (Long) -> Unit,
        origin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        requiredSessionId: String? = null,
        prepareImages: suspend () -> List<AgentImageRef> = { emptyList() },
        ideContext: AgentIdeContextSnapshot? = null,
        reportError: (String?) -> Unit,
        onAccepted: suspend () -> Unit = {},
        media: List<ChatMediaRef> = emptyList(),
        // Capture before async attachment preparation, startup waits or inbox persistence. Explicit null
        // remains unknown; never replace it with the eventual run-start time.
        requestAdmittedAtNanos: Long? = if (origin == AgentInputOrigin.USER) admissionClockNanos() else null,
        sourceDelivery: SubagentDeliveryReceipt? = null,
    ): kotlinx.coroutines.Job? {
        val invalid = validate(userText, config, maxTurns)
        if (invalid != null) {
            reportError(invalid)
            return null
        }
        return scope.launch {
            try {
                val images = prepareImages()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val activeSessionId = currentSessionId()
                    if (requiredSessionId != null && activeSessionId != requiredSessionId) return@launch
                    when (val decision = inbox.routeSubmission(activeSessionId, userText, delivery, config, origin, sourceId, images, ideContext, media,
                        sessionIsCurrent = { currentSessionId() == activeSessionId }, requestAdmittedAtNanos = requestAdmittedAtNanos,
                        sourceDelivery = sourceDelivery)) {
                        is AgentPromptInbox.SubmissionDecision.StartRun -> {
                            reportError(null)
                            requireStartRun(decision.lease)
                            startRun(decision.lease)
                            onAccepted()
                            return@launch
                        }
                        is AgentPromptInbox.SubmissionDecision.Admitted -> {
                            reportError(null)
                            onAccepted()
                            return@launch
                        }
                        is AgentPromptInbox.SubmissionDecision.WaitForStartup -> {
                            inbox.awaitStartupResolution(decision.lease)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                reportError("Unable to admit prompt: ${t.message ?: "unknown error"}")
            }
        }
    }

    suspend fun admitSteerIfActive(sessionId: String?, userText: String, config: AgentConfig, ideContext: AgentIdeContextSnapshot? = null,
        sessionIsCurrent: () -> Boolean = { true },
        requestAdmittedAtNanos: Long? = admissionClockNanos(),
    ): Boolean {
        val invalid = validate(userText, config, maxAgentTurns)
        if (invalid != null) return false
        return inbox.admitSteerIfReady(sessionId, userText, config, ideContext = ideContext, sessionIsCurrent = sessionIsCurrent,
            requestAdmittedAtNanos = requestAdmittedAtNanos) != null
    }

    suspend fun reserveDirectRun(): Long = inbox.reserveDirectRun()
    internal fun requireStartRun(lease: Long) = inbox.requireStartRun(lease)
    suspend fun markRunReady(lease: Long): Boolean = inbox.markReady(lease)
    suspend fun markRunInactive(lease: Long): Boolean = inbox.markIdle(lease)
    suspend fun remove(sessionId: String, id: String, sessionIsCurrent: () -> Boolean = { true }): Boolean = inbox.remove(sessionId, id, sessionIsCurrent)

    suspend fun promoteAtBoundary(
        runLease: Long,
        allowQueue: Boolean,
        activeConfig: AgentConfig,
        step: Int,
        maxApiMessageChars: Int,
        prepare: suspend (PromotionBatch) -> Unit = {},
        commit: (PromotionBatch, acknowledge: () -> Unit) -> Unit = { _, acknowledge -> acknowledge() },
        parentSessionId: String? = null,
    ): PromotionBatch? = inbox.promoteAtBoundary(runLease, allowQueue, activeConfig, prepare = { promoted ->
        var nextConfig = activeConfig
        promoted.forEach { it.runtimeConfig?.let { cfg -> nextConfig = cfg } }
        val receipts = promoted.map { item -> SubagentDeliveryHistory.boundReceipt(
            item.input.origin, item.input.sourceId, parentSessionId, item.input.sourceDelivery,
        ) }
        PromotionBatch(
            nextConfig = nextConfig,
            userMessages = promoted.filter { it.input.origin == AgentInputOrigin.USER }
                .map { AgentMessage("user", it.input.text, media = it.input.media) },
            apiEntries = promoted.mapIndexed { index, item ->
                if (item.input.origin == AgentInputOrigin.USER) {
                    VisionSupport.userMessage(AgentIdeContextProjection.forModel(AgentMediaPromptReceipt.forModel(item.input.text, item.input.media, item.input.images), item.input.ideContext, maxApiMessageChars), item.input.images, maxApiMessageChars)
                } else SubagentDeliveryHistory.entry(
                    "[Background subagent completion; synthetic system event, not user-authored]\n${item.input.text}".take(maxApiMessageChars), receipts[index],
                )
            },
            streamParts = promoted.map { item ->
                val synthetic = item.input.origin == AgentInputOrigin.SUBAGENT
                AgentStreamPart(
                    id = "input:${item.input.id}",
                    step = step,
                    kind = if (synthetic) AgentStreamPartKind.STATUS else AgentStreamPartKind.INPUT,
                    state = AgentStreamPartState.COMPLETED,
                    title = if (synthetic) "Subagent result" else if (item.input.delivery == AgentPromptDelivery.STEER) "Steer" else "Queued prompt",
                    detail = if (synthetic) "Delivered from ${item.input.sourceId ?: "background task"}" else if (item.input.delivery == AgentPromptDelivery.STEER)
                        "Promoted at safe provider-turn boundary" else "Promoted from FIFO queue",
                    text = item.input.text,
                    endedAtMs = System.currentTimeMillis(),
                )
            },
            deliveredSourceReceipts = receipts.filterNotNull().distinct(),
            fallbackTitle = promoted.first().input.text.take(40),
            promotedInputs = promoted.map { it.input },
            // A mixed/restored batch cannot acquire new authority merely by being promoted.
            requestAdmittedAtNanos = if (promoted.any { it.input.origin != AgentInputOrigin.USER || it.requestAdmittedAtNanos == null }) null
                else promoted.minOf { requireNotNull(it.requestAdmittedAtNanos) },
        ).also { prepare(it) }
    }, commit = commit)

    private fun validate(userText: String, config: AgentConfig, maxTurns: Int): String? = when {
        userText.isBlank() -> "Prompt is empty"
        ProviderRegistry.findById(config.providerId) == null -> "Unknown AI provider"
        ProviderRegistry.findById(config.providerId)?.needsKey == true && config.apiKey.isBlank() &&
            ProviderRegistry.findById(config.providerId)?.let { !ProviderAuthMethods.canResolveWithoutStoredSecret(it) } == true ->
            "Connect an AI provider and enter its API key first"
        config.model.isBlank() -> "Select a validated model first"
        !ProviderModelIdentity.isCanonical(config.model) -> "Model ID is invalid or non-canonical; refresh the provider and reselect it"
        !ProviderModelCertificationRegistry.isCertified(config.providerId, config.model) ->
            "Refresh the provider and select a live-validated model first"
        userText.length > maxUserMessageChars -> "Message exceeds $maxUserMessageChars characters"
        maxTurns !in 1..maxAgentTurns -> "maxTurns must be between 1 and $maxAgentTurns"
        else -> null
    }
}

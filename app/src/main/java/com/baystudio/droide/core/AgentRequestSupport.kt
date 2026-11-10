package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AgentRequestSupport {
    /** Shared with preflight/compaction so High reasoning does not reserve a smaller window there. */
    fun outputTokens(config: AgentConfig): Int = ProviderRequestBudget.outputTokens(
        buildJsonObject { ReasoningSupport.apply(this, config.providerId, config.model, config.reasoningEffort) },
        config.providerId, config.model,
    )

    fun build(
        config: AgentConfig,
        systemText: String,
        history: List<JsonObject>,
        tools: JsonArray?,
    ): JsonObject {
        val modelId = ProviderModelIdentity.requireCanonical(config.model)
        val hasImages = history.any { (it["content"] as? JsonArray)?.any { part ->
            (part as? JsonObject)?.get("type") == kotlinx.serialization.json.JsonPrimitive("image_url")
        } == true }
        require(!hasImages || VisionSupport.providerAcceptsImageUrl(config)) {
            "This conversation contains images; select an image-capable model or start a text-only session"
        }
        require(tools.isNullOrEmpty() || ModelCapabilityRegistry.allowsToolUse(config.providerId, modelId)) {
            "The selected model does not support the required Agent tools; select a tool-capable model"
        }
        val enabledTools = tools
        val request = buildJsonObject {
        put("model", modelId)
        ReasoningSupport.apply(this, config.providerId, modelId, config.reasoningEffort)
        val system = PromptCacheSupport.systemMessage(config, systemText, enabledTools,
            availableBreakpoints = PromptCacheSupport.availableBreakpoints(history, enabledTools))
        put("messages", JsonArray(listOf(system) + history))
        if (enabledTools != null) {
            put("tools", enabledTools)
            put("tool_choice", "auto")
        }
        }
        return ProviderRequestBudget.withOutputLimit(request, config.providerId, modelId)
    }

    suspend fun compact(
        sessions: SessionManager,
        tokens: TokenTracker,
        todos: TodoManager,
        mode: AgentMode,
        pendingInputs: List<AgentPendingInput>,
        messages: List<AgentMessage>,
        apiHistory: MutableList<JsonObject>,
        exactForkHistory: List<JsonObject>?,
        config: AgentConfig,
        sid: String,
        overflowRecovery: Boolean,
        ensureCurrent: () -> Unit,
        commitAdmission: ((() -> Unit) -> Unit) = { publish -> publish() },
        continuationBudget: AgentCompactionBudget? = null,
    ): Boolean {
        ensureCurrent()
        val captured = sessions.snapshotForUpdate(sid) ?: error("The target conversation was removed; compaction was not started")
        val existing = captured.session
        val complete = AgentHistory.requireCompleteGroups(apiHistory).flatten()
        val forkMessages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (exactForkHistory != null) SessionForkHistory.annotate(messages, exactForkHistory)
            else messages.map { it.copy(forkHistoryEnd = null) }
        }
        val snapshot = existing.copy(
            messages = forkMessages,
            apiHistory = complete,
            forkHistory = exactForkHistory ?: emptyList(),
            forkHistoryVersion = if (exactForkHistory != null) SessionForkHistory.SCHEMA_VERSION else 0,
            todos = todos.get(sid),
            mode = mode,
            pendingInputs = pendingInputs,
        )
        val keepTokens = if (overflowRecovery) {
            ContextBudget.overflowRecoveryKeepTokens(config.providerId, config.model, outputTokens(config))
        } else ContextBudget.recentKeepTokens(config.providerId, config.model, outputTokens(config))
        val budget = continuationBudget?.let { it.copy(keepRecentTokens = minOf(keepTokens, it.keepRecentTokens)) }
            ?: AgentCompactionBudget.forModel(config, keepTokens)
        val result = sessions.compactWithinBudget(snapshot, config, budget,
            expectedRevision = captured.revision, commitAdmission = commitAdmission,
            onUsage = { tokens.addSample(config.model, it) })
        ensureCurrent()
        if (!result.changed) return false
        // The session-file commit and this later live-list replacement need separate admissions:
        // shutdown/load may revoke ownership after the provider returns.
        commitAdmission {
            apiHistory.clear()
            apiHistory.addAll(result.session.apiHistory)
        }
        return true
    }
}

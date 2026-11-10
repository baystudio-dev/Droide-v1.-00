package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject

object AgentManualCompaction {
    suspend fun run(
        sessionId: String?, sessions: SessionManager, messages: List<AgentMessage>, apiHistory: List<JsonObject>,
        exactFork: List<JsonObject>?, todos: List<Todo>, mode: AgentMode, pendingInputs: List<AgentPendingInput>,
        config: AgentConfig, tokens: TokenTracker, hooks: AgentHookLifecycleBridge, load: suspend (DroideSession) -> Unit,
        commitAdmission: ((() -> Unit) -> Unit) = { publish -> publish() },
        hookOwner: HookInvocationAuthority? = null,
    ): String {
        val sid = sessionId ?: return "No active session."
        val captured = sessions.snapshotForUpdate(sid) ?: return "Session not found."
        val current = captured.session
        var result: CompactionResult? = null
        val hook = hooks.compact("manual", owner = hookOwner) {
            result = sessions.compact(
                current.copy(
                    messages = messages, apiHistory = apiHistory, forkHistory = exactFork ?: emptyList(),
                    forkHistoryVersion = if (exactFork != null) SessionForkHistory.SCHEMA_VERSION else 0,
                    todos = todos, mode = mode, pendingInputs = pendingInputs,
                ), config, expectedRevision = captured.revision, commitAdmission = commitAdmission,
                onUsage = { tokens.addSample(config.model, it) },
            )
            result!!.changed
        }
        if (hook.blockedReason.isNotBlank()) return "Compaction blocked by project hook: ${hook.blockedReason}"
        val compacted = result ?: return "Session is already compact enough."
        if (!compacted.changed) return "Session is already compact enough."
        load(compacted.session)
        return "Session compacted."
    }
}

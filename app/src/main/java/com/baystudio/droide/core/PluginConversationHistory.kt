package com.baystudio.droide.core

import java.util.UUID

enum class PluginHistoryStatus { RUNNING, COMPLETED, STOPPED, FAILED, INTERRUPTED }

data class PluginHistorySummary(
    val id: String,
    val agentId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val nativeSessionId: String?,
    val mode: WorkspaceAgentMode,
    val modelId: String?,
    val messageCount: Int,
    val partCount: Long,
    val status: PluginHistoryStatus,
)

data class PluginHistoryCursor(val updatedAt: Long, val id: String)
data class PluginHistoryPage(val entries: List<PluginHistorySummary>, val nextCursor: PluginHistoryCursor?)

/** A bounded presentation window; absolute part identities survive paging and new turns. */
data class PluginHistoryWindow(
    val summary: PluginHistorySummary,
    val messages: List<AgentMessage>,
    val messageKeys: List<Long>,
    val firstPart: Long,
    val endPart: Long,
    /** Non-null means inspection only: the summary's native identity is evidence, not resume authority. */
    val readOnlyReason: String? = null,
) {
    val hasEarlier: Boolean get() = firstPart > 0
    val hasLater: Boolean get() = endPart < summary.partCount
}

/** Workspace-owned local transcript storage. It never starts a CLI or sends a model prompt. */
interface PluginConversationHistory {
    suspend fun listPage(agentId: String? = null, query: String = "", before: PluginHistoryCursor? = null, limit: Int = 40): PluginHistoryPage
    suspend fun latest(agentId: String): PluginHistoryWindow?
    suspend fun load(id: String, beforePart: Long? = null): PluginHistoryWindow?
    suspend fun beginTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode, nativeSessionId: String?): PluginHistoryWindow
    suspend fun beginMediaTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode,
                               nativeSessionId: String?, media: List<ChatMediaRef>): PluginHistoryWindow {
        require(media.isEmpty()) { "This plugin history cannot persist chat media" }
        return beginTurn(id, agentId, prompt, mode, nativeSessionId)
    }
    suspend fun bind(id: String, nativeSessionId: String, modelId: String? = null)
    suspend fun appendPartial(id: String, fragments: List<String>)
    suspend fun appendTranscript(id: String, updates: List<ExternalTranscriptUpdate>) {
        require(updates.all { it.textDelta != null }) { "This plugin history cannot persist tool activity" }
        appendPartial(id, updates.map { checkNotNull(it.textDelta) })
    }
    suspend fun finish(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus): PluginHistoryWindow
    suspend fun finishMediaTurn(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus,
                                media: List<ChatMediaRef>): PluginHistoryWindow {
        require(media.isEmpty()) { "This plugin history cannot persist chat media" }
        return finish(id, reply, notices, status)
    }
    suspend fun delete(id: String)
    suspend fun close()

    companion object {
        fun newId(): String = "p-${UUID.randomUUID()}"
    }
}

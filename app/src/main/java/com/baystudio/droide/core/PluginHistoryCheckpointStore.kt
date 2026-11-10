package com.baystudio.droide.core

import java.security.MessageDigest

/** Identity minted and committed with admission, never reconstructed from displayed history. */
data class PluginHistoryTurnIdentity(val conversationId: String, val turnId: String)
data class PluginHistoryTurnAdmission(val window: PluginHistoryWindow, val identity: PluginHistoryTurnIdentity)
data class PluginHistoryFinishRequest(
    val identity: PluginHistoryTurnIdentity,
    val reply: String?,
    val notices: List<String>,
    val status: PluginHistoryStatus,
    val media: List<ChatMediaRef> = emptyList(),
)

interface PluginHistoryCheckpointStore {
    suspend fun beginCheckpointTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                    nativeSessionId: String?, media: List<ChatMediaRef> = emptyList()): PluginHistoryTurnAdmission
    suspend fun bindCheckpoint(identity: PluginHistoryTurnIdentity, nativeSessionId: String, modelId: String? = null)
    suspend fun appendCheckpointPartial(identity: PluginHistoryTurnIdentity, fragments: List<String>)
    suspend fun appendCheckpointTranscript(identity: PluginHistoryTurnIdentity, updates: List<ExternalTranscriptUpdate>)
    suspend fun finishCheckpoint(request: PluginHistoryFinishRequest): PluginHistoryWindow
}

/** Bounded working memory; exact UTF-16 code units, nullness, collection order and field lengths. */
internal fun pluginHistoryFinishFingerprint(request: PluginHistoryFinishRequest): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun number(value: Long) { for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte()) }
    fun text(value: String?) {
        if (value == null) { number(-1); return }
        number(value.length.toLong())
        for (unit in value) { digest.update((unit.code ushr 8).toByte()); digest.update(unit.code.toByte()) }
    }
    text("Droide plugin finish receipt v1")
    text(request.identity.conversationId); text(request.identity.turnId)
    text(request.reply); text(request.status.name)
    number(request.notices.size.toLong()); request.notices.forEach(::text)
    number(request.media.size.toLong())
    request.media.forEach { ref ->
        text(ref.id); text(ref.path); text(ref.name); text(ref.mediaType)
        number(ref.bytes); number(ref.createdAt); number(ref.expiresAt)
    }
    return "v1:" + digest.digest().joinToString("") { "%02x".format(it) }
}

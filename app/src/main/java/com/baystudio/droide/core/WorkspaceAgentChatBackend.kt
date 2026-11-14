package com.baystudio.droide.core

/** Session operations shared by the live host and the chat lifecycle controller. */
interface WorkspaceAgentChatBackend {
    val workspaceSyncState: kotlinx.coroutines.flow.StateFlow<WorkspaceAgentSyncState>? get() = null
    suspend fun reviewWorkspaceChanges(agentId: String): WorkspaceAgentSyncResult =
        error("This CLI backend does not expose retained workspace review")
    /** Checked synchronously before consuming a draft; missing capabilities are never guessed. */
    fun mediaAdmissionError(agentId: String, media: List<ChatMediaRef>): String? =
        if (media.isEmpty()) null else "This CLI connection does not advertise chat media input. Keep this draft or select a supported Agent."
    suspend fun runMediaTurn(agentId: String, prompt: String, media: List<ChatMediaRef>,
                             mediaOwnerId: String? = null, mode: WorkspaceAgentMode = WorkspaceAgentMode.EDIT,
                             resumeSessionId: String? = null, onEvent: (WorkspaceAgentEvent) -> Unit = {}): WorkspaceAgentTurnResult {
        require(media.isEmpty()) { "This CLI does not support media input" }
        return runTurn(agentId, prompt, mode, resumeSessionId, onEvent)
    }
    suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode = WorkspaceAgentMode.EDIT,
                        resumeSessionId: String? = null, onEvent: (WorkspaceAgentEvent) -> Unit = {}): WorkspaceAgentTurnResult
    suspend fun prepareSession(agentId: String, resumeSessionId: String? = null,
                               onEvent: (WorkspaceAgentEvent) -> Unit = {}): WorkspaceAgentEvent.Configuration
    suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration
    suspend fun selectReasoning(agentId: String, expectedSessionId: String, value: String): WorkspaceAgentEvent.Configuration =
        error("This CLI does not expose Reasoning configuration")
    /** Requests a redirect at a safe ACP boundary; the next prompt remains owned by the controller. */
    suspend fun requestSteer(agentId: String, expectedSessionId: String): Boolean = false
    suspend fun clearSteerRequest(agentId: String, expectedSessionId: String) = Unit
    suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit = {}): String
    /** Detaches an exact deleted conversation without starting or prompting a replacement. */
    suspend fun discardSession(agentId: String, expectedSessionId: String) {
        error("This CLI backend cannot detach a deleted conversation")
    }
    suspend fun cancel(agentId: String, expectedSessionId: String? = null)
}

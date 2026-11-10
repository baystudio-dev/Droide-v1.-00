package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SubagentInputProjection(
    val messages: List<AgentMessage>,
    val apiEntries: List<JsonObject>,
    val deliveryReceipts: List<SubagentDeliveryReceipt> = emptyList(),
)

class AgentSubagentBridge(
    files: FileRepository,
    terminal: ITerminalSession,
    git: GitManager,
    approvals: ApprovalManager,
    lsp: LspManager,
    questions: QuestionManager,
    permissions: PermissionEngine,
    workDir: File,
    depth: Int,
    maxDepth: Int,
    pluginSource: AgentPluginSource = AgentPluginSource.EMPTY,
    documentAuthority: WorkspaceDocumentAuthority? = null,
    private val maxAssistantChars: Int,
    private val maxApiMessageChars: Int,
    providerConfiguration: ProviderRegistry.ConfiguredSnapshot? = null,
    codeStyleDefaults: suspend () -> CodeStyleDefaults = { CodeStyleDefaults() },
    onBackgroundCompletion: suspend (SubagentCompletion) -> Unit,
) {
    private val emptyDashboard = MutableStateFlow(SubagentDashboardState())

    val manager: SubagentManager? = if (depth < maxDepth) SubagentManager(
        files = files,
        terminal = terminal,
        git = git,
        approvals = approvals,
        lsp = lsp,
        questions = questions,
        parentPermissions = permissions,
        workDir = workDir,
        depth = depth,
        maxDepth = maxDepth,
        pluginSource = pluginSource,
        documentAuthority = documentAuthority,
        providerConfiguration = providerConfiguration,
        codeStyleDefaults = codeStyleDefaults,
        onBackgroundCompletion = onBackgroundCompletion,
        ) else null

    val dashboard: StateFlow<SubagentDashboardState> get() = manager?.dashboard ?: emptyDashboard

    fun bindParent(parentSessionId: String?) {
        manager?.bindParent(parentSessionId) ?: run { emptyDashboard.value = SubagentDashboardState() }
    }

    suspend fun controlFromUi(parentSessionId: String, action: String, taskId: String, message: String? = null): String =
        manager?.control(parentSessionId, action, taskId, message) ?: "Subagents unavailable in this context."

    suspend fun projectInitial(
        origin: AgentInputOrigin,
        sourceId: String?,
        text: String,
        runId: Long,
        images: List<AgentImageRef> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
        media: List<ChatMediaRef> = emptyList(),
        parentSessionId: String? = null,
        sourceDelivery: SubagentDeliveryReceipt? = null,
    ): SubagentInputProjection {
        val receipt = SubagentDeliveryHistory.boundReceipt(origin, sourceId, parentSessionId, sourceDelivery)
        if (origin == AgentInputOrigin.USER) {
            return SubagentInputProjection(
                messages = listOf(AgentMessage("user", text, media = media)),
                apiEntries = listOf(VisionSupport.userMessage(AgentIdeContextProjection.forModel(AgentMediaPromptReceipt.forModel(text, media, images), ideContext, maxApiMessageChars), images, maxApiMessageChars)),
            )
        }
        val part = AgentStreamPart(
            id = "subagent-input:${sourceId ?: runId}:${receipt?.attempt ?: runId}",
            step = 0,
            kind = AgentStreamPartKind.STATUS,
            state = AgentStreamPartState.COMPLETED,
            title = "Subagent result",
            detail = "Background child completion delivered to parent",
            text = text.take(maxAssistantChars),
            endedAtMs = System.currentTimeMillis(),
        )
        return SubagentInputProjection(
            messages = listOf(AgentMessage("assistant", "", streamPart = part)),
            apiEntries = listOf(SubagentDeliveryHistory.entry(
                "[Background subagent completion; synthetic system event, not user-authored]\n${text.take(maxApiMessageChars)}", receipt,
            )),
            deliveryReceipts = listOfNotNull(receipt),
        )
    }

    suspend fun recover(
        parentSessionId: String,
        existingApiHistory: List<JsonObject>,
    ): SubagentInputProjection {
        val active = manager ?: return SubagentInputProjection(emptyList(), emptyList())
        val pending = active.pendingDeliveries(parentSessionId)
        if (pending.isEmpty()) return SubagentInputProjection(emptyList(), emptyList())
        val messages = mutableListOf<AgentMessage>()
        val api = mutableListOf<JsonObject>()
        val deliveryReceipts = mutableListOf<SubagentDeliveryReceipt>()
        val existingReceipts = existingApiHistory.mapNotNull(SubagentDeliveryHistory::read).toSet()
        pending.forEach { record ->
            val receipt = SubagentDeliveryReceipt.from(record)
            deliveryReceipts += receipt

            if (receipt in existingReceipts) return@forEach
            val text = render(record)
            messages += AgentMessage("assistant", "", streamPart = AgentStreamPart(
                id = "subagent-recovery:${record.id}:${record.attempt}",
                step = 0,
                kind = AgentStreamPartKind.STATUS,
                state = if (record.state == SubagentTaskState.IDLE) AgentStreamPartState.COMPLETED else AgentStreamPartState.ERROR,
                title = if (record.state == SubagentTaskState.IDLE) "Subagent completed" else "Subagent ${record.state.name.lowercase()}",
                detail = "Recovered durable result · ${record.profile} · ${record.childSessionId}",
                text = text.take(maxAssistantChars),
                endedAtMs = System.currentTimeMillis(),
            ))
            api += SubagentDeliveryHistory.entry(
                "[Background subagent completion; recovered durable system event, not user-authored]\n${text.take(maxApiMessageChars)}", receipt,
            )
        }
        return SubagentInputProjection(messages, api, deliveryReceipts.distinct())
    }

    suspend fun markDelivered(receipts: List<SubagentDeliveryReceipt>) {
        val active = manager ?: return
        receipts.distinct().forEach { active.markDelivered(it) }
    }

    fun render(record: SubagentTaskRecord): String = when (record.state) {
        SubagentTaskState.IDLE ->
            "SUBAGENT_COMPLETED task_id=${record.id} attempt=${record.attempt} child_session_id=${record.childSessionId} profile=${record.profile} " +
                "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.result}" +
                if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED) "\nChanges remain isolated until task_control merge/discard." else ""
        else ->
            "SUBAGENT_${record.state.name} task_id=${record.id} attempt=${record.attempt} child_session_id=${record.childSessionId} profile=${record.profile} " +
                "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.error.ifBlank { record.result }}"
    }.take(maxApiMessageChars)

    internal fun beginExit() { manager?.beginExit() }

    suspend fun closeAndJoin() { manager?.closeAndJoin() }

    fun close() = manager?.close()
}

internal fun renderSubagentCompletionText(record: SubagentTaskRecord, maxChars: Int, activeSiblings: Int = 0): String {
    val coordination = if (activeSiblings > 0)
        "\nremaining_parallel_agents=$activeSiblings; absorb this result but do not finalize the parallel work yet."
    else "\nremaining_parallel_agents=0; all currently launched sibling agents have settled, so synthesize their relevant results before finalizing."
    return (when (record.state) {
        SubagentTaskState.IDLE -> "SUBAGENT_COMPLETED task_id=${record.id} attempt=${record.attempt} child_session_id=${record.childSessionId} profile=${record.profile} " +
            "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.result}" +
            if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED) "\nChanges remain isolated until task_control review/merge/discard." else ""
        else -> "SUBAGENT_${record.state.name} task_id=${record.id} attempt=${record.attempt} child_session_id=${record.childSessionId} profile=${record.profile} " +
            "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.error.ifBlank { record.result }}"
    } + coordination).take(maxChars)
}

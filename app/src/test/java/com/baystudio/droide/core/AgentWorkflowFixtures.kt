package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

internal class AgentWorkflowTerminal : ITerminalSession {
    override val output = MutableStateFlow("")
    override val supportsCommandInput = true
    override fun start() = Unit
    override fun send(cmd: String) = Unit
    override suspend fun execOnce(cmd: String, timeoutMs: Long) = execStreaming(cmd, timeoutMs) {}
    override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = LocalProcessSupervisor.capture(argv, File("/tmp"), timeoutMs = timeoutMs)
    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit) =
        LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", cmd), File("/tmp"), timeoutMs = timeoutMs, onOutput = onOutput, keepTail = true)
    override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit) =
        LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", cmd), File("/tmp"), timeoutMs = timeoutMs,
            onOutput = onOutput, keepTail = true, streamInput = input)
    override fun interrupt() = Unit
    override fun clear() = Unit
    override fun destroy() = Unit
}

internal class AgentWorkflowDocuments(var current: WorkspaceDocumentSnapshot) : WorkspaceDocumentAuthority {
    override suspend fun snapshot(path: String) = current.takeIf { it.path == path }
    override suspend fun snapshots() = listOf(current)
    override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest) =
        request.captureFiniteSnapshots(listOf(current))
    override suspend fun replaceText(path: String, expected: WorkspaceDocumentVersion, content: String, reason: String): WorkspaceDocumentMutationResult {
        if (current.path != path) return WorkspaceDocumentMutationResult.NotLive
        if (current.version != expected) return WorkspaceDocumentMutationResult.Conflict(current)
        val before = current
        current = before.copy(content = content, dirty = true, changeVersion = before.changeVersion + 1,
            selectionStart = 0, selectionEnd = 0)
        return WorkspaceDocumentMutationResult.Applied(before, current)
    }
}

internal fun agentWorkflowSnapshot(path: String, text: String, kind: WorkspaceDocumentKind = WorkspaceDocumentKind.TEXT, editable: Boolean = true) =
    WorkspaceDocumentSnapshot(path, text, true, kind, true, editable, 2, 3, 0, 0, text.length, java.util.UUID.randomUUID().toString())

internal fun agentWorkflowContext(
    root: File, manager: BackgroundCommandManager? = null, documents: WorkspaceDocumentAuthority? = null,
    permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
    mode: AgentMode = AgentMode.BUILD, imageSink: ((List<AgentImageRef>) -> Unit)? = null,
    webResearch: AgentWebResearch = AgentWebResearch(root),
    terminal: ITerminalSession = AgentWorkflowTerminal(),
    onProgress: (AgentToolProgress) -> Unit = {},
    browserController: AgentBrowserController? = null,
    provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
) : AgentTools.Ctx {
    val files = FileRepository(root)
    return AgentTools.Ctx(files, terminal, GitManager(root), ApprovalManager(), QuestionManager(), EditHistory(files, documents),
        permissions, mode, LlmClient(), AgentConfig(providerId = "openai", model = "gpt-4o"), root,
        TodoManager(), "test-session", object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = ""
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        }, backgroundCommands = manager, documentAuthority = documents, onImageReady = imageSink, webResearch = webResearch, onProgress = onProgress,
        browserController = browserController, provenance = provenance)
}

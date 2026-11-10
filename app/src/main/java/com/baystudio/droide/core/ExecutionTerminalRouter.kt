package com.baystudio.droide.core

import kotlinx.coroutines.flow.StateFlow

class ExecutionTerminalRouter(
    private val terminals: TerminalManager,
    private val fallback: ITerminalSession? = null,
    private val appContext: android.content.Context? = null,
    private val workspaceRoot: java.io.File? = null,
) : ITerminalSession, WorkspaceCommandSession {
    private val emptyOutput = kotlinx.coroutines.flow.MutableStateFlow("")
    override val output: StateFlow<String> get() = selected()?.output ?: fallback?.output ?: emptyOutput
    override fun start() = Unit
    override fun send(cmd: String) = Unit

    // Command execution binds to the visible active tab; never create a hidden terminal. Capture the
    // session once per command to prevent rerouting on tab switch.
    private fun selected(): ITerminalSession? = terminals.activeTab()?.takeIf {
        !terminals.ubuntuOnly || it.profile == TerminalManager.Profile.LOCAL_LINUX_ARM64
    }?.session

    override val supportsCommandInput: Boolean get() = selected()?.supportsCommandInput == true

    override suspend fun prepareCommandInput(): Boolean =
        selected()?.prepareCommandInput() == true

    override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult {
        return tracked { it.execStreamingWithInput(workspaceCommand(cmd), timeoutMs, input, onOutput) }
    }

    private fun workspaceCommand(command: String): String =
        workspaceRoot?.let { AgentWorkspaceCommandPolicy.shell(it, command) } ?: command

    private suspend fun workspaceArgv(target: ITerminalSession, argv: List<String>, timeoutMs: Long): ExecResult {
        val root = workspaceRoot?.let(AgentWorkspaceCommandPolicy::requireRoot)
            ?: return target.execArgv(argv, timeoutMs)
        return WorkspaceCommandInvocation.execute(target, root, root, argv, timeoutMs)
    }

    private fun unavailable(): ExecResult = ExecResult(
        exitCode = 126,
        output = "No active terminal is available. Open or select an Ubuntu terminal before running Agent commands. " +
            terminals.automatedExecutionUnavailableReason(),
        timedOut = false,
    )

    private suspend fun tracked(
        target: ITerminalSession? = selected(),
        run: suspend (ITerminalSession) -> ExecResult,
    ): ExecResult {
        if (target == null || terminals.snapshot.value.tabs.none { it.session === target }) return unavailable()
        val activity = target as? TerminalAgentActivitySession
        activity?.agentCommandStarted()
        var exitCode: Int? = null
        try {
            return run(target).also { exitCode = it.exitCode }
        } finally {
            activity?.agentCommandFinished(exitCode)
        }
    }

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
        return tracked { it.execOnce(workspaceCommand(cmd), timeoutMs) }
    }

    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
        return tracked { it.execStreaming(workspaceCommand(cmd), timeoutMs, onOutput) }
    }

    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
        return tracked { workspaceArgv(it, argv, timeoutMs) }
    }

    override suspend fun execWorkspaceArgv(workspaceRoot: java.io.File, directory: java.io.File,
                                          command: List<String>, timeoutMs: Long): ExecResult = tracked { pinned ->
        WorkspaceCommandInvocation.execute(pinned, workspaceRoot, directory, command, timeoutMs)
    }

    /** One Agent command is pinned to the current visible tab at creation. */

    internal fun bindCommandSession(): ITerminalSession {
        val pinnedSnapshot = terminals.activeTab()?.takeIf {
            !terminals.ubuntuOnly || it.profile == TerminalManager.Profile.LOCAL_LINUX_ARM64
        }
        val pinned = pinnedSnapshot?.session
        return object : ITerminalSession, DevelopmentOperationSession, InteractivePtyCommandSession, TerminalEnvironmentIdentitySession, WorkspaceCommandSession {
            override val environmentProfile = pinnedSnapshot?.profile
            override val supportsInteractivePty: Boolean get() = (pinned as? InteractivePtyCommandSession)?.supportsInteractivePty == true
            override suspend fun execInteractivePty(command: String, timeoutMs: Long, input: ProcessInputBuffer?, onOutput: (String) -> Unit): ExecResult =
                tracked(pinned) {
                    val pty = it as? InteractivePtyCommandSession
                        ?: error("The selected terminal backend does not support an Agent-owned visible PTY")
                    check(pty.supportsInteractivePty) { "Interactive PTY is unavailable in the selected terminal" }
                    pty.execInteractivePty(workspaceCommand(command), timeoutMs, input, onOutput)
                }
            override suspend fun <T> protectCommand(block: suspend () -> T): T {
                val backend = pinned as? DevelopmentOperationSession
                if (backend != null) return backend.protectCommand(block)
                val context = appContext
                return if (pinned != null && context != null)
                    OwnedDevelopmentOperation.run(context, "Agent terminal command", block)
                else block()
            }
            override val output: StateFlow<String> get() = pinned?.output ?: fallback?.output ?: emptyOutput
            override val supportsCommandInput: Boolean get() = pinned?.supportsCommandInput == true
            override suspend fun prepareCommandInput(): Boolean =
                pinned?.prepareCommandInput() == true
            override fun start() = Unit
            override fun send(cmd: String) = Unit
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult =
                tracked(pinned) { it.execOnce(workspaceCommand(cmd), timeoutMs) }
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult =
                tracked(pinned) { it.execStreaming(workspaceCommand(cmd), timeoutMs, onOutput) }
            override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult =
                tracked(pinned) { it.execStreamingWithInput(workspaceCommand(cmd), timeoutMs, input, onOutput) }
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult =
                tracked(pinned) { workspaceArgv(it, argv, timeoutMs) }
            override suspend fun execWorkspaceArgv(workspaceRoot: java.io.File, directory: java.io.File,
                                                  command: List<String>, timeoutMs: Long): ExecResult = tracked(pinned) {
                WorkspaceCommandInvocation.execute(it, workspaceRoot, directory, command, timeoutMs)
            }
            override fun interrupt() = Unit
            override fun clear() = Unit
            override fun destroy() = Unit
        }
    }

    // Agent cancellation must never deliver Ctrl-C to the human PTY.
    override fun interrupt() = Unit
    override fun clear() = Unit

    override fun destroy() = Unit

}

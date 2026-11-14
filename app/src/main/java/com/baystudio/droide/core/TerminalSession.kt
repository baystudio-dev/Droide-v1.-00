package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap

 
interface ITerminalSession {
    val supportsCommandInput: Boolean get() = false
    suspend fun prepareCommandInput(): Boolean = supportsCommandInput
    val output: StateFlow<String>
    fun start()
    fun send(cmd: String)
    suspend fun execOnce(cmd: String, timeoutMs: Long = 30_000): ExecResult
    suspend fun execStreaming(cmd: String, timeoutMs: Long = 30_000, onOutput: (String) -> Unit): ExecResult =
        execOnce(cmd, timeoutMs)
    suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult =
        throw UnsupportedOperationException("This execution backend does not support command stdin; use exec_command with keep_stdin_open=false")
    suspend fun execArgv(argv: List<String>, timeoutMs: Long = 30_000): ExecResult
    fun interrupt()
    fun clear()
    fun destroy()
    /** Explicit Exit must observe completion; unknown backends fail closed. */
    suspend fun stopAndAwaitForExit() { throw IllegalStateException("This terminal backend has no verified Exit completion path") }
}

 
interface TerminalMetadataSession {
    val terminalTitle: StateFlow<String?>
}

interface TerminalWorkingDirectorySession {
    val workingDirectory: StateFlow<String?>
}

data class ExecResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    /** Backend capture completeness, independent of process status and output-retention cursors. */
    val outputIncomplete: Boolean = false,
)






class TerminalSession(
    private val workDir: File,
    private val scope: CoroutineScope,
    private val launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(),
    val commandModeReason: String? = null,
    internal val sharedRecording: TerminalOutputRecording = TerminalOutputRecording(workDir),
    private val humanCpuFactory: (((Long, Long) -> Unit, (String) -> Unit) -> HumanTerminalCpuProtection)? = null,
) : ITerminalSession, TerminalOwnershipObservationSource, TerminalWorkingDirectorySession, TerminalCommandActivitySession, TerminalAgentActivitySession, WorkspaceCommandSession, TerminalCpuProtectionSession, TerminalDiagnosticsSession {
    override val diagnosticsWorkspace: File get() = workDir
    override val recording: TerminalOutputRecording get() = sharedRecording
    private var hostLayoutTestAuthority: TerminalHostLayoutTestAuthority? = null
    internal constructor(workDir: File, scope: CoroutineScope, launchSpec: TerminalLaunchSpec,
                         hostLayoutTestAuthority: TerminalHostLayoutTestAuthority) : this(workDir, scope, launchSpec) {
        this.hostLayoutTestAuthority = hostLayoutTestAuthority
    }
    private var workspaceLaunchTestResolver: (suspend (TerminalLaunchSpec, File, File) -> TerminalLaunchSpec?)? = null
    internal constructor(workDir: File, scope: CoroutineScope, launchSpec: TerminalLaunchSpec,
                         hostLayoutTestAuthority: TerminalHostLayoutTestAuthority,
                         workspaceLaunchResolver: suspend (TerminalLaunchSpec, File, File) -> TerminalLaunchSpec?) :
        this(workDir, scope, launchSpec, hostLayoutTestAuthority) {
        this.workspaceLaunchTestResolver = workspaceLaunchResolver
    }
    private fun expandedRuntimeLimits(): CountedShellArgv.Limits? {
        val host = hostLayoutTestAuthority
        return if (host == null) AndroidShellAttemptLimits.current() else host.limits()
    }

    internal val commandHistory = TerminalCommandHistory()
    private var directoryIdentity = workDir.takeIf { it.isDirectory }?.let(::WorkspaceDirectoryIdentity)
    private fun requireWorkingDirectory() {
        if (directoryIdentity == null) {
            check(workDir.isDirectory || workDir.mkdirs()) { "Terminal working directory is unavailable" }
            directoryIdentity = WorkspaceDirectoryIdentity(workDir)
        }
        directoryIdentity?.requireCurrent()
    }
    private val agentTracker = TerminalAgentActivityTracker()
    override val agentActivity = agentTracker.state
    override fun agentCommandStarted() = agentTracker.start()
    override fun agentCommandFinished(exitCode: Int?) = agentTracker.finish(exitCode)
    override val supportsCommandInput: Boolean get() = true
    private val _output = MutableStateFlow("")
    override val output: StateFlow<String> = _output.asStateFlow()
    private val _workingDirectory = MutableStateFlow<String?>(workDir.absolutePath)
    override val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()
    /** Native PTY shell integration supplies cwd; never inject input into its shell. */
    internal fun syncObservedCwd(path: String?) { _workingDirectory.value = path }
    private val activity = TerminalCommandActivityTracker()
    override val commandActivity = activity.state
    override fun noteCommandInput() = activity.submitted()
    private val directoryStream = TerminalDirectoryStream(
        onDirectory = { _workingDirectory.value = it },
        onTitle = {
            if (activity.consumeTitle(it) && activity.state.value.phase == TerminalCommandPhase.CWD_UNAVAILABLE) {
                _workingDirectory.value = null
            }
        },
    )
    private val outputCap = 200_000
    private val outputBuffer = TerminalOutputBuffer(outputCap)
    private val outputPublisher = TerminalTranscriptPublisher(
        schedule = { waitMs, action ->
            val job = scope.launch(Dispatchers.Default) { delay(waitMs); action() }
            val cancel = { job.cancel() }
            cancel
        },
        capture = outputBuffer::snapshot,
        publish = { _output.value = it },
    )
    private val processLeases = ConcurrentHashMap<Process, RemoteProcessLease>()
    private val executionLock = Any()
    private var observationVersion: Any = Any() // guarded by executionLock
    private val executions = mutableSetOf<Job>()
    @Volatile private var destroyed = false
    @Volatile private var executionCleanupFailure: Throwable? = null
    private val shellEpoch = MutableStateFlow(0L)
    private val humanCpu = humanCpuFactory?.invoke(::stopHumanCommand) { detail ->
        if (!destroyed) appendLine("[CPU protection] $detail")
    }
    override val cpuProtection: StateFlow<HumanTerminalCpuProtection.Status> = humanCpu?.status
        ?: MutableStateFlow(HumanTerminalCpuProtection.Status(HumanTerminalCpuProtection.Phase.IDLE))
    private var humanCpuWatcher: Job? = null
    private fun stopHumanCommand(epoch: Long, generation: Long) {
        scope.launch(Dispatchers.IO) {
            if (!ApplicationWorkAdmission.isOpen || destroyed) return@launch
            var claim: InteractiveTerminalProcess.RestartClaim? = null
            activity.withActiveGeneration(generation) {
                claim = interactive.claimRestartIfCurrent(epoch) {
                    !destroyed && shellEpoch.value == epoch
                }
                claim != null
            }
            // The tracker and process locks are both released before cancellation or waiting.
            claim?.complete { restart ->
                if (!destroyed && ApplicationWorkAdmission.isOpen) {
                    try { ApplicationWorkAdmission.withOpen { restart() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: RuntimeException) {
                        if (!destroyed && ApplicationWorkAdmission.isOpen) appendLine("[shell restart unavailable]")
                    }
                }
            }
            if (claim != null && !destroyed) appendLine("[previous shell stopped]")
        }
    }
    private val interactive = InteractiveTerminalProcess(
        scope = scope,
        launchProcess = { epoch ->
            activity.resetForLaunch()
            shellEpoch.value = epoch
            requireWorkingDirectory()
            val lease = RemoteProcessLease.createLocal("pipe-terminal")
            val promptEnvironment = launchSpec.interactiveEnvironment +
                (if (launchSpec.prefix.isEmpty()) LocalExecutionSubstrate.localTerminalPromptEnvironment() else emptyMap()) +
                activity.environment
            ExecutionDiagnostics.record(workDir, "Command shell", workDir.absolutePath, launchSpec, overrides = TerminalPipeShell.environment)
            val command = launchSpec.pipeCommand(promptEnvironment + lease.environment).joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)
            ProcessBuilder(ownershipShell(), "-c", lease.wrap(command))
                .directory(workDir).redirectErrorStream(true)
                .apply { environment().putAll(launchSpec.environment + promptEnvironment + lease.environment + TerminalPipeShell.environment) }
                .start().also { child -> synchronized(executionLock) {
                    observationVersion = Any(); processLeases[child] = lease
                } }
        },
        cleanup = { process -> processLeases[process]?.let { lease ->
            LocalExecutionSubstrate.terminateLease(lease)
            synchronized(executionLock) {
                if (processLeases.remove(process, lease)) observationVersion = Any()
            }
        } },
        output = { text ->
            activity.observedOutput()
            recording.append(text.toByteArray(Charsets.UTF_8))
            emit(Ansi.strip(directoryStream.consume(text)))
        },
        onFinished = { activity.closed(it) },
        onFailure = { activity.failed() },
        maxCommandChars = TerminalPipeShell.MAX_FRAME_CHARS,
        maxQueuedChars = TerminalPipeShell.MAX_QUEUED_FRAME_CHARS,
        initialCommand = TerminalPipeShell.ready(),
        prepareLaunch = {
            val applicationEpoch = ApplicationWorkAdmission.requireOpen()
            InteractiveTerminalProcess.LaunchAdmission { create ->
                fun requireStartupContext() {
                    scope.ensureActive()
                    check(!destroyed) { "Terminal is closed" }
                    check(ApplicationWorkAdmission.isCurrentOpen(applicationEpoch)) {
                        "Terminal startup belongs to an expired application session"
                    }
                    requireWorkingDirectory()
                }
                requireStartupContext()
                withWorkspaceProcessCreation(workDir) {
                    requireStartupContext()
                    create().also { requireStartupContext() }
                }
            }
        },
    )

    init {
        if (humanCpu != null) humanCpuWatcher = scope.launch(Dispatchers.Main.immediate) {
            try { combine(activity.state, shellEpoch) { value, epoch -> value to epoch }
                .collect { (value, epoch) -> if (epoch > 0) humanCpu.observe(value, epoch) } }
            finally { humanCpu.close() }
        }
    }

    override fun start() {
        ApplicationWorkAdmission.requireOpen()
        interactive.start()
    }

    override fun send(cmd: String) { sendCommand(cmd) }

    /** The command-mode field needs an admission receipt to preserve rejected drafts. */
    internal fun submitCommandInput(cmd: String): Boolean = commandHistory.submit(cmd, ::sendCommand)

    private fun sendCommand(cmd: String): Boolean {
        try {
            ApplicationWorkAdmission.requireOpen()
            noteCommandInput()
            interactive.send(TerminalPipeShell.command(cmd))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            activity.failed()
            appendLine("[terminal error] ${error.message ?: error.javaClass.simpleName}")
            return false
        }
        // Admission has committed. A transcript-display failure must never report
        // rejection and invite resubmission of a command already queued to the shell.
        runCatching { appendLine("$ $cmd") }
        return true
    }

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult =
        executeProcess(emptyList(), timeoutMs, shellText = cmd)

    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult =
        executeProcess(emptyList(), timeoutMs, onOutput, shellText = cmd)

    override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult =
        executeProcess(emptyList(), timeoutMs, onOutput, shellText = cmd, streamInput = input)

    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult =
        executeProcess(argv, timeoutMs)

    override suspend fun execWorkspaceArgv(workspaceRoot: File, directory: File, command: List<String>, timeoutMs: Long): ExecResult {
        require(PathSecurity.contains(workspaceRoot, directory)) { "Command cwd escaped workspace" }
        return executeProcess(command, timeoutMs, workspaceInvocation = WorkspaceInvocation(workspaceRoot, directory))
    }

    private data class WorkspaceInvocation(val root: File, val directory: File)

    private suspend fun executeProcess(
        payloadArgv: List<String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit = { },
        shellText: String? = null,
        streamInput: ProcessInputBuffer? = null,
        workspaceInvocation: WorkspaceInvocation? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        val execution = currentCoroutineContext().job
        val applicationEpoch = synchronized(executionLock) {
            val epoch = ApplicationWorkAdmission.requireOpen()
            if (destroyed) return@withContext ExecResult(-1, "Terminal is closed", false)
            observationVersion = Any()
            executions += execution
            epoch
        }
        var expandedAttempt: LocalShellAttemptPlan.Prepared? = null
        try {
            require(timeoutMs in 0..86_400_000L) { "Invalid process timeout" }
            requireWorkingDirectory()
            val requestedRootIdentity = workspaceInvocation?.root?.takeUnless { it == workDir }
                ?.let(::WorkspaceDirectoryIdentity)
            val requestedDirectoryIdentity = workspaceInvocation?.directory?.let(::WorkspaceDirectoryIdentity)
            val observedCwd = workspaceInvocation?.directory?.absolutePath ?: _workingDirectory.value
            val commandCwd = TerminalCommandCwdPolicy.resolveHostCwd(
                workspace = workDir, reported = observedCwd,
                isNativeHost = launchSpec.prefix.isEmpty(),
            )
            val commandDirectoryIdentity = WorkspaceDirectoryIdentity(commandCwd)
            val guestLaunch = TerminalGuestCwdPolicy.resolveLaunch(launchSpec, workDir, observedCwd)
            val guestDirectoryIdentity = guestLaunch.workspaceCwd
                ?.takeUnless { it == commandCwd }
                ?.let(::WorkspaceDirectoryIdentity)
            val lease = RemoteProcessLease.createLocal("terminal")
            val resolver = workspaceLaunchTestResolver ?: LocalExecutionSubstrate::workspaceToolchainLaunchSpec
            val refreshed = resolver(guestLaunch.spec, workspaceInvocation?.directory ?: commandCwd,
                workspaceInvocation?.root ?: workDir) ?: guestLaunch.spec
            val commandSpec = if (workspaceInvocation == null)
                TerminalGuestCwdPolicy.withObservedCwd(refreshed, workDir, observedCwd) else refreshed
            execution.ensureActive()
            val guestEnvironment = if (commandSpec.prefix.isEmpty()) emptyMap() else lease.environment
            val commandArgv = workspaceInvocation?.let {
                WorkspaceCommandInvocation.argv(it.directory, payloadArgv, commandSpec.shell)
            } ?: payloadArgv
            val expandedLimits = if (shellText != null && shellText.length > 32_000)
                expandedRuntimeLimits() else null
            val ownedArgv = if (expandedLimits != null && shellText != null) {
                val counted = LocalShellAttemptPlan.shellArgv(commandSpec, shellText, guestEnvironment) { execution.ensureActive() }
                val target = LocalShellAttemptPlan.joinTarget(counted)
                val wrapped = hostLayoutTestAuthority?.let { lease.countedLocalWrapForHostTest(target, it) }
                    ?: lease.countedLocalWrap(target)
                LocalShellAttemptPlan.prepare(listOf(CountedShellArgv.Node.literal(ownershipShell()),
                    CountedShellArgv.Node.literal("-c"), wrapped), expandedLimits, shellText.length.toLong(),
                    checkCancelled = { execution.ensureActive() }).also { expandedAttempt = it }.render()
            } else {
                // Existing <=32k and unavailable-runtime paths remain byte-for-byte unchanged.
                val targetArgv = if (shellText == null) commandSpec.command(commandArgv, guestEnvironment)
                    else commandSpec.shellCommand(shellText, guestEnvironment)
                val targetCommand = targetArgv.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)
                listOf(ownershipShell(), "-c", hostLayoutTestAuthority?.let { lease.wrapForHostTest(targetCommand, it) }
                    ?: lease.wrap(targetCommand))
            }
            ExecutionDiagnostics.record(workspaceInvocation?.root ?: workDir,
                if (workspaceInvocation == null) "Agent / terminal command" else "Run / Build / task",
                observedCwd ?: commandCwd.absolutePath, commandSpec, payloadArgv.firstOrNull() ?: commandSpec.shell)
            val result = LocalProcessSupervisor.captureWithAdmission(
                argv = ownedArgv,
                cwd = commandCwd,
                environment = commandSpec.environment + lease.environment,
                maxOutputBytes = 24_000,
                timeoutMs = timeoutMs,
                cleanup = {
                    try {
                        val host = hostLayoutTestAuthority
                        if (host == null) LocalExecutionSubstrate.terminateLease(lease) else host.terminate(lease)
                    }
                    catch (failure: Throwable) { recordCleanupFailure(failure); throw failure }
                },
                onOutput = { chunk -> recording.append(chunk.toByteArray(Charsets.UTF_8)); runCatching { onOutput(Ansi.strip(chunk)) } },
                keepTail = true,
                streamInput = streamInput,
                onCleanupCompleted = { failure -> if (failure != null) recordCleanupFailure(failure) },
            ) { create ->
                withWorkspaceProcessCreation(workspaceInvocation?.root ?: workDir) {
                    scope.ensureActive()
                    check(!destroyed) { "Terminal is closed" }
                    check(ApplicationWorkAdmission.isCurrentOpen(applicationEpoch)) {
                        "Terminal command belongs to an expired application session"
                    }
                    requireWorkingDirectory()
                    requestedRootIdentity?.requireCurrent()
                    requestedDirectoryIdentity?.requireCurrent()
                    commandDirectoryIdentity.requireCurrent()
                    guestDirectoryIdentity?.requireCurrent()
                    expandedAttempt?.revalidate(expandedRuntimeLimits())
                    hostLayoutTestAuthority?.recordNativeCreationEntry()
                    create().also {
                        requireWorkingDirectory()
                        requestedRootIdentity?.requireCurrent()
                        requestedDirectoryIdentity?.requireCurrent()
                        commandDirectoryIdentity.requireCurrent()
                        guestDirectoryIdentity?.requireCurrent()
                    }
                }
            }
            if (result.timedOut) {
                result.copy(
                    exitCode = -1,
                    output = (Ansi.strip(result.output) + "\n[TIMEOUT ${timeoutMs}ms]").takeLast(20_000),
                )
            } else {
                result.copy(output = Ansi.strip(result.output).takeLast(20_000))
            }
        } catch (cancel: CancellationException) {
            cancel.suppressed.filterIsInstance<UserEnvironmentCleanupUnconfirmed>().firstOrNull()
                ?.let(::recordCleanupFailure)
            throw cancel
        } catch (e: Exception) {
            if (e is UserEnvironmentCleanupUnconfirmed) recordCleanupFailure(e)
            ExecResult(-1, e.message ?: "error", false)
        } finally {
            expandedAttempt?.close()
            synchronized(executionLock) { observationVersion = Any(); executions -= execution }
        }
    }


    private fun ownershipShell(): String =
        if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh"

    override fun interrupt() {
        scope.launch(Dispatchers.IO) {
            try {
                interactive.restart()
                appendLine("[shell restarted]")
            } catch (e: Exception) {
                appendLine("[restart error] ${e.message}")
            }
        }
    }

    /** Bounded current pipe tail, independent of the coalesced UI publication timer. */
    fun captureRetainedHistory(): String = outputBuffer.snapshot()

    override fun clear() {
        outputBuffer.clear()
        outputPublisher.clear()
    }

    private fun recordCleanupFailure(failure: Throwable) = synchronized(executionLock) {
        observationVersion = Any()
        executionCleanupFailure = failure
    }

    override suspend fun observeTerminalOwnership(): TerminalOwnershipObservation {
        currentCoroutineContext().ensureActive()
        val execution = synchronized(executionLock) {
            val version = observationVersion
            val diagnostics = buildList {
                if (executions.isNotEmpty()) add("Fallback terminal execution registrations: ${executions.size}")
                if (processLeases.isNotEmpty()) add("Fallback terminal retained interactive-shell leases: ${processLeases.size}")
                if (executionCleanupFailure != null) add("Fallback terminal execution cleanup failure retained")
            }
            TerminalOwnershipObservation(emptyList(), destroyed, diagnostics) {
                synchronized(executionLock) {
                    check(observationVersion === version) { "Fallback terminal executions changed during review" }
                }
            }
        }
        // No nested owner locks. Revalidate both after collecting the second authority.
        val pipe = interactive.observeOwnership()
        val result = TerminalOwnershipObservation(emptyList(), execution.disposed,
            execution.retainedOwners + pipe.retainedOwners) {
            execution.validateMetadata(); pipe.validateMetadata(); execution.validateMetadata()
        }
        result.validate()
        return result
    }

    override suspend fun stopAndAwaitForExit() {
        commandHistory.close()
        val running = synchronized(executionLock) { observationVersion = Any(); destroyed = true; executions.toList() }
        humanCpuWatcher?.cancel(); humanCpu?.close()
        running.forEach { it.cancel(CancellationException("Droide Exit")) }
        OwnedResourceCleanup.runSuspend(
            { interactive.destroyAndJoinForExit() },
            { withTimeoutOrNull(8_000) { running.forEach { it.join() }; true }
                ?: error("Terminal command cleanup is still pending; its owner is retained") },
            { activity.closed(); outputPublisher.close(); recording.awaitClosed() },
            { executionCleanupFailure?.let { throw IllegalStateException("Terminal command cleanup is incomplete", it) } },
        ).getOrThrow()
    }

    override fun destroy() {
        commandHistory.close()
        humanCpuWatcher?.cancel(); humanCpu?.close()
        activity.closed()
        val running = synchronized(executionLock) {
            observationVersion = Any()
            destroyed = true
            executions.toList()
        }
        running.forEach { it.cancel(CancellationException("Terminal is closed")) }
        try { interactive.destroy() } finally { outputPublisher.close(); recording.close() }
    }

    private fun emit(s: String) {
        outputBuffer.append(s)
        outputPublisher.request()
    }
    private fun appendLine(s: String) { emit(s + "\n") }
}

object Ansi {
    private val regex = Regex("\u001B\\[[;\\d]*m|\u001B\\[\\d+[A-Z]|\u001B\\(B|\r")
    fun strip(s: String): String = s.replace(regex, "")
}

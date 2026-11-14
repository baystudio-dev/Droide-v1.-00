package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicBoolean
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.termux.terminal.TerminalSession as TermuxSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.io.File

 
interface NativePtySessionHandle : ITerminalSession, TerminalMetadataSession, TerminalWorkingDirectorySession {
    val agentPtyActive: Boolean get() = false
    val nativePtySession: TermuxSession
    /** Display target changes within one tab; the human shell itself is never replaced or reused. */
    val displayedPtySession: StateFlow<TermuxSession>
    fun writeRaw(text: String)
    fun setScreenUpdateListener(listener: (() -> Unit)?)
    fun setBellListener(listener: (() -> Unit)?)
    /** Fresh bounded text for explicit capture, called on the terminal's main Looper. */
    fun captureTranscript(): String
    fun copySelection(owner: TermuxSession, text: String): Boolean = false
}

internal object NativePtyThreadPolicy {
    fun requireMainThread(isMainThread: Boolean) {
        check(isMainThread) {
            "Native PTY sessions must be constructed on Android's main Looper"
        }
    }
}


class NativePtyTerminalSession(
    private val context: Context,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(),
) : NativePtySessionHandle, NativeTerminalGeometrySession, TerminalCpuProtectionSession, TerminalCommandActivitySession, TerminalAgentActivitySession, DevelopmentOperationSession, InteractivePtyCommandSession, WorkspaceCommandSession, TerminalOwnershipObservationSource, TerminalDiagnosticsSession {
    override val diagnosticsWorkspace: File get() = workDir
    override val recording = TerminalOutputRecording(workDir)
    override val recordingSupported: Boolean get() = TerminalRawOutputBridge.available
    override val agentPtyActive: Boolean get() = activeAgentPty != null
    init {
        NativePtyThreadPolicy.requireMainThread(
            isMainThread = Looper.getMainLooper().thread === Thread.currentThread(),
        )
    }

    private val agentTracker = TerminalAgentActivityTracker()
    private var directoryIdentity = workDir.takeIf { it.isDirectory }?.let(::WorkspaceDirectoryIdentity)
    override val agentActivity = agentTracker.state
    override suspend fun <T> protectCommand(block: suspend () -> T): T =
        OwnedDevelopmentOperation.run(context, "Agent terminal command", block)
    override fun agentCommandStarted() = agentTracker.start()
    override fun agentCommandFinished(exitCode: Int?) = agentTracker.finish(exitCode)
    private val _output = MutableStateFlow("")
    private val activity = TerminalCommandActivityTracker()
    override val commandActivity = activity.state
    override fun noteCommandInput() { if (activeAgentPty == null) activity.submitted() }
    override val output: StateFlow<String> = _output.asStateFlow()
    private val _terminalTitle = MutableStateFlow<String?>(null)
    override val terminalTitle: StateFlow<String?> = _terminalTitle.asStateFlow()
    private val _workingDirectory = MutableStateFlow<String?>(workDir.absolutePath)
    override val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()
    private val outputCap = 200_000
    private val execDelegate = TerminalSession(workDir, scope, launchSpec, sharedRecording = recording)
    // Correlation is diagnostic only. The native owner alone may signal/reap its child;
    // no generic .pid lease or environment-scanned numeric cleanup may bypass it.
    private val processToken = NativePtyToken.create()
    private val promptEnvironment = launchSpec.interactiveEnvironment +
        (if (launchSpec.prefix.isEmpty()) TerminalPromptPresentation.androidEnvironment(File(context.filesDir, "terminal-home/presentation"))
        else emptyMap()) + activity.environment
    private val interactiveArgv = launchSpec.interactiveCommand(promptEnvironment + processToken.environment)
    @Volatile private var screenUpdateListener: (() -> Unit)? = null
    @Volatile private var bellListener: (() -> Unit)? = null
    private val pendingCommands = TerminalStartupCommandQueue()
    private val destroyed = AtomicBoolean(false)
    private val humanExit = CompletableDeferred<NativePtyCleanupReceipt>()
    @Volatile private var nativeObservationVersion: Any = Any()
    private var exitAgentJob: Job? = null
    private data class NativeExitOwner(
        val terminal: TermuxSession,
        val correlation: NativePtyToken,
        val completion: CompletableDeferred<NativePtyCleanupReceipt>,
    )
    // Includes retired/partially-started Agent PTYs until their native owner confirms cleanup.
    private val pendingNativeExits = java.util.IdentityHashMap<TermuxSession, NativeExitOwner>()
    private var exitNativeOwners: List<NativeExitOwner>? = null
    private val humanInterruptPending = AtomicBoolean(false)
    private val transcriptHandler = Handler(Looper.getMainLooper())
    private val transcriptPublisher = TerminalTranscriptPublisher(
        schedule = { delay, action ->
            val callback = Runnable(action)
            transcriptHandler.postDelayed(callback, delay)
            val cancellation = { transcriptHandler.removeCallbacks(callback) }
            cancellation
        },
        capture = {
            nativePtySession.emulator?.let { TerminalTranscriptSnapshot.capture(it, outputCap) }.orEmpty()
        },
        publish = { _output.value = it },
    )

    private val pasteTransactions = java.util.IdentityHashMap<TermuxSession, TerminalPasteTransaction>()
    private val resizeReceipts = java.util.IdentityHashMap<TermuxSession, TerminalResizeReceipt>()
    private val _geometry = MutableStateFlow(TerminalGeometrySnapshot())
    override val geometry = _geometry.asStateFlow()

    private fun reportingClient(delegate: TerminalSessionClient): TerminalSessionClient =
        TerminalResizeClientBridge(delegate) { changed, generation, requestId, requestedColumns, requestedRows,
            emulatorColumns, emulatorRows, acceptedColumns, acceptedRows, status ->
            val receipt = TerminalResizeReceipt(generation, requestId, requestedColumns, requestedRows,
                emulatorColumns, emulatorRows, acceptedColumns, acceptedRows, status)
            val publish = Runnable {
                if (destroyed.get()) return@Runnable
                val previous = resizeReceipts[changed]
                if (previous != null && (generation < previous.generation ||
                    generation == previous.generation && requestId < previous.requestId)) return@Runnable
                resizeReceipts[changed] = receipt
                if (changed === nativePtySession) {
                    val old = _geometry.value.takeIf { it.owner === changed } ?: TerminalGeometrySnapshot(owner = changed)
                    _geometry.value = old.copy(receipt = receipt)
                }
            }
            if (Looper.getMainLooper().thread === Thread.currentThread()) publish.run()
            else transcriptHandler.post(publish)
        }

    private fun displayGeometry(owner: TermuxSession) {
        _geometry.value = TerminalGeometrySnapshot(owner = owner, receipt = resizeReceipts[owner])
    }

    override fun reportViewport(owner: TermuxSession, viewport: TerminalViewportGeometry) {
        NativePtyThreadPolicy.requireMainThread(Looper.getMainLooper().thread === Thread.currentThread())
        if (destroyed.get() || owner !== nativePtySession) return
        val old = _geometry.value.takeIf { it.owner === owner } ?: TerminalGeometrySnapshot(owner = owner)
        _geometry.value = old.copy(viewport = viewport, receipt = resizeReceipts[owner])
    }

    private val client = reportingClient(object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TermuxSession) {
            if (destroyed.get()) return
            activity.observedOutput()
            flushPendingCommands()
            screenUpdateListener?.invoke()
            transcriptPublisher.request()
        }
        override fun onTitleChanged(changedSession: TermuxSession) {
            if (destroyed.get()) return
            val title = changedSession.title
            if (activity.consumeTitle(title)) {
                if (activity.state.value.phase == TerminalCommandPhase.CWD_UNAVAILABLE) {
                    _workingDirectory.value = null
                    execDelegate.syncObservedCwd(null)
                }
                _terminalTitle.value = null
            } else if (title?.startsWith(TerminalPromptPresentation.TITLE_PREFIX) == true) {
                TerminalPromptPresentation.directoryFromTitle(title)?.let { cwd ->
                    _workingDirectory.value = cwd
                    execDelegate.syncObservedCwd(cwd)
                }
                _terminalTitle.value = null
            } else _terminalTitle.value = TerminalTitlePolicy.display(title)
            screenUpdateListener?.invoke()
        }
        override fun onSessionFinished(finishedSession: TermuxSession) {
            val receipt = cleanupReceipt(finishedSession, humanInputRelay)
            if (humanExit.complete(receipt)) nativeObservationVersion = Any()
            pendingCommands.close()
            transcriptPublisher.finish()
            activity.closed(receipt.exitCode.takeIf { it >= 0 })
            appendLine("\n${receipt.description}")
            Log.i("DroideTerminal", "Native PTY ${processToken.value}: ${receipt.description}")
        }

        override fun onCopyTextToClipboard(session: TermuxSession, text: String) {
            copySelection(session, text)
        }

        override fun onPasteTextFromClipboard(session: TermuxSession) {
            if (destroyed.get() || session !== nativePtySession || !ApplicationWorkAdmission.isOpen) {
                showInputNotice("Terminal changed or closed; the whole paste was rejected")
                return
            }
            val text = try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: error("Clipboard unavailable")
                val clip = clipboard.primaryClip
                if (clip == null || clip.itemCount == 0) return
                clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                showInputNotice("Paste failed; clipboard text could not be read")
                return
            }
            val transaction = pasteTransactions[session]
            val emulator = session.emulator
            if (transaction == null || emulator == null) {
                showInputNotice("Terminal is unavailable; the whole paste was rejected")
                return
            }
            transaction.paste(text, isCurrent = {
                !destroyed.get() && ApplicationWorkAdmission.isOpen && session === nativePtySession &&
                    session.emulator === emulator && pasteTransactions[session] === transaction
            }, produce = emulator::paste)
        }

        override fun onBell(session: TermuxSession) { if (!destroyed.get()) bellListener?.invoke() }
        override fun onColorsChanged(session: TermuxSession) { if (!destroyed.get()) screenUpdateListener?.invoke() }
        override fun onTerminalCursorStateChange(state: Boolean) { if (!destroyed.get()) screenUpdateListener?.invoke() }
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message ?: "terminal error", e) }
    })

    private val humanPtySession: TermuxSession = TermuxSession(
        interactiveArgv.first(),
        workDir.absolutePath,
        interactiveArgv.toTypedArray(),
        buildEnvironment(),
        10_000,
        client,
    )
    private val humanInputReady = CompletableDeferred<TermuxSession>()
    private val humanInputRelay = createInputRelay(humanInputReady) { detail ->
        val interruptUnconfirmed = humanInterruptPending.getAndSet(false)
        transcriptHandler.post {
            activity.failed()
            if (interruptUnconfirmed) appendLine("[terminal interrupt delivery could not be confirmed]")
            appendLine(detail)
        }
    }
    init { installInputHandler(humanPtySession, humanInputRelay) }
    private val _displayedPtySession = MutableStateFlow(humanPtySession)
    override val displayedPtySession = _displayedPtySession.asStateFlow()
    override val nativePtySession: TermuxSession get() = _displayedPtySession.value
    private val ptyOwnership = InteractivePtyOwnership()
    private data class AgentPtyRun(
        val token: Any,
        val terminal: TermuxSession,
        val correlation: NativePtyToken,
        val completion: CompletableDeferred<NativePtyCleanupReceipt>,
        val job: Job,
        val inputRelay: TerminalInputRelay<TermuxSession>,
    )
    @Volatile private var activeAgentPty: AgentPtyRun? = null
    override val supportsInteractivePty: Boolean get() = !destroyed.get()

    private val humanCpu = humanTerminalCpuProtection(context,
        stop = { _, ownerGeneration ->
            transcriptHandler.post {
                if (InteractivePtyOwnership.mayStopHumanCommand(activity.state.value, ownerGeneration,
                        activeAgentPty != null, destroyed.get())) interruptHumanPty()
            }
        }, warn = { detail -> transcriptHandler.post { if (!destroyed.get()) appendLine("[CPU protection] $detail") } })
    override val cpuProtection = humanCpu.status
    private val userActivityWatcher = scope.launch(Dispatchers.Main.immediate) {
        try { activity.state.collect { value -> humanCpu.observe(value, 0L) } }
        finally { humanCpu.close() }
    }

     
    override fun start() {
        ApplicationWorkAdmission.requireOpen()
        check(!destroyed.get()) { "Terminal is closed" }
        NativePtyThreadPolicy.requireMainThread(Looper.getMainLooper().thread === Thread.currentThread())
        try {
            scope.ensureActive()
            if (directoryIdentity == null) {
                check(workDir.isDirectory || workDir.mkdirs()) { "Terminal working directory is unavailable" }
                directoryIdentity = WorkspaceDirectoryIdentity(workDir)
            }
            directoryIdentity?.requireCurrent()
            // Debugger-created terminals must start even before a TerminalView is attached.
            try {
                if (humanPtySession.emulator == null) {
                    TerminalRawOutputBridge.attach(humanPtySession, recording::append)
                    ExecutionDiagnostics.record(workDir, "Interactive terminal", workDir.absolutePath, launchSpec,
                        overrides = TerminalPtyEnvironment.variables + launchSpec.interactiveEnvironment)
                    humanPtySession.updateSize(80, 24)
                }
                humanPtySession.spawnedPid.also {
                    check(it > 0) { "Native terminal did not create a child process" }
                }
            } catch (failure: Exception) {
                throw NativePtyUnavailableException(failure)
            } catch (failure: LinkageError) {
                throw NativePtyUnavailableException(failure)
            }
            // Spawned PID is an informational receipt only; never persist or signal it.
            humanInputReady.complete(humanPtySession)
            flushPendingCommands()
        } catch (error: Throwable) {
            if (error is NativePtyUnavailableException) Log.w("DroideTerminal", "Native PTY startup failed; command mode may be used", error)
            runCatching { destroy() }.onFailure { cleanup -> if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun send(cmd: String) {
        if (activeAgentPty != null) { writeRaw(cmd + "\r"); return }
        try {
            ApplicationWorkAdmission.requireOpen()
            noteCommandInput()
            pendingCommands.send(cmd, humanPtySession.pid, humanPtySession::write)
        } catch (error: Exception) {
            activity.failed()
            appendLine("[terminal error] ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun writeRaw(text: String) {
        if (text.isEmpty()) return
        try {
            ApplicationWorkAdmission.requireOpen()
            check(!destroyed.get()) { "Terminal is closed; input was not sent" }
            activeAgentPty?.let { owned ->
                check(owned.terminal.pid > 0 && owned.terminal.isRunning) { "Interactive command has finished; input was not sent" }
                // All UI and Agent input shares a bounded, non-blocking admission queue.
                owned.terminal.write(text)
                return
            }
            if ('\n' in text || '\r' in text) noteCommandInput()
            pendingCommands.sendRaw(text, humanPtySession.pid, humanPtySession::write)
        } catch (error: Exception) {
            if (activeAgentPty == null) activity.failed()
            appendLine("[terminal input delivery could not be confirmed] ${error.message ?: error.javaClass.simpleName}. Check the terminal before retrying.")
        }
    }

    override fun setScreenUpdateListener(listener: (() -> Unit)?) {
        screenUpdateListener = listener
        if (listener != null && !destroyed.get()) flushPendingCommands()
    }

    override fun setBellListener(listener: (() -> Unit)?) {
        bellListener = listener
    }

    override fun captureTranscript(): String {
        NativePtyThreadPolicy.requireMainThread(Looper.getMainLooper().thread === Thread.currentThread())
        transcriptPublisher.flush()
        return _output.value
    }

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = execDelegate.execOnce(cmd, timeoutMs)
    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult =
        execDelegate.execStreaming(cmd, timeoutMs, onOutput)

    override val supportsCommandInput: Boolean get() = execDelegate.supportsCommandInput
    override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult =
        execDelegate.execStreamingWithInput(cmd, timeoutMs, input, onOutput)
    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = execDelegate.execArgv(argv, timeoutMs)

    override suspend fun execWorkspaceArgv(workspaceRoot: File, directory: File, command: List<String>, timeoutMs: Long): ExecResult =
        execDelegate.execWorkspaceArgv(workspaceRoot, directory, command, timeoutMs)

    override suspend fun execInteractivePty(command: String, timeoutMs: Long, input: ProcessInputBuffer?, onOutput: (String) -> Unit): ExecResult = coroutineScope {
        require(timeoutMs in 0..86_400_000L) { "Invalid process timeout" }
        require(command.isNotBlank() && command.length <= 32_000 && '\u0000' !in command) { "Invalid interactive command" }
        val commandJob = currentCoroutineContext().get(Job) ?: error("Interactive command has no coroutine owner")
        var run: AgentPtyRun? = null
        var stagedRelay: TerminalInputRelay<TermuxSession>? = null
        val screenOutput = InteractivePtyScreenOutput()
        try {
            withContext(Dispatchers.Main.immediate) {
                ApplicationWorkAdmission.requireOpen()
                check(!destroyed.get() && humanPtySession.isRunning && humanPtySession.pid > 0) { "Terminal is closed; no interactive command was launched" }
                val correlation = NativePtyToken.create()
                val token = ptyOwnership.acquire(activity.state.value.phase)
                var terminal: TermuxSession? = null
                try {
                    val completion = CompletableDeferred<NativePtyCleanupReceipt>()
                    val nativeCompletion = CompletableDeferred<NativePtyCleanupReceipt>()
                    val ready = CompletableDeferred<TermuxSession>()
                    val relay = createInputRelay(ready) { detail ->
                        completion.completeExceptionally(IllegalStateException(detail))
                        transcriptHandler.post { appendLine(detail) }
                    }
                    stagedRelay = relay
                    val agentClient = reportingClient(object : TerminalSessionClient by client {
                        override fun onTextChanged(changedSession: TermuxSession) {
                            if (!ptyOwnership.isOwner(token) || destroyed.get()) return
                            screenUpdateListener?.invoke()
                            transcriptPublisher.request()
                        }
                        override fun onTitleChanged(changedSession: TermuxSession) = Unit
                        override fun onSessionFinished(finishedSession: TermuxSession) {
                            val receipt = cleanupReceipt(finishedSession, relay)
                            if (nativeCompletion.complete(receipt)) nativeObservationVersion = Any()
                            if (receipt.processFailureReason in 0..1 && pendingNativeExits.remove(finishedSession) != null)
                                nativeObservationVersion = Any()
                            completion.complete(receipt)
                            Log.i("DroideTerminal", "Agent native PTY ${correlation.value}: ${receipt.description}")
                        }
                    })
                    directoryIdentity?.requireCurrent()
                    val cwd = TerminalCommandCwdPolicy.resolveHostCwd(workDir, workingDirectory.value, launchSpec.prefix.isEmpty())
                    val refreshed = LocalExecutionSubstrate.workspaceToolchainLaunchSpec(launchSpec, cwd, workDir) ?: launchSpec
                    val spec = TerminalGuestCwdPolicy.withObservedCwd(refreshed, workDir, workingDirectory.value)
                    val argv = spec.shellCommand(command, TerminalPtyEnvironment.variables + correlation.environment)
                    val pty = TermuxSession(argv.first(), cwd.absolutePath, argv.toTypedArray(), buildEnvironment(correlation.environment, includePrompt = false), 2_000, agentClient)
                    TerminalRawOutputBridge.attach(pty, recording::append)
                    ExecutionDiagnostics.record(workDir, "Agent interactive PTY", workingDirectory.value ?: cwd.absolutePath,
                        spec, overrides = TerminalPtyEnvironment.variables)
                    terminal = pty
                    nativeObservationVersion = Any()
                    pendingNativeExits[pty] = NativeExitOwner(pty, correlation, nativeCompletion)
                    installInputHandler(pty, relay)
                    val humanEmulator = humanPtySession.emulator
                    pty.updateSize(humanEmulator?.mColumns ?: 80, humanEmulator?.mRows ?: 24)
                    val spawnedPid = pty.spawnedPid
                    check(spawnedPid > 0) { "Interactive terminal did not create a child process" }
                    ready.complete(pty)
                    input?.bindPty(relay::send) { relay.send(byteArrayOf(0x04)) }
                    AgentPtyRun(token, pty, correlation, completion, commandJob, relay).also {
                        // Assign before crossing withContext's prompt-cancellation boundary:
                        // a cancellation after native spawn must still stop its native owner.
                        run = it
                        nativeObservationVersion = Any()
                        activeAgentPty = it
                        transcriptPublisher.cancelPending()
                        _displayedPtySession.value = pty
                        displayGeometry(pty)
                        screenUpdateListener?.invoke()
                    }
                } catch (error: Throwable) {
                    if (run?.token !== token) {
                        stagedRelay?.close(reportUnsent = true)
                        runCatching { terminal?.let { requestOwnedSessionStop(it, correlation) } }
                        terminal?.let { resizeReceipts.remove(it); pasteTransactions.remove(it) }
                        ptyOwnership.release(token)
                    }
                    throw error
                }
            }
            val owned = requireNotNull(run)
            suspend fun publishScreen() {
                val screen = withContext(Dispatchers.Main.immediate) {
                    owned.terminal.emulator?.let { TerminalTranscriptSnapshot.capture(it, 12_000) }.orEmpty()
                }
                screenOutput.capture(screen)?.let(onOutput)
            }
            suspend fun awaitExit(): NativePtyCleanupReceipt {
                while (!owned.completion.isCompleted) {
                    publishScreen()
                    delay(150)
                }
                publishScreen()
                return owned.completion.await()
            }
            val exit = if (timeoutMs == 0L) awaitExit() else withTimeoutOrNull(timeoutMs) { awaitExit() }
            val drainNotice = if (exit != null && !owned.terminal.isOutputComplete)
                "\n[Output incomplete: native PTY capture is incomplete]" else ""
            val cleanupNotice = exit?.description ?: NativePtyCleanupReceipt.STOP_NOTICE
            ExecResult(exit?.exitCode ?: -1, screenOutput.output + drainNotice + "\n" + cleanupNotice,
                timedOut = exit == null, outputIncomplete = !owned.terminal.isOutputComplete)
        } finally {
            input?.abort()
            stagedRelay?.close(reportUnsent = true)
            val owned = run
            if (owned != null) withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) {
                    runCatching { requestOwnedSessionStop(owned.terminal, owned.correlation) }
                    pasteTransactions.remove(owned.terminal)
                    if (ptyOwnership.release(owned.token) && activeAgentPty === owned) {
                        nativeObservationVersion = Any()
                        activeAgentPty = null
                        transcriptPublisher.cancelPending()
                        if (!destroyed.get()) {
                            _displayedPtySession.value = humanPtySession
                            resizeReceipts.remove(owned.terminal)
                            displayGeometry(humanPtySession)
                            transcriptPublisher.request()
                            screenUpdateListener?.invoke()
                        }
                    }
                }

            }
        }
    }

     
    override fun interrupt() {
        activeAgentPty?.let { owned ->
            owned.job.cancel(CancellationException("User stopped the visible Agent interactive command"))
            return
        }
        interruptHumanPty()
    }

    private fun interruptHumanPty() {
        if (!TerminalPtyInputDelivery.mayInterrupt(
                destroyed.get(), humanPtySession.pid, humanPtySession.isRunning,
            )) return
        // The process may exit between the running check and write. This is a
        // user's PTY Ctrl+C only; Agent cancellation never calls this method.
        try {
            humanInterruptPending.set(true)
            humanPtySession.write(byteArrayOf(0x03), 0, 1)
        } catch (_: Exception) {
            humanInterruptPending.set(false)
            activity.failed()
            appendLine("[terminal interrupt delivery could not be confirmed]")
        }
    }

    override fun clear() {
        transcriptPublisher.cancelPending()
        val reset = Runnable {
            if (!destroyed.get()) {
                if (nativePtySession.emulator != null) nativePtySession.reset()
                // reset can itself notify onTextChanged; invalidate that queued snapshot too.
                transcriptPublisher.clear()
            }
        }
        if (Looper.getMainLooper().thread === Thread.currentThread()) reset.run()
        else transcriptHandler.post(reset)
    }

    override suspend fun observeTerminalOwnership(): TerminalOwnershipObservation = withContext(Dispatchers.Main.immediate) {
        // These collections belong to Main. destroyed is separately atomic because destroy
        // may be requested off Main. No native property, relay or cleanup method is called.
        val version = nativeObservationVersion
        val wasDestroyed = destroyed.get()
        NativePtyOwnershipObservation.capture(humanExit,
            pendingNativeExits.values.map { it.completion }, exitNativeOwners.orEmpty().map { it.completion },
            wasDestroyed, validateOwner = {
                // Only volatile/atomic metadata here. Main-owned maps were copied above.
                check(nativeObservationVersion === version && destroyed.get() == wasDestroyed) {
                    "Native terminal ownership changed during review"
                }
            }).also { it.validate() }
    }

    override suspend fun stopAndAwaitForExit() {
        val stopFailure = withContext(Dispatchers.Main.immediate) {
            if (exitNativeOwners == null) {
                nativeObservationVersion = Any()
                exitNativeOwners = pendingNativeExits.values.toList()
            }
            runCatching { destroy() }.exceptionOrNull()
        }
        OwnedResourceCleanup.runSuspend(
            { execDelegate.stopAndAwaitForExit() },
            { withTimeoutOrNull(8_000) { exitAgentJob?.join(); true }
                ?: error("Agent terminal cleanup is still pending; its owner is retained") },
            {
                val owners = listOf(NativeExitOwner(humanPtySession, processToken, humanExit)) + exitNativeOwners.orEmpty()
                // Request every native owner before waiting for any callback.
                val requests = withContext(Dispatchers.Main.immediate) {
                    OwnedResourceCleanup.run(*owners.map { owned -> { requestOwnedSessionStop(owned.terminal, owned.correlation) } }.toTypedArray())
                }
                OwnedResourceCleanup.runSuspend(*owners.map { owned -> suspend {
                    val neverStarted = withContext(Dispatchers.Main.immediate) {
                        owned.terminal.pid == 0 && owned.terminal.emulator == null
                    }
                    if (!neverStarted) {
                        val receipt = withTimeoutOrNull(8_000) { owned.completion.await() }
                            ?: error("Native terminal stop acknowledgement is pending; its owner is retained")
                        check(receipt.processFailureReason in 0..1) {
                            "Native terminal cleanup is unconfirmed (reason ${receipt.processFailureReason}). Exit remains paused."
                        }
                    }
                } }.toTypedArray()).getOrThrow()
                requests.getOrThrow()
            },
            { stopFailure?.let { throw it } },
        ).getOrThrow()
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        nativeObservationVersion = Any()
        val clearGeometry = Runnable { pasteTransactions.clear(); resizeReceipts.clear(); _geometry.value = TerminalGeometrySnapshot() }
        if (Looper.getMainLooper().thread === Thread.currentThread()) clearGeometry.run()
        else transcriptHandler.post(clearGeometry)
        if (exitAgentJob == null) exitAgentJob = activeAgentPty?.job
        activeAgentPty?.job?.cancel(CancellationException("Terminal tab closed"))
        activity.closed()
        userActivityWatcher.cancel()
        humanCpu.close()
        transcriptPublisher.cancelPending()
        if (Looper.getMainLooper().thread === Thread.currentThread()) transcriptPublisher.close()
        else transcriptHandler.post { transcriptPublisher.close() }
        screenUpdateListener = null
        bellListener = null
        pendingCommands.close()
        humanInputRelay.close(reportUnsent = true)
        activeAgentPty?.inputRelay?.close(reportUnsent = true)
        var failure = runCatching { execDelegate.destroy() }.exceptionOrNull()
        try {
            requestOwnedSessionStop(humanPtySession, processToken)
        } catch (cleanup: Throwable) {
            val prior = failure
            if (prior == null) failure = cleanup else if (cleanup !== prior) prior.addSuppressed(cleanup)
        }
        failure?.let { throw it }
    }

    private fun flushPendingCommands() {
        TerminalPtyInputDelivery.flush(
            pendingCommands,
            humanPtySession.pid,
            humanPtySession::write,
        ) {
            activity.failed()
            appendLine("[terminal input delivery could not be confirmed; check before retrying]")
        }
    }

    private fun createInputRelay(
        connection: CompletableDeferred<TermuxSession>,
        onError: (String) -> Unit,
    ): TerminalInputRelay<TermuxSession> = TerminalInputRelay(scope, connection, { terminal, bytes ->
        check(terminal.writeToPty(bytes, 0, bytes.size)) {
            "PTY closed during input delivery; input may be partial and will not be replayed"
        }
    }, onError)

    private fun installInputHandler(terminal: TermuxSession, relay: TerminalInputRelay<TermuxSession>) {
        val transaction = TerminalPasteTransaction(relay::send, ::showInputNotice)
        pasteTransactions[terminal] = transaction
        terminal.setInputHandler(object : TermuxSession.InputHandler {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                // Termux reuses its UTF-8 input buffer. The relay owns an immutable copy.
                if (ApplicationWorkAdmission.isOpen && !destroyed.get()) transaction.write(data, offset, count)
                else relay.close(reportUnsent = true)
            }

            override fun onPtyWriteFailure(reason: Int) {
                // Java publishes its sticky receipt and releases the writer lane first.
                // This only closes input; the persistent human shell is never stopped here.
                val detail = NativePtyCleanupReceipt.inputFailureNotice(reason)
                relay.fail(detail)
                Log.w("DroideTerminal", detail)
            }
        })
    }

    private fun cleanupReceipt(terminal: TermuxSession, relay: TerminalInputRelay<TermuxSession>): NativePtyCleanupReceipt {
        // Close admission and freeze queued/in-flight/prior-failure state before
        // publishing completion. No join, UI wait, observer dependency or replay.
        val relayFailure = relay.finishInput()
        return NativePtyCleanupReceipt(
            terminal.exitStatus, terminal.processExitFailureReason, terminal.inputWriteFailureReason,
        ).withRelayInputFailure(relayFailure)
    }

    private fun requestOwnedSessionStop(terminal: TermuxSession, correlation: NativePtyToken) {
        // isRunning may be true before initial spawn; the historical PID only qualifies this notice.
        val wasRunning = terminal.spawnedPid > 0 && terminal.isRunning
        terminal.finishIfRunning()
        if (wasRunning) {
            Log.w("DroideTerminal", "Native PTY ${correlation.value}: ${NativePtyCleanupReceipt.STOP_NOTICE}")
            transcriptHandler.post { appendLine(NativePtyCleanupReceipt.STOP_NOTICE) }
        }
    }

    override fun copySelection(owner: TermuxSession, text: String): Boolean {
        NativePtyThreadPolicy.requireMainThread(Looper.getMainLooper().thread === Thread.currentThread())
        if (destroyed.get() || owner !== nativePtySession) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: error("Clipboard unavailable")
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            showInputNotice("Copy failed; the clipboard was not updated. The selection is available to retry.")
            false
        }
    }

    private fun showInputNotice(detail: String) {
        val notice = Runnable {
            appendLine(detail)
            Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
        }
        if (Looper.getMainLooper().thread === Thread.currentThread()) notice.run()
        else transcriptHandler.post(notice)
    }

    private fun appendLine(text: String) {
        val combined = _output.value + text + "\n"
        _output.value = combined.substring(terminalTextTailStart(combined, outputCap))
    }

    private fun buildEnvironment(correlationEnvironment: Map<String, String> = processToken.environment, includePrompt: Boolean = true): Array<String> {
        val home = File(context.filesDir, "terminal-home").apply { mkdirs() }
        val path = System.getenv("PATH")?.takeIf { it.isNotBlank() }
            ?: "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin"
        val base = arrayOf(
            "PATH=$path",
            "HOME=${home.absolutePath}",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "LANG=en_US.UTF-8",
            "SHELL=/system/bin/sh",
        ) + TerminalPtyEnvironment.variables.map { (key, value) -> "$key=$value" }
        val prompts = if (includePrompt) promptEnvironment else emptyMap()
        val overridden = launchSpec.environment.keys + prompts.keys + correlationEnvironment.keys
        return base.filterNot { it.substringBefore('=') in overridden }.toTypedArray() +
            launchSpec.environment.map { (key, value) -> "$key=$value" } +
            prompts.map { (key, value) -> "$key=$value" } +
            correlationEnvironment.map { (key, value) -> "$key=$value" }
    }
}

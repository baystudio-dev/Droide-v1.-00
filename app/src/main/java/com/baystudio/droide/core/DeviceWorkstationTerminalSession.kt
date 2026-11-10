package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbPtyShellSession
import com.flyfishxu.kadb.shell.AdbShellPacket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope

interface DevicePtySessionHandle : ITerminalSession, TerminalMetadataSession, TerminalWorkingDirectorySession {
    val terminalEmulator: DeviceTerminalEmulator
    val profileLabel: String
    val status: StateFlow<String>
    fun writeRaw(text: String)
    fun sendKeyCode(keyCode: Int, modifiers: Int = 0)
    fun resize(columns: Int, rows: Int)
    suspend fun refreshWorkspace()
}

class DeviceWorkstationTerminalSession(
    private val bridge: DeviceBridgeManager,
    private val androidDevelopment: AndroidDevelopmentManager,
    private val scope: CoroutineScope,
) : DevicePtySessionHandle, TerminalCommandActivitySession, WorkspaceCommandSession {
    private val _output = MutableStateFlow("")
    override val output: StateFlow<String> = _output.asStateFlow()
    override val profileLabel: String = "Device Workstation PTY • xterm-256color"
    private val _status = MutableStateFlow("Disconnected")
    override val status: StateFlow<String> = _status.asStateFlow()

    private val sessionRef = AtomicReference<AdbPtyShellSession?>(null)
    private val destroyed = AtomicBoolean(false)
    private val configRef = AtomicReference<AndroidDevelopmentManager.InteractiveShellConfig?>(null)
    private val resizeAuthority = TerminalResizeAuthority()
    private data class AppliedResize(
        val session: AdbPtyShellSession,
        val geometry: TerminalResizeAuthority.Geometry,
    )
    private val appliedResizeRef = AtomicReference<AppliedResize?>(null)
    private val writeLock = Any()
    private var readerJob: Job? = null
    private val ownedJobs = mutableSetOf<Job>()
    @Volatile private var cleanupFailure: Throwable? = null
    @Volatile private var inputRelay: TerminalInputRelay<AdbPtyShellSession>? = null
    private var resizeJob: Job? = null
    @Volatile private var transcriptPublisher: TerminalTranscriptPublisher? = null

    override val terminalEmulator = resizeAuthority.latest().let { initial ->
        DeviceTerminalEmulator(::enqueuePtyBytes, initial.columns, initial.rows, pasteAdmission = {
            val owner = inputRelay
            if (owner == null) null else { bytes: ByteArray ->
                // Exact captured relay, including across start/reconnect on another thread.
                if (destroyed.get() || !ApplicationWorkAdmission.isOpen || inputRelay !== owner) false
                else owner.send(bytes)
            }
        })
    }
    override val terminalTitle: StateFlow<String?> = terminalEmulator.title
    override val workingDirectory: StateFlow<String?> = terminalEmulator.workingDirectory
    override val commandActivity = terminalEmulator.commandActivity
    override fun noteCommandInput() = terminalEmulator.commandTracker.submitted()

    @Synchronized override fun start() {
        ApplicationWorkAdmission.requireOpen()
        check(!destroyed.get()) { "Device terminal is closed" }
        if (readerJob?.isActive == true) return
        transcriptPublisher?.close()
        val publisher = createTranscriptPublisher()
        transcriptPublisher = publisher
        val connection = CompletableDeferred<AdbPtyShellSession>()
        inputRelay?.close(reportUnsent = true)
        val relay = TerminalInputRelay(scope, connection,
            write = { pty, bytes -> synchronized(writeLock) { pty.write(bytes) } },
            onError = { message -> _status.value = message; terminalEmulator.commandTracker.failed() })
        inputRelay = relay
        terminalEmulator.commandTracker.resetForLaunch()
        if (resizeJob?.isActive != true) {
            resizeJob = track(scope.launch(Dispatchers.IO) {
                while (true) {
                    val geometry = resizeAuthority.receive()
                    applyRemoteResize(geometry)
                }
            })
        }
        readerJob = track(scope.launch(Dispatchers.IO) {
            var ownedSession: AdbPtyShellSession? = null
            _status.value = "Preparing Device Workstation…"
            try {
                val config = androidDevelopment.prepareInteractiveShell()
                currentCoroutineContext().ensureActive()
                configRef.set(config)
                terminalEmulator.initializeWorkingDirectory(config.remoteWorkspace)
                val promptDirectory = DeviceBridgeManager.remoteRoot() + "/terminal-presentation"
                val promptFile = "$promptDirectory/prompt-v1.sh"
                val quote = DeviceBridgeManager::shellQuote
                val prepare = bridge.shell("set -eu; test ! -L ${quote(promptDirectory)}; mkdir -p ${quote(promptDirectory)}; " +
                    "test ! -L ${quote(promptFile)}; printf '%s' ${quote(TerminalPromptPresentation.androidProfile())} > ${quote(promptFile)}")
                check(prepare.exitCode == 0) { "Terminal prompt setup failed: ${prepare.combined.takeLast(1_000)}" }
                val command = config.shellPrefix() + "export ENV=${quote(promptFile)}; " +
                    "export PS1=${quote(TerminalPromptPresentation.COMPACT_PROMPT)}; " +
                    terminalEmulator.commandTracker.environment.entries.joinToString("") { (key, value) -> "export $key=${quote(value)}; " } +
                    "exec /system/bin/sh -i"
                currentCoroutineContext().ensureActive()
                val session = bridge.openPtyShell(command = command, term = "xterm-256color").also {
                    ownedSession = it
                    sessionRef.set(it)
                }
                currentCoroutineContext().ensureActive()
                val initialGeometry = TerminalResizeAuthority.Geometry(terminalEmulator.columns(), terminalEmulator.rows())
                resizeAuthority.offer(initialGeometry.columns, initialGeometry.rows)
                session.resize(rows = initialGeometry.rows, cols = initialGeometry.columns)
                appliedResizeRef.set(AppliedResize(session, initialGeometry))
                connection.complete(session)

                resizeAuthority.signalLatest()
                _status.value = "Ready"
                session.use { pty ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        when (val packet = pty.read()) {
                            is AdbShellPacket.StdOut -> appendPtyBytes(packet.payload, publisher)
                            is AdbShellPacket.StdError -> appendPtyBytes(packet.payload, publisher)
                            is AdbShellPacket.Exit -> {
                                val code = packet.payload.firstOrNull()?.toUByte()?.toInt() ?: -1
                                _status.value = "Process exited · $code"
                                terminalEmulator.commandTracker.closed(code.takeIf { it >= 0 })
                                break
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                connection.cancel(cancelled)
                throw cancelled
            } catch (error: Throwable) {
                connection.completeExceptionally(error)
                terminalEmulator.commandTracker.failed()
                _status.value = "Error · ${error.message ?: error.javaClass.simpleName}"
            } finally {
                connection.completeExceptionally(IllegalStateException("Device terminal connection closed"))
                relay.close(reportUnsent = true)
                ownedSession?.let { owned ->
                    if (sessionRef.compareAndSet(owned, null)) appliedResizeRef.set(null)
                    runCatching { owned.close() }.onFailure { cleanupFailure = it }
                }
                publisher.finish()
            }
        })
    }

    @Synchronized private fun track(job: Job): Job {
        ownedJobs += job
        job.invokeOnCompletion { synchronized(this) { ownedJobs -= job } }
        return job
    }

    private suspend fun <T> ownedExecution(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        synchronized(this@DeviceWorkstationTerminalSession) {
            ApplicationWorkAdmission.requireOpen()
            check(!destroyed.get()) { "Device terminal is closed" }
            ownedJobs += job
        }
        try { block() } finally { synchronized(this@DeviceWorkstationTerminalSession) { ownedJobs -= job } }
    }

    override fun send(cmd: String) {
        if (cmd.isBlank()) return
        writeRaw(cmd + "\n")
    }

    override fun writeRaw(text: String) {
        if (text.isEmpty()) return
        if ('\n' in text || '\r' in text) noteCommandInput()
        enqueuePtyBytes(text.encodeToByteArray())
    }

    override fun sendKeyCode(keyCode: Int, modifiers: Int) {
        terminalEmulator.keySequence(keyCode, modifiers)?.let(::writeRaw)
    }

    override fun resize(columns: Int, rows: Int) {
        val geometry = TerminalResizeAuthority.normalize(columns, rows)
        terminalEmulator.resize(geometry.columns, geometry.rows)
        // The remote PTY and renderer must share the geometry accepted by the memory policy.
        resizeAuthority.offer(terminalEmulator.columns(), terminalEmulator.rows())
    }

    private fun applyRemoteResize(geometry: TerminalResizeAuthority.Geometry) {
        val pty = sessionRef.get() ?: return
        val applied = appliedResizeRef.get()
        if (applied?.session === pty && applied.geometry == geometry) return
        runCatching {
            synchronized(writeLock) { pty.resize(rows = geometry.rows, cols = geometry.columns) }
        }.onSuccess {
            if (sessionRef.get() === pty) appliedResizeRef.set(AppliedResize(pty, geometry))
        }.onFailure { error ->
            if (sessionRef.get() === pty) {
                _status.value = "Resize error · ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    override suspend fun refreshWorkspace() = ownedExecution {
        val config = androidDevelopment.prepareInteractiveShell(syncProject = true)
        synchronized(this) { if (!destroyed.get()) configRef.set(config) }
    }

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult =
        execStreaming(cmd, timeoutMs) { }

    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = ownedExecution {
        val config = ensureConfig()
        val lease = RemoteProcessLease.create("terminal-exec")
        val execute: suspend () -> BridgeShellResult = {
            bridge.shellStreaming(lease.wrap(config.shellPrefix() + cmd), maxOutputBytes = 1_500_000) { text, _ ->
                runCatching { onOutput(text) }
            }
        }
        try {
            val result = withContext(Dispatchers.IO) {
                if (timeoutMs > 0L) kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { execute() } else execute()
            }
            if (result == null) {
                terminateExecLease(lease)
                ExecResult(-1, "[TIMEOUT ${timeoutMs}ms]", true)
            } else {
                terminateExecLease(lease)
                ExecResult(result.exitCode, result.combined.takeLast(20_000), false)
            }
        } catch (cancelled: CancellationException) {
            terminateExecLease(lease)
            throw cancelled
        } catch (error: Throwable) {
            terminateExecLease(lease)
            throw error
        }
    }

    private suspend fun terminateExecLease(lease: RemoteProcessLease) = withContext(NonCancellable + Dispatchers.IO) {
        try {
            check(bridge.state.value.connected != null) { "Remote terminal cleanup transport is unavailable" }
            check(bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384).exitCode == 0) {
                "Remote terminal cleanup request failed"
            }
        } catch (failure: Throwable) {
            cleanupFailure = failure
            throw failure
        }
    }

    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        val literal = argv.joinToString(" ") { DeviceBridgeManager.shellQuote(it) }
        return execOnce(literal, timeoutMs)
    }

    override suspend fun execWorkspaceArgv(workspaceRoot: java.io.File, directory: java.io.File,
                                          command: List<String>, timeoutMs: Long): ExecResult {
        val config = ensureConfig()
        return execArgv(WorkspaceCommandInvocation.remoteArgv(workspaceRoot, directory,
            config.remoteWorkspace, command, "/system/bin/sh"), timeoutMs)
    }

    override fun interrupt() {
        writeRaw("\u0003")
    }

    override fun clear() {
        terminalEmulator.resetAndClear()
        transcriptPublisher?.clear() ?: run { _output.value = "" }
    }

    private var exitJobs: List<Job>? = null
    override suspend fun stopAndAwaitForExit() {
        synchronized(this) {
            if (exitJobs == null) exitJobs = ownedJobs.toList()
            destroy()
        }
        exitJobs.orEmpty().forEach { it.join() }
        cleanupFailure?.let { throw IllegalStateException("Device terminal cleanup is incomplete", it) }
        // This acknowledges local ADB transport shutdown, never remote descendant termination.
    }

    @Synchronized override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        if (exitJobs == null) exitJobs = ownedJobs.toList()
        ownedJobs.toList().forEach { it.cancel(CancellationException("Device terminal closed")) }
        readerJob?.cancel()
        readerJob = null
        inputRelay?.close()
        inputRelay = null
        resizeJob?.cancel()
        resizeJob = null
        terminalEmulator.commandTracker.closed()
        resizeAuthority.close()
        configRef.set(null)
        terminalEmulator.setUiListener(null)
        transcriptPublisher?.close()
        sessionRef.getAndSet(null)?.let { runCatching { it.close() }.onFailure { cleanupFailure = it } }
    }

    private suspend fun ensureConfig(): AndroidDevelopmentManager.InteractiveShellConfig {
        configRef.get()?.let { return it }
        return androidDevelopment.prepareInteractiveShell().also(configRef::set)
    }

    private fun enqueuePtyBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (destroyed.get() || !ApplicationWorkAdmission.isOpen) {
            _status.value = "Terminal is closed; input was not sent"
            return
        }
        val relay = inputRelay
        if (relay == null) {
            _status.value = "Terminal is unavailable; input was not sent"
            terminalEmulator.commandTracker.failed()
        } else relay.send(bytes)
    }

    private suspend fun appendPtyBytes(bytes: ByteArray, publisher: TerminalTranscriptPublisher) {
        if (bytes.isEmpty() || destroyed.get() || transcriptPublisher !== publisher) return
        val readerContext = currentCoroutineContext()
        terminalEmulator.append(bytes) { readerContext.ensureActive() }
        publisher.request()
    }

    private fun createTranscriptPublisher(): TerminalTranscriptPublisher {
        lateinit var publisher: TerminalTranscriptPublisher
        publisher = TerminalTranscriptPublisher(
            schedule = { interval, action ->
                val job = scope.launch(Dispatchers.IO) { delay(interval); action() }
                val cancel = { job.cancel() }
                cancel
            },
            capture = { terminalEmulator.snapshotText() },
            publish = { if (transcriptPublisher === publisher) _output.value = it },
        )
        return publisher
    }
}

package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbShellPacket
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext

 
enum class ProcessExecutionScope { LOCAL_LINUX_ARM64, DEVICE_ADB }

// It deliberately exposes byte streams, not shell strings, so protocol traffic cannot be reinterpreted by a command shell.


interface HostedStdioProcess : Closeable {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isAlive: Boolean
    val exitCode: Int?
    /** Wait for the owned process and its host cleanup. close() alone only requests stop. */
    suspend fun awaitExit(): Int
}


data class ProcessResourceLimits(
    val cpuSeconds: Int = 120,
    val fileSizeKiB: Int = 64 * 1024,
    val openFiles: Int = 256,
    val processes: Int = 64,
    val virtualMemoryKiB: Int = 768 * 1024,
) {
    fun validate() {
        require(cpuSeconds in 5..3600) { "Invalid CPU limit" }
        require(fileSizeKiB in 1024..1024 * 1024) { "Invalid file-size limit" }
        require(openFiles in 32..4096) { "Invalid open-file limit" }
        require(processes in 8..512) { "Invalid process-count limit" }
        require(virtualMemoryKiB in 128 * 1024..4 * 1024 * 1024) { "Invalid virtual-memory limit" }
    }
}

 
interface StdioProcessHost {
    val executionScope: ProcessExecutionScope
    suspend fun resolveExecutable(command: String): String?
    suspend fun start(
        argv: List<String>,
        environment: Map<String, String> = emptyMap(),
        resourceLimits: ProcessResourceLimits? = null,
    ): HostedStdioProcess
    suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String> = emptyMap(), remoteCwd: String): HostedStdioProcess {
        require(remoteCwd == pathMapper()?.remoteRoot) { "Unsupported process working directory" }
        return start(argv, environment)
    }
    /** Additive bounded-start seam. Existing hosts never silently discard explicit limits. */
    suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String,
        resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
        require(resourceLimits == null) { "This host does not support workspace process resource limits" }
        return startInWorkspace(argv, environment, remoteCwd)
    }
    /** ELP guest metadata only. Unsupported hosts never discover tools by executing them. */
    suspend fun inspectElpSelection(workspaceRoot: String, rootPath: String, deadline: ElpDeadline): ElpSelectionInspection? = null
    suspend fun observeElpPaths(inspection: ElpSelectionInspection, guestPaths: List<String>, deadline: ElpDeadline): Map<String, ElpObservedFile> =
        throw UnsupportedOperationException("This host cannot observe the ELP guest filesystem")
    suspend fun inspectElpToml(request: ElpMetadataInspectionRequest, permit: ElpMetadataInspectionPermit,
        deadline: ElpDeadline): ElpMetadataInspectionResult =
        throw UnsupportedOperationException("This host cannot own ELP metadata inspection")
    suspend fun reviewedElpLaunchIdentity(resolvedExecutable: String, workspaceRoot: String, deadline: ElpDeadline): ElpManagedLaunchIdentity? = null
    suspend fun prepareReviewedElpOwner(identity: ElpManagedLaunchIdentity, request: ElpOwnerRequest,
        deadline: ElpDeadline): ElpPreparedOwner = throw UnsupportedOperationException("This host cannot own selected ELP analysis")
    suspend fun startReviewedElp(owner: ElpPreparedOwner, deadline: ElpDeadline,
        admitStart: (() -> ElpHostedProcess) -> ElpHostedProcess): ElpHostedProcess =
        throw UnsupportedOperationException("This host cannot protect ELP native process creation")
    /** Exact captured-source GraphQL route. Unsupported hosts never fall back to generic launch. */
    suspend fun reviewedGraphqlLaunchIdentity(resolvedExecutable: String, workspaceRoot: String, deadline: GraphqlDeadline): GraphqlManagedLaunchIdentity? = null
    suspend fun prepareReviewedGraphqlOwner(identity: GraphqlManagedLaunchIdentity, request: GraphqlOwnerRequest, deadline: GraphqlDeadline): GraphqlPreparedOwner =
        throw UnsupportedOperationException("This host cannot protect a reviewed GraphQL owner")
    suspend fun startReviewedGraphql(owner: GraphqlPreparedOwner, deadline: GraphqlDeadline,
        admitStart: (() -> GraphqlHostedProcess) -> GraphqlHostedProcess): GraphqlHostedProcess =
        throw UnsupportedOperationException("This host cannot protect reviewed GraphQL analysis")
    /** Exact TOML library route. Unsupported hosts never downgrade into generic process creation. */
    suspend fun reviewedTomlLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):TomlManagedLaunchIdentity? = null
    suspend fun prepareReviewedTomlOwner(identity:TomlManagedLaunchIdentity,request:TomlOwnerRequest):TomlPreparedOwner =
        throw UnsupportedOperationException("This host cannot protect a reviewed TOML owner")
    suspend fun startReviewedTomlInWorkspace(identity:TomlManagedLaunchIdentity,owner:TomlPreparedOwner,
        admitStart:(()->TomlHostedProcess)->TomlHostedProcess):TomlHostedProcess =
        throw UnsupportedOperationException("This host cannot protect reviewed TOML analysis")
    /** Metadata only; unsupported hosts cannot silently run discovery or use a generic launch. */
    suspend fun inspectCSharpSelection(workspaceRoot: String, rootPath: String, solutionPath: String?): CSharpSelectionInspection? = null
    suspend fun reviewedCSharpLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): CSharpManagedLaunchIdentity? = null
    suspend fun prepareReviewedCSharpOwner(identity: CSharpManagedLaunchIdentity, request: CSharpOwnerRequest): CSharpPreparedOwner =
        throw UnsupportedOperationException("This host cannot protect a reviewed C# owner")
    suspend fun startReviewedCSharpInWorkspace(identity: CSharpManagedLaunchIdentity, owner: CSharpPreparedOwner,
        admitStart: (() -> CSharpHostedProcess) -> CSharpHostedProcess): CSharpHostedProcess =
        throw UnsupportedOperationException("This host cannot protect reviewed C# analysis")
    /** Exact private Vue lane. Unsupported hosts cannot fall back to generic process creation. */
    suspend fun reviewedVueLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): VueManagedLaunchIdentity? = null
    suspend fun prepareReviewedVueOwner(identity: VueManagedLaunchIdentity, request: VueOwnerRequest): VuePreparedOwner =
        throw UnsupportedOperationException("This host cannot protect a reviewed Vue owner")
    suspend fun startReviewedVueInWorkspace(identity: VueManagedLaunchIdentity, owner: VuePreparedOwner,
        admitStart: (() -> VueHostedProcess) -> VueHostedProcess): VueHostedProcess =
        throw UnsupportedOperationException("This host cannot protect reviewed Vue paired analysis")
    /** Exact private Nim lane. Unsupported hosts cannot fall back to generic process creation. */
    suspend fun reviewedNimLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): NimManagedLaunchIdentity? = null
    suspend fun prepareReviewedNimOwner(identity: NimManagedLaunchIdentity, request: NimOwnerRequest): NimPreparedOwner =
        throw UnsupportedOperationException("This host cannot protect a reviewed Nim owner")
    suspend fun startReviewedNimInWorkspace(identity: NimManagedLaunchIdentity, owner: NimPreparedOwner,
        admitStart: (() -> NimHostedProcess) -> NimHostedProcess): NimHostedProcess =
        throw UnsupportedOperationException("This host cannot protect reviewed Nim fixed-toolchain analysis")
    /** CMake-only read-only selection context; never prepares a runtime or runs discovery. */
    suspend fun cmakeSelectionContext(workspaceRoot: String): CMakeSelectionContext? = null
    suspend fun reviewedCMakeLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): CMakeManagedLaunchIdentity? = null
    suspend fun prepareReviewedCMakeOwner(identity: CMakeManagedLaunchIdentity, request: CMakeOwnerRequest): CMakePreparedOwner =
        throw UnsupportedOperationException("This host cannot protect a reviewed CMake owner")
    suspend fun startReviewedCMakeInWorkspace(identity: CMakeManagedLaunchIdentity, owner: CMakePreparedOwner,
        admitStart: (() -> CMakeHostedProcess) -> CMakeHostedProcess): CMakeHostedProcess =
        throw UnsupportedOperationException("This host cannot protect a reviewed CMake process group")
    /** Sqruff-only creation boundary. Unsupported hosts cannot downgrade a reviewed launch. */
    suspend fun startReviewedSqruffInWorkspace(identity: SqruffManagedLaunchIdentity, argv: List<String>,
        environment: Map<String, String>, remoteCwd: String, resourceLimits: ProcessResourceLimits,
        admitStart: (() -> HostedStdioProcess) -> HostedStdioProcess): HostedStdioProcess =
        throw UnsupportedOperationException("This host does not support reviewed Sqruff process creation")
    /** Byte-only managed provenance; custom commands and other hosts have no reviewed pin identity. */
    suspend fun reviewedSqruffLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): SqruffManagedLaunchIdentity? = null
    /** Metadata-only query; a returned generation is not permission to execute unproved bytes. */
    suspend fun reviewedPowerShellLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): PowerShellManagedLaunchIdentity? = null
    /** Unsupported hosts must not fall back to an unprotected generic start. */
    suspend fun startReviewedPowerShellInWorkspace(identity: PowerShellManagedLaunchIdentity, argv: List<String>,
        environment: Map<String, String>, remoteCwd: String,
        admitStart: (() -> HostedStdioProcess) -> HostedStdioProcess): HostedStdioProcess =
        throw UnsupportedOperationException("This host does not support reviewed PowerShell process creation")
    /** Svelte-only package proof. Unsupported/custom hosts remain provenance-unverified. */
    suspend fun reviewedSvelteLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): SvelteManagedLaunchIdentity? = null
    suspend fun startReviewedSvelteInWorkspace(identity: SvelteManagedLaunchIdentity, argv: List<String>,
        environment: Map<String, String>, remoteCwd: String,
        admitStart: (() -> HostedStdioProcess) -> HostedStdioProcess): HostedStdioProcess =
        throw UnsupportedOperationException("This host does not support reviewed Svelte process creation")
    suspend fun pathMapper(): WorkspacePathMapper? = null
}


class DeviceWorkstationProcessHost(
    private val bridge: DeviceBridgeManager,
    private val development: AndroidDevelopmentManager,
    private val localRoot: File,
    private val scope: CoroutineScope,
) : StdioProcessHost {
    override val executionScope: ProcessExecutionScope = ProcessExecutionScope.DEVICE_ADB

    @Volatile private var prepared: AndroidDevelopmentManager.InteractiveShellConfig? = null

    private suspend fun prepare(syncProject: Boolean): AndroidDevelopmentManager.InteractiveShellConfig {
        val next = development.prepareInteractiveShell(syncProject = syncProject)
        prepared = next
        return next
    }

    override suspend fun pathMapper(): WorkspacePathMapper {
        val config = prepared ?: prepare(syncProject = false)
        return WorkspacePathMapper(localRoot, config.remoteWorkspace)
    }

    suspend fun refreshWorkspace(): WorkspacePathMapper {
        val config = prepare(syncProject = true)
        return WorkspacePathMapper(localRoot, config.remoteWorkspace)
    }

    override suspend fun resolveExecutable(command: String): String? {
        ProcessSecurityPolicy.requireExecutableName(command)
        val config = prepare(syncProject = false)
        return DeviceWorkstationExecutableProbe.resolve(bridge, config, command)
    }

    override suspend fun start(
        argv: List<String>,
        environment: Map<String, String>,
        resourceLimits: ProcessResourceLimits?,
    ): HostedStdioProcess {
        val config = prepare(syncProject = true)
        return startRemote(argv, environment, config, config.remoteWorkspace, resourceLimits)
    }

     
    override suspend fun startInWorkspace(
        argv: List<String>,
        environment: Map<String, String>,
        remoteCwd: String,
    ): HostedStdioProcess {
        val config = prepare(syncProject = false)
        val root = config.remoteWorkspace.trimEnd('/')
        require(remoteCwd == root || remoteCwd.startsWith("$root/")) { "Remote process cwd escaped the workspace" }
        DeviceBridgeManager.requireSafeRemotePath(remoteCwd)
        return startRemote(argv, environment, config, remoteCwd, null)
    }

    private suspend fun startRemote(
        argv: List<String>,
        environment: Map<String, String>,
        config: AndroidDevelopmentManager.InteractiveShellConfig,
        remoteCwd: String,
        resourceLimits: ProcessResourceLimits?,
    ): HostedStdioProcess {
        val epoch = ApplicationWorkAdmission.requireOpen()
        ProcessSecurityPolicy.validateArgv(argv)
        ProcessSecurityPolicy.validateEnvironment(environment)
        resourceLimits?.validate()
        require(ProcessSecurityPolicy.isAllowedRemoteExecutable(argv.first(), DeviceBridgeManager.remoteRoot())) {
            "Remote executable is outside Droide-managed/system roots"
        }
        val command = buildString {
            append(config.shellPrefix())
            if (remoteCwd != config.remoteWorkspace) {
                append("cd ").append(DeviceBridgeManager.shellQuote(remoteCwd)).append("; ")
            }
            environment.forEach { (key, value) ->
                append("export ").append(key).append('=').append(DeviceBridgeManager.shellQuote(value)).append("; ")
            }
            resourceLimits?.let { limits ->
                append("/system/bin/toybox ulimit -P \$\$ -Sc 0 && ")
                append("/system/bin/toybox ulimit -P \$\$ -St ").append(limits.cpuSeconds).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Sf ").append(limits.fileSizeKiB).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Sn ").append(limits.openFiles).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Su ").append(limits.processes).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Sv ").append(limits.virtualMemoryKiB).append(" && ")
            }
            append("exec ")
            append(argv.joinToString(" ") { DeviceBridgeManager.shellQuote(it) })
        }
        val lease = RemoteProcessLease.create("stdio-protocol")
        val stream = bridge.openRawShell(lease.wrap(command))
        val process = RemoteHostedStdioProcess(stream, scope) { _ ->
            

            scope.async(NonCancellable + Dispatchers.IO) {
                check(bridge.state.value.connected != null) { "Remote process cleanup transport is unavailable" }
                val result = bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384)
                check(result.exitCode == 0) { "Remote process cleanup request failed" }
                // This confirms the owned cleanup command, not arbitrary remote descendants.
            }
        }
        kotlinx.coroutines.currentCoroutineContext()[SessionHostedProcessOwner]?.retain(process)
        return ExitHostedProcessRegistry.admit(process, epoch)
    }
}

internal interface RemoteProcessChannel : Closeable {
    fun read(): AdbShellPacket
    fun write(bytes: ByteArray)
    fun closeStdin()
}

internal class RemoteHostedStdioProcess(
    private val stream: RemoteProcessChannel,
    scope: CoroutineScope,
    private val onFinished: (terminateRemote: Boolean) -> Deferred<Unit>,
) : HostedStdioProcess {
    constructor(stream: com.flyfishxu.kadb.shell.AdbShellStream, scope: CoroutineScope,
        onFinished: (Boolean) -> Deferred<Unit>) : this(object : RemoteProcessChannel {
        override fun read() = stream.read()
        override fun write(bytes: ByteArray) = stream.write(bytes)
        override fun closeStdin() = stream.closeStdin()
        override fun close() = stream.close()
    }, scope, onFinished)

    private val alive = AtomicBoolean(true)
    private val stopRequested = AtomicBoolean(false)
    private val sawRemoteExit = AtomicBoolean(false)
    private val cleanupDispatched = AtomicBoolean(false)
    private val cleanupCompletion = kotlinx.coroutines.CompletableDeferred<Deferred<Unit>>()
    private val transportCompletion = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val transportClosing = AtomicBoolean(false)
    private val stopCompletion = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val exit = kotlinx.coroutines.CompletableDeferred<Int>()
    @Volatile private var observedExitCode: Int? = null
    private val stdoutPipe = PipedInputStream(128 * 1024)
    private val stdoutWriter = PipedOutputStream(stdoutPipe)
    private val stderrPipe = PipedInputStream(64 * 1024)
    private val stderrWriter = PipedOutputStream(stderrPipe)
    // A childless cancellation sentinel is notified without waiting for a blocked
    // reader child. It is completed/detached when the reader actually finishes.
    private val parentCancellation = Job(scope.coroutineContext[Job])
    private val readJob: Job

    override val stdin: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(alive.get() && !stopRequested.get()) { "Remote process is closed" }
            require(off >= 0 && len >= 0 && off + len <= b.size) { "Invalid write range" }
            if (len == 0) return
            stream.write(b.copyOfRange(off, off + len))
        }
        override fun flush() = Unit
        override fun close() {
            if (alive.get()) runCatching { stream.closeStdin() }
        }
    }
    override val stdout: InputStream get() = stdoutPipe
    override val stderr: InputStream get() = stderrPipe
    override val isAlive: Boolean get() = alive.get()
    override val exitCode: Int? get() = observedExitCode
    override suspend fun awaitExit(): Int {
        var code = 0
        OwnedResourceCleanup.runSuspend(
            { code = exit.await() },
            { cleanupCompletion.await().await() },
            { readJob.join() },
            { transportCompletion.await() },
            { if (stopRequested.get()) stopCompletion.await() },
        ).getOrThrow()
        return code
    }

    init {
        readJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && alive.get()) {
                    when (val packet = stream.read()) {
                        is AdbShellPacket.StdOut -> forwardOutput(stdoutWriter, packet.payload)
                        is AdbShellPacket.StdError -> forwardOutput(stderrWriter, packet.payload)
                        is AdbShellPacket.Exit -> {
                            val code = requireNotNull(packet.payload.firstOrNull()) { "Remote Exit packet has no status" }.toUByte().toInt()
                            sawRemoteExit.set(true)
                            alive.set(false)
                            observedExitCode = code
                            if (!exit.isCompleted) exit.complete(code)
                            break
                        }
                    }
                }
            } catch (failure: Exception) {
                exit.completeExceptionally(IllegalStateException("Remote process exit was not confirmed", failure))
            } finally {
                dispatchCleanup(terminateRemote = !sawRemoteExit.get())
                if (!exit.isCompleted) {
                    exit.completeExceptionally(IllegalStateException("Remote process exit was not confirmed"))
                }
                finishTransport()
            }
        }
        // Register after readJob assignment: an already-cancelled parent can
        // invoke this immediately. close() publishes its own nonblocking worker.
        parentCancellation.invokeOnCompletion { cause ->
            if (cause != null) close()
        }
        // A cancelled parent can prevent the reader body (and finally) from starting.
        readJob.invokeOnCompletion {
            parentCancellation.complete()
            if (!exit.isCompleted) exit.completeExceptionally(IllegalStateException("Remote reader stopped without an exit receipt"))
            dispatchCleanup(terminateRemote = !sawRemoteExit.get())
            finishTransport()
        }
    }

    override fun close() {
        if (!stopRequested.compareAndSet(false, true)) return
        // A natural-exit observer may already have retired this handle. Publish
        // the new stop receipt before scheduling its independent exact-owner worker.
        ExitHostedProcessRegistry.retain(this)
        try {
            Thread({
                try {
                    // Release local output backpressure before any transport wait.
                    // Otherwise a full pipe can hold the reader needed by stdin
                    // closure or remote cleanup, creating a shutdown dependency cycle.
                    // The reader still needs an actual Exit packet for its receipt.
                    OwnedResourceCleanup.run(
                        { stdoutPipe.close() }, { stderrPipe.close() },
                        { stdoutWriter.close() }, { stderrWriter.close() },
                        { dispatchCleanup(terminateRemote = !sawRemoteExit.get()) },
                        { if (!sawRemoteExit.get()) stream.closeStdin() },
                    ).getOrThrow()
                    stopCompletion.complete(Unit)
                } catch (failure: Throwable) {
                    stopCompletion.completeExceptionally(failure)
                }
            }, "droide-remote-process-stop").apply { isDaemon = true }.start()
        } catch (failure: Throwable) {
            stopCompletion.completeExceptionally(failure)
        }
    }

    private fun forwardOutput(output: OutputStream, bytes: ByteArray) {
        if (stopRequested.get()) return
        try { output.write(bytes) }
        catch (failure: java.io.IOException) { if (!stopRequested.get()) throw failure }
    }

    private fun finishTransport() {
        if (!transportClosing.compareAndSet(false, true)) return
        fun failed(failure: Throwable) {
            // Completion observers retain Exceptions; do not rethrow a raw Error
            // from a deferred worker into their coroutine exception handlers.
            transportCompletion.completeExceptionally(if (failure is Exception) failure
                else IllegalStateException("Remote transport close failed", failure))
        }
        // Completion handlers may run inline while a cancelled owner is being
        // constructed. A blocking transport close must not hold that caller or
        // the reader's parent lifecycle; its separate receipt remains required.
        try {
            Thread({
                try {
                    OwnedResourceCleanup.run({ stdoutWriter.close() }, { stderrWriter.close() }, { stream.close() })
                        .getOrThrow()
                    transportCompletion.complete(Unit)
                } catch (failure: Throwable) {
                    failed(failure)
                }
            }, "droide-remote-transport-close").apply { isDaemon = true }.start()
        } catch (failure: Throwable) {
            failed(failure)
        }
    }

    private fun dispatchCleanup(terminateRemote: Boolean) {
        if (cleanupDispatched.compareAndSet(false, true)) {
            try { cleanupCompletion.complete(onFinished(terminateRemote)) }
            catch (failure: Throwable) { cleanupCompletion.completeExceptionally(failure) }
        }
    }
}

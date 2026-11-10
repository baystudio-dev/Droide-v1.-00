package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.shell.AdbPtyShellSession
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import com.flyfishxu.kadb.stream.AdbStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import okio.Buffer
import okio.ForwardingSource
import okio.source

class DeviceBridgeManager internal constructor(
    context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val dependencies: DeviceBridgeDependencies,
) : Closeable {
    constructor(context: Context, scope: kotlinx.coroutines.CoroutineScope) :
        this(context, scope, DeviceBridgeDependencies.live(context.applicationContext))

    data class State(
        val supported: Boolean = Build.VERSION.SDK_INT >= 30,
        val discovering: Boolean = false,
        val pairEndpoints: List<MdnsEndpoint> = emptyList(),
        val connectEndpoints: List<MdnsEndpoint> = emptyList(),
        val connected: MdnsEndpoint? = null,
        val lastError: String? = null,
        val attemptId: Long? = null,
        val connecting: MdnsEndpoint? = null,
    )

    private val appContext = context.applicationContext
    private val bridgePrefs = appContext.getSharedPreferences("droide_device_bridge", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var client: DeviceBridgeTransport? = null
    @Volatile private var closed = false
    private val connectionMutex = Mutex()
    private val attempts = DeviceBridgeConnectionAttempts()
    private val discoveryLock = Any()
    private val retirementLock = Any()
    private val retiringResources = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Closeable, Boolean>())
    @Volatile private var closeFailure: Throwable? = null

    private fun ownedTransport(endpoint: MdnsEndpoint): DeviceBridgeTransport {
        val delegate = dependencies.create(endpoint)
        val owned = object : DeviceBridgeTransport by delegate {
            override fun close() {
                delegate.close()
                check(!delegate.open) { "ADB transport still reports open after close" }
                synchronized(retirementLock) { retiringResources.remove(this) }
            }
        }
        synchronized(retirementLock) { retiringResources.add(owned) }
        return owned
    }

    private fun ownedDiscovery(): DeviceBridgeDiscovery {
        val delegate = dependencies.discovery()
        val owned = object : DeviceBridgeDiscovery by delegate {
            override fun close() {
                delegate.close()
                synchronized(retirementLock) { retiringResources.remove(this) }
            }
        }
        synchronized(retirementLock) { retiringResources.add(owned) }
        return owned
    }
    private var discoverySequence = 0L
    private var discoveryEpoch: DiscoveryEpoch? = null
    private class DiscoveryEpoch(val id: Long, val source: DeviceBridgeDiscovery) {
        var collector: Job? = null
        var stopper: Job? = null
    }
    private val shellExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "Droide-AdbShell").apply { isDaemon = true }
    }
    // A platform resolver may ignore cancellation. Bound stranded connection workers and queued
    // work.
    private val connectionExecutor = java.util.concurrent.ThreadPoolExecutor(
        2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(2),
        { task -> Thread(task, "Droide-AdbConnect").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    init { dependencies.configureIdentity() }

    fun startDiscovery() { startDiscoveryEpoch() }

    private fun startDiscoveryEpoch(): Long {
        check(!closed) { "Device bridge is closed" }
        check(scope.coroutineContext[Job]?.isActive != false) { "Device bridge scope is closed" }
        check(Build.VERSION.SDK_INT >= 30) { "Wireless Debugging requires Android 11 or newer." }
        return synchronized(discoveryLock) {
            check(!closed) { "Device bridge is closed" }
            stopDiscovery()
            val epoch = DiscoveryEpoch(++discoverySequence, ownedDiscovery())
            discoveryEpoch = epoch
            _state.update { it.copy(discovering = true, pairEndpoints = emptyList(), connectEndpoints = emptyList(), lastError = null) }
            epoch.collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                epoch.source.state.collectLatest { discovered ->
                    synchronized(discoveryLock) {
                        if (discoveryEpoch === epoch && !closed) _state.update {
                            it.copy(pairEndpoints = discovered.pairDevices, connectEndpoints = discovered.connectDevices)
                        }
                    }
                }
            }
            try {
                epoch.source.start()
                epoch.stopper = scope.launch { delay(dependencies.timing.discoveryMs); stopDiscovery(epoch.id) }
            } catch (error: Throwable) { stopDiscovery(epoch.id); throw error }
            epoch.id
        }
    }

    fun stopDiscovery() { stopDiscovery(null) }

    private fun stopDiscovery(id: Long?) {
        val epoch = synchronized(discoveryLock) {
            val current = discoveryEpoch ?: return
            if (id != null && current.id != id) return
            discoveryEpoch = null
            _state.update { it.copy(discovering = false) }
            current
        }
        epoch.collector?.cancel()
        epoch.stopper?.cancel()
        runCatching { epoch.source.close() }
    }

    private data class Prepared<T>(val value: T, val next: DeviceBridgeTransport? = null,
        val endpoint: MdnsEndpoint? = null, val onCommit: () -> Unit = {})

    /** Independent worker lets cancellation close raw I/O before anyone waits for its completion. */
    private suspend fun <T> connectionAttempt(
        endpoint: MdnsEndpoint? = null,
        operation: suspend (DeviceBridgeConnectionAttempts.Token) -> Prepared<T>,
    ): T = withTimeout(dependencies.timing.connectionMs) {
        suspendCancellableCoroutine { continuation ->
            val token = attempts.begin(cancelCaller = {
                continuation.cancel(CancellationException("Device connection cancelled"))
            }) { started ->
                _state.update { it.copy(attemptId = started.id, connecting = endpoint, lastError = null) }
            }
            fun end(error: Throwable): Boolean =
                attempts.cancel(token) {
                    _state.update { it.copy(attemptId = null, connecting = null, lastError = when (error) {
                        is TimeoutCancellationException -> "Device connection timed out. Try again or connect manually."
                        is CancellationException -> null
                        else -> error.message ?: "Device connection failed"
                    }) }
                }
            val worker = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    val prepared = connectionMutex.withLock {
                        attempts.checkCurrent(token)
                        val result = operation(token)
                        currentCoroutineContext().ensureActive()
                        var old: DeviceBridgeTransport? = null
                        val committed = attempts.commit(token, result.next) {
                            if (result.next != null) {
                                check(client?.open != true) { "Disconnect the current Device Workstation first" }
                                old = client
                            }
                            result.onCommit()
                            if (result.next != null) client = result.next
                            _state.update { it.copy(connected = result.endpoint ?: it.connected,
                                attemptId = null, connecting = null, lastError = null) }
                        }
                        if (!committed) throw CancellationException("Device connection cancelled")
                        old?.let { runCatching { it.close() } }
                        result
                    }
                    if (continuation.isActive) continuation.resume(prepared.value)
                } catch (error: Throwable) {
                    if (end(error) && continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { error ->
                end(error ?: CancellationException("Device connection cancelled"))
                worker.cancel()
            }
            worker.invokeOnCompletion { error ->
                if (error != null && continuation.isActive && end(error)) continuation.cancel(error)
            }
            worker.start()
        }
    }

    fun cancelConnect(attemptId: Long): Boolean {
        val token = attempts.active()?.takeIf { it.id == attemptId } ?: return false
        return attempts.requestCancel(token) {
            _state.update { it.copy(attemptId = null, connecting = null, lastError = null) }
        }
    }

    suspend fun pair(endpoint: MdnsEndpoint, code: String) = connectionAttempt {
        require(endpoint.serviceType == MdnsServiceType.TLS_PAIRING) { "Not a pairing endpoint" }
        require(code.matches(Regex("[0-9]{6}"))) { "Pairing code must contain 6 digits" }
        val control = com.flyfishxu.kadb.KadbConnectionControl()
        attempts.own(it, Closeable { control.cancel() })
        val pairedGuid = ownedBlocking(control::cancel) { dependencies.pair(endpoint, code, control) }
        val guid = requireNotNull(DeviceBridgeConnectionPolicy.validGuid(pairedGuid)) {
            "Invalid paired device identity"
        }
        attempts.checkCurrent(it)
        Prepared(Unit, onCommit = {
            bridgePrefs.edit().putString(KEY_LAST_PAIRED_GUID, guid).putString(KEY_LAST_PAIRED_HOST, endpoint.host).apply()
        })
    }

    suspend fun connect(endpoint: MdnsEndpoint): String = connectionAttempt(endpoint) { token ->
        DeviceBridgeConnectionPolicy.requireEndpoint(endpoint)
        val previous = _state.value.connected
        val existing = client
        if (previous != null && existing != null) {
            require(DeviceBridgeConnectionPolicy.reusable(previous, endpoint)) {
                "Disconnect the current Device Workstation before selecting a different device or protocol"
            }
            if (existing.open) {
                check(!DeviceBridgeConnectionPolicy.requiresTls(endpoint) || existing.encrypted) { "Existing connection is not encrypted; disconnect and reconnect" }
                return@connectionAttempt Prepared("Already connected to ${previous.host}:${previous.port}")
            }
        }
        prepareConnection(endpoint, token)
    }

    private suspend fun <T> ownedBlocking(abort: () -> Unit, operation: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val future = connectionExecutor.submit {
                try {
                    val result = operation()
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                runCatching(abort)
                future.cancel(true)
                (future as? Runnable)?.let(connectionExecutor::remove)
            }
        }

    private suspend fun boundedProbe(adb: DeviceBridgeTransport, command: String): BridgeShellResult =
        withTimeout(dependencies.timing.probeMs) {
            ownedBlocking(adb::close) { adb.probe(command, PROBE_OUTPUT_BYTES) }
        }

    private suspend fun prepareConnection(endpoint: MdnsEndpoint, token: DeviceBridgeConnectionAttempts.Token): Prepared<String> {
        DeviceBridgeConnectionPolicy.requireEndpoint(endpoint)
        attempts.checkCurrent(token)
        val next = ownedTransport(endpoint)
        attempts.own(token, next)
        val probe = boundedProbe(next, "printf DROIDE_BRIDGE_OK")
        attempts.checkCurrent(token)
        check(probe.exitCode == 0 && probe.stdout.contains("DROIDE_BRIDGE_OK")) { "Device bridge probe failed" }
        check(!DeviceBridgeConnectionPolicy.requiresTls(endpoint) || next.encrypted) { "Wireless Debugging requires TLS" }
        val reap = boundedProbe(next, RemoteProcessLease.reapAllCommand())
        check(reap.exitCode == 0) { "Could not reconcile stale Device Workstation processes" }
        attempts.checkCurrent(token)
        return Prepared("Connected to ${endpoint.host}:${endpoint.port}", next, endpoint)
    }

    suspend fun disconnect() = withContext(NonCancellable + Dispatchers.IO) {
        var previous: DeviceBridgeTransport? = null
        var discoveryId: Long? = null
        attempts.invalidate {
            discoveryId = synchronized(discoveryLock) { discoveryEpoch?.id }
            previous = client
            client = null
            _state.update { it.copy(connected = null, attemptId = null, connecting = null) }
        }
        discoveryId?.let { stopDiscovery(it) }
        previous?.let { runCatching { it.close() } }
    }

    suspend fun ensurePackageBackend(): String = connectionAttempt { token ->
        check(Build.VERSION.SDK_INT >= 30) { "Wireless Debugging package backend requires Android 11 or newer" }
        prepareHealthyOrPaired(token)
    }

    suspend fun ensureHealthyConnection(): String = connectionAttempt { token ->
        check(_state.value.connected != null && client != null) { "Device bridge is not connected" }
        prepareHealthyOrPaired(token)
    }

    private suspend fun prepareHealthyOrPaired(token: DeviceBridgeConnectionAttempts.Token): Prepared<String> {
        val previous = _state.value.connected
        val existing = client
        if (previous != null && existing?.open == true) {
            check(!DeviceBridgeConnectionPolicy.requiresTls(previous) || existing.encrypted) { "Existing connection is not encrypted; disconnect and reconnect" }
            // Probe an independently owned transport. Never reset or abort the published session.
            val probeClient = ownedTransport(previous)
            attempts.own(token, probeClient)
            val probe = boundedProbe(probeClient, "printf DROIDE_BRIDGE_HEALTH")
            check(probe.exitCode == 0 && probe.stdout.contains("DROIDE_BRIDGE_HEALTH")) { "Device bridge health probe failed" }
            check(!DeviceBridgeConnectionPolicy.requiresTls(previous) || probeClient.encrypted) { "Wireless Debugging requires TLS" }
            return Prepared("reachable:${previous.host}:${previous.port}; existing session preserved")
        }
        val guid = DeviceBridgeConnectionPolicy.validGuid(bridgePrefs.getString(KEY_LAST_PAIRED_GUID, null))
            ?: error(DeviceBridgeConnectionPolicy.PAIR_ONCE_MESSAGE)
        if (previous != null) require(previous.serviceType == MdnsServiceType.TLS_CONNECT && previous.name == guid) {
            DeviceBridgeConnectionPolicy.PAIR_ONCE_MESSAGE
        }
        val endpoint = discoverPairedEndpoint(token, guid)
        return prepareConnection(endpoint, token)
    }

    private suspend fun discoverPairedEndpoint(token: DeviceBridgeConnectionAttempts.Token, guid: String): MdnsEndpoint {
        attempts.checkCurrent(token)
        val epoch = startDiscoveryEpoch()
        attempts.own(token, Closeable { stopDiscovery(epoch) })
        try {
            var matches = emptyList<MdnsEndpoint>()
            repeat(30) {
                attempts.checkCurrent(token)
                matches = DeviceBridgeConnectionPolicy.automaticCandidates(_state.value.connectEndpoints, guid)
                if (matches.size == 1) return matches.single()
                delay(dependencies.timing.pollMs)
            }
            error(if (matches.isEmpty()) "Paired Device Workstation is not advertising a connect endpoint. Connect manually or try again."
                else "Multiple Wireless Debugging endpoints match this device; select the intended device manually.")
        } finally { stopDiscovery(epoch) }
    }

    // Keep untrusted input and output bounded.

    suspend fun shell(command: String): BridgeShellResult {
        val result = shellBounded(command, DEFAULT_CONTROL_SHELL_OUTPUT_BYTES)
        check(!result.truncated) { "Device shell command exceeded the control-plane output limit" }
        return result
    }

    fun openRawShell(command: String): AdbShellStream {
        require(command.isNotBlank() && command.length <= 128_000 && '\u0000' !in command) { "Invalid raw shell command" }
        return requireClient().openShell(command)
    }

    fun openJdwp(pid: Int): AdbStream {
        require(pid > 0) { "Invalid JDWP pid" }
        return requireClient().open("jdwp:$pid")
    }

    fun openJdwpTracker(): AdbStream = requireClient().open("track-jdwp")

    suspend fun jdwpPids(timeoutMs: Long = 3_000): List<Int> = withTimeout(timeoutMs.coerceIn(500, 15_000)) {
        suspendCancellableCoroutine { continuation ->
            val streamRef = AtomicReference<AdbStream?>(null)
            val future = shellExecutor.submit {
                try {
                    val stream = openJdwpTracker()
                    streamRef.set(stream)
                    stream.use { tracker ->
                        val header = tracker.source.readUtf8(4)
                        val length = header.toIntOrNull(16) ?: error("Invalid track-jdwp frame length")
                        require(length in 0..64_000) { "track-jdwp frame is too large" }
                        val payload = if (length == 0) "" else tracker.source.readUtf8(length.toLong())
                        val pids = payload.lineSequence()
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .mapNotNull(String::toIntOrNull)
                            .filter { it > 0 }
                            .distinct()
                            .take(256)
                            .toList()
                        if (continuation.isActive) continuation.resume(pids)
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                }
            }
            continuation.invokeOnCancellation {
                streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                future.cancel(true)
            }
        }
    }

    // Callers own and must close the returned session.
    fun openPtyShell(command: String = "", term: String = "xterm-256color"): AdbPtyShellSession {
        require(term.matches(Regex("[A-Za-z0-9._+-]{1,64}"))) { "Invalid terminal type" }
        return requireClient().openPtyShellSession(command = command, term = term)
    }

    // The callback is observational only: callback failures are ignored and never corrupt the build.

    suspend fun shellStreaming(
        command: String,
        maxOutputBytes: Int = 1_500_000,
        onChunk: (text: String, stderr: Boolean) -> Unit,
    ): BridgeShellResult {
        require(maxOutputBytes in 8_192..3_000_000) { "Invalid shell output cap" }
        val adb = requireClient()
        return suspendCancellableCoroutine { continuation ->
            val streamRef = AtomicReference<AdbShellStream?>(null)
            val future = shellExecutor.submit {
                try {
                    val stdout = TerminalOutputCollector(maxOutputBytes / 2) { onChunk(it, false) }
                    val stderr = TerminalOutputCollector(maxOutputBytes / 2) { onChunk(it, true) }
                    val stream = adb.openShell(command)
                    streamRef.set(stream)
                    var exitCode = 1
                    stream.use { shell ->
                        while (true) {
                            when (val packet = shell.read()) {
                                is AdbShellPacket.StdOut -> {
                                    stdout.append(packet.payload)
                                }
                                is AdbShellPacket.StdError -> {
                                    stderr.append(packet.payload)
                                }
                                is AdbShellPacket.Exit -> {
                                    exitCode = packet.payload.firstOrNull()?.toUByte()?.toInt() ?: 1
                                    break
                                }
                            }
                        }
                    }
                    stdout.finish()
                    stderr.finish()
                    streamRef.set(null)
                    if (continuation.isActive) {
                        continuation.resume(
                            BridgeShellResult(
                                exitCode = exitCode,
                                stdout = stdout.output,
                                stderr = stderr.output,
                                truncated = stdout.truncated || stderr.truncated,
                            )
                        )
                    }
                } catch (error: Throwable) {
                    streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                runCatching { adb.resetConnection() }
                future.cancel(true)
            }
        }
    }

    suspend fun shellBounded(command: String, maxOutputBytes: Int = 1_500_000): BridgeShellResult =
        shellStreaming(command, maxOutputBytes) { _, _ -> }

    suspend fun push(local: File, remotePath: String, mode: Int = 420) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        val path = local.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Local source must be a regular non-symlink file: ${local.path}" }
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val source = input.source()
            requireClient().push(source, remotePath, mode = mode, lastModifiedMs = attrs.lastModifiedTime().toMillis())
        }
    }

    suspend fun pushCancellable(
        local: File,
        remotePath: String,
        mode: Int = 420,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        val path = local.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Local source must be a regular non-symlink file: ${local.path}" }
        val total = attrs.size()
        val job = currentCoroutineContext()[Job]
        val adb = requireClient()
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            var copied = 0L
            val guarded = object : ForwardingSource(input.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    job?.ensureActive()
                    val read = super.read(sink, byteCount)
                    if (read > 0L) {
                        copied = Math.addExact(copied, read)
                        try {
                            onProgress(copied, total)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // Progress observers never get to corrupt the transport.
                        }
                    }
                    return read
                }
            }
            try {
                adb.push(guarded, remotePath, mode = mode, lastModifiedMs = attrs.lastModifiedTime().toMillis())
                job?.ensureActive()
                require(copied == total) { "ADB push size changed while reading: $copied != $total" }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                runCatching { adb.resetConnection() }
                throw cancelled
            } finally {
                guarded.close()
            }
        }
    }

    suspend fun pushStream(
        input: InputStream,
        remotePath: String,
        mode: Int = 420,
        lastModifiedMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        // Kadb does not close the Source supplied to push(); ownership remains with the caller.
        val source = input.source()
        requireClient().push(source, remotePath, mode = mode, lastModifiedMs = lastModifiedMs)
    }

    suspend fun pull(remotePath: String, local: File) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        local.parentFile?.mkdirs()
        requireClient().pull(local, remotePath)
    }

    suspend fun install(apk: File, replace: Boolean = true) = withContext(Dispatchers.IO) {
        require(apk.isFile && apk.extension.equals("apk", true)) { "APK not found: ${apk.path}" }
        if (replace) requireClient().install(apk, "-r") else requireClient().install(apk)
    }

    suspend fun launch(packageName: String, activity: String? = null): BridgeShellResult {
        requirePackageName(packageName)
        val command = if (activity.isNullOrBlank()) {
            "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1"
        } else {
            require(activity.matches(Regex("[A-Za-z0-9_.$/]+"))) { "Invalid activity" }
            "am start -n ${shellQuote(packageName + "/" + activity)}"
        }
        return shell(command)
    }

    suspend fun launchDebuggable(packageName: String, suspendAtStart: Boolean = true): BridgeShellResult {
        requirePackageName(packageName)
        val resolve = shell(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + shellQuote(packageName) + " | tail -1"
        )
        if (resolve.exitCode != 0) return resolve
        val component = resolve.stdout.lineSequence().map(String::trim)
            .lastOrNull { it.contains('/') && it.length <= 512 }
            ?: return BridgeShellResult(1, "", "Launcher activity could not be resolved")
        require(component.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))) { "Unsafe launcher component" }
        val suspendArg = if (suspendAtStart) " --suspend" else ""
        return shell("am start -D$suspendArg -n ${shellQuote(component)}")
    }

    suspend fun forceStop(packageName: String): BridgeShellResult {
        requirePackageName(packageName)
        return shell("am force-stop ${shellQuote(packageName)}")
    }

    suspend fun pidOf(packageName: String): Int? {
        requirePackageName(packageName)
        return shell("pidof ${shellQuote(packageName)}").stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
    }

    suspend fun logcat(packageName: String, maxLines: Int = 1200): BridgeShellResult {
        val pid = pidOf(packageName) ?: return BridgeShellResult(1, "", "App is not running")
        val lines = maxLines.coerceIn(50, 5000)
        return shellBounded("logcat -d --pid=$pid -t $lines", maxOutputBytes = 1_000_000)
    }

    fun forward(localPort: Int, remotePort: Int): Closeable {
        require(localPort in 1024..65535 && remotePort in 1..65535)
        val handle = requireClient().tcpForward(localPort, remotePort)
        return Closeable { handle.close() }
    }

    private fun requireClient(): Kadb = client?.kadb ?: error("Device bridge is not connected")

    suspend fun resetPairingIdentity() = withContext(NonCancellable + Dispatchers.IO) {
        var previous: DeviceBridgeTransport? = null
        var discoveryId: Long? = null
        try {
            attempts.invalidate {
                discoveryId = synchronized(discoveryLock) { discoveryEpoch?.id }
                previous = client
                client = null
                _state.update { it.copy(connected = null, lastError = null, attemptId = null, connecting = null) }
                // Forget auto-selection even when deleting the identity store fails.
                bridgePrefs.edit().remove(KEY_LAST_PAIRED_HOST).remove(KEY_LAST_PAIRED_GUID).apply()
                try {
                    dependencies.resetIdentity()
                } catch (error: Throwable) {
                    _state.update { it.copy(lastError = "Could not reset pairing identity: ${error.message ?: "identity storage failure"}") }
                    throw error
                }
            }
        } finally {
            discoveryId?.let { stopDiscovery(it) }
            previous?.let { runCatching { it.close() } }
        }
    }

    override fun close() {
        closed = true
        var previous: DeviceBridgeTransport? = null
        OwnedResourceCleanup.run(
            { attempts.close {
                previous = client
                client = null
                _state.update { it.copy(connected = null, attemptId = null, connecting = null) }
            } },
            { stopDiscovery() },
            { previous?.let { runCatching { it.close() } }; Unit },
            { connectionExecutor.shutdownNow(); Unit },
            { shellExecutor.shutdownNow(); Unit },
        ).onFailure { closeFailure = it }
    }

    /** Called after owned remote cleanup and the runtime coroutine barrier. Local transport only. */
    suspend fun stopAndAwaitForExit() = withContext(Dispatchers.IO) {
        close()
        val resources = synchronized(retirementLock) { retiringResources.toList() }
        val result = OwnedResourceCleanup.run(*resources.map { resource -> { resource.close() } }.toTypedArray())
        // The host's observation budget reports a pending Exit without cancelling these workers. Keep
        // the same shutdown executors until every exact local worker has actually retired.
        while (!connectionExecutor.isTerminated || !shellExecutor.isTerminated) {
            currentCoroutineContext().ensureActive()
            connectionExecutor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            shellExecutor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        result.getOrThrow()
        check(synchronized(retirementLock) { retiringResources.isEmpty() }) { "ADB transport cleanup is still pending" }
        closeFailure?.let { throw IllegalStateException("ADB shutdown was not confirmed", it) }
    }

    companion object {
        private const val REMOTE_ROOT = "/data/local/tmp/droide"
        private const val DEFAULT_CONTROL_SHELL_OUTPUT_BYTES = 512 * 1024
        private const val KEY_LAST_PAIRED_HOST = "last_paired_host"
        private const val KEY_LAST_PAIRED_GUID = "last_paired_guid"
        private const val PROBE_OUTPUT_BYTES = 16_384
        fun remoteRoot(): String = REMOTE_ROOT

        fun requireSafeRemotePath(path: String) {
            require(path == REMOTE_ROOT || path.startsWith("$REMOTE_ROOT/")) { "Remote path must stay inside $REMOTE_ROOT" }
            require(!path.contains("/../") && !path.endsWith("/..") && !path.contains('\u0000')) { "Unsafe remote path" }
        }

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        fun requirePackageName(value: String) {
            require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }
        }
    }
}

data class BridgeShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean = false,
) {
    val combined: String get() = buildString {
        if (truncated) append("[Droide: earlier command output was truncated to protect memory]\n")
        append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty() && !endsWith("\n")) append('\n')
            append(stderr)
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(block: suspend () -> Unit) =
    launch { runSuspendCatching { block() } }

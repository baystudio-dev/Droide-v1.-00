package com.baystudio.droide.core
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class DebugState { IDLE, STARTING, RUNNING, STOPPED, TERMINATED, ERROR }
data class DebugBreakpoint(
    val path: String,

    val line: Int,
    val verified: Boolean = false,
    val id: Int? = null,
    val message: String? = null,

    val requestedLine: Int = line,
)
data class DebugThread(val id: Int, val name: String)
data class DebugFrame(val id: Int, val name: String, val path: String?, val line: Int, val column: Int)
data class DebugVariable(
    val name: String,
    val value: String,
    val type: String?,
    val variablesReference: Int,
    val evaluateName: String? = null,
)
data class DebugWatch(
    val expression: String,
    val value: String = "",
    val type: String? = null,
    val variablesReference: Int = 0,
    val error: String? = null,
)

data class DebugConfiguration(
    val name: String,
    val request: String,
    val adapter: List<String>,
    val arguments: JsonObject,
    val source: String,
    val executionScope: ProcessExecutionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
    val adapterTransport: DebugAdapterTransport = DebugAdapterTransport.STDIO,
    val reverseTcpContract: AuthenticatedReverseTcpContract? = null,
    val androidJdwp: Boolean = false,
    val androidJdwpPid: Int? = null,
    val preLaunchTask: String? = null,
    val runtimeWarning: String? = null,
)

data class AndroidDebugProcess(val pid: Int, val processName: String?)

class DebugManager(
    private val scope: CoroutineScope,
    private val files: FileRepository,
    private val terminalManager: TerminalManager,
    private val executableDirs: List<File> = emptyList(),
    private val stateStore: DebugStateStore? = null,
    private val remoteProcessHost: StdioProcessHost? = null,
    private val deviceBridge: DeviceBridgeManager? = null,
    private val extraDebugAdapters: () -> List<DebugAdapterRegistry.Spec> = { emptyList() },
) : AutoCloseable {
    private val root = files.root
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val sessionMutex = Mutex()
    @Volatile private var dap: DapProcess? = null
    @Volatile private var rootDap: DapProcess? = null
    @Volatile private var debugSessionScope: CoroutineScope? = null
    @Volatile private var multiSessionHost: MultiSessionDebugProcessHost? = null
    private val eventMutex = Mutex()
    private val inspectionLock = Any()
    private var inspectionRevision = 0L
    private var inspectionJob: Job? = null
    private data class Inspection(val process: DapProcess, val revision: Long)
    private class ChildSession(val id: String, val process: DapProcess) {
        val initialized = CompletableDeferred<Unit>()
        var capabilities: JsonObject = buildJsonObject {}
        var eventJob: Job? = null
        var startupJob: Job? = null
        val breakpoints = ConcurrentHashMap<String, List<DebugBreakpoint>>()
        val breakpointEvents = ConcurrentHashMap<Int, JsonObject>()
    }
    private val childSessions = ConcurrentHashMap<String, ChildSession>()
    private val reservedChildIds = mutableSetOf<String>()
    val targetSessionCount: Int get() = childSessions.size
    private var eventJob: Job? = null
    private var launchJob: Deferred<JsonObject>? = null
    private data class RootReadiness(val process: DapProcess, val signal: CompletableDeferred<Unit>)
    @Volatile private var rootReadiness: RootReadiness? = null
    private var activeConfig: DebugConfiguration? = null
    private var activePathMapper: WorkspacePathMapper? = null
    private var activeJdwpProxy: AndroidJdwpProxy? = null
    private var capabilities: JsonObject = buildJsonObject {}
    private var rootCapabilities: JsonObject = buildJsonObject {}
    private val rootBreakpointEvents = ConcurrentHashMap<Int, JsonObject>()
    private val breakpointSyncLock = Any()
    private val breakpointEditVersions = mutableMapOf<String, Long>()
    private val breakpointRequestVersions = mutableMapOf<Pair<DapProcess, String>, Long>()
    private var nextBreakpointRequestVersion = 0L
    private val stopGeneration = AtomicLong(0L)

    private val _state = MutableStateFlow(DebugState.IDLE)
    val state: StateFlow<DebugState> = _state.asStateFlow()
    private val _status = MutableStateFlow("No debug session")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _breakpoints = MutableStateFlow<Map<String, List<DebugBreakpoint>>>(emptyMap())
    val breakpoints: StateFlow<Map<String, List<DebugBreakpoint>>> = _breakpoints.asStateFlow()
    private val _threads = MutableStateFlow<List<DebugThread>>(emptyList())
    val threads: StateFlow<List<DebugThread>> = _threads.asStateFlow()
    private val _frames = MutableStateFlow<List<DebugFrame>>(emptyList())
    val frames: StateFlow<List<DebugFrame>> = _frames.asStateFlow()
    private val _variables = MutableStateFlow<List<DebugVariable>>(emptyList())
    val variables: StateFlow<List<DebugVariable>> = _variables.asStateFlow()
    private val _watches = MutableStateFlow<List<DebugWatch>>(emptyList())
    val watches: StateFlow<List<DebugWatch>> = _watches.asStateFlow()
    private val _selectedThread = MutableStateFlow<Int?>(null)
    val selectedThread: StateFlow<Int?> = _selectedThread.asStateFlow()
    private val _selectedFrame = MutableStateFlow<Int?>(null)
    val selectedFrame: StateFlow<Int?> = _selectedFrame.asStateFlow()
    private val _configurationDiagnostics = MutableStateFlow<List<String>>(emptyList())
    val configurationDiagnostics: StateFlow<List<String>> = _configurationDiagnostics.asStateFlow()
    private val _lastStop = MutableStateFlow<DebugStopSnapshot?>(null)
    val lastStop: StateFlow<DebugStopSnapshot?> = _lastStop.asStateFlow()

    val persistenceStatus: StateFlow<DebugPersistenceStatus> = stateStore?.status
        ?: MutableStateFlow(DebugPersistenceStatus(true)).asStateFlow()
    private val restoreMutex = Mutex()

    suspend fun restoreState() = restorePersistentState(retry = false)

    suspend fun retryRestoreState() = restorePersistentState(retry = true)

    private suspend fun restorePersistentState(retry: Boolean) {
        val store = stateStore ?: return
        if (!restoreMutex.tryLock()) return
        try {
            if (retry) sessionMutex.withLock {
                check(_state.value == DebugState.IDLE && dap == null && rootDap == null &&
                    multiSessionHost == null && debugSessionScope == null && activeJdwpProxy == null &&
                    launchJob == null && eventJob == null && childSessions.isEmpty() &&
                    synchronized(reservedChildIds) { reservedChildIds.isEmpty() }) {
                    "Stop debugging before retrying saved debugger state."
                }
                if (!store.status.value.writable) restoreStoredModel(store, retry = true)
            } else restoreStoredModel(store, retry = false)
        } finally {
            restoreMutex.unlock()
        }
    }

    private suspend fun restoreStoredModel(store: DebugStateStore, retry: Boolean) {
        try {
            store.restore(retry) { saved ->
                if (saved != null) {
                    val restoredBreakpoints = linkedMapOf<String, List<DebugBreakpoint>>()
                    for ((path, lines) in saved.breakpoints) {
                        val safe = files.resolveChecked(path)
                        check(PathSecurity.contains(root, safe)) { "Stored breakpoint is outside the project" }
                        restoredBreakpoints[path] = lines.map { DebugBreakpoint(path, it) }
                    }
                    _breakpoints.value = restoredBreakpoints
                    _watches.value = saved.watches.map { DebugWatch(it) }
                }
            }
        } catch (failure: DebugStateUnavailableException) {
            appendOutput("[debug state] ${failure.message}\n")
        }
    }

    private fun editPersistentState(action: () -> Unit) {
        val store = stateStore
        if (store == null) action() else store.withEditableState(action)
    }

    private fun editBreakpoints(path: String, action: () -> Unit) {
        var changed = false
        editPersistentState {
            synchronized(breakpointSyncLock) {
                val before = _breakpoints.value[path]
                try { action() } finally {
                    changed = _breakpoints.value[path] !== before
                    if (changed) breakpointEditVersions[path] = (breakpointEditVersions[path] ?: 0L) + 1
                }
            }
        }
        if (changed) scheduleBreakpointSync(path)
    }

    private fun persistStateAsync() {
        val store = stateStore ?: return
        val snapshot = DebugPersistentState(
            breakpoints = _breakpoints.value.mapValues { (_, list) -> list.map { it.requestedLine } },
            watches = _watches.value.map { it.expression },
        )
        val request = store.prepareSave(snapshot)
        scope.launch {
            runSuspendCatching { store.savePrepared(request) }.onFailure {
                appendOutput("[debug state] Save failed: ${it.message ?: "unknown error"}\n")
            }
        }
    }

    suspend fun androidJdwpProcesses(): List<AndroidDebugProcess> = withContext(Dispatchers.IO) {
        val bridge = deviceBridge ?: return@withContext emptyList()
        if (bridge.state.value.connected == null) return@withContext emptyList()
        val pids = runSuspendCatching { bridge.jdwpPids() }.getOrDefault(emptyList()).take(128)
        if (pids.isEmpty()) return@withContext emptyList()
        val command = buildString {
            append("for p in ")
            append(pids.joinToString(" "))
            append("; do n=$(cat /proc/${'$'}p/cmdline 2>/dev/null | toybox tr '\\000' ' ' | toybox head -c 240); printf '%s\\t%s\\n' \"${'$'}p\" \"${'$'}n\"; done")
        }
        val names = runSuspendCatching { bridge.shellBounded(command, maxOutputBytes = 64 * 1024) }
            .getOrNull()?.stdout.orEmpty().lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                val pid = parts.firstOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                pid to parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }.toMap()
        pids.map { AndroidDebugProcess(it, names[it]) }
    }

    suspend fun hasAndroidAttachProvider(activeFile: String): Boolean =
        androidAttachConfigurations(activeFile, 1).isNotEmpty()

    suspend fun androidAttachConfigurations(activeFile: String, pid: Int): List<DebugConfiguration> = withContext(Dispatchers.IO) {
        require(pid > 0) { "Invalid JDWP pid" }
        val custom = loadCustomConfigurations(activeFile, androidPid = pid, androidOnly = true)
        val builtIn = mutableListOf<DebugConfiguration>()
        val remote = remoteProcessHost
        if (remote != null && activeFile.substringAfterLast('.', "").lowercase() in setOf("kt", "kts")) {
            val executable = checkedToolchainProbe("Kotlin/JDWP adapter") { remote.resolveExecutable("kotlin-debug-adapter") }
            val mapper = if (executable != null) checkedToolchainProbe("Kotlin/JDWP workspace") {
                requireNotNull(remote.pathMapper()) { "Workspace path mapping is unavailable" }
            } else null
            if (executable != null && mapper != null) {
                builtIn += DebugConfiguration(
                    name = "Kotlin/JDWP attach (Workstation)",
                    request = "attach",
                    adapter = listOf(executable),
                    arguments = buildJsonObject {
                        put("projectRoot", mapper.remoteRoot)
                        put("hostName", "${'$'}{jdwpHost}")
                        put("port", "${'$'}{jdwpPort}")
                        put("timeout", 30_000)
                    },
                    source = "built-in:user-installed-kotlin-debug-adapter",
                    executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
                    androidJdwp = true,
                    androidJdwpPid = pid,
                )
            }
        }
        (custom + builtIn).distinctBy { Triple(it.name, it.executionScope, it.adapter) }
    }

    suspend fun configurations(activeFile: String): List<DebugConfiguration> = withContext(Dispatchers.IO) {
        val custom = loadCustomConfigurations(activeFile, androidPid = null, androidOnly = false)
        val builtIn = mutableListOf<DebugConfiguration>()
        for (spec in DebugAdapterRegistry.forPath(activeFile, extraDebugAdapters())) {
            // Never prefer an app-local binary: workspace/debugger code must not inherit Droide's
            // application UID.

            val remote = remoteProcessHost ?: continue
            val candidate = resolveRemoteCommand(spec.commandCandidates, remote) ?: continue
            val mapper = checkedToolchainProbe("Debug workspace") { requireNotNull(remote.pathMapper()) { "Workspace path mapping is unavailable" } }
            builtIn += DebugConfiguration(
                name = "${spec.id} (Workstation): ${activeFile.substringAfterLast('/')}",
                request = "launch",
                adapter = candidate,
                arguments = mapJsonPathsToRemote(spec.defaultArguments(activeFile, root), mapper) as JsonObject,
                source = spec.source,
                executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
                adapterTransport = spec.transport, reverseTcpContract = spec.reverseTcp,
                runtimeWarning = nativeRuntimeWarning(spec),
            )
        }
        (custom + builtIn).distinctBy { Triple(it.name, it.executionScope, it.adapter) }
    }

    fun addBreakpoint(path: String, line: Int) = editBreakpoints(path) {
        require(line >= 1) { "Breakpoint line must be >= 1" }
        files.resolveChecked(path)
        val current = _breakpoints.value[path].orEmpty()
        if (current.any { it.requestedLine == line || it.line == line }) return@editBreakpoints
        _breakpoints.value = _breakpoints.value.toMutableMap().apply {
            put(path, (current + DebugBreakpoint(path, line)).sortedBy { it.line })
        }
        persistStateAsync()
    }

    fun toggleBreakpoint(path: String, line: Int) = editBreakpoints(path) {
        val current = _breakpoints.value[path].orEmpty()
        if (current.any { it.requestedLine == line || it.line == line }) {
            _breakpoints.value = _breakpoints.value.toMutableMap().apply {
                put(path, current.filterNot { it.requestedLine == line || it.line == line })
            }
            persistStateAsync()
        } else {
            require(line >= 1) { "Breakpoint line must be >= 1" }
            files.resolveChecked(path)
            _breakpoints.value = _breakpoints.value.toMutableMap().apply {
                put(path, (current + DebugBreakpoint(path, line)).sortedBy { it.line })
            }
            persistStateAsync()
        }
    }

    fun clearBreakpoint(path: String, line: Int) = editBreakpoints(path) {
        val current = _breakpoints.value[path].orEmpty()
        _breakpoints.value = _breakpoints.value.toMutableMap().apply {
            put(path, current.filterNot { it.requestedLine == line || it.line == line })
        }
        persistStateAsync()
    }

    fun breakpointForRequestedLine(path: String, line: Int): DebugBreakpoint? =
        _breakpoints.value[path].orEmpty().firstOrNull { it.requestedLine == line }

    suspend fun start(config: DebugConfiguration) = sessionMutex.withLock {
        try {
            startLocked(config)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { stopLocked(terminateDebuggee = true) }
            throw cancel
        } catch (failure: Throwable) {
            if (_state.value != DebugState.ERROR) {
                _state.value = DebugState.ERROR
                _status.value = failure.message ?: "Debug adapter failed"
            }
            withContext(NonCancellable) { stopLocked(terminateDebuggee = true, preserveError = true) }
            throw failure
        }
    }

    private suspend fun startLocked(config: DebugConfiguration) {
        stopLocked(terminateDebuggee = true)
        _state.value = DebugState.STARTING
        _status.value = "Starting ${config.name}…"
        _output.value = ""
        _threads.value = emptyList()
        _frames.value = emptyList()
        _variables.value = emptyList()
        _lastStop.value = null
        resetBreakpointVerification()
        rootBreakpointEvents.clear()
        synchronized(breakpointSyncLock) { breakpointRequestVersions.clear() }
        config.runtimeWarning?.let { appendOutput("[debug capability] $it\n") }
        var effectiveConfig = config
        if (config.androidJdwp) {
            val pid = requireNotNull(config.androidJdwpPid) { "Android JDWP attach requires a selected process" }
            val bridge = deviceBridge ?: error("Device Bridge is unavailable")
            check(bridge.state.value.connected != null) { "Device Workstation is not connected" }
            val proxy = AndroidJdwpProxy(AndroidJdwpTransport(bridge), scope)
            val endpoint = proxy.start(pid)
            activeJdwpProxy = proxy
            effectiveConfig = config.copy(
                adapter = config.adapter.map { replaceJdwpString(it, endpoint) },
                arguments = replaceJdwpElement(config.arguments, endpoint) as JsonObject,
            )
        }
        activeConfig = effectiveConfig
        val initializedSignal = CompletableDeferred<Unit>()

        check(effectiveConfig.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
            "IDE-owned debug adapters must execute in the selected Linux backend"
        }
        val baseHost: StdioProcessHost = remoteProcessHost
            ?: error("Local Linux debug host is unavailable")
        check(baseHost.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
            "Debug host does not match the selected backend"
        }
        val host = baseHost.forDebugAdapterTransport(
            scope, effectiveConfig.adapterTransport, effectiveConfig.reverseTcpContract,
        )
        activePathMapper = if (effectiveConfig.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
            host.pathMapper() ?: error("Local Linux path mapper is unavailable")
        } else null
        val ownedScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        debugSessionScope = ownedScope
        multiSessionHost = host as? MultiSessionDebugProcessHost
        lateinit var process: DapProcess
        process = DapProcess(
            scope = ownedScope,
            workDir = root,
            argv = effectiveConfig.adapter,
            processHost = host,
            reverseRequestHandler = { command, arguments -> handleReverseRequest(command, arguments, process) },
        )
        try {
            // Keep startup ownership even if the hosted peer is created but cancellation wins before DAP
            // publishes its streams.
            rootDap = process
            process.start()
            dap = process
            rootReadiness = RootReadiness(process, initializedSignal)
            eventJob = ownedScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { process.events.collect { event -> if (rootDap === process) handleEvent(event, process) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    _state.value = DebugState.ERROR
                    _status.value = "Debug event failed: ${failure.message ?: failure::class.java.simpleName}"
                    process.close()
                }
            }
            val initResponse = process.request("initialize", buildJsonObject {
                put("clientID", "droide")
                put("clientName", "Droide")
                put("adapterID", if (multiSessionHost != null) "pwa-node" else effectiveConfig.name.take(120))
                put("locale", "en-US")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("pathFormat", "path")
                put("supportsVariableType", true)
                put("supportsVariablePaging", true)
                put("supportsRunInTerminalRequest", true)
                put("supportsMemoryReferences", false)
                put("supportsProgressReporting", false)
                put("supportsInvalidatedEvent", true)
                put("supportsStartDebuggingRequest", multiSessionHost != null)
            }, timeoutMs = 12_000)
            capabilities = initResponse["body"] as? JsonObject ?: buildJsonObject {}
            rootCapabilities = capabilities

            launchJob = process.requestAsync(effectiveConfig.request, effectiveConfig.arguments)
            withTimeout(12_000) { initializedSignal.await() }
            _breakpoints.value.keys.toList().forEach { syncBreakpoints(it) }
            process.request("setExceptionBreakpoints", buildJsonObject {
                put("filters", buildJsonArray {
                    (rootCapabilities["exceptionBreakpointFilters"] as? JsonArray).orEmpty().take(64).forEach { item ->
                        val filter = item as? JsonObject ?: return@forEach
                        if (filter["default"]?.jsonPrimitive?.booleanOrNull == true) {
                            filter["filter"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..200 }?.let { add(JsonPrimitive(it)) }
                        }
                    }
                })
            }, timeoutMs = 5_000)
            if (rootCapabilities["supportsConfigurationDoneRequest"]?.jsonPrimitive?.booleanOrNull == true) {
                process.request("configurationDone", buildJsonObject {}, timeoutMs = 8_000)
            }
            withTimeout(20_000) { launchJob?.await() }

            when (_state.value) {
                DebugState.STARTING -> {
                    _state.value = DebugState.RUNNING
                    _status.value = "Debugging ${effectiveConfig.name}"
                }
                DebugState.RUNNING, DebugState.STOPPED -> Unit
                DebugState.TERMINATED, DebugState.ERROR -> error("Debug session ended during startup")
                DebugState.IDLE -> error("Debug session became idle during startup")
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { stopLocked(terminateDebuggee = true) }
            throw cancel
        } catch (t: Throwable) {
            val stderr = process.stderr.takeLast(4_000)
            _state.value = DebugState.ERROR
            _status.value = buildString {
                append(t.message ?: "Debug adapter failed")
                if (stderr.isNotBlank()) append("\n").append(stderr)
            }.take(6_000)
            withContext(NonCancellable) { stopLocked(terminateDebuggee = true, preserveError = true) }
            throw t
        }
    }

    suspend fun stop(terminateDebuggee: Boolean = true) = sessionMutex.withLock {
        stopLocked(terminateDebuggee)
    }

    suspend fun shutdown() = sessionMutex.withLock {
        withContext(NonCancellable) { stopLocked(terminateDebuggee = true) }
    }

    suspend fun continueExecution(threadId: Int? = _selectedThread.value) {
        val process = activeProcess()
        val generation = _lastStop.value?.generation
        val id = threadId ?: refreshThreads().firstOrNull()?.id ?: error("No debug thread")
        synchronized(inspectionLock) {
            check(dap === process) { "Debug target changed before continue" }
            if (_lastStop.value?.generation != generation) return
            invalidateInspection()
        }
        process.request("continue", buildJsonObject { put("threadId", id) }, timeoutMs = 12_000)
        synchronized(inspectionLock) {
            if (dap !== process || _lastStop.value?.generation != generation) return
            markRunning()
        }
    }

    suspend fun pause(threadId: Int? = _selectedThread.value) {
        val process = activeProcess()
        val id = threadId ?: refreshThreads().firstOrNull()?.id ?: error("No debug thread")
        check(dap === process) { "Debug target changed before pause" }
        process.request("pause", buildJsonObject { put("threadId", id) }, timeoutMs = 12_000)
    }

    suspend fun next(threadId: Int? = _selectedThread.value) = step("next", threadId)
    suspend fun stepIn(threadId: Int? = _selectedThread.value) = step("stepIn", threadId)
    suspend fun stepOut(threadId: Int? = _selectedThread.value) = step("stepOut", threadId)

    suspend fun refreshThreads(): List<DebugThread> = refreshThreads(currentInspection())

    private suspend fun refreshThreads(inspection: Inspection): List<DebugThread> {
        if (!isCurrentInspection(inspection)) return emptyList()
        val response = inspection.process.request("threads", buildJsonObject {}, timeoutMs = 12_000)
        val items = ((response["body"] as? JsonObject)?.get("threads") as? JsonArray).orEmpty()
        val parsed = items.take(512).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            DebugThread(id, o["name"]?.jsonPrimitive?.contentOrNull ?: "Thread $id")
        }
        return synchronized(inspectionLock) {
            if (!isCurrentInspection(inspection)) return emptyList()
            _threads.value = parsed
            if (_selectedThread.value !in parsed.map { it.id }) _selectedThread.value = parsed.firstOrNull()?.id
            parsed
        }
    }

    suspend fun selectThread(threadId: Int) {
        val inspection = synchronized(inspectionLock) {
            invalidateInspection()
            _selectedThread.value = threadId
            _frames.value = emptyList()
            _variables.value = emptyList()
            _selectedFrame.value = null
            currentInspection()
        }
        refreshStack(threadId, 0, 200, inspection)
        refreshWatches(inspection)
    }

    suspend fun refreshStack(threadId: Int = _selectedThread.value ?: error("No thread selected"), startFrame: Int = 0, levels: Int = 200): List<DebugFrame> =
        refreshStack(threadId, startFrame, levels, currentInspection())

    private suspend fun refreshStack(threadId: Int, startFrame: Int, levels: Int, inspection: Inspection): List<DebugFrame> {
        require(startFrame >= 0 && levels in 1..500) { "Invalid stack paging" }
        if (!isCurrentInspection(inspection)) return emptyList()
        val response = inspection.process.request("stackTrace", buildJsonObject {
            put("threadId", threadId)
            put("startFrame", startFrame)
            put("levels", levels)
        }, timeoutMs = 12_000)
        val items = ((response["body"] as? JsonObject)?.get("stackFrames") as? JsonArray).orEmpty()
        val parsed = items.take(levels).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val source = o["source"] as? JsonObject
            val rawPath = source?.get("path")?.jsonPrimitive?.contentOrNull
            val relative = rawPath?.let(::relativePathOrNull)
            DebugFrame(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Frame $id",
                path = relative,
                line = o["line"]?.jsonPrimitive?.intOrNull ?: 1,
                column = o["column"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
        synchronized(inspectionLock) {
            if (!isCurrentInspection(inspection)) return emptyList()
            _frames.value = parsed
            _selectedFrame.value = parsed.firstOrNull()?.id
        }
        parsed.firstOrNull()?.let { refreshFrameVariables(it.id, inspection) }
        return if (isCurrentInspection(inspection)) parsed else emptyList()
    }

    suspend fun selectFrame(frameId: Int) {
        val inspection = synchronized(inspectionLock) {
            invalidateInspection()
            _selectedFrame.value = frameId
            _variables.value = emptyList()
            currentInspection()
        }
        refreshFrameVariables(frameId, inspection)
        refreshWatches(inspection)
    }

    suspend fun refreshFrameVariables(frameId: Int = _selectedFrame.value ?: error("No frame selected")): List<DebugVariable> =
        refreshFrameVariables(frameId, currentInspection())

    private suspend fun refreshFrameVariables(frameId: Int, inspection: Inspection): List<DebugVariable> {
        if (!isCurrentInspection(inspection)) return emptyList()
        val scopesResponse = inspection.process.request("scopes", buildJsonObject { put("frameId", frameId) }, timeoutMs = 12_000)
        val scopes = ((scopesResponse["body"] as? JsonObject)?.get("scopes") as? JsonArray).orEmpty()
        val out = mutableListOf<DebugVariable>()
        for (scopeEl in scopes.take(32)) {
            if (!isCurrentInspection(inspection)) return emptyList()
            val scopeObj = scopeEl as? JsonObject ?: continue
            val ref = scopeObj["variablesReference"]?.jsonPrimitive?.intOrNull ?: 0
            if (ref == 0) continue
            val scopeName = scopeObj["name"]?.jsonPrimitive?.contentOrNull ?: "Scope"
            out += DebugVariable("[$scopeName]", "", null, ref)
            out += variables(ref, null, null, inspection).take(500)
            if (out.size >= 2_000) break
        }
        return synchronized(inspectionLock) {
            if (!isCurrentInspection(inspection)) return emptyList()
            out.take(2_000).also { _variables.value = it }
        }
    }

    suspend fun variables(reference: Int, start: Int? = null, count: Int? = null): List<DebugVariable> =
        variables(reference, start, count, currentInspection())

    private suspend fun variables(reference: Int, start: Int?, count: Int?, inspection: Inspection): List<DebugVariable> {
        require(reference > 0) { "variablesReference must be > 0" }
        if (!isCurrentInspection(inspection)) return emptyList()
        val response = inspection.process.request("variables", buildJsonObject {
            put("variablesReference", reference)
            start?.let { put("start", it.coerceAtLeast(0)) }
            count?.let { put("count", it.coerceIn(1, 2_000)) }
        }, timeoutMs = 12_000)
        if (!isCurrentInspection(inspection)) return emptyList()
        val items = ((response["body"] as? JsonObject)?.get("variables") as? JsonArray).orEmpty()
        return items.take(2_000).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            DebugVariable(
                name = o["name"]?.jsonPrimitive?.contentOrNull?.take(500) ?: return@mapNotNull null,
                value = o["value"]?.jsonPrimitive?.contentOrNull?.take(4_000) ?: "",
                type = o["type"]?.jsonPrimitive?.contentOrNull?.take(500),
                variablesReference = o["variablesReference"]?.jsonPrimitive?.intOrNull ?: 0,
                evaluateName = o["evaluateName"]?.jsonPrimitive?.contentOrNull?.take(1_000),
            )
        }
    }

    fun addWatch(expression: String) = editPersistentState {
        val clean = expression.trim()
        require(clean.isNotBlank()) { "Watch expression is empty" }
        require(clean.length <= 20_000) { "Watch expression exceeds 20,000 characters" }
        if (_watches.value.any { it.expression == clean }) return@editPersistentState
        _watches.value = (_watches.value + DebugWatch(clean)).takeLast(200)
        persistStateAsync()
        if (_state.value == DebugState.STOPPED) scope.launch { refreshWatches() }
    }

    fun removeWatch(expression: String) = editPersistentState {
        _watches.value = _watches.value.filterNot { it.expression == expression }
        persistStateAsync()
    }

    fun clearWatches() = editPersistentState {
        _watches.value = emptyList()
        persistStateAsync()
    }

    suspend fun refreshWatches() {
        if (_state.value != DebugState.STOPPED) return
        refreshWatches(currentInspection())
    }

    private suspend fun refreshWatches(inspection: Inspection) {
        if (!isCurrentInspection(inspection) || _state.value != DebugState.STOPPED) return
        val frameId = _selectedFrame.value
        val updated = linkedMapOf<String, DebugWatch>()
        for (watch in _watches.value) {
            if (!isCurrentInspection(inspection)) return
            updated[watch.expression] = evaluateWatch(watch.expression, frameId, inspection.process)
        }
        synchronized(inspectionLock) {
            if (isCurrentInspection(inspection) && _state.value == DebugState.STOPPED && _selectedFrame.value == frameId) {
                _watches.value = _watches.value.map { updated[it.expression] ?: it }
            }
        }
    }

    suspend fun evaluate(expression: String, frameId: Int? = _selectedFrame.value, context: String = "repl"): String {
        val body = evaluateBody(expression, frameId, context)
        return body["result"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20_000)
    }

    private suspend fun evaluateWatch(expression: String, frameId: Int?, process: DapProcess): DebugWatch = try {
        val body = evaluateBody(expression, frameId, "watch", process)
        DebugWatch(
            expression = expression,
            value = body["result"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20_000),
            type = body["type"]?.jsonPrimitive?.contentOrNull?.take(500),
            variablesReference = body["variablesReference"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        DebugWatch(expression = expression, error = (t.message ?: "Evaluation failed").take(2_000))
    }

    private suspend fun evaluateBody(expression: String, frameId: Int?, context: String, process: DapProcess = activeProcess()): JsonObject {
        require(expression.isNotBlank() && expression.length <= 20_000) { "Invalid debug expression" }
        val response = process.request("evaluate", buildJsonObject {
            put("expression", expression)
            frameId?.let { put("frameId", it) }
            put("context", context)
        }, timeoutMs = 12_000)
        return response["body"] as? JsonObject ?: buildJsonObject {}
    }

    private suspend fun step(command: String, threadId: Int?) {
        val process = activeProcess()
        val generation = _lastStop.value?.generation
        val id = threadId ?: _selectedThread.value ?: error("No debug thread")
        synchronized(inspectionLock) {
            if (dap !== process || _lastStop.value?.generation != generation) return
            invalidateInspection()
        }
        process.request(command, buildJsonObject { put("threadId", id) }, timeoutMs = 12_000)
        synchronized(inspectionLock) {
            if (dap !== process || _lastStop.value?.generation != generation) return
            markRunning()
        }
    }

    private fun currentInspection(): Inspection = synchronized(inspectionLock) {
        Inspection(activeProcess(), inspectionRevision)
    }

    private fun isCurrentInspection(inspection: Inspection): Boolean = synchronized(inspectionLock) {
        dap === inspection.process && inspectionRevision == inspection.revision && isOwnedSession(inspection.process)
    }

    private fun invalidateInspection() = synchronized(inspectionLock) {
        inspectionRevision++
        inspectionJob?.cancel()
        inspectionJob = null
    }

    private fun markRunning() = synchronized(inspectionLock) {
        invalidateInspection()
        _state.value = DebugState.RUNNING
        _status.value = "Running"
        _frames.value = emptyList()
        _variables.value = emptyList()
        _selectedFrame.value = null
        _watches.value = _watches.value.map { it.copy(value = "", type = null, variablesReference = 0, error = null) }
    }

    private fun scheduleInspection(inspection: Inspection, threadId: Int?): Unit = synchronized(inspectionLock) {
        val ownerScope = debugSessionScope ?: return
        if (!isCurrentInspection(inspection) || _state.value != DebugState.STOPPED || inspectionJob != null) return
        val job = ownerScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!isCurrentInspection(inspection)) return@launch
                refreshThreads(inspection)
                val selected = synchronized(inspectionLock) {
                    if (!isCurrentInspection(inspection)) return@launch
                    (threadId ?: _selectedThread.value ?: _threads.value.firstOrNull()?.id).also { _selectedThread.value = it }
                }
                if (selected == null) {
                    synchronized(inspectionLock) {
                        if (!isCurrentInspection(inspection)) return@launch
                        _selectedFrame.value = null
                        _frames.value = emptyList()
                        _variables.value = emptyList()
                        _watches.value = _watches.value.map { it.copy(value = "", type = null, variablesReference = 0, error = null) }
                    }
                    return@launch
                }
                refreshStack(selected, 0, 200, inspection)
                refreshWatches(inspection)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                synchronized(inspectionLock) {
                    if (isCurrentInspection(inspection) && _state.value == DebugState.STOPPED) {
                        appendOutput("[debug inspection] ${failure.message ?: "Could not read paused target"}\n")
                    }
                }
            }
        }
        inspectionJob = job
        job.invokeOnCompletion {
            synchronized(inspectionLock) { if (inspectionJob === job) inspectionJob = null }
        }
        job.start()
    }

    private fun scheduleBreakpointSync(path: String) {
        (debugSessionScope ?: scope).launch {
            try {
                if (_state.value !in setOf(DebugState.RUNNING, DebugState.STOPPED)) return@launch
                val targets = (listOfNotNull(rootDap) + childSessions.values.filter { it.initialized.isCompleted }.map { it.process }).distinct()
                targets.forEach { target -> if (target.isRunning && isOwnedSession(target)) syncBreakpoints(path, target) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { appendOutput("[debug breakpoint] ${failure.message ?: "sync failed"}\n") }
        }
    }

    private suspend fun syncBreakpoints(path: String, target: DapProcess? = dap) {
        val process = target ?: return
        val sourceFile = files.resolveChecked(path)
        val key = process to path
        val (requested, editVersion, requestVersion) = synchronized(breakpointSyncLock) {
            val version = ++nextBreakpointRequestVersion
            breakpointRequestVersions[key] = version
            Triple(_breakpoints.value[path].orEmpty().sortedBy { it.requestedLine },
                breakpointEditVersions[path] ?: 0L, version)
        }
        val response = process.request("setBreakpoints", buildJsonObject {
            put("source", buildJsonObject {
                put("name", sourceFile.name)
                put("path", activePathMapper?.localToRemote(sourceFile) ?: sourceFile.absolutePath)
            })
            put("breakpoints", buildJsonArray {
                requested.forEach { bp -> add(buildJsonObject { put("line", bp.requestedLine) }) }
            })
            put("sourceModified", false)
        }, timeoutMs = 8_000)
        val returned = ((response["body"] as? JsonObject)?.get("breakpoints") as? JsonArray).orEmpty()
        if (!isOwnedSession(process)) return
        val events = childSessions.values.firstOrNull { it.process === process }?.breakpointEvents
            ?: rootBreakpointEvents
        val merged = requested.mapIndexed { index, bp ->
            val obj = returned.getOrNull(index) as? JsonObject
            val result = bp.copy(
                verified = obj?.get("verified")?.jsonPrimitive?.booleanOrNull ?: false,
                id = obj?.get("id")?.jsonPrimitive?.intOrNull,
                line = obj?.get("line")?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 } ?: bp.requestedLine,
                message = obj?.get("message")?.jsonPrimitive?.contentOrNull?.take(1_000),
            )
            result.id?.let(events::get)?.let { updateBreakpoint(result, it) } ?: result
        }
        synchronized(breakpointSyncLock) {
            if (!isOwnedSession(process) ||
                breakpointRequestVersions[key] != requestVersion ||
                (breakpointEditVersions[path] ?: 0L) != editVersion) return
            childSessions.values.firstOrNull { it.process === process }?.breakpoints?.put(path, merged)
            if (dap === process) _breakpoints.value = _breakpoints.value.toMutableMap().apply { put(path, merged) }
        }
    }

    private suspend fun handleEvent(event: DapProcess.Event, owner: DapProcess) {
        // Protocol readiness must not queue behind another target's stack/variable requests. Bind the
        // signal to its owner so an old adapter cannot initialize a replacement.
        if (event.name == "initialized") {
            val ready = rootReadiness
            if (rootDap === owner && ready?.process === owner) {
                ready.signal.complete(Unit)
            } else childSessions.values.firstOrNull { it.process === owner }?.initialized?.complete(Unit)
            return
        }
        eventMutex.withLock {
            if (isOwnedSession(owner)) handleOwnedEvent(event, owner)
        }
    }

    private suspend fun handleOwnedEvent(event: DapProcess.Event, owner: DapProcess) {
        when (event.name) {
            "output" -> {
                val body = event.body as? JsonObject
                val category = body?.get("category")?.jsonPrimitive?.contentOrNull
                val text = body?.get("output")?.jsonPrimitive?.contentOrNull.orEmpty()
                appendOutput(if (category.isNullOrBlank()) text else "[$category] $text")
            }
            "stopped" -> {
                activateChild(owner)
                if (dap !== owner) return
                val body = event.body as? JsonObject
                val threadId = body?.get("threadId")?.jsonPrimitive?.intOrNull
                val reason = body?.get("reason")?.jsonPrimitive?.contentOrNull?.take(120) ?: "stopped"
                val description = (body?.get("description") ?: body?.get("text"))
                    ?.jsonPrimitive?.contentOrNull?.take(1_000)
                val hitBreakpointIds = (body?.get("hitBreakpointIds") as? JsonArray)
                    .orEmpty().take(128).mapNotNull { it.jsonPrimitive.intOrNull }
                synchronized(inspectionLock) {
                    if (dap !== owner || !isOwnedSession(owner)) return
                    invalidateInspection()
                    _selectedThread.value = threadId
                    _selectedFrame.value = null
                    _frames.value = emptyList()
                    _variables.value = emptyList()
                    _lastStop.value = DebugStopSnapshot(
                        generation = stopGeneration.incrementAndGet(),
                        reason = reason,
                        description = description,
                        threadId = threadId,
                        hitBreakpointIds = hitBreakpointIds,
                    )
                    _state.value = DebugState.STOPPED
                    _status.value = "Paused: $reason"
                    scheduleInspection(Inspection(owner, inspectionRevision), threadId)
                }
            }
            "continued" -> {
                if (dap !== owner) return
                markRunning()
            }
            "thread" -> if (dap === owner && _state.value == DebugState.STOPPED) {
                scheduleInspection(currentInspection(), null)
            }
            "breakpoint" -> consumeBreakpointEvent(event.body, owner)
            "terminated", "exited" -> {
                if (rootDap !== owner) {
                    removeChild(owner)
                    return
                }
                invalidateInspection()
                _state.value = DebugState.TERMINATED
                _status.value = if (event.name == "exited") {
                    val code = (event.body as? JsonObject)?.get("exitCode")?.jsonPrimitive?.intOrNull
                    if (code == null) "Debuggee exited" else "Debuggee exited ($code)"
                } else "Debug session terminated"
                _threads.value = emptyList()
                _frames.value = emptyList()
                _variables.value = emptyList()
                // Tear the transport down outside this collector so cancelling eventJob cannot cancel the
                // collector itself mid-event.

                scope.launch {
                    sessionMutex.withLock {
                        if (rootDap === owner && _state.value == DebugState.TERMINATED) {
                            stopLocked(terminateDebuggee = false, preserveTerminalState = true)
                        }
                    }
                }
            }
        }
    }

    private fun consumeBreakpointEvent(bodyEl: JsonElement?, owner: DapProcess) {
        val body = bodyEl as? JsonObject ?: return
        val bp = body["breakpoint"] as? JsonObject ?: return
        val id = bp["id"]?.jsonPrimitive?.intOrNull ?: return
        val child = childSessions.values.firstOrNull { it.process === owner }
        val events = child?.breakpointEvents ?: rootBreakpointEvents
        if (events.size < 512 || events.containsKey(id)) events[id] = bp
        val current = (child?.breakpoints?.toMap() ?: _breakpoints.value).toMutableMap()
        for ((path, list) in current) {
            val updated = list.map { old ->
                if (old.id != id) old else updateBreakpoint(old, bp)
            }
            current[path] = updated
        }
        child?.breakpoints?.putAll(current)
        if (dap === owner) _breakpoints.value = current
    }

    private fun updateBreakpoint(old: DebugBreakpoint, update: JsonObject): DebugBreakpoint = old.copy(
        verified = update["verified"]?.jsonPrimitive?.booleanOrNull ?: old.verified,
        line = update["line"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: old.line,
        message = update["message"]?.jsonPrimitive?.contentOrNull?.take(1_000) ?: old.message,
    )

    private suspend fun handleReverseRequest(command: String, arguments: JsonElement?, owner: DapProcess): DapProcess.ReverseResponse = when (command) {
        "startDebugging" -> startChildDebugSession(arguments, owner).also { result ->
            if (!result.success) appendOutput("[debug target] ${result.message ?: "Target request rejected"}\n")
        }
        "runInTerminal" -> {
            val args = arguments as? JsonObject
            val rawArgs = args?.get("args") as? JsonArray
            val argv = rawArgs?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
            val rawCwd = args?.get("cwd")
            val cwdRaw = (rawCwd as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            val rawEnv = args?.get("env")
            val malformedEnv = (rawEnv as? JsonObject)?.values?.any { value ->
                value != JsonNull && (value !is JsonPrimitive || !value.isString)
            } == true
            val environment = (rawEnv as? JsonObject).orEmpty().map { (key, value) ->
                key to (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            }
            if (argv.isNullOrEmpty() || argv.size > 256 || argv.any { it == null || it.length > 32_000 || '\u0000' in it } ||
                (rawCwd != null && cwdRaw == null) || (rawEnv != null && rawEnv !is JsonObject) ||
                malformedEnv || environment.size > 128 || environment.any { (key, value) ->
                    !key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) ||
                        (value != null && (value.length > 32_000 || '\u0000' in value))
                }
            ) {
                DapProcess.ReverseResponse(false, message = "runInTerminal did not include a safe argv")
            } else {
                runSuspendCatching {
                    check(isOwnedSession(owner)) { "Debug session is no longer active" }
                    val safeArgv = argv.orEmpty().filterNotNull()
                    check(activeConfig?.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
                        "runInTerminal requires the active local Linux debug backend"
                    }
                    val mapper = activePathMapper ?: error("Local Linux debug path mapper unavailable")
                    val target = when {
                        cwdRaw.isNullOrBlank() -> mapper.remoteRoot
                        cwdRaw.startsWith("file:") -> mapper.uriToLocal(cwdRaw)?.let(mapper::localToRemote)
                            ?: error("Debugger requested terminal outside workspace")
                        mapper.remoteToLocal(cwdRaw) != null -> cwdRaw
                        File(cwdRaw).isAbsolute -> mapper.localToRemote(safeWorkingDirectory(cwdRaw))
                        else -> mapper.localRelativeToRemote(cwdRaw)
                    }
                    require(mapper.remoteToLocal(target)?.isDirectory == true) {
                        "Debugger requested terminal outside workspace"
                    }
                    val shellCommand = buildString {
                        append("cd -- ").append(LocalExecutionSubstrate.shellQuote(target)).append(" || exit; ")
                        environment.forEach { (key, value) ->
                            if (value == null) append("unset ").append(key).append("; ")
                            else append("export ").append(key).append('=').append(LocalExecutionSubstrate.shellQuote(value)).append("; ")
                        }
                        append("exec ")
                        append(safeArgv.joinToString(" ") { LocalExecutionSubstrate.shellQuote(it) })
                    }
                    val terminal = terminalManager.createLinuxTerminal(title = "debug", activate = true)
                    terminal.session.send(shellCommand)
                    DapProcess.ReverseResponse(true, buildJsonObject {})
                }.getOrElse { DapProcess.ReverseResponse(false, message = it.message ?: "Could not open debug terminal") }
            }
        }
        else -> DapProcess.ReverseResponse(false, message = "Unsupported reverse DAP request: $command")
    }

    private fun isOwnedSession(process: DapProcess): Boolean =
        rootDap === process || childSessions.values.any { it.process === process }

    private fun activateChild(process: DapProcess) {
        val child = childSessions.values.firstOrNull { it.process === process } ?: return
        if (dap !== process) {
            invalidateInspection()
            _threads.value = emptyList(); _frames.value = emptyList(); _variables.value = emptyList()
            _selectedThread.value = null; _selectedFrame.value = null
        }
        dap = process
        capabilities = child.capabilities
        _breakpoints.value = _breakpoints.value.mapValues { (path, list) ->
            val verified = child.breakpoints[path].orEmpty().associateBy { it.requestedLine }
            list.map { verified[it.requestedLine] ?: it.copy(line = it.requestedLine, verified = false, id = null, message = null) }
        }
    }

    private fun removeChild(process: DapProcess) {
        val child = childSessions.values.firstOrNull { it.process === process } ?: return
        if (!childSessions.remove(child.id, child)) return
        synchronized(reservedChildIds) { reservedChildIds.remove(child.id) }
        child.startupJob?.cancel(); child.eventJob?.cancel(); child.process.close()
        if (dap === process) {
            invalidateInspection()
            dap = childSessions.values.firstOrNull()?.process ?: rootDap
            _threads.value = emptyList(); _frames.value = emptyList(); _variables.value = emptyList()
            _selectedThread.value = null; _selectedFrame.value = null
            if (_state.value in setOf(DebugState.RUNNING, DebugState.STOPPED)) {
                _state.value = DebugState.RUNNING
                _status.value = "Debug target ended"
            }
        }
    }

    private fun closeChildSessions() {
        childSessions.values.toList().forEach { child ->
            child.startupJob?.cancel(); child.eventJob?.cancel(); runCatching { child.process.close() }
        }
        childSessions.clear()
        synchronized(reservedChildIds) { reservedChildIds.clear() }
    }

    private fun startChildDebugSession(arguments: JsonElement?, owner: DapProcess): DapProcess.ReverseResponse {
        val server = multiSessionHost ?: return DapProcess.ReverseResponse(false, message = "This adapter does not support child debug sessions")
        val parent = rootDap ?: return DapProcess.ReverseResponse(false, message = "Debug session is closed")
        val ownedScope = debugSessionScope ?: return DapProcess.ReverseResponse(false, message = "Debug session is closed")
        if (!isOwnedSession(owner)) return DapProcess.ReverseResponse(false, message = "Debug session is no longer active")
        val target = runCatching { JavascriptDebugTargetRequest.parse(arguments) }.getOrElse {
            return DapProcess.ReverseResponse(false, message = it.message ?: "Invalid child debug configuration")
        }
        val id = target.id
        val request = target.request
        val childConfig = target.configuration
        synchronized(reservedChildIds) {
            if (reservedChildIds.size >= 8 || !reservedChildIds.add(id)) {
                return DapProcess.ReverseResponse(false, message = "Duplicate target or child debug session limit reached")
            }
        }
        lateinit var childProcess: DapProcess
        childProcess = DapProcess(ownedScope, root, activeConfig?.adapter.orEmpty(), processHost = server.childSessionHost(),
            reverseRequestHandler = { command, body -> handleReverseRequest(command, body, childProcess) })
        val child = ChildSession(id, childProcess)
        synchronized(reservedChildIds) {
            if (rootDap !== parent || !ownedScope.isActive) {
                reservedChildIds.remove(id)
                childProcess.close()
                return DapProcess.ReverseResponse(false, message = "Debug session closed before target admission")
            }
            childSessions[id] = child
        }
        // js-debug waits for this successful response before releasing the target. Handshake therefore
        // runs separately, never inside the protocol reader.
        child.startupJob = ownedScope.launch {
            try {
                check(rootDap === parent && ownedScope.isActive) { "Debug session changed before child startup" }
                childProcess.start()
                child.eventJob = ownedScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { childProcess.events.collect { event -> if (rootDap === parent && childSessions[id] === child) handleEvent(event, childProcess) } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        if (rootDap === parent) appendOutput("[debug target] ${failure.message ?: "target event failed"}\n")
                        removeChild(childProcess)
                    }
                }
                val init = childProcess.request("initialize", buildJsonObject {
                    put("clientID", "droide"); put("clientName", "Droide"); put("adapterID", "pwa-node")
                    put("linesStartAt1", true); put("columnsStartAt1", true); put("pathFormat", "path")
                    put("supportsVariableType", true); put("supportsVariablePaging", true)
                    put("supportsRunInTerminalRequest", true); put("supportsStartDebuggingRequest", true)
                }, timeoutMs = 30_000)
                child.capabilities = init["body"] as? JsonObject ?: buildJsonObject {}
                val launch = childProcess.requestAsync(request, childConfig)
                withTimeout(30_000) { child.initialized.await() }
                _breakpoints.value.keys.toList().forEach { syncBreakpoints(it, childProcess) }
                childProcess.request("setExceptionBreakpoints", buildJsonObject { put("filters", buildJsonArray {}) }, timeoutMs = 8000)
                if (child.capabilities["supportsConfigurationDoneRequest"]?.jsonPrimitive?.booleanOrNull == true) {
                    childProcess.request("configurationDone", buildJsonObject {}, timeoutMs = 8000)
                }
                withTimeout(30_000) { launch.await() }
                check(rootDap === parent) { "Debug session changed during child startup" }
            } catch (cancel: CancellationException) {
                childProcess.close()
                throw cancel
            } catch (failure: Exception) {
                if (rootDap === parent) {
                    appendOutput("[debug target] ${failure.message ?: "child startup failed"}\n")
                    _status.value = "Debug target failed: ${failure.message ?: "startup failed"}"
                    removeChild(childProcess)
                } else childProcess.close()
            }
        }
        return DapProcess.ReverseResponse(true)
    }

    private fun safeWorkingDirectory(raw: String): File {
        val f = File(raw)
        val resolved = if (f.isAbsolute) f.canonicalFile else File(root, raw).canonicalFile
        require(PathSecurity.contains(root, resolved)) { "Debugger requested terminal outside workspace" }
        require(resolved.isDirectory) { "Debugger working directory does not exist" }
        return resolved
    }

    private fun activeProcess(): DapProcess = dap ?: error("No active debug adapter")

    private suspend fun request(command: String, args: JsonElement?): JsonObject =
        activeProcess().request(command, args, timeoutMs = 12_000)

    private suspend fun shutdownRequest(process: DapProcess, command: String, arguments: JsonObject) {
        try {
            process.request(command, arguments, timeoutMs = 3_000)
        } catch (_: Exception) {
            // The adapter may emit terminated before its disconnect response, or never respond.

            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun stopLocked(
        terminateDebuggee: Boolean,
        preserveError: Boolean = false,
        preserveTerminalState: Boolean = false,
    ) {
        invalidateInspection()
        val process = rootDap ?: dap
        try {
            coroutineScope {
                childSessions.values.toList().map { child -> async {
                    shutdownRequest(child.process, "disconnect", buildJsonObject { put("terminateDebuggee", terminateDebuggee) })
                } }.awaitAll()
            }
            if (process != null && process.isRunning) {
                val supportsTerminate = rootCapabilities["supportsTerminateRequest"]?.jsonPrimitive?.booleanOrNull == true
                if (terminateDebuggee && supportsTerminate) {
                    shutdownRequest(process, "terminate", buildJsonObject {})
                } else {
                    shutdownRequest(process, "disconnect", buildJsonObject { put("terminateDebuggee", terminateDebuggee) })
                }
            }
        } finally {
            // Cleanup must finish even if the caller is already cancelled while waiting for DAP.
            withContext(NonCancellable) {
                val ownedProcesses = synchronized(reservedChildIds) {
                    rootDap = null
                    rootReadiness = null
                    (listOfNotNull(process, dap) + childSessions.values.map { it.process }).distinct()
                }
                // JS server spawn can precede publication of the root DAP socket. Keep its owner until cleanup
                // completes even when no DAP peer exists.
                val ownedServer = multiSessionHost as? JavascriptDebugServerProcessHost
                closeChildSessions()
                debugSessionScope?.cancel(); debugSessionScope = null
                (multiSessionHost as? AutoCloseable)?.close()
                multiSessionHost = null
                rootDap = null
                launchJob?.cancel()
                launchJob = null
                eventJob?.cancel()
                eventJob = null
                process?.close()
                dap = null
                activeConfig = null
                activePathMapper = null
                activeJdwpProxy?.close()
                activeJdwpProxy = null
                capabilities = buildJsonObject {}
                rootCapabilities = buildJsonObject {}
                rootBreakpointEvents.clear()
                synchronized(breakpointSyncLock) { breakpointRequestVersions.clear() }
                resetBreakpointVerification()
                _threads.value = emptyList()
                _frames.value = emptyList()
                _variables.value = emptyList()
                _selectedThread.value = null
                _selectedFrame.value = null
                if (!preserveError && !preserveTerminalState) {
                    _state.value = DebugState.IDLE
                    _status.value = "No debug session"
                }
                val failures = coroutineScope {
                    val cleanup = ownedProcesses.map { owned -> async {
                        runCatching { owned.closeAndAwaitExit() }.exceptionOrNull()
                    } }.toMutableList()
                    ownedServer?.let { server -> cleanup += async {
                        runCatching { server.closeAndAwaitExit() }.exceptionOrNull()
                    } }
                    cleanup.awaitAll().filterNotNull()
                }
                if (failures.isNotEmpty()) {
                    val failure = IllegalStateException("Debug adapter cleanup incomplete", failures.first())
                    failures.drop(1).forEach(failure::addSuppressed)
                    _state.value = DebugState.ERROR
                    _status.value = "Debug adapter cleanup incomplete: ${failures.first().message ?: "owned process failed"}"
                    appendOutput("[debug cleanup] ${_status.value}\n")
                    throw failure
                }
            }
        }
    }

    private fun appendOutput(text: String) {
        if (text.isEmpty()) return
        _output.value = (_output.value + text).takeLast(200_000)
    }

    private fun resolveCommand(candidates: List<List<String>>): List<String>? {
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            val exe = ExecutableResolver.resolve(candidate.first(), executableDirs) ?: continue
            return listOf(exe) + candidate.drop(1)
        }
        return null
    }

    private suspend fun loadCustomConfigurations(
        activeFile: String,
        androidPid: Int?,
        androidOnly: Boolean,
    ): List<DebugConfiguration> {
        val diagnostics = mutableListOf<String>()
        val result = mutableListOf<DebugConfiguration>()
        for (rel in listOf(".droide/launch.json", ".vscode/launch.json")) {
            val launch = runCatching { files.resolveChecked(rel) }.getOrNull() ?: continue
            if (!launch.isFile) continue
            if (launch.length() !in 1..WorkspaceJsonConfig.MAX_BYTES.toLong()) {
                diagnostics += "$rel: launch configuration exceeds the safety bound"
                continue
            }
            val rootObj = runCatching { WorkspaceJsonConfig.parseObject(launch.readText()) }.getOrElse {
                diagnostics += "$rel: invalid launch JSONC (${it.message ?: "parse error"})"
                continue
            }
            val array = rootObj["configurations"] as? JsonArray ?: continue
            for (el in array.take(64)) {
                val original = el as? JsonObject ?: continue
                val linux = original["linux"] as? JsonObject
                val obj = if (linux == null) original else JsonObject(original + linux)
                val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
                try {
                    val request = obj["request"]?.jsonPrimitive?.contentOrNull
                    require(request in setOf("launch", "attach")) { "request must be launch or attach" }
                    val androidJdwp = obj["androidJdwp"]?.jsonPrimitive?.booleanOrNull == true
                    if (androidOnly != androidJdwp || (androidJdwp && androidPid == null)) continue
                    require(obj["postDebugTask"] == null) { "postDebugTask is not supported; run the cleanup task explicitly" }
                    val task = (obj["preLaunchTask"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    require(obj["preLaunchTask"] == null || task != null) { "preLaunchTask must be a task label" }
                    require(task == null || (task.isNotBlank() && '\u0000' !in task)) { "Invalid preLaunchTask label" }
                    val adapterId = (obj["adapterId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    val adapterRaw = obj["adapter"] as? JsonArray
                    require(adapterId == null || adapterRaw == null) { "Specify adapterId or adapter, not both" }
                    val spec = when {
                        adapterId != null -> DebugAdapterRegistry.byId(adapterId, extraDebugAdapters())
                            ?: error("No registered adapter for adapterId=$adapterId")
                        adapterRaw != null -> null
                        type != null -> DebugAdapterRegistry.byConfigurationType(type, extraDebugAdapters())
                            ?: error("No supported installed DAP integration for type=$type")
                        else -> error("Specify a supported type, adapterId, or adapter argv")
                    }
                    val templates = spec?.commandCandidates ?: listOf(adapterRaw.orEmpty().map {
                        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull
                            ?: error("adapter argv must contain strings")
                    }.also { raw ->
                        require(raw.isNotEmpty() && raw.size <= 64 && raw.all { it.length in 1..2000 && '\u0000' !in it }) { "Invalid adapter argv" }
                    })
                    val requestedScope = obj["executionScope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                    require(requestedScope == null || requestedScope in setOf("local", "local_linux_arm64")) { "Debug configuration cannot redirect the Ubuntu backend" }
                    val remote = remoteProcessHost ?: error("Ubuntu debug process host is unavailable")
                    require(remote.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) { "Debug host must use Ubuntu ARM64" }
                    val expandedCandidates = templates.map { candidate -> candidate.map { expandString(it, activeFile, null) } }
                    val remoteAdapter = resolveRemoteCommand(expandedCandidates, remote) ?: error("Debug adapter command is not installed in Ubuntu")
                    val mapper = remote.pathMapper() ?: error("Ubuntu debug path mapper is unavailable")
                    val mappedAdapter = listOf(remoteAdapter.first()) + remoteAdapter.drop(1).map(mapper::toRemoteProtocolString)
                    val explicit = obj["arguments"]
                    require(explicit == null || explicit is JsonObject) { "arguments must be an object" }
                    val fields = if (explicit is JsonObject) explicit else JsonObject(obj.filterKeys { it !in LAUNCH_CONTROL_FIELDS })
                    val defaults = if (request == "launch") spec?.defaultArguments(activeFile, root).orEmpty() else emptyMap()
                    val args = mapJsonPathsToRemote(expandElement(JsonObject(defaults + fields), activeFile), mapper) as JsonObject
                    result += DebugConfiguration(
                        name, requireNotNull(request), mappedAdapter, args, source = rel,
                        executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
                        androidJdwp = androidJdwp, androidJdwpPid = androidPid,
                        adapterTransport = spec?.transport ?: DebugAdapterTransport.STDIO, reverseTcpContract = spec?.reverseTcp,
                        preLaunchTask = task, runtimeWarning = spec?.let(::nativeRuntimeWarning),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    diagnostics += "$rel [$name]: ${failure.message ?: "invalid debug configuration"}".take(1000)
                }
            }
        }
        _configurationDiagnostics.value = diagnostics.take(128)
        return result
    }

    private fun nativeRuntimeWarning(spec: DebugAdapterRegistry.Spec): String? =
        if (spec.nativeDebugger || spec.id in setOf("lldb-codelldb", "codelldb", "netcoredbg")) DebugAdapterRegistry.NATIVE_PROOT_WARNING else null

    private fun resetBreakpointVerification() {
        _breakpoints.value = _breakpoints.value.mapValues { (_, list) ->
            list.map { it.copy(line = it.requestedLine, verified = false, id = null, message = null) }
        }
    }

    private companion object {
        val LAUNCH_CONTROL_FIELDS = setOf("name", "type", "request", "adapterId", "adapter", "executionScope",
            "androidJdwp", "preLaunchTask", "postDebugTask", "presentation", "linux", "windows", "osx", "arguments")
    }

    private suspend fun resolveRemoteCommand(
        candidates: List<List<String>>,
        host: StdioProcessHost,
    ): List<String>? {
        return resolveToolchainCommand(candidates, host::resolveExecutable)
    }

    private fun mapJsonPathsToRemote(el: JsonElement, mapper: WorkspacePathMapper): JsonElement = when (el) {
        is JsonPrimitive -> if (el.isString) JsonPrimitive(mapper.toRemoteProtocolString(el.content)) else el
        is JsonArray -> JsonArray(el.map { mapJsonPathsToRemote(it, mapper) })
        is JsonObject -> JsonObject(el.mapValues { mapJsonPathsToRemote(it.value, mapper) })
        else -> el
    }

    private fun expandElement(el: JsonElement, activeFile: String): JsonElement = when (el) {
        is JsonPrimitive -> if (el.isString) JsonPrimitive(expandString(el.content, activeFile, null)) else el
        is JsonArray -> JsonArray(el.map { expandElement(it, activeFile) })
        is JsonObject -> JsonObject(el.mapValues { expandElement(it.value, activeFile) })
        else -> el
    }

    private fun expandString(value: String, activeFile: String, port: Int?): String {
        val active = files.resolveChecked(activeFile)
        val expanded = value
            .replace("${'$'}{workspaceFolderBasename}", root.name)
            .replace("${'$'}{fileBasenameNoExtension}", active.name.let { name -> if (name.lastIndexOf('.') > 0) name.substring(0, name.lastIndexOf('.')) else name })
            .replace("${'$'}{relativeFileDirname}", activeFile.substringBeforeLast('/', ".").ifEmpty { "." })
            .replace("${'$'}{pathSeparator}", "/")
            .replace("${'$'}{/}", "/")
            .replace("${'$'}{workspaceFolder}", root.absolutePath)
            .replace("${'$'}{file}", active.absolutePath)
            .replace("${'$'}{relativeFile}", activeFile)
            .replace("${'$'}{fileDirname}", active.parentFile?.absolutePath ?: root.absolutePath)
            .replace("${'$'}{fileBasename}", active.name)
            .replace("${'$'}{fileExtname}", if (active.name.lastIndexOf('.') > 0) active.name.substring(active.name.lastIndexOf('.')) else "")
            .replace("${'$'}{port}", port?.toString() ?: "")
        val unresolved = Regex("\\$\\{([^}]+)}").findAll(expanded).map { it.groupValues[1] }.toList()
        require(unresolved.all { it in setOf("jdwpHost", "jdwpPort", "jdwpPid") }) { "Unsupported launch variable; use a workspace path or literal value" }
        return expanded
    }

    private fun replaceJdwpString(value: String, endpoint: AndroidJdwpProxy.Endpoint): String = value
        .replace("${'$'}{jdwpHost}", endpoint.host)
        .replace("${'$'}{jdwpPort}", endpoint.port.toString())
        .replace("${'$'}{jdwpPid}", endpoint.pid.toString())

    private fun replaceJdwpElement(element: JsonElement, endpoint: AndroidJdwpProxy.Endpoint): JsonElement = when (element) {
        is JsonPrimitive -> if (!element.isString) element else when (element.content) {
            "${'$'}{jdwpPort}" -> JsonPrimitive(endpoint.port)
            "${'$'}{jdwpPid}" -> JsonPrimitive(endpoint.pid)
            else -> JsonPrimitive(replaceJdwpString(element.content, endpoint))
        }
        is JsonArray -> JsonArray(element.map { replaceJdwpElement(it, endpoint) })
        is JsonObject -> JsonObject(element.mapValues { replaceJdwpElement(it.value, endpoint) })
        else -> element
    }

    private fun relativePathOrNull(raw: String): String? {
        activePathMapper?.remoteToLocal(raw)?.let { file ->
            return file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        }
        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        if (!PathSecurity.contains(root, file)) return null
        return file.relativeTo(root.canonicalFile).invariantSeparatorsPath
    }

    override fun close() {
        invalidateInspection()
        val parent = rootDap
        rootDap = null
        rootReadiness = null
        closeChildSessions()
        debugSessionScope?.cancel(); debugSessionScope = null
        (multiSessionHost as? AutoCloseable)?.close()
        multiSessionHost = null
        parent?.close()
        launchJob?.cancel()
        launchJob = null
        eventJob?.cancel()
        eventJob = null
        dap?.close()
        dap = null
        activeConfig = null
        activePathMapper = null
        activeJdwpProxy?.close()
        activeJdwpProxy = null
        capabilities = buildJsonObject {}
        rootCapabilities = buildJsonObject {}
        rootBreakpointEvents.clear()
        synchronized(breakpointSyncLock) { breakpointRequestVersions.clear() }
        _lastStop.value = null
        _state.value = DebugState.IDLE
        _status.value = "No debug session"
    }
}

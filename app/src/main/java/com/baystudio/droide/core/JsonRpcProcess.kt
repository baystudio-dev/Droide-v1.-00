package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

 
class JsonRpcProcess(
    private val scope: CoroutineScope,
    private val workDir: File,
    private val argv: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val processHost: StdioProcessHost,
    private val inboundRequestHandler: suspend (String, JsonElement?) -> JsonElement? = { _, _ -> JsonNull },
    private val maxMessageBytes: Int = ContentLengthProtocol.MAX_MESSAGE_BYTES,
    private val stderrBytes: Int = 64 * 1024,
    private val resourceLimits: ProcessResourceLimits? = null,
    private val eventQueueByteLimit: Long = 8L * 1024 * 1024,
    private val sessionAdmission: (JsonObject?) -> Unit = {},
) : AutoCloseable {
    data class Notification(val method: String, val params: JsonElement?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    // A failed protocol child must report to its request owner, not cancel an IDE
    // scope that happens to use a regular Job. Parent cancellation still owns us.
    private val transportJob: CompletableJob
    private val transportScope: CoroutineScope
    private val writeMutex = Mutex()
    private val startMutex = Mutex()
    @Volatile private var closed = false
    private val nextId = AtomicLong(1)
    private val pendingCount = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private data class InboundRequest(val id: JsonPrimitive, val method: String, val params: JsonElement?, val bytes: Int)
    private val inboundRequests = Channel<InboundRequest>(16)
    private val inboundBytes = AtomicInteger(0)
    private val omittedNotificationCount = AtomicLong(0)
    private val _notifications = ProtocolEventMailbox<Notification>(128, 0, eventQueueByteLimit)
    val notifications: Flow<Notification> = _notifications.events

    @Volatile private var process: HostedStdioProcess? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrJob: Job? = null
    @Volatile private var inboundJob: Job? = null
    private val _stderr = BoundedOutput(stderrBytes.coerceIn(4 * 1024, 1024 * 1024))

    val isRunning: Boolean get() = process?.isAlive == true
    val stderr: String get() = _stderr.utf8()
    val omittedNotifications: Long get() = omittedNotificationCount.get()

    init {
        require(processHost.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "JSON-RPC protocol processes must execute in a bound Linux or Device Workstation backend"
        }
        require(maxMessageBytes in 1..ContentLengthProtocol.MAX_MESSAGE_BYTES) { "Invalid JSON-RPC message limit" }
        require(stderrBytes in 4 * 1024..1024 * 1024) { "Invalid JSON-RPC stderr limit" }
        require(eventQueueByteLimit in 1..8L * 1024 * 1024) { "Invalid JSON-RPC event queue byte limit" }
        transportJob = SupervisorJob(scope.coroutineContext[Job])
        transportScope = CoroutineScope(scope.coroutineContext + transportJob)
        transportScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { close() } // Close streams to release blocking reads on parent cancellation.
        }
    }

    suspend fun start() {
        // Only the caller admitted to launch this transport may clean up startup.
        // A canceled waiter or idempotent start does not own another caller's process.
        var startupAdmitted = false
        try {
            withContext(Dispatchers.IO) {
                startMutex.withLock {
                    check(!closed) { "JsonRpcProcess has been closed; create a new session" }
                    if (isRunning) return@withLock
                    check(process == null) { "Previous protocol transport is shutting down" }
                    startupAdmitted = true
                    require(argv.isNotEmpty()) { "Protocol argv must not be empty" }
                    sessionAdmission(null)
                    val p = processHost.start(argv, environment, resourceLimits)
                    var published = false
                    try {
                        currentCoroutineContext().ensureActive()
                        synchronized(this@JsonRpcProcess) {
                            check(!closed && transportScope.isActive) { "Protocol session was canceled during startup" }
                            process = p
                            inboundJob = transportScope.launch(Dispatchers.IO) {
                                try {
                                    for (request in inboundRequests) {
                                        try { handleInboundRequest(request.id, request.method, request.params) }
                                        finally { inboundBytes.addAndGet(-request.bytes) }
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    failTransport(p, failure)
                                } finally {
                                    failTransport(p, CancellationException("JSON-RPC inbound handler closed"))
                                }
                            }
                            readerJob = transportScope.launch(Dispatchers.IO) { readLoop(p) }
                            stderrJob = transportScope.launch(Dispatchers.IO) {
                                try {
                                    val buf = ByteArray(4096)
                                    p.stderr.use { input ->
                                        while (isActive) {
                                            val n = input.read(buf)
                                            if (n <= 0) break
                                            _stderr.write(buf, 0, n)
                                        }
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    failTransport(p, failure)
                                }
                            }
                            published = true
                        }
                    } catch (cancelled: CancellationException) {
                        if (published) close()
                        throw cancelled
                    } finally {
                        if (!published) runCatching { p.close() }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            if (startupAdmitted) close()
            throw cancel
        } catch (failure: Exception) {
            if (startupAdmitted) close()
            throw failure
        }
    }

    suspend fun request(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = 15_000,
        cancellationNotificationMethod: String? = null,
    ): JsonElement {
        check(isRunning) { "JSON-RPC process is not running" }
        require(method.isNotBlank() && method.length <= 256) { "Invalid JSON-RPC method" }
        require(timeoutMs in 1..300_000) { "Invalid JSON-RPC request timeout" }
        val id = nextId.getAndIncrement()
        val idElement = JsonPrimitive(id)
        val deferred = CompletableDeferred<JsonElement>()
        synchronized(this@JsonRpcProcess) {
            check(!closed && isRunning) { "JSON-RPC process is not running" }
            if (pendingCount.incrementAndGet() > 128) {
                pendingCount.decrementAndGet()
                error("Too many pending JSON-RPC requests")
            }
            pending[idKey(idElement)] = deferred
            deferred.invokeOnCompletion {
                pending.remove(idKey(idElement), deferred)
                pendingCount.decrementAndGet()
            }
        }
        var writeStarted = false
        try {
            return withTimeout(timeoutMs) {
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    if (params != null) put("params", params)
                }, onWriteStarted = { writeStarted = true })
                deferred.await()
            }
        } catch (cancel: CancellationException) {
            // Cancellation can win while awaiting a completed writer. Track admission to
            // the serialized write, rather than the awaiter's return, so a live transport
            // still receives cancellation for a frame that may already have been sent.
            if (writeStarted && isRunning && cancellationNotificationMethod != null) {
                sendCancellationNotification(cancellationNotificationMethod, idElement)
            }
            throw cancel
        } finally {
            pending.remove(idKey(idElement), deferred)
            if (!deferred.isCompleted) deferred.cancel()
        }
    }


    private suspend fun sendCancellationNotification(method: String, id: JsonPrimitive) {
        withContext(NonCancellable) {
            withTimeoutOrNull(250L) {
                runCatching {
                    send(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", method)
                        put("params", buildJsonObject { put("id", id) })
                    })
                }
            }
        }
    }

    suspend fun notify(method: String, params: JsonElement? = null) {
        check(isRunning) { "JSON-RPC process is not running" }
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        })
    }

    private suspend fun send(obj: JsonObject, onWriteStarted: (() -> Unit)? = null) {
        val encoded = json.encodeToString(JsonObject.serializer(), obj)
        writeMutex.withLock {
            val active = process ?: error("Protocol process is not running")
            val out = active.stdin
            onWriteStarted?.invoke()
            val writer = transportScope.async(Dispatchers.IO) {
                // Recheck after both writeMutex and dispatcher suspension. The caller
                // may revoke a project session while this frame is queued.
                sessionAdmission(obj)
                ContentLengthProtocol.writeMessage(out, encoded, maxMessageBytes)
            }
            try {
                withTimeout(15_000) { writer.await() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                failTransport(active, failure)
                throw failure
            } finally {
                // A peer can observe the frame before flush/the writer coroutine has returned.
                // Give an admitted write a bounded chance to finish after caller cancellation,
                // so request() can send $/cancelRequest on the same healthy transport. A write
                // that remains blocked, or fails, still closes the transport to avoid half frames.
                if (!writer.isCompleted) {
                    withContext(NonCancellable) { withTimeoutOrNull(250L) { writer.join() } }
                }
                if (!writer.isCompleted || writer.isCancelled) {
                    withContext(NonCancellable + Dispatchers.IO) { close() }
                    writer.cancel()
                }
            }
        }
    }

    private suspend fun readLoop(p: HostedStdioProcess) {
        var failure: Throwable? = null
        try {
            p.stdout.use { input ->
                while (currentCoroutineContext().isActive) {
                    val raw = ContentLengthProtocol.readMessage(input, maxMessageBytes) ?: break
                    val obj = json.parseToJsonElement(raw) as? JsonObject ?: continue
                    val method = obj["method"]?.jsonPrimitive?.contentOrNull
                    val idPrimitive = obj["id"] as? JsonPrimitive
                    when {
                        method != null && idPrimitive != null -> {
                            val bytes = raw.toByteArray(Charsets.UTF_8).size
                            if (inboundBytes.addAndGet(bytes) > MAX_INBOUND_BYTES) {
                                inboundBytes.addAndGet(-bytes)
                                error("JSON-RPC inbound request queue exceeded its byte limit")
                            }
                            if (!inboundRequests.trySend(InboundRequest(idPrimitive, method, obj["params"], bytes)).isSuccess) {
                                inboundBytes.addAndGet(-bytes)
                                error("JSON-RPC inbound request queue overflow")
                            }
                        }
                        method != null -> {
                            when (_notifications.tryOffer(Notification(method, obj["params"]), raw.toByteArray(Charsets.UTF_8).size.toLong())) {
                                ProtocolEventMailbox.Admission.ACCEPTED, ProtocolEventMailbox.Admission.DISCARDED -> Unit
                                ProtocolEventMailbox.Admission.OVERFLOW -> {
                                    if (method in LOSSY_NOTIFICATIONS) omittedNotificationCount.incrementAndGet()
                                    else error("JSON-RPC notification queue overflow; byte or count safety limit reached, restart the protocol session")
                                }
                                ProtocolEventMailbox.Admission.CLOSED -> throw CancellationException("JSON-RPC notification owner closed")
                            }
                        }
                        idPrimitive != null -> {
                            val waiter = pending.remove(idKey(idPrimitive)) ?: continue
                            val err = obj["error"]
                            if (err != null && err !is JsonNull) waiter.completeExceptionally(IllegalStateException(err.toString()))
                            else waiter.complete(obj["result"] ?: JsonNull)
                        }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Exception) {
            failure = t
        } finally {
            failTransport(p, failure ?: IllegalStateException("JSON-RPC process exited"))
            runCatching { p.close() }
        }
    }

    suspend fun awaitExit(): Int {
        val active = process ?: error("JSON-RPC process is not running")
        return active.awaitExit()
    }

    private fun idKey(id: JsonPrimitive): String = if (id.isString) "s:${id.content}" else "n:${id.content}"

    private suspend fun handleInboundRequest(id: JsonPrimitive, method: String, params: JsonElement?) {
        val response = try {
            val result = withTimeout(15_000) { inboundRequestHandler(method, params) } ?: JsonNull
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
        } catch (timeout: TimeoutCancellationException) {
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("error", buildJsonObject { put("code", -32000); put("message", "IDE request handler timed out") })
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Exception) {
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("error", buildJsonObject { put("code", -32603); put("message", t.message ?: "Internal error") })
            }
        }
        send(response)
    }

    override fun close() {
        val won = synchronized(this) { shutDown(CancellationException("JSON-RPC process closed")) }
        if (won) _notifications.finish()
    }

    private fun failTransport(active: HostedStdioProcess, failure: Throwable) {
        val won = synchronized(this) {
            if (process !== active || closed) false else shutDown(failure)
        }
        if (won) _notifications.finish()
    }

    // Must hold this process's monitor. Complete independent request deferreds
    // before canceling children, so failures stay visible to their actual owners.
    private fun shutDown(failure: Throwable): Boolean {
        if (closed) return false
        closed = true
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        readerJob?.cancel()
        stderrJob?.cancel()
        inboundJob?.cancel()
        inboundRequests.close()
        readerJob = null
        stderrJob = null
        inboundJob = null
        transportJob.cancel()
        runCatching { process?.close() }
        process = null
        return true
    }

    private companion object {
        const val MAX_INBOUND_BYTES = 8 * 1024 * 1024
        val LOSSY_NOTIFICATIONS = setOf("window/logMessage", "telemetry/event", "$/progress")
    }
}

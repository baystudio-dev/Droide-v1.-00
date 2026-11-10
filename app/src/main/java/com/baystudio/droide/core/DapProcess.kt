package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class DapProcess(
    private val scope: CoroutineScope,
    private val workDir: File,
    private val argv: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val processHost: StdioProcessHost,
    private val eventQueueByteLimit: Long = 8L * 1024 * 1024,
    private val reverseRequestHandler: suspend (String, JsonElement?) -> ReverseResponse = { command, _ ->
        ReverseResponse(false, message = "Droide does not support reverse DAP request: $command")
    },
) : AutoCloseable {
    data class Event(val name: String, val body: JsonElement?)
    data class ReverseResponse(
        val success: Boolean,
        val body: JsonElement? = null,
        val message: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val transportJob: CompletableJob
    private val transportScope: CoroutineScope
    private data class ReverseRequest(val message: JsonObject, val bytes: Int)
    private val reverseRequests = Channel<ReverseRequest>(16)
    private val reverseBytes = AtomicInteger(0)
    private val writeMutex = Mutex()
    private val startMutex = Mutex()
    @Volatile private var closed = false
    private val nextSeq = AtomicInteger(1)
    private val pendingCount = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val _events = ProtocolEventMailbox<Event>(256, 8, eventQueueByteLimit)
    val events: Flow<Event> = _events.events

    @Volatile private var process: HostedStdioProcess? = null
    // close() detaches the transport immediately, but local lease cleanup may finish asynchronously.
    // Retain ownership until a suspending Stop can reap it.
    @Volatile private var ownedProcess: HostedStdioProcess? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrJob: Job? = null
    @Volatile private var reverseJob: Job? = null
    private val _stderr = BoundedOutput(128 * 1024)
    private var omittedOutputEvents = 0L

    val isRunning: Boolean get() = process?.isAlive == true
    val stderr: String get() = _stderr.utf8()

    init {
        require(processHost.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "DAP protocol processes must execute in a bound Linux or Device Workstation backend"
        }
        require(eventQueueByteLimit in 1..8L * 1024 * 1024) { "Invalid DAP event queue byte limit" }
        transportJob = SupervisorJob(scope.coroutineContext[Job])
        transportScope = CoroutineScope(scope.coroutineContext + transportJob)
        transportScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { close() }
        }
    }

    suspend fun start() {
        var startupAdmitted = false
        try {
            withContext(Dispatchers.IO) {
                startMutex.withLock {
                    check(!closed) { "DapProcess has been closed; create a new session" }
                    if (isRunning) return@withLock
                    check(process == null) { "Previous protocol transport is shutting down" }
                    startupAdmitted = true
                    require(argv.isNotEmpty()) { "Protocol argv must not be empty" }
                    val p = processHost.start(argv, environment)
                    ownedProcess = p
                    var published = false
                    try {
                        currentCoroutineContext().ensureActive()
                        synchronized(this@DapProcess) {
                            check(!closed && transportScope.isActive) { "Protocol session was canceled during startup" }
                            process = p
                            reverseJob = transportScope.launch(Dispatchers.IO) {
                                try {
                                    for (request in reverseRequests) {
                                        try { handleReverseRequest(request.message) }
                                        finally { reverseBytes.addAndGet(-request.bytes) }
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    failTransport(p, failure)
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
        command: String,
        arguments: JsonElement? = null,
        timeoutMs: Long = 15_000,
    ): JsonObject {
        require(timeoutMs in 1..300_000) { "Invalid DAP request timeout" }
        return withTimeout(timeoutMs) {
            val deferred = requestAsync(command, arguments)
            try { deferred.await() } finally {
                if (!deferred.isCompleted) deferred.cancel()
            }
        }
    }

    suspend fun requestAsync(command: String, arguments: JsonElement? = null): Deferred<JsonObject> {
        check(isRunning) { "DAP process is not running" }
        require(command.isNotBlank() && command.length <= 200) { "Invalid DAP command" }
        val seq = nextSeq.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        synchronized(this@DapProcess) {
            check(!closed && isRunning) { "DAP process is not running" }
            val inFlight = pendingCount.incrementAndGet()
            if (inFlight > MAX_PENDING_REQUESTS) {
                pendingCount.decrementAndGet()
                error("Too many pending DAP requests")
            }
            pending[seq] = deferred
            deferred.invokeOnCompletion {
                pending.remove(seq, deferred)
                pendingCount.decrementAndGet()
            }
        }
        try {
            send(buildJsonObject {
                put("seq", seq)
                put("type", "request")
                put("command", command)
                if (arguments != null) put("arguments", arguments)
            })
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        }
        return deferred
    }

    private suspend fun send(obj: JsonObject) {
        val encoded = json.encodeToString(JsonObject.serializer(), obj)
        writeMutex.withLock {
            val out = process?.stdin ?: error("Protocol process is not running")
            val writer = transportScope.async(Dispatchers.IO) { ContentLengthProtocol.writeMessage(out, encoded) }
            try {
                withTimeout(15_000) { writer.await() }
            } catch (cancel: CancellationException) {
                // A cancelled transport writer is a session failure, not a user Stop. Keep cancellation of the
                // actual request caller intact.
                currentCoroutineContext().ensureActive()
                if (closed || !transportScope.isActive) {
                    throw IllegalStateException("Debug protocol session closed during write", cancel)
                }
                throw cancel
            } finally {
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
                    val raw = ContentLengthProtocol.readMessage(input) ?: break
                    val obj = json.parseToJsonElement(raw) as? JsonObject ?: continue
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "response" -> handleResponse(obj)
                        "event" -> {
                            val name = obj["event"]?.jsonPrimitive?.contentOrNull ?: continue
                            publishEvent(name, obj["body"], raw.toByteArray(Charsets.UTF_8).size.toLong())
                        }
                        "request" -> {
                            val size = raw.toByteArray(Charsets.UTF_8).size
                            if (reverseBytes.addAndGet(size) > MAX_REVERSE_BYTES ||
                                !reverseRequests.trySend(ReverseRequest(obj, size)).isSuccess) {
                                reverseBytes.addAndGet(-size)
                                error("Debug adapter reverse-request queue exceeded its safety limit")
                            }
                        }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            failure = t
        } finally {
            val reason = failure ?: IllegalStateException("Debug adapter exited")
            failTransport(p, reason)
        }
    }

    private fun failTransport(p: HostedStdioProcess, reason: Throwable) {
        val notice = Event("terminated", buildJsonObject { put("reason", reason.message?.take(512) ?: "adapter exited") })
        val noticeBytes = buildJsonObject {
            put("event", notice.name); put("body", notice.body ?: JsonNull)
        }.toString().toByteArray(Charsets.UTF_8).size.toLong()
        val won = synchronized(this@DapProcess) {
            if (process !== p || closed) false else shutDown(reason)
        }
        // The shutdown winner alone finishes outside the transport monitor. A cleanup close cannot erase
        // this separately bounded terminal notice.
        if (won) _events.finish(ProtocolEventMailbox.Terminal(notice, noticeBytes))
    }

    private fun publishEvent(name: String, body: JsonElement?, wireBytes: Long) {
        if (name != "output") {
            check(_events.tryOffer(Event(name, body), wireBytes) == ProtocolEventMailbox.Admission.ACCEPTED) {
                "Debug adapter event queue exceeded its byte or count safety limit"
            }
            return
        }
        var addedBytes = 0L
        val rendered = if (omittedOutputEvents > 0 && body is JsonObject) {
            val text = (body["output"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val prefix = "[Droide: $omittedOutputEvents output event(s) omitted while the debugger UI was busy]\n"
            val decorated = JsonObject(body + ("output" to JsonPrimitive(prefix + text)))
            // Include escaping and structural changes even for malformed bodies with no string output.
            // Nothing serialized here is retained.
            addedBytes = decorated.toString().toByteArray(Charsets.UTF_8).size.toLong() -
                body.toString().toByteArray(Charsets.UTF_8).size.toLong()
            decorated
        } else body
        when (_events.tryOffer(Event(name, rendered), wireBytes + addedBytes)) {
            ProtocolEventMailbox.Admission.ACCEPTED, ProtocolEventMailbox.Admission.DISCARDED -> omittedOutputEvents = 0
            ProtocolEventMailbox.Admission.OVERFLOW -> omittedOutputEvents = (omittedOutputEvents + 1).coerceAtMost(Long.MAX_VALUE - 1)
            ProtocolEventMailbox.Admission.CLOSED -> Unit
        }
    }

    private fun handleResponse(obj: JsonObject) {
        val requestSeq = obj["request_seq"]?.jsonPrimitive?.intOrNull ?: return
        val waiter = pending.remove(requestSeq) ?: return
        val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (success) {
            waiter.complete(obj)
        } else {
            val message = obj["message"]?.jsonPrimitive?.contentOrNull
                ?: (obj["body"] as? JsonObject)?.get("error")?.toString()
                ?: "DAP request failed"
            waiter.completeExceptionally(IllegalStateException(message.take(4_000)))
        }
    }

    private suspend fun handleReverseRequest(obj: JsonObject) {
        val requestSeq = obj["seq"]?.jsonPrimitive?.intOrNull ?: return
        val command = obj["command"]?.jsonPrimitive?.contentOrNull ?: return
        val response = try {
            reverseRequestHandler(command, obj["arguments"])
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            ReverseResponse(false, message = t.message ?: "Reverse request failed")
        }
        send(buildJsonObject {
            put("seq", nextSeq.getAndIncrement())
            put("type", "response")
            put("request_seq", requestSeq)
            put("success", response.success)
            put("command", command)
            response.message?.let { put("message", it.take(2_000)) }
            response.body?.let { put("body", it) }
        })
    }

    private companion object {
        const val MAX_PENDING_REQUESTS = 128
        const val MAX_REVERSE_BYTES = 8 * 1024 * 1024
    }

    internal suspend fun closeAndAwaitExit(timeoutMs: Long = 5_000) {
        require(timeoutMs in 1..30_000) { "Invalid debug cleanup timeout" }
        withContext(NonCancellable) {
            close()
            val settled = withTimeoutOrNull(timeoutMs) {

                val owned = startMutex.withLock { ownedProcess }
                owned?.awaitExit()
                true
            }
            check(settled == true) { "Debug adapter process cleanup did not complete within ${timeoutMs}ms" }
        }
    }

    override fun close() {
        val won = synchronized(this) { shutDown(IllegalStateException("Debug protocol session closed")) }
        if (won) _events.finish()
    }

    // Requires this transport's monitor. Only the winner may finish its mailbox.
    private fun shutDown(failure: Throwable): Boolean {
        if (closed) return false
        closed = true
        readerJob?.cancel()
        stderrJob?.cancel()
        reverseJob?.cancel()
        reverseRequests.close()
        readerJob = null
        stderrJob = null
        reverseJob = null
        // Do not pass the transport's cancellation into otherwise active callers.
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        runCatching { process?.close() }
        process = null
        transportJob.cancel()
        return true
    }
}

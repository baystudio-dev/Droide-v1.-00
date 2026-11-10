package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*

// Blocking pipe workers cannot prevent deadline/cancellation supervision.
object LocalProcessSupervisor {
    internal const val OUTPUT_DRAIN_MS = 2_000L

    suspend fun capture(
        argv: List<String>, cwd: File, environment: Map<String, String> = emptyMap(),
        maxOutputBytes: Int = 1_048_576, timeoutMs: Long = 30_000,
        input: ((OutputStream) -> Unit)? = null, cleanup: (() -> Unit)? = null,
        onOutput: ((String) -> Unit)? = null,
        keepTail: Boolean = false,
        streamInput: ProcessInputBuffer? = null,
        onCleanupCompleted: ((Throwable?) -> Unit)? = null,
    ): ExecResult = captureWithAdmission(argv, cwd, environment, maxOutputBytes, timeoutMs,
        cleanup, onOutput, keepTail, streamInput, onCleanupCompleted, input, admitStart = { it() })

    internal suspend fun captureAdmitted(
        argv: List<String>,
        cwd: File,
        environment: Map<String, String>,
        maxOutputBytes: Int,
        timeoutMs: Long,
        admitStart: suspend (() -> Process) -> Process,
    ): ExecResult = captureWithAdmission(argv, cwd, environment, maxOutputBytes, timeoutMs, admitStart = admitStart)

    internal suspend fun captureWithAdmission(
        argv: List<String>,
        cwd: File,
        environment: Map<String, String>,
        maxOutputBytes: Int,
        timeoutMs: Long,
        cleanup: (() -> Unit)? = null,
        onOutput: ((String) -> Unit)? = null,
        keepTail: Boolean = false,
        streamInput: ProcessInputBuffer? = null,
        onCleanupCompleted: ((Throwable?) -> Unit)? = null,
        input: ((OutputStream) -> Unit)? = null,
        admitStart: suspend (() -> Process) -> Process,
    ): ExecResult = withContext(Dispatchers.IO) {
        require(input == null || streamInput == null) { "Only one process input source is allowed" }
        require(maxOutputBytes in 1..16_777_216 && timeoutMs in 0..86_400_000)
        require(!keepTail || maxOutputBytes <= 4_000_000) { "Tail output limit exceeds 4 MB" }
        val context = currentCoroutineContext()
        var owned: Process? = null
        var writerOwner: CapturedWorkspaceProcess? = null
        var captureOwnsProcess = false
        val rejectedLeaseCleanup = OwnedLeaseCleanupAttempt(cleanup)
        fun completeCapture(failure: Throwable?) {
            try { onCleanupCompleted?.invoke(failure) }
            catch (observerFailure: Throwable) {
                if (failure != null && failure !== observerFailure) observerFailure.addSuppressed(failure)
                writerOwner?.completed(observerFailure)
                throw observerFailure
            }
            writerOwner?.completed(failure)
        }
        try {
            val process = admitStart {
                context.ensureActive()
                check(owned == null) { "Process creation was requested more than once" }
                val builder = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true)
                    .apply { environment().putAll(environment) }
                WorkspaceProcessWriteAdmission.create(cwd, context[WorkspaceProcessCustody],
                    beforeFactory = { context.ensureActive(); NativeProcessStartPreflight.validate(builder) },
                ) {
                    val created = builder.start().also { owned = it }
                    CapturedWorkspaceProcess(created, context.job).also { writerOwner = it }
                }.process
            }
            check(process === owned) { "Process admission returned an unowned handle" }
            context.ensureActive()
            captureOwnsProcess = true
            captureOwned(process, maxOutputBytes, timeoutMs, input = input, cleanup = cleanup, onOutput = onOutput,
                keepTail = keepTail, streamInput = streamInput, onCleanupCompleted = ::completeCapture)
        } catch (failure: Throwable) {
            // Once captureOwned starts, its finally block owns cleanup and the completion receipt.
            if (!captureOwnsProcess) {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        var cleanupFailure: Throwable? = null
                        fun attempt(action: () -> Unit) {
                            try { action() }
                            catch (error: Throwable) {
                                val previous = cleanupFailure
                                if (previous == null) cleanupFailure = error
                                else if (previous !== error) previous.addSuppressed(error)
                            }
                        }
                        attempt { streamInput?.abort() }
                        owned?.let { process ->
                            attempt { rejectedLeaseCleanup.observe() }
                            attempt { terminate(process) }
                            attempt { check(!process.isAlive) { "Rejected process cleanup is unconfirmed" } }
                        }
                        val receiptFailure = cleanupFailure
                        attempt { completeCapture(receiptFailure) }
                        cleanupFailure?.let { throw it }
                    }
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    /** Internal post-spawn seam. Callers validate options before creating the owned process. */
    internal suspend fun captureOwned(
        process: Process,
        maxOutputBytes: Int = 1_048_576,
        timeoutMs: Long = 30_000,
        input: ((OutputStream) -> Unit)? = null,
        cleanup: (() -> Unit)? = null,
        onOutput: ((String) -> Unit)? = null,
        keepTail: Boolean = false,
        streamInput: ProcessInputBuffer? = null,
        nowNanos: () -> Long = System::nanoTime,
        pause: suspend (Long) -> Unit = { delay(it) },
        onCleanupCompleted: ((Throwable?) -> Unit)? = null,
    ): ExecResult = captureOwned(
        process, { it.start() }, maxOutputBytes, timeoutMs, input, cleanup, onOutput,
        keepTail, streamInput, nowNanos, pause, onCleanupCompleted,
    )

    internal suspend fun captureOwned(
        process: Process,
        startWorker: (Thread) -> Unit,
        maxOutputBytes: Int = 1_048_576,
        timeoutMs: Long = 30_000,
        input: ((OutputStream) -> Unit)? = null,
        cleanup: (() -> Unit)? = null,
        onOutput: ((String) -> Unit)? = null,
        keepTail: Boolean = false,
        streamInput: ProcessInputBuffer? = null,
        nowNanos: () -> Long = System::nanoTime,
        pause: suspend (Long) -> Unit = { delay(it) },
        onCleanupCompleted: ((Throwable?) -> Unit)? = null,
    ): ExecResult {
        var reader: Thread? = null
        var writer: Thread? = null
        var outputMonitor: Any? = null
        var acceptingOutput = true
        var operationFailure: Throwable? = null
        var cleaned = false
        val leaseCleanup = OwnedLeaseCleanupAttempt(cleanup)
        var leaseCleanupFailure: Throwable? = null
        var inputAborted = false
        fun abortInput() {
            if (inputAborted) return
            inputAborted = true
            streamInput?.abort()
        }
        fun releaseOwnedLease() {
            if (cleaned) return
            cleaned = true
            try { leaseCleanup.observe() }
            catch (error: Throwable) { leaseCleanupFailure = error; throw error }
        }
        return try {
            currentCoroutineContext().ensureActive()
            // Start the deadline after OS process creation, not before the JVM schedules it.
            val deadline = MonotonicProcessDeadline(nowNanos(), timeoutMs)
            val failure = AtomicReference<Throwable?>()
            val bytes = ByteArrayOutputStream().also { outputMonitor = it }
            val tail = if (keepTail) TailByteBuffer(maxOutputBytes) else null
            var truncated = false
            // Buffer acceptance and the final snapshot share this monitor. An observer is never called while
            // holding it; observers must remain non-blocking.
            fun recordFailure(error: Throwable) {
                synchronized(bytes) { if (acceptingOutput) failure.compareAndSet(null, error) }
            }
            val outputWorker = thread(start = false, name = "droide-process-output", isDaemon = true) {
                try {
                    process.inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        val decoder = onOutput?.let { callback -> Utf8StreamDecoder { chunk ->
                            if (synchronized(bytes) { acceptingOutput }) runCatching { callback(chunk) }
                        } }
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            val accepted = synchronized(bytes) {
                                if (!acceptingOutput) return@synchronized false
                                if (tail != null) {
                                    tail.write(buffer, 0, n)
                                    truncated = tail.truncated
                                } else {
                                    val keep = minOf(n, maxOutputBytes - bytes.size())
                                    if (keep > 0) bytes.write(buffer, 0, keep)
                                    if (keep < n) truncated = true
                                }
                                true
                            }
                            if (!accepted) break
                            decoder?.accept(buffer, n)
                        }
                        decoder?.finish()
                    }
                } catch (e: Throwable) { recordFailure(e) }
            }
            reader = outputWorker
            startWorker(outputWorker)
            val inputWorker = thread(start = false, name = "droide-process-input", isDaemon = true) {
                try { process.outputStream.use { stream ->
                    if (streamInput != null) streamInput.drain(stream) { process.isAlive } else input?.invoke(stream)
                } }
                catch (e: Throwable) { recordFailure(e) }
            }
            writer = inputWorker
            startWorker(inputWorker)
            var timedOut = false
            var rootExit: Int? = null
            var drainExpired = false
            // The runtime deadline belongs only to a living root. In particular, lease cleanup and inherited
            // pipes cannot turn a known exit into 124.
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!process.isAlive) {
                    rootExit = process.exitValue()
                    break
                }
                failure.get()?.let { throw it }
                if (deadline.expired(nowNanos())) {
                    // Prefer an observed exit when it races the deadline check.
                    if (!process.isAlive) rootExit = process.exitValue() else timedOut = true
                    break
                }
                pause(25)
            }
            if (rootExit != null) {
                abortInput()
                // Release the owned lease once, then give buffered output a separate drain.

                releaseOwnedLease()
                val drainDeadline = MonotonicProcessDeadline(nowNanos(), OUTPUT_DRAIN_MS)
                while (outputWorker.isAlive || inputWorker.isAlive) {
                    currentCoroutineContext().ensureActive()
                    failure.get()?.let { throw it }
                    if (drainDeadline.expired(nowNanos())) {
                        drainExpired = true
                        break
                    }
                    pause(25)
                }
            }
            val incomplete = drainExpired || (timedOut && outputWorker.isAlive)
            val text = synchronized(bytes) {
                acceptingOutput = false
                if (!timedOut) failure.get()?.let { throw it }
                (tail?.utf8() ?: bytes.toString("UTF-8")) +
                    (if (truncated) "\n[output truncated]\n" else "") +
                    (if (drainExpired) "\n[output incomplete: process I/O did not finish after root exit]\n" else "")
            }
            ExecResult(if (timedOut) 124 else requireNotNull(rootExit), text, timedOut, outputIncomplete = incomplete)
        } catch (error: Throwable) {
            operationFailure = error
            throw error
        } finally {
            // A blocked reader/callback is not forcibly stoppable.

            try {
                outputMonitor?.let { monitor -> synchronized(monitor) { acceptingOutput = false } }
                val cleanupError = withContext(NonCancellable + Dispatchers.IO) {
                    var cleanupFailure: Throwable? = null
                    fun retain(error: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = error
                        else if (cleanupFailure !== error) cleanupFailure?.addSuppressed(error)
                    }
                    try { abortInput() } catch (error: Throwable) { retain(error) }
                    try { releaseOwnedLease() } catch (error: Throwable) { retain(error) }
                    try { terminate(process) } catch (error: Throwable) { retain(error) }
                    try { reader?.join(500) } catch (error: Throwable) { retain(error) }
                    try { writer?.join(500) } catch (error: Throwable) { retain(error) }
                    val unconfirmed = try { process.isAlive || reader?.isAlive == true || writer?.isAlive == true }
                        catch (error: Throwable) { retain(error); true }
                    val receiptFailure = cleanupFailure ?: leaseCleanupFailure ?: if (unconfirmed)
                        IllegalStateException("Process or I/O worker cleanup is unconfirmed") else null
                    // Preserve ordinary bounded capture semantics, but let Exit owners retain a failed receipt after
                    // their execution coroutine leaves the active set.
                    try { onCleanupCompleted?.invoke(receiptFailure) } catch (error: Throwable) { retain(error) }
                    // Throwing it inside withContext can copy it during coroutine stack recovery and sever the
                    // original receipt/cleanup identity used by its owner.
                    cleanupFailure
                }
                if (cleanupError != null) throw cleanupError
            } catch (cleanupError: Throwable) {
                val original = operationFailure
                if (original == null) throw cleanupError
                if (cleanupError !== original) original.addSuppressed(cleanupError)
            }
        }
    }
    fun terminate(process: Process) = OwnedProcessTermination.terminate(process)
}

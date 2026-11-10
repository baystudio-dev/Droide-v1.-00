package com.baystudio.droide.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded isolation for blocking MCP streams. */

internal class McpStdioIoPool(
    private val capacity: Int,
    blockingWorkers: Int = minOf(4, capacity),
) : AutoCloseable {
    init {
        require(capacity in 1..64)
        require(blockingWorkers in 1..capacity)
    }

    private val admissionLock = Any()
    private val permits = Semaphore(capacity)
    private var closed = false
    private val writers = executor("write", blockingWorkers)
    private val readers = executor("stderr", capacity)
    private val cleanup = executor("close", blockingWorkers)
    internal val activeSessions: Int get() = capacity - permits.availablePermits()
    internal val queuedWrites: Int get() = writers.queue.size

    private fun executor(role: String, count: Int): ThreadPoolExecutor {
        val ids = AtomicInteger()
        return ThreadPoolExecutor(count, count, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity), { task ->
            Thread(task, "droide-mcp-$role-${ids.incrementAndGet()}").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }

    suspend fun start(startProcess: suspend () -> HostedStdioProcess): McpStdioIo {
        currentCoroutineContext().ensureActive()
        synchronized(admissionLock) {
            check(!closed) { "MCP I/O pool is closed" }
            check(permits.tryAcquire()) { "MCP I/O capacity reached; a previous server may still be closing" }
        }
        var process: HostedStdioProcess? = null
        var io: McpStdioIo? = null
        try {
            process = startProcess()
            io = McpStdioIo(process, this)
            io.startStderr()
            currentCoroutineContext().ensureActive()
            return io
        } catch (failure: Throwable) {
            val owned = process
            try {
                if (io != null) io.close()
                else if (owned != null) submitCleanup {
                    if (closeOwnedStreams(owned)) releaseAdmission()
                }
                else releaseAdmission()
            } catch (cleanupFailure: Throwable) {
                // Keep the admission reserved if cleanup could not be admitted.
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    internal fun submitWrite(work: Runnable) = writers.execute(work)
    internal fun removeWrite(work: Runnable) { writers.remove(work) }
    internal fun submitReader(work: () -> Unit) = readers.execute(work)
    internal fun submitCleanup(work: () -> Unit) = cleanup.execute(work)
    internal fun releaseAdmission() { permits.release() }

    internal fun closeOwnedStreams(process: HostedStdioProcess): Boolean {
        var succeeded = true
        // Never flush/close the BufferedWriter: doing so can acquire a lock held by the blocked write,
        // or transmit a partial frame after its caller has timed out. The process owns stdin.
        runCatching { process.close() }.onFailure { succeeded = false }
        runCatching { process.stdout.close() }.onFailure { succeeded = false }
        runCatching { process.stderr.close() }.onFailure { succeeded = false }
        return succeeded
    }

    override fun close() = synchronized(admissionLock) {
        check(activeSessions == 0) { "MCP I/O sessions are still active or quarantined" }
        closed = true
        writers.shutdown()
        readers.shutdown()
        cleanup.shutdown()
    }
}

internal class McpStdioIo internal constructor(
    val process: HostedStdioProcess,
    private val pool: McpStdioIoPool,
) : AutoCloseable {
    val stdout = process.stdout
    private val stdin = process.stdin.bufferedWriter(Charsets.UTF_8)
    private val lock = Any()
    // The owner's reservation remains until close has actually returned, plus all admitted I/O.
    private var workCount = 1
    private var pendingWrite: WriteWork? = null
    @Volatile var closed = false
        private set
    @Volatile var stderrTail = ""
        private set

    internal fun startStderr() {
        synchronized(lock) { workCount++ }
        try {
            pool.submitReader {
                try {
                    if (!closed) stderrTail = runCatching { ProcessIo.readTextBounded(process.stderr, 16_000) }.getOrDefault("")
                } finally { workFinished() }
            }
        } catch (failure: Throwable) {
            workFinished()
            throw failure
        }
    }

    suspend fun write(line: String, deadlineNanos: Long) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val work = WriteWork(line, deadlineNanos, caller[Job])
        try {
            synchronized(lock) {
                check(!closed) { "MCP server process is closed" }
                check(pendingWrite == null) { "Concurrent MCP frame write" }
                workCount++
                pendingWrite = work
                try { pool.submitWrite(work) }
                catch (failure: Throwable) {
                    pendingWrite = null
                    workCount--
                    throw failure
                }
            }
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw McpTimeoutException("MCP request timed out while writing")
            val completed = withTimeoutOrNull((remainingNanos + 999_999L) / 1_000_000L) {
                work.completion.await()
                true
            }
            if (completed != true || deadlineNanos - System.nanoTime() <= 0) {
                throw McpTimeoutException("MCP request timed out while writing")
            }
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    private inner class WriteWork(
        private val line: String,
        private val deadlineNanos: Long,
        private val caller: Job?,
    ) : Runnable {
        private val state = AtomicInteger(0)
        val completion = CompletableDeferred<Unit>() // deliberately not a child of the caller

        override fun run() {
            if (!state.compareAndSet(0, 1)) return
            val result = runCatching {
                caller?.ensureActive()
                check(!closed && process.isAlive) { "MCP server process is closed" }
                if (deadlineNanos - System.nanoTime() <= 0) throw McpTimeoutException("MCP request timed out before writing")
                stdin.write(line)
                stdin.newLine()
                stdin.flush()
            }
            state.set(2)
            writeFinished(this)
            result.fold({ completion.complete(Unit) }, { completion.completeExceptionally(it) })
        }

        fun cancelQueued() {
            if (!state.compareAndSet(0, 2)) return
            pool.removeWrite(this)
            writeFinished(this)
            completion.completeExceptionally(CancellationException("MCP session closed before writing"))
        }
    }

    private fun writeFinished(work: WriteWork) {
        synchronized(lock) {
            check(pendingWrite === work)
            pendingWrite = null
        }
        workFinished()
    }

    private fun workFinished() {
        val release = synchronized(lock) {
            check(workCount > 0)
            --workCount == 0
        }
        if (release) pool.releaseAdmission()
    }

    override fun close() {
        val queued = synchronized(lock) {
            if (closed) return
            closed = true
            pendingWrite
        }
        queued?.cancelQueued()
        // One cleanup admission is reserved per session. No blocking operation runs on the caller. A
        // failed close conservatively quarantines its owner permit too.
        pool.submitCleanup {
            if (pool.closeOwnedStreams(process)) workFinished()
        }
    }
}

internal class McpTimeoutException(message: String) : IllegalStateException(message)

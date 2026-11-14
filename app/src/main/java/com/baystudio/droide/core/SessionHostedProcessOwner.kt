package com.baystudio.droide.core

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact handles launched by one ACP connection, including its client-side terminals.
 * This is not a claim that the workspace has no other writers.
 */
internal class SessionHostedProcessOwner : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionHostedProcessOwner>

    private val lock = Any()
    private class ProcessRecord {
        var attempt: StopAttempt? = null
        var naturalCompletion: Job? = null
    }
    private class CompletedKey(process: HostedStdioProcess, queue: ReferenceQueue<HostedStdioProcess>? = null) :
        WeakReference<HostedStdioProcess>(process, queue) {
        private val identityHash = System.identityHashCode(process)
        override fun hashCode() = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is CompletedKey && get()?.let { it === other.get() } == true
    }
    private class StopAttempt(val process: HostedStdioProcess) {
        val completion = CompletableDeferred<Result<Unit>>()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var outcome: Result<Unit>? = null
        @Volatile var closeWorker: Thread? = null
        @Volatile var receiptWorker: Job? = null
    }
    // Suspended cleanup receipts must not pin one native thread per retained process.
    private val receiptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Only unconfirmed handles stay strongly owned. Weak identity tombstones deduplicate
    // late handoffs without retaining completed processes, their streams or callbacks.
    private val owned = IdentityHashMap<HostedStdioProcess, ProcessRecord>()
    private val collected = ReferenceQueue<HostedStdioProcess>()
    // A natural exit can leave streams available to their readers. Keep only a weak
    // identity, but still close any surviving handle when the session itself stops.
    private val naturallyFinished = HashSet<CompletedKey>()
    private val completed = HashSet<CompletedKey>()
    private val launches = mutableSetOf<Job>()
    private val cleanup = Mutex()
    @Volatile var isClosing = false
        private set

    // Production hosts call this at process creation, before a cancellable dispatcher handoff.
    fun retain(process: HostedStdioProcess) {
        val (record, start) = synchronized(lock) {
            purgeCompletedLocked()
            val key = CompletedKey(process)
            if (key in completed || key in naturallyFinished || owned.containsKey(process)) return
            val record = ProcessRecord().also { owned[process] = it }
            record to if (isClosing) registerStopLocked(process, record) else null
        }
        // A late birth must not wait for its cancelled launch's finalizer to return.
        if (start != null) startStop(start) else observeNaturalCompletion(process, record)
    }

    private fun purgeCompletedLocked() {
        while (true) {
            val key = collected.poll() ?: return
            completed.remove(key)
            naturallyFinished.remove(key)
        }
    }

    private fun releaseCompletedLocked(process: HostedStdioProcess) {
        purgeCompletedLocked()
        completed += CompletedKey(process, collected)
        owned.remove(process)
    }

    private fun observeNaturalCompletion(process: HostedStdioProcess, record: ProcessRecord) {
        // A failed observation (including dispatcher rejection) never releases ownership.
        // Explicit cleanup retains its own result and retry path.
        val worker = receiptScope.launch(CoroutineExceptionHandler { _, _ -> }, start = CoroutineStart.LAZY) {
            try {
                process.awaitExit()
                check(!process.isAlive) { "CLI process is still alive after completion" }
                WorkspaceProcessWriteAdmission.confirmCompletion(process)
                synchronized(lock) {
                    // Once stop was requested, its close acknowledgement is also required.
                    if (owned[process] === record && record.attempt == null) {
                        purgeCompletedLocked()
                        naturallyFinished += CompletedKey(process, collected)
                        owned.remove(process)
                    }
                }
            } catch (_: Throwable) {
                // Stop/Exit must still observe or retry this exact handle's cleanup.
            }
        }
        val observe = synchronized(lock) {
            if (owned[process] === record && record.attempt == null) {
                record.naturalCompletion = worker
                true
            } else false
        }
        try { if (observe) worker.start() else worker.cancel() }
        catch (_: Throwable) {
            // A synchronous dispatcher failure must leave the already-published handle
            // owned. A later explicit stop has its own retryable observation worker.
        }
    }

    private suspend fun launch(action: suspend () -> HostedStdioProcess): HostedStdioProcess = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(lock) {
            check(!isClosing) { "CLI session is closing; process launch was refused" }
            launches += job
        }
        try {
            withContext(this@SessionHostedProcessOwner) {
                action().also { process ->
                    // Also supports hosts with a synchronous return. Local/ADB hosts retain earlier.
                    retain(process)
                    check(!isClosing) { "CLI session closed during process launch" }
                }
            }
        } finally { synchronized(lock) { launches -= job } }
    }

    fun closeAdmission() {
        closeAndDispatch(retryFailed = false)
    }

    /** Freeze and publish all exact-owner stop work before cancellation can run finalizers. */
    private fun closeAndDispatch(retryFailed: Boolean): List<Job> {
        val (pending, starts) = synchronized(lock) {
            isClosing = true
            purgeCompletedLocked()
            naturallyFinished.forEach { key -> key.get()?.let { process ->
                owned.getOrPut(process) { ProcessRecord() }
            } }
            naturallyFinished.clear()
            val starts = owned.entries.mapNotNull { (process, record) ->
                val previous = record.attempt
                if (previous == null || retryFailed && previous.outcome?.isFailure == true) {
                    registerStopLocked(process, record)
                } else null
            }
            launches.toList() to starts
        }
        starts.forEach(::startStop)
        // Stop dispatch is already independent. Arbitrary synchronous cancellation handlers
        // can still hold this caller, but cannot prevent the known children from being stopped.
        pending.forEach { it.cancel(CancellationException("CLI session is closing")) }
        return pending
    }

    /** Called only under lock. Publishing a receipt never executes process methods. */
    private fun registerStopLocked(process: HostedStdioProcess, record: ProcessRecord): StopAttempt =
        StopAttempt(process).also { record.attempt = it }

    private fun startStop(attempt: StopAttempt) {
        if (!attempt.started.compareAndSet(false, true)) return
        try {
            // Isolate only synchronous close. A blocked close cannot starve sibling stop
            // requests, while a suspended exit receipt does not pin this native thread.
            val worker = Thread({
                try {
                    // A throwing close is already a failed request. Publish that failure
                    // promptly so a later explicit retry can try stopping a still-live child.
                    attempt.process.close()
                    startReceiptObservation(attempt)
                } catch (failure: Throwable) {
                    finishStop(attempt, Result.failure(normalizeStopFailure(failure)))
                }
            }, "droide-cli-stop").apply { isDaemon = true }
            attempt.closeWorker = worker
            worker.start()
        } catch (failure: Throwable) {
            finishStop(attempt, Result.failure(normalizeStopFailure(failure)))
        }
    }

    private fun startReceiptObservation(attempt: StopAttempt) {
        val handler = CoroutineExceptionHandler { _, failure ->
            finishStop(attempt, Result.failure(normalizeStopFailure(failure)))
        }
        val worker = receiptScope.launch(handler, start = CoroutineStart.LAZY) {
            val result = try {
                attempt.process.awaitExit()
                check(!attempt.process.isAlive) { "CLI process is still alive after cleanup" }
                WorkspaceProcessWriteAdmission.confirmCompletion(attempt.process)
                Result.success(Unit)
            } catch (failure: Throwable) {
                Result.failure(normalizeStopFailure(failure))
            }
            finishStop(attempt, result)
        }
        attempt.receiptWorker = worker
        worker.invokeOnCompletion { failure ->
            // Dispatcher/start failure can prevent entry into the coroutine's try block.
            if (failure != null) finishStop(attempt, Result.failure(normalizeStopFailure(failure)))
        }
        worker.start()
    }

    private fun finishStop(attempt: StopAttempt, result: Result<Unit>) {
        var naturalCompletion: Job? = null
        val publish = synchronized(lock) {
            if (attempt.outcome != null) false
            else {
                attempt.outcome = result
                val record = owned[attempt.process]
                if (result.isSuccess && record?.attempt === attempt) {
                    naturalCompletion = record.naturalCompletion
                    releaseCompletedLocked(attempt.process)
                }
                true
            }
        }
        // Unconfined observers may resume inline. Never resume them under the owner monitor.
        if (publish) {
            attempt.completion.complete(result)
            naturalCompletion?.cancel()
        }
    }

    private fun normalizeStopFailure(failure: Throwable): Exception =
        failure as? Exception ?: IllegalStateException("CLI process cleanup failed", failure)

    suspend fun stopAndAwait() = cleanup.withLock {
        // Only a new explicit invocation retries an already-failed attempt. Pending and
        // successful identities remain deduplicated, including after an observer timeout/cancel.
        closeAndDispatch(retryFailed = true).joinAll()
        val snapshot = synchronized(lock) { owned.values.map { checkNotNull(it.attempt) } }
        OwnedResourceCleanup.runSuspend(*snapshot.map { attempt -> suspend {
            attempt.completion.await().getOrThrow()
        } }.toTypedArray()).getOrThrow()
        check(synchronized(lock) { launches.isEmpty() && owned.isEmpty() }) {
            "A CLI process is still being handed off; retry cleanup before reconnecting"
        }
    }

    fun bind(delegate: StdioProcessHost): StdioProcessHost = object : StdioProcessHost by delegate {
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) =
            launch { delegate.start(argv, environment, resourceLimits) }

        override suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String) =
            launch { delegate.startInWorkspace(argv, environment, remoteCwd) }

        override suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String,
            resourceLimits: ProcessResourceLimits?) = launch { delegate.startInWorkspace(argv, environment, remoteCwd, resourceLimits) }
    }
}

/** Published in the host before bootstrap starts; survives failed or cancelled initialization. */
internal class WorkspaceAgentSessionLifetime(parent: CoroutineScope) {
    val processes = SessionHostedProcessOwner()
    private val job = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + job)
    @Volatile var rpc: AcpNdjsonConnection? = null
    private val cleanup = Mutex()

    fun requestStop(): Result<Unit> {
        processes.closeAdmission()
        return OwnedResourceCleanup.run({ rpc?.close() }, { job.cancel() })
    }

    suspend fun closeAndAwait() = withContext(NonCancellable) {
        cleanup.withLock {
            try {
                withTimeout(15_000) {
                    OwnedResourceCleanup.runSuspend(
                        { requestStop().getOrThrow() },
                        { processes.stopAndAwait() },
                        { job.cancelAndJoin() },
                        { rpc?.awaitClosed() },
                    ).getOrThrow()
                }
            } catch (failure: Exception) {
                throw IllegalStateException("CLI cleanup is not confirmed; the session remains owned. Retry cleanup before reconnecting.", failure)
            }
        }
    }
}

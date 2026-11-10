package com.baystudio.droide.core

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Exact handles only. No process-name matching, PID scan, or inferred remote-tree ownership. */
internal object ExitHostedProcessRegistry {
    private val owners = ExitHostedProcessOwners(
        isOpen = { ApplicationWorkAdmission.isOpen },
        isCurrentOpen = ApplicationWorkAdmission::isCurrentOpen,
    )
    fun observe(): ExitHostedProcessOwners.Observation = owners.observe()
    fun retain(process: HostedStdioProcess) = owners.retain(process)
    suspend fun admit(process: HostedStdioProcess, epoch: String): HostedStdioProcess = owners.admit(process, epoch)
    suspend fun stopAndAwaitAll() = owners.stopAndAwaitAll()
}

/** Isolated exact-owner ledger; admission predicates keep lifecycle tests independent of Android. */
internal class ExitHostedProcessOwners(
    private val isOpen: () -> Boolean,
    private val isCurrentOpen: (String) -> Boolean,
    private val dispatchClose: (() -> Unit) -> Unit = { action ->
        Thread(action, "droide-exit-stop").apply { isDaemon = true }.start()
    },
) {
    private class ProcessRecord {
        var observation: Any = Any()
        var completedObservation: Any? = null
        var attempt: StopAttempt? = null
    }
    private class RetainedObservation(val record: ProcessRecord, val observation: Any)
    private class StopAttempt(
        val process: HostedStdioProcess,
        val record: ProcessRecord,
        val observation: Any,
    ) {
        val started = AtomicBoolean(false)
        val completion = CompletableDeferred<Result<Unit>>()
        var outcome: Result<Unit>? = null // accessed only under lock
    }
    private val lock = Any()
    private val owned = IdentityHashMap<HostedStdioProcess, ProcessRecord>()
    private var observationVersion: Any = Any() // guarded by lock
    class Observation internal constructor(val retainedHandles: Int, val pendingCleanup: Int,
                                          val failedCleanup: Int, val completionObserved: Int,
                                          private val validateState: () -> Unit) {
        fun validate() = validateState()
    }

    /** Application-wide retained receipts, not live OS process or root/descriptor proof. No process
     * property, callback, PID or toString is evaluated under this monitor. */
    fun observe(): Observation = synchronized(lock) {
        val version = observationVersion
        Observation(owned.size, owned.values.count { it.attempt != null && it.attempt?.outcome == null },
            owned.values.count { it.attempt?.outcome?.isFailure == true },
            owned.values.count { it.completedObservation === it.observation }) {
            synchronized(lock) { check(observationVersion === version) { "Retained process ownership changed during review" } }
        }
    }
    // Observation cancellation must not abandon cleanup, and a suspended receipt must not retain the
    // native thread used to isolate a synchronous blocking close.
    private val observer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cleanup ownership must be published before a cancellable process-return handoff. */
    fun retain(process: HostedStdioProcess) { retainObservation(process) }

    private fun retainObservation(process: HostedStdioProcess): RetainedObservation {
        val observation = Any()
        val record = synchronized(lock) {
            observationVersion = Any()
            owned.getOrPut(process) { ProcessRecord() }.also {
                it.observation = observation
                it.completedObservation = null
            }
        }
        observer.launch {
            try {
                process.awaitExit()
                check(!process.isAlive) { "Owned process still reports live after completion" }
                WorkspaceProcessWriteAdmission.confirmCompletion(process)
                synchronized(lock) {
                    if (owned[process] === record && record.observation === observation) {
                        observationVersion = Any()
                        record.completedObservation = observation
                        // Natural completion cannot certify that a pending/failed close returned. Preserve its exact
                        // attempt for Exit and retry.
                        if (record.attempt == null || record.attempt?.outcome?.isSuccess == true) owned.remove(process)
                    }
                }
            } catch (_: Throwable) {
                // Failed receipt/custody remains owned; Exit must report it.
            }
        }
        return RetainedObservation(record, observation)
    }

    suspend fun admit(process: HostedStdioProcess, epoch: String): HostedStdioProcess {
        val retained = retainObservation(process)
        if (!isCurrentOpen(epoch)) {
            val attempt = synchronized(lock) {
                // Keep a completed attempt if this exact record already retired while admission was checking its
                // epoch; do not replay that successful stop.
                val record = owned[process] ?: retained.record
                record.attempt ?: run {
                    owned[process] = record
                    // A newer retain may have arrived while the epoch predicate ran. This rejected handoff cannot
                    // retire that newer observation.
                    registerStopLocked(process, record, retained.observation)
                }
            }
            startStop(attempt)
            // The rejected handoff must still await cleanup when its caller is cancelled. It shares Exit's
            // attempt instead of invoking a second inline close.
            withContext(NonCancellable) { attempt.completion.await().getOrThrow() }
            throw CancellationException("Process admission closed for Droide Exit")
        }
        return process
    }

    /** Publish attempts without running any process methods while holding the ledger. */
    private fun registerStopLocked(
        process: HostedStdioProcess,
        record: ProcessRecord,
        observation: Any = record.observation,
    ): StopAttempt = StopAttempt(process, record, observation).also { observationVersion = Any(); record.attempt = it }

    private fun startStop(attempt: StopAttempt) {
        if (!attempt.started.compareAndSet(false, true)) return
        try {
            dispatchClose {
                try {
                    attempt.process.close()
                    startReceiptObservation(attempt)
                } catch (failure: Throwable) {
                    finishStop(attempt, Result.failure(normalizeFailure(failure)))
                }
            }
        } catch (failure: Throwable) {
            finishStop(attempt, Result.failure(normalizeFailure(failure)))
        }
    }

    private fun startReceiptObservation(attempt: StopAttempt) {
        val handler = CoroutineExceptionHandler { _, failure ->
            finishStop(attempt, Result.failure(normalizeFailure(failure)))
        }
        val worker = observer.launch(handler, start = CoroutineStart.LAZY) {
            val result = try {
                attempt.process.awaitExit()
                check(!attempt.process.isAlive) { "Owned process still reports live after completion" }
                WorkspaceProcessWriteAdmission.confirmCompletion(attempt.process)
                Result.success(Unit)
            } catch (failure: Throwable) {
                Result.failure(normalizeFailure(failure))
            }
            finishStop(attempt, result)
        }
        worker.invokeOnCompletion { failure ->
            // A dispatch failure may prevent entry into the coroutine's try block.
            if (failure != null) finishStop(attempt, Result.failure(normalizeFailure(failure)))
        }
        worker.start()
    }

    private fun finishStop(attempt: StopAttempt, result: Result<Unit>) {
        val publish = synchronized(lock) {
            if (attempt.outcome != null) false
            else {
                observationVersion = Any()
                attempt.outcome = result
                val record = attempt.record
                if (result.isSuccess && owned[attempt.process] === record && record.attempt === attempt &&
                    (record.observation === attempt.observation || record.completedObservation === record.observation)) {
                    owned.remove(attempt.process)
                }
                true
            }
        }
        // Unconfined waiters may resume inline. Never resume them under the ledger lock.
        if (publish) attempt.completion.complete(result)
    }

    private fun normalizeFailure(failure: Throwable): Throwable = when (failure) {
        is Exception, is LinkageError -> failure
        else -> IllegalStateException("Owned process cleanup failed", failure)
    }

    suspend fun stopAndAwaitAll() {
        check(!isOpen()) { "Close work admission before stopping processes" }
        val attempts = synchronized(lock) {
            owned.entries.map { (process, record) ->
                val previous = record.attempt
                // Only an explicit new invocation retries a settled failure. Pending work stays single-flight
                // after an observer timeout or cancellation.
                if (previous == null || previous.outcome?.isFailure == true) registerStopLocked(process, record)
                else previous
            }
        }
        // Dispatch EVERY exact close independently before waiting for any close/exit receipt. A held
        // synchronous capture cancellation cannot starve its siblings.
        attempts.forEach(::startStop)
        OwnedResourceCleanup.runSuspend(*attempts.map { attempt -> suspend {
            attempt.completion.await().getOrThrow()
        } }.toTypedArray()).getOrThrow()
        check(synchronized(lock) { owned.isEmpty() }) { "A previously admitted process is still being handed off; retry Exit" }
        // No permanent closing flag: a later explicit user launch can open a new epoch.
    }
}

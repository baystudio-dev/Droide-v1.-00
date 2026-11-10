package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One private transport belongs to one form owner. prepare() performs only the CSRF GET. */
internal interface BugReportTransport {
    suspend fun prepare(): BugReportPreparation
    suspend fun submit(snapshot: BugReportDraft, onPostStarted: () -> Unit): BugReportOutcome
    fun clearSession()
    fun close()
}

/** One operation at a time, even when taps/rotation race. */

internal class BugReportCoordinator(
    private val transport: BugReportTransport = OfficialBugReportTransport(),
    initiallyUnknown: Boolean = false,
) {
    private class Operation(val job: Job) { val postStarted = AtomicBoolean(false) }
    private val active = AtomicReference<Operation?>()
    private val closed = AtomicBoolean(false)
    private val unknown = AtomicBoolean(initiallyUnknown)
    val requiresUnknownRetry: Boolean get() = unknown.get()

    suspend fun prepare(): BugReportPreparation = coroutineScope {
        if (closed.get()) return@coroutineScope BugReportPreparation.CLOSED
        val operation = Operation(requireNotNull(currentCoroutineContext()[Job]))
        if (!active.compareAndSet(null, operation)) return@coroutineScope BugReportPreparation.BUSY
        try {
            currentCoroutineContext().ensureActive()
            if (closed.get()) BugReportPreparation.CLOSED else transport.prepare()
        } catch (cancelled: CancellationException) {
            transport.clearSession()
            throw cancelled
        } catch (_: Exception) {
            transport.clearSession()
            BugReportPreparation.FAILED
        } finally {
            active.compareAndSet(operation, null)
        }
    }

    suspend fun submit(draft: BugReportDraft, confirmUnknownRetry: Boolean = false): BugReportOutcome = coroutineScope {
        if (closed.get()) return@coroutineScope BugReportOutcome.Closed
        val snapshot = draft.copy()
        val errors = BugReportPolicy.validate(snapshot)
        if (errors.isNotEmpty()) return@coroutineScope BugReportOutcome.Invalid(errors)
        val operation = Operation(requireNotNull(currentCoroutineContext()[Job]))
        if (!active.compareAndSet(null, operation)) return@coroutineScope BugReportOutcome.Busy
        try {
            currentCoroutineContext().ensureActive()
            if (closed.get()) return@coroutineScope BugReportOutcome.Closed
            if (unknown.get() && !confirmUnknownRetry) return@coroutineScope BugReportOutcome.RetryConfirmationRequired
            val result = transport.submit(snapshot) {
                operation.job.ensureActive()
                operation.postStarted.set(true)
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is BugReportOutcome.Accepted -> unknown.set(false)
                BugReportOutcome.UnknownSubmission -> unknown.set(true)
                else -> Unit
            }
            result
        } catch (cancelled: CancellationException) {
            if (operation.postStarted.get()) unknown.set(true)
            transport.clearSession()
            throw cancelled
        } catch (_: Exception) {
            transport.clearSession()
            if (operation.postStarted.get()) {
                unknown.set(true)
                BugReportOutcome.UnknownSubmission
            } else BugReportOutcome.PreparationFailed
        } finally {
            active.compareAndSet(operation, null)
        }
    }

    fun cancel() {
        val operation = active.get()
        if (operation?.postStarted?.get() == true) unknown.set(true)
        operation?.job?.cancel(CancellationException("Bug report cancelled locally"))
        transport.clearSession()
    }

    fun close() {
        closed.set(true)
        cancel()
        transport.close()
    }
}

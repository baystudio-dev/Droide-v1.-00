package com.baystudio.droide.core

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BugReportCoordinatorTest {
    private class Fake : BugReportTransport {
        var prepareCount=0; var submitCount=0; var clears=0; var closes=0
        var submitted: BugReportDraft? = null
        var result: BugReportOutcome = BugReportOutcome.Accepted("ticket-1")
        var beforePost: suspend () -> Unit = {}
        var afterPost: suspend () -> Unit = {}
        var prepareBlock: suspend () -> Unit = {}
        override suspend fun prepare(): BugReportPreparation { prepareCount++; prepareBlock(); return BugReportPreparation.READY }
        override suspend fun submit(snapshot: BugReportDraft, onPostStarted: () -> Unit): BugReportOutcome {
            submitCount++; submitted=snapshot; beforePost(); onPostStarted(); afterPost(); return result
        }
        override fun clearSession() { clears++ }
        override fun close() { closes++ }
    }

    @Test fun invalidDraftNeverPreparesOrSends() = runBlocking {
        val fake=Fake(); val c=BugReportCoordinator(fake)
        assertTrue(c.submit(BugReportDraft()) is BugReportOutcome.Invalid)
        assertEquals(0, fake.prepareCount); assertEquals(0, fake.submitCount)
    }

    @Test fun prepareDoesNotPostAndSubmitUsesImmutableSnapshot() = runBlocking {
        val fake=Fake(); val c=BugReportCoordinator(fake)
        assertEquals(BugReportPreparation.READY,c.prepare()); assertEquals(0,fake.submitCount)
        val draft=validBugReport()
        assertEquals(BugReportOutcome.Accepted("ticket-1"),c.submit(draft))
        assertEquals(draft,fake.submitted); assertNotSame(draft,fake.submitted)
    }

    @Test fun repeatedClicksCannotQueueAnotherPost() = runBlocking {
        val hold=CompletableDeferred<Unit>(); val entered=CompletableDeferred<Unit>()
        val fake=Fake().apply { afterPost={entered.complete(Unit);hold.await()} }; val c=BugReportCoordinator(fake)
        val first=async { c.submit(validBugReport()) }; entered.await()
        repeat(10) { assertEquals(BugReportOutcome.Busy,c.submit(validBugReport())) }
        assertEquals(BugReportPreparation.BUSY,c.prepare()); assertEquals(1,fake.submitCount)
        hold.complete(Unit); first.await(); assertEquals(1,fake.submitCount)
    }

    @Test fun cannotSubmitDuringPreparation() = runBlocking {
        val hold=CompletableDeferred<Unit>(); val entered=CompletableDeferred<Unit>()
        val fake=Fake().apply { prepareBlock={entered.complete(Unit);hold.await()} }; val c=BugReportCoordinator(fake)
        val preparing=async { c.prepare() }; entered.await()
        assertEquals(BugReportOutcome.Busy,c.submit(validBugReport()))
        assertEquals(BugReportPreparation.BUSY,c.prepare()); hold.complete(Unit); preparing.await()
        assertEquals(0,fake.submitCount)
    }

    @Test fun unknownRequiresSeparateExplicitConfirmationAndNeverRetriesItself() = runBlocking {
        val fake=Fake().apply {result=BugReportOutcome.UnknownSubmission}; val c=BugReportCoordinator(fake)
        assertEquals(BugReportOutcome.UnknownSubmission,c.submit(validBugReport()))
        assertTrue(c.requiresUnknownRetry)
        repeat(3) { assertEquals(BugReportOutcome.RetryConfirmationRequired,c.submit(validBugReport())) }
        assertEquals(1,fake.submitCount)
        fake.result=BugReportOutcome.Accepted()
        assertEquals(BugReportOutcome.Accepted(),c.submit(validBugReport(),confirmUnknownRetry=true))
        assertEquals(2,fake.submitCount); assertFalse(c.requiresUnknownRetry)
    }

    @Test fun restoredPendingSubmissionRequiresConfirmationWithoutNetwork() = runBlocking {
        val fake=Fake(); val c=BugReportCoordinator(fake,initiallyUnknown=true)
        assertEquals(BugReportOutcome.RetryConfirmationRequired,c.submit(validBugReport()))
        assertEquals(0,fake.submitCount); assertTrue(c.requiresUnknownRetry)
    }

    @Test fun cancellationBeforeDispatchIsNotReportedAsSent() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val fake=Fake().apply {beforePost={entered.complete(Unit);awaitCancellation()}}
        val c=BugReportCoordinator(fake); val job=launch {c.submit(validBugReport())}; entered.await()
        c.cancel(); job.join(); assertTrue(job.isCancelled); assertFalse(c.requiresUnknownRetry)
        assertTrue(fake.clears>0); assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun cancellationAfterDispatchKeepsUnknownAndDoesNotCancelOwnerScope() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val fake=Fake().apply {afterPost={entered.complete(Unit);awaitCancellation()}}
        val c=BugReportCoordinator(fake); val job=launch {c.submit(validBugReport())}; entered.await()
        c.cancel(); job.join(); assertTrue(job.isCancelled); assertTrue(c.requiresUnknownRetry)
        assertEquals(BugReportOutcome.RetryConfirmationRequired,c.submit(validBugReport()))
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun exceptionBeforeDispatchIsPreparationFailure() = runBlocking {
        val fake=Fake().apply {beforePost={throw IOException("fixture")}}; val c=BugReportCoordinator(fake)
        assertEquals(BugReportOutcome.PreparationFailed,c.submit(validBugReport())); assertFalse(c.requiresUnknownRetry)
    }

    @Test fun exceptionAfterDispatchIsUnknownAndKeepsDraftSnapshot() = runBlocking {
        val fake=Fake().apply {afterPost={throw IOException("fixture")}}; val c=BugReportCoordinator(fake)
        val draft=validBugReport()
        assertEquals(BugReportOutcome.UnknownSubmission,c.submit(draft)); assertTrue(c.requiresUnknownRetry)
        assertEquals(draft,fake.submitted); assertEquals(1,fake.submitCount)
    }

    @Test fun explicitRetryRejectionDoesNotEraseOriginalUnknown() = runBlocking {
        val fake=Fake().apply {result=BugReportOutcome.Rejected(BugReportError.RATE_LIMIT)}
        val c=BugReportCoordinator(fake,initiallyUnknown=true)
        assertTrue(c.submit(validBugReport(),confirmUnknownRetry=true) is BugReportOutcome.Rejected)
        assertTrue(c.requiresUnknownRetry)
    }

    @Test fun closeIsTerminalAndCancelsPendingPreparation() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val fake=Fake().apply {prepareBlock={entered.complete(Unit);awaitCancellation()}}
        val c=BugReportCoordinator(fake); val job=launch {c.prepare()}; entered.await()
        c.close(); job.join()
        assertTrue(job.isCancelled); assertEquals(1,fake.closes)
        assertEquals(BugReportPreparation.CLOSED,c.prepare()); assertEquals(BugReportOutcome.Closed,c.submit(validBugReport()))
    }
}

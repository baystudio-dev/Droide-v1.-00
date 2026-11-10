package com.baystudio.droide.core

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFailureBoundaryTest {
    @Test fun failedOperationIsReportedWhileIndependentWorkAndOwnerRemainActive() = runBlocking {
        val reports = mutableListOf<Throwable>()
        val boundary = WorkspaceFailureBoundary(reports::add)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + boundary.handler)
        val sibling = scope.launch { awaitCancellation() }
        try {
            val failure = IOException("Simulated failed runtime operation")
            val failed = scope.launch { throw failure }
            failed.join()
            assertTrue(failed.isCancelled)
            assertEquals(listOf(failure), reports)
            assertTrue(scope.isActive)
            assertTrue(sibling.isActive)
            var retried = false
            scope.launch { retried = true }.join()
            assertTrue(retried)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun normalStopIsNotReportedAndFatalVmErrorsAreNotHidden() {
        val reports = mutableListOf<Throwable>()
        val boundary = WorkspaceFailureBoundary(reports::add)
        boundary.handler.handleException(kotlin.coroutines.EmptyCoroutineContext, CancellationException("Stop"))
        assertTrue(reports.isEmpty())
        val linkage = UnsatisfiedLinkError("Unavailable optional native operation")
        boundary.handler.handleException(kotlin.coroutines.EmptyCoroutineContext, linkage)
        assertEquals(listOf(linkage), reports)
        val fatal = OutOfMemoryError("Simulated VM failure")
        assertSame(fatal, runCatching {
            boundary.handler.handleException(kotlin.coroutines.EmptyCoroutineContext, fatal)
        }.exceptionOrNull())
    }

    @Test fun repeatedFailuresDoNotFloodUiAndTheFirstNoticeIsImmediate() {
        var nanos = 1L
        val notice = WorkspaceFailureNotice { nanos }
        assertEquals(1L, notice.next()?.id)
        repeat(1_000) { nanos += 1_000L; assertNull(notice.next()) }
        nanos = 10_000_000_001L
        assertEquals(2L, notice.next()?.id)
    }
}

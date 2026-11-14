package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExitOperationFenceR64Test {
    @Test fun closingAdmissionRetainsExactJobsUntilTheirFinalizersFinish() = runBlocking {
        var applicationOpen = true
        val fence = ExitOperationFence(applicationOpen = { applicationOpen })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        val work = launch {
            val registration = fence.track(currentCoroutineContext().job)
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { release.await(); published = true; registration.close() } }
        }
        started.await()
        applicationOpen = false
        assertEquals(listOf(work), fence.beginExit())
        assertFalse(fence.isOpen)
        assertTrue(runCatching { fence.track(Job()) }.exceptionOrNull() is CancellationException)
        work.cancel()
        yield()
        assertFalse(published)
        assertEquals(listOf(work), fence.beginExit())
        release.complete(Unit); work.join()
        assertTrue(published)
        assertTrue(fence.beginExit().isEmpty())
        applicationOpen = true // Reopening the application never revives an old owner.
        assertFalse(fence.isOpen)
    }

    @Test fun nestedOperationsKeepTheirSharedExactJobUntilBothRetire() {
        val fence = ExitOperationFence(applicationOpen = { true })
        val job = Job()
        val outer = fence.track(job); val inner = fence.track(job)
        inner.close()
        assertEquals(listOf(job), fence.beginExit())
        outer.close()
        assertTrue(fence.beginExit().isEmpty())
        job.cancel()
    }
}

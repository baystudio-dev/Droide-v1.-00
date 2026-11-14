package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkspaceStartupOwnershipTest {
    @Test
    fun dispatcherReturnCancellationReleasesAcquiredRuntimeExactlyOnce() = runTest {
        val worker = StandardTestDispatcher(testScheduler, "builder")
        var releases = 0
        var delivered = false
        val owner = launch {
            val ownerJob = currentCoroutineContext()[Job]!!
            WorkspaceStartupResources.prepare(worker) {
                own { releases++ }
                ownerJob.cancel(CancellationException("Activity gone"))
                Any()
            }
            delivered = true
        }
        owner.join()
        assertTrue(owner.isCancelled)
        assertFalse(delivered)
        assertEquals(1, releases)
    }

    @Test
    fun failedPartialConstructionReleasesEveryAcquiredPartInReverseOrder() = runTest {
        val released = mutableListOf<String>()
        val expected = IllegalStateException("constructor failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val failure = runCatching {
            WorkspaceStartupResources.prepare(StandardTestDispatcher(testScheduler)) {
                own { released += "first" }
                own { released += "second"; throw cleanupFailure }
                own { released += "third" }
                throw expected
            }
        }.exceptionOrNull()
        assertEquals(expected::class.java, failure!!::class.java)
        assertEquals(expected.message, failure.message)
        // Keep original constructor error and cleanup evidence even when
        // coroutine debug recovery copies an exception across withContext.
        val causeChain = generateSequence(failure) { it.cause }.take(32).toList()
        assertSame(expected, causeChain.last())
        assertEquals(listOf("third", "second", "first"), released)
        val suppressed = causeChain.flatMap { it.suppressed.toList() }.distinct()
        assertEquals(1, suppressed.size)
        assertSame(cleanupFailure, generateSequence(suppressed.single()) { it.cause }.take(32).last())
    }

    @Test
    fun completeRuntimeTakesOverPartialPartsAndSuccessfulReturnDoesNotCloseIt() = runTest {
        var partialReleases = 0
        var runtimeReleases = 0
        val value = Any()
        assertSame(value, WorkspaceStartupResources.prepare(StandardTestDispatcher(testScheduler)) {
            own { partialReleases++ }
            consolidate { runtimeReleases++ }
            value
        })
        assertEquals(0, partialReleases)
        assertEquals(0, runtimeReleases)
    }

    @Test
    fun consolidatedRuntimeIsClosedInsteadOfClosingItsPartsAgainOnCancellation() = runTest {
        var partialReleases = 0
        var runtimeReleases = 0
        val acquired = CompletableDeferred<Unit>()
        val job = launch {
            WorkspaceStartupResources.prepare(StandardTestDispatcher(testScheduler)) {
                own { partialReleases++ }
                consolidate { runtimeReleases++ }
                acquired.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        acquired.await()
        job.cancel()
        job.join()
        assertEquals(0, partialReleases)
        assertEquals(1, runtimeReleases)
    }

    @Test
    fun stageDiscardedReturnIsReleasedBeforeFallbackAndPublishedFallbackSurvives() = runTest {
        val released = mutableListOf<Any>()
        val candidate = WorkspaceStartupCandidate<Any> { released += it }
        val first = Any()
        val fallback = Any()
        val failure = runCatching {
            WorkspaceStartupPolicy.bounded(WorkspaceStartupPhase.ACTIVE_RUNTIME, 1_000) {
                val prepared = WorkspaceStartupResources.prepare(StandardTestDispatcher(testScheduler)) {
                    own { released += first }
                    first
                }
                candidate.retain(prepared)
                withContext(NonCancellable) { delay(1_001) }
                prepared
            }
        }.exceptionOrNull()
        assertTrue(failure is WorkspaceStartupTimeoutException)
        candidate.discardUnlessPublished()
        assertEquals(listOf(first), released)
        candidate.retain(fallback)
        candidate.discardUnlessPublished(fallback)
        candidate.discardUnlessPublished()
        assertEquals(listOf(first), released)
    }

    @Test
    fun publicationUsesIdentityAndUnpublishedCandidateCleanupIsIdempotent() = runTest {
        var releases = 0
        val candidate = WorkspaceStartupCandidate<String> { releases++ }
        val first = String(charArrayOf('a'))
        candidate.retain(first)
        candidate.discardUnlessPublished(String(charArrayOf('a')))
        candidate.discardUnlessPublished()
        assertEquals(1, releases)
    }
}

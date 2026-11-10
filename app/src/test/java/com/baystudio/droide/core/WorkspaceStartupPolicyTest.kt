package com.baystudio.droide.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceStartupPolicyTest {
    @Test
    fun safRuntimeGetsMoreTimeThanPrivateRuntimeButAllBudgetsStayBounded() {
        val local = DroideProject("local", "Local", "/tmp/local")
        val saf = DroideProject("saf", "SAF", "/tmp/saf", "content://example/tree")

        assertEquals(WorkspaceStartupPolicy.LOCAL_RUNTIME_TIMEOUT_MS, WorkspaceStartupPolicy.runtimeTimeoutMs(local))
        assertEquals(WorkspaceStartupPolicy.SAF_RUNTIME_TIMEOUT_MS, WorkspaceStartupPolicy.runtimeTimeoutMs(saf))
        assertTrue(WorkspaceStartupPolicy.SAF_RUNTIME_TIMEOUT_MS > WorkspaceStartupPolicy.LOCAL_RUNTIME_TIMEOUT_MS)
        listOf(
            WorkspaceStartupPolicy.RUNTIME_REGISTRIES_TIMEOUT_MS,
            WorkspaceStartupPolicy.PROJECT_CATALOG_TIMEOUT_MS,
            WorkspaceStartupPolicy.LOCAL_RUNTIME_TIMEOUT_MS,
            WorkspaceStartupPolicy.SAF_RUNTIME_TIMEOUT_MS,
            WorkspaceStartupPolicy.FALLBACK_RUNTIME_TIMEOUT_MS,
            WorkspaceStartupPolicy.PUBLISH_RUNTIME_TIMEOUT_MS,
        ).forEach { timeout -> assertTrue(timeout in 1_000L..60_000L) }
    }

    @Test
    fun timeoutBecomesExplicitStartupFailureInsteadOfCancellationLookingLikeSuccess() = runBlocking {
        val failure = runCatching {
            WorkspaceStartupPolicy.bounded(
                phase = WorkspaceStartupPhase.ACTIVE_RUNTIME,
                timeoutMs = 1_000L,
                projectId = "slow-project",
            ) {
                delay(1_500L)
            }
        }.exceptionOrNull()

        assertTrue(failure is WorkspaceStartupTimeoutException)
        failure as WorkspaceStartupTimeoutException
        assertEquals(WorkspaceStartupPhase.ACTIVE_RUNTIME, failure.phase)
        assertEquals("slow-project", failure.projectId)
        assertEquals(1_000L, failure.timeoutMs)
    }

    @Test
    fun enclosingDeadlineRemainsCancellationWithoutStartingRecovery() = runTest {
        var observed: Throwable? = null
        val failure = runCatching {
            withTimeout(10) {
                try {
                    WorkspaceStartupPolicy.bounded(WorkspaceStartupPhase.ACTIVE_RUNTIME, 1_000) { delay(100) }
                } catch (error: Throwable) {
                    observed = error
                    throw error
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(observed!!::class.java, failure!!::class.java)
        assertEquals(observed!!.message, failure.message)
        // Debug stack-trace recovery may copy cancellation at a suspension boundary; its causal root
        // must still be the original deadline.
        assertSame(causalRoot(observed!!), causalRoot(failure))
    }

    @Test
    fun nestedOperationDeadlineIsNotMisreportedAsStageDeadline() = runTest {
        var nested: Throwable? = null
        val failure = runCatching {
            WorkspaceStartupPolicy.bounded(WorkspaceStartupPhase.ACTIVE_RUNTIME, 1_000) {
                try { withTimeout(10) { delay(100) } }
                catch (error: Throwable) { nested = error; throw error }
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(nested!!::class.java, failure!!::class.java)
        assertEquals(nested!!.message, failure.message)
        assertSame(causalRoot(nested!!), causalRoot(failure))
    }

    @Test
    fun ownedDeadlineStillAllowsAFreshRetry() = runTest {
        val failure = runCatching {
            WorkspaceStartupPolicy.bounded(WorkspaceStartupPhase.ACTIVE_RUNTIME, 1_000) { delay(2_000) }
        }.exceptionOrNull()
        assertTrue(failure is WorkspaceStartupTimeoutException)
        assertEquals("ready", WorkspaceStartupPolicy.bounded(WorkspaceStartupPhase.ACTIVE_RUNTIME, 1_000) { "ready" })
    }

    @Test
    fun nullableSuccessIsNotMistakenForDeadlineFailureOrExecutedAgain() = runTest {
        var calls = 0
        assertNull(WorkspaceStartupPolicy.bounded<String?>(WorkspaceStartupPhase.ACTIVE_RUNTIME, 1_000) { calls++; null })
        assertEquals(1, calls)
    }

    private fun causalRoot(failure: Throwable): Throwable =
        generateSequence(failure) { it.cause }.take(32).last()

}

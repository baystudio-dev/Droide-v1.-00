package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InstallerPrerequisiteRestorationTest {
    @Test fun successReturnsOnlyAfterTheOriginalDefaultIsRestored() = runBlocking {
        var restored = false
        val result = withInstallerPrerequisiteRestoration(restore = { restored = true }) { "installed" }
        assertEquals("installed", result)
        assertTrue(restored)
    }

    @Test fun restorationFailureAfterInstallCannotBeReportedAsSuccess() = runBlocking {
        val restoration = IllegalStateException("Previous default failed health check")
        try {
            withInstallerPrerequisiteRestoration(restore = { throw restoration }) { "committed package" }
            fail("Failed restoration must not report success")
        } catch (failure: IllegalStateException) {
            assertRetainsFailure(restoration, failure.cause)
            assertTrue(failure.message!!.contains("previous prerequisite default"))
        }
    }

    @Test fun installationFailureIsPreservedWhenRestorationAlsoFails() = runBlocking {
        val original = IllegalArgumentException("Artifact failed checksum")
        val restoration = IllegalStateException("Could not restore default")
        try {
            withInstallerPrerequisiteRestoration(restore = { throw restoration }) { throw original }
            fail("Original failure must propagate")
        } catch (actual: IllegalArgumentException) {
            assertRetainsFailure(original, actual)
            assertEquals(1, actual.suppressed.size)
            assertRetainsFailure(restoration, actual.suppressed.single())
        }
    }

    @Test fun cancellationRunsRestorationWithItsOwnLiveContextAndThenPropagates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var restored = false
        val operation = launch {
            withInstallerPrerequisiteRestoration(restore = {
                assertTrue(currentCoroutineContext().isActive)
                restored = true
            }) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        operation.cancel(CancellationException("User canceled"))
        operation.join()
        assertTrue(restored)
        assertTrue(operation.isCancelled)
    }

    @Test fun restorationHasABoundedDeadlineAndRetainsOriginalFailure() = runBlocking {
        val original = IllegalArgumentException("Install failed")
        try {
            withInstallerPrerequisiteRestoration(timeoutMs = 5, restore = { awaitCancellation() }) { throw original }
            fail("Original failure must propagate")
        } catch (actual: IllegalArgumentException) {
            assertRetainsFailure(original, actual)
            assertEquals(1, actual.suppressed.size)
            assertTrue(actual.suppressed.single() is kotlinx.coroutines.TimeoutCancellationException)
        }
    }
}

/** Coroutine debug recovery may copy exceptions; verify the failure type and reason. */
private fun assertRetainsFailure(expected: Throwable, actual: Throwable?) {
    assertNotNull("Original failure was lost", actual)
    assertEquals(expected.javaClass, actual!!.javaClass)
    assertEquals(expected.message, actual.message)
}

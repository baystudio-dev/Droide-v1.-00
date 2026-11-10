package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DevelopmentExtensionRefreshAdmissionTest {
    private fun admission(owner: InstallerOperationCoordinator) = DevelopmentExtensionRefreshAdmission { block ->
        check(owner.publish(block = block)) { "Closed owner" }
    }

    @Test fun aToBToAReturningToTheSameValueCannotAdmitTheFirstA() = runBlocking {
        val owner = InstallerOperationCoordinator()
        val gate = admission(owner)
        val firstA = requireNotNull(gate.begin())
        val b = requireNotNull(gate.begin())
        val latestA = requireNotNull(gate.begin())
        assertTrue(firstA < b && b < latestA)
        val effects = mutableListOf<String>()
        fun commit(label: String) {
            effects += "$label:preferences"
            effects += "$label:capabilities"
            effects += "$label:snapshot"
        }
        assertFalse(gate.publish(firstA) { commit("old A") })
        assertFalse(gate.publish(b) { commit("B") })
        assertTrue(gate.publish(latestA) { commit("latest A") })
        assertFalse(gate.publish(firstA) { commit("late old A") })
        assertEquals(listOf("latest A:preferences", "latest A:capabilities", "latest A:snapshot"), effects)
    }

    @Test fun closeBeforeFinalAdmissionPreventsEveryPublicationSideEffect() = runBlocking {
        val owner = InstallerOperationCoordinator()
        val gate = admission(owner)
        val request = requireNotNull(gate.begin())
        owner.close()
        var preferences = 0
        var capabilities = 0
        var snapshots = 0
        val error = runCatching { gate.publish(request) { preferences++; capabilities++; snapshots++ } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, preferences)
        assertEquals(0, capabilities)
        assertEquals(0, snapshots)
        assertTrue(runCatching { requireNotNull(gate.begin()) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun skippedRefreshDoesNotInvalidateTheCurrentOwnersRead() = runBlocking {
        val owner = InstallerOperationCoordinator()
        val gate = admission(owner)
        val current = requireNotNull(gate.begin())
        assertNull(gate.begin { false })
        var publications = 0
        assertTrue(gate.publish(current) { publications++ })
        assertEquals(1, publications)
        assertEquals(current + 1, requireNotNull(gate.begin()))
    }

    @Test fun cancelledRequestCannotPublishEvenWhenNoSuspensionRemainsBeforeCommit() = runBlocking {
        val owner = InstallerOperationCoordinator()
        val gate = admission(owner)
        val request = requireNotNull(gate.begin())
        var effects = 0
        val job = Job()
        val error = runCatching {
            withContext(job) {
                job.cancel(CancellationException("Request was cancelled during its read"))
                gate.publish(request) { effects++ }
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, effects)
        // A later independent request is still admitted; cancellation is not manager closure.
        assertTrue(gate.publish(requireNotNull(gate.begin())) { effects++ })
        assertEquals(1, effects)
    }

    @Test fun completedOldReadCannotBeReusedAfterANewerFailedRead() = runBlocking {
        val owner = InstallerOperationCoordinator()
        val gate = admission(owner)
        var snapshot = "initial"
        val old = requireNotNull(gate.begin())
        assertTrue(gate.publish(old) { snapshot = "old" })
        requireNotNull(gate.begin())
        assertFalse(gate.publish(old) { snapshot = "resurrected old" })
        assertEquals("old", snapshot)
        assertTrue(gate.publish(requireNotNull(gate.begin())) { snapshot = "retry" })
        assertEquals("retry", snapshot)
    }
}

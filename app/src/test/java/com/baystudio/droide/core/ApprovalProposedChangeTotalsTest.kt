package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Presentation totals must follow the exact request, never create approval authority. */
class ApprovalProposedChangeTotalsTest {
    @Test(timeout = 10_000)
    fun eightThousandCharacterDetailClippingRetainsTotalsAndPlainRequestsStayAbsent() = bounded { manager ->
        val totals = AgentChangeTotals(500L, 200L)
        val detail = "reviewed line\n".repeat(800)
        val counted = request(manager, "counted.txt", totals, detail = detail)
        val pending = requireNotNull(manager.pending.value)
        assertEquals(8_000, pending.detail.length)
        assertEquals(detail.take(8_000), pending.detail)
        assertEquals(totals, pending.proposedChangeTotals)
        assertTrue(manager.resolve(pending.id, ApprovalResolution.DENY))
        assertEquals(ApprovalDecision.Denied, counted.await())

        val plain = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("shell", "A different request", "echo fixture",
                action = "shell", resource = "echo fixture")
        }
        val plainPending = requireNotNull(manager.pending.value)
        assertTrue(plainPending.id > pending.id)
        assertNull(plainPending.proposedChangeTotals)
        assertTrue(manager.resolve(plainPending.id, ApprovalResolution.DENY))
        assertEquals(ApprovalDecision.Denied, plain.await())
        assertNull(manager.pending.value)
    }

    @Test(timeout = 10_000)
    fun queuedReplacementKeepsItsOwnCountsAndRejectsEveryStaleChoice() = bounded { manager ->
        val firstTotals = AgentChangeTotals(1L, 2L)
        val secondTotals = AgentChangeTotals(7L, 3L)
        val first = request(manager, "first.txt", firstTotals)
        val firstRequest = requireNotNull(manager.pending.value)
        val second = request(manager, "second.txt", secondTotals)
        assertEquals(firstRequest, manager.pending.value)
        assertFalse(second.isCompleted)
        assertEquals(firstTotals, manager.pending.value?.proposedChangeTotals)
        first.cancelAndJoin()
        val replacement = requireNotNull(manager.pending.first { it != null && it.id != firstRequest.id })
        assertTrue(replacement.id > firstRequest.id)
        assertEquals("second.txt", replacement.resource)
        assertEquals(secondTotals, replacement.proposedChangeTotals)
        for (resolution in ApprovalResolution.entries) {
            assertFalse(manager.resolve(firstRequest.id, resolution))
            assertEquals(replacement, manager.pending.value)
            assertFalse(second.isCompleted)
            assertFalse(hasGrant(manager, "first.txt"))
            assertFalse(hasGrant(manager, "second.txt"))
        }
        assertTrue(manager.resolve(replacement.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(ApprovalDecision.Approved, second.await())
        assertFalse(manager.resolve(replacement.id, ApprovalResolution.ALLOW_SESSION))
        assertFalse(hasGrant(manager, "second.txt"))
        assertNull(manager.pending.value)
    }

    @Test(timeout = 10_000)
    fun staleValidityCannotGrantAuthorityOrContaminateSameResourceReplacement() = bounded { manager ->
        var current = true
        val staleTotals = AgentChangeTotals(99L, 98L)
        val first = request(manager, "same.txt", staleTotals, isStillCurrent = { current })
        val firstRequest = requireNotNull(manager.pending.value)
        assertEquals(staleTotals, firstRequest.proposedChangeTotals)
        current = false
        assertTrue(manager.resolve(firstRequest.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(ApprovalDecision.Denied, first.await())
        assertFalse(hasGrant(manager, "same.txt"))
        assertNull(manager.pending.value)

        val replacementTotals = AgentChangeTotals(null, null)
        val second = request(manager, "same.txt", replacementTotals)
        val replacement = requireNotNull(manager.pending.value)
        assertTrue(replacement.id > firstRequest.id)
        assertEquals(replacementTotals, replacement.proposedChangeTotals)
        assertFalse(requireNotNull(replacement.proposedChangeTotals).known)
        assertFalse(manager.resolve(firstRequest.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(replacement, manager.pending.value)
        assertFalse(hasGrant(manager, "same.txt"))
        assertTrue(manager.resolve(replacement.id, ApprovalResolution.DENY))
        assertEquals(ApprovalDecision.Denied, second.await())
        assertFalse(hasGrant(manager, "same.txt"))
        assertNull(manager.pending.value)
    }

    private fun CoroutineScope.request(
        manager: ApprovalManager,
        resource: String,
        totals: AgentChangeTotals,
        detail: String = "A bounded reviewed proposal",
        isStillCurrent: (() -> Boolean)? = null,
    ): Deferred<ApprovalDecision> = async(start = CoroutineStart.UNDISPATCHED) {
        manager.requestApproval(
            kind = "write_file", summary = "Review $resource", detail = detail,
            action = "edit", resource = resource, permissionScope = "build",
            provenance = AgentRequestProvenance.primary("r34-session"),
            proposedChangeTotals = totals, isStillCurrent = isStillCurrent,
        )
    }

    private fun hasGrant(manager: ApprovalManager, resource: String) =
        manager.hasSessionApproval("r34-session", "build", "edit", resource)

    private fun bounded(block: suspend CoroutineScope.(ApprovalManager) -> Unit) = runBlocking {
        withTimeout(5_000) { coroutineScope {
            val manager = ApprovalManager()
            try { block(manager) } finally {

                val children = currentCoroutineContext().job.children.toList()
                withContext(NonCancellable) { withTimeout(2_000) {
                    children.forEach { it.cancel() }
                    children.joinAll()
                } }
                manager.clearAllSessionApprovals()
            }
        } }
    }
}

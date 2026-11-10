package com.baystudio.droide.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApprovalInputLifecycleTest {
    private fun expired(review: ApprovalInputReview) {
        try { review.readPage(0); fail("Expected expired review") } catch (_: IllegalStateException) { }
    }
    @Test fun resolutionRetiresFullInputReaderWithoutRequiringReadAll() = runBlocking {
        val manager = ApprovalManager()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("run_command", "Run", "preview", inputReview =
                ApprovalInputReview.text(ApprovalInputReview.Label.COMMAND, "x".repeat(40000)))
        }
        val request = requireNotNull(manager.pending.value)
        val review = requireNotNull(request.inputReview)
        assertTrue(review.readPage(0).hasNext)
        assertTrue(manager.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(ApprovalDecision.Approved, work.await())
        expired(review)
    }
    @Test fun invalidOwnerDeniesApprovalEvenWithoutOpeningViewer() = runBlocking {
        var current = true
        val manager = ApprovalManager()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("run_command", "Run", "preview", inputReview =
                ApprovalInputReview.text(ApprovalInputReview.Label.COMMAND, "echo safe") { check(current) })
        }
        val request = requireNotNull(manager.pending.value)
        current = false
        manager.resolve(request.id, ApprovalResolution.ALLOW_ONCE)
        assertEquals(ApprovalDecision.Denied, work.await())
        expired(requireNotNull(request.inputReview))
    }
    @Test fun cancellationRetiresReader() = runBlocking {
        val manager = ApprovalManager()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("stdin", "Input", "preview", inputReview =
                ApprovalInputReview.text(ApprovalInputReview.Label.STDIN, "input"))
        }
        val review = requireNotNull(manager.pending.value?.inputReview)
        work.cancel(); work.join()
        assertNull(manager.pending.value)
        expired(review)
    }
}

package com.baystudio.droide.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApprovalRequestIdentityTest {
    private fun CoroutineScope.request(
        manager: ApprovalManager,
        resource: String = "http://localhost:3000/page",
        browserContext: BrowserApprovalContext? = null,
    ): Deferred<ApprovalDecision> = async(start = CoroutineStart.UNDISPATCHED) {
        manager.requestApproval(
            kind = "browser",
            summary = "Browser fixture",
            detail = resource,
            action = "browser_local",
            resource = resource,
            permissionScope = "build",
            provenance = AgentRequestProvenance.primary("session-a"),
            browserContext = browserContext,
        )
    }

    private fun hasGrant(manager: ApprovalManager, resource: String = "http://localhost:3000/page"): Boolean =
        manager.hasSessionApproval("session-a", "build", "browser_local", resource)

    @Test fun staleChoicesAfterCancellationCannotAnswerOrGrantForTheReplacement() = runBlocking {
        val manager = ApprovalManager()
        val first = request(manager)
        val firstId = requireNotNull(manager.pending.value).id
        first.cancelAndJoin()
        assertNull(manager.pending.value)

        val second = request(manager, "http://localhost:3001/replacement")
        val secondRequest = requireNotNull(manager.pending.value)
        assertTrue(secondRequest.id > firstId)
        for (resolution in ApprovalResolution.entries) {
            assertFalse(manager.resolve(firstId, resolution))
            assertEquals(secondRequest, manager.pending.value)
            assertFalse(second.isCompleted)
            assertFalse(hasGrant(manager))
            assertFalse(hasGrant(manager, secondRequest.resource))
        }

        assertTrue(manager.resolve(secondRequest.id, ApprovalResolution.DENY))
        assertEquals(ApprovalDecision.Denied, second.await())
        assertNull(manager.pending.value)
    }

    @Test fun duplicateResponsesCannotUpgradeAnAllowOnceDecisionToSessionAuthority() = runBlocking {
        val manager = ApprovalManager()
        val request = request(manager)
        val id = requireNotNull(manager.pending.value).id
        assertTrue(manager.resolve(id, ApprovalResolution.ALLOW_ONCE))
        assertFalse(manager.resolve(id, ApprovalResolution.ALLOW_SESSION))
        assertFalse(manager.resolve(id, ApprovalResolution.DENY))
        assertEquals(ApprovalDecision.Approved, request.await())
        assertFalse(hasGrant(manager))
        assertNull(manager.pending.value)
    }

    @Test fun resumedCallerCanReenterWithoutAnOldResponseConsumingItsNextRequest() = runBlocking {
        val manager = ApprovalManager()
        var firstId = -1L
        var reentrantAccepted: Boolean? = null
        val decisions = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            val firstDecision = manager.requestApproval(
                "browser", "First", "", "browser_local", "http://localhost:3000/first", "build",
                AgentRequestProvenance.primary("session-a"),
            )
            reentrantAccepted = manager.resolve(firstId, ApprovalResolution.ALLOW_SESSION)
            val secondDecision = manager.requestApproval(
                "browser", "Second", "", "browser_local", "http://localhost:3001/second", "build",
                AgentRequestProvenance.primary("session-a"),
            )
            listOf(firstDecision, secondDecision)
        }
        firstId = requireNotNull(manager.pending.value).id

        assertTrue(manager.resolve(firstId, ApprovalResolution.ALLOW_ONCE))
        assertEquals(false, reentrantAccepted)
        val second = requireNotNull(manager.pending.value)
        assertTrue(second.id > firstId)
        assertFalse(manager.resolve(firstId, ApprovalResolution.ALLOW_SESSION))
        assertEquals(second, manager.pending.value)
        assertFalse(hasGrant(manager))
        assertFalse(hasGrant(manager, second.resource))

        assertTrue(manager.resolve(second.id, ApprovalResolution.DENY))
        assertEquals(listOf(ApprovalDecision.Approved, ApprovalDecision.Denied), decisions.await())
    }

    @Test fun cancelledActiveRequestCannotCreateSessionAuthority() = runBlocking {
        val manager = ApprovalManager()
        val request = request(manager)
        val id = requireNotNull(manager.pending.value).id
        request.cancelAndJoin()

        assertNull(manager.pending.value)
        assertFalse(manager.resolve(id, ApprovalResolution.ALLOW_SESSION))
        assertFalse(manager.resolve(id, ApprovalResolution.ALLOW_ONCE))
        assertFalse(hasGrant(manager))
        assertTrue(request.isCancelled)
    }

    @Test fun cancelledContinuationIsRejectedEvenBeforeItsCleanupGetsTheStateLock() = runBlocking {
        val manager = ApprovalManager()
        val request = request(manager)
        val pending = requireNotNull(manager.pending.value)
        // Hold the manager lock to expose the window where continuation cancellation has happened but
        // its cleanup has not yet removed the displayed request.
        val lock = ApprovalManager::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(manager)
        @Suppress("UNCHECKED_CAST")
        val continuations = ApprovalManager::class.java.getDeclaredField("conts").apply { isAccessible = true }
            .get(manager) as Map<Long, CancellableContinuation<ApprovalDecision>>
        val continuation = requireNotNull(continuations[pending.id])
        val cancellationFailure = AtomicReference<Throwable?>(null)
        lateinit var canceller: Thread
        try {
            synchronized(lock) {
                canceller = thread(name = "approval-cancellation-fixture", isDaemon = true) {
                    try {
                        continuation.cancel()
                    } catch (failure: Throwable) {
                        cancellationFailure.set(failure)
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (continuation.isActive && System.nanoTime() < deadline) Thread.yield()
                assertTrue("Cancellation should win before cleanup can acquire the held lock", continuation.isCancelled)
                assertEquals(pending, manager.pending.value)
                assertFalse(manager.resolve(pending.id, ApprovalResolution.ALLOW_SESSION))
                assertEquals(pending, manager.pending.value)
                assertFalse(hasGrant(manager))
            }
        } finally {
            canceller.join(5_000)
            request.cancelAndJoin()
        }
        assertFalse("Cancellation cleanup must finish after the lock is released", canceller.isAlive)
        assertNull(cancellationFailure.get())
        assertNull(manager.pending.value)
        assertFalse(hasGrant(manager))
    }

    @Test fun cancellationBeforeDecisionDeliveryRevokesOnlyThatRequestsSessionGrant() = runBlocking {
        val manager = ApprovalManager()
        val established = request(manager, "http://localhost:3002/established")
        assertTrue(manager.resolve(requireNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(ApprovalDecision.Approved, established.await())

        val pendingDecision = request(manager)
        val id = requireNotNull(manager.pending.value).id

        // This runBlocking dispatcher cannot deliver the resumed value until we yield.
        assertTrue(manager.resolve(id, ApprovalResolution.ALLOW_SESSION))
        assertTrue(hasGrant(manager))
        pendingDecision.cancelAndJoin()
        assertTrue(pendingDecision.isCancelled)
        assertFalse(hasGrant(manager))
        assertTrue(hasGrant(manager, "http://localhost:3002/established"))
        assertNull(manager.pending.value)

        val replacement = request(manager)
        val replacementId = requireNotNull(manager.pending.value).id
        assertTrue(manager.resolve(replacementId, ApprovalResolution.ALLOW_SESSION))
        assertEquals(ApprovalDecision.Approved, replacement.await())
        assertTrue(hasGrant(manager))
    }

    @Test fun queuedRequestsStaySerialAndOldIdsCannotAnswerTheNextPrompt() = runBlocking {
        val manager = ApprovalManager()
        val first = request(manager)
        val firstPending = requireNotNull(manager.pending.value)
        val second = request(manager, "http://localhost:3001/second")
        val third = request(manager, "http://localhost:3002/third")
        assertEquals(firstPending, manager.pending.value)

        assertTrue(manager.resolve(firstPending.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(ApprovalDecision.Approved, first.await())
        val secondPending = requireNotNull(manager.pending.value)
        assertEquals("http://localhost:3001/second", secondPending.resource)
        assertFalse(manager.resolve(firstPending.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(secondPending, manager.pending.value)
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)

        assertTrue(manager.resolve(secondPending.id, ApprovalResolution.DENY))
        assertEquals(ApprovalDecision.Denied, second.await())
        val thirdPending = requireNotNull(manager.pending.value)
        assertEquals("http://localhost:3002/third", thirdPending.resource)
        assertFalse(manager.resolve(secondPending.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(thirdPending, manager.pending.value)
        assertTrue(manager.resolve(thirdPending.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(ApprovalDecision.Approved, third.await())
        assertNull(manager.pending.value)
        assertFalse(hasGrant(manager))
        assertFalse(hasGrant(manager, secondPending.resource))
        assertFalse(hasGrant(manager, thirdPending.resource))
    }

    @Test fun cancellingAQueuedRequestDoesNotDismissTheActivePrompt() = runBlocking {
        val manager = ApprovalManager()
        val first = request(manager)
        val pending = requireNotNull(manager.pending.value)
        val queued = request(manager, "http://localhost:3001/queued")
        queued.cancelAndJoin()

        assertEquals(pending, manager.pending.value)
        assertFalse(first.isCompleted)
        assertTrue(manager.resolve(pending.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(ApprovalDecision.Approved, first.await())
        assertNull(manager.pending.value)
        assertFalse(hasGrant(manager, "http://localhost:3001/queued"))
    }

    @Test fun invalidSessionChoiceDoesNotSilentlyApproveOrConsumeThePrompt() = runBlocking {
        val manager = ApprovalManager()
        val request = request(manager, "http://user@localhost:3000/private")
        val pending = requireNotNull(manager.pending.value)
        assertFalse(pending.canAllowForSession)
        assertFalse(manager.resolve(pending.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(pending, manager.pending.value)
        assertFalse(request.isCompleted)
        assertFalse(hasGrant(manager))

        assertTrue(manager.resolve(pending.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(ApprovalDecision.Approved, request.await())
        assertFalse(hasGrant(manager))
    }

    @Test fun matchingSessionChoiceKeepsExistingSessionScopeAndOriginReuse() = runBlocking {
        val manager = ApprovalManager()
        val first = request(manager)
        val pending = requireNotNull(manager.pending.value)
        assertTrue(manager.resolve(pending.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(ApprovalDecision.Approved, first.await())
        assertTrue(hasGrant(manager, "http://localhost:3000/another"))
        assertFalse(hasGrant(manager, "http://localhost:3001/another"))
        assertFalse(manager.hasSessionApproval("session-b", "build", "browser_local", pending.resource))
        assertFalse(manager.hasSessionApproval("session-a", "reviewer", "browser_local", pending.resource))
        assertFalse(manager.resolve(pending.id, ApprovalResolution.DENY))

        val reused = request(manager, "http://localhost:3000/another")
        assertTrue(reused.isCompleted)
        assertNull(manager.pending.value)
        assertEquals(ApprovalDecision.Approved, reused.await())
    }

    @Test fun legacyResolutionOverloadsRemainCompatible() = runBlocking {
        val manager = ApprovalManager()
        val denied = request(manager)
        manager.resolve(false)
        assertEquals(ApprovalDecision.Denied, denied.await())
        val approved = request(manager)
        manager.resolve(true)
        assertEquals(ApprovalDecision.Approved, approved.await())
        assertFalse(hasGrant(manager))

        val unavailableSession = request(manager, "http://user@localhost:3000/private")
        assertFalse(requireNotNull(manager.pending.value).canAllowForSession)
        manager.resolve(ApprovalResolution.ALLOW_SESSION)
        assertEquals(ApprovalDecision.Approved, unavailableSession.await())
        assertFalse(hasGrant(manager))
    }

    @Test fun browserPresentationContextDoesNotChangePermissionAuthority() = runBlocking {
        val manager = ApprovalManager()
        val context = BrowserApprovalContext(
            operation = "click",
            url = "http://localhost:4000/presentation-only",
            target = "#fixture",
            reason = "Read the fixture",
        )
        val request = request(manager, browserContext = context)
        val pending = requireNotNull(manager.pending.value)
        assertEquals(context, pending.browserContext)
        assertEquals("http://localhost:3000", pending.sessionGrantResource)
        assertTrue(manager.resolve(pending.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(ApprovalDecision.Approved, request.await())
        assertTrue(hasGrant(manager))
        assertFalse(hasGrant(manager, context.url))
    }
}

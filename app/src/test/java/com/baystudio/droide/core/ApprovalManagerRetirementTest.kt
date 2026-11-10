package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Manager-level family controls only; no Agent, ACP transport, browser, process or provider. */
class ApprovalManagerRetirementTest {
    private suspend fun ask(manager: ApprovalManager, resource: String = "inert") =
        manager.requestApproval("inert", "inert", "No executable payload", action = "read", resource = resource,
            permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE, approvalSessionId = "r38-session")

    @Test(timeout = 8_000) fun ownerlessPendingQueuedAndLateAsksAreDeniedWithoutInlineDelivery() = runBlocking {
        withTimeout(5_000) {
            val manager = ApprovalManager()
            var resumed = false
            val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                ask(manager).also { resumed = true }
            }
            val id = checkNotNull(manager.pending.value).id
            val queued = async(start = CoroutineStart.UNDISPATCHED) { ask(manager, "queued") }
            try {
                val deliver = manager.retire()
                assertNull(manager.pending.value)
                assertFalse("Retirement must only reserve denial, not execute the caller", resumed)
                assertFalse(first.isCompleted)
                assertFalse(queued.isCompleted)
                assertFalse(manager.resolve(id, ApprovalResolution.ALLOW_SESSION))
                manager.retire().invoke() // Idempotence must not lose the first reserved token.
                assertFalse(resumed)
                deliver()
                assertSame(ApprovalDecision.Denied, first.await())
                assertSame(ApprovalDecision.Denied, queued.await())
                assertSame(ApprovalDecision.Denied, ask(manager, "late"))
                assertNull(manager.pending.value)
            } finally { first.cancelAndJoin(); queued.cancelAndJoin() }
        }
    }

    @Test(timeout = 8_000) fun stillCurrentChildOwnerCannotReuseGrantAfterItsRuntimeManagerRetires() = runBlocking {
        withTimeout(5_000) {
            val manager = ApprovalManager()
            val samples = AtomicInteger()
            val owner = ApprovalOwnerAdmission(Any()) { samples.incrementAndGet(); true }
            val first = async(start = CoroutineStart.UNDISPATCHED) { manager.withApprovalOwner(owner) { ask(manager) } }
            try {
                assertTrue(manager.resolve(checkNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_SESSION))
                assertSame(ApprovalDecision.Approved, first.await())
                assertTrue(manager.hasSessionApproval("r38-session", EXTERNAL_ACP_PERMISSION_SCOPE, "read", "inert"))
                val before = samples.get()
                manager.retire().invoke()
                assertEquals("Retirement does not call a native/child owner predicate", before, samples.get())
                assertFalse(manager.hasSessionApproval("r38-session", EXTERNAL_ACP_PERMISSION_SCOPE, "read", "inert"))
                assertSame(ApprovalDecision.Denied, manager.withApprovalOwner(owner) { ask(manager) })
                assertEquals("Late retirement check precedes owner admission", before, samples.get())
            } finally { first.cancelAndJoin() }
        }
    }

    @Test(timeout = 8_000) fun retirementDuringSuspendedBrowserAdmissionCannotPublishOrUseCachedGrant() = runBlocking {
        withTimeout(5_000) {
            val manager = ApprovalManager()
            val first = async(start = CoroutineStart.UNDISPATCHED) { ask(manager) }
            assertTrue(manager.resolve(checkNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_SESSION))
            assertSame(ApprovalDecision.Approved, first.await())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed = async(start = CoroutineStart.UNDISPATCHED) {
                manager.withBrowserValidity(check = { true }, admission = { entered.complete(Unit); release.await(); true }) { ask(manager) }
            }
            try {
                entered.await()
                manager.retire().invoke()
                release.complete(Unit)
                assertSame(ApprovalDecision.Denied, delayed.await())
                assertNull(manager.pending.value)
                assertFalse(manager.hasSessionApproval("r38-session", EXTERNAL_ACP_PERMISSION_SCOPE, "read", "inert"))
            } finally { release.complete(Unit); delayed.cancelAndJoin(); first.cancelAndJoin() }
        }
    }

    @Test(timeout = 8_000) fun retirementWinsOverResolveWhoseValidityWasAlreadySampled() = runBlocking {
        withTimeout(5_000) {
            val manager = ApprovalManager()
            val resolving = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val checks = AtomicInteger()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                manager.requestApproval("inert", "inert", "No executable payload", action = "read", resource = "inert",
                    permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE, approvalSessionId = "r38-session", isStillCurrent = {
                        if (checks.incrementAndGet() == 2) {
                            resolving.complete(Unit)
                            check(release.await(2, TimeUnit.SECONDS)) { "Fixture gate timeout is not ownership evidence" }
                        }
                        true
                    })
            }
            val id = checkNotNull(manager.pending.value).id
            val resolver = async(Dispatchers.Default) { manager.resolve(id, ApprovalResolution.ALLOW_SESSION) }
            try {
                resolving.await()
                val deliver = manager.retire()
                release.countDown()
                deliver()
                assertFalse(resolver.await())
                assertSame(ApprovalDecision.Denied, first.await())
                assertFalse(manager.hasSessionApproval("r38-session", EXTERNAL_ACP_PERMISSION_SCOPE, "read", "inert"))
            } finally { release.countDown(); first.cancelAndJoin(); resolver.cancelAndJoin() }
        }
    }

    @Test(timeout = 8_000) fun retirementDoesNotTouchNewManagerWithTheSameRequestAndSessionIds() = runBlocking {
        withTimeout(5_000) {
            val a = ApprovalManager(); val b = ApprovalManager()
            val old = async(start = CoroutineStart.UNDISPATCHED) { ask(a) }
            val replacement = async(start = CoroutineStart.UNDISPATCHED) { ask(b) }
            try {
                val oldId = checkNotNull(a.pending.value).id
                val current = checkNotNull(b.pending.value)
                assertEquals(oldId, current.id)
                a.retire().invoke()
                assertSame(ApprovalDecision.Denied, old.await())
                assertFalse(a.resolve(oldId, ApprovalResolution.ALLOW_SESSION))
                assertSame(current, b.pending.value)
                assertTrue(b.resolve(current.id, ApprovalResolution.ALLOW_SESSION))
                assertSame(ApprovalDecision.Approved, replacement.await())
                a.retire().invoke()
                assertTrue(b.hasSessionApproval("r38-session", EXTERNAL_ACP_PERMISSION_SCOPE, "read", "inert"))
                assertSame(ApprovalDecision.Approved, ask(b))
            } finally { old.cancelAndJoin(); replacement.cancelAndJoin() }
        }
    }
}

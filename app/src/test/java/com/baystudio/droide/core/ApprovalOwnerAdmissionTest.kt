package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalOwnerAdmissionTest {
    @Test(timeout = 10_000)
    fun aStaleOwnerCannotPublishPendingAfterTheLegacyCheck() = bounded {
        val manager = ApprovalManager()
        var current = true
        val owner = ApprovalOwnerAdmission(Any()) { current }
        val result = manager.requestApproval("hook", "fixture", "", action = "hook", resource = "fixture",
            permissionScope = "build", provenance = AgentRequestProvenance.primary("session"),
            isStillCurrent = { val sampled = current; current = false; sampled }, ownerAdmission = owner)
        assertSame(ApprovalDecision.Denied, result)
        assertNull(manager.pending.value)
        assertFalse(granted(manager))
        assertNoPendingBindings(manager)
    }

    @Test(timeout = 10_000)
    fun pendingRegistrationRechecksAfterCacheLookup() = bounded {
        val manager = ApprovalManager()
        var checks = 0
        val owner = ApprovalOwnerAdmission(Any()) { ++checks == 1 }
        val result = request(manager, owner).await()
        assertEquals(2, checks)
        assertSame(ApprovalDecision.Denied, result)
        assertNull(manager.pending.value)
        assertFalse(granted(manager))
        assertNoPendingBindings(manager)
    }

    @Test(timeout = 10_000)
    fun staleOwnerCannotBorrowButDoesNotEraseAPreviouslyApprovedSessionGrant() = bounded {
        val manager = ApprovalManager()
        var current = true
        val owner = ApprovalOwnerAdmission(Any()) { current }
        val first = request(manager, owner)
        try {
            assertTrue(manager.resolve(requireNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_SESSION))
            assertSame(ApprovalDecision.Approved, first.await())
            assertTrue(granted(manager))
            current = false
            assertSame(ApprovalDecision.Denied, request(manager, owner).await())
            assertNull(manager.pending.value)
            assertTrue("Earlier legitimate session authority remains valid", granted(manager))
            current = true
            assertSame(ApprovalDecision.Approved, request(manager, owner).await())
            assertNoPendingBindings(manager)
        } finally { first.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun currentResolutionChecksBeforeStateLockAndCompletesOutsideBothMonitors() = bounded {
        val manager = ApprovalManager()
        val stateLock = field(manager, "stateLock")
        val ownerLock = Any()
        var checks = 0
        val owner = ApprovalOwnerAdmission(ownerLock) {
            assertTrue(Thread.holdsLock(ownerLock))
            assertFalse(Thread.holdsLock(stateLock))
            checks++
            true
        }
        var completionLocks: Pair<Boolean, Boolean>? = null
        val work = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            val result = manager.requestApproval("hook", "fixture", "", action = "hook", resource = "fixture",
                permissionScope = "build", provenance = AgentRequestProvenance.primary("session"),
                isStillCurrent = {
                    assertFalse(Thread.holdsLock(ownerLock)); assertFalse(Thread.holdsLock(stateLock)); true
                }, ownerAdmission = owner)
            completionLocks = Thread.holdsLock(ownerLock) to Thread.holdsLock(stateLock)
            result
        }
        try {
            val id = requireNotNull(manager.pending.value).id
            assertTrue(manager.resolve(id, ApprovalResolution.ALLOW_SESSION))
            assertSame(ApprovalDecision.Approved, work.await())
            assertEquals(false to false, completionLocks)
            assertEquals(3, checks)
            assertTrue(granted(manager))
            assertFalse(manager.resolve(id, ApprovalResolution.ALLOW_SESSION))
            assertNoPendingBindings(manager)
        } finally { work.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun staleOwnerCannotReceiveAnAllowOnceDecision() = bounded {
        val manager = ApprovalManager()
        var current = true
        val owner = ApprovalOwnerAdmission(Any()) { current }
        val work = request(manager, owner)
        try {
            val id = requireNotNull(manager.pending.value).id
            current = false
            assertTrue(manager.resolve(id, ApprovalResolution.ALLOW_ONCE))
            assertSame(ApprovalDecision.Denied, work.await())
            assertFalse(granted(manager))
            assertNoPendingBindings(manager)
        } finally { work.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun cancellationAfterReservationRemovesOnlyItsNewGrant() = bounded {
        val manager = ApprovalManager()
        val owner = ApprovalOwnerAdmission(Any()) { true }
        val established = request(manager, owner, "established")
        assertTrue(manager.resolve(requireNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_SESSION))
        assertSame(ApprovalDecision.Approved, established.await())
        val work = request(manager, owner)
        try {
            assertTrue(manager.resolve(requireNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_SESSION))
            assertTrue(granted(manager))
            // The runBlocking event loop cannot deliver this resumed result until we yield.
            work.cancelAndJoin()
            assertFalse(granted(manager))
            assertTrue(granted(manager, "established"))
            assertNoPendingBindings(manager)
        } finally { work.cancelAndJoin(); established.cancelAndJoin() }
    }

    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000) { block() } }
    private fun CoroutineScope.request(manager: ApprovalManager, owner: ApprovalOwnerAdmission,
        resource: String = "fixture") = async(start = CoroutineStart.UNDISPATCHED) {
        manager.requestApproval("hook", "fixture", "", action = "hook", resource = resource,
            permissionScope = "build", provenance = AgentRequestProvenance.primary("session"),
            ownerAdmission = owner)
    }
    private fun granted(manager: ApprovalManager, resource: String = "fixture") =
        manager.hasSessionApproval("session", "build", "hook", resource)
    private fun field(manager: ApprovalManager, name: String): Any = ApprovalManager::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(manager)
    private fun assertNoPendingBindings(manager: ApprovalManager) {
        assertNull(manager.pending.value)
        for (name in listOf("conts", "validityChecks", "ownerAdmissions"))
            assertTrue("Leaked $name binding", (field(manager, name) as Map<*, *>).isEmpty())
    }
}

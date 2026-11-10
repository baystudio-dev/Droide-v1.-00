package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Pure bounded coroutine-scope controls; no files, Agent, terminal or network. */
class ApprovalOwnerContextTest {
    @Test(timeout = 10_000)
    fun inheritedOwnerRejectsAStalePendingRequest() = bounded {
        val manager = ApprovalManager()
        val result = manager.withApprovalOwner(ApprovalOwnerAdmission(Any()) { false }) { ask(manager) }
        assertSame(ApprovalDecision.Denied, result)
        assertNull(manager.pending.value)
        assertFalse(granted(manager))
    }

    @Test(timeout = 10_000)
    fun inheritedOwnerRejectsResolutionAfterRevocation() = bounded {
        val manager = ApprovalManager()
        var current = true
        val owner = ApprovalOwnerAdmission(Any()) { current }
        val work = async(start = CoroutineStart.UNDISPATCHED) { manager.withApprovalOwner(owner) { ask(manager) } }
        try {
            val request = requireNotNull(manager.pending.value)
            current = false
            assertTrue(manager.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
            assertSame(ApprovalDecision.Denied, work.await())
            assertNull(manager.pending.value)
            assertFalse(granted(manager))
        } finally { work.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun doomLoopApprovalUsesTheInheritedOwner() = bounded {
        val manager = ApprovalManager()
        var current = true
        val owner = ApprovalOwnerAdmission(Any()) { current }
        val permissions = PermissionEngine(listOf(PermRule("doom_loop", "write_file", PermEffect.ASK)))
        val work = async(start = CoroutineStart.UNDISPATCHED) { manager.withApprovalOwner(owner) {
            AgentApprovalSupport.doomLoopDenied(manager, permissions, "build", "write_file", "{}",
                AgentRequestProvenance.primary("session"))
        } }
        try {
            val request = requireNotNull(manager.pending.value)
            assertEquals("doom_loop", request.action)
            current = false
            assertTrue(manager.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
            assertTrue(work.await())
            assertFalse(manager.hasSessionApproval("session", "build", "doom_loop", "write_file"))
            assertNull(manager.pending.value)
        } finally { work.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun explicitOwnerTakesPrecedenceOverTheInheritedOwner() = bounded {
        val manager = ApprovalManager()
        val inherited = ApprovalOwnerAdmission(Any()) { false }
        val explicit = ApprovalOwnerAdmission(Any()) { true }
        val work = async(start = CoroutineStart.UNDISPATCHED) { manager.withApprovalOwner(inherited) { ask(manager, explicit) } }
        try {
            assertTrue(manager.resolve(requireNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_ONCE))
            assertSame(ApprovalDecision.Approved, work.await())
            assertNull(manager.pending.value)
            assertFalse(granted(manager))
        } finally { work.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun scopeDoesNotBindAnotherManagerOrEscapeItsBlock() = bounded {
        val manager = ApprovalManager()
        val other = ApprovalManager()
        manager.withApprovalOwner(ApprovalOwnerAdmission(Any()) { false }) {
            coroutineScope {
                val otherWork = async(start = CoroutineStart.UNDISPATCHED) { ask(other) }
                try {
                    assertTrue(other.resolve(requireNotNull(other.pending.value).id, ApprovalResolution.ALLOW_ONCE))
                    assertSame(ApprovalDecision.Approved, otherWork.await())
                } finally { otherWork.cancelAndJoin() }
            }
            assertSame(ApprovalDecision.Denied, ask(manager))
        }
        val outside = async(start = CoroutineStart.UNDISPATCHED) { ask(manager) }
        try {
            assertTrue(manager.resolve(requireNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_ONCE))
            assertSame(ApprovalDecision.Approved, outside.await())
            assertNull(manager.pending.value)
            assertFalse(granted(manager))
        } finally { outside.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun browserValidityAndInheritedOwnerBothMustPermitAdmission() = bounded {
        val manager = ApprovalManager()
        val browserDenied = manager.withApprovalOwner(ApprovalOwnerAdmission(Any()) { true }) {
            manager.withBrowserValidity({ false }) { ask(manager) }
        }
        assertSame(ApprovalDecision.Denied, browserDenied)
        val ownerDenied = manager.withApprovalOwner(ApprovalOwnerAdmission(Any()) { false }) {
            manager.withBrowserValidity({ true }) { ask(manager) }
        }
        assertSame(ApprovalDecision.Denied, ownerDenied)
        assertNull(manager.pending.value)
        assertFalse(granted(manager))
    }

    @Test(timeout = 10_000)
    fun queuedRequestKeepsItsOwnerWhileWaitingForTheExistingPrompt() = bounded {
        val manager = ApprovalManager()
        var current = true
        val first = async(start = CoroutineStart.UNDISPATCHED) { ask(manager) }
        val firstRequest = requireNotNull(manager.pending.value)
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            manager.withApprovalOwner(ApprovalOwnerAdmission(Any()) { current }) { ask(manager) }
        }
        try {
            assertEquals(firstRequest, manager.pending.value)
            assertFalse(queued.isCompleted)
            current = false
            assertTrue(manager.resolve(firstRequest.id, ApprovalResolution.ALLOW_ONCE))
            assertSame(ApprovalDecision.Approved, first.await())
            assertSame(ApprovalDecision.Denied, queued.await())
            assertNull(manager.pending.value)
            assertFalse(granted(manager))
        } finally { queued.cancelAndJoin(); first.cancelAndJoin() }
    }

    @Test(timeout = 10_000)
    fun cancellingTheScopedWaiterRemovesItsPendingOwnerBinding() = bounded {
        val manager = ApprovalManager()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            manager.withApprovalOwner(ApprovalOwnerAdmission(Any()) { true }) { ask(manager) }
        }
        try {
            val id = requireNotNull(manager.pending.value).id
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertNull(manager.pending.value)
            assertFalse(manager.resolve(id, ApprovalResolution.ALLOW_SESSION))
            assertFalse(granted(manager))
            val bindings = ApprovalManager::class.java.getDeclaredField("ownerAdmissions")
                .apply { isAccessible = true }.get(manager) as Map<*, *>
            assertTrue(bindings.isEmpty())
        } finally { work.cancelAndJoin() }
    }

    private suspend fun ask(manager: ApprovalManager, owner: ApprovalOwnerAdmission? = null) = manager.requestApproval(
        "edit", "fixture", "fixture", action = "edit", resource = "reviewed.txt", permissionScope = "build",
        provenance = AgentRequestProvenance.primary("session"), ownerAdmission = owner)
    private fun granted(manager: ApprovalManager) = manager.hasSessionApproval("session", "build", "edit", "reviewed.txt")
    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000) { block() } }
}

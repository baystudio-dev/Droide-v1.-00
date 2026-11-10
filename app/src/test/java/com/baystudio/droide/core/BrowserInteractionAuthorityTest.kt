package com.baystudio.droide.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Authored by static review only. These regressions have not been compiled or executed. */
class BrowserInteractionAuthorityTest {
    private fun key(url: String = "https://example.test/page", target: String = "#submit",
        document: String = "renderer-a:1", operation: String = "click", text: String? = null,
        submit: Boolean = false, dialogText: String? = null) =
        BrowserInteractionAuthority.sessionResource(document, url, operation, target, text, submit, dialogText)

    @Test fun fullUrlAndTargetReachPolicyDespiteIdenticalOldPrefixes() {
        val prefix = "https://example.test/" + "a".repeat(600)
        val selector = "#" + "b".repeat(250)
        val first = BrowserInteractionAuthority.policyResource(prefix + "first", "click", selector + "first")
        val second = BrowserInteractionAuthority.policyResource(prefix + "second", "click", selector + "second")
        assertNotEquals(first, second)
        assertEquals(prefix + "first#click:" + selector + "first", first)
        val policy = PermissionEngine(base = listOf(PermRule("browser_interact", "*", PermEffect.ALLOW)),
            hardRules = listOf(PermRule("browser_interact", second, PermEffect.DENY)))
        assertEquals(PermEffect.ALLOW, policy.decide("browser_interact", first))
        assertEquals(PermEffect.DENY, policy.decide("browser_interact", second))
        assertNotEquals(key(prefix + "first", selector), key(prefix + "second", selector))
        assertNotEquals(key(prefix, selector + "first"), key(prefix, selector + "second"))
    }

    @Test fun structuredSessionKeysDoNotCollideOnRawPolicySeparators() {
        val url = "https://example.test/page"
        assertNotEquals(key(url = "$url#click:x", target = "y"), key(url = url, target = "x#click:y"))
        for (operation in listOf("click", "type", "dialog_respond")) {
            assertTrue(runCatching { BrowserInteractionAuthority.policyResource("$url#$operation:x", "click", "y") }.isFailure)
        }
        assertEquals("$url#click:x#click:y", BrowserInteractionAuthority.policyResource(url, "click", "x#click:y"))
    }

    @Test fun documentAndEveryEffectChangingArgumentRequireTheirOwnGrant() {
        assertNotEquals(key(), key(document = "renderer-a:2"))
        assertNotEquals(key(), key(document = "renderer-b:1"))
        assertNotEquals(key(operation = "type", text = "first"), key(operation = "type", text = "second"))
        assertNotEquals(key(operation = "type", text = "first"), key(operation = "type", text = "first", submit = true))
        assertNotEquals(key(operation = "dialog_respond", text = "accept"), key(operation = "dialog_respond", text = "dismiss"))
        assertNotEquals(key(operation = "dialog_respond", text = "accept", dialogText = "first"),
            key(operation = "dialog_respond", text = "accept", dialogText = "second"))
        assertFalse(key(operation = "type", text = "private-input-fixture").contains("private-input-fixture"))
    }

    @Test fun longCompleteKeysRemainEligibleWithoutReusingAnotherTarget() = runBlocking {
        val manager = ApprovalManager()
        val url = "https://example.test/" + "a".repeat(7_000)
        val target = "#" + "b".repeat(10_000)
        val firstKey = key(url, target)
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("browser_interact", "Click", "", "browser_interact",
                BrowserInteractionAuthority.policyResource(url, "click", target), "build",
                AgentRequestProvenance.primary("session-a"), sessionGrantResource = firstKey)
        }
        val request = requireNotNull(manager.pending.value)
        assertTrue(request.canAllowForSession)
        assertTrue(manager.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(ApprovalDecision.Approved, approval.await())
        assertTrue(manager.hasSessionApproval("session-a", "build", "browser_interact", firstKey))
        assertFalse(manager.hasSessionApproval("session-a", "build", "browser_interact", key(url, target + "x")))
    }

    @Test fun documentChangeDuringPendingApprovalConsumesRequestWithoutInstallingGrant() = runBlocking {
        val manager = ApprovalManager()
        var current = true
        val grantKey = key()
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("browser_interact", "Click", "", "browser_interact", "page#click:target", "build",
                AgentRequestProvenance.primary("session-a"), sessionGrantResource = grantKey, isStillCurrent = { current })
        }
        val request = requireNotNull(manager.pending.value)
        current = false
        assertTrue(manager.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        assertEquals(ApprovalDecision.Denied, approval.await())
        assertNull(manager.pending.value)
        assertFalse(manager.hasSessionApproval("session-a", "build", "browser_interact", grantKey))
    }

    @Test fun displayTargetTruncationIsExplicitAndNeverChangesStoredTarget() {
        val target = "#" + "a".repeat(500)
        val context = BrowserApprovalContext("click", "https://example.test/", target, "Click this target.")
        assertEquals(target, context.target)
        assertEquals(target.take(200) + "… (target truncated)", context.targetPreview)
        assertEquals("#ok", context.copy(target = "#ok").targetPreview)
    }

    @Test fun approvalFreshnessCallbackNeverRunsWhileTheApprovalStateLockIsHeld() = runBlocking {
        val manager = ApprovalManager()
        val lock = ApprovalManager::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(manager)
        var checks = 0
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("browser_interact", "Click", "", "browser_interact", "target", "build",
                AgentRequestProvenance.primary("session-a"), isStillCurrent = {
                    assertFalse("Avoid lifecycle-lock/cancellation-lock inversion", Thread.holdsLock(lock))
                    checks++
                    true
                })
        }
        manager.resolve(requireNotNull(manager.pending.value).id, ApprovalResolution.ALLOW_ONCE)
        assertEquals(ApprovalDecision.Approved, approval.await())
        assertEquals(2, checks)
    }

    @Test fun unsupportedLengthsAreRejectedInsteadOfTruncated() {
        assertTrue(runCatching { BrowserInteractionAuthority.policyResource("x".repeat(8_193), "click", "#ok") }.isFailure)
        assertTrue(runCatching { BrowserInteractionAuthority.policyResource("https://example.test/", "click", "x".repeat(20_001)) }.isFailure)
        assertTrue(runCatching { BrowserInteractionAuthority.policyResource("https://example.test/", "click", "body\nbutton") }.isFailure)
    }
}

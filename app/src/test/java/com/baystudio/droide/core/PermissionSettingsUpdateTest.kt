package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class PermissionSettingsUpdateTest {
    private fun rule(e: PermEffect, resource: String = "private/*", action: String = "read") = PermRule(action, resource, e)
    private fun policy(vararg r: PermRule) = PermissionPolicyDocument(rules = r.toList())
    private fun effect(p: PermissionPolicyDocument, path: String = "private/a", agent: String = "build") = PermissionEngine(policy = p).decide("read", path, agent)
    private inline fun rejected(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} }

    @Test fun broadAllowPreservesNarrowDeny() { assertEquals(PermEffect.DENY, effect(policy(rule(PermEffect.DENY)).withSafeBroadSetting("read", PermEffect.ALLOW))) }
    @Test fun broadAskRestrictsNarrowAllow() { assertEquals(PermEffect.ASK, effect(policy(rule(PermEffect.ALLOW)).withSafeBroadSetting("read", PermEffect.ASK))) }
    @Test fun broadDenyRestrictsNarrowAllow() { assertEquals(PermEffect.DENY, effect(policy(rule(PermEffect.ALLOW)).withSafeBroadSetting("read", PermEffect.DENY))) }
    @Test fun contradictoryDenyThenAllowRejectsAskWithoutMutation() {
        val before = policy(rule(PermEffect.DENY), rule(PermEffect.ALLOW)); val original = before.rules.toList()
        rejected { before.withSafeBroadSetting("read", PermEffect.ASK) }; assertEquals(original, before.rules)
        assertEquals(PermEffect.ALLOW, effect(before))
    }
    @Test fun allowThenDenyAdmitsAskInMiddle() {
        val before = policy(rule(PermEffect.ALLOW), rule(PermEffect.DENY))
        val after = before.withSafeBroadSetting("read", PermEffect.ASK)
        assertEquals(listOf(before.rules[0], rule(PermEffect.ASK, "*"), before.rules[1]), after.rules)
        assertEquals(PermEffect.DENY, effect(after)); assertEquals(PermEffect.ASK, effect(after, "other"))
    }
    @Test fun wildcardActionsParticipateInBounds() {
        val before = policy(rule(PermEffect.ALLOW, action = "r?ad"), rule(PermEffect.DENY, action = "*"))
        val after = before.withSafeBroadSetting("read", PermEffect.ASK)
        assertEquals(before.rules[0], after.rules[0]); assertEquals(before.rules[1], after.rules[2])
        assertEquals(PermEffect.DENY, effect(after))
    }
    @Test fun sameEffectAndUnrelatedRulesKeepOrder() {
        val before = policy(rule(PermEffect.ASK), rule(PermEffect.DENY, action = "shell"), rule(PermEffect.ASK, "else/*"))
        assertEquals(before.rules, before.withSafeBroadSetting("read", PermEffect.ASK).rules.drop(1))
    }
    @Test fun emptyAndRepeatedEditsDoNotAccumulateRules() {
        var p = PermissionPolicyDocument.EMPTY
        repeat(15) { p = p.withSafeBroadSetting("read", PermEffect.entries[it % 3]) }
        assertEquals(1, p.rules.size); assertEquals(PermEffect.DENY, effect(p))
    }
    @Test fun replacesOnlyLiteralCategoryBroadRule() {
        val unrelated = rule(PermEffect.DENY, "*", "shell"); val fine = rule(PermEffect.DENY)
        val after = policy(rule(PermEffect.DENY, "*"), fine, unrelated).withSafeBroadSetting("read", PermEffect.ALLOW)
        assertEquals(listOf(rule(PermEffect.ALLOW, "*"), fine, unrelated), after.rules)
    }
    @Test fun ruleLimitRejectsNewButAllowsReplacement() {
        val full = PermissionPolicyDocument(rules = List(512) { rule(PermEffect.ASK, "file$it", "shell") })
        rejected { full.withSafeBroadSetting("read", PermEffect.ASK) }
        val replace = full.copy(rules = full.rules.dropLast(1) + rule(PermEffect.ASK, "*"))
        assertEquals(512, replace.withSafeBroadSetting("read", PermEffect.DENY).rules.size)
    }
    @Test fun agentScopesAndTheirExistingPrecedenceArePreserved() {
        val agents = mapOf("build" to listOf(rule(PermEffect.ALLOW, "*")), "reviewer" to listOf(rule(PermEffect.DENY, "*")))
        val ask = PermissionPolicyDocument(agents = agents).withSafeBroadSetting("read", PermEffect.ASK)
        assertEquals(agents, ask.agents); assertEquals(PermEffect.ALLOW, effect(ask)); assertEquals(PermEffect.DENY, effect(ask, agent = "reviewer"))
        assertEquals(PermEffect.DENY, effect(ask.withSafeBroadSetting("read", PermEffect.DENY)))
    }
    @Test fun rejectsWildcardOrBlankSettingActions() { listOf("", "*", "r?ad", "read ").forEach { rejected { PermissionPolicyDocument.EMPTY.withSafeBroadSetting(it, PermEffect.ALLOW) } } }
    @Test fun concurrentIndependentEditsReadLatestInsideGate() = runBlocking {
        val gate = Mutex(locked = true); val engine = PermissionEngine(); val saves = mutableListOf<PermissionPolicyDocument>()
        suspend fun update(action: String) = updatePermissionSetting(gate, "p", action, PermEffect.DENY, { "p" to engine }, { _, p -> saves += p })
        val a = async(start = CoroutineStart.UNDISPATCHED) { update("read") }; val b = async(start = CoroutineStart.UNDISPATCHED) { update("shell") }
        assertTrue(saves.isEmpty()); gate.unlock(); a.await(); b.await()
        assertEquals(2, saves.size); assertEquals(setOf("read", "shell"), saves.last().rules.map { it.action }.toSet())
        assertEquals(saves.last(), engine.policySnapshot())
    }
    @Test fun sameFieldQueuedOrderIsLastSuccessfulWrite() = runBlocking {
        val gate = Mutex(locked = true); val engine = PermissionEngine(); val saves = mutableListOf<PermEffect>()
        fun enqueue(effect: PermEffect) = async(start = CoroutineStart.UNDISPATCHED) { updatePermissionSetting(gate, "p", "read", effect, { "p" to engine }, { _, p -> saves += p.rules.single().effect }) }
        val a = enqueue(PermEffect.ASK); val b = enqueue(PermEffect.DENY); gate.unlock(); a.await(); b.await()
        assertEquals(listOf(PermEffect.ASK, PermEffect.DENY), saves); assertEquals(PermEffect.DENY, engine.decide("read", "a"))
    }
    @Test fun failedPersistenceDoesNotPublishAndNextEditStillWorks() = runBlocking {
        val gate = Mutex(); val engine = PermissionEngine(); val original = engine.policySnapshot()
        try { updatePermissionSetting(gate, "p", "read", PermEffect.DENY, { "p" to engine }, { _, _ -> error("disk") }); fail() } catch (e: IllegalStateException) { assertEquals("disk", e.message) }
        assertEquals(original, engine.policySnapshot())
        updatePermissionSetting(gate, "p", "read", PermEffect.ASK, { "p" to engine }, { _, _ -> })
        assertEquals(PermEffect.ASK, engine.decide("read", "a"))
    }
    @Test fun queuedEditCannotMoveToDifferentWorkspace() = runBlocking {
        val gate = Mutex(locked = true); val engine = PermissionEngine(); var active = "old"; var wrote = false
        val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { updatePermissionSetting(gate, "old", "read", PermEffect.DENY, { active to engine }, { _, _ -> wrote = true }) } }
        active = "new"; gate.unlock(); assertTrue(task.await().isFailure); assertFalse(wrote); assertEquals(PermissionPolicyDocument.EMPTY, engine.policySnapshot())
    }
    @Test fun cancellationWhileQueuedDoesNotSave() = runBlocking {
        val gate = Mutex(locked = true); val engine = PermissionEngine(); var wrote = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) { updatePermissionSetting(gate, "p", "read", PermEffect.DENY, { "p" to engine }, { _, _ -> wrote = true }) }
        task.cancelAndJoin(); gate.unlock(); assertFalse(wrote); assertTrue(task.isCancelled)
    }
    @Test fun presetsAndAllEffectPairsPreserveExpectedFloor() {
        for (old in PermEffect.entries) for (chosen in PermEffect.entries) {
            val after = policy(rule(old)).withSafeBroadSetting("read", chosen)
            assertEquals(PermEffect.entries[maxOf(old.ordinal, chosen.ordinal)], effect(after))
            assertEquals(chosen, effect(after, "other"))
        }
    }
}

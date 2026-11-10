package com.baystudio.droide.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Edit only a category-wide rule. Never reorder retained user rules or change evaluation. */
internal fun PermissionPolicyDocument.withSafeBroadSetting(action: String, effect: PermEffect): PermissionPolicyDocument {
    require(action.matches(Regex("[A-Za-z0-9_]+"))) { "Settings require one literal permission action" }
    val kept = rules.filterNot { it.action == action && it.resource == "*" }
    var afterLessRestrictive = 0
    var beforeMoreRestrictive = kept.size
    kept.forEachIndexed { index, rule ->
        if (PermissionEngine.wild(rule.action, action)) {
            if (rule.effect.ordinal < effect.ordinal) afterLessRestrictive = index + 1
            if (rule.effect.ordinal > effect.ordinal) beforeMoreRestrictive = minOf(beforeMoreRestrictive, index)
        }
    }
    require(afterLessRestrictive <= beforeMoreRestrictive) {
        "This change conflicts with the order of existing fine-grained rules. Review those rules first; no permissions were changed."
    }
    val next = kept.toMutableList().apply { add(afterLessRestrictive, PermRule(action, "*", effect)) }
    return copy(rules = next).validated()
}

/** Called with the coordinator's existing transition mutex, not a second policy cache. */
internal suspend fun updatePermissionSetting(
    transitionMutex: Mutex,
    expectedProjectId: String,
    action: String,
    effect: PermEffect,
    active: () -> Pair<String, PermissionEngine>?,
    persist: (String, PermissionPolicyDocument) -> Unit,
): PermissionPolicyDocument = transitionMutex.withLock {
    val (projectId, engine) = active() ?: error("No active workspace")
    check(projectId == expectedProjectId) { "The workspace changed. Reopen Settings before changing permissions." }
    val updated = engine.policySnapshot().withSafeBroadSetting(action, effect)
    persist(projectId, updated)
    engine.replacePolicy(updated)
    updated
}

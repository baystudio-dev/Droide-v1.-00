package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Source-authored only. Unknown contributors conservatively retain every applicable DENY. */
class BrowserUnverifiedOutputPolicyTest {
    private val allow = PermRule("*", "*", PermEffect.ALLOW)
    private val remote = PermRule("webfetch", "https://private.test/secret", PermEffect.DENY)
    private val local = PermRule("browser_local", "http://localhost:9000/private", PermEffect.DENY)

    @Test fun everyReadOrLocalLayerBlocksUnknownContributorsEvenOnPublicTopPage() {
        for (deny in listOf(remote, local, remote.copy(action = "web*"), local.copy(action = "browser_?ocal"))) {
            val engines = listOf(
                PermissionEngine(listOf(allow, deny)),
                PermissionEngine(listOf(allow), hardRules = listOf(deny)),
                PermissionEngine(listOf(allow), PermissionPolicyDocument(rules = listOf(deny))),
                PermissionEngine(listOf(allow), PermissionPolicyDocument(agents = mapOf("build" to listOf(deny)))),
            )
            for (engine in engines) {
                assertEquals(PermEffect.ALLOW, engine.decide("webfetch", "https://public.test/", "build"))
                assertTrue(engine.browserOutputAuthority("build").unresolvedDeny)
            }
        }
    }

    @Test fun shadowedOrderedDenialRemainsUnresolvedButOtherActionsAndScopesDoNot() {
        val engine = PermissionEngine(listOf(allow), PermissionPolicyDocument(
            rules = listOf(remote, allow), agents = mapOf("review" to listOf(local))))
        assertEquals(PermEffect.ALLOW, engine.decide("webfetch", remote.resource, "build"))
        assertTrue(engine.browserOutputAuthority("build").unresolvedDeny)
        val scoped = PermissionEngine(listOf(allow, PermRule("shell", "*", PermEffect.DENY)),
            PermissionPolicyDocument(agents = mapOf("review" to listOf(local))))
        assertFalse(scoped.browserOutputAuthority("build").unresolvedDeny)
        assertTrue(scoped.browserOutputAuthority("review").unresolvedDeny)
    }

    @Test fun crossLayerAllowAndSessionGrantCannotOverrideDenial() {
        val engine = PermissionEngine(listOf(allow), PermissionPolicyDocument(
            rules = listOf(remote), agents = mapOf("build" to listOf(allow))))
        engine.allowForSession("webfetch", "*", "build")
        assertEquals(PermEffect.DENY, engine.decide("webfetch", remote.resource, "build"))
        assertTrue(engine.browserOutputAuthority("build").unresolvedDeny)
    }

    @Test fun mutableHardRuleInputCannotChangeAuthorityWithoutRevision() {
        val input = mutableListOf(remote)
        val engine = PermissionEngine(listOf(allow), hardRules = input)
        input.clear()
        assertTrue(engine.browserOutputAuthority("build").unresolvedDeny)
        assertEquals(PermEffect.DENY, engine.decide("webfetch", remote.resource))
        val initiallyEmpty = mutableListOf<PermRule>()
        val isolated = PermissionEngine(listOf(allow), hardRules = initiallyEmpty)
        val authority = isolated.browserOutputAuthority("build")
        initiallyEmpty += remote
        assertTrue(isolated.browserOutputAuthorityCurrent(authority, "build"))
    }

    @Test fun engineScopeForkAndPolicyAbaCannotBorrowAuthority() {
        val engine = PermissionEngine(listOf(allow))
        val authority = engine.browserOutputAuthority("BUILD")
        assertTrue(engine.browserOutputAuthorityCurrent(authority, "build"))
        assertFalse(engine.browserOutputAuthorityCurrent(authority, "review"))
        assertFalse(engine.fork().browserOutputAuthorityCurrent(authority, "build"))
        engine.replacePolicy(PermissionPolicyDocument(rules = listOf(remote)))
        engine.replacePolicy(PermissionPolicyDocument.EMPTY)
        assertFalse(engine.browserOutputAuthorityCurrent(authority, "build"))
    }

    @Test fun actualGrantMutationInvalidatesButDuplicateAndEmptyClearDoNot() {
        val engine = PermissionEngine(listOf(allow))
        val initial = engine.browserOutputAuthority("build")
        engine.clearSessionAllows()
        assertTrue(engine.browserOutputAuthorityCurrent(initial, "build"))
        engine.allowExactForSession("webfetch", "https://example.test/", "build")
        assertFalse(engine.browserOutputAuthorityCurrent(initial, "build"))
        val granted = engine.browserOutputAuthority("build")
        engine.allowExactForSession("webfetch", "https://example.test/", "build")
        assertTrue(engine.browserOutputAuthorityCurrent(granted, "build"))
        engine.clearSessionAllows()
        assertFalse(engine.browserOutputAuthorityCurrent(granted, "build"))
    }
}

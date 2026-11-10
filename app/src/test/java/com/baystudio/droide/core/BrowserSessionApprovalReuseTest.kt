package com.baystudio.droide.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowserSessionApprovalReuseTest {
    private suspend fun approve(
        manager: ApprovalManager,
        resource: String,
        action: String = "browser_local",
        session: String = "session-a",
        scope: String = "build",
        grantResource: String = resource,
        resolution: ApprovalResolution = ApprovalResolution.ALLOW_SESSION,
    ): ApprovalRequest = coroutineScope {
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval(action, "Browser fixture", resource, action, resource, scope,
                AgentRequestProvenance.primary(session), sessionGrantResource = grantResource)
        }
        val pending = requireNotNull(manager.pending.value)
        manager.resolve(resolution)
        assertEquals(ApprovalDecision.Approved, request.await())
        pending
    }

    @Test fun explicitLocalSessionChoiceApprovesLaterPathsOnTheSameOriginWithoutAnotherPrompt() = runBlocking {
        val manager = ApprovalManager()
        val first = approve(manager, "http://localhost:3000/a?fixture=private#fragment")
        assertEquals("http://localhost:3000/a?fixture=private#fragment", first.resource)
        assertEquals("http://localhost:3000", first.sessionGrantResource)
        assertTrue(first.canAllowForSession)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("browser_local", "Next read", "", "browser_local", "http://localhost:3000/b?other=1", "build", AgentRequestProvenance.primary("session-a"))
        }
        assertTrue("Same-origin session authority should not queue another user question", next.isCompleted)
        assertNull(manager.pending.value)
        assertEquals(ApprovalDecision.Approved, next.await())
    }

    @Test fun localGrantDoesNotCrossPortsSchemesLiteralHostAliasesActionsOrAgentSessions() = runBlocking {
        val manager = ApprovalManager()
        approve(manager, "http://localhost:3000/a")
        for (url in listOf("http://localhost:3001/a", "https://localhost:3000/a", "http://127.0.0.1:3000/a", "http://[::1]:3000/a", "http://192.168.1.2:3000/a", "http://user@localhost:3000/a")) {
            assertFalse(url, manager.hasSessionApproval("session-a", "build", "browser_local", url))
        }
        assertFalse(manager.hasSessionApproval("session-b", "build", "browser_local", "http://localhost:3000/a"))
        assertFalse(manager.hasSessionApproval("session-a", "reviewer", "browser_local", "http://localhost:3000/a"))
        assertFalse(manager.hasSessionApproval("session-a", "build", "webfetch", "http://localhost:3000/a"))
        assertFalse(manager.hasSessionApproval("session-a", "build", "browser_interact", "http://localhost:3000/a"))
        assertFalse(ApprovalManager().hasSessionApproval("session-a", "build", "browser_local", "http://localhost:3000/a"))
    }

    @Test fun allowOnceRemainsOnceAndRejectedLocalOriginsCannotAcquireSessionAuthority() = runBlocking {
        val manager = ApprovalManager()
        approve(manager, "http://localhost:3000/a", resolution = ApprovalResolution.ALLOW_ONCE)
        assertFalse(manager.hasSessionApproval("session-a", "build", "browser_local", "http://localhost:3000/a"))
        val repeat = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("browser_local", "Repeat", "", "browser_local", "http://localhost:3000/a", "build", AgentRequestProvenance.primary("session-a"))
        }
        assertNotNull(manager.pending.value)
        manager.resolve(ApprovalResolution.DENY)
        assertEquals(ApprovalDecision.Denied, repeat.await())
        for (invalid in listOf("http://user@localhost:3000/a", "http://localhost:*/a", "http://192.168.1.2/a")) {
            assertFalse(approve(manager, invalid).canAllowForSession)
            assertFalse(manager.hasSessionApproval("session-a", "build", "browser_local", invalid))
        }
    }

    @Test fun childAgentProvenanceCannotInheritTheParentOriginGrant() = runBlocking {
        val manager = ApprovalManager()
        approve(manager, "http://localhost:3000/a")
        val child = AgentExecutionIdentity.subagent("explore", "task-1", "session-a", 1).request("child-session")
        val childScope = AgentPermissionScope.id(AgentMode.BUILD, child)
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("browser_local", "Child browser", "", "browser_local", "http://localhost:3000/b", childScope, child)
        }
        val pending = requireNotNull(manager.pending.value)
        assertEquals(child, pending.provenance)
        assertEquals("child-session", pending.approvalSessionId)
        assertEquals("explore", pending.permissionScope)
        assertTrue(pending.summary.startsWith(child.uiPrefix()))
        manager.resolve(ApprovalResolution.DENY)
        assertEquals(ApprovalDecision.Denied, request.await())
        assertTrue(manager.hasSessionApproval("session-a", "build", "browser_local", "http://localhost:3000/b"))
        assertFalse(manager.hasSessionApproval("child-session", childScope, "browser_local", "http://localhost:3000/b"))
    }

    @Test fun onlyDedicatedBrowserReadGrantsReuseFetchApprovalAcrossLocalPaths() = runBlocking {
        val manager = ApprovalManager()
        val firstUrl = "http://localhost:3000/a"
        val key = BrowserSessionApprovalScope.browserReadGrantResource(firstUrl)
        val approval = approve(manager, firstUrl, action = "webfetch", grantResource = key)
        assertEquals(firstUrl, approval.resource)
        assertEquals(key, approval.sessionGrantResource)
        assertTrue(manager.hasSessionApproval("session-a", "build", "webfetch", BrowserSessionApprovalScope.browserReadGrantResource("http://localhost:3000/b")))
        assertFalse("An ordinary webfetch URL must not inherit browser-only authority", manager.hasSessionApproval("session-a", "build", "webfetch", firstUrl))
        assertFalse(manager.hasSessionApproval("session-a", "build", "webfetch", "http://localhost:3000/b"))
        assertFalse(manager.hasSessionApproval("session-a", "build", "webfetch", BrowserSessionApprovalScope.browserReadGrantResource("http://localhost:3001/a")))
        assertFalse(manager.hasSessionApproval("session-a", "build", "browser_interact", key))
    }

    @Test fun ordinaryPublicAndLocalFetchGrantsRetainTheirExactResources() = runBlocking {
        val manager = ApprovalManager()
        for (url in listOf("https://example.test/a", "http://localhost:3000/a")) {
            approve(manager, url, action = "webfetch")
            assertTrue(manager.hasSessionApproval("session-a", "build", "webfetch", url))
            assertFalse(manager.hasSessionApproval("session-a", "build", "webfetch", url.replace("/a", "/b")))
        }
    }

    @Test fun revocationAndNewWorkspaceManagersDoNotRetainOriginAuthority() = runBlocking {
        val manager = ApprovalManager()
        approve(manager, "http://localhost:3000/a", session = "session-a", scope = "build")
        approve(manager, "http://localhost:3000/a", session = "session-a", scope = "reviewer")
        approve(manager, "http://localhost:3000/a", session = "session-b", scope = "build")
        manager.clearSessionApprovals("session-a", "build")
        assertFalse(manager.hasSessionApproval("session-a", "build", "browser_local", "http://localhost:3000/b"))
        assertTrue(manager.hasSessionApproval("session-a", "reviewer", "browser_local", "http://localhost:3000/b"))
        assertTrue(manager.hasSessionApproval("session-b", "build", "browser_local", "http://localhost:3000/b"))
        manager.clearAllSessionApprovals()
        assertFalse(manager.hasSessionApproval("session-b", "build", "browser_local", "http://localhost:3000/b"))
        assertFalse(ApprovalManager().hasSessionApproval("session-a", "reviewer", "browser_local", "http://localhost:3000/b"))
    }

    @Test fun exactEngineLocalCapabilitiesHonorActualPathDenialsAndStayAgentScoped() {
        val denied = PermRule("browser_local", "http://localhost:3000/private*", PermEffect.DENY)
        val engines = listOf(
            PermissionEngine(hardRules = listOf(denied)),
            PermissionEngine(base = PermissionEngine.defaults() + denied),
            PermissionEngine(policy = PermissionPolicyDocument(rules = listOf(denied))),
            PermissionEngine(policy = PermissionPolicyDocument(agents = mapOf("build" to listOf(denied)))),
        )
        for (engine in engines) {
            engine.allowExactForSession("browser_local", "http://localhost:3000/a", "build")
            assertEquals(PermEffect.ALLOW, engine.decide("browser_local", "http://localhost:3000/b?fixture=1", "build"))
            assertEquals(PermEffect.DENY, engine.decide("browser_local", "http://localhost:3000/private/data", "build"))
            assertEquals(PermEffect.ASK, engine.decide("browser_local", "http://localhost:3001/b", "build"))
            assertEquals(PermEffect.ASK, engine.decide("browser_local", "http://localhost:3000/b", "reviewer"))
            assertEquals(PermEffect.ASK, engine.decide("webfetch", "http://localhost:3000/b", "build"))
        }
    }

    @Test fun deniedOriginGrantAndPolicyReplacementCannotCreateOrKeepEngineAuthority() {
        val denied = PermRule("browser_local", "http://localhost:3000/private*", PermEffect.DENY)
        val engine = PermissionEngine(policy = PermissionPolicyDocument(rules = listOf(denied)))
        engine.allowExactForSession("browser_local", "http://localhost:3000/private", "build")
        assertEquals(PermEffect.ASK, engine.decide("browser_local", "http://localhost:3000/a", "build"))
        engine.allowExactForSession("browser_local", "http://localhost:3000/a", "build")
        assertEquals(PermEffect.ASK, engine.fork().decide("browser_local", "http://localhost:3000/b", "build"))
        engine.replacePolicy(PermissionPolicyDocument.EMPTY)
        assertEquals(PermEffect.ASK, engine.decide("browser_local", "http://localhost:3000/b", "build"))
    }

    @Test fun browserFetchOriginReuseStillChecksPolicyAgainstTheActualUrl() = runBlocking {
        val manager = ApprovalManager()
        val key = BrowserSessionApprovalScope.browserReadGrantResource("http://localhost:3000/a")
        approve(manager, "http://localhost:3000/a", action = "webfetch", grantResource = key)
        val permissions = PermissionEngine(policy = PermissionPolicyDocument(rules = listOf(
            PermRule("webfetch", "http://localhost:3000/private*", PermEffect.DENY),
        )))
        suspend fun canRead(url: String) = PermissionApprovalGate.approved(
            permissions, manager, "webfetch", url, "build", "browser", "Read browser", url,
            AgentRequestProvenance.primary("session-a"), sessionGrantResource = BrowserSessionApprovalScope.browserReadGrantResource(url),
        )
        assertTrue(canRead("http://localhost:3000/b"))
        assertFalse(canRead("http://localhost:3000/private/data"))
        assertNull("A stored origin grant must not queue or override a policy DENY", manager.pending.value)
    }

    @Test fun policyChangedWhileAwaitingApprovalStillRevokesTheActualBrowserPath() = runBlocking {
        val manager = ApprovalManager()
        val permissions = PermissionEngine()
        val url = "http://localhost:3000/private/data"
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            PermissionApprovalGate.approved(permissions, manager, "webfetch", url, "build", "browser", "Read browser", url,
                AgentRequestProvenance.primary("session-a"), sessionGrantResource = BrowserSessionApprovalScope.browserReadGrantResource(url))
        }
        assertNotNull(manager.pending.value)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", url, PermEffect.DENY))))
        manager.resolve(ApprovalResolution.ALLOW_SESSION)
        assertFalse(request.await())
        assertEquals(PermEffect.DENY, permissions.decide("webfetch", url, "build"))
    }
}

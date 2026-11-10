package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BrowserSiteSessionTest {
    private suspend fun grant(manager: ApprovalManager, url: String, resolution: ApprovalResolution = ApprovalResolution.ALLOW_SESSION) = coroutineScope {
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("browser_interact", "Browser", "", "browser_interact", "$url#click:d1", "build",
                AgentRequestProvenance.primary("chat-a"), sessionGrantAction = BrowserSiteSession.ACTION,
                sessionGrantResource = requireNotNull(BrowserSiteSession.resource(url)))
        }
        assertTrue(manager.resolve(requireNotNull(manager.pending.value).id, resolution))
        assertEquals(ApprovalDecision.Approved, response.await())
    }

    @Test fun changingFieldsTextPathsAndOutputsReuseOneExplicitSiteGrant() = runBlocking {
        val manager = ApprovalManager()
        grant(manager, "https://example.com/form")
        for ((action, path) in listOf("browser_interact" to "/form#type:d2", "webfetch" to "/done", "browser_unverified_output" to "/done")) {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                manager.requestApproval(action, "Next browser step", "", action, "https://example.com$path", "build",
                    AgentRequestProvenance.primary("chat-a"), sessionGrantAction = BrowserSiteSession.ACTION,
                    sessionGrantResource = requireNotNull(BrowserSiteSession.resource("https://example.com$path")))
            }
            assertTrue("No repeated prompt for $action", response.isCompleted)
            assertEquals(ApprovalDecision.Approved, response.await()); assertNull(manager.pending.value)
        }
    }

    @Test fun exactOriginSessionScopeAndExplicitChoiceRemainRequired() = runBlocking {
        val manager = ApprovalManager()
        val url = "https://example.com/form"
        grant(manager, url, ApprovalResolution.ALLOW_ONCE)
        val key = requireNotNull(BrowserSiteSession.resource(url))
        assertFalse(manager.hasSessionApproval("chat-a", "build", BrowserSiteSession.ACTION, key))
        grant(manager, url)
        for (other in listOf("https://other.example.com", "https://example.com:8443", "https://example.net"))
            assertFalse(other, manager.hasSessionApproval("chat-a", "build", BrowserSiteSession.ACTION, requireNotNull(BrowserSiteSession.resource(other))))
        assertFalse(manager.hasSessionApproval("chat-b", "build", BrowserSiteSession.ACTION, key))
        assertFalse(manager.hasSessionApproval("chat-a", "reviewer", BrowserSiteSession.ACTION, key))
        assertNull(BrowserSiteSession.resource("http://example.com"))
        assertNull(BrowserSiteSession.resource("https://user:secret@example.com"))
        assertNull(BrowserSiteSession.resource("file:///private"))
        assertNotEquals(BrowserSiteSession.resource("http://localhost:3000"), BrowserSiteSession.resource("http://127.0.0.1:3000"))
    }

    @Test fun revocationCannotBeUndoneByReapprovalForAnInFlightCall() = runBlocking {
        val manager = ApprovalManager(); val url = "https://example.com"
        grant(manager, url)
        val validity = requireNotNull(manager.sessionApprovalValidity("chat-a", "build", BrowserSiteSession.ACTION, requireNotNull(BrowserSiteSession.resource(url))))
        val publication = AgentTools.BrowserPublicationAuthority().apply { capture { null }; bindSessionGrant(validity) }
        assertNull(publication.denial())
        manager.clearSessionApprovals("chat-a")
        assertFalse(validity()); assertNotNull(publication.denial())
        grant(manager, url)
        assertFalse(validity()); assertNotNull(publication.denial())
    }

    @Test fun siteGrantDoesNotChangeConcretePolicyDenyDecisions() = runBlocking {
        val manager = ApprovalManager(); val url = "https://example.com/admin"
        grant(manager, url)
        val policy = PermissionEngine(base = listOf(PermRule("browser_interact", "*", PermEffect.ASK)),
            hardRules = listOf(PermRule("browser_interact", "$url#click:d9", PermEffect.DENY)))
        assertEquals(PermEffect.DENY, policy.decide("browser_interact", "$url#click:d9", "build"))
        assertEquals(PermEffect.ASK, policy.decide("browser_interact", "$url#type:d8", "build"))
    }

    @Test fun missingMalformedAndStringAcknowledgmentsFailClosed() {
        for (raw in listOf("null", "{}", "[]", "oops", "{\"ok\":\"true\"}"))
            assertTrue(raw, runCatching { AgentBrowserInteraction.result(raw) }.isFailure)
        assertEquals("false", AgentBrowserInteraction.result("{\"ok\":false}")["ok"].toString())
    }
}

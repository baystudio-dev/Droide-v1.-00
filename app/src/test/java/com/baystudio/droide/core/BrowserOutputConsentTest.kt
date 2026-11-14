package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Trusted scope/state tests; no renderer or runtime execution was performed for this repair. */
class BrowserOutputConsentTest {
    private val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))
    private val document = AgentBrowserController.DocumentIdentity(Any(), 3, "https://example.test/", "document-3")
    private var current = true
    private fun token(operation: String = "screenshot", fullPage: Boolean = false, console: Any? = null) =
        BrowserOutputConsent.create(permissions, BrowserOutputConsent.Request("session", "run", "2:call-id", "build",
            document, operation, fullPage, console), { current }, { current })
    private fun observation() = AgentBrowserController.Observation("private-output", listOf(
        AgentBrowserController.ObservationSource(document.url, document)), hasUnknownSource = true)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected the one-call authority to reject") }
        catch (_: BrowserPublicationRevokedException) { }
    }

    @Test fun onlyExactConsumedObservationHasRepeatablePublicationAuthority() {
        val token = token()
        assertTrue(token.validForApproval()); assertFalse(token.validForDispatch())
        assertTrue(token.approve()); assertFalse(token.approve())
        token.consume("screenshot", document)
        val observed = token.complete(observation())
        repeat(3) { assertTrue(observed.outputConsent!!.permits(observed)) }
        assertFalse(observed.outputConsent!!.permits(observed.copy(payload = "substituted")))
        assertFalse(observed.outputConsent!!.permits(observation()))
        rejected { token.complete(observation()) }
        assertFalse(observed.outputConsent!!.permits(observed))
    }

    @Test fun changedOperationCaptureScopeRendererAndConsoleSnapshotBurnConsent() {
        val alternate = AgentBrowserController.DocumentIdentity(Any(), 3, document.url, "document-3")
        val cases: List<(BrowserOutputConsent) -> Unit> = listOf(
            { it.consume("console", document, consoleIdentity = Any()) },
            { it.consume("screenshot", document, fullPage = true) },
            { it.consume("screenshot", alternate) },
            { it.consume("screenshot", AgentBrowserController.DocumentIdentity(document.leaseToken, 4, document.url, "document-4")) },
        )
        for (attempt in cases) {
            val token = token(); assertTrue(token.approve()); rejected { attempt(token) }
            assertFalse(token.validForDispatch()); rejected { token.consume("screenshot", document) }
        }
        val identity = Any()
        val console = token("console", console = identity); assertTrue(console.approve())
        rejected { console.consume("console", document, consoleIdentity = Any()) }
        assertFalse(console.validForDispatch())
    }

    @Test fun consumedTokenCannotAuthorizeReplayOrSiblingOutput() {
        val token = token(); assertTrue(token.approve()); token.consume("screenshot", document)
        val output = token.complete(observation())
        rejected { token.consume("screenshot", document) }
        assertFalse(output.outputConsent!!.permits(output))
    }

    @Test fun interveningPolicyMutationInvalidatesPendingAndConsumedConsentIrreversibly() {
        val pending = token()
        val consumed = token(); assertTrue(consumed.approve()); consumed.consume("screenshot", document)
        val output = consumed.complete(observation())
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", "https://other.test/*", PermEffect.DENY))))
        permissions.replacePolicy(PermissionPolicyDocument.EMPTY)
        assertFalse(pending.approve()); assertFalse(output.outputConsent!!.permits(output))
        assertFalse(consumed.validForDispatch())
    }

    @Test fun cancelledRequestCannotReviveAndFreshRequestsHaveDifferentNonces() {
        val first = token(); val second = token()
        assertNotEquals(first.request.nonce, second.request.nonce)
        current = false; assertFalse(first.validForApproval())
        current = true; assertFalse(first.approve())
        second.invalidate(); assertFalse(second.approve())
    }

    @Test fun unresolvedDenyCannotBeApprovedAsAnException() {
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("browser_local", "http://localhost:1/*", PermEffect.DENY))))
        rejected { token() }
    }
}

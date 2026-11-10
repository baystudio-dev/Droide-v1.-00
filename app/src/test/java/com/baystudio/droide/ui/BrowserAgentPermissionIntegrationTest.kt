package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.WebView
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserAgentPermissionIntegrationTest {
    private val root = Files.createTempDirectory("browser-tool-approval-").toFile()
    private val browser = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val permissions = PermissionEngine()
    private val context = agentWorkflowContext(root, permissions = permissions, browserController = browser,
        provenance = AgentRequestProvenance.primary("browser-agent-session"))
    private lateinit var view: PageView

    private class PageView(context: Context, var page: String) : WebView(context) {
        var evaluations = 0
        var acknowledge = false
        override fun getUrl() = page
        override fun evaluateJavascript(script: String, resultCallback: android.webkit.ValueCallback<String>?) {
            evaluations++
            if (acknowledge) {
                val payload = if (script.contains("const a=")) "{\"ok\":true,\"action\":\"type\",\"target\":\"d1\",\"accepted\":true}"
                    else buildJsonObject { put("ok", true); put("url", page); put("lines", buildJsonArray {}) }.toString()
                resultCallback?.onReceiveValue(JsonPrimitive(payload).toString())
            } else super.evaluateJavascript(script, resultCallback)
        }
    }

    @Before fun attach() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        browser.bindOpenRequest {}
        view = PageView(RuntimeEnvironment.getApplication(), "http://localhost:3000/start")
        browser.configure(view); browser.attach(view)
    }

    @After fun cleanup() {
        caller.cancel(); view.destroy(); context.webResearch.close(); root.deleteRecursively()
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Browser tool must complete or request approval promptly", condition())
    }

    private fun start(operation: String = "status", ctx: AgentTools.Ctx = context) = caller.async {
        AgentTools.execute("browser", buildJsonObject { put("operation", operation); put("target", "obsolete-dialog"); put("text", "dismiss") }, ctx)
    }

    private fun respondToDialog(id: String, answer: String = "private-native-prompt-answer") = caller.async {
        AgentTools.execute("browser", buildJsonObject {
            put("operation", "dialog_respond"); put("target", id)
            put("text", "accept"); put("dialog_text", answer)
        }, context)
    }

    private fun approveInitialBrowserOrigin() {
        val operation = start()
        pumpUntil { context.approvals.pending.value?.action == "webfetch" }
        assertTrue(context.approvals.pending.value!!.canAllowForSession)
        assertTrue(context.approvals.pending.value!!.browserContext!!.sessionAccess)
        context.approvals.resolve(ApprovalResolution.ALLOW_SESSION)
        pumpUntil { operation.isCompleted }
        assertNull("One explicit site grant covers browser read/local requirements", context.approvals.pending.value)
        assertEquals("browser_status", Json.parseToJsonElement(runBlocking { operation.await() }).jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun actualBrowserDispatchReusesBothReadAndLocalOriginApprovalsAcrossPaths() {
        approveInitialBrowserOrigin()
        view.page = "http://localhost:3000/second?query=changed#section"
        view.webViewClient.onPageStarted(view, view.page, null)
        val operation = start()
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull("A path change must not repeat session approvals for the same origin", context.approvals.pending.value)
        val result = Json.parseToJsonElement(runBlocking { operation.await() }).jsonObject
        assertEquals(view.page, result["url"]!!.jsonPrimitive.content)
        assertEquals(0, view.evaluations)
    }

    @Test fun multipleFormStepsAfterSiteApprovalDoNotPromptAgainAndRevocationStopsReuse() {
        approveInitialBrowserOrigin()
        browser.setKeepActive(true); view.acknowledge = true
        for ((target, text) in listOf("#first" to "Nadia", "#second" to "Bandung")) {
            val operation = caller.async {
                AgentTools.execute("browser", buildJsonObject {
                    put("operation", "type"); put("target", target); put("text", text); put("wait_ms", 50)
                }, context)
            }
            pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
            assertNull("Each field must reuse the approved site", context.approvals.pending.value)
            assertEquals(JsonPrimitive(true), Json.parseToJsonElement(runBlocking { operation.await() }).jsonObject["success"])
        }
        assertTrue(view.evaluations >= 4)
        context.approvals.clearSessionApprovals("browser-agent-session")
        val next = start()
        pumpUntil { context.approvals.pending.value != null }
        context.approvals.resolve(ApprovalResolution.DENY)
        pumpUntil { next.isCompleted }
        assertTrue(runBlocking { next.await() }.startsWith("DENIED"))
    }

    @Test fun browserReadApprovalCarriesPresentationWithoutEvaluatingBeforeApproval() {
        val operation = start("read")
        pumpUntil { context.approvals.pending.value != null }
        val request = requireNotNull(context.approvals.pending.value)
        val review = requireNotNull(request.browserContext)
        assertEquals("read", review.operation)
        assertEquals(view.page, review.url)
        assertEquals("http://localhost:3000", review.site)
        assertEquals("webfetch", request.action)
        assertEquals(view.page, request.resource)
        assertEquals(0, view.evaluations)
        assertTrue(context.approvals.resolve(request.id, ApprovalResolution.DENY))
        pumpUntil { operation.isCompleted }
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED"))
        assertEquals(0, view.evaluations)
    }

    @Test fun browserInputApprovalDescribesSubmissionWithoutCopyingEnteredText() {
        val secret = "private-fixture-input"
        val operation = caller.async {
            AgentTools.execute("browser", buildJsonObject {
                put("operation", "type"); put("target", "#message"); put("text", secret); put("submit", true)
            }, context)
        }
        pumpUntil { context.approvals.pending.value != null }
        val request = requireNotNull(context.approvals.pending.value)
        val review = requireNotNull(request.browserContext)
        assertEquals("browser_interact", request.action)
        assertEquals("type", review.operation)
        assertEquals("#message", review.target)
        assertEquals(view.page, review.url)
        assertTrue(review.reason.contains("submit"))
        assertFalse(request.toString().contains(secret))
        assertEquals(0, view.evaluations)
        assertTrue(context.approvals.resolve(request.id, ApprovalResolution.DENY))
        pumpUntil { operation.isCompleted }
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED"))
        assertEquals(0, view.evaluations)
    }

    @Test fun aNewPortRequiresFreshApprovalAndCanBeDeniedBeforeBrowserWork() {
        approveInitialBrowserOrigin()
        view.page = "http://localhost:3001/second"
        view.webViewClient.onPageStarted(view, view.page, null)
        val operation = start()
        pumpUntil { context.approvals.pending.value != null }
        assertEquals("webfetch", context.approvals.pending.value!!.action)
        context.approvals.resolve(ApprovalResolution.DENY)
        pumpUntil { operation.isCompleted }
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED"))
        assertEquals(0, view.evaluations)
    }

    @Test fun originReuseDoesNotOverrideAnActualPathPolicyDenyOrAuthorizeOrdinaryFetches() {
        approveInitialBrowserOrigin()
        val ordinaryFetch = caller.async { AgentTools.permissionGateForProbe("webfetch", view.page, context) }
        pumpUntil { context.approvals.pending.value != null }
        assertEquals(view.page, context.approvals.pending.value!!.sessionGrantResource)
        assertNull("Ordinary fetches must retain the generic approval presentation", context.approvals.pending.value!!.browserContext)
        context.approvals.resolve(ApprovalResolution.DENY)
        pumpUntil { ordinaryFetch.isCompleted }
        assertTrue(runBlocking { ordinaryFetch.await() }!!.startsWith("DENIED"))
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", "http://localhost:3000/private*", PermEffect.DENY))))
        view.page = "http://localhost:3000/private/report"
        view.webViewClient.onPageStarted(view, view.page, null)
        val operation = start()
        pumpUntil { operation.isCompleted }
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED (policy)"))
        assertNull(context.approvals.pending.value)
    }

    @Test fun dialogResponseIsDeniedInReadOnlyModesAndByInteractionPolicyBeforeEvaluation() {
        for (mode in listOf(AgentMode.PLAN, AgentMode.REVIEW, AgentMode.EXPLORE)) {
            val ctx = agentWorkflowContext(root, mode = mode, browserController = browser)
            val operation = start("dialog_respond", ctx)
            pumpUntil { operation.isCompleted }
            assertTrue(runBlocking { operation.await() }.startsWith("DENIED"))
            ctx.webResearch.close()
        }
        val ctx = agentWorkflowContext(root, permissions = PermissionEngine(listOf(PermRule("browser_interact", "*", PermEffect.DENY))), browserController = browser)
        val operation = start("dialog_respond", ctx)
        pumpUntil { operation.isCompleted }
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED (policy)"))
        assertEquals(0, view.evaluations)
        ctx.webResearch.close()
    }

    @Test fun hardReadDenyBlocksEveryObservationProducingOperationBeforeRendererWork() {
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("browser_interact", "*", PermEffect.ALLOW),
            PermRule("browser_local", "*", PermEffect.ALLOW),
            PermRule("webfetch", view.page, PermEffect.DENY),
        )))
        for (op in listOf("click", "type", "dialog_respond", "read", "find", "links", "scroll", "status",
            "console", "dialog", "back", "reload", "wait", "screenshot", "open", "navigate")) {
            val operation = caller.async {
                AgentTools.execute("browser", buildJsonObject {
                    put("operation", op); put("url", view.page); put("target", "body")
                    put("text", if (op == "dialog_respond") "dismiss" else "private-fixture-input")
                }, context)
            }
            pumpUntil { operation.isCompleted }
            val result = runBlocking { operation.await() }
            val expectedDenial = if (op in setOf("console", "screenshot"))
                "DENIED: unverified browser contributors" else "DENIED (policy): webfetch"
            assertTrue("$op must honor hard read DENY", result.startsWith(expectedDenial))
            assertFalse(result.contains("private-fixture-input"))
            assertNull(context.approvals.pending.value)
            assertEquals("$op must not run JavaScript", 0, view.evaluations)
        }
    }

    @Test fun localDenyPreflightsBeforeReadOrInteractionApproval() {
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("webfetch", "*", PermEffect.ASK),
            PermRule("browser_interact", "*", PermEffect.ASK),
            PermRule("browser_local", view.page, PermEffect.DENY),
        )))
        for (op in listOf("read", "status", "console", "screenshot", "click", "type", "dialog_respond")) {
            val operation = start(op)
            pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
            assertNull("$op must inspect the local DENY before asking about any other scope", context.approvals.pending.value)
            assertTrue("$op must finish without renderer work", operation.isCompleted)
            val expectedDenial = if (op in setOf("console", "screenshot"))
                "DENIED: unverified browser contributors" else "DENIED (policy): browser_local"
            assertTrue(runBlocking { operation.await() }.startsWith(expectedDenial))
            assertEquals(0, view.evaluations)
        }
    }

    @Test fun localDenyIntroducedDuringInteractionReviewSuppressesTheNextReadPrompt() {
        val operation = start("click")
        pumpUntil { context.approvals.pending.value?.action == "browser_interact" }
        val request = requireNotNull(context.approvals.pending.value)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("webfetch", "*", PermEffect.ASK),
            PermRule("browser_interact", "*", PermEffect.ASK),
            PermRule("browser_local", view.page, PermEffect.DENY),
        )))
        assertTrue(context.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull("An approved first scope cannot hide DENY while the next scope asks", context.approvals.pending.value)
        assertTrue(operation.isCompleted)
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED (policy): browser_local"))
        assertEquals(0, view.evaluations)
    }

    @Test fun sameUrlReplacementDuringRealApprovalCannotDispatchOrInstallAnOldGrant() {
        for (op in listOf("click", "type", "scroll", "back", "reload", "read")) {
            val operation = caller.async {
                AgentTools.execute("browser", buildJsonObject {
                    put("operation", op); put("target", "#submit")
                    put("text", if (op == "scroll") "down" else "fixture-input")
                }, context)
            }
            pumpUntil { context.approvals.pending.value != null }
            val request = requireNotNull(context.approvals.pending.value)
            view.webViewClient.onPageStarted(view, view.page, null)
            view.webViewClient.onPageFinished(view, view.page)
            assertTrue(context.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
            pumpUntil { operation.isCompleted }
            val result = runBlocking { operation.await() }
            assertTrue("$op must require a fresh document review: $result", result.contains("document changed"))
            assertFalse(context.approvals.hasSessionApproval(request.approvalSessionId,
                request.permissionScope, request.sessionGrantAction, request.sessionGrantResource))
            assertEquals(0, view.evaluations)
            assertNull(context.approvals.pending.value)
        }
    }

    @Test fun replacementRendererAtTheSameUrlCannotInheritPendingInteractionReview() {
        val operation = start("click")
        pumpUntil { context.approvals.pending.value != null }
        val request = requireNotNull(context.approvals.pending.value)
        val old = view
        view = PageView(RuntimeEnvironment.getApplication(), old.page)
        browser.configure(view); browser.attach(view)
        old.destroy()
        assertTrue(context.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        pumpUntil { operation.isCompleted }
        assertTrue(runBlocking { operation.await() }.contains("document changed"))
        assertEquals(0, view.evaluations)
        assertFalse(context.approvals.hasSessionApproval(request.approvalSessionId,
            request.permissionScope, request.sessionGrantAction, request.sessionGrantResource))
    }

    @Test fun completeLongInteractionPolicyTargetIsDeniedBeforeAnyApproval() {
        view.page = "http://localhost:3000/" + "a".repeat(650)
        view.webViewClient.onPageStarted(view, view.page, null)
        val target = "#" + "b".repeat(260) + "denied"
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("browser_interact", "${view.page}#click:$target", PermEffect.DENY),
        )))
        val operation = caller.async {
            AgentTools.execute("browser", buildJsonObject { put("operation", "click"); put("target", target) }, context)
        }
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull("The full target DENY must match rather than asking about its prefix", context.approvals.pending.value)
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED (policy): browser_interact"))
        assertEquals(0, view.evaluations)
    }

    @Test fun dialogReviewSeparatesAcceptAndDismissWithoutDisplayingPromptAnswer() {
        val keys = mutableListOf<String>()
        val native = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, view.page, "Choose a title", "", native))
        val dialogId = requireNotNull(browser.pendingDialog.value).id
        for (resolution in listOf("accept", "dismiss")) {
            val operation = caller.async {
                AgentTools.execute("browser", buildJsonObject {
                    put("operation", "dialog_respond"); put("target", dialogId)
                    put("text", resolution)
                    if (resolution == "accept") put("dialog_text", "sensitive-fixture-answer")
                }, context)
            }
            pumpUntil { context.approvals.pending.value != null }
            val request = requireNotNull(context.approvals.pending.value)
            assertTrue(requireNotNull(request.browserContext).reason.startsWith(if (resolution == "accept") "Accept" else "Dismiss"))
            assertFalse(request.toString().contains("sensitive-fixture-answer"))
            keys += request.sessionGrantResource
            context.approvals.resolve(request.id, ApprovalResolution.DENY)
            pumpUntil { operation.isCompleted }
            assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
            assertEquals(dialogId, browser.pendingDialog.value?.id)
        }
        assertEquals("Session choice grants the site, while each pending request still describes its exact action", keys[0], keys[1])
        assertEquals(0, view.evaluations)
        assertTrue(browser.respondHumanDialog(dialogId, false))
    }

    @Test fun requestingFrameReadDenyBlocksNativeResponseEvenWhenTopPageIsAllowed() {
        val source = "https://frame.example.test/private-dialog"
        val native = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, source, "Private requesting frame", "", native))
        val dialog = requireNotNull(browser.pendingDialog.value)
        assertEquals(source, dialog.sourceUrl)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("*", "*", PermEffect.ALLOW),
            PermRule("webfetch", source, PermEffect.DENY),
        )))
        val operation = respondToDialog(dialog.id)
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull("A requesting-source DENY must win before any approval", context.approvals.pending.value)
        assertTrue(operation.isCompleted)
        val result = runBlocking { operation.await() }
        assertTrue(result.startsWith("DENIED"))
        assertFalse(result.contains("private-native-prompt-answer"))
        assertEquals(dialog.id, browser.pendingDialog.value?.id)
        assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
        assertEquals(0, BrowserDialogTestResults.state(native).cancellations)
        assertEquals(0, view.evaluations)
    }

    @Test fun staleDialogIdCannotBorrowTheCurrentDialogsKnownSource() {
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("*", "*", PermEffect.ALLOW))))
        val first = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, view.page, "First prompt", "", first))
        val obsoleteId = requireNotNull(browser.pendingDialog.value).id
        val replacement = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, view.page, "Replacement prompt", "", replacement))
        val currentId = requireNotNull(browser.pendingDialog.value).id
        assertNotEquals(obsoleteId, currentId)
        val operation = respondToDialog(obsoleteId)
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull(context.approvals.pending.value)
        assertTrue(operation.isCompleted)
        val result = runBlocking { operation.await() }
        assertFalse(result.contains("\"success\":true"))
        assertTrue(result.contains("dialog", ignoreCase = true))
        assertEquals(currentId, browser.pendingDialog.value?.id)
        assertEquals(0, BrowserDialogTestResults.state(replacement).confirmations)
        assertEquals(0, BrowserDialogTestResults.state(replacement).cancellations)
        assertEquals(1, BrowserDialogTestResults.state(first).cancellations)
    }

    @Test fun requestingFrameInteractionDenyAlsoPreventsTheNativeResponse() {
        val source = "http://localhost:3001/frame-dialog"
        val native = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, source, "Frame submission", "", native))
        val id = requireNotNull(browser.pendingDialog.value).id
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("*", "*", PermEffect.ALLOW),
            PermRule("browser_interact", BrowserInteractionAuthority.policyResource(source, "dialog_respond", id), PermEffect.DENY),
        )))
        val operation = respondToDialog(id)
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull(context.approvals.pending.value)
        assertTrue(operation.isCompleted)
        assertTrue(runBlocking { operation.await() }.startsWith("DENIED (policy): browser_interact"))
        assertEquals(id, browser.pendingDialog.value?.id)
        assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
        assertEquals(0, BrowserDialogTestResults.state(native).cancellations)
    }

    @Test fun exactCurrentDialogCanBeAnsweredAfterItsRequestingSourceIsReviewed() {
        val source = "http://localhost:3001/reviewed-dialog"
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("*", "*", PermEffect.ALLOW),
            PermRule("webfetch", source, PermEffect.ASK),
        )))
        val native = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, source, "Current frame prompt", "", native))
        val id = requireNotNull(browser.pendingDialog.value).id
        val operation = respondToDialog(id, "reviewed-native-answer")
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        val request = requireNotNull(context.approvals.pending.value)
        assertEquals("webfetch", request.action)
        assertEquals(source, request.resource)
        assertEquals(id, request.browserContext?.target)
        assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
        assertTrue(context.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull("The exact pre-reviewed source must not ask again after answering the native prompt", context.approvals.pending.value)
        assertTrue(operation.isCompleted)
        val result = Json.parseToJsonElement(runBlocking { operation.await() }).jsonObject
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive(id), result["resolved_id"])
        assertNull(browser.pendingDialog.value)
        assertEquals(1, BrowserDialogTestResults.state(native).confirmations)
        assertEquals("reviewed-native-answer", BrowserDialogTestResults.state(native).promptText)
        assertEquals(0, view.evaluations)
    }

    @Test fun replacementDialogAtTheSameSourceCannotInheritAnAwaitingSourceApproval() {
        val source = "https://frame.example.test/reviewed-dialog"
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("*", "*", PermEffect.ALLOW),
            PermRule("webfetch", source, PermEffect.ASK),
        )))
        val first = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, source, "First prompt", "", first))
        val original = requireNotNull(browser.pendingDialog.value)
        val operation = respondToDialog(original.id)
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        val request = requireNotNull(context.approvals.pending.value)
        assertEquals("webfetch", request.action)
        assertEquals(source, request.resource)
        assertEquals(source, request.browserContext?.url)
        assertFalse(request.toString().contains("private-native-prompt-answer"))
        assertEquals(0, BrowserDialogTestResults.state(first).confirmations)
        assertEquals(0, BrowserDialogTestResults.state(first).cancellations)

        val replacement = BrowserDialogTestResults.prompt()
        assertTrue(view.webChromeClient!!.onJsPrompt(view, source, "Different prompt on the same source", "", replacement))
        val replacementId = requireNotNull(browser.pendingDialog.value).id
        assertNotEquals(original.id, replacementId)
        assertTrue(context.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
        assertNull("A replacement dialog requires a new request, not another scope prompt", context.approvals.pending.value)
        assertTrue(operation.isCompleted)
        val result = runBlocking { operation.await() }
        assertTrue(result.contains("changed", ignoreCase = true))
        assertFalse(result.contains("private-native-prompt-answer"))
        assertEquals(replacementId, browser.pendingDialog.value?.id)
        assertEquals(0, BrowserDialogTestResults.state(replacement).confirmations)
        assertEquals(0, BrowserDialogTestResults.state(replacement).cancellations)
        assertFalse("A stale native dialog must not install an approval grant",
            context.approvals.hasSessionApproval(request.approvalSessionId, request.permissionScope,
                request.sessionGrantAction, request.sessionGrantResource))
        assertTrue(browser.respondHumanDialog(replacementId, true, "human-reviewed-answer"))
        assertEquals("human-reviewed-answer", BrowserDialogTestResults.state(replacement).promptText)
    }

    @Test fun unknownOrUnsupportedDialogSourceRejectsAgentButStillAllowsHumanResponse() {
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("*", "*", PermEffect.ALLOW))))
        for (source in listOf("about:blank", "file:///private/dialog.html", "http://insecure.example.test/dialog")) {
            val native = BrowserDialogTestResults.prompt()
            assertTrue(view.webChromeClient!!.onJsPrompt(view, source, "Human-only requesting source", "", native))
            val id = requireNotNull(browser.pendingDialog.value).id
            val operation = respondToDialog(id)
            pumpUntil { operation.isCompleted || context.approvals.pending.value != null }
            assertNull("Unknown source must fail closed without asking", context.approvals.pending.value)
            assertTrue(operation.isCompleted)
            val result = runBlocking { operation.await() }
            assertTrue("Agent must reject $source before answering native dialog: $result", result.startsWith("DENIED"))
            assertFalse(result.contains("private-native-prompt-answer"))
            assertEquals(id, browser.pendingDialog.value?.id)
            assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
            assertEquals(0, BrowserDialogTestResults.state(native).cancellations)
            assertTrue("The visible human path retains authority", browser.respondHumanDialog(id, true, "human-answer"))
            assertNull(browser.pendingDialog.value)
            assertEquals(1, BrowserDialogTestResults.state(native).confirmations)
            assertEquals("human-answer", BrowserDialogTestResults.state(native).promptText)
        }
        assertEquals(0, view.evaluations)
    }
}

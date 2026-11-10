package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebView
import android.view.View
import android.widget.FrameLayout
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

/** Actual Tool/Approval/Controller paths with controlled native callbacks. Authored, not executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserOutputConsentIntegrationTest {
    private val root = Files.createTempDirectory("browser-output-consent-").toFile()
    private val browser = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))
    private val contexts = mutableListOf<AgentTools.Ctx>()
    private lateinit var view: PageView
    private var activity: ActivityController<Activity>? = null
    private class PageView(context: Context) : WebView(context) {
        var page = "http://localhost:3000/consent"
        var evaluations = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun getUrl() = page
        override fun getTitle() = "frozen-title"
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) { evaluations++ }
    }
    @Before fun attach() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        browser.bindOpenRequest {}
        view = PageView(RuntimeEnvironment.getApplication())
        browser.configure(view); browser.attach(view)
        view.webViewClient.onPageStarted(view, view.page, null)
        view.webViewClient.onPageFinished(view, view.page)
    }
    @After fun cleanup() {
        caller.cancel(); contexts.forEach { it.webResearch.close() }; view.destroy(); activity?.pause()?.stop()?.destroy(); root.deleteRecursively()
    }
    private fun context() = agentWorkflowContext(root, permissions = permissions, browserController = browser,
        provenance = AgentRequestProvenance.primary("consent-session")).also { contexts += it }
    private fun log(message: String, source: String = view.page) {
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage(message, source, 1, ConsoleMessage.MessageLevel.LOG))
    }
    private fun pumpUntil(check: () -> Boolean) {
        val deadline = System.nanoTime() + 4_000_000_000L
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!check()) Thread.sleep(2)
        }
        assertTrue("Consent fixture did not reach the bounded state", check())
    }
    private fun start(c: AgentTools.Ctx, operation: String = "console", start: Int = 0, count: Int = 80) = caller.async {
        AgentTools.execute("browser", buildJsonObject {
            put("operation", operation); put("start_line", start); put("num_lines", count)
        }, c)
    }
    private fun pending(c: AgentTools.Ctx): ApprovalRequest {
        pumpUntil { c.approvals.pending.value != null }
        return requireNotNull(c.approvals.pending.value).also { assertEquals("browser_unverified_output", it.kind) }
    }
    private fun finish(result: Deferred<String>): String {
        pumpUntil { result.isCompleted }
        return runBlocking { result.await() }
    }
    private fun approve(c: AgentTools.Ctx) {
        val request = pending(c)
        assertFalse(request.canAllowForSession)
        assertEquals("", request.approvalSessionId)
        assertEquals("", request.sessionGrantAction)
        assertEquals("", request.sessionGrantResource)
        assertFalse(c.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        assertTrue(c.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
    }

    @Test fun ordinaryAllowAndSessionReadGrantStillRequireEachSpecialAsk() {
        permissions.allowForSession("webfetch", "*", "build")
        log("one-call-only")
        repeat(2) {
            val c = context(); val result = start(c)
            val request = pending(c)
            assertFalse(result.isCompleted)
            assertEquals(BrowserOutputConsent.DISCLOSURE, request.browserContext!!.outputDisclosure)
            assertFalse(request.toString().contains("one-call-only"))
            assertEquals(0, view.evaluations)
            approve(c)
            assertTrue(finish(result).contains("one-call-only"))
        }
    }

    @Test fun rememberedOrdinaryApprovalDoesNotSkipSpecialApproval() {
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", "*", PermEffect.ASK))))
        val c = context()
        val ordinary = caller.async { c.approvals.requestApproval("webfetch", "Read site", "Ordinary read",
            action = "webfetch", resource = view.page, permissionScope = c.permissionScope, provenance = c.provenance,
            sessionGrantResource = BrowserSessionApprovalScope.browserReadGrantResource(view.page)) }
        pumpUntil { c.approvals.pending.value != null }
        val request = requireNotNull(c.approvals.pending.value)
        assertTrue(request.canAllowForSession)
        assertTrue(c.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        pumpUntil { ordinary.isCompleted }
        assertEquals(ApprovalDecision.Approved, runBlocking { ordinary.await() })
        log("remembered-read-still-needs-consent")
        val result = start(c)
        approve(c)
        assertTrue(finish(result).contains("remembered-read-still-needs-consent"))
    }

    @Test fun frozenSelectionSurvivesDequeEvictionWithoutPublishingLaterPayloadOrSources() {
        log("excluded-before", "https://excluded.test/script.js")
        log("approved-private-line", "fixture-with-unknown-source.js")
        val c = context(); val result = start(c, start = 1, count = 1)
        val request = pending(c)
        assertTrue(request.browserContext!!.outputScope!!.contains("lines 1 to 2"))
        repeat(50) { log("later-unapproved-$it", "https://new-source.test/$it.js") }
        approve(c)
        val output = finish(result)
        assertTrue(output.contains("approved-private-line"))
        assertTrue(output.contains("frozen-title"))
        assertFalse(output.contains("excluded-before")); assertFalse(output.contains("later-unapproved"))
        assertFalse(output.contains("new-source.test"))
        assertEquals(0, view.evaluations)
        assertNull(c.browserPublication.denial())
    }

    @Test fun resourceSpecificUnknownLocalDenialBlocksPublicConsoleBeforeAnyAsk() {
        view.page = "https://public.example.test/"
        view.webViewClient.onPageStarted(view, view.page, null)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("browser_local", "http://localhost:9000/secret", PermEffect.DENY))))
        log("private-withheld")
        val c = context(); val output = finish(start(c))
        assertTrue("Unexpected output: $output; bounds=${view.width}x${view.height}", output.startsWith("DENIED")); assertFalse(output.contains("private-withheld"))
        assertNull(c.approvals.pending.value); assertEquals(0, view.evaluations)
    }

    @Test fun policyDenyThenAllowWhilePendingCannotReviveTheRequest() {
        log("never-publish")
        val c = context(); val result = start(c); val request = pending(c)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", "https://other.test/*", PermEffect.DENY))))
        permissions.replacePolicy(PermissionPolicyDocument.EMPTY)
        assertTrue(c.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        val output = finish(result)
        assertTrue("Unexpected output: $output; bounds=${view.width}x${view.height}", output.startsWith("DENIED")); assertFalse(output.contains("never-publish"))
        assertEquals(0, view.evaluations)
    }

    @Test fun unrelatedApprovalMutexWaitRechecksRevisionBeforeShowingSpecialAsk() {
        val c = context()
        val unrelated = caller.async { c.approvals.requestApproval("shell", "Unrelated", "fixture") }
        pumpUntil { c.approvals.pending.value != null }
        val original = requireNotNull(c.approvals.pending.value)
        log("never-publish")
        val result = start(c)
        pumpUntil { browser.retention.value.activeOperations > 0 }
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", "https://hidden.test/*", PermEffect.DENY))))
        assertTrue(c.approvals.resolve(original.id, ApprovalResolution.ALLOW_ONCE))
        pumpUntil { unrelated.isCompleted }
        assertTrue(finish(result).startsWith("DENIED"))
        assertNull(c.approvals.pending.value); assertEquals(0, view.evaluations)
    }

    @Test fun denyAfterApprovalWhileWaitingForRendererMutexWithholdsFrozenPayload() {
        val mutex = AgentBrowserController::class.java.getDeclaredField("executeMutex").apply { isAccessible = true }
            .get(browser) as kotlinx.coroutines.sync.Mutex
        runBlocking { mutex.lock() }
        log("queued-private-output")
        val c = context(); val result = start(c)
        try {
            approve(c)
            val executing = AgentBrowserController::class.java.getDeclaredField("activeOperations").apply { isAccessible = true }
            pumpUntil { executing.getInt(browser) == 1 }
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
                PermRule("browser_local", "http://localhost:9999/other", PermEffect.DENY))))
        } finally { mutex.unlock() }
        val output = finish(result)
        assertTrue("Unexpected output: $output; bounds=${view.width}x${view.height}", output.startsWith("DENIED")); assertFalse(output.contains("queued-private-output"))
        assertEquals(0, view.evaluations)
    }

    @Test fun rejectionAndFullCancellationNeverPublishFrozenConsoleData() {
        log("rejected-private-output")
        val rejectedContext = context(); val rejected = start(rejectedContext)
        val request = pending(rejectedContext)
        assertTrue(rejectedContext.approvals.resolve(request.id, ApprovalResolution.DENY))
        val output = finish(rejected)
        assertTrue("Unexpected output: $output; bounds=${view.width}x${view.height}", output.startsWith("DENIED")); assertFalse(output.contains("rejected-private-output"))
        val cancelledContext = context(); val cancelled = start(cancelledContext)
        pending(cancelledContext)
        cancelled.cancel()
        pumpUntil { cancelled.isCompleted }
        assertTrue(cancelled.isCancelled)
        assertNull(cancelledContext.approvals.pending.value)
        assertEquals(0, browser.retention.value.activeOperations)
        assertEquals(0, view.evaluations)
    }

    @Test fun sameUrlReloadInvalidatesSnapshotAndApproval() {
        log("old-document-private")
        val c = context(); val result = start(c); val request = pending(c)
        view.webViewClient.onPageStarted(view, view.page, null)
        assertTrue(c.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        assertTrue(finish(result).startsWith("DENIED"))
        assertEquals(0, view.evaluations)
    }

    @Test fun stopDuringSpecialApprovalReleasesTicketAndKeepsHumanBrowserUsable() {
        val c = context(); val result = start(c); pending(c)
        assertTrue(browser.setKeepActive(false, cancelActive = true))
        val output = finish(result)
        assertTrue(output.contains("browser_unavailable"))
        assertNull(c.approvals.pending.value)
        assertEquals(0, browser.retention.value.activeOperations)
        assertTrue(caller.isActive)
        val human = caller.async { browser.executeObserved("status") }
        pumpUntil { human.isCompleted }
        assertTrue(runBlocking { human.await() }.payload.contains("browser_status"))
    }

    @Test fun screenshotDialogFallbackCannotBorrowOutputConsent() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val host = FrameLayout(activity!!.get())
        host.addView(view, FrameLayout.LayoutParams(120, 100))
        activity!!.get().setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        val width = View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        host.measure(width, height); host.layout(0, 0, 120, 100)
        view.forceLayout()
        view.measure(width, height); view.layout(0, 0, 120, 100)
        // The WebView shadow does not run Chromium layout; set real View bounds.
        view.left = 0
        view.top = 0
        view.right = 120
        view.bottom = 100
        assertTrue(view.isAttachedToWindow && view.isShown && view.isLaidOut)
        assertFalse(view.isLayoutRequested)
        assertEquals(120, view.width)
        assertEquals(100, view.height)
        root.resolve("consent.html").writeText("<html>Controlled dialog fallback</html>")
        view.page = browser.htmlPreviewUrl("consent.html")
        view.webViewClient.onPageStarted(view, view.page, null)
        view.webViewClient.onPageFinished(view, view.page)
        view.webChromeClient!!.onJsConfirm(view, view.page, "dialog-private-payload", BrowserDialogTestResults.alert())
        val c = context(); val result = start(c, operation = "screenshot")
        approve(c)
        val output = finish(result)
        assertTrue("Unexpected output: $output; bounds=${view.width}x${view.height}", output.startsWith("DENIED"))
        assertFalse(output.contains("dialog-private-payload"))
        assertEquals(0, view.evaluations)
        assertNotNull(browser.pendingDialog.value)
    }

    @Test fun revisionMutationAfterToolResultRevokesOuterHookAndHistoryReceipt() {
        log("authorized-uncommitted-output")
        val c = context(); val result = start(c); approve(c)
        assertTrue(finish(result).contains("authorized-uncommitted-output"))
        permissions.replacePolicy(PermissionPolicyDocument.EMPTY)
        assertNotNull(c.browserPublication.denial())
        permissions.replacePolicy(PermissionPolicyDocument.EMPTY)
        assertNotNull(c.browserPublication.denial())
    }
}

package com.baystudio.droide.ui

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.AgentBrowserDialogs
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Controlled Android callbacks prove dialog ownership/routing; they do not execute device Chromium. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserDialogOperationTest {
    private val directory = Files.createTempDirectory("browser-dialog-").toFile()
    private val controller = AgentBrowserController(directory)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val views = mutableListOf<DialogWebView>()

    private class DialogWebView(context: Context, var page: String) : WebView(context) {
        val evaluations = mutableListOf<ValueCallback<String>>()
        var onEvaluate: (() -> Unit)? = null
        var onLoad: (() -> Unit)? = null
        var loads = 0
        var destroyed = 0
        override fun getUrl(): String = page
        override fun getTitle() = "Dialog fixture"
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.let { evaluations += it }
            onEvaluate?.invoke()
        }
        override fun loadUrl(url: String) {
            loads++
            page = url
            webViewClient.onPageStarted(this, url, null)
            onLoad?.invoke()
        }
        override fun destroy() { destroyed++; super.destroy() }
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        directory.resolve("index.html").writeText("<html><body>Ready</body></html>")
        controller.bindOpenRequest {}
    }

    @After fun release() {
        caller.cancel()
        views.forEach { if (it.destroyed == 0) it.destroy() }
        directory.deleteRecursively()
    }

    private fun surface(): DialogWebView = DialogWebView(RuntimeEnvironment.getApplication(), controller.htmlPreviewUrl("index.html"))
        .also { views += it; controller.configure(it); controller.attach(it) }

    private fun execute(operation: String, target: String? = null, text: String? = null, dialogText: String? = null,
        url: String? = null, expectedUrl: String? = null, allowLoopback: Boolean = false, reviewedOrigin: String? = null) = caller.async {
        runCatching { controller.execute(operation, target = target, text = text, dialogText = dialogText, url = url,
            expectedCurrentUrl = expectedUrl, waitMs = 10_000, allowLoopback = allowLoopback, reviewedLoopbackOrigin = reviewedOrigin) }
    }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Browser dialog operation failed to progress before the host deadline", condition())
    }

    private fun <T> completed(value: Deferred<T>): T {
        drainUntil { value.isCompleted }
        return runBlocking { value.await() }
    }

    private fun snapshot(url: String): String = JsonPrimitive(buildJsonObject { put("ok", true); put("url", url) }.toString()).toString()

    @Test fun dialogDuringJavascriptReturnsPendingWithoutTimeoutOrAutomaticReplayThenFreshReadWorks() {
        val view = surface()
        val result = BrowserDialogTestResults.alert()
        view.onEvaluate = { view.webChromeClient!!.onJsConfirm(view, view.page, "Continue?", result) }

        val output = Json.parseToJsonElement(completed(execute("click", target = "#continue")).getOrThrow()).jsonObject
        assertEquals("browser_dialog", output["kind"]!!.jsonPrimitive.content)
        assertEquals(false, output["operation_completed"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(1, view.evaluations.size)
        val id = controller.pendingDialog.value!!.id
        completed(execute("dialog_respond", target = id, text = "accept")).getOrThrow()
        assertEquals(1, BrowserDialogTestResults.state(result).confirmations)
        assertNull(controller.pendingDialog.value)
        assertTrue(caller.isActive)

        view.onEvaluate = null
        // The old renderer callback belongs to the interrupted action and is ignored.
        view.evaluations.first().onReceiveValue(JsonPrimitive("{\"ok\":true}").toString())
        val fresh = execute("read")
        drainUntil { view.evaluations.size == 2 }
        view.evaluations.last().onReceiveValue(snapshot(view.page))
        assertEquals("browser_page", Json.parseToJsonElement(completed(fresh).getOrThrow()).jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun alreadyPendingDialogMakesReadClickTypeOpenAndWaitReturnImmediatelyWithoutDispatchingPageActions() {
        val view = surface()
        val result = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsAlert(view, view.page, "Waiting for a human", result)
        val id = controller.pendingDialog.value!!.id

        for (operation in listOf("read", "click", "type", "open", "wait")) {
            val value = completed(execute(operation, target = "#field", text = "hello", url = view.page)).getOrThrow()
            val observed = Json.parseToJsonElement(value).jsonObject
            assertEquals(true, observed["pending"]!!.jsonPrimitive.booleanOrNull)
            assertEquals(id, observed["dialog"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(operation, observed["blocked_operation"]!!.jsonPrimitive.content)
        }
        assertEquals(0, view.evaluations.size)
        assertEquals(0, view.loads)
        assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
        assertEquals(0, BrowserDialogTestResults.state(result).cancellations)
    }

    @Test fun openThatTriggersDialogReturnsPendingBeforeItsLongNavigationWaitFinishes() {
        val view = surface()
        val result = BrowserDialogTestResults.alert()
        view.onLoad = { view.webChromeClient!!.onJsAlert(view, view.page, "Loaded with a dialog", result) }

        val output = completed(execute("open", url = view.page)).getOrThrow()
        assertEquals(true, Json.parseToJsonElement(output).jsonObject["pending"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(1, view.loads)
        assertEquals(0, view.evaluations.size)
    }

    @Test fun dialogObservationAndResponseDoNotWaitForThePageActionMutex() {
        val view = surface()
        val result = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsConfirm(view, view.page, "Resolve independently", result)
        val mutex = controller.javaClass.getDeclaredField("executeMutex").apply { isAccessible = true }.get(controller) as Mutex
        runBlocking { mutex.lock() }
        try {
            val observed = Json.parseToJsonElement(completed(execute("dialog")).getOrThrow()).jsonObject
            val id = observed["dialog"]!!.jsonObject["id"]!!.jsonPrimitive.content
            completed(execute("dialog_respond", target = id, text = "dismiss")).getOrThrow()
            assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
        } finally { mutex.unlock() }
    }

    @Test fun promptDefaultIsExcludedFromAgentOutputAndExplicitResponseRemainsLiteralWithoutJavascriptEvaluation() {
        val view = surface()
        val result = BrowserDialogTestResults.prompt()
        view.webChromeClient!!.onJsPrompt(view, view.page, "Enter a display name", "Human default stays private", result)
        val observed = completed(execute("dialog")).getOrThrow()
        assertFalse(observed.contains("Human default stays private"))
        val id = controller.pendingDialog.value!!.id
        assertTrue(completed(execute("dialog_respond", target = id, text = "accept")).isFailure)
        assertNotNull(controller.pendingDialog.value)

        val literal = "'); window.injected = true; //"
        val response = completed(execute("dialog_respond", target = id, text = "accept", dialogText = literal)).getOrThrow()
        assertEquals(literal, BrowserDialogTestResults.state(result).promptText)
        assertFalse(response.contains(literal))
        assertEquals(0, view.evaluations.size)
        assertEquals(1, BrowserDialogTestResults.state(result).confirmations)
    }

    @Test fun oversizedPromptResponseAndStaleIdsAreRejectedWithoutAnsweringCurrentDialog() {
        val view = surface()
        val first = BrowserDialogTestResults.prompt()
        view.webChromeClient!!.onJsPrompt(view, view.page, "First", "", first)
        val firstId = controller.pendingDialog.value!!.id
        assertTrue(completed(execute("dialog_respond", target = firstId, text = "accept", dialogText = "x".repeat(20_001))).isFailure)
        assertEquals(0, BrowserDialogTestResults.state(first).confirmations)

        val second = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsConfirm(view, view.page, "Second", second)
        val secondId = controller.pendingDialog.value!!.id
        assertNotEquals(firstId, secondId)
        assertEquals(1, BrowserDialogTestResults.state(first).cancellations)
        assertFalse(controller.respondHumanDialog(firstId, accept = true, promptText = "stale"))
        assertEquals(secondId, controller.pendingDialog.value!!.id)
        assertTrue(controller.respondHumanDialog(secondId, accept = false))
        assertFalse(controller.respondHumanDialog(secondId, accept = false))
        assertEquals(1, BrowserDialogTestResults.state(second).cancellations)
    }

    @Test fun navigationIncludingSameUrlInvalidatesDialogIdAndCancelsItsResultExactlyOnce() {
        val view = surface()
        val result = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsConfirm(view, view.page, "Before reload", result)
        val id = controller.pendingDialog.value!!.id

        view.webViewClient.onPageStarted(view, view.page, null)

        assertNull(controller.pendingDialog.value)
        assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
        assertFalse(controller.respondHumanDialog(id, accept = true))
        assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
    }

    @Test fun detachReplacementRendererDeathAndStaleCallbackCannotLeaveOrResolveZombieDialogs() {
        val first = surface()
        val firstBinding = controller.attach(first)
        val result = BrowserDialogTestResults.alert()
        first.webChromeClient!!.onJsConfirm(first, first.page, "First", result)
        controller.detach(firstBinding, first, preserveState = false)
        assertEquals(1, BrowserDialogTestResults.state(result).cancellations)

        val second = surface()
        val secondResult = BrowserDialogTestResults.alert()
        second.webChromeClient!!.onJsAlert(second, second.page, "Second", secondResult)
        val third = surface()
        assertEquals(1, BrowserDialogTestResults.state(secondResult).cancellations)
        val staleResult = BrowserDialogTestResults.alert()
        first.webChromeClient!!.onJsAlert(first, first.page, "Stale callback", staleResult)
        assertEquals(1, BrowserDialogTestResults.state(staleResult).cancellations)
        assertNull(controller.pendingDialog.value)

        val thirdResult = BrowserDialogTestResults.prompt()
        third.webChromeClient!!.onJsPrompt(third, third.page, "Third", "", thirdResult)
        assertTrue(third.webViewClient.onRenderProcessGone(third, object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        }))
        assertEquals(1, BrowserDialogTestResults.state(thirdResult).cancellations)
        assertNull(controller.pendingDialog.value)
    }

    @Test fun failedNativeConfirmOrCancelDoesNotReportSuccessAndStillClearsItsDialogExactlyOnce() {
        val view = surface()
        for (resolution in listOf("accept", "dismiss")) {
            val result = BrowserDialogTestResults.alert()
            val state = BrowserDialogTestResults.state(result).apply { failCompletion = true }
            view.webChromeClient!!.onJsConfirm(view, view.page, "Fault injection", result)
            val id = controller.pendingDialog.value!!.id

            assertTrue(completed(execute("dialog_respond", target = id, text = resolution)).isFailure)
            assertNull(controller.pendingDialog.value)
            assertEquals(1, state.confirmations + state.cancellations)
            val status = Json.parseToJsonElement(completed(execute("status")).getOrThrow()).jsonObject
            assertTrue(status["last_error"]!!.jsonPrimitive.content.contains("could not be completed"))
        }
    }

    @Test fun staleReviewedPageCannotBeUsedToAnswerDialogAndMessageOutputIsBounded() {
        val view = surface()
        val result = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsAlert(view, view.page, "\u0001".repeat(100_000), result)
        val id = controller.pendingDialog.value!!.id
        val observed = completed(execute("dialog")).getOrThrow()
        assertTrue(observed.length < 18_000)
        assertEquals(true, Json.parseToJsonElement(observed).jsonObject["dialog"]!!.jsonObject["message_truncated"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(completed(execute("dialog_respond", target = id, text = "accept", expectedUrl = "https://unrelated.example.test/")).isFailure)
        assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
        assertNotNull(controller.pendingDialog.value)
    }

    @Test fun iframeRequestingSourceIsObservedSeparatelyWhileDialogOwnershipAndResponseStayBoundToTopPage() {
        val view = surface()
        val iframeSource = "https://frame.example.test/dialog?trace=fixture#fragment"
        val result = BrowserDialogTestResults.prompt()
        view.webChromeClient!!.onJsPrompt(view, iframeSource, "Iframe asks for a display name", "", result)

        val pending = controller.pendingDialog.value!!
        assertEquals(view.page, pending.url)
        assertEquals(iframeSource, pending.sourceUrl)
        val observed = Json.parseToJsonElement(completed(execute("dialog", expectedUrl = view.page)).getOrThrow()).jsonObject["dialog"]!!.jsonObject
        assertEquals(view.page, observed["url"]!!.jsonPrimitive.content)
        assertEquals(iframeSource, observed["source_url"]!!.jsonPrimitive.content)
        assertEquals(false, observed["source_unknown"]!!.jsonPrimitive.booleanOrNull)

        assertTrue(completed(execute("dialog_respond", target = pending.id, text = "accept", dialogText = "Fixture",
            expectedUrl = iframeSource)).isFailure)
        assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
        assertNotNull(controller.pendingDialog.value)
        completed(execute("dialog_respond", target = pending.id, text = "accept", dialogText = "Fixture", expectedUrl = view.page)).getOrThrow()
        assertEquals(1, BrowserDialogTestResults.state(result).confirmations)
    }

    @Test fun invalidOrOversizedRequestingUrlIsExplicitlyUnknownWithoutBorrowingTopPageOriginOrUrlPrefix() {
        val view = surface()
        for (source in listOf("not a URL", "https://frame.example.test/" + "x".repeat(8_192))) {
            val result = BrowserDialogTestResults.alert()
            view.webChromeClient!!.onJsAlert(view, source, "Unknown requesting frame", result)
            val pending = controller.pendingDialog.value!!
            assertEquals(view.page, pending.url)
            assertNull(pending.sourceUrl)
            val observed = Json.parseToJsonElement(completed(execute("dialog")).getOrThrow()).jsonObject["dialog"]!!.jsonObject
            assertEquals(true, observed["source_unknown"]!!.jsonPrimitive.booleanOrNull)
            assertNull(observed["source_url"]!!.jsonPrimitive.contentOrNull)
            assertTrue(controller.respondHumanDialog(pending.id, accept = false))
            assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
        }
    }

    @Test fun longValidRequestingUrlRemainsExactInHumanStateAndIsExplicitlyOmittedFromBoundedAgentOutput() {
        val view = surface().apply { page = "https://top.example.test/" + "a".repeat(8_100) }
        val source = "https://frame.example.test/" + "b".repeat(8_100)
        val result = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsAlert(view, source, "\u0001".repeat(4_000), result)
        val pending = controller.pendingDialog.value!!

        assertEquals(source, pending.sourceUrl)
        val encoded = AgentBrowserDialogs().observation(pending, "read")
        val description = Json.parseToJsonElement(encoded).jsonObject["dialog"]!!.jsonObject
        assertTrue(encoded.length <= 18_000)
        assertEquals(view.page, description["url"]!!.jsonPrimitive.content)
        assertEquals("https://frame.example.test", description["source_origin"]!!.jsonPrimitive.content)
        assertEquals(true, description["source_url_omitted"]!!.jsonPrimitive.booleanOrNull)
        assertNull(description["source_url"]!!.jsonPrimitive.contentOrNull)
        assertTrue(controller.respondHumanDialog(pending.id, accept = false))
    }

    @Test fun reviewedAgentLocalOriginRestrictsNavigationWhileCrossPortApiResourcesFromApprovedFrontendStillWork() {
        val view = surface().apply { page = "http://localhost:3000/app" }
        view.webViewClient.onPageStarted(view, view.page, null)
        completed(execute("console", allowLoopback = true, reviewedOrigin = "http://localhost:3000")).getOrThrow()

        assertNull(view.webViewClient.shouldInterceptRequest(view, request("http://localhost:8000/api", false)))
        assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("http://localhost:3001/app", true)))
        assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request("http://localhost:3001/app", true))?.statusCode)
        assertTrue(completed(execute("console", allowLoopback = true, reviewedOrigin = "http://localhost:9999")).isFailure)

        // A redirect to an unreviewed origin cannot retain the previous page's trust.
        view.page = "http://localhost:3001/app"
        view.webViewClient.onPageStarted(view, view.page, null)
        assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request("http://localhost:8000/api", false))?.statusCode)
        assertEquals(403, view.webViewClient.shouldInterceptRequest(view, request(view.page, true))?.statusCode)
    }

    private fun request(raw: String, mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(raw)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}

package com.baystudio.droide.ui

import android.app.Application
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebHistoryItem
import android.webkit.DroideTestWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.view.View
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.core.BrowserSurfaceChangedException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserOperationLifetimeTest {
    private val directory = Files.createTempDirectory("browser-lifetime-").toFile()
    private val controller = AgentBrowserController(directory)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val views = mutableListOf<PendingWebView>()
    private val activities = mutableListOf<ActivityController<Activity>>()

    private class PendingWebView(context: Context, var page: String) : WebView(context) {
        val pending = mutableListOf<ValueCallback<String>>()
        var destroyed = 0
        var restoreAction: (() -> Unit)? = null
        var drawnCanvas: Canvas? = null
        var onUrlRead: (() -> Unit)? = null
        var saves = 0
        var restores = 0
        var saveHistory = false
        var saveFailure: Throwable? = null
        override fun getUrl(): String { onUrlRead?.invoke(); return page }
        override fun getTitle(): String = "Test page"
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.let { pending += it }
        }
        override fun restoreState(state: Bundle): WebBackForwardList? {
            restores++
            state.getString("droide.test.history")?.let { page = it }
            restoreAction?.invoke()
            return if (state.containsKey("droide.test.history")) HistoryEntry() else null
        }
        override fun saveState(state: Bundle): WebBackForwardList? {
            saves++
            saveFailure?.let { throw it }
            if (!saveHistory) return super.saveState(state)
            state.putString("droide.test.history", page)
            return HistoryEntry()
        }
        override fun draw(canvas: Canvas) { drawnCanvas = canvas; canvas.drawColor(Color.BLUE) }

        fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean =
            org.robolectric.shadow.api.Shadow.directlyOn(this, View::class.java, "setFrame",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, left),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, top),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, right),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, bottom))
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Robolectric does not run Chromium's provider measurement. Measure this controlled renderer
            // fixture through the real View measurement contract.
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun destroy() { destroyed++; super.destroy() }
    }

    private class HistoryEntry : WebBackForwardList() {
        override fun getSize() = 1
        override fun getCurrentIndex() = 0
        override fun getCurrentItem(): WebHistoryItem? = null
        override fun getItemAtIndex(index: Int): WebHistoryItem? = null
        public override fun clone(): WebBackForwardList = this
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        directory.resolve("index.html").writeText("<html><body>Ready</body></html>")
        directory.resolve("next.html").writeText("<html><body>Next</body></html>")
        controller.bindOpenRequest {}
    }

    @After fun release() {
        caller.cancel()
        views.forEach { if (it.destroyed == 0) it.destroy() }
        activities.forEach { it.pause().stop().destroy() }
        directory.deleteRecursively()
    }

    private fun surface(path: String = "index.html", onGone: (WebView, String) -> Unit = { _, _ -> }): PendingWebView =
        PendingWebView(RuntimeEnvironment.getApplication(), controller.htmlPreviewUrl(path)).also {
            views += it
            controller.configure(it, onGone)
        }

    private fun read(allowLoopback: Boolean = false): Deferred<Result<String>> = caller.async {
        runCatching { controller.execute("read", allowLoopback = allowLoopback) }
    }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Browser operation did not progress before the test deadline", condition())
    }

    private fun <T> completed(result: Deferred<T>): T {
        drainUntil { result.isCompleted }
        return runBlocking { result.await() }
    }

    private fun snapshot(url: String): String = JsonPrimitive(buildJsonObject {
        put("ok", true)
        put("url", url)
        put("title", "Ready")
        put("form_values_included", false)
    }.toString()).toString()

    private fun status(): String = completed(caller.async { controller.execute("status") })

    @Test fun closingSurfaceCompletesPendingJavascriptAsRecoverableErrorWithoutCancellingAgentCaller() {
        val view = surface()
        val binding = controller.attach(view)
        val operation = read()
        drainUntil { view.pending.size == 1 }

        controller.detach(binding, view, preserveState = false)
        val result = completed(operation)
        assertTrue(result.exceptionOrNull() is BrowserSurfaceChangedException)
        assertTrue("Browser loss must not cancel the Agent task", caller.isActive)

        // Late callbacks cannot resurrect the canceled operation or prevent the next one.
        view.pending.single().onReceiveValue(snapshot(view.page))
        val reopened = surface()
        controller.attach(reopened)
        val next = read()
        drainUntil { reopened.pending.size == 1 }
        reopened.pending.single().onReceiveValue(snapshot(reopened.page))
        assertTrue(completed(next).getOrThrow().contains("browser_page"))
    }

    @Test fun replacementCancelsOldOperationAndStaleDetachCannotDetachTheReplacement() {
        val old = surface()
        val oldBinding = controller.attach(old)
        val operation = read()
        drainUntil { old.pending.size == 1 }

        val replacement = surface("next.html")
        controller.attach(replacement)
        controller.detach(oldBinding, old, preserveState = false)

        assertTrue(completed(operation).exceptionOrNull() is BrowserSurfaceChangedException)
        assertTrue(caller.isActive)
        val next = read()
        drainUntil { replacement.pending.size == 1 }
        replacement.pending.single().onReceiveValue(snapshot(replacement.page))
        assertTrue(completed(next).isSuccess)
        assertEquals(replacement.page, controller.currentUrl())
    }

    @Test fun rendererDeathCancelsPendingOperationImmediatelyAndAllowsFreshSurface() {
        var gone = 0
        val view = surface { _, _ -> gone++ }
        controller.attach(view)
        val operation = read()
        drainUntil { view.pending.size == 1 }

        assertTrue(view.webViewClient.onRenderProcessGone(view, crashedRenderer()))

        assertTrue(completed(operation).exceptionOrNull() is BrowserSurfaceChangedException)
        assertEquals(1, gone)
        assertEquals(1, view.destroyed)
        assertTrue(caller.isActive)
        val replacement = surface("next.html")
        controller.attach(replacement)
        assertTrue(Json.parseToJsonElement(status()).jsonObject["attached"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test fun externalCancellationStillPropagatesAndLateJavascriptCallbackIsIgnored() {
        val view = surface()
        controller.attach(view)
        val operation = caller.async { controller.execute("read") }
        drainUntil { view.pending.size == 1 }

        operation.cancel(CancellationException("User stopped Agent"))
        drainUntil { operation.isCompleted }
        val failure = runBlocking { runCatching { operation.await() }.exceptionOrNull() }
        assertTrue(failure is CancellationException)
        assertFalse(failure is BrowserSurfaceChangedException)
        view.pending.single().onReceiveValue(snapshot(view.page))
        assertTrue(caller.isActive)
        assertTrue(Json.parseToJsonElement(status()).jsonObject["attached"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test fun screenshotCancelledAfterFileCreationDeletesItsOwnedFileAndRecyclesTheBitmap() {
        val view = surface()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().also { activities += it }
        val host = FrameLayout(activity.get()).apply {
            addView(view, FrameLayout.LayoutParams(100, 80))
        }
        activity.get().setContentView(host)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            activity.get().window.decorView.parent, "handleAppVisibility",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
        )
        shadowOf(Looper.getMainLooper()).idle()
        host.apply {
            measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            layout(0, 0, 100, 80)
        }
        view.forceLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 100, 80)
        // ShadowWebView's provider does not perform Chromium layout, and its setLayoutParams shadow
        // skips requestLayout.
        view.left = 0
        view.top = 0
        view.right = 100
        view.bottom = 80
        assertEquals(100, view.width)
        assertEquals(80, view.height)
        val binding = controller.attach(view)
        shadowOf(Looper.getMainLooper()).idle()
        host.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 100, 80)
        assertTrue("Capture fixture must be attached", view.isAttachedToWindow)
        assertTrue("Capture fixture must be shown", view.isShown)
        assertTrue("Capture fixture must have completed layout", view.isLaidOut && !view.isLayoutRequested)
        val output = com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory).resolve("scratch/browser")
        var cancelledAfterFile = false
        // The final URL check runs only after compression finished. Cancel at that boundary, while a
        // real image exists and the operation has not committed.
        view.onUrlRead = {
            if (!cancelledAfterFile && output.walkTopDown().any { it.isFile && it.extension == "png" && it.length() > 0 }) {
                cancelledAfterFile = true
                controller.detach(binding, view, preserveState = false)
            }
        }
        val operation = caller.async { runCatching { controller.execute("screenshot") } }
        val result = completed(operation)
        assertNotNull("Viewport must actually be captured; size=${view.width}x${view.height}; failure=${result.exceptionOrNull()}", view.drawnCanvas)
        assertTrue("Capture must create a real file before cancellation; result=$result", cancelledAfterFile)
        assertTrue(result.exceptionOrNull() is BrowserSurfaceChangedException)
        assertTrue("Canceled capture must not leave a partial image", output.walkTopDown().none { it.isFile })
        val canvasShadow = shadowOf(requireNotNull(view.drawnCanvas))
        val bitmap = canvasShadow.javaClass.getDeclaredField("targetBitmap").apply { isAccessible = true }
            .get(canvasShadow) as Bitmap
        assertTrue("The actual captured bitmap must be recycled", bitmap.isRecycled)
        host.removeView(view)
    }

    @Test fun attachBeforeOldDetachSavesLiveHistoryOnceAndRestoresTheReplacement() {
        val old = surface().apply { saveHistory = true }
        val oldBinding = controller.attach(old)
        val replacement = surface("next.html")

        controller.attach(replacement)
        controller.detach(oldBinding, old, preserveState = true)

        assertEquals(1, old.saves)
        assertEquals(1, replacement.restores)
        assertEquals(old.page, replacement.page)
        assertEquals(old.page, controller.currentUrl())
        assertEquals(true, Json.parseToJsonElement(status()).jsonObject["attached"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test fun failedOldHistorySaveStillAllowsUsableReplacementAndDoesNotResaveStaleView() {
        for (failure in listOf(IllegalStateException("Old renderer history unavailable"), UnsatisfiedLinkError("Old renderer unavailable"))) {
            val old = surface().apply { saveFailure = failure }
            val oldBinding = controller.attach(old)
            val replacement = surface("next.html")

            controller.attach(replacement)
            controller.detach(oldBinding, old, preserveState = true)

            assertEquals(1, old.saves)
            assertEquals(0, replacement.restores)
            val next = read()
            drainUntil { replacement.pending.size == 1 }
            replacement.pending.single().onReceiveValue(snapshot(replacement.page))
            assertTrue(completed(next).isSuccess)
        }
    }

    @Test fun approvedLocalhostHistoryKeepsItsOriginApprovalAfterDetachAndRestore() {
        val old = surface().apply { page = "http://localhost:8080/app"; saveHistory = true }
        val oldBinding = controller.attach(old)
        authorizeLocalRead(old)

        controller.detach(oldBinding, old, preserveState = true)
        val replacement = surface()
        controller.attach(replacement)

        assertRestoredLocalOrigin(replacement)
    }

    @Test fun approvedLocalhostHistoryKeepsItsOriginApprovalWhenReplacementAttachesFirst() {
        val old = surface().apply { page = "http://localhost:8080/app"; saveHistory = true }
        val oldBinding = controller.attach(old)
        authorizeLocalRead(old)

        val replacement = surface()
        controller.attach(replacement)
        controller.detach(oldBinding, old, preserveState = true)

        assertEquals(1, old.saves)
        assertRestoredLocalOrigin(replacement)
    }

    @Test fun publicHistoryCannotRestoreImplicitLoopbackAccessEvenIfOldUiOperationAllowedLocalNavigation() {
        val old = surface().apply { page = "https://public.example.test/app"; saveHistory = true }
        val binding = controller.attach(old)
        // The human browser UI passes allowLoopback=true. A public-page operation is not a grant to
        // carry localhost access into its saved history.
        completed(caller.async { controller.execute("status", allowLoopback = true) })
        controller.detach(binding, old, preserveState = true)
        val replacement = surface()
        controller.attach(replacement)

        assertEquals(old.page, replacement.page)
        for (mainFrame in listOf(false, true)) {
            assertEquals(403, replacement.webViewClient.shouldInterceptRequest(replacement,
                request("http://localhost:8080/private", mainFrame))?.statusCode)
        }
    }

    private fun authorizeLocalRead(view: PendingWebView) {
        val operation = read(allowLoopback = true)
        drainUntil { view.pending.size == 1 }
        view.pending.single().onReceiveValue(snapshot(view.page))
        assertTrue(completed(operation).isSuccess)
        assertNull("An explicitly approved frontend must retain cross-port API support",
            view.webViewClient.shouldInterceptRequest(view, request("http://localhost:9090/api", false)))
    }

    private fun assertRestoredLocalOrigin(view: PendingWebView) {
        assertEquals("http://localhost:8080/app", view.page)
        assertNull("Approved same-origin assets must load after restoration",
            view.webViewClient.shouldInterceptRequest(view, request("http://localhost:8080/assets/app.js", false)))
        assertNull("A restored approved frontend must retain cross-port API support",
            view.webViewClient.shouldInterceptRequest(view, request("http://localhost:9090/api", false)))
        assertNull("Approved local pages can retain API resources on a local alias",
            view.webViewClient.shouldInterceptRequest(view, request("http://127.0.0.1:8080/api", false)))
        assertEquals("Restoration must not grant top-level navigation to a different local origin", 403,
            view.webViewClient.shouldInterceptRequest(view, request("http://localhost:9090/private", true))?.statusCode)
        assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("http://localhost:9090/private", true)))
    }

    @Test fun restoredLocalApprovalDoesNotFollowPublicNavigationAndNewPageTitleStartsEmpty() {
        val old = surface().apply { page = "http://localhost:8080/app"; saveHistory = true }
        val binding = controller.attach(old)
        authorizeLocalRead(old)
        controller.detach(binding, old, preserveState = true)
        val replacement = surface()
        controller.attach(replacement)
        assertRestoredLocalOrigin(replacement)

        replacement.page = "https://public.example.test/app"
        replacement.webViewClient.onPageStarted(replacement, replacement.page, null)

        for (mainFrame in listOf(false, true)) {
            assertEquals(403, replacement.webViewClient.shouldInterceptRequest(replacement,
                request("http://localhost:8080/private", mainFrame))?.statusCode)
        }
        assertEquals("", Json.parseToJsonElement(status()).jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun staleNavigationErrorAndRendererCallbacksCannotCorruptOrCloseNewSurface() {
        var oldGone = 0
        val old = surface { _, _ -> oldGone++ }
        controller.attach(old)
        val replacement = surface("next.html")
        controller.attach(replacement)

        old.webViewClient.onPageStarted(old, "https://stale.example.test/", null)
        old.webViewClient.onPageFinished(old, "https://stale.example.test/")
        old.webViewClient.onReceivedError(old, request(old.page), DroideTestWebResourceError(-7, "Stale connection error"))
        old.webViewClient.onReceivedHttpError(old, request(old.page), WebResourceResponse(
            "text/plain", "utf-8", 503, "Unavailable", emptyMap(), null))
        assertTrue(old.webViewClient.onRenderProcessGone(old, crashedRenderer()))

        assertEquals(0, oldGone)
        assertEquals(1, old.destroyed)
        assertEquals(0, replacement.destroyed)
        assertEquals(replacement.page, controller.currentUrl())
        val current = Json.parseToJsonElement(status()).jsonObject
        assertEquals(true, current["attached"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, current["loading"]!!.jsonPrimitive.booleanOrNull)
        assertFalse(current.containsKey("last_error"))
    }

    @Test fun restoredSurfaceCallbacksAreCurrentAndFailedRestoreDoesNotLeaveAnActiveLease() {
        // A saved-history bundle is enough to exercise callbacks synchronously fired by restoreState
        // without asking Robolectric to emulate Chromium's history renderer.
        val state = controller.javaClass.getDeclaredField("navigationState").apply { isAccessible = true }
        state.set(controller, Bundle())
        val restored = surface()
        restored.restoreAction = { restored.webViewClient.onPageStarted(restored, restored.page, null) }
        val restoredBinding = controller.attach(restored)
        assertEquals(true, Json.parseToJsonElement(status()).jsonObject["loading"]!!.jsonPrimitive.booleanOrNull)

        controller.detach(restoredBinding, restored, preserveState = false)
        state.set(controller, Bundle())
        val broken = surface("next.html")
        broken.restoreAction = { throw IllegalStateException("History could not be restored") }
        assertTrue(runCatching { controller.attach(broken) }.exceptionOrNull() is IllegalStateException)
        val lease = controller.javaClass.getDeclaredField("viewLease").apply { isAccessible = true }
        assertNull("Failed attach must invalidate the newly published lease", lease.get(controller))

        state.set(controller, null)
        val healthy = surface("next.html")
        controller.attach(healthy)
        assertEquals(true, Json.parseToJsonElement(status()).jsonObject["attached"]!!.jsonPrimitive.booleanOrNull)
    }

    private fun crashedRenderer() = object : RenderProcessGoneDetail() {
        override fun didCrash() = true
        override fun rendererPriorityAtExit() = 0
    }

    private fun request(raw: String, mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(raw)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}

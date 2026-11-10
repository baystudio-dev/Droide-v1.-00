package com.baystudio.droide.ui

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Canvas
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebView
import android.view.View
import android.widget.FrameLayout
import org.robolectric.Robolectric
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.BrowserApplicationVisibility
import com.baystudio.droide.core.BrowserSurfaceChangedException
import java.nio.file.Files
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import org.robolectric.annotation.GraphicsMode

/** Lifecycle/callback contracts only: this does not measure real Chromium CPU or Android 16 RAM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserMobileLifecycleTest {
    private val root = Files.createTempDirectory("browser-mobile-").toFile()
    private val controller = AgentBrowserController(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val views = mutableListOf<TrackedView>()
    private var opens = 0

    private class TrackedView(context: Context, val page: String) : WebView(context) {
        val pending = mutableListOf<ValueCallback<String>>()
        var paused = 0
        var resumed = 0
        var destroyed = 0
        var loads = 0
        var draws = 0
        var requestedPriority = -1
        var waived = false
        var failPause = false
        var failResume = false
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun getUrl() = page
        override fun getTitle() = "Mobile fixture"
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.let { pending += it }
        }
        override fun onPause() { paused++; if (failPause) error("Pause unavailable"); super.onPause() }
        override fun onResume() { resumed++; if (failResume) error("Resume unavailable"); super.onResume() }
        override fun loadUrl(url: String) { loads++; super.loadUrl(url) }
        override fun draw(canvas: Canvas) { draws++; super.draw(canvas) }
        override fun destroy() { destroyed++; super.destroy() }
        override fun setRendererPriorityPolicy(priority: Int, waivedWhenNotVisible: Boolean) {
            requestedPriority = priority
            waived = waivedWhenNotVisible
            super.setRendererPriorityPolicy(priority, waivedWhenNotVisible)
        }
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        root.resolve("index.html").writeText("<html><body>Ready</body></html>")
        controller.bindOpenRequest { opens++ }
    }

    @After fun cleanup() {
        scope.cancel()
        views.filter { it.destroyed == 0 }.forEach { it.destroy() }
        root.deleteRecursively()
    }

    private fun surface(onGone: (WebView, String) -> Unit = { _, _ -> }): Pair<TrackedView, AgentBrowserController.Binding> {
        val view = TrackedView(RuntimeEnvironment.getApplication(), controller.htmlPreviewUrl("index.html"))
        views += view
        controller.configure(view, onGone)
        return view to controller.attach(view)
    }

    private fun execute(operation: String, fullPage: Boolean = false) = scope.async {
        runCatching { controller.execute(operation, fullPage = fullPage) }
    }

    @Test fun exitAfterHiddenRendererReclamationDoesNotWaitForADisposedPane() = runBlocking {
        val (view, _) = surface()
        controller.setApplicationVisible(false)
        controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(1, view.destroyed)
        controller.requestStopForExit()
        kotlinx.coroutines.withTimeout(1_000) { controller.awaitNativeSurfacesReleasedForExit() }
    }

    @Test fun exitReleasesItsOriginalSurfaceWithoutAComposeFrame() = runBlocking {
        val view = TrackedView(RuntimeEnvironment.getApplication(), controller.htmlPreviewUrl("index.html"))
        views += view
        var releases = 0
        controller.configure(view, onExitRelease = {
            releases++
            view.destroy()
            controller.reportNativeSurfaceReleased(view, null)
        })
        controller.attach(view)
        controller.requestStopForExit()
        controller.releaseNativeSurfacesForExit()
        controller.releaseNativeSurfacesForExit()
        kotlinx.coroutines.withTimeout(1_000) { controller.awaitNativeSurfacesReleasedForExit() }
        assertEquals(1, releases)
        assertEquals(1, view.destroyed)
    }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Browser did not complete its bounded test operation", condition())
    }

    private fun <T> completed(result: Deferred<T>): T {
        drainUntil { result.isCompleted }
        return runBlocking { result.await() }
    }

    private fun reply(view: TrackedView) {
        view.pending.last().onReceiveValue(JsonPrimitive(buildJsonObject {
            put("ok", true); put("url", view.page)
        }.toString()).toString())
    }

    @Test fun backgroundCancelsPendingReadWithoutCancellingAgentAndResumeNeverReplaysIt() {
        val (view, _) = surface()
        val first = execute("read")
        drainUntil { view.pending.size == 1 }
        controller.setApplicationVisible(false)
        assertTrue(completed(first).exceptionOrNull() is BrowserSurfaceChangedException)
        assertTrue(scope.isActive)
        assertEquals(1, view.paused)
        controller.setApplicationVisible(false)
        assertEquals("Duplicate background callbacks must be idempotent", 1, view.paused)
        reply(view)
        controller.setApplicationVisible(true)
        controller.setApplicationVisible(true)
        assertEquals(1, view.resumed)
        assertEquals(0, view.loads)
        assertEquals("A cancelled read must not replay on return", 1, view.pending.size)
        val next = execute("read")
        drainUntil { view.pending.size == 2 }
        reply(view)
        assertTrue(completed(next).getOrThrow().contains("browser_page"))
    }

    @Test fun backgroundBrowserUseFailsBeforeOpeningUiEvenWithoutAnAttachedPane() {
        controller.setApplicationVisible(false)
        val result = completed(execute("open"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("visible"))
        assertEquals(0, opens)
        assertTrue(views.isEmpty())
        val status = Json.parseToJsonElement(completed(execute("status")).getOrThrow()).jsonObject
        assertEquals(false, status["application_visible"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(true, status["paused"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, status["ready_for_actions"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, status["attached"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(0, opens)
    }

    @Test fun queuedMutationFromBeforeBackgroundCannotRunWhenUserReturns() {
        val (view, _) = surface()
        val first = execute("read")
        drainUntil { view.pending.size == 1 }
        val queued = scope.async { runCatching { controller.execute("click", target = "#submit") } }
        controller.setApplicationVisible(false)
        controller.setApplicationVisible(true)
        assertTrue(completed(first).isFailure)
        assertTrue(completed(queued).isFailure)
        assertEquals("Neither original read nor queued click may be replayed", 1, view.pending.size)
        assertEquals(0, view.loads)
    }

    @Test fun pendingUiDispatchCannotRunMutationAfterFullBackgroundAndForegroundCycle() {
        val (view, _) = surface()
        val pending = scope.async { runCatching { controller.execute("click", target = "#submit") } }
        // requestSurface is posted but the main looper has not dispatched it yet.
        controller.setApplicationVisible(false)
        controller.setApplicationVisible(true)
        assertTrue(completed(pending).isFailure)
        assertEquals(0, view.pending.size)
        assertEquals(0, opens)
    }

    @Test fun operationWaitingForNewSurfaceCannotGainAFreshLeaseAfterBackgroundCycle() {
        val pending = scope.async { runCatching { controller.execute("click", target = "#submit") } }
        drainUntil { opens == 1 }
        controller.setApplicationVisible(false)
        controller.setApplicationVisible(true)
        val (view, _) = surface()
        assertTrue(completed(pending).isFailure)
        assertEquals(0, view.pending.size)
    }

    @Test fun pendingDialogResponseCannotCrossBackgroundCycleOrAnswerRetainedPrompt() {
        val (view, _) = surface()
        val native = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsConfirm(view, view.page, "Continue?", native)
        val id = requireNotNull(controller.pendingDialog.value).id
        val pending = scope.async {
            runCatching { controller.execute("dialog_respond", target = id, text = "accept") }
        }
        controller.setApplicationVisible(false)
        controller.setApplicationVisible(true)
        assertTrue(completed(pending).isFailure)
        assertEquals(id, controller.pendingDialog.value!!.id)
        assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
        assertEquals(0, BrowserDialogTestResults.state(native).cancellations)
    }

    @Test fun transientNativeLifecycleFailureCanBeRetriedWithoutReplayingNavigation() {
        val (view, _) = surface()
        view.failPause = true
        controller.setApplicationVisible(false)
        view.failPause = false
        controller.setApplicationVisible(false)
        assertEquals(2, view.paused)
        view.failResume = true
        controller.setApplicationVisible(true)
        view.failResume = false
        controller.setApplicationVisible(true)
        assertEquals(2, view.resumed)
        assertFalse(completed(execute("status")).getOrThrow().contains("Browser lifecycle failed"))
        assertEquals(0, view.loads)
    }

    @Test fun lateLifecycleCallbackCannotPauseReplacementOrResumeAnAppStillInBackground() {
        val (old, binding) = surface()
        val (replacement, currentBinding) = surface()
        controller.setSurfaceStarted(binding, old, false)
        assertEquals(0, replacement.paused)
        controller.setApplicationVisible(false)
        controller.setSurfaceStarted(currentBinding, replacement, true)
        assertEquals(1, replacement.paused)
        assertEquals(0, replacement.resumed)
        controller.setApplicationVisible(true)
        assertEquals(1, replacement.resumed)
    }

    @Test fun ordinaryBackgroundRetainsPendingWebsitePromptAndDocumentIdentity() {
        val (view, _) = surface()
        val result = BrowserDialogTestResults.prompt()
        view.webChromeClient!!.onJsPrompt(view, view.page, "Name?", "Draft", result)
        val dialog = requireNotNull(controller.pendingDialog.value)
        controller.setApplicationVisible(false)
        assertEquals(dialog, controller.pendingDialog.value)
        assertEquals(0, BrowserDialogTestResults.state(result).cancellations)
        controller.setApplicationVisible(true)
        assertTrue(controller.respondHumanDialog(dialog.id, true, "My saved draft"))
        assertEquals(1, BrowserDialogTestResults.state(result).confirmations)
        assertEquals(view.page, controller.currentUrl())
        assertEquals(0, view.loads)
        assertEquals(0, view.destroyed)
    }

    @Test fun criticalMemoryKeepsVisiblePageAliveButRejectsNewScreenshotBeforeDrawing() {
        val (view, _) = surface()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val host = FrameLayout(activity.get())
            host.addView(view, FrameLayout.LayoutParams(120, 100))
            activity.get().setContentView(host)
            shadowOf(Looper.getMainLooper()).idle()
            val width = View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
            host.measure(width, height)
            host.layout(0, 0, 120, 100)
            view.forceLayout()
            view.measure(width, height)
            view.layout(0, 0, 120, 100)
            // The WebView shadow does not run Chromium layout; set real View bounds.
            view.left = 0
            view.top = 0
            view.right = 120
            view.bottom = 100
            assertTrue(view.isAttachedToWindow && view.isShown && view.isLaidOut)
            assertFalse(view.isLayoutRequested)
            assertEquals(120, view.width)
            assertEquals(100, view.height)
            controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            val result = completed(execute("screenshot"))
            assertTrue("Capture failure: ${result.exceptionOrNull()}; bounds=${view.width}x${view.height}", result.exceptionOrNull()!!.message!!.contains("memory pressure"))
            assertEquals(0, view.draws)
            assertEquals(0, view.destroyed)
            assertTrue(completed(execute("status")).isSuccess)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun pressureClearsConsoleAndOnlyCriticalBackgroundPressureReleasesRendererOnce() {
        var releases = 0
        var releaseMessage = ""
        val (view, _) = surface { _, message -> releases++; releaseMessage = message }
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage("transient", view.page, 1, ConsoleMessage.MessageLevel.LOG))
        controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(1, Json.parseToJsonElement(completed(execute("console")).getOrThrow()).jsonObject["lines"]!!.jsonArray.size)
        controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue(Json.parseToJsonElement(completed(execute("console")).getOrThrow()).jsonObject["lines"]!!.jsonArray.isEmpty())
        assertEquals(0, view.destroyed)
        controller.setApplicationVisible(false)
        controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(0, view.destroyed)
        controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(1, view.destroyed)
        assertEquals(1, releases)
        assertTrue(releaseMessage.contains("unsaved page input"))
        controller.setApplicationVisible(true)
        assertEquals(0, view.loads)
        assertEquals(0, view.resumed)
        assertEquals(view.page, controller.currentUrl())
    }

    @Test fun rendererIsBoundWhileVisibleAndMayBeReclaimedWhenHidden() {
        val (view, _) = surface()
        assertEquals(WebView.RENDERER_PRIORITY_BOUND, view.requestedPriority)
        assertTrue(view.waived)
    }

    @Test fun visibilityTrackerHandlesOverlappingActivitiesAndDoesNotTreatPauseAsHidden() {
        val events = mutableListOf<Boolean>()
        val visibility = BrowserApplicationVisibility { events += it }
        val first = Activity()
        val replacement = Activity()
        visibility.onActivityStarted(first)
        visibility.onActivityPaused(first)
        visibility.onActivityStarted(replacement)
        visibility.onActivityStopped(first)
        visibility.onActivityDestroyed(first)
        assertEquals(listOf(true), events)
        assertTrue(visibility.isVisible)
        visibility.onActivityStopped(replacement)
        visibility.onActivityDestroyed(replacement)
        assertEquals(listOf(true, false), events)
        assertFalse(visibility.isVisible)
    }
}

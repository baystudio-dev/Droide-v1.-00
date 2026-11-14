package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Canvas
import android.os.Looper
import android.os.Handler
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.FrameLayout
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.core.BrowserSurfaceChangedException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Controlled callbacks exercise retention ownership, not actual Chromium rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserRetentionOperationTest {
    private val root = Files.createTempDirectory("browser-retention-").toFile()
    private val controller = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val views = mutableListOf<RetainedView>()
    private var opens = 0

    private class RetainedView(context: Context, val page: String) : WebView(context) {
        val evaluations = mutableListOf<ValueCallback<String>>()
        var destroyed = 0
        var pauses = 0
        var draws = 0
        var onUrlRead: (() -> Unit)? = null
        override fun getUrl(): String { onUrlRead?.invoke(); return page }
        override fun getTitle() = "Retained fixture"
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.let { evaluations += it }
        }
        override fun onPause() { pauses++; super.onPause() }
        override fun draw(canvas: Canvas) { draws++; super.draw(canvas) }
        override fun destroy() { destroyed++; super.destroy() }
        // Override the hidden runtime frame hook through the real Android View contract.
        fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean =
            org.robolectric.shadow.api.Shadow.directlyOn(this, View::class.java, "setFrame",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, left),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, top),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, right),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, bottom))
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        root.resolve("index.html").writeText("<html><body>Ready</body></html>")
        controller.bindOpenRequest { opens++ }
    }

    @After fun cleanup() {
        caller.cancel()
        views.filter { it.destroyed == 0 }.forEach { it.destroy() }
        activity.pause().stop().destroy()
        root.deleteRecursively()
    }

    private fun surface(): Pair<RetainedView, AgentBrowserController.Binding> {
        val view = RetainedView(activity.get(), controller.htmlPreviewUrl("index.html"))
        views += view
        val host = FrameLayout(activity.get())
        activity.get().setContentView(host)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            activity.get().window.decorView.parent, "handleAppVisibility",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
        )
        host.addView(view, FrameLayout.LayoutParams(320, 480))
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 320, 480)
        controller.configure(view)
        val binding = controller.attach(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Fixture renderer must be attached to the visible Activity", view.isAttachedToWindow)
        assertEquals(View.VISIBLE, view.windowVisibility)
        return view to binding
    }

    private fun hide(view: RetainedView, binding: AgentBrowserController.Binding) {
        view.visibility = View.INVISIBLE
        controller.setSurfacePresented(binding, view, false)
    }

    private fun execute(operation: String) = caller.async {
        runCatching { controller.execute(operation, target = "#submit",
            text = when (operation) { "type" -> "draft"; "scroll" -> "down"; else -> null }) }
    }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Retention fixture failed to reach its expected bounded state", condition())
    }

    private fun <T> completed(result: Deferred<T>): T {
        drainUntil { result.isCompleted }
        return runBlocking { result.await() }
    }

    private fun reply(view: RetainedView, index: Int = view.evaluations.lastIndex) {
        view.evaluations[index].onReceiveValue(JsonPrimitive(buildJsonObject {
            put("ok", true); put("url", view.page)
        }.toString()).toString())
    }

    @Test fun defaultOffStatusDoesNotCreateOrCountBrowserWork() {
        assertFalse(controller.retention.value.keepActive)
        assertFalse(controller.retention.value.attached)
        assertTrue(completed(execute("status")).getOrThrow().contains("\"keep_active\":false"))
        assertEquals(0, controller.retention.value.activeOperations)
        assertEquals(0, opens)
    }

    @Test fun hiddenRetainedDomReadKeepsItsViewWithoutOpeningPanelsOrPausing() {
        val (view, binding) = surface()
        assertTrue(controller.setKeepActive(true))
        hide(view, binding)
        val read = execute("read")
        drainUntil { view.evaluations.size == 1 }
        assertEquals(0, opens)
        assertEquals(0, view.pauses)
        assertEquals(0, view.destroyed)
        assertEquals(1, controller.retention.value.activeOperations)
        reply(view)
        assertTrue(completed(read).getOrThrow().contains("browser_page"))
        assertEquals(0, controller.retention.value.activeOperations)
        assertFalse(controller.retention.value.presented)
        assertTrue(controller.retention.value.attached)
    }

    @Test fun busyOffWithoutConfirmationLeavesRunningReadAndChoiceUntouched() {
        val (view, _) = surface()
        controller.setKeepActive(true)
        val read = execute("read")
        drainUntil { view.evaluations.size == 1 }
        assertFalse(controller.setKeepActive(false))
        assertTrue(controller.retention.value.keepActive)
        assertFalse(controller.retention.value.stopping)
        assertFalse(read.isCompleted)
        reply(view)
        assertTrue(completed(read).isSuccess)
    }

    @Test fun confirmedStopCancelsReadWithoutCancellingCallerOrReplayingLateJavascript() {
        val (view, _) = surface()
        controller.setKeepActive(true)
        val read = execute("read")
        drainUntil { view.evaluations.size == 1 }
        assertTrue(controller.setKeepActive(false, cancelActive = true))
        assertTrue(completed(read).exceptionOrNull() is BrowserSurfaceChangedException)
        assertTrue(caller.isActive)
        assertFalse(controller.retention.value.keepActive)
        assertFalse(controller.retention.value.stopping)
        reply(view, 0)
        val next = execute("read")
        drainUntil { view.evaluations.size == 2 }
        reply(view)
        assertTrue(completed(next).isSuccess)
        assertEquals(0, view.destroyed)
    }

    @Test fun queuedMutationHoldsStopBarrierUntilCleanupAndNeverReplays() {
        val (view, _) = surface()
        controller.setKeepActive(true)
        val mutex = controller.javaClass.getDeclaredField("executeMutex").apply { isAccessible = true }
            .get(controller) as Mutex
        runBlocking { mutex.lock() }
        val queued = execute("click")
        try {
            drainUntil { controller.retention.value.activeOperations == 1 }
            assertFalse(controller.setKeepActive(false))
            assertTrue(controller.setKeepActive(false, cancelActive = true))
            assertTrue(controller.retention.value.stopping)
            val generation = controller.javaClass.getDeclaredField("pageGeneration").apply { isAccessible = true }
            val beforeReload = generation.getLong(controller)
            view.webViewClient.onPageStarted(view, view.page, null)
            assertEquals("Same-URL reload must invalidate capture restoration even while stopping",
                beforeReload + 1L, generation.getLong(controller))
            assertFalse(controller.setKeepActive(true))
            assertTrue(completed(execute("read")).isFailure)
            assertEquals(1, controller.retention.value.activeOperations)
            assertEquals(0, view.evaluations.size)
        } finally { mutex.unlock() }
        assertTrue(completed(queued).isFailure)
        assertFalse(controller.retention.value.stopping)
        assertEquals(0, controller.retention.value.activeOperations)
        assertEquals(0, view.evaluations.size)
    }

    @Test fun keepActiveDoesNotAuthorizeAppBackgroundExecutionOrReplay() {
        val (view, binding) = surface()
        controller.setKeepActive(true)
        hide(view, binding)
        val read = execute("read")
        drainUntil { view.evaluations.size == 1 }
        controller.setApplicationVisible(false)
        assertTrue(completed(read).isFailure)
        assertTrue(completed(execute("click")).isFailure)
        controller.setApplicationVisible(true)
        reply(view, 0)
        assertEquals(1, view.evaluations.size)
        assertTrue(controller.retention.value.keepActive)
        assertEquals(0, opens)
    }

    @Test fun criticalMemoryCanReleaseAHiddenForegroundRetainedRenderer() {
        val (view, binding) = surface()
        controller.setKeepActive(true)
        hide(view, binding)
        controller.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(1, view.destroyed)
        assertFalse(controller.retention.value.attached)
        assertFalse(controller.retention.value.presented)
        assertFalse(controller.retention.value.captureReady)
    }

    @Test fun pendingWebsiteDialogRequiresConfirmationEvenAfterToolReturns() {
        val (view, binding) = surface()
        controller.setKeepActive(true)
        hide(view, binding)
        val result = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsConfirm(view, view.page, "Continue?", result)
        assertEquals(0, controller.retention.value.activeOperations)
        assertFalse(controller.setKeepActive(false))
        assertNotNull(controller.pendingDialog.value)
        assertTrue(controller.setKeepActive(false, cancelActive = true))
        assertNull(controller.pendingDialog.value)
        assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
    }

    @Test fun stalePresentationCallbackCannotHideReplacement() {
        val (old, oldBinding) = surface()
        val (replacement, binding) = surface()
        controller.setSurfacePresented(binding, replacement, true, captureReady = false)
        controller.setSurfacePresented(oldBinding, old, false)
        assertTrue(controller.retention.value.presented)
        assertFalse(controller.retention.value.captureReady)
    }

    @Test fun captureRequestsRevealButDoesNotDrawAClippedRetainedSurface() {
        val (view, binding) = surface()
        controller.setKeepActive(true)
        hide(view, binding)
        controller.bindOpenRequest {
            opens++
            view.visibility = View.VISIBLE
            controller.setSurfacePresented(binding, view, true, captureReady = false)
        }
        val screenshot = execute("screenshot")
        drainUntil { opens == 1 }
        assertEquals(0, view.draws)
        assertTrue(completed(screenshot).isFailure)
        assertEquals(0, view.draws)
        assertEquals(0, view.destroyed)
    }

    @Test fun stopCancelsPendingToolGateAndRetiresUninvokedBatchTicket() {
        controller.setKeepActive(true)
        val tickets = controller.registerToolWorkBatch(2)
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val gated = caller.async {
            runCatching {
                controller.withToolWork(tickets[0]) {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanup.await() } }
                }
            }
        }
        drainUntil { entered.isCompleted }
        assertEquals(2, controller.retention.value.activeOperations)
        assertFalse(controller.setKeepActive(false))
        assertTrue(controller.setKeepActive(false, cancelActive = true))
        assertTrue(controller.retention.value.stopping)
        assertEquals(1, controller.retention.value.activeOperations)
        var queuedRan = false
        val queued = caller.async { runCatching { controller.withToolWork(tickets[1]) { queuedRan = true } } }
        assertTrue(completed(queued).exceptionOrNull() is BrowserSurfaceChangedException)
        assertFalse(queuedRan)
        cleanup.complete(Unit)
        assertTrue(completed(gated).exceptionOrNull() is BrowserSurfaceChangedException)
        assertTrue(caller.isActive)
        assertFalse(controller.retention.value.stopping)
        assertEquals(0, controller.retention.value.activeOperations)
        tickets.forEach(controller::releaseToolWork)
        assertEquals(0, controller.retention.value.activeOperations)
    }

    @Test fun sameUrlReloadAfterIdentitySnapshotRejectsEveryOldDocumentMutation() {
        for (operation in listOf("click", "type", "scroll", "back", "reload")) {
            val (view, _) = surface()
            var urlReads = 0
            view.onUrlRead = {
                urlReads++
                if (urlReads == 2) {
                    // Enqueue document replacement after the pre-validation identity
                    // snapshot and before the eventual main-thread mutation callback.
                    Handler(Looper.getMainLooper()).post {
                        view.webViewClient.onPageStarted(view, view.page, null)
                    }
                }
            }
            val result = completed(execute(operation))
            assertTrue("$operation must reject an old document at the same URL",
                result.exceptionOrNull() is BrowserSurfaceChangedException)
            assertEquals("$operation must not dispatch mutation JavaScript", 0, view.evaluations.size)
        }
    }
}

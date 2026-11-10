package com.baystudio.droide.ui

import org.junit.Before

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.BrowserApplicationTimers
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Observes documented process-global calls, not the Chromium JavaScript scheduler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
class BrowserApplicationTimersTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    private var globallyPaused = false
    private val views = mutableListOf<TimerView>()
    private val failures = mutableListOf<Throwable>()
    private val timers = BrowserApplicationTimers { failures += it }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private inner class TimerView(context: Context) : WebView(context) {
        var pauses = 0
        var resumes = 0
        var failPause = false
        var failResume = false
        var dead = false
        var page = ""
        val evaluations = mutableListOf<ValueCallback<String>>()
        override fun getUrl() = page
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.let { evaluations += it }
        }
        override fun pauseTimers() {
            check(!dead)
            pauses++
            if (failPause) error("Pause failed")
            globallyPaused = true
        }
        override fun resumeTimers() {
            check(!dead)
            resumes++
            if (failResume) error("Resume failed")
            globallyPaused = false
        }
    }
    private fun view() = TimerView(RuntimeEnvironment.getApplication()).also { views += it }
    @After fun cleanup() { scope.cancel(); timers.close(); views.forEach { it.destroy() } }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Browser timer operation did not complete", condition())
    }

    private fun <T> completed(result: Deferred<T>): T {
        drainUntil { result.isCompleted }
        return runBlocking { result.await() }
    }

    @Test fun onlyAllAppHiddenSignalPausesTimersAndDuplicateTransitionsAreIdempotent() {
        val first = view()
        val second = view()
        timers.attach(first)
        timers.attach(second)
        assertFalse(globallyPaused)
        timers.setApplicationVisible(false)
        timers.setApplicationVisible(false)
        assertTrue(globallyPaused)
        assertEquals(1, first.pauses + second.pauses)
        timers.setApplicationVisible(true)
        timers.setApplicationVisible(true)
        assertFalse(globallyPaused)
        assertEquals(1, first.resumes + second.resumes)
    }

    @Test fun panePauseAndDetachNeverSuspendAnotherViewWhileApplicationIsVisible() {
        val root = Files.createTempDirectory("browser-timer-pane-").toFile()
        try {
            val controller = AgentBrowserController(root, timers)
            val browser = view()
            val other = view()
            timers.attach(other)
            controller.configure(browser)
            val binding = controller.attach(browser)
            controller.setSurfaceStarted(binding, browser, false)
            assertFalse(globallyPaused)
            controller.detach(binding, browser, preserveState = false)
            assertFalse(globallyPaused)
            assertEquals(0, browser.pauses + other.pauses)
        } finally { root.deleteRecursively() }
    }

    @Test fun backgroundReplacementTransfersOwnershipAndForegroundResumesRemainingView() {
        val old = view()
        timers.attach(old)
        timers.setApplicationVisible(false)
        val replacement = view()
        timers.attach(replacement)
        timers.detach(old)
        old.dead = true
        assertTrue(globallyPaused)
        assertEquals(1, replacement.resumes)
        assertEquals(1, replacement.pauses)
        timers.setApplicationVisible(true)
        assertFalse(globallyPaused)
        assertEquals(2, replacement.resumes)
    }

    @Test fun lastViewReleaseBalancesGlobalTimerPauseBeforeItIsDestroyed() {
        val browser = view()
        timers.attach(browser)
        timers.setApplicationVisible(false)
        timers.detach(browser)
        browser.dead = true
        assertFalse(globallyPaused)
        timers.setApplicationVisible(true)
        assertEquals(1, browser.resumes)
    }

    @Test fun deadRendererIsNeverCalledAndReplacementSettlesPendingTimerResume() {
        val old = view()
        timers.attach(old)
        timers.setApplicationVisible(false)
        old.dead = true
        timers.detach(old, rendererGone = true)
        timers.setApplicationVisible(true)
        val replacement = view()
        timers.attach(replacement)
        assertFalse(globallyPaused)
        assertEquals(0, old.resumes)
        assertEquals(1, replacement.resumes)
        assertTrue(failures.isEmpty())
    }

    @Test fun failuresRemainRetryableAndDoNotDeclareTimersResumedPrematurely() {
        val browser = view()
        timers.attach(browser)
        browser.failPause = true
        timers.setApplicationVisible(false)
        browser.failPause = false
        timers.setApplicationVisible(false)
        assertTrue(globallyPaused)
        assertEquals(2, browser.pauses)
        browser.failResume = true
        timers.setApplicationVisible(true)
        assertTrue(globallyPaused)
        browser.failResume = false
        timers.setApplicationVisible(true)
        assertFalse(globallyPaused)
        assertEquals(2, browser.resumes)
        assertEquals(2, failures.size)
    }

    @Test fun closingProcessOwnerRestoresTimersForSurvivingViews() {
        val browser = view()
        timers.attach(browser)
        timers.setApplicationVisible(false)
        timers.close()
        assertFalse(globallyPaused)
        assertEquals(1, browser.resumes)
    }

    @Test fun failedGlobalResumeBlocksPageWorkUntilAnExplicitSuccessfulRetry() {
        val root = Files.createTempDirectory("browser-timer-debt-").toFile()
        try {
            root.resolve("index.html").writeText("<html><body>Ready</body></html>")
            val controller = AgentBrowserController(root, timers)
            controller.bindOpenRequest {}
            val browser = view().apply { page = controller.htmlPreviewUrl("index.html") }
            controller.configure(browser)
            controller.attach(browser)
            timers.setApplicationVisible(false)
            controller.setApplicationVisible(false)
            browser.failResume = true
            timers.setApplicationVisible(true)
            controller.setApplicationVisible(true)
            val failed = completed(scope.async { runCatching { controller.execute("read") } })
            assertTrue(failed.exceptionOrNull()!!.message!!.contains("JavaScript timers"))
            assertTrue(browser.evaluations.isEmpty())
            assertFalse(timers.readyForActions)
            browser.failResume = false
            val retried = scope.async { controller.execute("read") }
            drainUntil { browser.evaluations.size == 1 }
            browser.evaluations.single().onReceiveValue(JsonPrimitive(buildJsonObject {
                put("ok", true); put("url", browser.page)
            }.toString()).toString())
            assertTrue(completed(retried).contains("browser_page"))
            assertTrue(timers.readyForActions)
            assertEquals(1, browser.evaluations.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun failedGlobalResumeDoesNotAnswerPendingWebsiteConfirmation() {
        val root = Files.createTempDirectory("browser-timer-dialog-").toFile()
        try {
            root.resolve("index.html").writeText("<html><body>Ready</body></html>")
            val controller = AgentBrowserController(root, timers)
            controller.bindOpenRequest {}
            val browser = view().apply { page = controller.htmlPreviewUrl("index.html") }
            controller.configure(browser)
            controller.attach(browser)
            val native = BrowserDialogTestResults.alert()
            browser.webChromeClient!!.onJsConfirm(browser, browser.page, "Submit?", native)
            val id = controller.pendingDialog.value!!.id
            timers.setApplicationVisible(false)
            controller.setApplicationVisible(false)
            browser.failResume = true
            timers.setApplicationVisible(true)
            controller.setApplicationVisible(true)
            val denied = scope.async { runCatching { controller.execute("dialog_respond", target = id, text = "accept") } }
            assertTrue(completed(denied).isFailure)
            assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
            assertEquals(id, controller.pendingDialog.value!!.id)
            browser.failResume = false
            val retried = scope.async { controller.execute("dialog_respond", target = id, text = "accept") }
            assertTrue(completed(retried).contains("browser_dialog_response"))
            assertEquals(1, BrowserDialogTestResults.state(native).confirmations)
        } finally { root.deleteRecursively() }
    }
}

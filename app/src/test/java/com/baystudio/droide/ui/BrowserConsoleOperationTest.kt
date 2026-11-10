package com.baystudio.droide.ui

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebView
import com.baystudio.droide.core.AgentBrowserController
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
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserConsoleOperationTest {
    private val root = Files.createTempDirectory("browser-console-").toFile()
    private val browser = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val views = mutableListOf<ConsoleView>()

    private class ConsoleView(context: Context, var page: String) : WebView(context) {
        var urlReadCount = 0
        var onUrlRead: (Int) -> Unit = {}
        override fun getUrl(): String { onUrlRead(++urlReadCount); return page }
        override fun getTitle() = "Fixture"
    }

    @Before fun setup() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        root.resolve("index.html").writeText("<html><body>First</body></html>")
        root.resolve("next.html").writeText("<html><body>Second</body></html>")
        browser.bindOpenRequest {}
    }

    @After fun cleanup() {
        caller.cancel(); views.forEach(WebView::destroy); root.deleteRecursively()
    }

    private fun surface(path: String = "index.html") = ConsoleView(RuntimeEnvironment.getApplication(), browser.htmlPreviewUrl(path)).also {
        views += it; browser.configure(it); browser.attach(it)
    }

    private fun log(view: WebView, message: String) {
        assertTrue(requireNotNull(view.webChromeClient).onConsoleMessage(ConsoleMessage(message, "fixture.js", 7, ConsoleMessage.MessageLevel.LOG)))
    }

    private fun output(start: Int = 0, count: Int = 80, expected: String? = null): JsonObject {
        val result = caller.async { browser.execute("console", start = start, count = count, expectedCurrentUrl = expected) }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!result.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!result.isCompleted) Thread.sleep(2)
        }
        assertTrue("Console tool must complete without renderer JS evaluation", result.isCompleted)
        return Json.parseToJsonElement(runBlocking { result.await() }).jsonObject
    }

    @Test fun consoleIsReadableThroughActualBrowserOperationAndPagedWithinTheCurrentPage() {
        val view = surface()
        repeat(45) { log(view, "line-$it") }
        val first = output(count = 2)
        assertEquals("browser_console", first["kind"]!!.jsonPrimitive.content)
        assertEquals(view.page, first["url"]!!.jsonPrimitive.content)
        assertEquals(40, first["total_lines"]!!.jsonPrimitive.int)
        assertEquals(2, first["next_start_line"]!!.jsonPrimitive.int)
        assertTrue(first["lines"]!!.jsonArray.first().jsonPrimitive.content.contains("line-5"))
        val last = output(start = 38, count = 2)
        assertTrue(last["lines"]!!.jsonArray.last().jsonPrimitive.content.contains("line-44"))
        assertEquals(JsonNull, last["next_start_line"])
    }

    @Test fun escapedLargeConsoleMessagesRetainStructuredLinesAndContinuationUnderOutputLimit() {
        val view = surface()
        repeat(40) { log(view, "\u0001".repeat(2_000)) }
        val result = output(count = 40)
        assertTrue(result.toString().length <= 18_000)
        assertTrue(result["output_truncated"]!!.jsonPrimitive.boolean)
        val lines = result["lines"]!!.jsonArray
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.jsonPrimitive.content.length <= 1_000 })
        assertEquals(lines.size, result["next_start_line"]!!.jsonPrimitive.int)
        assertFalse("Console must remain machine-readable, not a clipped raw preview", "preview" in result)
    }

    @Test fun navigationAndReplacementClearPriorPageLogsAndIgnoreOldViewCallbacks() {
        val old = surface()
        log(old, "first page private fixture")
        old.page = browser.htmlPreviewUrl("next.html")
        old.webViewClient.onPageStarted(old, old.page, null)
        assertTrue(output()["lines"]!!.jsonArray.isEmpty())
        log(old, "second page fixture")
        val replacement = surface()
        log(old, "stale view fixture")
        log(replacement, "active view fixture")
        val result = output()
        assertEquals(1, result["lines"]!!.jsonArray.size)
        assertTrue(result["lines"]!!.jsonArray.single().jsonPrimitive.content.contains("active view fixture"))
        assertFalse(result.toString().contains("private fixture"))
        assertFalse(result.toString().contains("stale view fixture"))
    }

    @Test fun pageChangingAfterPermissionReviewCannotReleaseConsoleFromTheNewPage() {
        val view = surface()
        val reviewed = view.page
        view.urlReadCount = 0
        view.onUrlRead = { read -> if (read == 3) {
            view.page = browser.htmlPreviewUrl("next.html")
            view.webViewClient.onPageStarted(view, view.page, null)
            log(view, "new-page fixture")
        } }
        val failure = runCatching { output(expected = reviewed) }.exceptionOrNull()
        assertTrue("Expected a page review error, got $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("changed while reading console"))
        assertTrue(caller.isActive)
        view.onUrlRead = {}
        assertTrue(output()["lines"]!!.jsonArray.single().jsonPrimitive.content.contains("new-page fixture"))
    }
}

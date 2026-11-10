package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
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

/** Authored regression fixtures only; no live web page or shell process is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserQueuedToolStopTest {
    private val root = Files.createTempDirectory("browser-queued-tool-").toFile()
    private val browser = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val contexts = mutableListOf<AgentTools.Ctx>()
    private lateinit var view: PageView

    private class PageView(context: Context) : WebView(context) {
        var evaluations = 0
        override fun getUrl() = "https://example.com/review"
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) { evaluations++ }
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        root.resolve("keep.txt").writeText("Other Agent work remains available")
        view = PageView(RuntimeEnvironment.getApplication())
        browser.bindOpenRequest {}
        browser.configure(view)
        browser.attach(view)
        browser.setKeepActive(true)
    }

    @After fun cleanup() {
        caller.cancel()
        view.destroy()
        contexts.map { it.webResearch }.distinct().forEach { it.close() }
        root.deleteRecursively()
    }

    private fun context(
        permissions: PermissionEngine = PermissionEngine(),
        ticket: AgentBrowserController.ToolWork? = null,
    ): AgentTools.Ctx {
        val base = agentWorkflowContext(root, permissions = permissions, browserController = browser,
            provenance = AgentRequestProvenance.primary("queue-stop-session"))
        val ctx = if (ticket == null) base else AgentTools.Ctx(
            base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
            base.perms, base.mode, base.llm, base.llmConfig, root, base.todos, base.sessionId, base.lsp,
            browserController = browser, browserToolWork = ticket, webResearch = base.webResearch,
            provenance = base.provenance,
        )
        contexts += ctx
        return ctx
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Browser tool did not reach the expected state", condition())
    }

    private fun browserArgs(operation: String = "read") = buildJsonObject {
        put("operation", operation); put("target", "#submit")
    }

    private fun assertRecoverable(result: String) {
        val parsed = Json.parseToJsonElement(result).jsonObject
        assertEquals(JsonPrimitive(false), parsed["success"])
        assertEquals(JsonPrimitive(true), parsed["recoverable"])
        assertEquals(JsonPrimitive("browser_unavailable"), parsed["kind"])
    }

    @Test fun directPermissionWaitCountsAsBusyAndStopRemovesOnlyItsApproval() {
        val ctx = context(PermissionEngine(listOf(PermRule("*", "*", PermEffect.ASK))))
        val operation = caller.async { AgentTools.execute("browser", browserArgs(), ctx) }
        pumpUntil { ctx.approvals.pending.value?.action == "webfetch" }
        val browserRequest = requireNotNull(ctx.approvals.pending.value)
        assertNotNull(browserRequest.browserContext)
        assertEquals(1, browser.retention.value.activeOperations)
        assertFalse(browser.setKeepActive(false))
        assertTrue(browser.retention.value.keepActive)
        assertEquals(browserRequest.id, ctx.approvals.pending.value?.id)

        val read = caller.async {
            AgentTools.execute("read_file", buildJsonObject { put("path", "keep.txt") }, ctx)
        }
        assertTrue(browser.setKeepActive(false, cancelActive = true))
        pumpUntil { operation.isCompleted && ctx.approvals.pending.value?.action == "read" }
        assertRecoverable(runBlocking { operation.await() })
        assertFalse(ctx.approvals.resolve(browserRequest.id, ApprovalResolution.ALLOW_SESSION))
        val otherRequest = requireNotNull(ctx.approvals.pending.value)
        assertEquals("keep.txt", otherRequest.resource)
        assertTrue(ctx.approvals.resolve(otherRequest.id, ApprovalResolution.ALLOW_ONCE))
        pumpUntil { read.isCompleted }
        assertTrue(runBlocking { read.await() }.contains("Other Agent work remains available"))
        assertTrue(caller.isActive)
        assertEquals(0, browser.retention.value.activeOperations)
        assertFalse(browser.retention.value.stopping)
        assertEquals(0, view.evaluations)
        assertFalse(ctx.approvals.hasSessionApproval(browserRequest.approvalSessionId,
            browserRequest.permissionScope, browserRequest.sessionGrantAction, browserRequest.sessionGrantResource))
    }

    @Test fun staleQueuedTicketsCannotAskForApprovalOrRunUnderTheNewStopEpoch() {
        val tickets = browser.registerToolWorkBatch(2)
        val first = context(ticket = tickets[0])
        val second = context(ticket = tickets[1])
        assertEquals(2, browser.retention.value.activeOperations)
        assertTrue(browser.setKeepActive(false, cancelActive = true))
        assertFalse(browser.retention.value.stopping)
        for (ctx in listOf(first, second)) {
            val operation = caller.async { AgentTools.execute("browser", browserArgs("click"), ctx) }
            pumpUntil { operation.isCompleted }
            assertRecoverable(runBlocking { operation.await() })
            assertNull(ctx.approvals.pending.value)
        }
        tickets.forEach { browser.releaseToolWork(it); browser.releaseToolWork(it) }
        assertEquals(0, browser.retention.value.activeOperations)
        assertEquals(0, view.evaluations)

        val fresh = context()
        val next = caller.async { AgentTools.execute("browser", browserArgs("status"), fresh) }
        pumpUntil { fresh.approvals.pending.value != null }
        fresh.approvals.resolve(ApprovalResolution.DENY)
        pumpUntil { next.isCompleted }
        assertTrue(runBlocking { next.await() }.startsWith("DENIED"))
        assertEquals(0, browser.retention.value.activeOperations)
    }

    @Test fun nestedDispatchKeepsOneTicketThroughPostToolApprovalAndReleasesAfterStop() {
        val ctx = context(PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), browser.registerToolWork())
        var reachedPostTool = false
        val operation = caller.async {
            AgentTools.withBrowserToolWork("browser", ctx) {
                val result = AgentTools.execute("browser", browserArgs("status"), ctx)
                assertEquals(1, browser.retention.value.activeOperations)
                reachedPostTool = true
                ctx.approvals.requestApproval("hook", "Post browser hook", "fixture",
                    action = "hook", resource = "browser-post-hook")
                result
            }
        }
        pumpUntil { reachedPostTool && ctx.approvals.pending.value != null }
        val request = requireNotNull(ctx.approvals.pending.value)
        assertTrue(browser.setKeepActive(false, cancelActive = true))
        pumpUntil { operation.isCompleted }
        assertRecoverable(runBlocking { operation.await() })
        assertNull(ctx.approvals.pending.value)
        assertFalse(ctx.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(0, browser.retention.value.activeOperations)
        assertTrue(caller.isActive)
    }

    @Test fun cancellingTheAgentCallerStillPropagatesAndReleasesItsPendingBrowserTicket() {
        val ctx = context()
        val operation = caller.async { AgentTools.execute("browser", browserArgs(), ctx) }
        pumpUntil { ctx.approvals.pending.value != null }
        operation.cancel(CancellationException("User stopped the whole Agent"))
        pumpUntil { operation.isCompleted && ctx.approvals.pending.value == null }
        assertTrue(runCatching { runBlocking { operation.await() } }.exceptionOrNull() is CancellationException)
        assertEquals(0, browser.retention.value.activeOperations)
        assertTrue(caller.isActive)
        assertTrue(browser.retention.value.keepActive)
        assertEquals(0, view.evaluations)
    }
}

package com.baystudio.droide.core

import android.app.Application
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.FrameLayout
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Scripted provider, controlled WebView callbacks and inert terminal; tests remain unexecuted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentBrowserQueueWorkflowTest {
    private val root = Files.createTempDirectory("agent-browser-queue-").toFile()
    private val browser = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val terminal = RecordingTerminal()
    private lateinit var view: PageView
    private var captureActivity: ActivityController<Activity>? = null

    private class RecordingTerminal : ITerminalSession {
        val calls = AtomicInteger()
        override val output = MutableStateFlow("")
        override fun start() = Unit
        override fun send(cmd: String) { calls.incrementAndGet() }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            calls.incrementAndGet()
            return ExecResult(0, "fixture only", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = execOnce(argv.joinToString(" "), timeoutMs)
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private class PageView(context: Context) : WebView(context) {
        val evaluations = mutableListOf<ValueCallback<String>>()
        var page = "https://example.com/review"
        var captureDraws = 0
        var onCaptureDraw: (() -> Unit)? = null
        override fun getUrl() = page
        override fun getContentHeight() = 100
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun postVisualStateCallback(requestId: Long, callback: VisualStateCallback) {
            Handler(Looper.getMainLooper()).post { callback.onComplete(requestId) }
        }
        override fun draw(canvas: Canvas) {
            captureDraws++
            canvas.drawColor(Color.MAGENTA)
            onCaptureDraw?.invoke()
        }
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.let { evaluations += it }
        }
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        root.resolve("keep.txt").writeText("Independent file work completed")
        view = PageView(RuntimeEnvironment.getApplication())
        browser.bindOpenRequest {}
        browser.configure(view)
        browser.attach(view)
        browser.setKeepActive(true)
    }

    @After fun cleanup() {
        caller.cancel()
        view.destroy()
        captureActivity?.pause()?.stop()?.destroy()
        PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root))
        root.deleteRecursively()
    }

    private fun call(index: Int, name: String, args: JsonObject) = buildJsonObject {
        put("index", index); put("id", "call-$index"); put("type", "function")
        put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
    }

    private fun click(index: Int) = call(index, "browser", buildJsonObject {
        put("operation", "click"); put("target", "#submit-$index")
    })

    private fun read(index: Int) = call(index, "read_file", buildJsonObject { put("path", "keep.txt") })

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Agent workflow did not reach the expected bounded state", condition())
    }

    private fun withAgent(
        calls: List<JsonObject>,
        permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
        onProviderRequest: (JsonObject) -> Unit = {},
        block: (AgentService, Deferred<Unit>) -> Unit,
    ) {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                perms = permissions, browserController = browser, maxSubagentDepth = 0)
            fixture.response = { turn, request, _ ->
                onProviderRequest(request)
                if (turn == 1) {
                    fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":${JsonArray(calls)}},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                } else fixture.event("""{"choices":[{"index":0,"delta":{"content":"Finished independent work"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            }
            val operation = caller.async { withTimeout(20_000) { agent.chat("Inspect page and keep.txt", fixture.config, maxTurns = 2) } }
            try { block(agent, operation) }
            finally {
                operation.cancel()
                runBlocking { operation.join() }
                agent.shutdown()
            }
        }
    }

    private fun results(agent: AgentService): Map<String, String> {
        val session = runBlocking { agent.sessions.get(requireNotNull(agent.currentSessionId))!! }
        return session.apiHistory.filter { it["role"] == JsonPrimitive("tool") }.associate {
            it.getValue("tool_call_id").jsonPrimitive.content to it.getValue("content").jsonPrimitive.content
        }
    }

    private fun assertStopped(result: String) {
        assertTrue(result.contains("browser_unavailable"))
        assertTrue(result.contains("\"recoverable\":true"))
        assertTrue(result.contains("not replayed"))
    }

    private fun installBrowserHook(event: String) {
        root.resolve(".droide").mkdirs()
        root.resolve(".droide/hooks.json").writeText(
            """{"enabled":true,"events":{"$event":[{"command":"echo controlled-browser-hook","matcher":"browser"}]}}""")
    }

    private fun assertPolicyDeniedWithoutPrompts(agent: AgentService, operation: Deferred<Unit>, ids: List<String>) {
        pumpUntil { operation.isCompleted || agent.approvals.pending.value != null }
        assertNull("Hard browser DENY must precede doom-loop and lifecycle-hook ASK", agent.approvals.pending.value)
        assertTrue(operation.isCompleted)
        runBlocking { operation.await() }
        val output = results(agent)
        ids.forEach { id -> assertTrue(output.getValue(id).contains("DENIED")) }
        assertEquals(0, terminal.calls.get())
        assertEquals(0, view.evaluations.size)
        assertEquals(0, browser.retention.value.activeOperations)
    }

    private fun approveOneOutput(agent: AgentService, operation: Deferred<Unit>) {
        pumpUntil { agent.approvals.pending.value?.kind == "browser_unverified_output" || operation.isCompleted }
        val approval = requireNotNull(agent.approvals.pending.value)
        assertEquals("browser_unverified_output", approval.kind)
        assertFalse(approval.canAllowForSession)
        assertFalse(agent.approvals.resolve(approval.id, ApprovalResolution.ALLOW_SESSION))
        assertTrue(agent.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
    }

    private fun assertNoBrowserPayload(agent: AgentService, requests: List<JsonObject>, vararg secrets: String) {
        val session = runBlocking { agent.sessions.get(requireNotNull(agent.currentSessionId))!! }
        val surfaces = listOf(
            agent.streamState.value.toString(), agent.messages.value.toString(),
            session.messages.toString(), session.apiHistory.toString(), session.forkHistory.toString(),
            requests.joinToString("\n"),
            root.resolve(".droide/sessions").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() },
        )
        secrets.forEach { secret ->
            surfaces.forEach { surface -> assertFalse("Browser payload leaked into UI, provider or saved history", surface.contains(secret)) }
        }
        assertTrue(agent.messages.value.all { it.media.isEmpty() })
        assertTrue(session.messages.all { it.media.isEmpty() })
        assertTrue(ChatMediaStore(root).ownerRefs(session.id).isEmpty())
        assertNull("A policy revocation is not a storage failure", agent.persistenceError.value)
    }

    private fun prepareCaptureSurface() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        captureActivity = activity
        val host = FrameLayout(activity.get())
        host.addView(view, FrameLayout.LayoutParams(120, 100))
        activity.get().setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        host.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 120, 100)
        view.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 120, 100)
        view.left = 0; view.top = 0; view.right = 120; view.bottom = 100
        assertTrue(view.isAttachedToWindow)
        assertEquals(120, view.width)
        assertEquals(100, view.height)
        root.resolve("capture.html").writeText("<html><body>Controlled native capture</body></html>")
        view.page = browser.htmlPreviewUrl("capture.html")
        view.webViewClient.onPageStarted(view, view.page, null)
        view.webViewClient.onPageFinished(view, view.page)
    }

    @Test(timeout = 30_000) fun hardBrowserDenySuppressesTheThirdIdenticalCallsDoomLoopAsk() {
        val permissions = PermissionEngine(listOf(
            PermRule("*", "*", PermEffect.ALLOW),
            PermRule("doom_loop", "*", PermEffect.ASK),
            PermRule("webfetch", view.page, PermEffect.DENY),
        ))
        val repeated = (0..2).map { index -> call(index, "browser", buildJsonObject { put("operation", "status") }) }
        withAgent(repeated + read(3), permissions) { agent, operation ->
            assertPolicyDeniedWithoutPrompts(agent, operation, listOf("call-0", "call-1", "call-2"))
            assertTrue(results(agent).getValue("call-3").contains("Independent file work completed"))
        }
    }

    @Test(timeout = 30_000) fun hardBrowserDenySuppressesPreToolHookApproval() {
        installBrowserHook("PreToolUse")
        val permissions = PermissionEngine(listOf(
            PermRule("*", "*", PermEffect.ALLOW),
            PermRule("hook", "*", PermEffect.ASK),
            PermRule("webfetch", view.page, PermEffect.DENY),
        ))
        withAgent(listOf(click(0), read(1)), permissions) { agent, operation ->
            assertPolicyDeniedWithoutPrompts(agent, operation, listOf("call-0"))
            assertTrue(results(agent).getValue("call-1").contains("Independent file work completed"))
        }
    }

    @Test(timeout = 30_000) fun hardBrowserDenySuppressesAnOtherwiseAllowedPreToolHookCommand() {
        installBrowserHook("PreToolUse")
        val permissions = PermissionEngine(listOf(
            PermRule("*", "*", PermEffect.ALLOW),
            PermRule("webfetch", view.page, PermEffect.DENY),
        ))
        withAgent(listOf(click(0), read(1)), permissions) { agent, operation ->
            assertPolicyDeniedWithoutPrompts(agent, operation, listOf("call-0"))
            assertTrue(results(agent).getValue("call-1").contains("Independent file work completed"))
        }
    }

    @Test(timeout = 60_000) fun actualSameDocumentUrlDenySuppressesPreToolHooksBeforeCachedUrlReconciliation() {
        val cached = "http://localhost:3000/public"
        val actual = "http://localhost:3000/private"
        view.page = cached
        view.webViewClient.onPageStarted(view, cached, null)
        view.webViewClient.onPageFinished(view, cached)
        assertEquals(cached, browser.currentUrl())
        // History API navigation can change WebView.url without either page callback.
        view.page = actual
        installBrowserHook("PreToolUse")
        for (hookEffect in listOf(PermEffect.ASK, PermEffect.ALLOW)) {
            val permissions = PermissionEngine(listOf(
                PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", hookEffect),
                PermRule("webfetch", actual, PermEffect.DENY),
            ))
            withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "status") }), read(1)),
                permissions) { agent, operation ->
                assertPolicyDeniedWithoutPrompts(agent, operation, listOf("call-0"))
                assertTrue(results(agent).getValue("call-1").contains("Independent file work completed"))
                assertEquals("Prehook sampling must not reconcile or mutate the cached document", cached, browser.currentUrl())
                assertEquals(actual, view.page)
            }
        }
    }

    @Test(timeout = 60_000) fun selectedConsoleSourceDenySuppressesPreToolApprovalAndAllowedHookCommands() {
        view.page = "http://localhost:3000/console-preflight"
        view.webViewClient.onPageStarted(view, view.page, null)
        view.webViewClient.onPageFinished(view, view.page)
        val deniedSource = "https://frame.example.test/denied-console-script.js"
        val secret = "private-selected-console-line"
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage("allowed-first-line", view.page, 1, ConsoleMessage.MessageLevel.LOG))
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage(secret, deniedSource, 2, ConsoleMessage.MessageLevel.LOG))
        installBrowserHook("PreToolUse")
        for (hookEffect in listOf(PermEffect.ASK, PermEffect.ALLOW)) {
            val permissions = PermissionEngine(listOf(
                PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", hookEffect),
                PermRule("webfetch", deniedSource, PermEffect.DENY),
            ))
            val requests = CopyOnWriteArrayList<JsonObject>()
            withAgent(listOf(call(0, "browser", buildJsonObject {
                put("operation", "console"); put("start_line", 1); put("num_lines", 1)
            }), read(1)), permissions, onProviderRequest = { requests += it }) { agent, operation ->
                assertPolicyDeniedWithoutPrompts(agent, operation, listOf("call-0"))
                assertTrue(results(agent).getValue("call-1").contains("Independent file work completed"))
                assertNoBrowserPayload(agent, requests, secret)
            }
        }
    }

    @Test(timeout = 30_000) fun excludedConsoleSourceDenyStillWithholdsUnverifiedContributorsBeforeAnyHook() {
        view.page = "http://localhost:3000/console-preflight"
        view.webViewClient.onPageStarted(view, view.page, null)
        view.webViewClient.onPageFinished(view, view.page)
        val allowed = "permitted-requested-console-line"
        val excluded = "private-excluded-console-line"
        val deniedSource = "https://frame.example.test/excluded-console-script.js"
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage(allowed, view.page, 1, ConsoleMessage.MessageLevel.LOG))
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage(excluded, deniedSource, 2, ConsoleMessage.MessageLevel.LOG))
        installBrowserHook("PreToolUse")
        val permissions = PermissionEngine(listOf(
            PermRule("*", "*", PermEffect.ALLOW), PermRule("webfetch", deniedSource, PermEffect.DENY),
        ))
        val requests = CopyOnWriteArrayList<JsonObject>()
        withAgent(listOf(call(0, "browser", buildJsonObject {
            put("operation", "console"); put("start_line", 0); put("num_lines", 1)
        }), read(1)), permissions, onProviderRequest = { requests += it }) { agent, operation ->
            pumpUntil { operation.isCompleted || agent.approvals.pending.value != null }
            assertNull("Unknown contributors retain every applicable DENY, including excluded diagnostics", agent.approvals.pending.value)
            assertTrue(operation.isCompleted)
            runBlocking { operation.await() }
            val output = results(agent)
            assertFalse(output.getValue("call-0").contains(allowed))
            assertTrue(output.getValue("call-0").contains("DENIED"))
            assertTrue(output.getValue("call-1").contains("Independent file work completed"))
            assertEquals("Unresolved DENY must suppress even the otherwise allowed prehook", 0, terminal.calls.get())
            assertEquals(0, view.evaluations.size)
            assertNoBrowserPayload(agent, requests, excluded)
        }
    }

    @Test(timeout = 30_000) fun sourceDenyWhilePostToolHookAsksWithholdsConsolePayloadEverywhere() {
        val secret = "private-post-hook-console-payload"
        val source = "https://frame.example.test/post-hook-script.js"
        view.page = "http://localhost:3000/console"
        view.webViewClient.onPageStarted(view, view.page, null)
        view.webViewClient.onPageFinished(view, view.page)
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage(secret, source, 1, ConsoleMessage.MessageLevel.LOG))
        installBrowserHook("PostToolUse")
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", PermEffect.ASK)))
        val requests = CopyOnWriteArrayList<JsonObject>()
        withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "console") }), read(1)),
            permissions, onProviderRequest = { requests += it }) { agent, operation ->
            approveOneOutput(agent, operation)
            pumpUntil { agent.approvals.pending.value?.action == "hook" || operation.isCompleted }
            val request = requireNotNull(agent.approvals.pending.value)
            assertTrue(request.resource.contains("PostToolUse"))
            assertEquals(0, terminal.calls.get())
            assertFalse(agent.streamState.value.toString().contains(secret))
            assertFalse(agent.messages.value.toString().contains(secret))
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
                PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", PermEffect.ASK),
                PermRule("webfetch", source, PermEffect.DENY),
            )))
            assertTrue(agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            val output = results(agent)
            assertTrue(output.getValue("call-0").contains("DENIED"))
            assertTrue(output.getValue("call-0").contains("withheld", ignoreCase = true))
            assertTrue(output.getValue("call-1").contains("Independent file work completed"))
            assertEquals("Policy revocation must also fence the waiting hook command", 0, terminal.calls.get())
            assertNoBrowserPayload(agent, requests, secret)
        }
    }

    @Test(timeout = 30_000) fun denyWhilePostToolHookAsksDiscardsCapturedMediaBeforeHistoryCommit() {
        prepareCaptureSurface()
        installBrowserHook("PostToolUse")
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", PermEffect.ASK)))
        val requests = CopyOnWriteArrayList<JsonObject>()
        withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "screenshot") }), read(1)),
            permissions, onProviderRequest = { requests += it }) { agent, operation ->
            approveOneOutput(agent, operation)
            pumpUntil { agent.approvals.pending.value?.action == "hook" || operation.isCompleted }
            val request = requireNotNull(agent.approvals.pending.value)
            assertTrue(request.resource.contains("PostToolUse"))
            val store = ChatMediaStore(root)
            assertTrue("Exercise a real controlled capture, not an earlier error", view.captureDraws > 0)
            val capturedAliases = store.storageDirectory.walkTopDown().filter { it.isFile && it.extension == "png" }
                .map { ".droide/chat-media/" + it.relativeTo(store.storageDirectory).invariantSeparatorsPath }.toList()
            assertTrue("An actual captured PNG must exist before revocation", capturedAliases.isNotEmpty())
            assertTrue(agent.messages.value.all { it.media.isEmpty() })
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
                PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", PermEffect.ASK),
                PermRule("webfetch", view.page, PermEffect.DENY),
            )))
            assertTrue(agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            assertTrue(results(agent).getValue("call-0").contains("DENIED"))
            assertTrue(results(agent).getValue("call-1").contains("Independent file work completed"))
            assertNoBrowserPayload(agent, requests, "browser_screenshot", *capturedAliases.toTypedArray())
            assertTrue("No rejected staging or session-owned PNG may remain", store.storageDirectory.walkTopDown().none { it.isFile && it.extension == "png" })
            assertEquals(0, terminal.calls.get())
        }
    }

    @Test(timeout = 30_000) fun rejectedScreenshotConsentDoesNotDrawOrProduceMedia() {
        prepareCaptureSurface()
        withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "screenshot") }), read(1))) { agent, operation ->
            pumpUntil { agent.approvals.pending.value?.kind == "browser_unverified_output" || operation.isCompleted }
            val request = requireNotNull(agent.approvals.pending.value)
            assertTrue(agent.approvals.resolve(request.id, ApprovalResolution.DENY))
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            assertEquals(0, view.captureDraws)
            assertTrue(results(agent).getValue("call-0").contains("DENIED"))
            assertTrue(agent.messages.value.all { it.media.isEmpty() })
            assertTrue(ChatMediaStore(root).storageDirectory.walkTopDown().none { it.isFile && it.extension == "png" })
        }
    }

    @Test(timeout = 30_000) fun policyMutationDuringNativeDrawWithholdsAndCleansTheCapture() {
        prepareCaptureSurface()
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))
        val requests = CopyOnWriteArrayList<JsonObject>()
        view.onCaptureDraw = {
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
                PermRule("webfetch", "https://unverified-frame.test/*", PermEffect.DENY))))
        }
        withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "screenshot") }), read(1)),
            permissions, onProviderRequest = { requests += it }) { agent, operation ->
            approveOneOutput(agent, operation)
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            assertTrue("Revocation cannot undo a native draw already admitted", view.captureDraws > 0)
            assertTrue(results(agent).getValue("call-0").contains("DENIED"))
            assertNoBrowserPayload(agent, requests, "browser_screenshot")
            assertTrue(ChatMediaStore(root).storageDirectory.walkTopDown().none { it.isFile && it.extension == "png" })
            assertTrue(results(agent).getValue("call-1").contains("Independent file work completed"))
        }
    }

    @Test(timeout = 30_000) fun fullCancellationDuringPostHookReleasesCapturedSessionAndStagingOwners() {
        prepareCaptureSurface()
        installBrowserHook("PostToolUse")
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", PermEffect.ASK)))
        withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "screenshot") })), permissions) { agent, operation ->
            approveOneOutput(agent, operation)
            pumpUntil { agent.approvals.pending.value?.action == "hook" || operation.isCompleted }
            assertEquals("hook", requireNotNull(agent.approvals.pending.value).action)
            assertTrue(view.captureDraws > 0)
            operation.cancel()
            pumpUntil { operation.isCompleted }
            runBlocking { operation.join() }
            assertNull(agent.approvals.pending.value)
            assertEquals(0, browser.retention.value.activeOperations)
            assertTrue(agent.messages.value.all { it.media.isEmpty() })
            assertTrue(ChatMediaStore(root).storageDirectory.walkTopDown().none { it.isFile && it.extension == "png" })
        }
    }

    @Test(timeout = 30_000) fun cancellationWhileHistoryMutexWaitsReleasesScreenshotOwners() {
        prepareCaptureSurface()
        installBrowserHook("PostToolUse")
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", PermEffect.ASK)))
        withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "screenshot") })), permissions) { agent, operation ->
            approveOneOutput(agent, operation)
            pumpUntil { agent.approvals.pending.value?.action == "hook" || operation.isCompleted }
            val request = requireNotNull(agent.approvals.pending.value)
            val history = AgentService::class.java.getDeclaredField("conversationHistory").apply { isAccessible = true }.get(agent)
            val mutex = AgentConversationHistory::class.java.getDeclaredField("appendMutex").apply { isAccessible = true }
                .get(history) as Mutex
            runBlocking { mutex.lock() }
            try {
                assertTrue(agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
                pumpUntil { terminal.calls.get() == 1 && browser.retention.value.activeOperations == 0 }
                assertFalse(operation.isCompleted)
                operation.cancel()
            } finally { mutex.unlock() }
            pumpUntil { operation.isCompleted }
            runBlocking { operation.join() }
            assertTrue(view.captureDraws > 0)
            assertTrue(agent.messages.value.all { it.media.isEmpty() })
            assertTrue(ChatMediaStore(root).storageDirectory.walkTopDown().none { it.isFile && it.extension == "png" })
        }
    }

    @Test(timeout = 30_000) fun sourceDenyDuringHistoryAdmissionClosesTheCallOnceWithoutPoisoningStorage() {
        val secret = "private-browser-history-admission-payload"
        val source = "https://frame.example.test/admission-script.js"
        view.page = "http://localhost:3000/console"
        view.webViewClient.onPageStarted(view, view.page, null)
        view.webViewClient.onPageFinished(view, view.page)
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage(secret, source, 1, ConsoleMessage.MessageLevel.LOG))
        installBrowserHook("PostToolUse")
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("hook", "*", PermEffect.ASK)))
        val requests = CopyOnWriteArrayList<JsonObject>()
        withAgent(listOf(call(0, "browser", buildJsonObject { put("operation", "console") }), read(1)),
            permissions, onProviderRequest = { requests += it }) { agent, operation ->
            approveOneOutput(agent, operation)
            pumpUntil { agent.approvals.pending.value?.action == "hook" || operation.isCompleted }
            val request = requireNotNull(agent.approvals.pending.value)
            val history = AgentService::class.java.getDeclaredField("conversationHistory").apply { isAccessible = true }
                .get(agent) as AgentConversationHistory
            val appendMutex = AgentConversationHistory::class.java.getDeclaredField("appendMutex").apply { isAccessible = true }
                .get(history) as Mutex
            runBlocking { appendMutex.lock() }
            try {
                assertTrue(agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
                pumpUntil { terminal.calls.get() == 1 && browser.retention.value.activeOperations == 0 }
                assertFalse("The held history admission must keep the Agent run pending", operation.isCompleted)
                permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
                    PermRule("*", "*", PermEffect.ALLOW), PermRule("webfetch", source, PermEffect.DENY),
                )))
            } finally { appendMutex.unlock() }
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            val session = runBlocking { agent.sessions.get(requireNotNull(agent.currentSessionId))!! }
            val closures = session.apiHistory.filter { it["role"] == JsonPrimitive("tool") && it["tool_call_id"] == JsonPrimitive("call-0") }
            assertEquals("A revoked publication needs one safe closure, never duplicate tool results", 1, closures.size)
            assertTrue(closures.single().getValue("content").jsonPrimitive.content.contains("DENIED"))
            assertTrue(results(agent).getValue("call-1").contains("Independent file work completed"))
            assertTrue("The exact history grammar must remain forkable", SessionForkHistory.isExact(session.forkHistory))
            assertNoBrowserPayload(agent, requests, secret)
            runBlocking { history.ensureStorageAvailable() }
        }
    }

    @Test(timeout = 30_000) fun revocationAtHistoryIoAdmissionRejectsRawEntryWithoutStorageFailure() = runBlocking {
        val history = AgentConversationHistory(SessionHistoryLedger(root))
        val pending = buildJsonObject {
            put("role", "assistant"); put("content", "")
            put("tool_calls", JsonArray(listOf(call(0, "browser", buildJsonObject { put("operation", "console") }))))
        }
        fun previous(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
        val permittedBytes = SessionHistoryLedger.HOT_HISTORY_BYTES - SessionJsonSize.estimatedBytes(pending) - 1
        val permitted = previous("x".repeat((permittedBytes - SessionJsonSize.estimatedBytes(previous(""))).toInt()))
        history.append(listOf(permitted, pending))
        assertEquals("Only the result append should need cold storage", SessionHistoryLedger.HOT_HISTORY_BYTES - 1, history.hotHistoryBytes)

        val secret = "private-browser-payload-before-ledger-io"
        fun result(text: String) = buildJsonObject {
            put("role", "tool"); put("tool_call_id", "call-0"); put("content", text)
        }
        val raw = result(secret)
        val checks = AtomicInteger()
        val failure = runCatching {
            history.append(raw, beforeAppend = {
                if (checks.incrementAndGet() == 2) {
                    throw BrowserPublicationRevokedException("DENIED: browser output was withheld at IO admission")
                }
            })
        }.exceptionOrNull()
        assertTrue("Policy revocation must propagate instead of becoming an IO/storage fallback", failure is BrowserPublicationRevokedException)
        assertEquals("Exercise mutex admission and then the actual IO-side admission", 2, checks.get())
        assertEquals(listOf(permitted, pending), history.active)
        assertEquals(SessionHistoryLedger.HOT_HISTORY_BYTES - 1, history.hotHistoryBytes)
        assertTrue(root.resolve(".droide/sessions/.history-pages").walkTopDown().filter { it.isFile }.none { it.readText().contains(secret) })

        // This still has one unresolved tool. A poisoned storageFailure would try
        // to checkpoint incomplete grammar here and incorrectly stop future work.
        history.ensureStorageAvailable()
        val safe = result("DENIED: browser output was withheld")
        history.append(safe)
        assertEquals("The safe retry must still be able to spill", 0L, history.hotHistoryBytes)
        val exact = requireNotNull(history.completeExactOrNull())
        assertTrue(SessionForkHistory.isExact(exact))
        assertEquals(listOf(permitted, pending, safe), exact)
        assertEquals(1, exact.count { it["tool_call_id"] == JsonPrimitive("call-0") })
        assertFalse(history.active.toString().contains(secret))
        assertTrue(root.resolve(".droide/sessions/.history-pages").walkTopDown().filter { it.isFile }.none { it.readText().contains(secret) })
        history.ensureStorageAvailable()
    }

    @Test(timeout = 30_000) fun stopDuringFirstClickRejectsSecondPreparedClickAndContinuesFileWork() {
        root.resolve("queue-stop.html").writeText("<html><body>Controlled queue fixture</body></html>")
        view.page = browser.htmlPreviewUrl("queue-stop.html")
        withAgent(listOf(click(0), click(1), read(2))) { agent, operation ->
            pumpUntil { view.evaluations.size == 1 }
            assertEquals("The renderer call and queued sibling each own one ticket", 2,
                browser.retention.value.activeOperations)
            assertFalse(browser.setKeepActive(false))
            assertTrue(browser.setKeepActive(false, cancelActive = true))
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            val output = results(agent)
            assertEquals(3, output.size)
            assertStopped(output.getValue("call-0"))
            assertStopped(output.getValue("call-1"))
            assertTrue(output.getValue("call-2").contains("Independent file work completed"))
            assertEquals(1, view.evaluations.size)
            view.evaluations.single().onReceiveValue(JsonPrimitive("""{"ok":true}""").toString())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("A late callback cannot replay either click", 1, view.evaluations.size)
            assertEquals(0, browser.retention.value.activeOperations)
            assertFalse(browser.retention.value.stopping)
            assertFalse(agent.busy.value)
            assertTrue(caller.isActive)
        }
    }

    @Test(timeout = 30_000) fun stopDuringBrowserPreToolApprovalCancelsTheHookAndQueuedBrowserCallsOnly() {
        root.resolve(".droide").mkdirs()
        root.resolve(".droide/hooks.json").writeText(
            """{"enabled":true,"events":{"PreToolUse":[{"command":"echo before","matcher":"browser"}]}}""")
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW),
            PermRule("hook", "*", PermEffect.ASK)))
        withAgent(listOf(click(0), click(1), read(2)), permissions) { agent, operation ->
            pumpUntil { agent.approvals.pending.value?.action == "hook" }
            val request = requireNotNull(agent.approvals.pending.value)
            assertEquals(2, browser.retention.value.activeOperations)
            assertFalse(browser.setKeepActive(false))
            assertTrue(browser.setKeepActive(false, cancelActive = true))
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            assertNull(agent.approvals.pending.value)
            assertFalse(agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
            val output = results(agent)
            assertStopped(output.getValue("call-0"))
            assertStopped(output.getValue("call-1"))
            assertTrue(output.getValue("call-2").contains("Independent file work completed"))
            assertEquals(0, terminal.calls.get())
            assertEquals(0, view.evaluations.size)
            assertEquals(0, browser.retention.value.activeOperations)
        }
    }

    @Test(timeout = 30_000) fun stopOfOnlyQueuedBrowserWorkPreservesTheUnrelatedActivePermissionRequest() {
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW),
            PermRule("read", "*", PermEffect.ASK)))
        withAgent(listOf(read(0), click(1)), permissions) { agent, operation ->
            pumpUntil { agent.approvals.pending.value?.action == "read" }
            val request = requireNotNull(agent.approvals.pending.value)
            assertEquals(1, browser.retention.value.activeOperations)
            assertFalse(browser.setKeepActive(false))
            assertTrue(browser.setKeepActive(false, cancelActive = true))
            assertEquals(request.id, agent.approvals.pending.value?.id)
            assertFalse(operation.isCompleted)
            assertFalse(browser.retention.value.stopping)
            assertTrue(agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
            pumpUntil { operation.isCompleted }
            runBlocking { operation.await() }
            val output = results(agent)
            assertTrue(output.getValue("call-0").contains("Independent file work completed"))
            assertStopped(output.getValue("call-1"))
            assertEquals(0, view.evaluations.size)
            assertEquals(0, browser.retention.value.activeOperations)
        }
    }

    @Test(timeout = 30_000) fun fullAgentCancellationReleasesBrowserCallsThatNeverEnteredDispatch() {
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW),
            PermRule("read", "*", PermEffect.ASK)))
        withAgent(listOf(read(0), click(1), click(2)), permissions) { agent, operation ->
            pumpUntil { agent.approvals.pending.value?.action == "read" }
            assertEquals(2, browser.retention.value.activeOperations)
            operation.cancel(CancellationException("Stop the complete Agent run"))
            pumpUntil { operation.isCompleted }
            assertTrue(operation.isCancelled)
            assertNull(agent.approvals.pending.value)
            assertEquals(0, browser.retention.value.activeOperations)
            assertEquals(0, view.evaluations.size)
            assertTrue(browser.retention.value.keepActive)
        }
    }
}

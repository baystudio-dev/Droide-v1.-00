package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.ConsoleMessage
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

/** Controlled callbacks only. Authored by static review; never compiled or executed in this repair. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserObservationOwnershipTest {
    private val root = Files.createTempDirectory("browser-observation-owner-").toFile()
    private val controller = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))
    private val context = agentWorkflowContext(root, permissions = permissions, browserController = controller,
        provenance = AgentRequestProvenance.primary("observation-session"))
    private lateinit var view: SnapshotView
    private lateinit var binding: AgentBrowserController.Binding

    private class SnapshotView(context: Context) : WebView(context) {
        var page = "http://localhost:3000/source-a"
        var autoReply = true
        var onInteraction: (() -> Unit)? = null
        var interactionEvaluations = 0
        var navigationDispatches = 0
        override fun loadUrl(url: String) { navigationDispatches++; page = url }
        val callbacks = mutableListOf<ValueCallback<String>>()
        override fun getUrl() = page
        override fun getTitle() = "private-title-fixture"
        fun snapshot() = JsonPrimitive(buildJsonObject {
            put("ok", true); put("url", page); put("text", "private-body-fixture")
        }.toString()).toString()
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback ?: return
            callbacks += resultCallback
            if (script.contains("const t =")) {
                interactionEvaluations++
                onInteraction?.invoke()
                resultCallback.onReceiveValue(JsonPrimitive("{\"ok\":true,\"action\":\"fixture\"}").toString())
                return
            }
            if (autoReply) resultCallback.onReceiveValue(snapshot())
        }
    }

    @Before fun attach() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        controller.bindOpenRequest {}
        view = SnapshotView(RuntimeEnvironment.getApplication())
        controller.configure(view); binding = controller.attach(view)
    }

    @After fun cleanup() { caller.cancel(); view.destroy(); context.webResearch.close(); root.deleteRecursively() }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Observation fixture did not reach the expected state", condition())
    }

    private fun <T> completed(result: Deferred<T>): T {
        pumpUntil { result.isCompleted }
        return runBlocking { result.await() }
    }

    private fun navigate(url: String) {
        view.page = url
        view.webViewClient.onPageStarted(view, url, null)
        view.webViewClient.onPageFinished(view, url)
    }

    private fun observe(operation: String) = completed(caller.async {
        controller.executeObserved(operation, allowLoopback = true)
    })

    @Test fun capturedReadSourceCannotBeReplacedByLaterCurrentUrlBeforePublication() {
        val original = view.page
        val observation = observe("read")
        assertTrue(observation.payload.contains("private-body-fixture"))
        assertEquals(listOf(original), observation.sources.map { it.url })
        val reviewed = "http://localhost:3000/reviewed-c"
        navigate(reviewed)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", original, PermEffect.DENY))))
        val denied = completed(caller.async {
            AgentTools.browserObservationGate(observation, reviewed, true, context, "open")
        })
        assertNotNull(denied)
        assertTrue(denied!!.startsWith("DENIED"))
        assertFalse(denied.contains("private-body-fixture"))
        assertFalse(denied.contains("private-title-fixture"))
        assertEquals(reviewed, controller.currentUrl())
        assertEquals(original, observation.sources.single().url)
    }

    @Test fun clickAndTypeReturningADeniedRedirectPageCannotPublishItsBody() {
        val original = view.page
        val deniedPage = "http://localhost:3000/denied-result"
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", deniedPage, PermEffect.DENY))))
        for (operation in listOf("click", "type")) {
            navigate(original)
            view.onInteraction = { navigate(deniedPage) }
            val result = completed(caller.async {
                AgentTools.execute("browser", buildJsonObject {
                    put("operation", operation); put("target", "body"); put("text", "fixture")
                    put("wait_ms", 100)
                }, context)
            })
            assertTrue(result.startsWith("DENIED"))
            assertFalse(result.contains("private-body-fixture"))
            assertTrue(result.contains("do not replay"))
        }
        assertEquals(2, view.interactionEvaluations)
    }

    @Test fun statusAndConsoleEnvelopesRetainTheirCapturedDocumentAfterNavigation() {
        val original = view.page
        for (operation in listOf("status", "console")) {
            navigate(original)
            val observation = observe(operation)
            navigate("http://localhost:3001/different-origin")
            assertEquals(original, observation.sources.single().url)
            assertEquals(original, observation.sources.single().document.url)
            assertFalse(controller.isReviewIdentityCurrent(observation.sources.single().document))
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", original, PermEffect.DENY))))
            assertNotNull(completed(caller.async {
                AgentTools.browserObservationGate(observation, original, true, context, operation)
            }))
        }
    }

    @Test fun sameDocumentUrlChangeWithoutPageStartedCanReceiveAFreshReviewIdentity() {
        val original = completed(caller.async { controller.captureReviewIdentity() })
        view.page = "http://localhost:3000/spa-route"
        val fresh = completed(caller.async { controller.captureReviewIdentity() })
        assertEquals(view.page, fresh.url)
        assertEquals(view.page, controller.currentUrl())
        assertTrue(controller.isReviewIdentityCurrent(fresh))
        assertFalse(controller.isReviewIdentityCurrent(original))
        val result = completed(caller.async { AgentTools.execute("browser", buildJsonObject { put("operation", "status") }, context) })
        assertEquals(view.page, Json.parseToJsonElement(result).jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun lateSnapshotFromAnOldDocumentAtTheSameUrlIsRejected() {
        view.autoReply = false
        val result = caller.async { runCatching { controller.executeObserved("read", allowLoopback = true) } }
        pumpUntil { view.callbacks.size == 1 }
        val oldSnapshot = view.snapshot()
        navigate(view.page)
        view.callbacks.single().onReceiveValue(oldSnapshot)
        val failure = completed(result).exceptionOrNull()
        assertTrue("The callback must belong to the same document, not merely URL: $failure", failure is BrowserSurfaceChangedException)
        assertTrue(caller.isActive)
    }

    @Test fun deniedConsoleScriptSourceCannotInheritTopPageReadPermission() {
        val script = "https://frame.example.test/private-script.js"
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage("private-console-fixture", script, 1, ConsoleMessage.MessageLevel.LOG))
        val observation = observe("console")
        assertEquals(setOf(view.page, script), observation.sources.map { it.url }.toSet())
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", script, PermEffect.DENY))))
        val result = completed(caller.async {
            AgentTools.execute("browser", buildJsonObject { put("operation", "console") }, context)
        })
        assertTrue(result.startsWith("DENIED"))
        assertFalse(result.contains("private-console-fixture"))
    }

    @Test fun everyAgentConsoleOutputNeedsConsentEvenWhenSelectedDiagnosticSourcesAreKnown() {
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage("unknown-fixture", "fixture.js", 1, ConsoleMessage.MessageLevel.LOG))
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage("known-fixture", view.page, 2, ConsoleMessage.MessageLevel.LOG))
        val unknown = observe("console")
        assertTrue(unknown.hasUnknownSource)
        assertNotNull(completed(caller.async {
            AgentTools.browserObservationGate(unknown, view.page, true, context, "console")
        }))
        val known = completed(caller.async { controller.executeObserved("console", allowLoopback = true, start = 1, count = 1) })
        assertFalse(known.hasUnknownSource)
        assertEquals(listOf(view.page), known.sources.map { it.url })
        assertNotNull(completed(caller.async {
            AgentTools.browserObservationGate(known, view.page, true, context, "console")
        }))
    }

    @Test fun dialogAndCachedStatusRequireTheCapturedRequestingFrameSource() {
        val source = "https://frame.example.test/private"
        val native = BrowserDialogTestResults.alert()
        view.webChromeClient!!.onJsConfirm(view, source, "private-dialog-fixture", native)
        for (operation in listOf("dialog", "status")) {
            val observation = observe(operation)
            assertEquals(setOf(view.page, source), observation.sources.map { it.url }.toSet())
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", source, PermEffect.DENY))))
            val denied = completed(caller.async {
                AgentTools.browserObservationGate(observation, view.page, true, context, operation)
            })
            assertNotNull(denied)
            assertFalse(denied!!.contains("private-dialog-fixture"))
        }
        assertEquals(0, BrowserDialogTestResults.state(native).confirmations)
    }

    @Test fun unknownDialogSourceIsNotSubstitutedWithTheTopPage() {
        view.webChromeClient!!.onJsConfirm(view, "about:blank", "private-dialog-fixture", BrowserDialogTestResults.alert())
        val observation = observe("dialog")
        assertTrue(observation.hasUnknownSource)
        val denied = completed(caller.async {
            AgentTools.browserObservationGate(observation, view.page, true, context, "dialog")
        })
        assertTrue(denied!!.contains("unknown document source"))
        assertFalse(denied.contains("private-dialog-fixture"))
        assertNotNull(controller.pendingDialog.value)
    }

    @Test fun denyingAnEarlierSourceWhileAnotherSourceApprovalWaitsWithholdsTheWholeResult() {
        val original = view.page
        val frame = "https://frame.example.test/prompt"
        view.webChromeClient!!.onJsConfirm(view, frame, "private-dialog-fixture", BrowserDialogTestResults.alert())
        val observation = observe("dialog")
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", frame, PermEffect.ASK))))
        val gate = caller.async { AgentTools.browserObservationGate(observation, original, true, context, "dialog") }
        pumpUntil { context.approvals.pending.value != null }
        assertEquals(frame, context.approvals.pending.value!!.resource)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", original, PermEffect.DENY))))
        context.approvals.resolve(ApprovalResolution.ALLOW_ONCE)
        val denied = completed(gate)
        assertNotNull(denied)
        assertFalse(denied!!.contains("private-dialog-fixture"))
    }

    @Test fun deniedLaterConsoleSourcePreflightsBeforeAnEarlierSourceCanAsk() {
        val askSource = "https://ask.example.test/script.js"
        val denySource = "https://deny.example.test/script.js"
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage("first-private-line", askSource, 1, ConsoleMessage.MessageLevel.LOG))
        view.webChromeClient!!.onConsoleMessage(ConsoleMessage("second-private-line", denySource, 2, ConsoleMessage.MessageLevel.LOG))
        val observation = observe("console")
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("webfetch", askSource, PermEffect.ASK),
            PermRule("webfetch", denySource, PermEffect.DENY),
        )))
        val gate = caller.async { AgentTools.browserObservationGate(observation, view.page, true, context, "console") }
        pumpUntil { gate.isCompleted || context.approvals.pending.value != null }
        assertNull("The whole known source set must be checked before the first prompt", context.approvals.pending.value)
        assertTrue(gate.isCompleted)
        val denied = completed(gate)
        assertTrue(denied!!.startsWith("DENIED (policy): webfetch $denySource"))
        assertFalse(denied.contains("first-private-line"))
        assertFalse(denied.contains("second-private-line"))
    }

    @Test fun denyAddedDuringSourceReviewSuppressesAllRemainingSourcePrompts() {
        val first = "https://first.example.test/script.js"
        val second = "https://second.example.test/script.js"
        val last = "https://last.example.test/script.js"
        for (source in listOf(first, second, last)) {
            view.webChromeClient!!.onConsoleMessage(ConsoleMessage("private-output-fixture", source, 1, ConsoleMessage.MessageLevel.LOG))
        }
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(first, second, last).map {
            PermRule("webfetch", it, PermEffect.ASK)
        }))
        val gate = caller.async { AgentTools.execute("browser", buildJsonObject { put("operation", "console") }, context) }
        pumpUntil { context.approvals.pending.value != null }
        val request = requireNotNull(context.approvals.pending.value)
        assertEquals(first, request.resource)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("webfetch", first, PermEffect.ASK),
            PermRule("webfetch", second, PermEffect.ASK),
            PermRule("webfetch", last, PermEffect.DENY),
        )))
        assertTrue(context.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        pumpUntil { gate.isCompleted || context.approvals.pending.value != null }
        assertNull("A second ASK cannot precede the now-known third-source DENY", context.approvals.pending.value)
        assertTrue(gate.isCompleted)
        val denied = completed(gate)
        assertTrue(denied.startsWith("DENIED"))
        assertFalse(denied.contains("private-output-fixture"))
    }
    @Test fun denyWhileQueuedBehindRendererReadPreventsEveryInteractionDispatch() {
        for (action in listOf("webfetch", "browser_local", "browser_interact")) {
            val queuedContext = agentWorkflowContext(root, permissions = permissions, browserController = controller,
                provenance = AgentRequestProvenance.primary("queued-$action"))
            try {
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("*", "*", PermEffect.ALLOW))))
            view.autoReply = false
            val firstCallback = view.callbacks.size
            val blocker = caller.async { controller.executeObserved("read", allowLoopback = true) }
            pumpUntil { view.callbacks.size == firstCallback + 1 }
            val queued = caller.async {
                AgentTools.execute("browser", buildJsonObject {
                    put("operation", "click"); put("target", "#submit"); put("wait_ms", 100)
                }, queuedContext)
            }
            val executing = AgentBrowserController::class.java.getDeclaredField("activeOperations").apply { isAccessible = true }

            pumpUntil { executing.getInt(controller) == 2 }
            permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule(action, "*", PermEffect.DENY))))
            view.autoReply = true
            view.callbacks[firstCallback].onReceiveValue(view.snapshot())
            completed(blocker)
            val denied = completed(queued)
            assertTrue("$action: $denied", denied.startsWith("DENIED"))
            assertEquals("No queued mutation may run after $action DENY", 0, view.interactionEvaluations)
            assertEquals("No queued renderer read may run after $action DENY", firstCallback + 1, view.callbacks.size)
            } finally { queuedContext.webResearch.close() }
        }
    }

    @Test fun denyWhileWaitingForSurfaceMountPreventsNavigation() {
        controller.detach(binding, view)
        var requested = false
        controller.bindOpenRequest { requested = true }
        val operation = caller.async {
            AgentTools.execute("browser", buildJsonObject {
                put("operation", "open"); put("url", "http://localhost:3000/next"); put("wait_ms", 100)
            }, context)
        }
        pumpUntil { requested }
        assertFalse(operation.isCompleted)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", "*", PermEffect.DENY))))
        binding = controller.attach(view)
        val denied = completed(operation)
        assertTrue(denied.startsWith("DENIED"))
        assertEquals(0, view.navigationDispatches)
        assertTrue(view.callbacks.isEmpty())
    }

    @Test fun queuedBrowserPromptChecksWholeScopeWhenUnrelatedApprovalReleases() {
        val unrelated = caller.async {
            context.approvals.requestApproval("question", "Unrelated approval", "fixture")
        }
        pumpUntil { context.approvals.pending.value != null }
        val first = requireNotNull(context.approvals.pending.value)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("browser_interact", "*", PermEffect.ASK),
        )))
        val operation = caller.async {
            AgentTools.execute("browser", buildJsonObject {
                put("operation", "click"); put("target", "#submit")
            }, context)
        }
        // Capture/review is posted to main; drain it until the request queues on the unrelated prompt's
        // mutex without being made visible itself.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(first.id, context.approvals.pending.value!!.id)
        assertFalse(operation.isCompleted)
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(
            PermRule("browser_interact", "*", PermEffect.ASK),
            PermRule("browser_local", "*", PermEffect.DENY),
        )))
        context.approvals.resolve(first.id, ApprovalResolution.ALLOW_ONCE)
        completed(unrelated)
        val denied = completed(operation)
        assertTrue(denied.startsWith("DENIED"))
        assertNull(context.approvals.pending.value)
        assertEquals(0, view.interactionEvaluations)
    }

    @Test fun denyAfterMainDispatchIsPostedPreventsEvenSurfaceReveal() {
        var revealRequests = 0
        controller.bindOpenRequest { revealRequests++ }
        var allowed = true
        val operation = caller.async {
            runCatching {
                controller.executeObserved("open", url = "http://localhost:3000/next", allowLoopback = true,
                    validateAuthority = { check(allowed) { "request authority revoked" } })
            }
        }
        // Unconfined execution has admitted the call and posted requestSurface, but the paused main
        // looper has not dispatched its closure.
        assertFalse(operation.isCompleted)
        allowed = false
        val result = completed(operation)
        assertTrue(result.isFailure)
        assertEquals(0, revealRequests)
        assertEquals(0, view.navigationDispatches)
        assertTrue(view.callbacks.isEmpty())
    }

    @Test fun queuedHookApprovalResamplesSameDocumentUrlAfterTakingApprovalMutex() {
        val args = buildJsonObject { put("operation", "read") }
        val cached = controller.currentUrl()
        var sampled = view.page
        val unrelated = caller.async {
            context.approvals.requestApproval("question", "Unrelated prompt", "fixture")
        }
        pumpUntil { context.approvals.pending.value != null }
        val prior = requireNotNull(context.approvals.pending.value)
        val queued = caller.async {
            context.approvals.withBrowserValidity(
                check = { AgentTools.browserRequestPreflight("browser", args, context, sampled) == null },
                admission = {
                    sampled = controller.sampleCurrentUrlForPreflight()
                    AgentTools.browserRequestPreflight("browser", args, context, sampled) == null
                },
            ) {
                context.approvals.requestApproval("run_command", "Browser pre-hook", "fixture", action = "hook")
            }
        }
        assertFalse(queued.isCompleted)
        view.page = "http://localhost:3000/private-after-queue"
        permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("webfetch", view.page, PermEffect.DENY))))
        assertEquals("The fixture changes History API URL without a navigation callback", cached, controller.currentUrl())
        context.approvals.resolve(prior.id, ApprovalResolution.ALLOW_ONCE)
        completed(unrelated)
        assertEquals(ApprovalDecision.Denied, completed(queued))
        assertNull("The stale queued hook prompt must never become visible", context.approvals.pending.value)
        assertEquals(cached, controller.currentUrl())
        assertEquals(0, view.navigationDispatches)
        assertTrue(view.callbacks.isEmpty())
    }

}

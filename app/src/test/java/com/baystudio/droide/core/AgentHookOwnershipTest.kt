package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Finite literal-fake controls. No method delegates to an execution backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentHookOwnershipTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 25_000) fun currentStartupAndSessionApprovalStillDispatch() = serviceControl("current")
    @Test(timeout = 25_000) fun shutdownDuringHookRejectsItsContinuation() = serviceControl("shutdown")
    @Test(timeout = 25_000) fun modeChangeDuringHookDoesNotBorrowNewScope() = serviceControl("mode")

    @Test(timeout = 15_000) fun preToolRevocationPreventsToolBody() = withEnv {
        var bodies = 0
        terminal.handler = { current.set(false); ExecResult(0, "{}", false) }
        val result = runCatching { bridge.tool("read_file", "{}", owner = owner) { bodies++; "read" } }
        assertEquals(0, bodies)
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(listOf("PreToolUse"), events())
    }

    @Test(timeout = 15_000) fun toolBodyRevocationPreventsPostHook() = withEnv {
        val result = runCatching { bridge.tool("read_file", "{}", owner = owner) { current.set(false); "read" } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(listOf("PreToolUse"), events())
    }

    @Test(timeout = 15_000) fun revokedFailureHookPreservesOriginalFailure() = withEnv {
        val failure = FixtureIOException("original tool failure")
        val result = runCatching { bridge.tool("read_file", "{}", owner = owner) { current.set(false); throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(failure.suppressed.any { it is CancellationException })
        assertEquals(listOf("PreToolUse"), events())
    }

    @Test(timeout = 15_000) fun fatalToolFailureDoesNotStartAnotherHook() = withEnv {
        val failure = AssertionError("deliberate fatal fixture")
        val result = runCatching { bridge.tool("read_file", "{}", owner = owner) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf("PreToolUse"), events())
    }

    @Test(timeout = 15_000) fun compactionActionRevocationPreventsPostHook() = withEnv {
        var actions = 0
        val result = runCatching { bridge.compact("manual", owner) { actions++; current.set(false); true } }
        assertEquals(1, actions)
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(listOf("PreCompact"), events())
    }

    @Test(timeout = 15_000) fun currentCompactionAndStopKeepTheirOwner() = withEnv {
        assertTrue(bridge.compact("auto", owner) { true }.changed)
        assertFalse(bridge.stop(owner).blocked)
        assertEquals(listOf("PreCompact", "PostCompact", "Stop"), events())
        assertTrue(terminal.events.all { it["sessionId"] == JsonPrimitive("old-session") })
    }

    @Test(timeout = 15_000) fun capturedContextAndProvenanceSurviveMutableSuppliers() = withEnv {
        terminal.handler = {
            suppliedSession = "replacement-session"
            suppliedMode = AgentMode.PLAN
            suppliedProvenance = AgentRequestProvenance.primary("replacement-session")
            ExecResult(0, "{}", false)
        }
        bridge.beforePrompt(true, null, "old prompt", owner)
        assertEquals(listOf("SessionStart", "UserPromptSubmit"), events())
        terminal.events.forEach {
            assertEquals(JsonPrimitive("old-session"), it["sessionId"])
            assertEquals(JsonPrimitive("build"), it["mode"])
            assertEquals(JsonPrimitive("old-task"), it["taskId"])
            assertEquals(JsonPrimitive("parent-session"), it["parentSessionId"])
        }
    }

    @Test(timeout = 15_000) fun browserCheckRemainsComposedWithoutHandlers() = withEnv {
        root.resolve(".droide/hooks.json").writeText("{}")
        var browserChecks = 0
        var bodies = 0
        val result = runCatching {
            bridge.tool("browser", "{}", beforeDispatch = {
                browserChecks++
                throw BrowserPublicationRevokedException("fixture browser revoked")
            }, owner = owner) { bodies++; "must not execute" }
        }
        assertTrue(result.exceptionOrNull() is BrowserPublicationRevokedException)
        assertEquals(1, browserChecks)
        assertEquals(0, bodies)
        assertTrue(terminal.events.isEmpty())
    }

    @Test(timeout = 15_000) fun approvalKeepsCapturedProvenanceAndScope() = withEnv(ask = true) { coroutineScope {
        val work = async { bridge.sessionStart("startup", owner) }
        try {
            val pending = requireNotNull(approvals.pending.first { it != null })
            suppliedSession = "replacement-session"
            suppliedMode = AgentMode.PLAN
            suppliedProvenance = AgentRequestProvenance.primary("replacement-session")
            assertEquals(owner.provenance, pending.provenance)
            assertEquals("old-agent", pending.permissionScope)
            assertTrue(approvals.resolve(pending.id, ApprovalResolution.ALLOW_SESSION))
            work.await()
            assertTrue(approvals.hasSessionApproval("old-session", "old-agent", pending.sessionGrantAction, pending.sessionGrantResource))
            assertFalse(approvals.hasSessionApproval("replacement-session", "plan", pending.sessionGrantAction, pending.sessionGrantResource))
            assertEquals(listOf("SessionStart"), events())
        } finally {
            approvals.resolve(ApprovalResolution.DENY)
            withContext(NonCancellable) { withTimeout(3_000) { work.cancelAndJoin() } }
        }
    } }

    private fun serviceControl(kind: String) {
        val root = Files.createTempDirectory("droide-hook-control-").toFile()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val env = Env(root, ask = kind == "current")
        env.terminal.handler = { event ->
            if (event["event"] == JsonPrimitive("SessionStart") && kind != "current") {
                reached.complete(Unit); release.await()
            }
            ExecResult(0, if (event["event"] == JsonPrimitive("UserPromptSubmit"))
                """{"decision":"block","reason":"fixture stops before provider"}""" else "{}", false)
        }
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                provider.response = { _, _, _ -> throw AssertionError("No provider request is permitted") }
                val agent = AgentService(FileRepository(root), env.terminal, GitManager(root),
                    approvals = env.approvals, perms = env.permissions, maxSubagentDepth = 0)
                try { runBlocking { withTimeout(12_000) {
                    val work = launch(Dispatchers.Default) { agent.chat("Hook control", provider.config, maxTurns = 1) }
                    try {
                        val pending = if (kind == "current") env.approvals.pending.first { it != null } else {
                            reached.await(); null
                        }
                        val session = requireNotNull(agent.currentSessionId)
                        if (pending != null) {
                            assertEquals(session, pending.provenance.sessionId)
                            assertEquals("build", pending.permissionScope)
                            assertTrue(env.approvals.resolve(pending.id, ApprovalResolution.ALLOW_SESSION))
                        } else {
                            if (kind == "shutdown") agent.shutdown() else agent.setMode(AgentMode.PLAN)
                            release.complete(Unit)
                        }
                        work.join()
                        assertEquals(0, provider.requests.size)
                        assertEquals(0, env.terminal.forbiddenCalls.get())
                        assertEquals(session, agent.currentSessionId)
                        assertEquals(if (kind == "current") listOf("SessionStart", "UserPromptSubmit") else listOf("SessionStart"), env.events())
                        assertTrue(env.terminal.events.all { it["sessionId"] == JsonPrimitive(session) && it["mode"] == JsonPrimitive("build") })
                        if (pending != null) assertTrue(env.approvals.hasSessionApproval(session, "build",
                            pending.sessionGrantAction, pending.sessionGrantResource))
                    } finally {
                        release.complete(Unit); env.approvals.resolve(ApprovalResolution.DENY)
                        withContext(NonCancellable) { withTimeout(3_000) { work.cancelAndJoin() } }
                    }
                } } } finally { release.complete(Unit); agent.shutdown() }
            }
        } finally { release.complete(Unit); PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun withEnv(ask: Boolean = false, block: suspend Env.() -> Unit) {
        val root = Files.createTempDirectory("droide-hook-pure-").toFile()
        val env = Env(root, ask)
        try {
            runBlocking { withTimeout(8_000) { env.block() } }
            assertEquals(0, env.terminal.forbiddenCalls.get())
        } finally { env.approvals.resolve(ApprovalResolution.DENY); PathSecurity.deleteTreeNoFollow(root) }
    }

    private class Env(val root: File, ask: Boolean) {
        val current = AtomicBoolean(true)
        var suppliedSession = "old-session"
        var suppliedMode = AgentMode.BUILD
        var suppliedProvenance = AgentExecutionIdentity.subagent("old-agent", "old-task", "parent-session", 1).request("old-session")
        val owner = HookInvocationAuthority(AgentMode.BUILD, suppliedProvenance, "old-agent", current::get)
        val approvals = ApprovalManager()
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)) +
            if (ask) listOf(PermRule("hook", "workspace:SessionStart:*", PermEffect.ASK)) else emptyList())
        val terminal = LiteralTerminal(root)
        val manager = HookManager(root, terminal, permissions, approvals,
            provenanceProvider = { suppliedProvenance }, permissionScopeProvider = { suppliedMode.name.lowercase() })
        val bridge = AgentHookLifecycleBridge(manager, { suppliedSession }, { suppliedMode })
        init {
            root.resolve(".droide").mkdirs()
            root.resolve(".droide/hooks.json").writeText(buildJsonObject {
                put("enabled", true)
                put("events", buildJsonObject { HookLifecycleEvent.entries.forEach { event ->
                    put(event.configKey, buildJsonArray { add(buildJsonObject { put("command", "owned-hook-fixture {event_file}") }) })
                } })
            }.toString())
        }
        fun events() = terminal.events.map { it["event"]!!.jsonPrimitive.content }
    }

    private class LiteralTerminal(private val root: File) : ITerminalSession {
        override val output = MutableStateFlow("")
        val events = CopyOnWriteArrayList<JsonObject>()
        val forbiddenCalls = AtomicInteger()
        var handler: suspend (JsonObject) -> ExecResult = { ExecResult(0, "{}", false) }
        private fun forbidden(): Nothing {
            forbiddenCalls.incrementAndGet(); throw AssertionError("Real backend execution is forbidden")
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            val match = Regex("^owned-hook-fixture '([^']+)'$").matchEntire(cmd) ?: forbidden()
            val file = File(match.groupValues[1])
            if (file.parentFile != root.resolve(".droide/.runtime-hooks") || !file.name.matches(Regex("event-[0-9a-f-]+\\.json")) ||
                !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() !in 1L..8_192L) forbidden()
            val event = Json.parseToJsonElement(file.readText()).jsonObject
            if (HookLifecycleEvent.fromConfigKey(event["event"]!!.jsonPrimitive.content) == null) forbidden()
            events += event
            return handler(event)
        }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class FixtureIOException(message: String) : IOException(message), CopyableThrowable<FixtureIOException> {
        override fun createCopy(): FixtureIOException? = null
    }
}

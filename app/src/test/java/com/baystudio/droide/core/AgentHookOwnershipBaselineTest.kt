package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.CopyOnWriteArrayList
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

/** Current API only. Literal hook markers are interpreted in memory, never executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentHookOwnershipBaselineTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun releasedOldSessionStartCannotDispatchPromptHookForReplacementSession() = scenario(ask = false)

    @Test(timeout = 30_000)
    fun oldPendingHookApprovalCannotGrantOrDispatchAfterReplacementLoad() = scenario(ask = true)

    private data class Visible(val session: String?, val stream: AgentStreamState, val busy: Boolean,
        val messages: List<AgentMessage>, val api: List<JsonObject>)

    private fun visible(agent: AgentService) = Visible(agent.currentSessionId, agent.streamState.value,
        agent.busy.value, agent.messages.value.toList(), agent.boundedApiHistory())

    private fun scenario(ask: Boolean) {
        val root = Files.createTempDirectory("droide-hook-owner-").toFile()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminal = LiteralHookTerminal(root, reached, release, holdStart = !ask)
        try {
            root.resolve(".droide").mkdirs()
            root.resolve(".droide/hooks.json").writeText("""{"enabled":true,"events":{"SessionStart":[{"command":"ownership-fixture {event_file}"}],"UserPromptSubmit":[{"command":"ownership-fixture {event_file}"}]}}""")
            // Registration/certification use the existing bounded loopback helper. No
            // request is expected: the fake prompt hook blocks before provider work.
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.response = { _, _, _ -> throw AssertionError("No provider request is allowed") }
                val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)) +
                    if (ask) listOf(PermRule("hook", "workspace:SessionStart:*", PermEffect.ASK)) else emptyList())
                val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                    perms = permissions, maxSubagentDepth = 0)
                try { runBlocking { withTimeout(15_000) {
                    val oldRun = launch(Dispatchers.Default) { agent.chat("Old hook task", provider.config, maxTurns = 1) }
                    try {
                        val pending = if (ask) agent.approvals.pending.first { it?.action == "hook" } else {
                            reached.await(); null
                        }
                        val oldSession = requireNotNull(agent.currentSessionId)
                        if (pending != null) {
                            assertEquals(oldSession, pending.provenance.sessionId)
                            assertEquals(oldSession, pending.approvalSessionId)
                            assertEquals("build", pending.permissionScope)
                            assertTrue(pending.canAllowForSession)
                            assertTrue(terminal.events.isEmpty())
                        } else {
                            assertEquals(1, terminal.events.size)
                            assertEquals("SessionStart", terminal.events.single()["event"]?.jsonPrimitive?.content)
                            assertEquals(oldSession, terminal.events.single()["sessionId"]?.jsonPrimitive?.content)
                            assertEquals("build", terminal.events.single()["mode"]?.jsonPrimitive?.content)
                        }
                        val sentinel = buildJsonObject { put("role", "user"); put("content", "replacement sentinel") }
                        val replacement = DroideSession("s-hook-replacement", "Replacement",
                            messages = listOf(AgentMessage("user", "replacement sentinel")),
                            apiHistory = listOf(sentinel), forkHistory = listOf(sentinel),
                            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION, mode = AgentMode.PLAN)
                        agent.sessions.save(replacement)
                        agent.loadSession(replacement)
                        val before = visible(agent)
                        val saved = root.resolve(".droide/sessions/${replacement.id}.json")
                        val bytes = saved.readBytes()
                        assertEquals(replacement.id, before.session)
                        assertEquals(listOf(sentinel), before.api)
                        assertEquals(AgentMode.PLAN, agent.mode.value)
                        assertFalse(before.busy)
                        if (pending != null) agent.approvals.resolve(pending.id, ApprovalResolution.ALLOW_SESSION)
                        else release.complete(Unit)
                        oldRun.join()
                        assertEquals("No real backend execution is permitted", 0, terminal.forbiddenCalls.get())
                        assertEquals("Hooks must stop this run before provider work", 0, provider.requests.size)
                        assertNull(agent.approvals.pending.value)
                        assertEquals(before, visible(agent))
                        assertArrayEquals(bytes, saved.readBytes())
                        if (pending != null) {
                            assertFalse("Revoked hook approval must not install a session grant",
                                agent.approvals.hasSessionApproval(oldSession, pending.permissionScope,
                                    pending.sessionGrantAction, pending.sessionGrantResource))
                            assertTrue("A revoked approval must never dispatch its hook", terminal.events.isEmpty())
                        } else {
                            assertEquals("An old SessionStart result must not dispatch another lifecycle event",
                                listOf("SessionStart"), terminal.events.map { it["event"]!!.jsonPrimitive.content })
                        }
                    } finally {
                        release.complete(Unit)
                        agent.approvals.resolve(ApprovalResolution.DENY)
                        withContext(NonCancellable) { withTimeout(5_000) { oldRun.cancelAndJoin() } }
                    }
                } } } finally { release.complete(Unit); agent.shutdown() }
                assertEquals(0, terminal.forbiddenCalls.get())
            }
        } finally { release.complete(Unit); PathSecurity.deleteTreeNoFollow(root) }
    }

    private class LiteralHookTerminal(private val root: File, private val reached: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>, private val holdStart: Boolean) : ITerminalSession {
        override val output = MutableStateFlow("")
        val events = CopyOnWriteArrayList<JsonObject>()
        val forbiddenCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            forbiddenCalls.incrementAndGet()
            throw AssertionError("Only a literal in-memory hook fixture is allowed; no process backend exists")
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            val match = Regex("^ownership-fixture '([^']+)'$").matchEntire(cmd) ?: forbidden()
            val file = File(match.groupValues[1])
            if (file.parentFile != root.resolve(".droide/.runtime-hooks") || !file.name.matches(Regex("event-[0-9a-f-]+\\.json")) ||
                !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() !in 1L..8_192L) forbidden()
            val event = Json.parseToJsonElement(file.readText()).jsonObject
            val kind = event["event"]?.jsonPrimitive?.content
            if (kind !in setOf("SessionStart", "UserPromptSubmit")) forbidden()
            events += event
            if (kind == "SessionStart") {
                if (holdStart) { reached.complete(Unit); release.await() }
                return ExecResult(0, "{}", false)
            }
            return ExecResult(0, """{"decision":"block","reason":"fixture stops before provider"}""", false)
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
}

package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ordinary write ASK; the only network is a bounded synthetic loopback response. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolApprovalOwnerBaselineTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun revokedWriteApprovalCannotMutateOrGrantForTheNextSameSessionRun() {
        val root = Files.createTempDirectory("droide-tool-approval-owner-").toFile()
        val terminal = NoProcessTerminal()
        val release = CountDownLatch(1)
        val sampled = CompletableDeferred<Unit>()
        val checks = AtomicInteger()
        val timedOut = AtomicBoolean(false)
        try {
            val target = root.resolve("reviewed.txt").apply { writeText("original text") }
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.response = { index, _, streaming ->
                    assertEquals(1, index); assertTrue(streaming)
                    val calls = buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("id", "write-reviewed"); put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "write_file"); put("arguments", buildJsonObject {
                                put("path", "reviewed.txt"); put("content", "obsolete requested text")
                            }.toString())
                        })
                    }) }
                    provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                }
                val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0,
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW),
                        PermRule("edit", "reviewed.txt", PermEffect.ASK))))
                try { runBlocking { withTimeout(15_000) {
                    val work = async(Dispatchers.Default) { runCatching {
                        agent.chat("Write reviewed.txt with the requested text", provider.config, maxTurns = 2)
                    } }
                    var resolver: Deferred<Boolean>? = null
                    var reuse: Deferred<ApprovalDecision>? = null
                    try {
                        val request = requireNotNull(agent.approvals.pending.first { it?.action == "edit" })
                        val sessionId = requireNotNull(agent.currentSessionId)
                        assertEquals("write_file", request.kind)
                        assertEquals("reviewed.txt", request.resource)
                        assertEquals(sessionId, request.approvalSessionId)
                        assertEquals("build", request.permissionScope)
                        assertTrue(request.canAllowForSession)
                        assertEquals("original text", target.readText())
                        val stateLock = ApprovalManager::class.java.getDeclaredField("stateLock")
                            .apply { isAccessible = true }.get(agent.approvals)
                        @Suppress("UNCHECKED_CAST")
                        val validity = ApprovalManager::class.java.getDeclaredField("validityChecks")
                            .apply { isAccessible = true }.get(agent.approvals) as MutableMap<Long, () -> Boolean>
                        synchronized(stateLock) {
                            val original = requireNotNull(validity[request.id])
                            validity[request.id] = {
                                val result = original()
                                assertTrue(result); checks.incrementAndGet(); sampled.complete(Unit)
                                val opened = release.await(5, TimeUnit.SECONDS)
                                if (!opened) timedOut.set(true)
                                check(opened) { "Fixture approval gate timed out" }
                                result
                            }
                        }
                        resolver = async(Dispatchers.Default) { agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION) }
                        sampled.await()
                        val api = listOf(buildJsonObject { put("role", "user"); put("content", "replacement sentinel") },
                            buildJsonObject { put("role", "assistant"); put("content", "replacement answer") })
                        val replacement = requireNotNull(agent.sessions.get(sessionId)).copy(apiHistory = api,
                            forkHistory = api, forkHistoryPages = emptyList(), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION,
                            messages = listOf(AgentMessage("user", "replacement sentinel"), AgentMessage("assistant", "replacement answer")))
                        agent.sessions.save(replacement); agent.loadSession(replacement)
                        val expected = visible(agent)
                        val savedFile = root.resolve(".droide/sessions/$sessionId.json")
                        val savedBytes = savedFile.readBytes()
                        release.countDown()
                        assertTrue(requireNotNull(resolver).await())
                        val outcome = work.await()

                        assertEquals(1, checks.get())
                        assertFalse("Fixture timeout is not product evidence", timedOut.get())
                        assertEquals(1, provider.requests.size)
                        assertTrue("write_file" in AgentRuntimePrompt.toolNames(provider.requests.single()["tools"] as JsonArray))
                        assertEquals(0, terminal.executionCalls.get())
                        assertTrue(outcome.exceptionOrNull() is CancellationException)
                        assertEquals(expected, visible(agent))
                        assertArrayEquals(savedBytes, savedFile.readBytes())
                        assertNull(agent.approvals.pending.value)
                        val granted = agent.approvals.hasSessionApproval(sessionId, request.permissionScope,
                            request.sessionGrantAction, request.sessionGrantResource)
                        assertEquals("A revoked tool approval must neither publish bytes nor retain authority",
                            "original text" to false, target.readText() to granted)
                        reuse = async(start = CoroutineStart.UNDISPATCHED) {
                            agent.approvals.requestApproval("write_file", "next reviewed edit", "fixture",
                                action = request.action, resource = request.resource, permissionScope = request.permissionScope,
                                provenance = AgentRequestProvenance.primary(sessionId))
                        }
                        assertFalse("The next same-session edit must still ask", requireNotNull(reuse).isCompleted)
                        val next = requireNotNull(agent.approvals.pending.value)
                        assertTrue(next.id > request.id)
                        assertTrue(agent.approvals.resolve(next.id, ApprovalResolution.DENY))
                        assertSame(ApprovalDecision.Denied, requireNotNull(reuse).await())
                    } finally {
                        release.countDown(); agent.approvals.resolve(ApprovalResolution.DENY)
                        withContext(NonCancellable) { withTimeout(5_000) {
                            reuse?.cancelAndJoin(); resolver?.cancelAndJoin(); work.cancelAndJoin()
                        } }
                    }
                } } } finally { release.countDown(); agent.shutdown() }
            }
        } finally { release.countDown(); PathSecurity.deleteTreeNoFollow(root) }
    }

    private data class Visible(val session: String?, val stream: AgentStreamState, val busy: Boolean,
        val mode: AgentMode, val messages: List<AgentMessage>, val api: List<JsonObject>, val error: String?)
    private fun visible(agent: AgentService) = Visible(agent.currentSessionId, agent.streamState.value,
        agent.busy.value, agent.mode.value, agent.messages.value.toList(), agent.boundedApiHistory(), agent.persistenceError.value)
    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing { executionCalls.incrementAndGet(); throw AssertionError("No execution backend is permitted") }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}

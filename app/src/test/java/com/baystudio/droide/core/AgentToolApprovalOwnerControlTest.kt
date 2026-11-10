package com.baystudio.droide.core

import java.nio.file.Files
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolApprovalOwnerControlTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun currentOrdinaryWriteApprovalStillPublishesAndKeepsItsSessionGrant() {
        val root = Files.createTempDirectory("droide-current-tool-approval-").toFile()
        val terminal = NoProcessTerminal()
        try {
            val target = root.resolve("reviewed.txt").apply { writeText("original text") }
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.response = { index, _, streaming ->
                    assertTrue(streaming)
                    if (index == 1) {
                        val calls = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "current-write"); put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "write_file"); put("arguments", buildJsonObject {
                                    put("path", "reviewed.txt"); put("content", "approved text")
                                }.toString())
                            })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else {
                        assertEquals(2, index)
                        provider.event("""{"choices":[{"index":0,"delta":{"content":"Write complete"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                    }
                }
                val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0,
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW),
                        PermRule("edit", "reviewed.txt", PermEffect.ASK))))
                try { runBlocking { withTimeout(15_000) {
                    val work = async(Dispatchers.Default) { agent.chat("Write reviewed.txt with approved text", provider.config, maxTurns = 2) }
                    try {
                        val request = requireNotNull(agent.approvals.pending.first { it?.action == "edit" })
                        val session = requireNotNull(agent.currentSessionId)
                        assertEquals("original text", target.readText())
                        assertEquals(session, request.approvalSessionId)
                        assertEquals("build", request.permissionScope)
                        assertEquals("reviewed.txt", request.resource)
                        assertTrue(agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
                        work.await()
                        assertEquals(2, provider.requests.size)
                        assertTrue("write_file" in AgentRuntimePrompt.toolNames(provider.requests.first()["tools"] as JsonArray))
                        assertEquals(0, terminal.executionCalls.get())
                        assertEquals(session, agent.currentSessionId)
                        assertEquals("approved text", target.readText())
                        assertNull(agent.approvals.pending.value)
                        assertTrue(agent.approvals.hasSessionApproval(session, "build", "edit", "reviewed.txt"))
                        val saved = requireNotNull(agent.sessions.get(session))
                        assertTrue(saved.apiHistory.any { it["role"] == JsonPrimitive("tool") &&
                            it["tool_call_id"] == JsonPrimitive("current-write") })
                        assertFalse(agent.busy.value)
                    } finally {
                        agent.approvals.resolve(ApprovalResolution.DENY)
                        withContext(NonCancellable) { withTimeout(5_000) { work.cancelAndJoin() } }
                    }
                } } } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
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

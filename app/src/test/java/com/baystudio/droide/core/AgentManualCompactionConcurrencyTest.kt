package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentManualCompactionConcurrencyTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun manualCompactionCannotCancelOrRewindAnAlreadyRunningTask() = fixture { agent, http, _ -> runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        http.response = { _, _, _ ->
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            http.event("""{"choices":[{"index":0,"delta":{"content":"Actual task finished"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
        }
        val task = async(Dispatchers.Default) { agent.chat("Explain this", http.config) }
        try {
            withTimeout(5_000) { entered.await(); agent.busy.first { it } }
            val sessionId = agent.currentSessionId
            val result = agent.compactCurrent(http.config)
            assertTrue(result.contains("Finish the running Agent task"))
            assertEquals(1, http.requests.size)
            assertTrue(agent.busy.value)
            assertEquals(sessionId, agent.currentSessionId)
            release.countDown(); task.await()
            assertTrue(agent.messages.value.any { it.content == "Actual task finished" })
        } finally { release.countDown(); task.cancelAndJoin() }
    } }

    @Test(timeout = 30_000) fun clearingWhileCompactionProviderRunsCannotCommitItsOldSnapshotToDisk() = fixture { agent, http, root -> runBlocking {
        val history = listOf(message("user", "old historical constraints ".repeat(7_500)),
            message("assistant", "old answer"), message("user", "review only"))
        val session = agent.sessions.create().copy(apiHistory = history, forkHistory = history,
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION,
            messages = listOf(AgentMessage("user", "Review only")))
        agent.sessions.save(session); agent.loadSession(session)
        ModelContextRegistry.remember(http.provider.id, http.model, 100_000)
        val file = File(root, ".droide/sessions/${session.id}.json")
        val original = file.readBytes()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        http.response = { _, _, streaming ->
            assertFalse(streaming)
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("message", message("assistant", "Historical checkpoint")); put("finish_reason", "stop")
            }) }) }.toString()
        }
        val compact = async(Dispatchers.Default) { runCatching { agent.compactCurrent(http.config) } }
        try {
            withTimeout(5_000) { entered.await() }
            agent.clear()
            release.countDown()
            assertTrue(compact.await().exceptionOrNull() is CancellationException)
            assertArrayEquals(original, file.readBytes())
            assertNull(agent.currentSessionId)
            assertTrue(agent.messages.value.isEmpty())
            assertEquals(1, http.requests.size)
        } finally { release.countDown(); compact.cancelAndJoin(); ModelContextRegistry.forget(http.provider.id) }
    } }

    private fun fixture(block: (AgentService, ProviderStreamFixture, File) -> Unit) {
        val root = Files.createTempDirectory("droide-manual-compact-owner-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                try { block(agent, http, root) } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
}

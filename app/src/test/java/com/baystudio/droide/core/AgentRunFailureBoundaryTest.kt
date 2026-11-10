package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRunFailureBoundaryTest {
    @org.junit.Before fun openAuthoredUserLaunch() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    private fun fixture(block: suspend (AgentService, ProviderStreamFixture, CoroutineScope, java.io.File, List<Throwable>) -> Unit) {
        val root = Files.createTempDirectory("droide-agent-run-boundary-").toFile()
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
            runBlocking {
                withTimeout(20_000) {
                    val escaped = CopyOnWriteArrayList<Throwable>()
                    val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]) + CoroutineExceptionHandler { _, error -> escaped += error })
                    val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                    provider.response = { _, _, _ -> provider.event("""{"choices":[{"index":0,"delta":{"content":"Recovered answer"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n" }
                    try { block(agent, provider, scope, root, escaped) }
                    finally { agent.shutdown(); scope.coroutineContext.job.cancelAndJoin() }
                }
            }
        }
        PathSecurity.deleteTreeNoFollow(root)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(8_000) {
        while (!condition()) delay(5)
    }

    private suspend fun recovered(agent: AgentService, provider: ProviderStreamFixture, scope: CoroutineScope) {
        assertNotNull(agent.submitAsync(scope, "Explain this project", provider.config, AgentPromptDelivery.STEER))
        awaitCondition { agent.messages.value.any { it.content.contains("Recovered answer") || it.streamPart?.text?.contains("Recovered answer") == true } && !agent.busy.value }
    }

    @Test fun providerBindingFailureBeforeChatTryIsReportedAndNextRunCanProceed() = fixture { agent, provider, scope, _, escaped ->
        assertNotNull(agent.submitAsync(scope, "Explain", provider.config.copy(baseUrl = "https://example.invalid/v1"), AgentPromptDelivery.STEER))
        awaitCondition { agent.persistenceError.value != null }
        assertFalse(agent.busy.value)
        assertTrue(provider.requests.isEmpty())
        recovered(agent, provider, scope)
        assertTrue("No exception may escape the supervised Agent run: $escaped", escaped.isEmpty())
    }

    @Test fun stopAtAdmissionBeforeRunDispatchReleasesStartingLeaseForRetry() = fixture { agent, provider, scope, _, escaped ->
        val admission = agent.submitAsync(scope, "First request", provider.config, AgentPromptDelivery.STEER,
            onAccepted = { agent.cancel() })
        requireNotNull(admission).join()
        assertTrue(provider.requests.isEmpty())
        assertNull(agent.currentSessionId)
        recovered(agent, provider, scope)
        assertEquals(1, provider.requests.size)
        assertTrue(escaped.isEmpty())
    }

    @Test fun unwritableSessionDirectoryDoesNotCrashTheSupervisorOrLeaveAdmissionBusy() = fixture { agent, provider, scope, root, escaped ->
        val sessions = root.resolve(".droide/sessions")
        sessions.parentFile.mkdirs()
        sessions.writeText("existing user data; must not be removed by recovery")
        assertNotNull(agent.submitAsync(scope, "Explain", provider.config, AgentPromptDelivery.STEER))
        awaitCondition { agent.persistenceError.value?.contains("session") == true }
        assertEquals("existing user data; must not be removed by recovery", sessions.readText())
        assertFalse(agent.busy.value)
        assertTrue(escaped.isEmpty())
        // Fixture repairs its own obstacle; the product must never delete it.
        sessions.delete()
        recovered(agent, provider, scope)
        assertTrue(escaped.isEmpty())
    }

    @Test fun stopWhileAwaitingWriteApprovalPreservesFilesAndCanContinueSameSession() = fixture { agent, provider, scope, root, escaped ->
        val original = root.resolve("keep.txt").apply { writeText("user source") }
        provider.response = { call, _, _ -> if (call == 1) {
            val arguments = buildJsonObject { put("path", "keep.txt"); put("content", "unapproved overwrite") }
            val function = buildJsonObject { put("name", "write_file"); put("arguments", arguments.toString()) }
            val tool = buildJsonObject { put("index", 0); put("id", "approval-call"); put("type", "function"); put("function", function) }
            provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":[$tool]},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
        } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Recovered answer"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n" }
        assertNotNull(agent.submitAsync(scope, "Edit keep.txt", provider.config, AgentPromptDelivery.STEER))
        awaitCondition { agent.approvals.pending.value != null }
        val session = agent.currentSessionId
        agent.cancel(); agent.cancel()
        awaitCondition { !agent.busy.value && agent.approvals.pending.value == null }
        assertEquals("user source", original.readText())
        val saved = requireNotNull(agent.sessions.get(requireNotNull(session)))
        assertEquals(1, saved.apiHistory.count { it["tool_call_id"] == JsonPrimitive("approval-call") })
        assertTrue(saved.messages.any { it.content == "Stopped by user." })
        recovered(agent, provider, scope)
        assertEquals(session, agent.currentSessionId)
        assertTrue("Stop must not escape as a crash: $escaped", escaped.isEmpty())
    }
}

package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AgentReusableWorkflowTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private fun answer(fixture: ProviderStreamFixture, text: String = "Done") =
        fixture.event("""{"choices":[{"delta":{"content":"$text"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"

    private fun tool(fixture: ProviderStreamFixture, name: String, args: JsonObject, finish: Boolean = true): String {
        val delta = buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", "call-$name"); put("type", "function")
            put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
        }) }) }
        return fixture.event("""{"choices":[{"delta":$delta${if (finish) ",\"finish_reason\":\"tool_calls\"" else ""}}]}""") +
            if (finish) "data: [DONE]\n\n" else ""
    }

    @Test(timeout = 30_000) fun chosenWorkflowLoadsOnlyOnDemandAndDoesNotCreateAnExtraRun() = runBlocking {
        val root = Files.createTempDirectory("workflow-skill-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
            fixture.response = { call, _, _ -> if (call == 1) tool(fixture, "skill", buildJsonObject { put("name", "workflow-repair") }) else answer(fixture) }
            try {
                runBlocking { agent.chat("@skill/workflow-repair Fix the reported parser failure", fixture.config, maxTurns = 2) }
                assertEquals(2, fixture.requests.size)
                assertFalse(fixture.requests.first().toString().contains("Allow at most two repair-and-check"))
                assertTrue(fixture.requests.last().toString().contains("Allow at most two repair-and-check"))
                assertEquals(1, runBlocking { agent.sessions.count() })
                assertEquals(AgentContinuation.READY, agent.continuationAvailability(agent.currentSessionId))
            } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 30_000) fun deniedWriteHasNoFileOrSuccessfulResultAndStopsAtNormalTurnBudget() = runBlocking {
        val root = Files.createTempDirectory("workflow-denied-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("edit", "*", PermEffect.DENY))), maxSubagentDepth = 0)
            fixture.response = { _, _, _ -> tool(fixture, "write_file", buildJsonObject { put("path", "denied.txt"); put("content", "must not write") }) }
            try {
                runBlocking { agent.chat("Create denied.txt", fixture.config, maxTurns = 1) }
                assertFalse(root.resolve("denied.txt").exists())
                assertEquals(1, fixture.requests.size)
                assertTrue(AgentSessionResults.page(agent.messages.value).entries.isEmpty())
                assertFalse(agent.busy.value)
            } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 30_000) fun truncatedProviderCallIsNotExecutedOrAutomaticallyRetried() = runBlocking {
        val root = Files.createTempDirectory("workflow-truncated-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
            fixture.response = { _, _, _ -> tool(fixture, "write_file", buildJsonObject { put("path", "partial.txt"); put("content", "incomplete") }, finish = false) }
            try {
                runBlocking { agent.chat("Create partial.txt", fixture.config, maxTurns = 3) }
                assertFalse(root.resolve("partial.txt").exists())
                assertEquals(1, fixture.requests.size)
                assertTrue(AgentSessionResults.page(agent.messages.value).entries.isEmpty())
                assertFalse(agent.busy.value)
            } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 30_000) fun restoredArtifactIsDiscoverableAndContinuationDoesNotCreateItAgain() = runBlocking {
        val root = Files.createTempDirectory("workflow-results-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            fun service() = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
            fixture.response = { call, _, _ -> when (call) {
                1 -> tool(fixture, "tool_search", buildJsonObject { put("query", "artifact") })
                2 -> tool(fixture, "artifact", buildJsonObject { put("type", "report"); put("content", "Verified output") })
                else -> answer(fixture)
            } }
            val first = service()
            val session = try {
                runBlocking { first.chat("Create a report artifact", fixture.config, maxTurns = 3) }
                runBlocking { checkNotNull(first.sessions.get(checkNotNull(first.currentSessionId))) }
            } finally { first.shutdown() }
            val second = service()
            try {
                runBlocking { second.loadSession(session) }
                val result = AgentSessionResults.page(second.messages.value).entries.filterIsInstance<AgentSessionResult.File>().single()
                assertTrue(root.resolve(result.path).readText().contains("Verified output"))
                assertEquals(AgentContinuation.READY, second.continuationAvailability(session.id))
                assertEquals(AgentContinuation.CHANGED, second.continuationAvailability("other"))
                runBlocking { second.chat(AGENT_CONTINUATION_PROMPT, fixture.config, maxTurns = 1) }
                assertEquals(1, AgentSessionResults.page(second.messages.value).entries.filterIsInstance<AgentSessionResult.File>().size)
                assertEquals(4, fixture.requests.size)
            } finally { second.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

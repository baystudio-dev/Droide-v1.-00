package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class AgentMalformedToolArgumentsWorkflowTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test fun missingOrNullWriteContentNeverClearsAnExistingFile() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-write-required-").toFile()
        try {
            root.resolve("important.txt").writeText("keep user source")
            val ctx = agentWorkflowContext(root)
            for (args in listOf(
                buildJsonObject { put("path", "important.txt") },
                buildJsonObject { put("path", "important.txt"); put("content", JsonNull) },
                buildJsonObject { put("path", "important.txt"); put("content", 17) },
            )) {
                assertTrue(AgentTools.execute("write_file", args, ctx).startsWith("ERROR"))
                assertEquals("keep user source", root.resolve("important.txt").readText())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun explicitEmptyWriteContentRemainsSupported() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-write-empty-").toFile()
        try {
            root.resolve("a.txt").writeText("old")
            val output = AgentTools.execute("write", buildJsonObject { put("path", "a.txt"); put("content", "") }, agentWorkflowContext(root))
            assertTrue(output.startsWith("OK"))
            assertEquals("", root.resolve("a.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 30_000) fun malformedUndoArgumentsCannotMutateAndValidSiblingCallsStillComplete() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-malformed-args-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                fixture.response = { call, _, _ -> if (call == 1) {
                    val calls = buildJsonArray {
                        fun append(index: Int, id: String, name: String, arguments: String) = add(buildJsonObject {
                            put("index", index); put("id", id); put("type", "function")
                            put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
                        })
                        append(0, "write-good", "write_file", buildJsonObject { put("path", "good.txt"); put("content", "successful edit") }.toString())
                        append(1, "undo-bad", "undo_agent_edit", "{broken")
                        append(2, "read-good", "read_file", buildJsonObject { put("path", "good.txt") }.toString())
                    }
                    fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                } else fixture.event("""{"choices":[{"index":0,"delta":{"content":"finished"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n" }
                try {
                    runBlocking { withTimeout(15_000) { agent.chat("Edit and inspect", fixture.config, maxTurns = 2) } }
                    assertTrue("Malformed arguments must not execute undo", root.resolve("good.txt").isFile)
                    assertEquals("successful edit", root.resolve("good.txt").readText())
                    val session = runBlocking { agent.sessions.get(agent.currentSessionId!!)!! }
                    val toolResults = session.apiHistory.filter { it["role"] == JsonPrimitive("tool") }
                    assertEquals(3, toolResults.size)
                    assertTrue(toolResults.single { it["tool_call_id"] == JsonPrimitive("undo-bad") }
                        .getValue("content").jsonPrimitive.content.contains("INVALID_TOOL_ARGUMENTS"))
                    assertTrue(toolResults.single { it["tool_call_id"] == JsonPrimitive("read-good") }
                        .getValue("content").jsonPrimitive.content.contains("successful edit"))
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

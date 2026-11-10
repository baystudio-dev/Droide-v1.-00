package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule

class AgentCommandUnicodeCursorTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test fun incrementalPagesNeverSplitAnEmojiAtTheEightThousandUnitBoundary() {
        val output = "x".repeat(7_999) + "😀done"
        val snapshot = BackgroundCommandManager.Snapshot("fixture", BackgroundCommandManager.State.EXITED, "printf", output, 0, null, 0, 1)
        val first = AgentCommandSessions.render(snapshot, 0)
        val metadata = metadata(first)
        val cursor = metadata.getValue("next_output_cursor").jsonPrimitive.long
        assertEquals(7_999, cursor)
        assertFalse(payload(first).last().isHighSurrogate())
        val second = AgentCommandSessions.render(snapshot, cursor)
        assertEquals("😀done", payload(second))
        assertEquals(output, payload(first) + payload(second))
        assertEquals(output.length.toLong(), metadata(second).getValue("next_output_cursor").jsonPrimitive.long)
    }

    @Test fun boundedTailDiscardsWholeEmojiAndReportsTheCorrectLostOutputCursor() = runBlocking {
        val emitted = "😀" + "x".repeat(19_999)
        val terminal = object : ITerminalSession {
            override val output = MutableStateFlow("")
            override fun start() = Unit
            override fun send(cmd: String) = Unit
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Not used")
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = error("Not used")
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
                onOutput(emitted)
                return ExecResult(0, emitted, false)
            }
            override fun interrupt() = Unit
            override fun clear() = Unit
            override fun destroy() = Unit
        }
        val manager = BackgroundCommandManager(terminal)
        try {
            val job = manager.start("emoji boundary fixture")
            val final = withTimeout(3_000) { manager.awaitExit(job.id, 0) {} }
            assertEquals(emitted.length.toLong(), final.outputEndCursor)
            assertEquals("x".repeat(19_999), final.output)
            assertFalse(final.output.first().isLowSurrogate())
            val first = AgentCommandSessions.render(final, 0)
            assertEquals(2, metadata(first).getValue("output_start_cursor").jsonPrimitive.long)
            assertTrue(metadata(first).getValue("output_lost").jsonPrimitive.boolean)
            assertEquals(8_002, metadata(first).getValue("next_output_cursor").jsonPrimitive.long)
        } finally { manager.close() }
    }

    private fun metadata(text: String): JsonObject = Json.parseToJsonElement(text.lineSequence().first { it.startsWith('{') }).jsonObject
    private fun payload(text: String): String = text.substringAfter(metadata(text).toString() + "\n")
}

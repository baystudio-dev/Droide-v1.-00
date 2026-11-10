package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextCompactionTranscriptTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun knownLargeModelCompactsInLargerChunksWhileSmallAndUnknownModelsStayBounded() {
        val manager = SessionManager(tmp.root)
        val config = AgentConfig(providerId = "compaction.chunk.fixture", model = "model")
        try {
            val unknown = manager.compactionChunkChars(config)
            ModelContextRegistry.remember(config.providerId, config.model, 1_048_576)
            val large = manager.compactionChunkChars(config)
            assertTrue(large > unknown)
            assertTrue(large < LlmClient.MAX_REQUEST_CHARS)
            ModelContextRegistry.remember(config.providerId, config.model, 8192)
            val small = manager.compactionChunkChars(config)
            assertTrue(small < unknown)
            assertTrue(small / 4 < 8192)
        } finally { ModelContextRegistry.forget(config.providerId) }
    }

    @Test fun checkpointInputPreservesConstraintsAndToolEvidenceBeyondFormerTruncationLimits() {
        val manager = SessionManager(tmp.root)
        val user = buildJsonObject { put("role", "user"); put("content", "x".repeat(6000) + " KEEP_USER_PROJECT") }
        val args = "x".repeat(2000) + " REQUIRED_ARG_TAIL"
        val assistant = buildJsonObject {
            put("role", "assistant")
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("id", "call1"); put("type", "function")
                put("function", buildJsonObject { put("name", "verify"); put("arguments", args) })
            }) })
        }
        val result = buildJsonObject { put("role", "tool"); put("tool_call_id", "call1"); put("content", "x".repeat(3000) + " TEST_FAILED") }
        val groups = AgentHistory.completeGroups(listOf(user, assistant, result))
        val rendered = groups.mapIndexed { i, group -> manager.renderGroupForCompaction(group, i + 1) }.joinToString("")
        val chunks = manager.chunkRenderedHistory(groups, 4000)
        assertEquals(rendered, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4000 })
        assertTrue(rendered.contains("KEEP_USER_PROJECT"))
        assertTrue(rendered.contains("REQUIRED_ARG_TAIL"))
        assertTrue(rendered.contains("TEST_FAILED"))
        assertEquals(2, groups.last().size)
    }

    @Test fun multimodalTextSurvivesCheckpointWithoutEmbeddingBinaryImageData() {
        val manager = SessionManager(tmp.root)
        val entry = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "Use the logo without changing its colors") })
                add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/png;base64,PRIVATE_BINARY") }) })
            })
        }
        val rendered = manager.renderGroupForCompaction(listOf(entry), 1)
        assertTrue(rendered.contains("without changing its colors"))
        assertTrue(rendered.contains("Image attachment"))
        assertFalse(rendered.contains("PRIVATE_BINARY"))
        assertFalse(rendered.contains("base64"))
    }
}

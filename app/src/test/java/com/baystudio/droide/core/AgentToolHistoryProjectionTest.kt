package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class AgentToolHistoryProjectionTest {
    @Test fun unchangedResultsRetainExactBytes() {
        listOf("", "{}", "ERROR: denied", "a".repeat(12_000)).forEach {
            assertEquals(it, AgentToolHistoryProjection.project(it))
        }
    }

    @Test fun oversizedOutputIsExplicitlyIncompleteWithinExistingBudget() {
        val raw = "x".repeat(12_001)
        val result = AgentToolHistoryProjection.project(raw)
        assertEquals(12_000, result.length)
        assertTrue(result.contains("[DROIDE_TOOL_OUTPUT_INCOMPLETE original_utf16_chars=12001]"))
        assertTrue(result.contains("Omitted content is unknown"))
        assertTrue(result.contains("not a complete document or JSON value"))
        assertTrue(result.contains("Do not infer missing results or repeat a side-effecting operation"))
        assertFalse(result.contains("ref_id"))
        assertFalse(result.contains("read_tool_output"))
    }

    @Test fun originalFailureEvidenceAndPrefixStayVisible() {
        val evidence = "[DROIDE_TOOL_EVIDENCE tool=run_command state=FAILED operation_started=true success=false]\n"
        val raw = evidence + "stderr: compile failed\n" + "z".repeat(20_000)
        val result = AgentToolHistoryProjection.project(raw)
        assertTrue(result.startsWith(evidence + "stderr: compile failed\n"))
        assertEquals(12_000, result.length)
    }

    @Test fun truncationNeverSplitsUtf16Pair() {
        val baseline = AgentToolHistoryProjection.project("a".repeat(20_000))
        val cut = baseline.indexOf("\n[DROIDE_TOOL_OUTPUT_INCOMPLETE")
        val raw = "a".repeat(cut - 1) + "\uD83D\uDE80" + "z".repeat(20_000 - cut - 1)
        assertEquals(20_000, raw.length)
        val result = AgentToolHistoryProjection.project(raw)
        assertEquals(11_999, result.length)
        assertEquals(cut - 1, result.indexOf("\n[DROIDE_TOOL_OUTPUT_INCOMPLETE"))
        assertFalse(result.any { it.isSurrogate() })
    }

    @Test fun projectionIsIdempotentAndDoesNotInventCompletion() {
        val result = AgentToolHistoryProjection.project("{\"success\":true,\"data\":\"" + "a".repeat(40_000))
        assertEquals(result, AgentToolHistoryProjection.project(result))
        assertTrue(result.contains("OUTPUT_INCOMPLETE"))
        assertFalse(result.endsWith("}"))
    }
}

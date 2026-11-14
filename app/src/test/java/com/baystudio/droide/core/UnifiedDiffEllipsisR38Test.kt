package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Three current-API source-only fixtures; no files, Agent, provider, terminal, network or device. */
class UnifiedDiffEllipsisR38Test {
    @Test fun standardHunksKeepLiteralEllipsisSourceAndLineNumbers() {
        for ((before, after) in listOf("… old" to "… new",
            "… 7 more removed lines …" to "… 7 more added lines …")) {
            val change = AgentFileChanges.preview("notes.txt", "$before\n", "$after\n")
            assertEquals(AgentChangeTotals(1, 1), AgentFileChanges.totals(listOf(change)))
            val hunk = UnifiedDiffParser.parse(change.diff).single().hunks.single()
            assertTrue(hunk.header.startsWith("@@ -"))
            val lines = hunk.lines.filter { it.kind != DiffLineKind.CONTEXT }
            assertEquals(listOf(DiffLine(DiffLineKind.REMOVE, before, oldLine = 1),
                DiffLine(DiffLineKind.ADD, after, newLine = 1)), lines)
        }
    }

    @Test fun actualLegacyTruncationMarkersRemainMetadata() {
        val diff = DiffUtil.unified("old1\nold2\nold3\n", "new1\nnew2\nnew3\n", context = 0, maxChangedLines = 1)
        val hunk = UnifiedDiffParser.parse(diff).single().hunks.single()
        assertTrue(hunk.header.startsWith("@@ old "))
        assertEquals(listOf("-… 2 more removed lines …", "+… 2 more added lines …"),
            hunk.lines.filter { it.kind == DiffLineKind.META && it.text.isNotEmpty() }.map { it.text })
        assertEquals(listOf(DiffLine(DiffLineKind.REMOVE, "old1", oldLine = 1),
            DiffLine(DiffLineKind.ADD, "new1", newLine = 1)),
            hunk.lines.filter { it.kind != DiffLineKind.META })
    }

    @Test fun nonMarkerLegacyEllipsisTextKeepsOrdinarySourceSemantics() {
        val before = listOf("… literal", "… 0 more removed lines …", "… 3 more added lines …")
        val after = listOf("… revised", "… 0 more added lines …", "… 3 more removed lines …")
        val diff = "@@ old 1-3 | new 1-3 @@\n" +
            before.joinToString("\n") { "-$it" } + "\n" + after.joinToString("\n") { "+$it" }
        val lines = UnifiedDiffParser.parse(diff).single().hunks.single().lines
        assertEquals(before.mapIndexed { index, text -> DiffLine(DiffLineKind.REMOVE, text, oldLine = index + 1) } +
            after.mapIndexed { index, text -> DiffLine(DiffLineKind.ADD, text, newLine = index + 1) }, lines)
    }
}

package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class AgentStructuralProposalTest {
    @Test fun emptyCreateDeleteAndSamePathNoOpKeepDistinctFileIdentity() {
        val create = AgentFileChanges.preview("created-empty.txt", null, "")
        val delete = AgentFileChanges.preview("deleted-empty.txt", "", null)
        val unchanged = AgentFileChanges.preview("unchanged.txt", "", "")
        val files = listOf(create, delete, unchanged).map { UnifiedDiffParser.parse(it.diff).single() }
        assertNull(files[0].oldPath); assertEquals("created-empty.txt", files[0].newPath)
        assertEquals("deleted-empty.txt", files[1].oldPath); assertNull(files[1].newPath)
        assertEquals("unchanged.txt", files[2].oldPath); assertEquals("unchanged.txt", files[2].newPath)
        assertEquals(AgentChangeTotals(0, 0), AgentFileChanges.totals(listOf(create, delete, unchanged)))
        assertTrue(files.all { it.hunks.isEmpty() })
        assertTrue(create.diff.contains("Created empty file"))
        assertTrue(delete.diff.contains("Deleted empty file"))
        assertTrue(unchanged.diff.contains("no content changes"))
    }

    @Test fun mixedStructuralAndTextProposalRemainsAttributableWithOneAggregate() {
        val operations = listOf(PatchTool.Op.Add("empty.txt", ""),
            PatchTool.Op.Update("old.txt", "", "", "renamed.txt", canonical = true),
            PatchTool.Op.Update("edited.txt", "before", "after", null))
        val changes = AgentFileChanges.patch(operations, mapOf("empty.txt" to null, "old.txt" to "same\n", "renamed.txt" to null, "edited.txt" to "before\n"))
        val files = UnifiedDiffParser.parse(changes.joinToString("\n") { it.diff })
        assertEquals(listOf("empty.txt", "renamed.txt", "edited.txt"), files.map { it.newPath })
        assertNull(files[0].oldPath); assertEquals("old.txt", files[1].oldPath)
        assertEquals(AgentChangeTotals(1, 1), AgentFileChanges.totals(changes))
        assertEquals(1, files.sumOf { it.hunks.size })
    }

    @Test fun unknownCountFallbackRetainsMoveEndpointsWithoutInventingZero() {
        val change = AgentFileChanges.preview("new.txt", "a", "x".repeat(800_000), previousPath = "old.txt")
        val file = UnifiedDiffParser.parse(change.diff).single()
        assertEquals("old.txt", file.oldPath); assertEquals("new.txt", file.newPath)
        assertEquals(AgentChangeTotals(null, null), AgentFileChanges.totals(listOf(change)))
        assertTrue(change.truncated)
    }
    @Test fun structuralAndUnavailablePreviewRetainExistingByteBudget() {
        val path = "nested/".repeat(90) + "file.txt"
        listOf(AgentFileChanges.preview(path, null, "", limit = 256),
            AgentFileChanges.preview(path, "a", "x".repeat(800_000), limit = 256)).forEach {
            assertTrue(it.diff.toByteArray(Charsets.UTF_8).size <= 256)
            assertTrue(it.truncated)
        }
    }

}

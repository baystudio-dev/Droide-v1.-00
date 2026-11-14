package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Report-only R34 candidate. Not compiled or executed. */
class AgentChangeTotalsR34Test {
    @Test(timeout = 5_000)
    fun absenceCreationDeletionAndNoOpKeepDistinctHonestTotals() {
        assertNull(AgentFileChanges.totals(emptyList()))
        val created = AgentFileChanges.preview("created.txt", null, "one\ntwo\nthree\n")
        val deleted = AgentFileChanges.preview("deleted.txt", "one\ntwo\n", null)
        val unchanged = AgentFileChanges.preview("same.txt", "same\n", "same\n")
        assertEquals(AgentChangeTotals(3L, 0L), AgentFileChanges.totals(listOf(created)))
        assertEquals(AgentChangeTotals(0L, 2L), AgentFileChanges.totals(listOf(deleted)))
        assertEquals(AgentChangeTotals(0L, 0L), AgentFileChanges.totals(listOf(unchanged)))
        assertTrue(AgentChangeTotals(0L, 0L).known)
        assertFalse(AgentChangeTotals(null, 0L).known)
        assertFalse(AgentChangeTotals(0L, null).known)
        assertFalse(AgentChangeTotals(-1L, 0L).known)
        assertFalse(AgentChangeTotals(0L, -1L).known)
    }

    @Test(timeout = 5_000)
    fun multipleFilesAndMovesCountContentEditsOnce() {
        val moved = AgentFileChanges.preview("renamed.txt", "old\nkeep\n", "new\nkeep\n", previousPath = "old.txt")
        val movedWithoutContentChange = AgentFileChanges.preview("moved.txt", "same\n", "same\n", previousPath = "same.txt")
        val created = AgentFileChanges.preview("created.txt", null, "a\nb\n")
        val deleted = AgentFileChanges.preview("deleted.txt", "a\nb\nc\n", null)
        assertEquals("old.txt", moved.previousPath)
        assertEquals(AgentChangeTotals(3L, 4L), AgentFileChanges.totals(listOf(moved, movedWithoutContentChange, created, deleted)))
        assertEquals(AgentChangeTotals(0L, 0L), AgentFileChanges.totals(listOf(movedWithoutContentChange)))
    }

    @Test(timeout = 5_000)
    fun clippedPreviewRetainsFullCountsWithoutParsingVisibleLines() {
        val change = AgentFileChanges.preview("clipped.txt", null,
            (1..500).joinToString("\n") { "bounded line $it" }, limit = 256)
        assertTrue(change.truncated)
        assertTrue(change.diff.toByteArray(Charsets.UTF_8).size <= 256)
        assertEquals(AgentChangeTotals(500L, 0L), AgentFileChanges.totals(listOf(change)))
        // Totals depend on structured evidence even when display text is unavailable.
        assertEquals(AgentChangeTotals(500L, 0L), AgentFileChanges.totals(listOf(change.copy(diff = "clipped"))))
    }

    @Test(timeout = 5_000)
    fun oversizedMissingAndNegativeCountsMakeTheWholeAggregateUnknown() {
        val known = AgentFileChanges.preview("known.txt", null, "known\n")
        val oversized = AgentFileChanges.preview("oversized.txt", null, "x".repeat(750_001))
        assertTrue(oversized.truncated)
        for (unknown in listOf(
            oversized,
            AgentFileChange("missing-add.txt", "", null, 1),
            AgentFileChange("missing-remove.txt", "", 1, null),
            AgentFileChange("negative-add.txt", "", -1, 0),
            AgentFileChange("negative-remove.txt", "", 0, -1),
        )) {
            assertEquals(AgentChangeTotals(null, null), AgentFileChanges.totals(listOf(unknown)))
            val total = requireNotNull(AgentFileChanges.totals(listOf(known, unknown)))
            assertEquals(AgentChangeTotals(null, null), total)
            assertFalse(total.known)
        }
    }

    @Test(timeout = 5_000)
    fun aggregateUsesLongWithoutAllocatingLargeFiles() {
        val max = Int.MAX_VALUE
        val changes = listOf(
            AgentFileChange("a.txt", "not parsed", max, max),
            AgentFileChange("b.txt", "not parsed", max, max),
            AgentFileChange("c.txt", "not parsed", 2, 3),
        )
        val total = requireNotNull(AgentFileChanges.totals(changes))
        assertEquals(AgentChangeTotals(4_294_967_296L, 4_294_967_297L), total)
        assertTrue(total.known)
    }
}

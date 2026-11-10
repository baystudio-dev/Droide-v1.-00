package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExportJournalIsolationTest {
    private fun fixture(body: (File, ExportJournal) -> Unit) {
        val root = Files.createTempDirectory("journal-isolation").toFile()
        try {
            val journal = ExportJournal(root)
            journal.write(ExportJobState("old", "epoch", ExportJobPhase.PARTIAL, retainedCapture = true))
            File(journal.jobDirectory("old"), "capture").mkdir()
            File(root, "old/capture/precious").writeText("untouched")
            journal.write(ExportJobState("healthy", "epoch", ExportJobPhase.COMPLETED_VERIFIED))
            body(root, journal)
            assertEquals("untouched", File(root, "old/capture/precious").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun malformedHistoricalRecordDoesNotHideHealthySibling() = fixture { root, journal ->
        val file = File(root, "old/state.properties")
        for (bad in listOf("version=broken\n", file.readText().replace("phase=PARTIAL", "phase=INVALID"),
            file.readText().replace("entries=0", "entries=-1"), file.readText().replace("id=old", "id=../healthy"))) {
            file.writeText(bad)
            val snapshot = journal.historySnapshot()
            assertEquals(listOf("healthy"), snapshot.jobs.map { it.id })
            assertEquals(1, snapshot.unreadableRecords)
            assertFalse(snapshot.currentUnreadable)
            assertEquals(bad, file.readText())
        }
    }
    @Test fun corruptCurrentIsReportedButNeverRewrittenOrResumed() = fixture { root, journal ->
        val current = File(root, "current.properties").apply { writeText("version=99\n") }
        File(root, "current.tmp").writeText("partial temporary write")
        repeat(3) {
            val snapshot = ExportJournal(root).historySnapshot()
            assertEquals(2, snapshot.jobs.size)
            assertTrue(snapshot.currentUnreadable)
            assertEquals("version=99\n", current.readText())
            assertEquals("partial temporary write", File(root, "current.tmp").readText())
            assertTrue(runCatching { journal.read() }.isFailure)
        }
    }
    @Test fun wrongOwnerIsIsolatedWithoutRepair() = fixture { root, journal ->
        File(root, "old/owned-job").writeText("healthy")
        assertEquals(listOf("healthy"), journal.history().map { it.id })
        assertEquals(1, journal.historySnapshot().unreadableRecords)
        assertEquals("healthy", File(root, "old/owned-job").readText())
    }
    @Test fun symlinkMetadataIsNotReadOrRemoved() = fixture { root, journal ->
        val old = File(root, "old/state.properties")
        old.delete()
        Files.createSymbolicLink(old.toPath(), File(root, "healthy/state.properties").toPath())
        assertEquals(1, journal.historySnapshot().unreadableRecords)
        assertEquals(listOf("healthy"), journal.history().map { it.id })
        assertTrue(Files.isSymbolicLink(old.toPath()))
    }
    @Test fun oversizedOrSymlinkOwnerIsNotAccepted() = fixture { root, journal ->
        val marker = File(root, "old/owned-job")
        marker.writeText("old" + "x".repeat(300))
        assertEquals(1, journal.historySnapshot().unreadableRecords)
        marker.delete()
        Files.createSymbolicLink(marker.toPath(), File(root, "healthy/owned-job").toPath())
        assertEquals(1, journal.historySnapshot().unreadableRecords)
        assertTrue(Files.isSymbolicLink(marker.toPath()))
    }
    @Test fun cancellationPropagatesWithoutChangingAnyRecord() = fixture { root, journal ->
        val before = File(root, "current.properties").readBytes()
        var checks = 0
        try {
            journal.historySnapshot { if (++checks == 5) throw java.util.concurrent.CancellationException("test") }
            fail("Cancellation was swallowed")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertArrayEquals(before, File(root, "current.properties").readBytes())
        assertEquals(2, journal.historySnapshot().jobs.size)
    }
    @Test fun oversizedMetadataIsIsolated() = fixture { root, journal ->
        File(root, "old/state.properties").writeText("x".repeat(1024 * 1024 + 1))
        assertEquals(1, journal.historySnapshot().unreadableRecords)
        assertEquals(listOf("healthy"), journal.history().map { it.id })
    }
}

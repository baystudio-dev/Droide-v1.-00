package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.DataInputStream
import java.io.File

class ExportJournalTest {
    @Test fun stateRoundTripsAndOwnedUrisRemainSeparateFromParent() {
        val dir = Files.createTempDirectory("export-journal").toFile()
        try {
            val store = ExportJournal(dir)
            val state = ExportJobState("job-1", "epoch-1", ExportJobPhase.WRITING, 12, 5_000_000_000L, "writing", 2)
            store.write(state)
            store.recordCreated(state.id, "content://test/document/new-child")
            assertEquals(state, ExportJournal(dir).read())
            DataInputStream(File(store.jobDirectory(state.id), "created-uris").inputStream()).use {
                assertEquals("content://test/document/new-child", it.readUTF())
                assertEquals(-1, it.read())
            }
            assertFalse(File(dir, "current.tmp").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun unknownJournalVersionIsNotOverwrittenOrDiscarded() {
        val dir = Files.createTempDirectory("export-journal").toFile()
        try {
            val file = File(dir, "current.properties")
            file.writeText("version=99\n")
            try { ExportJournal(dir).read(); fail("Expected refusal") } catch (_: IllegalStateException) { }
            assertEquals("version=99\n", file.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun terminalStatesDoNotIncludeWritingOrCancellationPending() {
        assertFalse(ExportJobPhase.WRITING.terminal)
        assertFalse(ExportJobPhase.VERIFYING.terminal)
        assertFalse(ExportJobPhase.CANCELLING.terminal)
        assertTrue(ExportJobPhase.UNVERIFIED.terminal)
        assertTrue(ExportJobPhase.INTERRUPTED.terminal)
        assertTrue(ExportJobPhase.COMPLETED_VERIFIED.terminal)
    }
    @Test fun failedJobHistoryAndOwnershipSurviveNewJobsAndRestart() {
        val dir = Files.createTempDirectory("export-recovery").toFile()
        try {
            val store = ExportJournal(dir)
            val first = ExportJobState("first", "epoch", ExportJobPhase.PARTIAL, retainedCapture = true)
            File(store.jobDirectory("first"), "capture").mkdir()
            File(store.jobDirectory("first"), "capture/bytes").writeText("retained bytes")
            store.recordCreated("first", "content://provider/document/first")
            store.write(first)
            assertTrue(ExportJournal(dir).hasCapture("first"))
            store.discardCapture("first")
            store.write(first.copy(retainedCapture = false))
            val second = ExportJobState("second", "epoch", ExportJobPhase.FAILED)
            store.write(second)
            val restored = ExportJournal(dir)
            assertEquals(setOf("first", "second"), restored.history().map { it.id }.toSet())
            assertEquals(second, restored.read())
            assertTrue(File(restored.jobDirectory("first"), "created-uris").isFile)
            assertFalse(restored.hasCapture("first"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun discardNeverFollowsCaptureSymlinksOrDeletesOutputLedger() {
        val dir = Files.createTempDirectory("export-recovery").toFile()
        val external = Files.createTempDirectory("export-external").toFile()
        try {
            val store = ExportJournal(dir)
            val job = store.jobDirectory("owned")
            val precious = File(external, "precious").apply { writeText("untouched") }
            val capture = File(job, "capture").apply { mkdir() }
            Files.createSymbolicLink(File(capture, "outside").toPath(), external.toPath())
            store.recordCreated("owned", "content://provider/document/output")
            store.discardCapture("owned")
            assertEquals("untouched", precious.readText())
            assertTrue(File(job, "created-uris").isFile)
            assertFalse(capture.exists())
            Files.createSymbolicLink(File(dir, "alias").toPath(), external.toPath())
            try { store.discardCapture("alias"); fail("Symlink job accepted") } catch (_: IllegalStateException) { }
            assertTrue(precious.exists())
        } finally { dir.deleteRecursively(); external.deleteRecursively() }
    }
    @Test fun discardRejectsUnownedJobDirectory() {
        val dir = Files.createTempDirectory("export-recovery").toFile()
        try {
            File(dir, "unknown/capture").mkdirs()
            try { ExportJournal(dir).discardCapture("unknown"); fail("Unowned job accepted") } catch (_: IllegalStateException) { }
            assertTrue(File(dir, "unknown/capture").isDirectory)
        } finally { dir.deleteRecursively() }
    }
}

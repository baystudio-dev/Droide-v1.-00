package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EditorRecoveryCopiesTest {
    private lateinit var root: java.io.File
    private lateinit var files: FileRepository
    private lateinit var state: EditorWorkspaceState
    @Before fun before() {
        root = Files.createTempDirectory("recovery-copies-").toFile()
        files = FileRepository(root)
        state = EditorWorkspaceState()
    }
    @After fun after() { PathSecurity.deleteTreeNoFollow(root) }
    private fun retain(text: String = "older") = state.retainRecoveryConflict("original.txt", text, null)
    private fun selection() = state.retainedCopies().single()
    private fun snapshot() = requireNotNull(state.recoverySnapshot(emptyList(), ""))

    @Test fun normalCopyIsUnsavedAndOriginalAndNewerEditorSurviveLaterSave() = runBlocking {
        root.resolve("original.txt").writeText("saved")
        state.document("original.txt").also { it.ensureLoaded(files); it.capture("newer unsaved") }
        retain()
        val prepared = EditorRecoveryCopies(state, files).prepare(selection(), "copy.txt")
        val document = prepared.open()
        assertEquals("older", document.content); assertTrue(document.dirty)
        assertFalse(root.resolve("copy.txt").exists())
        assertEquals("newer unsaved", state.document("original.txt").content)
        assertEquals("older", snapshot().conflictBuffers["original.txt"])
        assertEquals("older", snapshot().dirtyBuffers["copy.txt"])
        assertTrue(document.save(files).isSuccess)
        assertEquals("older", root.resolve("copy.txt").readText())
        assertEquals("saved", root.resolve("original.txt").readText())
        assertEquals("older", snapshot().conflictBuffers["original.txt"])
        assertTrue(runCatching { prepared.open() }.isFailure)
    }

    @Test fun emptyCopyStillHasPersistenceIntent() = runBlocking {
        retain("")
        val document = EditorRecoveryCopies(state, files).prepare(selection(), "empty.txt").open()
        assertTrue(document.persistenceRequired)
        assertEquals("", snapshot().dirtyBuffers["empty.txt"])
        assertTrue(document.save(files).isSuccess)
        assertTrue(root.resolve("empty.txt").isFile)
    }

    @Test fun deferredLoaderKeepsOriginalPathAndRunsOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        var reads = 0
        val reference = EditorRecoveryBuffer("original.txt", "original-blob", null, Any(), 5, DiskFileVersion.fromText("old")) {
            assertNotSame(caller, Thread.currentThread()); reads++; "older"
        }
        state.retainRecoveryConflict("original.txt", null, reference)
        EditorRecoveryCopies(state, files).prepare(selection(), "new.txt").open()
        assertEquals(1, reads)
        assertSame(reference, snapshot().deferredConflictBuffers["original.txt"])
        assertEquals("original.txt", reference.path)
        assertEquals(DiskFileVersion.fromText(null), state.document("new.txt").savedDiskVersion)
    }

    @Test fun corruptLoaderCreatesNothingAndPreservesReference() = runBlocking {
        val reference = EditorRecoveryBuffer("original.txt", "blob", null, Any(), 5) { error("Authentication failed") }
        state.retainRecoveryConflict("original.txt", null, reference)
        assertTrue(runCatching { EditorRecoveryCopies(state, files).prepare(selection(), "new.txt") }.isFailure)
        assertTrue(state.allDocuments().isEmpty())
        assertSame(reference, snapshot().deferredConflictBuffers["original.txt"])
    }

    @Test fun canceledDeferredReadCreatesNothing() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reference = EditorRecoveryBuffer("original.txt", "blob", null, Any(), 5) {
            started.complete(Unit); awaitCancellation()
        }
        state.retainRecoveryConflict("original.txt", null, reference)
        val job = launch { EditorRecoveryCopies(state, files).prepare(selection(), "new.txt").open() }
        started.await(); job.cancelAndJoin()
        assertTrue(state.allDocuments().isEmpty())
        assertSame(reference, snapshot().deferredConflictBuffers["original.txt"])
    }

    @Test fun detachedOrReboundOwnerAndForeignSelectionCannotPublish() = runBlocking {
        retain()
        val action = EditorRecoveryCopies(state, files)
        val prepared = action.prepare(selection(), "new.txt")
        val binding = state.bindProjectHandoffCapture { }
        binding.close()
        assertTrue(runCatching { prepared.open() }.isFailure)
        assertTrue(runCatching { EditorRecoveryCopies(EditorWorkspaceState(), files).prepare(selection(), "other.txt") }.isFailure)
        val fresh = action.prepare(selection(), "new.txt")
        action.close()
        assertTrue(runCatching { fresh.open() }.isFailure)
        assertTrue(state.allDocuments().isEmpty())
    }

    @Test fun changedRecoveryOrNewLiveDestinationRejectsWithoutReplacingAnything() = runBlocking {
        retain()
        val action = EditorRecoveryCopies(state, files)
        val prepared = action.prepare(selection(), "new.txt")
        val live = state.document("new.txt")
        assertTrue(runCatching { prepared.open() }.isFailure)
        assertSame(live, state.peek("new.txt"))
        val next = action.prepare(selection(), "other.txt")
        state.retainRecoveryConflict("second.txt", "second", null)
        assertTrue(runCatching { next.open() }.isFailure)
        assertNull(state.peek("other.txt"))
    }

    @Test fun fileAppearingDuringDeferredReadRejectsPreparation() = runBlocking {
        val reference = EditorRecoveryBuffer("original.txt", "blob", null, Any(), 5) {
            root.resolve("new.txt").writeText("foreign"); "older"
        }
        state.retainRecoveryConflict("original.txt", null, reference)
        assertTrue(runCatching { EditorRecoveryCopies(state, files).prepare(selection(), "new.txt") }.isFailure)
        assertTrue(state.allDocuments().isEmpty())
        assertEquals("foreign", root.resolve("new.txt").readText())
    }

    @Test fun racedFileAfterPreparationIsNeverOverwrittenBySave() = runBlocking {
        retain()
        val prepared = EditorRecoveryCopies(state, files).prepare(selection(), "new.txt")
        root.resolve("new.txt").writeText("foreign")
        val doc = prepared.open()
        assertTrue(doc.save(files).isFailure)
        assertTrue(doc.dirty); assertEquals("older", doc.content)
        assertEquals("foreign", root.resolve("new.txt").readText())
    }

    @Test fun unsafeExistingAndAliasedDestinationsAreRejected() = runBlocking {
        retain(); root.resolve("exists.txt").writeText("keep")
        Files.createSymbolicLink(root.resolve("dangling").toPath(), root.resolve("missing").toPath())
        for (target in listOf("", "../outside.txt", ".droide/new.txt", "dangling", "exists.txt", "missing/new.txt", "./alias.txt", "original.txt")) {
            assertTrue("$target should be refused", runCatching { EditorRecoveryCopies(state, files).prepare(selection(), target) }.isFailure)
        }
        assertTrue(state.allDocuments().isEmpty())
    }

    @Test fun destinationBeyondCheckpointPathLimitIsRejectedWithoutPublication() = runBlocking {
        retain()
        val parent = (1..6).joinToString("/") { "a".repeat(180) }
        assertTrue(root.resolve(parent).mkdirs())
        val destination = "$parent/copy.txt"
        assertTrue(destination.length > 1024)
        assertTrue(runCatching { EditorRecoveryCopies(state, files).prepare(selection(), destination) }.isFailure)
        assertTrue(state.allDocuments().isEmpty())
        assertEquals("older", snapshot().conflictBuffers["original.txt"])
    }

    @Test fun countCapacityAndUnknownDeferredBoundRejectWithoutPublication() = runBlocking {
        for (i in 0 until 256) state.retainRecoveryConflict("$i.txt", "x", null)
        assertTrue(runCatching { EditorRecoveryCopies(state, files).prepare(state.retainedCopies().first(), "new.txt") }.isFailure)
        assertTrue(state.allDocuments().isEmpty())
        val other = EditorWorkspaceState()
        other.retainRecoveryConflict("original.txt", null, EditorRecoveryBuffer("original.txt", "blob", null, Any()) { "old" })
        assertTrue(runCatching { EditorRecoveryCopies(other, files).prepare(other.retainedCopies().single(), "new.txt") }.isFailure)
        assertTrue(other.allDocuments().isEmpty())
    }

    @Test fun failedNativeCaptureCannotRegisterCopyOrEvictAnyTab() = runBlocking {
        retain()
        val prepared = EditorRecoveryCopies(state, files).prepare(selection(), "new.txt")
        val bridge = ActiveEditorBridge().also { it.snapshot = { error("Native capture failed") } }
        val old = state.document("existing.txt")
        assertTrue(runCatching { openRecoveryCopyTab(state, prepared, listOf("existing.txt"), "existing.txt", bridge::captureForSave) }.isFailure)
        assertSame(old, state.peek("existing.txt")); assertNull(state.peek("new.txt"))
        assertEquals("older", snapshot().conflictBuffers["original.txt"])
    }

    @Test fun allDirtyTabCapacityCannotRegisterCopyOrEvictAnyTab() = runBlocking {
        retain()
        val tabs = (1..32).map { "$it.txt" }
        tabs.forEach { state.document(it).restoreUnsaved("unsaved", DiskFileVersion.fromText(null)) }
        val prepared = EditorRecoveryCopies(state, files).prepare(selection(), "new.txt")
        assertTrue(runCatching { openRecoveryCopyTab(state, prepared, tabs, tabs.first()) { } }.isFailure)
        assertNull(state.peek("new.txt"))
        assertEquals(tabs.toSet(), state.allDocuments().map { it.path }.toSet())
        assertTrue(state.allDocuments().all { it.dirty })
    }

    @Test fun totalByteBudgetIncludesRetainedOriginalPlusDuplicate() = runBlocking {
        val bound = 20L * 1024 * 1024
        for (path in listOf("one.txt", "two.txt")) state.retainRecoveryConflict(path, null,
            EditorRecoveryBuffer(path, path, null, Any(), bound) { "copy" })
        assertTrue(runCatching { EditorRecoveryCopies(state, files).prepare(state.retainedCopies().first(), "new.txt") }.isFailure)
        assertTrue(state.allDocuments().isEmpty())
        assertEquals(2, snapshot().conflictPaths.size)
    }
}

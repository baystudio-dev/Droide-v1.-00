package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.assertOriginatingFailure
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EditorSaveCoordinatorTest {
    private fun workspace(block: suspend (File, CoroutineDispatcher) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-save-coordinator-").toFile()
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(owner) { block(root, owner) } }
        finally { owner.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun exportScopeSaveLeavesUnrelatedBuffersUntouched() = workspace { root, _ ->
        root.resolve("src").mkdirs(); root.resolve("src/a.kt").writeText("A")
        root.resolve("outside.kt").writeText("outside")
        val files = FileRepository(root); val state = EditorWorkspaceState()
        val inside = state.document("src/a.kt"); val outside = state.document("outside.kt")
        inside.ensureLoaded(files); outside.ensureLoaded(files)
        inside.capture("saved-A"); outside.capture("unsaved-outside")
        val report = EditorSaveCoordinator(state, files, ActiveEditorBridge()).saveScope("src")
        assertTrue(report.ready); assertEquals(listOf("src/a.kt"), report.savedPaths)
        assertEquals("saved-A", root.resolve("src/a.kt").readText())
        assertEquals("outside", root.resolve("outside.kt").readText()); assertTrue(outside.dirty)
    }

    @Test fun exportScopeSaveRejectsNewEditsDuringAnotherScopedSave() = workspace { root, owner ->
        root.resolve("a.kt").writeText("A"); root.resolve("b.kt").writeText("B")
        val state = EditorWorkspaceState(); val a = state.document("a.kt"); val b = state.document("b.kt")
        val files = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write && it.path == "b.kt") withContext(owner) { a.capture("new-A") }
        })
        a.ensureLoaded(files); b.ensureLoaded(files); a.capture("save-A"); b.capture("save-B")
        val report = EditorSaveCoordinator(state, files, ActiveEditorBridge()).saveScope("")
        assertFalse(report.ready); assertTrue(report.issues.any { it.path == "a.kt" })
        assertEquals("save-A", root.resolve("a.kt").readText()); assertTrue(a.dirty)
    }

    @Test fun persistedSnapshotAndNewerEditsHaveSeparateOutcomes() = workspace { root, owner ->
        root.resolve("a.kt").writeText("A")
        val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        val files = FileRepository(root, onMutation = { withContext(owner) { doc.capture("C") } })
        doc.ensureLoaded(files); doc.capture("B")
        val notifications = mutableListOf<Triple<String, String, String>>()
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { path, saved, current ->
            notifications += Triple(path, saved, current)
        })
        val report = saves.saveDocument(doc)
        assertEquals(listOf("a.kt"), report.savedPaths)
        assertEquals(listOf(EditorSaveIssueKind.NEWER_EDITS), report.issues.map { it.kind })
        assertEquals(listOf(Triple("a.kt", "B", "C")), notifications)
        assertEquals("B", root.resolve("a.kt").readText()); assertEquals("B", doc.savedContent)
        assertEquals("C", doc.content); assertTrue(doc.dirty)
        assertTrue(doc.status.startsWith("Saved snapshot"))
    }

    @Test fun saveAllContinuesPastAConflictAndPreservesBothBuffers() = workspace { root, _ ->
        val state = EditorWorkspaceState(); val files = FileRepository(root)
        root.resolve("a.kt").writeText("A"); root.resolve("b.kt").writeText("B")
        val a = state.document("a.kt"); val b = state.document("b.kt")
        a.ensureLoaded(files); b.ensureLoaded(files); a.capture("draft-A"); b.capture("draft-B")
        root.resolve("a.kt").writeText("terminal-A")
        val report = EditorSaveCoordinator(state, files, ActiveEditorBridge()).saveAll()
        assertFalse(report.ready); assertEquals(listOf("b.kt"), report.savedPaths)
        assertTrue(report.issues.any { it.path == "a.kt" && it.kind == EditorSaveIssueKind.NOT_SAVED })
        assertEquals("terminal-A", root.resolve("a.kt").readText()); assertEquals("draft-A", a.content)
        assertEquals("draft-B", root.resolve("b.kt").readText()); assertFalse(b.dirty)
    }

    @Test fun saveAllRechecksAnEarlierFileAfterTheNextFileSuspends() = workspace { root, owner ->
        val state = EditorWorkspaceState(); val a = state.document("a.kt"); val b = state.document("b.kt")
        root.resolve("a.kt").writeText("A"); root.resolve("b.kt").writeText("B")
        val files = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write && it.path == "b.kt") withContext(owner) { a.capture("new-A") }
        })
        a.ensureLoaded(files); b.ensureLoaded(files); a.capture("saved-A"); b.capture("saved-B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge())
        val report = saves.saveAll()
        assertFalse(report.ready)
        assertTrue(report.issues.any { it.path == "a.kt" && it.kind == EditorSaveIssueKind.NEWER_EDITS })
        assertEquals("saved-A", root.resolve("a.kt").readText()); assertEquals("new-A", a.content)
        var ran = false; saves.continueIfReady(a) { ran = true }; assertFalse(ran)
    }

    @Test fun readinessRechecksChangesDuringRecoveryPersistence() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("B")
        val bridge = ActiveEditorBridge(); val saves = EditorSaveCoordinator(state, files, bridge)
        assertTrue(saves.saveAll().ready)
        bridge.snapshot = { doc.capture("typed while recovery was saving") }
        assertFalse(saves.readiness().ready)
        var closed = false; saves.continueIfReady(doc) { closed = true }; assertFalse(closed)
        assertEquals("B", root.resolve("a.kt").readText())
        assertEquals("typed while recovery was saving", doc.content)
    }

    @Test fun suspendingPreOperationRecoveryCannotAdmitANewerUnsavedBuffer() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge())
        val preparing = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        coroutineScope {
            val attempt = async { saves.saveAllBefore { preparing.complete(Unit); resume.await() } }
            withTimeout(5000) { preparing.await() }; doc.capture("C"); resume.complete(Unit)
            assertFalse(withTimeout(5000) { attempt.await() }.ready)
        }
        assertEquals("B", root.resolve("a.kt").readText()); assertEquals("C", doc.content); assertTrue(doc.dirty)
    }

    @Test fun postCommitTypingDuringLanguageServerNotificationIsReportedAsUnsaved() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ -> doc.capture("C") })
        assertFalse(saves.saveDocument(doc).ready)
        assertEquals("B", root.resolve("a.kt").readText()); assertEquals("C", doc.content)
        assertTrue(doc.status.startsWith("Saved snapshot")); assertTrue(doc.dirty)
    }

    @Test fun aRenameDuringNotificationRequiresReviewInsteadOfClosingAnOldTabPath() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ ->
            root.resolve("a.kt").renameTo(root.resolve("b.kt")); state.remapPersistedPath("a.kt", "b.kt")
        })
        val report = saves.saveDocument(doc)
        assertFalse(report.ready)
        assertTrue(report.issues.any { it.kind == EditorSaveIssueKind.DOCUMENT_CHANGED })
        var closed = false; saves.continueIfReady(doc, expectedPath = "a.kt") { closed = true }; assertFalse(closed)
        assertEquals("B", root.resolve("b.kt").readText()); assertSame(doc, state.peek("b.kt"))
    }

    @Test fun optionalNotificationRetryDoesNotRetainAnInactiveCleanBuffer() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ -> error("LSP offline") })
        assertTrue(saves.saveDocument(doc).ready); assertTrue(doc.saveNotificationPending)
        assertTrue(doc.tryUnloadClean()); assertFalse(doc.loaded); assertFalse(doc.saveNotificationPending)
        assertTrue(saves.readiness().ready)
    }

    @Test fun notificationFailureAfterCleanBufferReclamationCannotBlockTheNextSaveAll() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ ->
            assertTrue(doc.tryUnloadClean()); error("late LSP failure")
        })
        val report = saves.saveDocument(doc)
        assertTrue(report.ready); assertEquals(1, report.warnings.size)
        assertFalse(doc.loaded); assertFalse(doc.saveNotificationPending)
        assertTrue(saves.saveAll().ready); assertTrue(saves.readiness().ready)
        assertEquals("B", root.resolve("a.kt").readText())
    }

    @Test fun newlyDirtyFileIsReportedWithoutAnUnboundedSaveLoop() = workspace { root, owner ->
        val state = EditorWorkspaceState(); val a = state.document("a.kt"); val c = state.document("c.kt")
        root.resolve("a.kt").writeText("A"); root.resolve("c.kt").writeText("C")
        val files = FileRepository(root, onMutation = { withContext(owner) { c.capture("new-C") } })
        a.ensureLoaded(files); c.ensureLoaded(files); a.capture("saved-A")
        val report = EditorSaveCoordinator(state, files, ActiveEditorBridge()).saveAll()
        assertEquals(listOf("a.kt"), report.savedPaths)
        assertTrue(report.issues.any { it.path == "c.kt" && it.kind == EditorSaveIssueKind.NEWER_EDITS })
        assertEquals("C", root.resolve("c.kt").readText()); assertEquals("new-C", c.content)
    }

    @Test fun captureExceptionStopsSaveWithoutPublishingStaleText() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("old model")
        val bridge = ActiveEditorBridge().apply { snapshot = { error("native view temporarily unavailable") } }
        val report = EditorSaveCoordinator(state, files, bridge).saveDocument(doc)
        assertEquals(EditorSaveIssueKind.CAPTURE_FAILED, report.issues.single().kind)
        assertEquals("A", root.resolve("a.kt").readText()); assertTrue(doc.dirty)
    }

    @Test fun captureCancellationPropagatesWithoutConvertingToSaveError() = workspace { root, _ ->
        val files = FileRepository(root); val state = EditorWorkspaceState()
        val bridge = ActiveEditorBridge().apply { snapshot = { throw CancellationException("capture cancelled") } }
        val failure = runCatching { EditorSaveCoordinator(state, files, bridge).saveAll() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun fatalCaptureFailureIsNeverReportedAsAnOrdinarySaveError() = workspace { root, _ ->
        val files = FileRepository(root); val state = EditorWorkspaceState()
        val failure = AssertionError("fatal fixture")
        val bridge = ActiveEditorBridge().apply { snapshot = { throw failure } }
        assertOriginatingFailure(failure, runCatching { EditorSaveCoordinator(state, files, bridge).saveAll() }.exceptionOrNull())
    }

    @Test fun cleanSaveDoesNotRewriteOrNotify() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        var mutations = 0; var notifications = 0
        val files = FileRepository(root, beforeMutation = { mutations++ }, onMutation = { mutations++ })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt"); doc.ensureLoaded(files)
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ -> notifications++ })
        repeat(5) { assertTrue(saves.saveDocument(doc).ready) }
        assertEquals(0, mutations); assertEquals(0, notifications)
        assertEquals("A", root.resolve("a.kt").readText())
    }

    @Test fun emptyNewFileIsActuallyCreatedEvenThoughItHasNoTextDifference() = workspace { root, _ ->
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("empty.kt")
        doc.ensureLoaded(files); assertFalse(doc.dirty)
        assertTrue(EditorSaveCoordinator(state, files, ActiveEditorBridge()).saveAll().ready)
        assertTrue(root.resolve("empty.kt").isFile); assertEquals(0L, root.resolve("empty.kt").length())
    }

    @Test fun pendingMirrorSyncRetriesWithoutRepublishingTheFile() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        var failSync = true; var publications = 0; var syncs = 0
        val files = FileRepository(root, beforeMutation = { publications++ }, onMutation = {
            if (it is FileRepository.Mutation.Write) { syncs++; if (failSync) error("mirror offline") }
        })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge())
        assertFalse(saves.saveDocument(doc).ready); assertFalse(doc.dirty); assertTrue(doc.saveSynchronizationPending)
        assertFalse(doc.tryUnloadClean())
        val file = root.resolve("a.kt").toPath()
        Files.setLastModifiedTime(file, FileTime.fromMillis(1234567000))
        val before = Files.readAttributes(file, BasicFileAttributes::class.java)
        failSync = false
        assertTrue(saves.saveAll().ready)
        val after = Files.readAttributes(file, BasicFileAttributes::class.java)
        assertEquals(1, publications); assertEquals(2, syncs)
        assertEquals(before.fileKey(), after.fileKey()); assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
        assertFalse(doc.saveSynchronizationPending); assertEquals("B", doc.savedContent)
    }

    @Test fun pendingSyncRetryNeverOverwritesANewerTerminalFile() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A"); var failSync = true; var publications = 0
        val files = FileRepository(root, beforeMutation = { publications++ }, onMutation = {
            if (failSync && it is FileRepository.Mutation.Write) error("mirror offline")
        })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge())
        assertFalse(saves.saveDocument(doc).ready)
        root.resolve("a.kt").writeText("terminal-D"); failSync = false
        assertFalse(saves.saveDocument(doc).ready)
        assertEquals("terminal-D", root.resolve("a.kt").readText()); assertEquals(1, publications)
        assertTrue(doc.saveSynchronizationPending)
    }

    @Test fun lspNotificationFailureCanBeRetriedWithoutASecondDiskWrite() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A"); var publications = 0; var notifications = 0
        val files = FileRepository(root, beforeMutation = { publications++ })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, saved, _ ->
            assertEquals("B", saved); if (++notifications == 1) error("language server offline")
        })
        val report = saves.saveDocument(doc)
        assertTrue("An optional LSP must not block a local build", report.ready)
        assertEquals(1, report.warnings.size); assertTrue(doc.saveNotificationPending)
        assertFalse(doc.saveSynchronizationPending)
        assertTrue(doc.status.startsWith("Saved locally")); assertFalse(doc.dirty)
        assertTrue(saves.saveDocument(doc).ready)
        assertEquals(1, publications); assertEquals(2, notifications); assertFalse(doc.saveNotificationPending)
        assertFalse(doc.status.contains("pending"))
    }

    @Test fun cancelledNotificationPreservesTheCommittedBaselineAndRetryState() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A"); var publications = 0; var cancel = true
        val files = FileRepository(root, beforeMutation = { publications++ })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ ->
            if (cancel) throw CancellationException("notification interrupted")
        })
        assertTrue(runCatching { saves.saveDocument(doc) }.exceptionOrNull() is CancellationException)
        assertEquals("B", doc.savedContent); assertTrue(doc.saveNotificationPending); assertFalse(doc.dirty)
        cancel = false; assertTrue(saves.saveDocument(doc).ready); assertEquals(1, publications)
    }

    @Test fun repeatedConcurrentSaveOfTheSameVersionPublishesOnce() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A"); var publications = 0
        val files = FileRepository(root, beforeMutation = { publications++ })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge())
        val reports = coroutineScope { (1..8).map { async { saves.saveDocument(doc) } }.awaitAll() }
        assertTrue(reports.all { it.ready }); assertEquals(1, publications)
        assertEquals("B", root.resolve("a.kt").readText())
    }

    @Test fun queuedSaveUsesTheNewerBufferWithoutOverlappingWrites() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var publications = 0
        val files = FileRepository(root, beforeMutation = {
            if (++publications == 1) { entered.complete(Unit); release.await() }
        })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.capture("B")
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge())
        coroutineScope {
            val first = async { saves.saveDocument(doc) }
            withTimeout(5000) { entered.await() }; doc.capture("C")
            val next = async { saves.saveDocument(doc) }; release.complete(Unit)
            assertFalse(withTimeout(5000) { first.await() }.ready)
            assertTrue(withTimeout(5000) { next.await() }.ready)
        }
        assertEquals(2, publications); assertEquals("C", root.resolve("a.kt").readText()); assertFalse(doc.dirty)
    }

    @Test fun aClosedDocumentCannotBeResurrectedBySaveAndClose() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge())
        state.forgetAtOrUnder("a.kt")
        assertFalse(saves.saveDocument(doc).ready)
        var closed = false; saves.continueIfReady(doc) { closed = true }; assertFalse(closed)
        assertNull(state.peek("a.kt")); assertEquals("A", root.resolve("a.kt").readText())
    }
}

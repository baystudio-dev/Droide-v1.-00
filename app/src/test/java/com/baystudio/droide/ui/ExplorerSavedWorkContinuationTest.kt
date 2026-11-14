package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExplorerSavedWorkContinuationTest {
    private fun workspace(block: suspend (File, CoroutineDispatcher) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-mutation-continue-").toFile()
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(owner) { block(root, owner) } }
        finally { owner.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun savedNotificationCompletesThenRealMoveCanProceed() = workspace { root, owner ->
        root.resolve("a.kt").writeText("A")
        val state = EditorWorkspaceState()
        lateinit var authority: EditorWorkspaceDocumentAuthority
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) },
            onMutation = { authority.persistedMutation(it) })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.markSaveNotificationPending()
        var notifications = 0
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ -> notifications++ })
        assertTrue(doc.blocksPersistedPathMutation)
        val report = saves.finishSavedPathWork(listOf("a.kt", "b.kt"))
        assertTrue(report.ready); assertEquals(1, notifications); assertTrue(report.savedPaths.isEmpty())
        assertFalse(doc.blocksPersistedPathMutation)
        assertTrue(files.move("a.kt", "b.kt", requireExistingParent = true))
        assertSame(doc, state.peek("b.kt")); assertEquals("A", root.resolve("b.kt").readText())
    }

    @Test fun savedSyncCompletesWithoutSecondWriteThenDeleteCanProceed() = workspace { root, owner ->
        root.resolve("a.kt").writeText("A")
        val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        val failing = FileRepository(root, onMutation = { error("sync temporarily offline") })
        doc.ensureLoaded(failing); doc.capture("B")
        assertNotNull(doc.save(failing).getOrThrow().synchronizationFailure)
        lateinit var authority: EditorWorkspaceDocumentAuthority
        var writes = 0
        val files = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write) writes++
            authority.validatePersistedMutation(it)
        }, onMutation = { authority.persistedMutation(it) })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        val report = EditorSaveCoordinator(state, files, ActiveEditorBridge()).finishSavedPathWork(listOf("a.kt"))
        assertTrue(report.ready); assertEquals(0, writes); assertFalse(doc.saveSynchronizationPending)
        assertEquals("B", root.resolve("a.kt").readText())
        assertTrue(files.delete("a.kt")); assertNull(state.peek("a.kt"))
    }

    @Test fun dirtyBufferIsNeverSavedImplicitly() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        var writes = 0; val files = FileRepository(root, beforeMutation = { writes++ })
        val state = EditorWorkspaceState(); val doc = state.document("a.kt")
        doc.ensureLoaded(files); doc.capture("draft")
        val report = EditorSaveCoordinator(state, files, ActiveEditorBridge()).finishSavedPathWork(listOf("a.kt"))
        assertFalse(report.ready); assertEquals(0, writes); assertEquals("A", root.resolve("a.kt").readText())
        assertEquals("draft", doc.content); assertTrue(doc.dirty)
    }

    @Test fun newEmptyBufferIsNotCreatedByPathPreparation() = workspace { root, _ ->
        val files = FileRepository(root); val state = EditorWorkspaceState(); val doc = state.document("new.kt")
        doc.ensureLoaded(files)
        assertFalse(EditorSaveCoordinator(state, files, ActiveEditorBridge()).finishSavedPathWork(listOf("new.kt")).ready)
        assertFalse(root.resolve("new.kt").exists())
    }

    @Test fun unrelatedPendingWorkAndPrefixLookalikesAreUntouched() = workspace { root, _ ->
        root.resolve("src").mkdirs(); root.resolve("src-other").mkdirs()
        root.resolve("src/a.kt").writeText("A"); root.resolve("src-other/b.kt").writeText("B")
        val files = FileRepository(root); val state = EditorWorkspaceState()
        val a = state.document("src/a.kt"); val b = state.document("src-other/b.kt")
        a.ensureLoaded(files); b.ensureLoaded(files); a.markSaveNotificationPending(); b.markSaveNotificationPending()
        val notified = mutableListOf<String>()
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { p, _, _ -> notified += p })
        assertTrue(saves.finishSavedPathWork(listOf("src")).ready)
        assertEquals(listOf("src/a.kt"), notified); assertTrue(b.saveNotificationPending)
    }

    @Test fun stillFailingNotificationIsBoundedAndRemainsBlocking() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A"); val files = FileRepository(root); val state = EditorWorkspaceState()
        val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.markSaveNotificationPending()
        var calls = 0
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ -> calls++; error("offline") })
        val report = saves.finishSavedPathWork(listOf("a.kt", "a.kt"))
        assertFalse(report.ready); assertEquals(1, calls); assertTrue(doc.saveNotificationPending)
        assertTrue(doc.blocksPersistedPathMutation)
    }

    @Test fun cancellationPreservesPendingWork() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A"); val files = FileRepository(root); val state = EditorWorkspaceState()
        val doc = state.document("a.kt"); doc.ensureLoaded(files); doc.markSaveNotificationPending()
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ -> throw CancellationException("cancelled") })
        assertTrue(runCatching { saves.finishSavedPathWork(listOf("a.kt")) }.exceptionOrNull() is CancellationException)
        assertTrue(doc.saveNotificationPending); assertEquals("A", root.resolve("a.kt").readText())
    }

    @Test fun typingDuringEarlierNotificationDoesNotSaveLaterDirtyBuffer() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A"); root.resolve("b.kt").writeText("B")
        val files = FileRepository(root); val state = EditorWorkspaceState()
        val a = state.document("a.kt"); val b = state.document("b.kt")
        a.ensureLoaded(files); b.ensureLoaded(files); a.markSaveNotificationPending(); b.markSaveNotificationPending()
        val notified = mutableListOf<String>()
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { p, _, _ ->
            notified += p; if (p == "a.kt") b.capture("new typing")
        })
        assertFalse(saves.finishSavedPathWork(listOf("a.kt", "b.kt")).ready)
        assertEquals(listOf("a.kt"), notified); assertEquals("B", root.resolve("b.kt").readText()); assertTrue(b.dirty)
    }

    @Test fun newlyOpenedDirtyDocumentIsIncludedInFinalScopeCheck() = workspace { root, _ ->
        root.resolve("src").mkdirs(); root.resolve("src/a.kt").writeText("A"); root.resolve("src/b.kt").writeText("B")
        val files = FileRepository(root); val state = EditorWorkspaceState(); val a = state.document("src/a.kt")
        a.ensureLoaded(files); a.markSaveNotificationPending()
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ ->
            state.document("src/b.kt").also { it.ensureLoaded(files); it.capture("new buffer") }
        })
        val report = saves.finishSavedPathWork(listOf("src"))
        assertFalse(report.ready); assertTrue(report.issues.any { it.path == "src/b.kt" })
        assertEquals("B", root.resolve("src/b.kt").readText())
    }


    @Test fun documentReplacementDuringNotificationRejectsContinuation() = workspace { root, _ ->
        root.resolve("a.kt").writeText("A")
        val files = FileRepository(root); val state = EditorWorkspaceState()
        val old = state.document("a.kt"); old.ensureLoaded(files); old.markSaveNotificationPending()
        val saves = EditorSaveCoordinator(state, files, ActiveEditorBridge(), notifySaved = { _, _, _ ->
            state.forgetAtOrUnder("a.kt")
            state.document("a.kt").also { it.ensureLoaded(files) }
        })
        val report = saves.finishSavedPathWork(listOf("a.kt"))
        assertFalse(report.ready)
        assertTrue(report.issues.any { it.kind == EditorSaveIssueKind.DOCUMENT_CHANGED })
        assertNotSame(old, state.peek("a.kt")); assertEquals("A", root.resolve("a.kt").readText())
    }

    @Test fun localFolderDeleteExplainsRetentionNotImmediateReclaim() {
        val local = explorerDeleteExplanation("build", directory = true, safLinked = false)
        assertTrue(local.contains("recovery data")); assertTrue(local.contains("storage will not be freed"))
        assertFalse(explorerDeleteExplanation("a.kt", false, false).contains("recovery data"))
        assertTrue(explorerDeleteExplanation("build", true, true).contains("linked folder"))
    }
}

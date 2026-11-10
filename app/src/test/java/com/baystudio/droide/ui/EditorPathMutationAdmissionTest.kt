package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorPathMutationAdmissionTest {
    @get:Rule val temp = TemporaryFolder()

    private class Fixture(
        val root: File, val owner: CoroutineDispatcher, val state: EditorWorkspaceState,
        val document: EditorDocument, val files: FileRepository, val authority: EditorWorkspaceDocumentAuthority,
    )

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(15_000) {
            val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val root = temp.newFolder()
            root.resolve("main.txt").writeText("original")
            val state = EditorWorkspaceState()
            lateinit var authority: EditorWorkspaceDocumentAuthority
            val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) },
                onMutation = { authority.persistedMutation(it) })
            authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
            val document = state.document("main.txt").also { it.ensureLoaded(files) }
            Fixture(root, owner, state, document, files, authority).block()
        }
    }

    private suspend fun Fixture.assertBlocked() {
        assertTrue(state.hasPendingPathMutationAtOrUnder("main.txt"))
        for (mutation in listOf(FileRepository.Mutation.Move("main.txt", "moved.txt"),
            FileRepository.Mutation.Delete("main.txt"))) {
            assertTrue(runCatching { authority.validatePersistedMutation(mutation) }.exceptionOrNull()
                is WorkspaceDocumentConflictException)
            assertTrue(runCatching { authority.persistedMutation(mutation) }.exceptionOrNull()
                is WorkspaceDocumentConflictException)
        }
        assertSame(document, state.peek("main.txt"))
        assertNull(state.peek("moved.txt"))
    }

    @Test fun notificationPendingBlocksMutationUntilNotificationCompletes() = fixture {
        document.markSaveNotificationPending()
        assertFalse(document.dirty)
        assertBlocked()
        assertTrue(runCatching { files.move("main.txt", "moved.txt", requireExistingParent = true) }.isFailure)
        assertEquals("original", root.resolve("main.txt").readText())
        assertFalse(root.resolve("moved.txt").exists())
        document.completeSaveNotification()
        assertFalse(state.hasPendingPathMutationAtOrUnder("main.txt"))
        assertTrue(files.move("main.txt", "moved.txt", requireExistingParent = true))
        assertSame(document, state.peek("moved.txt"))
    }

    @Test fun optionalNotificationDoesNotPermanentlyBlockCleanUnload() = fixture {
        document.markSaveNotificationPending()
        assertTrue(document.blocksPersistedPathMutation)
        document.unloadClean()
        assertFalse(document.saveNotificationPending)
        assertFalse(document.blocksPersistedPathMutation)
        authority.validatePersistedMutation(FileRepository.Mutation.Move("main.txt", "moved.txt"))
    }

    @Test fun synchronizationPendingBlocksMoveButLegitimateSaveRetryUnblocks() = fixture {
        val failing = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) error("sync unavailable") })
        document.capture("saved locally")
        assertNotNull(document.save(failing).getOrThrow().synchronizationFailure)
        assertFalse(document.dirty)
        assertTrue(document.saveSynchronizationPending)
        assertBlocked()
        assertEquals("saved locally", root.resolve("main.txt").readText())
        assertTrue(document.save(files).isSuccess)
        assertFalse(document.saveSynchronizationPending)
        assertFalse(document.blocksPersistedPathMutation)
        assertTrue(files.move("main.txt", "moved.txt", requireExistingParent = true))
        assertEquals("saved locally", root.resolve("moved.txt").readText())
    }

    @Test fun cleanModelDuringSuspendedSaveStillBlocksPathMutation() = fixture {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val savingFiles = FileRepository(root, beforeMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) withContext(owner) {
                document.capture("original")
                entered.complete(Unit)
                release.await()
            }
        })
        document.capture("captured save")
        coroutineScope {
            val saving = async { document.save(savingFiles) }
            try {
                entered.await()
                assertFalse(document.dirty)
                assertTrue(document.isSaveInProgress)
                assertBlocked()
            } finally { release.complete(Unit) }
            saving.await().getOrThrow()
        }
        assertFalse(document.isSaveInProgress)
        assertEquals("original", document.content)
        assertEquals("captured save", document.savedContent)
        assertTrue(document.dirty)
    }

    @Test fun folderGuardIncludesPendingDescendantsWithoutBlockingSiblingPrefix() = fixture {
        root.resolve("folder").mkdir()
        root.resolve("folder/a.txt").writeText("child")
        val child = state.document("folder/a.txt").also { it.ensureLoaded(files) }
        child.markSaveNotificationPending()
        assertTrue(state.hasPendingPathMutationAtOrUnder("folder"))
        assertFalse(state.hasPendingPathMutationAtOrUnder("fold"))
        assertFalse(state.hasPendingPathMutationAtOrUnder("folder-other"))
        assertTrue(runCatching { authority.validatePersistedMutation(FileRepository.Mutation.Move("folder", "renamed")) }
            .exceptionOrNull() is WorkspaceDocumentConflictException)
        child.completeSaveNotification()
        authority.validatePersistedMutation(FileRepository.Mutation.Move("folder", "renamed"))
    }

    @Test fun deferredRecoveryRemainsUnreadAndUnremapped() = fixture {
        var reads = 0
        val reference = EditorRecoveryBuffer("main.txt", "retained", null, Any()) { reads++; "recovered" }
        document.restoreDeferred(reference)
        assertBlocked()
        assertEquals(0, reads)
        assertSame(reference, document.pendingRecoveryBuffer)
        assertEquals("original", root.resolve("main.txt").readText())
    }

    @Test fun nativeOnlyOptimizedEditsRemainCapturedAtOriginalPath() = fixture {
        val text = "x".repeat(3 * 1024 * 1024)
        root.resolve("main.txt").writeText(text)
        document.reload(files)
        assertTrue(document.largeFileOptimized)
        document.captureInsert(0, "native pending")
        assertTrue(document.requiresBufferCapture)
        assertBlocked()
        assertEquals(text, document.content)
        assertEquals(text, root.resolve("main.txt").readText())
    }

    @Test fun refreshCannotRemoveOrOverwriteCleanPendingEditorWork() = fixture {
        document.markSaveNotificationPending()
        root.resolve("main.txt").delete()
        assertTrue(runCatching { authority.persistedMutation(FileRepository.Mutation.Sync("main.txt")) }
            .exceptionOrNull() is WorkspaceDocumentConflictException)
        assertSame(document, state.peek("main.txt"))
        assertEquals("original", document.content)
        root.resolve("main.txt").writeText("foreign")
        val refreshed = authority.reconcilePersistedWorkspace()
        assertEquals(listOf("main.txt"), refreshed.conflicts)
        assertEquals("original", document.content)
        assertTrue(document.saveNotificationPending)
        assertEquals("foreign", root.resolve("main.txt").readText())
    }

    @Test fun latePendingWorkAfterCommittedMoveIsRetainedWithoutUndo() = fixture {
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure = true
            }
        }
        val repository = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
            onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Move) withContext(owner) { document.markSaveNotificationPending() }
                authority.persistedMutation(mutation)
            }, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        val observed = repository.observeMove("main.txt", "moved.txt")
        assertTrue(runCatching { repository.moveObserved(observed) }.isFailure)
        assertTrue(observed.committed)
        assertFalse(observed.uncertain)
        assertNotNull(observed.failure)
        assertNull(observed.undo)
        assertSame(document, state.peek("main.txt"))
        assertNull(state.peek("moved.txt"))
        assertTrue(document.saveNotificationPending)
        assertEquals("original", root.resolve("moved.txt").readText())
        assertFalse(root.resolve("main.txt").exists())
    }

    @Test fun uncertainMoveFallbackPreservesPendingModelAndForeignSource() = fixture {
        val repository = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
            onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Move) {
                    root.resolve("main.txt").writeText("foreign source")
                    withContext(owner) { document.markSaveNotificationPending() }
                }
                authority.persistedMutation(mutation)
            })
        val observed = repository.observeMove("main.txt", "moved.txt")
        assertTrue(runCatching { repository.moveObserved(observed) }.isFailure)
        assertFalse(observed.committed)
        assertTrue(observed.uncertain)
        assertNull(observed.undo)
        assertSame(document, state.peek("main.txt"))
        assertTrue(document.saveNotificationPending)
        assertEquals("original", document.content)
        assertEquals("foreign source", root.resolve("main.txt").readText())
        assertEquals("original", root.resolve("moved.txt").readText())
    }
    @Test fun pendingStateIntroducedByParticipantPreparationPreventsLocalMove() = fixture {
        var moved = false
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                    withContext(owner) { document.markSaveNotificationPending() }
                }
            }
        }
        val repository = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
            onMutation = { authority.persistedMutation(it) }, newFilePublisher = NewProjectFilePublisher.platform,
            pathMover = WorkspacePathMove { _, _ -> moved = true; error("Unexpected local move") }, participant = participant)
        val observed = repository.observeMove("main.txt", "moved.txt")
        assertTrue(runCatching { repository.moveObserved(observed) }.exceptionOrNull() is WorkspaceDocumentConflictException)
        assertFalse(moved)
        assertFalse(observed.committed)
        assertFalse(observed.uncertain)
        assertNull(observed.undo)
        assertEquals("original", root.resolve("main.txt").readText())
        assertSame(document, state.peek("main.txt"))
        assertTrue(document.saveNotificationPending)
    }

    @Test fun cancellationAfterCommittedMoveKeepsReceiptAndPendingBuffer() = fixture {
        val cancelled = CancellationException("cancel after rename")
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure = true
            }
        }
        val repository = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
            onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Move) {
                    withContext(owner) { document.markSaveNotificationPending() }
                    throw cancelled
                }
                authority.persistedMutation(mutation)
            }, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        val observed = repository.observeMove("main.txt", "moved.txt")
        assertOriginatingFailure(cancelled, runCatching { repository.moveObserved(observed) }.exceptionOrNull())
        assertTrue(observed.committed)
        assertSame(cancelled, observed.failure)
        assertNull(observed.undo)
        assertSame(document, state.peek("main.txt"))
        assertTrue(document.saveNotificationPending)
        assertEquals("original", document.content)
        assertEquals("original", root.resolve("moved.txt").readText())
    }

    @Test fun missingCleanAncestorCannotRemovePendingDescendantDuringRefresh() = fixture {
        val ancestor = state.document("absent")
        val child = state.document("absent/child.txt")
        child.restoreUnsaved("retained child")
        val result = authority.reconcilePersistedWorkspace()
        assertTrue(result.conflicts.contains("absent/child.txt"))
        assertSame(ancestor, state.peek("absent"))
        assertSame(child, state.peek("absent/child.txt"))
        assertEquals("retained child", child.content)
        assertTrue(child.dirty)
    }

    @Test fun historicalRecoveryCopiesSurviveSingleRenameAndDeleteAtOriginalKeys() = fixture {
        val reference = EditorRecoveryBuffer("older.txt", "retained-blob", "retained-hash", Any(),
            diskVersion = DiskFileVersion.fromText("older")) { error("Historical copy must not be loaded") }
        state.retainRecoveryConflict("main.txt", "older recovery text", null, DiskFileVersion.fromText("older main"))
        state.retainRecoveryConflict("older.txt", null, reference)
        val before = checkNotNull(state.recoverySnapshot(emptyList(), ""))
        assertTrue(files.move("main.txt", "moved.txt", requireExistingParent = true))
        files.delete("moved.txt")
        val after = checkNotNull(state.recoverySnapshot(emptyList(), ""))
        assertEquals(before.conflictBuffers, after.conflictBuffers)
        assertEquals(before.conflictDiskVersions, after.conflictDiskVersions)
        assertEquals(before.deferredConflictBuffers, after.deferredConflictBuffers)
        assertSame(reference, after.deferredConflictBuffers["older.txt"])
        assertEquals("older recovery text", after.conflictBuffers["main.txt"])
        assertFalse(after.conflictBuffers.containsKey("moved.txt"))
    }

    @Test fun sameVersionDocumentReplacementDuringRefreshIsNotMutated() = fixture {
        root.resolve("unloaded.txt").writeText("disk contents")
        val original = state.document("unloaded.txt")
        var replacement: EditorDocument? = null
        var dispatches = 0
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                owner.dispatch(context, Runnable {
                    if (++dispatches == 2) {
                        state.forgetAtOrUnder("unloaded.txt")
                        replacement = state.document("unloaded.txt")
                    }
                    block.run()
                })
            }
        }
        val observed = EditorWorkspaceDocumentAuthority(state, dispatcher, files = files)
        val failure = runCatching { observed.persistedMutation(FileRepository.Mutation.Sync("unloaded.txt")) }.exceptionOrNull()
        assertTrue(failure is WorkspaceDocumentConflictException)
        assertTrue((failure as WorkspaceDocumentConflictException).paths.contains("unloaded.txt"))
        assertNotSame(original, replacement)
        assertSame(replacement, state.peek("unloaded.txt"))
        assertFalse(checkNotNull(replacement).loaded)
    }

    @Test fun pendingFlagIntroducedDuringRefreshCannotBeOverwrittenByObservedDiskText() = fixture {
        root.resolve("main.txt").writeText("external update")
        var dispatches = 0
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                owner.dispatch(context, Runnable {
                    if (++dispatches == 2) document.markSaveNotificationPending()
                    block.run()
                })
            }
        }
        val observed = EditorWorkspaceDocumentAuthority(state, dispatcher, files = files)
        val failure = runCatching { observed.persistedMutation(FileRepository.Mutation.Sync("main.txt")) }.exceptionOrNull()
        assertTrue(failure is WorkspaceDocumentConflictException)
        assertEquals("original", document.content)
        assertEquals("external update", root.resolve("main.txt").readText())
        assertTrue(document.saveNotificationPending)
    }

    @Test fun guardedReloadDoesNotPublishWhenNotificationArrivesDuringIo() = fixture {
        document.unloadClean()
        assertFalse(document.loaded)
        val published = queuedReload(document, files,
            canPublish = { state.peek("main.txt") === document && !document.blocksPersistedPathMutation },
            duringIo = { document.markSaveNotificationPending() })
        assertFalse(published)
        assertFalse(document.loaded)
        assertEquals("", document.content)
        assertTrue(document.saveNotificationPending)
        document.completeSaveNotification()
        assertTrue(document.reloadForPersistedChange(files) {
            state.peek("main.txt") === document && !document.blocksPersistedPathMutation
        })
        assertEquals("original", document.content)
    }

    @Test fun guardedReloadDoesNotPublishIntoAReplacedWorkspaceDocument() = fixture {
        document.unloadClean()
        var replacement: EditorDocument? = null
        val published = queuedReload(document, files,
            canPublish = { state.peek("main.txt") === document && !document.blocksPersistedPathMutation },
            duringIo = {
                state.forgetAtOrUnder("main.txt")
                replacement = state.document("main.txt")
            })
        assertFalse(published)
        assertFalse(document.loaded)
        assertSame(replacement, state.peek("main.txt"))
        assertFalse(checkNotNull(replacement).loaded)
        assertEquals("original", root.resolve("main.txt").readText())
    }

    /** Hold real IO context entry until the test has applied the concurrent editor transition.
     * Queuing only the owner dispatcher races with fast withContext(IO) completion. */
    private suspend fun queuedReload(
        document: EditorDocument, files: FileRepository, canPublish: () -> Boolean, duringIo: () -> Unit,
    ): Boolean {
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.put(block) }
        }
        val ownerThread = Thread.currentThread()
        val ioEntered = java.util.concurrent.CountDownLatch(1)
        val releaseIo = java.util.concurrent.CountDownLatch(1)
        val gate = object : ThreadContextElement<Unit>, kotlin.coroutines.AbstractCoroutineContextElement(
            object : kotlin.coroutines.CoroutineContext.Key<ThreadContextElement<Unit>> {}
        ) {
            override fun updateThreadContext(context: kotlin.coroutines.CoroutineContext) {
                if (Thread.currentThread() !== ownerThread) {
                    ioEntered.countDown()
                    check(releaseIo.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "IO gate timed out" }
                }
            }
            override fun restoreThreadContext(context: kotlin.coroutines.CoroutineContext, oldState: Unit) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher + gate)
        fun next() = checkNotNull(queue.poll(5, java.util.concurrent.TimeUnit.SECONDS)) { "Reload continuation missing" }
        try {
            val work = scope.async { document.reloadForPersistedChange(files, canPublish) }
            next().run() // IO cannot complete until releaseIo is opened.
            assertTrue("Repository IO entered", ioEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(work.isCompleted)
            duringIo()
            releaseIo.countDown()
            while (!work.isCompleted) next().run()
            return work.await()
        } finally { releaseIo.countDown(); scope.cancel() }
    }

}

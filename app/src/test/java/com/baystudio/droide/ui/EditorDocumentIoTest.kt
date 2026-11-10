package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import com.baystudio.droide.core.ProjectFileSyncException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class EditorDocumentIoTest {
    @Test(timeout = 20_000) fun cancellationReturningFromSyncReceiptVerificationStillAcknowledgesCommittedSnapshot() = runBlocking {
        val root = Files.createTempDirectory("droide-cancelled-receipt-").toFile()
        val continuations = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { continuations.put(block) }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val syncEntered = java.util.concurrent.CountDownLatch(1)
        val allowSync = java.util.concurrent.CountDownLatch(1)
        fun next(): Runnable = requireNotNull(continuations.poll(5, java.util.concurrent.TimeUnit.SECONDS)) { "Save continuation did not arrive" }
        try {
            root.resolve("main.kt").writeText("A")
            var failSync = true
            val files = FileRepository(root, onMutation = {
                if (it is FileRepository.Mutation.Sync) {
                    syncEntered.countDown()
                    check(allowSync.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Sync gate was not released" }
                }
                if (it is FileRepository.Mutation.Write && failSync) error("mirror offline")
            })
            val doc = EditorDocument("main.kt"); doc.ensureLoaded(files); doc.capture("B")
            val job = scope.launch { doc.save(files) }

            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (syncEntered.count != 0L) {
                check(System.nanoTime() < deadline && !job.isCompleted) { "Synchronization callback was not reached" }
                continuations.poll(25, java.util.concurrent.TimeUnit.MILLISECONDS)?.run()
            }
            allowSync.countDown()
            val receiptReturn = next()
            assertEquals("B", root.resolve("main.kt").readText()); assertEquals("A", doc.savedContent)
            job.cancel(kotlinx.coroutines.CancellationException("cancel receipt return"))
            receiptReturn.run()
            repeat(10) { if (!job.isCompleted) next().run() }
            assertTrue(job.isCancelled); assertTrue(job.isCompleted)
            assertEquals("B", doc.savedContent); assertFalse(doc.dirty)
            failSync = false; doc.capture("C")
            assertTrue(doc.save(files).isSuccess); assertEquals("C", root.resolve("main.kt").readText())
        } finally {
            allowSync.countDown()
            val owner = requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job])
            owner.cancel()
            val cleanupDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!owner.isCompleted && System.nanoTime() < cleanupDeadline) {
                continuations.poll(25, java.util.concurrent.TimeUnit.MILLISECONDS)?.run()
            }
            check(owner.isCompleted) { "Save cleanup did not finish; preserved workspace: $root" }
            owner.join()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun cancellationAfterPublicationAdvancesSavedBaselineBeforeRetry() = runBlocking {
        val root = Files.createTempDirectory("droide-cancelled-save-").toFile()
        try {
            root.resolve("main.kt").writeText("A")
            val cancelled = kotlinx.coroutines.CancellationException("cancel callback")
            var cancelOnce = true
            val files = FileRepository(root, onMutation = {
                if (it is FileRepository.Mutation.Write && cancelOnce) { cancelOnce = false; throw cancelled }
            })
            val doc = EditorDocument("main.kt"); doc.ensureLoaded(files); doc.capture("B")
            val failure = runCatching { doc.save(files) }.exceptionOrNull()
            com.baystudio.droide.core.assertOriginatingFailure(cancelled, failure)
            assertEquals("B", root.resolve("main.kt").readText())
            assertEquals("B", doc.savedContent); assertFalse(doc.dirty)
            doc.capture("C"); assertTrue(doc.save(files).isSuccess)
            assertEquals("C", root.resolve("main.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancelledSaveRetainsNewerTypingAndItsCommittedBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-cancelled-save-newer-").toFile()
        try {
            root.resolve("main.kt").writeText("A")
            val doc = EditorDocument("main.kt"); var cancelOnce = true
            val files = FileRepository(root, onMutation = {
                if (it is FileRepository.Mutation.Write && cancelOnce) {
                    cancelOnce = false; doc.capture("C"); throw kotlinx.coroutines.CancellationException("after typing")
                }
            })
            doc.ensureLoaded(files); doc.capture("B")
            assertTrue(runCatching { doc.save(files) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals("B", doc.savedContent); assertEquals("C", doc.content); assertTrue(doc.dirty)
            assertTrue(doc.save(files).isSuccess); assertEquals("C", root.resolve("main.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancellationBeforePublicationDoesNotAdvanceSavedBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-cancelled-save-before-").toFile()
        try {
            root.resolve("main.kt").writeText("A")
            val files = FileRepository(root, beforeMutation = { throw kotlinx.coroutines.CancellationException("before commit") })
            val doc = EditorDocument("main.kt"); doc.ensureLoaded(files); doc.capture("B")
            assertTrue(runCatching { doc.save(files) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals("A", root.resolve("main.kt").readText()); assertEquals("A", doc.savedContent)
            assertEquals("B", doc.content); assertTrue(doc.dirty); assertFalse(doc.saveSynchronizationPending)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancellationAfterTerminalReplacementDoesNotAcknowledgeForeignBytes() = runBlocking {
        val root = Files.createTempDirectory("droide-cancelled-save-foreign-").toFile()
        try {
            root.resolve("main.kt").writeText("A")
            val files = FileRepository(root, onMutation = {
                if (it is FileRepository.Mutation.Write) {
                    root.resolve("main.kt").writeText("terminal"); throw kotlinx.coroutines.CancellationException("foreign change")
                }
            })
            val doc = EditorDocument("main.kt"); doc.ensureLoaded(files); doc.capture("B")
            assertTrue(runCatching { doc.save(files) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals("terminal", root.resolve("main.kt").readText()); assertEquals("A", doc.savedContent)
            assertEquals("B", doc.content); assertTrue(doc.dirty); assertFalse(doc.saveSynchronizationPending)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun failedReloadPreservesDirtyText() = runBlocking {
        val root = Files.createTempDirectory("droide-reload-").toFile()
        try {
            root.resolve("Main.kt").writeText("A")
            val files = FileRepository(root); val doc = EditorDocument("Main.kt")
            doc.ensureLoaded(files); doc.capture("user-B")
            root.resolve("Main.kt").writeBytes(byteArrayOf(0, 1, 2, 3))
            doc.reload(files, discardUnsaved = true)
            assertEquals("user-B", doc.content); assertEquals("A", doc.savedContent); assertTrue(doc.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun automaticReloadCannotDiscardNewerFormatterEdits() = runBlocking {
        val root = Files.createTempDirectory("droide-format-").toFile()
        try {
            root.resolve("Main.kt").writeText("A")
            val files = FileRepository(root); val doc = EditorDocument("Main.kt")
            doc.ensureLoaded(files); doc.capture("typed-during-formatter")
            root.resolve("Main.kt").writeText("formatted-A"); doc.reload(files)
            assertEquals("typed-during-formatter", doc.content); assertTrue(doc.dirty)
            doc.reload(files, discardUnsaved = true)
            assertEquals("formatted-A", doc.content); assertFalse(doc.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun largeFileSaveRetainsOptimizedDirtyState() = runBlocking {
        val root = Files.createTempDirectory("droide-large-").toFile()
        try {
            val text = "x".repeat(3 * 1024 * 1024); root.resolve("Large.txt").writeText(text)
            val doc = EditorDocument("Large.txt")
            val files = FileRepository(root, onMutation = { doc.captureInsert(0, "new") })
            doc.ensureLoaded(files); assertTrue(doc.largeFileOptimized); doc.capture(text + "saved")
            assertTrue(doc.save(files).getOrThrow().didWrite); assertTrue(doc.dirty)
            assertEquals(text + "saved", doc.savedContent)
            assertEquals(text + "saved", root.resolve("Large.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun overCeilingFileStaysReadOnly() = runBlocking {
        val root = Files.createTempDirectory("droide-preview-").toFile()
        try {
            val target = root.resolve("Huge.txt")
            target.outputStream().use { out ->
                val block = ByteArray(1024 * 1024) { 'x'.code.toByte() }; repeat(21) { out.write(block) }
            }
            val files = FileRepository(root); val doc = EditorDocument("Huge.txt"); doc.ensureLoaded(files)
            assertEquals(EditorDocumentKind.LARGE, doc.kind); assertFalse(doc.editable)
            assertTrue(doc.save(files).isFailure); assertEquals(21L * 1024 * 1024, target.length())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun directoryLoadFailsClosedAndCanRetry() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-").toFile()
        try {
            val target = root.resolve("Main.kt"); target.mkdir()
            val files = FileRepository(root); val doc = EditorDocument("Main.kt"); doc.ensureLoaded(files)
            assertFalse(doc.loaded); assertFalse(doc.editable); assertNotNull(doc.loadError)
            assertTrue(doc.save(files).isFailure); assertTrue(target.isDirectory)
            target.delete(); target.writeText("repaired"); doc.ensureLoaded(files)
            assertNull(doc.loadError); assertTrue(doc.editable); assertEquals("repaired", doc.content)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun persistedCallbackThenTypingKeepsNewTextDirty() = runBlocking {
        val root = Files.createTempDirectory("droide-save-").toFile()
        val editor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            root.resolve("Main.kt").writeText("A")
            withContext(editor) {
                val state = EditorWorkspaceState(); val doc = state.document("Main.kt")
                val authority = EditorWorkspaceDocumentAuthority(state, editor)
                val files = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
                    onMutation = { mutation -> authority.persistedMutation(mutation); withContext(editor) { doc.capture("new-C") } })
                doc.ensureLoaded(files); doc.capture("save-B")
                assertTrue(doc.save(files).getOrThrow().didWrite)
                assertTrue(doc.status.startsWith("Saved snapshot"))
                assertEquals("save-B", root.resolve("Main.kt").readText())
                assertEquals("save-B", doc.savedContent); assertEquals("new-C", doc.content); assertTrue(doc.dirty)
            }
        } finally { editor.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun typingBeforePersistedCallbackKeepsCommittedSnapshotAndNewEdits() = runBlocking {
        val root = Files.createTempDirectory("droide-rollback-").toFile()
        val editor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            root.resolve("Main.kt").writeText("A")
            withContext(editor) {
                val state = EditorWorkspaceState(); val doc = state.document("Main.kt")
                val authority = EditorWorkspaceDocumentAuthority(state, editor); var changed = false
                val files = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
                    onMutation = { mutation ->
                        if (!changed) { changed = true; withContext(editor) { doc.capture("new-C") } }
                        authority.persistedMutation(mutation)
                    })
                doc.ensureLoaded(files); doc.capture("save-B")
                val receipt = doc.save(files).getOrThrow()
                assertEquals("save-B", receipt.text)
                assertTrue(receipt.synchronizationFailure?.cause is WorkspaceDocumentConflictException)
                assertEquals("save-B", root.resolve("Main.kt").readText())
                assertEquals("save-B", doc.savedContent); assertEquals("new-C", doc.content); assertTrue(doc.dirty)
                assertTrue(doc.status.contains("newer edits remain unsaved"))
            }
        } finally { editor.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun savedLocalSnapshotAdvancesBaselineAfterSyncFailureAndNextSaveWorks() = runBlocking {
        val root = Files.createTempDirectory("droide-saved-local-").toFile()
        try {
            root.resolve("main.kt").writeText("before")
            var failSync = true
            val files = FileRepository(root, onMutation = {
                if (failSync && it is FileRepository.Mutation.Write) error("mirror offline")
            })
            val doc = EditorDocument("main.kt")
            doc.ensureLoaded(files); doc.capture("saved locally")
            assertNotNull(doc.save(files).getOrThrow().synchronizationFailure)
            assertEquals("saved locally", doc.savedContent)
            assertEquals("saved locally", root.resolve("main.kt").readText())
            assertFalse(doc.dirty)
            assertTrue(doc.saveSynchronizationPending)
            assertTrue(doc.status.startsWith("Saved locally"))
            failSync = false; doc.capture("next snapshot")
            assertTrue(doc.save(files).isSuccess)
            assertEquals("next snapshot", root.resolve("main.kt").readText())
            assertFalse(doc.dirty)
            assertFalse(doc.saveSynchronizationPending)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun terminalReplacementDuringFailedSyncNeverBecomesEditorsSavedBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-terminal-conflict-").toFile()
        try {
            root.resolve("main.kt").writeText("before")
            val files = FileRepository(root, onMutation = {
                if (it is FileRepository.Mutation.Write) {
                    root.resolve("main.kt").delete()
                    root.resolve("main.kt").writeText("terminal replacement")
                    error("sync failed after terminal change")
                }
            })
            val doc = EditorDocument("main.kt")
            doc.ensureLoaded(files); doc.capture("editor snapshot")
            assertFalse(doc.save(files).isSuccess)
            assertEquals("before", doc.savedContent)
            assertEquals("editor snapshot", doc.content)
            assertTrue(doc.dirty)
            assertEquals("terminal replacement", root.resolve("main.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unrelatedSyncErrorBeforeCommitDoesNotInheritAnEarlierSavedStatus() = runBlocking {
        val root = Files.createTempDirectory("droide-save-receipt-").toFile()
        try {
            root.resolve("main.kt").writeText("A")
            val files = FileRepository(root, beforeMutation = {
                throw ProjectFileSyncException("main.kt", 1L, java.io.IOException("unrelated callback"))
            })
            val doc = EditorDocument("main.kt")
            doc.ensureLoaded(files); doc.status = "Saved previous snapshot"; doc.capture("B")
            assertTrue(doc.save(files).exceptionOrNull() is ProjectFileSyncException)
            assertFalse(doc.saveSynchronizationPending)
            assertEquals("A", doc.savedContent)
            assertTrue(doc.dirty)
            assertEquals("A", root.resolve("main.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

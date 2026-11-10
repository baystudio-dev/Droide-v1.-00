package com.baystudio.droide.core

import android.app.Application
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Integration regression sources; execution is separately owned by the coordinator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceImportRecoveryTest {
    private val uri: Uri get() = Uri.parse("content://test-provider/original?private=value")
    private val recoveryScope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)

    @Test fun samePathPrecommitTypedExceptionCannotForgeACommittedReceipt() = workspace { root, parent ->
        val files = FileRepository(root, beforeMutation = {
            throw ProjectFileSyncException("saved.txt", 5, IOException("forged precommit receipt"))
        })
        val controller = WorkspaceImportController(files, parent, source())
        try {
            controller.submit(controller.capturePicker(""), uri)
            val result = done(controller)
            assertEquals(WorkspaceImportResultStatus.FAILED, result.status)
            assertFalse(result.localCommitRecorded)
            assertNull(result.bytes)
            assertFalse(root.resolve("saved.txt").exists())
            assertEquals(ProjectCopyJournal.Status.PREPARED, controller.recovery().records.single().status)
        } finally { controller.closeAndJoin() }
    }

    @Test fun cancelDuringPostcommitSynchronizationRetainsExactLocalReceipt() = workspace { root, parent ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val files = FileRepository(root, onMutation = { entered.complete(Unit); release.await() })
        val controller = WorkspaceImportController(files, parent, source())
        try {
            controller.submit(controller.capturePicker(""), uri)
            withTimeout(5_000) { entered.await() }
            assertTrue(controller.cancel())
            release.complete(Unit)
            val result = done(controller)
            assertTrue(result.localCommitRecorded)
            assertEquals(WorkspaceImportResultStatus.SYNC_PENDING, result.status)
            assertEquals(5L, result.bytes)
            assertEquals("saved", root.resolve("saved.txt").readText())
            assertNotNull(result.transactionId)
            assertEquals(ProjectCopyEvidence.sha256("saved".toByteArray()), result.sha256)
            assertEquals(ProjectCopyJournal.Status.COMMITTED, controller.recovery().records.single().status)
        } finally { release.complete(Unit); controller.closeAndJoin() }
    }

    @Test fun providerCloseCancellationAfterCommitCannotBecomeUnsavedCancellation() = workspace { root, parent ->
        val closes = AtomicInteger()
        val input = object : ByteArrayInputStream("saved".toByteArray()) {
            override fun close() { closes.incrementAndGet(); throw CancellationException("provider close cancelled") }
        }
        val controller = WorkspaceImportController(FileRepository(root), parent, source(input = input))
        try {
            controller.submit(controller.capturePicker(""), uri)
            val result = done(controller)
            assertTrue(result.localCommitRecorded)
            assertEquals(WorkspaceImportResultStatus.SYNC_PENDING, result.status)
            assertEquals(5L, result.bytes)
            assertEquals("saved", root.resolve("saved.txt").readText())
            assertEquals(1, closes.get())
        } finally { controller.closeAndJoin() }
    }

    @Test fun journalSealFailureAfterPublicationIsUnknownAndBlocksRenamedRetryBeforeOpeningSource() = workspace { root, parent ->
        val durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) = ProjectCopyJournal.Durability.platform.forceFile(channel)
            override fun forceDirectory(directory: File) {
                if (directory.resolve("seal.json").exists()) throw IOException("injected postcommit seal force failure")
                ProjectCopyJournal.Durability.platform.forceDirectory(directory)
            }
        }
        val journal = ProjectCopyJournal(root, durability = durability)
        val opens = AtomicInteger()
        val selected = object : WorkspaceImportSource {
            var name = "saved.txt"
            override suspend fun metadata(uri: Uri) = WorkspaceImportSourceMetadata(name, 5)
            override suspend fun open(uri: Uri): InputStream { opens.incrementAndGet(); return ByteArrayInputStream("saved".toByteArray()) }
        }
        val controller = WorkspaceImportController(FileRepository(root), parent, selected, copyJournal = journal)
        try {
            controller.submit(controller.capturePicker(""), uri)
            val first = done(controller)
            assertEquals(WorkspaceImportResultStatus.RECOVERY_REQUIRED, first.status)
            assertFalse(first.localCommitRecorded)
            assertNull(first.bytes)
            assertEquals("saved", root.resolve("saved.txt").readText())
            assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(recoveryScope).records.single().status)
            selected.name = "renamed.txt"
            controller.submit(controller.capturePicker(""), uri)
            assertEquals(WorkspaceImportResultStatus.RECOVERY_REQUIRED, done(controller).status)
            assertEquals(1, opens.get())
            assertFalse(root.resolve("renamed.txt").exists())
        } finally { controller.closeAndJoin() }
    }

    @Test fun oldPickerCannotConsumeLaterAcknowledgementButFreshPickerCan() = workspace { root, parent ->
        var elapsed = 1000L
        val journal = ProjectCopyJournal(root, monotonicClock = { elapsed }, admissionClockNanos = { elapsed * 1_000_000L })
        val old = journal.begin(ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null, "old-picker",
            ProjectCopyJournal.explorerSourceId(uri.toString())), "saved.txt")
        old.beforePublish(StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
            ProjectCopyEvidence.root(root).second, "saved.txt", 5, ProjectCopyEvidence.sha256("saved".toByteArray()), "old-stage"))
        old.close()
        val opens = AtomicInteger()
        val selected = object : WorkspaceImportSource {
            override suspend fun metadata(uri: Uri) = WorkspaceImportSourceMetadata("saved.txt", 5)
            override suspend fun open(uri: Uri): InputStream { opens.incrementAndGet(); return ByteArrayInputStream("saved".toByteArray()) }
        }
        val controller = WorkspaceImportController(FileRepository(root), parent, selected, copyJournal = journal,
            admissionClockNanos = { elapsed * 1_000_000L })
        try {
            val alreadyOpenPicker = controller.capturePicker("")
            elapsed++
            controller.acknowledgeRecovery(controller.inspectRecovery(old.intent.id).token)
            assertEquals(0, opens.get())
            assertFalse(root.resolve("saved.txt").exists())
            elapsed++
            controller.submit(alreadyOpenPicker, uri)
            assertEquals(WorkspaceImportResultStatus.RECOVERY_REQUIRED, done(controller).status)
            assertEquals(0, opens.get())
            elapsed++
            controller.submit(controller.capturePicker(""), uri)
            assertEquals(WorkspaceImportResultStatus.IMPORTED, done(controller).status)
            assertEquals(1, opens.get())
            assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(recoveryScope).records.single { it.intent.id == old.intent.id }.status)
            assertEquals(1, journal.recovery(recoveryScope).records.count { it.status == ProjectCopyJournal.Status.COMMITTED })
        } finally { controller.closeAndJoin() }
    }

    @Test fun dirtyEditorAppearingDuringProviderReadIsPreservedBeforePublication() = workspace { root, parent ->
        var dirty = false
        val input = object : ByteArrayInputStream("saved".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                dirty = true
                return super.read(bytes, offset, length)
            }
        }
        val document = WorkspaceDocumentSnapshot("saved.txt", "unsaved", true, WorkspaceDocumentKind.TEXT,
            true, true, 1, 1, 0, 0, 0)
        val authority = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String) = document.takeIf { dirty && it.path == path }
            override suspend fun snapshots() = if (dirty) listOf(document) else emptyList()
        }
        val controller = WorkspaceImportController(FileRepository(root), parent, source(input), documentAuthority = authority)
        try {
            controller.submit(controller.capturePicker(""), uri)
            val result = done(controller)
            assertEquals(WorkspaceImportResultStatus.FAILED, result.status)
            assertFalse(result.localCommitRecorded)
            assertFalse(root.resolve("saved.txt").exists())
            assertEquals("unsaved", authority.snapshot("saved.txt")!!.content)
            assertEquals(ProjectCopyJournal.Status.PREPARED, controller.recovery().records.single().status)
        } finally { controller.closeAndJoin() }
    }

    @Test fun fatalPostcommitObserverFailureStillPresentsExactLocalReceipt() = workspace { root, parent ->
        val files = FileRepository(root, onMutation = { throw AssertionError("fatal observer fixture") })
        val controller = WorkspaceImportController(files, parent, source())
        try {
            controller.submit(controller.capturePicker(""), uri)
            val result = done(controller)
            assertTrue(result.localCommitRecorded)
            assertEquals(WorkspaceImportResultStatus.SYNC_PENDING, result.status)
            assertEquals(5L, result.bytes)
            assertEquals("saved", root.resolve("saved.txt").readText())
            assertEquals(ProjectCopyJournal.Status.COMMITTED, controller.recovery().records.single().status)
        } finally { controller.closeAndJoin() }
    }

    @Test fun closingWorkspaceDuringAcknowledgementEvidenceReadDoesNotWaitAndRejectsFinalGrant() = workspace { root, parent ->
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val journal = ProjectCopyJournal(root, inspectDestination = { directory, key, path ->
            if (block.get()) {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Test evidence read was not released" }
            }
            ProjectCopyEvidence.inspect(directory, key, path)
        })
        val owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null, "old-picker",
            ProjectCopyJournal.explorerSourceId(uri.toString()))
        val old = journal.begin(owner, "saved.txt")
        old.beforePublish(StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
            ProjectCopyEvidence.root(root).second, "saved.txt", 5, ProjectCopyEvidence.sha256("saved".toByteArray()), "old-stage"))
        old.close()
        val controller = WorkspaceImportController(FileRepository(root), parent, source(), copyJournal = journal)
        val token = controller.inspectRecovery(old.intent.id).token
        block.set(true)
        val acknowledging = async(Dispatchers.Default) { runCatching { controller.acknowledgeRecovery(token) }.exceptionOrNull() }
        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { async(Dispatchers.Default) { controller.close() }.await() }
            assertFalse("Closing the workspace must not wait for evidence IO", acknowledging.isCompleted)
        } finally { release.countDown() }
        try {
            assertNotNull(withTimeout(5_000) { acknowledging.await() })
            block.set(false)
            val failure = runCatching { journal.begin(owner.copy(runId = "new-picker"), "saved.txt", System.nanoTime()) }.exceptionOrNull()
            assertTrue(failure is ProjectCopyJournal.Blocked)
            assertFalse(root.resolve("saved.txt").exists())
        } finally { controller.closeAndJoin() }
    }

    private fun source(input: InputStream = ByteArrayInputStream("saved".toByteArray())) = object : WorkspaceImportSource {
        override suspend fun metadata(uri: Uri) = WorkspaceImportSourceMetadata("saved.txt", 5)
        override suspend fun open(uri: Uri): InputStream = input
    }
    private suspend fun done(controller: WorkspaceImportController): WorkspaceImportResult =
        withTimeout(5_000) { controller.state.first { !it.busy && it.result != null }.result!! }
    private fun workspace(block: suspend CoroutineScope.(File, CoroutineScope) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("workspace-import-recovery-").toFile()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(root, parent) } finally { parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}

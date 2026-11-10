package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorWorkingCopyMutationsTest {
    @Test fun ubuntuLocalExecutionUsesUnpublishedCliBytesWithoutAcceptingOrRefreshingCanonicalFiles() = fixture {
        files()
        File(source, "file").writeText("new canonical edit")
        File(working, "file").writeText("unpublished CLI edit")
        val state = store.load()
        val database = File(metadata, "state.sqlite")
        val stamp = Files.getLastModifiedTime(database.toPath())
        WorkspaceFileMutationLocks.withRoot(working) { guard ->
            engine().requireUbuntuLocalConsistency(guard)
            val command = ProcessBuilder("/bin/sh", "-c", "cat file > local-build-output.bin")
                .directory(working).redirectErrorStream(true).start()
            try {
                assertTrue(command.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, command.exitValue())
            } finally { if (command.isAlive) command.destroyForcibly() }
        }
        assertEquals("unpublished CLI edit", File(working, "local-build-output.bin").readText())
        assertEquals("unpublished CLI edit", File(working, "file").readText())
        assertEquals("new canonical edit", File(source, "file").readText())
        assertEquals(state, reopen().load())
        assertEquals(stamp, Files.getLastModifiedTime(database.toPath()))
        assertTrue(reopen().operations().isEmpty())
    }

    @Test fun unresolvedReviewConflictsAloneDoNotBlockUbuntuLocalConsistency() = fixture {
        files()
        store.commit(store.load().revision, store.load().baseline, setOf("file"))
        File(working, "file").writeText("CLI version awaiting review")
        WorkspaceFileMutationLocks.withRoot(working) { guard -> engine().requireUbuntuLocalConsistency(guard) }
        assertEquals(setOf("file"), reopen().load().conflicts)
        assertEquals("CLI version awaiting review", File(working, "file").readText())
        assertEquals("canonical", File(source, "file").readText())
    }

    @Test fun interruptedNamespaceIntentBlocksLocalAdmissionWithoutDiscardingUnpublishedFiles() = fixture {
        files()
        store.prepare("file", "replace", node("base"), node("canonical"))
        val failure = runCatching { WorkspaceFileMutationLocks.withRoot(working) { guard ->
            engine().requireUbuntuLocalConsistency(guard)
        } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("interrupted at file"))
        assertEquals("base", File(working, "file").readText())
        assertEquals("canonical", File(source, "file").readText())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, reopen().operations().single().phase)
    }

    @Test fun localAdmissionValidatesRecoveryRecordsBeyondTheFirstPageWithoutRewritingMetadata() = fixture {
        val proposals = (0 until 260).map { MirrorMetadataAuthority.Proposal("pending-$it", "create",
            MirrorMetadataAuthority.Node.absent, node("proposed")) }
        val records = store.prepareBatch(0, proposals)
        records.chunked(256).forEach { page -> store.transitionBatch(page.map {
            MirrorMetadataAuthority.Transition(it.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.CONFLICT)
        }) }
        val database = File(metadata, "state.sqlite")
        val stamp = Files.getLastModifiedTime(database.toPath())
        WorkspaceFileMutationLocks.withRoot(working) { guard -> engine().requireUbuntuLocalConsistency(guard) }
        assertEquals(stamp, Files.getLastModifiedTime(database.toPath()))
        val last = records.maxBy { it.id }
        android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS).use {
            it.execSQL("UPDATE operations SET record_digest=? WHERE id=?", arrayOf("0".repeat(64), last.id))
        }
        val failure = runCatching { WorkspaceFileMutationLocks.withRoot(working) { guard ->
            engine().requireUbuntuLocalConsistency(guard)
        } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("checksum"))
        assertTrue(reopen().load().baseline.isEmpty())
        assertTrue(working.listFiles()!!.isEmpty())
    }

    @Test fun lateTruncationThroughTheOldDescriptorCannotModifyTheIndependentStableCopy() = fixture {
        files()
        RandomAccessFile(File(working, "file"), "rw").use { writer ->
            val result = apply(engine { stage, _ -> if (stage == "published") {
                writer.setLength(0); writer.write("late CLI replacement".toByteArray()); writer.fd.sync()
            } }, replacement())
            assertTrue(result.applied)
            assertNotNull(result.conflict)
            assertEquals("canonical", File(working, "file").readText())
            assertEquals("late CLI replacement", result.retained!!.readText())
            assertEquals("base", result.stable!!.readText())
            assertNotEquals(Files.readAttributes(result.retained.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey(),
                Files.readAttributes(result.stable.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            assertEquals(setOf("file"), reopen().load().conflicts)
        }
    }

    @Test fun writesDuringSnapshotAreRestoredAndReportedWhileTheStableBaseRemainsAvailable() = fixture {
        files()
        RandomAccessFile(File(working, "file"), "rw").use { writer ->
            val result = apply(engine { stage, _ -> if (stage == "snapshot") {
                writer.setLength(0); writer.write("CLI during snapshot".toByteArray()); writer.fd.sync()
            } }, replacement())
            assertFalse(result.applied)
            assertNotNull(result.conflict)
            assertEquals("CLI during snapshot", File(working, "file").readText())
            assertEquals("base", result.stable!!.readText())
            assertEquals("canonical", File(source, "file").readText())
        }
    }

    @Test fun lateWritesAfterReceiptAreDetectedByExplicitRetainedInspectionWithoutRewritingUnchangedMetadata() = fixture {
        files()
        RandomAccessFile(File(working, "file"), "rw").use { writer ->
            val result = apply(engine(), replacement())
            writer.setLength(0); writer.write("CLI after receipt".toByteArray()); writer.fd.sync()
            val restarted = MirrorWorkingCopyMutations(source, working, reopen())
            WorkspaceFileMutationLocks.withRoot(working) { guard -> assertEquals(setOf("file"), restarted.inspectRetainedVersions(guard)) }
            val db = File(metadata, "state.sqlite")
            val stamp = Files.readAttributes(db.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            WorkspaceFileMutationLocks.withRoot(working) { guard -> assertEquals(setOf("file"), restarted.inspectRetainedVersions(guard)) }
            assertEquals(stamp.lastModifiedTime(), Files.getLastModifiedTime(db.toPath()))
            assertEquals(setOf("file"), reopen().load().conflicts)
            assertEquals("base", result.stable!!.readText())
            assertEquals("CLI after receipt", result.retained!!.readText())
        }
    }

    @Test fun directorySnapshotPreservesNestedBytesWhenAnOldWriterChangesTheRetainedDirectory() = fixture {
        File(source, "directory").writeText("replacement")
        val directory = File(working, "directory").apply { mkdir() }
        File(directory, "nested").mkdir()
        File(directory, "nested/old").writeText("old")
        store.commit(0, mapOf("directory/nested/old" to WorkspaceSyncManifest.Entry(node("old").sha256, 420)), emptySet())
        val proposal = MirrorMetadataAuthority.Proposal("directory", "directory",
            MirrorWorkingCopyMutations.directoryNode(directory, EmptyCoroutineContext), node("replacement"))
        val result = apply(engine { stage, intent -> if (stage == "published") {
            File(metadata, "retained/${intent.id}/nested/old").writeText("late directory CLI change")
        } }, proposal)
        assertTrue(result.applied)
        assertNotNull(result.conflict)
        assertEquals("replacement", File(working, "directory").readText())
        assertEquals("old", File(result.stable!!, "nested/old").readText())
        assertEquals("late directory CLI change", File(result.retained!!, "nested/old").readText())
    }

    @Test fun damagedStableSnapshotStopsRecoveryWithoutChangingTheLiveProject() = fixture {
        files()
        val result = apply(engine(), replacement())
        result.stable!!.writeText("damaged")
        val failure = runCatching { WorkspaceFileMutationLocks.withRoot(working) { guard ->
            MirrorWorkingCopyMutations(source, working, reopen()).inspectRetainedVersions(guard)
        } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("damaged", result.stable.readText())
        assertEquals("canonical", File(working, "file").readText())
        assertEquals("canonical", File(source, "file").readText())
    }

    @Test fun interruptionAfterReceiptCommitCannotLeaveAnOldBaselineForThePublishedFile() = fixture {
        files()
        val failure = runCatching { apply(engine { stage, _ -> if (stage == "receipts") throw InterruptedProcess() }, replacement()) }.exceptionOrNull()
        assertTrue(failure is InterruptedProcess)
        assertEquals(MirrorMetadataAuthority.Phase.APPLIED, reopen().operations().single().phase)
        assertEquals(node("canonical").sha256, reopen().load().baseline.getValue("file").sha256)
        assertEquals("canonical", File(working, "file").readText())
        assertTrue(File(working, "file").delete())
        assertEquals(node("canonical").sha256, reopen().load().baseline.getValue("file").sha256)
        assertFalse(File(working, "file").exists())
    }

    @Test fun aKnownCliDeletionCannotBePassedAsAnAbsentCreateProposal() = fixture {
        files()
        assertTrue(File(working, "file").delete())
        val proposal = MirrorMetadataAuthority.Proposal("file", "create", MirrorMetadataAuthority.Node.absent, node("canonical"))
        assertNotNull(runCatching { apply(engine(), proposal) }.exceptionOrNull())
        assertFalse(File(working, "file").exists())
        assertTrue(reopen().operations().isEmpty())
    }

    private class InterruptedProcess : Error("injected process interruption")
    private data class Fixture(val root: File, val source: File, val working: File, val metadata: File,
                               val store: MirrorMetadataAuthority) {
        fun engine(hook: (String, MirrorMetadataAuthority.Intent) -> Unit = { _, _ -> }) =
            MirrorWorkingCopyMutations(source, working, store, hook)
        suspend fun apply(engine: MirrorWorkingCopyMutations, proposal: MirrorMetadataAuthority.Proposal): MirrorWorkingCopyMutations.Result =
            WorkspaceFileMutationLocks.withRoot(working) { guard -> engine.apply(guard, store.load().revision, listOf(proposal)).single() }
        fun reopen() = MirrorMetadataAuthority(metadata, source, working)
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-mirror-mutations-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val working = File(root, "working")
            val metadata = File(root, "metadata")
            Fixture(root, source, working, metadata,
                MirrorMetadataAuthority.createForNewWorkingCopy(metadata, source, working)).block()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun node(text: String) = MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry(
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }, 420))
    private fun replacement() = MirrorMetadataAuthority.Proposal("file", "replace", node("base"), node("canonical"))
    private fun Fixture.files() {
        File(source, "file").writeText("canonical")
        File(working, "file").writeText("base")
        store.commit(0, mapOf("file" to WorkspaceSyncManifest.Entry(node("base").sha256, 420)), emptySet())
    }

    @Test fun replacementRetainsTheOldInodeAndNeverChangesCanonicalSource() = fixture {
        files()
        val before = Files.readAttributes(File(working, "file").toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        val result = apply(engine(), replacement())
        assertTrue(result.applied)
        assertNull(result.conflict)
        assertEquals("canonical", File(working, "file").readText())
        assertEquals("canonical", File(source, "file").readText())
        assertEquals("base", result.retained!!.readText())
        assertEquals(before.fileKey(), Files.readAttributes(result.retained.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertEquals(MirrorMetadataAuthority.Phase.APPLIED, reopen().operations().single().phase)
    }

    @Test fun concurrentReplacementBeforeCaptureIsRestoredWithoutOverwritingEitherCliVersion() = fixture {
        files()
        val result = apply(engine { stage, _ -> if (stage == "staged") {
            Files.move(File(working, "file").toPath(), File(working, "cli-retained-original").toPath())
            File(working, "file").writeText("new CLI changes")
        } }, replacement())
        assertFalse(result.applied)
        assertNotNull(result.conflict)
        assertEquals("new CLI changes", File(working, "file").readText())
        assertEquals("base", File(working, "cli-retained-original").readText())
        assertEquals("canonical", File(source, "file").readText())
        assertEquals(MirrorMetadataAuthority.Phase.CONFLICT, reopen().operations().single().phase)
    }

    @Test fun concurrentDestinationCreationWinsWithoutLosingTheCapturedVersion() = fixture {
        files()
        val result = apply(engine { stage, _ -> if (stage == "retired") File(working, "file").writeText("concurrent CLI") }, replacement())
        assertFalse(result.applied)
        assertEquals("concurrent CLI", File(working, "file").readText())
        assertEquals("base", result.retained!!.readText())
        val intent = reopen().operations().single()
        assertEquals("canonical", File(metadata, "prepared/${intent.id}").readText())
        assertEquals(MirrorMetadataAuthority.Phase.CONFLICT, intent.phase)
    }

    @Test fun anAlreadyOpenCliWriterCanStillWriteItsRetainedInodeAfterPublication() = fixture {
        files()
        FileOutputStream(File(working, "file"), true).use { writer ->
            val result = apply(engine { stage, _ -> if (stage == "published") {
                writer.write("-late-unpublished".toByteArray()); writer.fd.sync()
            } }, replacement())
            assertTrue(result.applied)
            assertEquals("canonical", File(working, "file").readText())
            assertEquals("base-late-unpublished", result.retained!!.readText())
        }
    }

    @Test fun concurrentCliWritePreventsDeletionAndRestoresItsExactBytes() = fixture {
        File(working, "file").writeText("base")
        store.commit(0, mapOf("file" to WorkspaceSyncManifest.Entry(node("base").sha256, 420)), emptySet())
        val proposal = MirrorMetadataAuthority.Proposal("file", "delete", node("base"), MirrorMetadataAuthority.Node.absent)
        val result = FileOutputStream(File(working, "file"), true).use { writer ->
            apply(engine { stage, _ -> if (stage == "retired") {
                writer.write("-CLI".toByteArray()); writer.fd.sync()
            } }, proposal)
        }
        assertFalse(result.applied)
        assertEquals("base-CLI", File(working, "file").readText())
        assertFalse(File(source, "file").exists())
    }

    @Test fun directoryTransitionWithNewCliChildRestoresTheWholeDirectory() = fixture {
        File(source, "directory").writeText("replacement")
        val directory = File(working, "directory").apply { mkdir() }
        File(directory, "old").writeText("old")
        store.commit(0, mapOf("directory/old" to WorkspaceSyncManifest.Entry(node("old").sha256, 420)), emptySet())
        val before = MirrorWorkingCopyMutations.directoryNode(directory, EmptyCoroutineContext)
        val proposal = MirrorMetadataAuthority.Proposal("directory", "directory", before, node("replacement"))
        val result = apply(engine { stage, intent -> if (stage == "retired") {
            File(metadata, "retained/${intent.id}/new-child").writeText("unpublished")
        } }, proposal)
        assertFalse(result.applied)
        assertTrue(directory.isDirectory)
        assertEquals("old", File(directory, "old").readText())
        assertEquals("unpublished", File(directory, "new-child").readText())
        assertEquals("replacement", File(source, "directory").readText())
    }

    @Test fun liveSymlinkReplacementCannotReadOrModifyOutsideBytes() = fixture {
        files()
        val outside = File(root, "outside-secret").apply { writeText("private") }
        val result = apply(engine { stage, _ -> if (stage == "staged") {
            Files.move(File(working, "file").toPath(), File(working, "cli-original").toPath())
            Files.createSymbolicLink(File(working, "file").toPath(), outside.toPath())
        } }, replacement())
        assertFalse(result.applied)
        assertTrue(Files.isSymbolicLink(File(working, "file").toPath()))
        assertEquals("private", outside.readText())
        assertEquals("base", File(working, "cli-original").readText())
    }

    @Test fun stagedSymlinkReplacementFailsBeforeAnyWorkingCopyMutation() = fixture {
        files()
        val outside = File(root, "outside-secret").apply { writeText("private") }
        val failure = runCatching { apply(engine { stage, intent -> if (stage == "staged") {
            val staged = File(metadata, "prepared/${intent.id}")
            Files.move(staged.toPath(), File(metadata, "original-stage").toPath())
            Files.createSymbolicLink(staged.toPath(), outside.toPath())
        } }, replacement()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("base", File(working, "file").readText())
        assertEquals("private", outside.readText())
        assertEquals("canonical", File(metadata, "original-stage").readText())
    }

    @Test fun interruptionAfterCaptureRetainsEveryVersionAndBlocksNewWorkAfterRestart() = fixture {
        files()
        val interrupted = runCatching { apply(engine { stage, _ -> if (stage == "retired") throw InterruptedProcess() }, replacement()) }.exceptionOrNull()
        assertTrue(interrupted is InterruptedProcess)
        val intent = reopen().operations().single()
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, intent.phase)
        assertEquals("base", File(metadata, "retained/${intent.id}").readText())
        assertEquals("canonical", File(metadata, "prepared/${intent.id}").readText())
        assertFalse(File(working, "file").exists())
        val restarted = MirrorWorkingCopyMutations(source, working, reopen())
        val failure = runCatching { restarted.requireNoInterruptedWork() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("file"))
        assertNotNull(runCatching { apply(restarted, replacement()) }.exceptionOrNull())
        assertEquals("base", File(metadata, "retained/${intent.id}").readText())
    }

    @Test fun interruptionAfterPublicationDoesNotDeleteAnyVersionOrInventACompletedReceipt() = fixture {
        files()
        assertTrue(runCatching { apply(engine { stage, _ -> if (stage == "published") throw InterruptedProcess() }, replacement()) }.exceptionOrNull() is InterruptedProcess)
        val intent = reopen().operations().single()
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, intent.phase)
        assertEquals("base", File(metadata, "retained/${intent.id}").readText())
        assertEquals("canonical", File(working, "file").readText())
        assertEquals("canonical", File(source, "file").readText())
        assertNotNull(runCatching { MirrorWorkingCopyMutations(source, working, reopen()).requireNoInterruptedWork() }.exceptionOrNull())
    }
}

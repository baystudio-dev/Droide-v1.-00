package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures are retained as evidence, including late descriptor writes. */
class F151LocalDirectoryDeleteCustodyTest {
    private fun root() = Files.createTempDirectory("f151-directory-custody-").toFile()
    private fun source(root: File, path: String = "src"): File = File(root, path).apply {
        check(mkdirs()); resolve("nested/deeper").mkdirs(); resolve("nested/deeper/child").writeText("original")
    }
    private fun stage(root: File): File = File(root, LocalDirectoryDeleteCustody.HOME).listFiles()!!.single().resolve("original")
    private fun id(root: File) = File(root, LocalDirectoryDeleteCustody.HOME).listFiles()!!.single().name
    private fun repository(root: File, journal: LocalDirectoryDeleteCustody) = FileRepository(root,
        newFilePublisher = NewProjectFilePublisher.platform, directoryCustody = journal)

    @Test fun lateDeepDescriptorWriteAndNewDescendantSurviveSuccessfulSynchronization() = runBlocking {
        val root = root(); val selected = source(root)
        RandomAccessFile(selected.resolve("nested/deeper/child"), "rw").use { writer ->
            val repo = FileRepository(root, onMutation = {
                val retained = stage(root)
                val before = Files.readAttributes(retained.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                writer.setLength(0); writer.write("late editor bytes".toByteArray()); writer.fd.sync()
                retained.resolve("nested/deeper/new-child").writeText("new descendant")
                val after = Files.readAttributes(retained.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                assertEquals(before.fileKey(), after.fileKey()); assertEquals(before.size(), after.size())
                assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
            })
            assertTrue(repo.delete("src")); assertFalse(selected.exists())
            assertEquals("late editor bytes", stage(root).resolve("nested/deeper/child").readText())
            assertEquals("new descendant", stage(root).resolve("nested/deeper/new-child").readText())
            val notice = repo.retainedWork.value.notices.single()
            assertEquals(RetainedWorkPurpose.DIRECTORY_DELETE, notice.purpose)
            assertFalse(notice.historicalWriteReturned)
            assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, notice.candidate.directoryDeletion!!.state)
        }
    }

    @Test fun noObserverDeleteUsesDurableCustodyAndRemainsDiscoverableAfterRestart() = runBlocking {
        val root = root(); source(root)
        assertTrue(FileRepository(root).delete("src"))
        val restarted = FileRepository(root)
        assertTrue(restarted.retainedWork.value.notices.isEmpty())
        val inventory = restarted.inspectRetainedWork()
        assertTrue(inventory.complete); assertTrue(inventory.problem!!.contains(LocalDirectoryDeleteCustody.HOME))
        assertTrue(inventory.candidates.isEmpty())
        val operations = restarted.inspectRetainedWork(LocalDirectoryDeleteCustody.HOME)
        assertTrue(operations.complete); assertEquals(1, operations.candidates.size)
        val detail = operations.candidates.single().directoryDeletion!!
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, detail.state)
        val original = restarted.inspectRetainedWork("${LocalDirectoryDeleteCustody.HOME}/${detail.operation}")
        assertEquals(detail.retainedPath, original.candidates.single().path)
        assertEquals("original", File(root, detail.retainedPath).resolve("nested/deeper/child").readText())
    }

    @Test fun ordinaryObserverFailureReportsCommittedDeletionAndPendingSynchronization() = runBlocking {
        val root = root(); source(root)
        val failure = IOException("LSP unavailable")
        val repo = FileRepository(root, onMutation = { throw failure })
        assertTrue(repo.delete("src"))
        val notice = repo.retainedWork.value.notices.single()
        assertEquals(LocalDirectoryDeleteCustody.ObserverState.FAILED, notice.candidate.directoryDeletion!!.observer)
        assertFalse(File(root, "src").exists()); assertTrue(stage(root).isDirectory)
    }

    @Test fun cancellationAndFatalObserverErrorsKeepOriginalIdentityAndCustody() {
        listOf(CancellationException("cancelled"), AssertionError("fatal")).forEach { failure ->
            val root = root(); source(root)
            val repo = FileRepository(root, onMutation = { throw failure })
            val actual = runCatching { runBlocking { repo.delete("src") } }.exceptionOrNull()
            assertSame(failure, actual)
            val inspected = LocalDirectoryDeleteCustody(root).inspect(id(root))
            assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, inspected.state)
            assertEquals(LocalDirectoryDeleteCustody.ObserverState.INTERRUPTED, inspected.observer)
            assertEquals("original", stage(root).resolve("nested/deeper/child").readText())
        }
    }

    @Test fun preparedInterruptionNeverMovesSourceAndExplicitReconcileDoesNotReplay() = runBlocking {
        val root = root(); val selected = source(root)
        val stop = IOException("stop after prepared")
        val journal = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "prepared") throw stop })
        assertSame(stop, runCatching { repository(root, journal).delete("src") }.exceptionOrNull())
        val inspection = LocalDirectoryDeleteCustody(root).inspect(id(root))
        assertEquals(LocalDirectoryDeleteCustody.State.PREPARED, inspection.state)
        assertTrue(selected.isDirectory); assertFalse(stage(root).exists())
        val reconciled = FileRepository(root).reconcileDirectoryDeletion(inspection.token!!)
        assertEquals(LocalDirectoryDeleteCustody.State.PREPARED, reconciled.state)
        assertTrue(selected.isDirectory); assertFalse(stage(root).exists())
    }

    @Test fun movedBeforeReceiptReconciliationConfirmsExactMoveWithoutRepeatingIt() = runBlocking {
        val root = root(); source(root)
        val stop = IOException("move returned but outcome not recorded")
        val journal = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "after-move") throw stop })
        assertSame(stop, runCatching { repository(root, journal).delete("src") }.exceptionOrNull())
        val before = LocalDirectoryDeleteCustody(root).inspect(id(root))
        assertEquals(LocalDirectoryDeleteCustody.State.MOVED_UNCONFIRMED, before.state)
        val restarted = FileRepository(root)
        val after = restarted.reconcileDirectoryDeletion(before.token!!)
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, after.state)
        assertEquals(LocalDirectoryDeleteCustody.ObserverState.NOT_STARTED, after.observer)
        assertEquals(after, restarted.reconcileDirectoryDeletion(before.token))
        assertEquals("original", stage(root).resolve("nested/deeper/child").readText())
    }

    @Test fun nondurableMoveReturnKeepsPreparedAndRequiresExactReconfirmation() = runBlocking {
        val root = root(); source(root)
        val journal = LocalDirectoryDeleteCustody(root, moveDirectory = { files, src, dst ->
            val actual = files.moveNoReplaceDurable(src, files, dst); assertTrue(actual.durable)
            AnchoredWorkspaceFiles.MoveReceipt(true, 5)
        })
        assertNotNull(runCatching { repository(root, journal).delete("src") }.exceptionOrNull())
        val observed = LocalDirectoryDeleteCustody(root).inspect(id(root))
        assertEquals(LocalDirectoryDeleteCustody.State.MOVED_UNCONFIRMED, observed.state)
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED,
            FileRepository(root).reconcileDirectoryDeletion(observed.token!!).state)
    }

    @Test fun lostCommittedReturnDoesNotRetryObserverOrMoveRecreatedSource() = runBlocking {
        val root = root(); source(root)
        val stop = IOException("lost committed result")
        val journal = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "retained") throw stop })
        assertSame(stop, runCatching { repository(root, journal).delete("src") }.exceptionOrNull())
        source(root).resolve("recreated").writeText("new source")
        val inspection = LocalDirectoryDeleteCustody(root).inspect(id(root))
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, inspection.state)
        assertEquals(LocalDirectoryDeleteCustody.SourceState.RECREATED, inspection.sourceState)
        var observers = 0
        val restarted = FileRepository(root, onMutation = { observers++ })
        repeat(3) { assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, restarted.reconcileDirectoryDeletion(inspection.token!!).state) }
        assertEquals(0, observers); assertEquals("new source", File(root, "src/recreated").readText())
        assertEquals(1, File(root, LocalDirectoryDeleteCustody.HOME).listFiles()!!.size)
    }

    @Test fun recreationBeforeOutcomeCannotBeAdoptedByReconciliation() = runBlocking {
        val root = root(); source(root)
        val journal = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "after-move") throw IOException("interrupted") })
        assertNotNull(runCatching { repository(root, journal).delete("src") }.exceptionOrNull())
        val token = LocalDirectoryDeleteCustody(root).inspect(id(root)).token!!
        source(root).resolve("new").writeText("new source")
        val result = FileRepository(root).reconcileDirectoryDeletion(token)
        assertEquals(LocalDirectoryDeleteCustody.State.RECOVERY_REQUIRED, result.state)
        assertEquals("new source", File(root, "src/new").readText())
        assertEquals("original", stage(root).resolve("nested/deeper/child").readText())
        assertFalse(File(root, "${LocalDirectoryDeleteCustody.HOME}/${token.operation}/retained.json").exists())
    }

    @Test fun identityReplacementDuringAdmissionIsRejectedBeforePreparation() = runBlocking {
        val root = root(); val selected = source(root)
        val repo = FileRepository(root, beforeMutation = {
            Files.move(selected.toPath(), File(root, "retired-source").toPath())
            source(root).resolve("new").writeText("new source")
        })
        assertNotNull(runCatching { repo.delete("src") }.exceptionOrNull())
        assertEquals("new source", File(root, "src/new").readText())
        assertEquals("original", File(root, "retired-source/nested/deeper/child").readText())
        assertFalse(File(root, LocalDirectoryDeleteCustody.HOME).exists())
    }

    @Test fun unknownSlotAndCorruptRecordArePreservedWithoutReceiptAdoption() = runBlocking {
        for (corrupt in listOf(false, true)) {
            val root = root(); source(root); assertTrue(FileRepository(root).delete("src"))
            val directory = File(root, LocalDirectoryDeleteCustody.HOME).listFiles()!!.single()
            if (corrupt) directory.resolve("prepared.json").writeText("{invalid")
            else directory.resolve("unexpected").writeText("unknown evidence")
            val result = LocalDirectoryDeleteCustody(root).inspect(directory.name)
            assertEquals(LocalDirectoryDeleteCustody.State.RECOVERY_REQUIRED, result.state)
            assertEquals("original", directory.resolve("original/nested/deeper/child").readText())
            if (!corrupt) assertEquals("unknown evidence", directory.resolve("unexpected").readText())
        }
    }

    @Test fun capacityFailureOccursBeforeAnySourceOrEvidenceMutation() = runBlocking {
        val root = root(); source(root)
        val journal = LocalDirectoryDeleteCustody(root, availableBytes = { 0L })
        val failure = runCatching { repository(root, journal).delete("src") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("does not free"))
        assertTrue(File(root, "src").isDirectory)
        assertFalse(File(root, ".droide").exists())
    }

    @Test fun collisionNeverReplacesForeignCustodyOrUnlinksOriginalSource() = runBlocking {
        val root = root(); source(root)
        val journal = LocalDirectoryDeleteCustody(root, moveDirectory = { files, src, dst ->
            File(root, dst).mkdir(); File(root, "$dst/foreign").writeText("foreign")
            files.moveNoReplaceDurable(src, files, dst)
        })
        assertNotNull(runCatching { repository(root, journal).delete("src") }.exceptionOrNull())
        assertEquals("foreign", stage(root).resolve("foreign").readText())
        assertEquals("original", File(root, "src/nested/deeper/child").readText())
        assertEquals(LocalDirectoryDeleteCustody.State.RECOVERY_REQUIRED, LocalDirectoryDeleteCustody(root).inspect(id(root)).state)
    }

    @Test fun laterAncestorDeletionDoesNotMoveOlderCustodyAndHistoricalInspectionSurvives() = runBlocking {
        val root = root(); source(root, "parent/child")
        val repo = FileRepository(root); assertTrue(repo.delete("parent/child"))
        val first = repo.retainedWork.value.notices.single().candidate.directoryDeletion!!
        assertTrue(repo.delete("parent"))
        val inspected = repo.inspectDirectoryDeletion(first.token!!)
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, inspected.state)
        assertEquals(LocalDirectoryDeleteCustody.SourceState.PARENTS_CHANGED, inspected.sourceState)
        assertEquals("original", File(root, first.retainedPath).resolve("nested/deeper/child").readText())
    }

    @Test fun ordinaryRepositoryMutationsCannotReleaseOrRelocateProtectedCustody() = runBlocking {
        val root = root(); source(root); val repo = FileRepository(root); assertTrue(repo.delete("src"))
        val retained = stage(root)
        val paths = listOf(".droide", LocalDirectoryDeleteCustody.HOME, "${LocalDirectoryDeleteCustody.HOME}/${id(root)}", "${LocalDirectoryDeleteCustody.HOME}/${id(root)}/original/nested/deeper/child")
        for (path in paths) {
            assertNotNull(runCatching { repo.delete(path) }.exceptionOrNull())
            assertNotNull(runCatching { repo.move(path, "moved") }.exceptionOrNull())
            assertNotNull(runCatching { repo.writeText(path, "overwrite") }.exceptionOrNull())
        }
        assertEquals("original", retained.resolve("nested/deeper/child").readText())
    }

    @Test fun unrelatedInternalDirectoriesRemainOrdinaryMutationTargets() = runBlocking {
        val root = root(); source(root, ".droide/cache")
        val repo = FileRepository(root)
        assertTrue(repo.delete(".droide/cache"))
        assertFalse(File(root, ".droide/cache").exists())
        assertEquals("original", stage(root).resolve("nested/deeper/child").readText())
    }

    @Test fun regularFileDeleteDoesNotCreateDirectoryCustody() = runBlocking {
        val root = root(); File(root, "file").writeText("ordinary")
        var observed = 0
        assertTrue(FileRepository(root, onMutation = { observed++ }).delete("file"))
        assertEquals(1, observed); assertFalse(File(root, ".droide").exists())
    }
}

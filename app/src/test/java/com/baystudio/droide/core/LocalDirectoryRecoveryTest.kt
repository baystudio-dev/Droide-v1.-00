package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Test

class LocalDirectoryRecoveryTest {
    private fun root() = Files.createTempDirectory("f160-folder-recovery-").toFile()
    private fun cache() = Files.createTempDirectory("f160-folder-cache-").toFile()
    private fun source(root: File, name: String = "src") = File(root, name).apply {
        mkdirs(); resolve("nested").mkdir(); resolve("nested/data").writeText("original")
    }
    private suspend fun deleted(root: File): LocalDirectoryDeleteCustody.Inspection {
        source(root); assertTrue(FileRepository(root).delete("src"))
        return FileRepository(root).localDirectoryRecovery.list().items.single()
    }
    private fun tarText(archive: File, name: String): String? = TarArchiveInputStream(archive.inputStream()).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: return null
            if (entry.name == name) return tar.readBytes().toString(Charsets.UTF_8)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    @Test fun emptyDiscoveryDoesNotCreateInternalState() = runBlocking {
        val root = root(); val page = FileRepository(root).localDirectoryRecovery.list()
        assertTrue(page.complete); assertTrue(page.items.isEmpty()); assertNull(page.nextCursor)
        assertFalse(File(root, ".droide").exists())
    }

    @Test fun all131OperationsAndUnknownEntryReachableWithBoundedPages() = runBlocking {
        val root = root(); val repo = FileRepository(root)
        repeat(131) { source(root, "source-$it"); assertTrue(repo.delete("source-$it")) }
        File(root, "${LocalDirectoryDeleteCustody.HOME}/unknown-entry").writeText("preserve")
        val seen = mutableSetOf<String>(); var cursor: String? = null; var pages = 0
        do {
            val page = FileRepository(root).localDirectoryRecovery.list(cursor)
            assertTrue(page.items.size <= 32)
            page.items.forEach { assertTrue(seen.add(it.operation)) }
            cursor = page.nextCursor; pages++
        } while (cursor != null)
        assertEquals(132, seen.size); assertEquals(5, pages)
        assertEquals("preserve", File(root, "${LocalDirectoryDeleteCustody.HOME}/unknown-entry").readText())
    }

    @Test fun membershipDriftAndOtherRootCursorRefuseWithoutSkipping() = runBlocking {
        val root = root(); val repo = FileRepository(root)
        repeat(33) { source(root, "source-$it"); repo.delete("source-$it") }
        val cursor = repo.localDirectoryRecovery.list().nextCursor!!
        val other = root(); deleted(other)
        assertNotNull(runCatching { FileRepository(other).localDirectoryRecovery.list(cursor) }.exceptionOrNull())
        File(root, "${LocalDirectoryDeleteCustody.HOME}/addition").mkdir()
        assertNotNull(runCatching { repo.localDirectoryRecovery.list(cursor) }.exceptionOrNull())
    }

    @Test fun readOnlyArchiveInspectionDistinguishesPreparedUnknownAndCommitted() = runBlocking {
        val root = root(); val item = deleted(root); val normal = FileRepository(root).localDirectoryRecovery
        assertEquals("NOT_STARTED", normal.inspectArchive(item.token!!, "rescue.tar").status)
        assertFalse(File(root, LocalDirectoryArchiveJournal.HOME).exists())
        val stop = LocalDirectoryRecovery(FileRepository(root), checkpoint = { if (it == "before-capture") throw IOException("capture not started") })
        assertNotNull(runCatching { stop.archive(stop.prepareArchive(item.token, "rescue.tar"), cache(), {}) }.exceptionOrNull())
        val prepared = normal.inspectArchive(item.token, "rescue.tar")
        assertEquals("PREPARED", prepared.status); assertNull(prepared.bytes)
        val interrupted = LocalDirectoryRecovery(FileRepository(root), checkpoint = { if (it == "attempt") throw IOException("publication not returned") })
        assertNotNull(runCatching { interrupted.archive(interrupted.prepareArchive(item.token, "rescue.tar"), cache(), {}) }.exceptionOrNull())
        assertEquals("UNKNOWN", normal.inspectArchive(item.token, "rescue.tar").status)
        val result = normal.archive(normal.prepareArchive(item.token, "fresh.tar"), cache(), {})
        val committed = normal.inspectArchive(item.token, "fresh.tar")
        assertEquals("COMMITTED", committed.status); assertEquals(result.bytes, committed.bytes)
        assertEquals(result.sha256, committed.sha256); assertFalse(committed.currentFileReverified)
    }

    @Test fun exactTokenAndReviewOwnerAndDestinationAliasesRequired() = runBlocking {
        val root = root(); val item = deleted(root); val recovery = FileRepository(root).localDirectoryRecovery
        val wrong = item.token!!.copy(preparedSha256 = "0".repeat(64))
        assertNotNull(runCatching { recovery.prepareArchive(wrong, "rescue.tar") }.exceptionOrNull())
        for (path in listOf("../escape.tar", "nested/../rescue.tar", ".droide/rescue.tar", "bad\\name.tar", "/absolute.tar", "rescue.zip"))
            assertNotNull(path, runCatching { recovery.prepareArchive(item.token, path) }.exceptionOrNull())
        val review = recovery.prepareArchive(item.token, "rescue.tar")
        assertNotNull(runCatching { FileRepository(root).localDirectoryRecovery.archive(review, cache(), {}) }.exceptionOrNull())
        val other = root()
        assertNotNull(runCatching { FileRepository(other).localDirectoryRecovery.prepareArchive(item.token, "rescue.tar") }.exceptionOrNull())
        assertFalse(File(root, "rescue.tar").exists())
    }

    @Test fun sourceRecreationDoesNotBlockRescuingExactOriginal() = runBlocking {
        val root = root(); val item = deleted(root); source(root).resolve("nested/data").writeText("replacement")
        val recovery = FileRepository(root).localDirectoryRecovery
        val review = recovery.prepareArchive(item.token!!, "rescue.tar")
        val result = recovery.archive(review, cache(), {})
        assertTrue(result.committed); assertFalse(result.historical)
        assertEquals("original", tarText(File(root, "rescue.tar"), "nested/data"))
        assertEquals("replacement", File(root, "src/nested/data").readText())
        assertEquals("original", File(root, item.retainedPath).resolve("nested/data").readText())
    }

    @Test fun sourceParentRecreationStillAllowsRetainedOriginal() = runBlocking {
        val root = root(); source(root, "parent/src"); val repo = FileRepository(root); repo.delete("parent/src")
        val item = repo.localDirectoryRecovery.list().items.single()
        Files.move(File(root, "parent").toPath(), File(root, "old-parent").toPath()); File(root, "parent").mkdir()
        val review = repo.localDirectoryRecovery.prepareArchive(item.token!!, "rescue.tar")
        assertEquals(LocalDirectoryDeleteCustody.SourceState.PARENTS_CHANGED, review.inspection.sourceState)
        repo.localDirectoryRecovery.archive(review, cache(), {})
        assertEquals("original", tarText(File(root, "rescue.tar"), "nested/data"))
    }

    @Test fun destinationParentAndCustodyLockReplacementInvalidateApproval() = runBlocking {
        for (replaceLock in listOf(false, true)) {
            val root = root(); val item = deleted(root); File(root, "out").mkdir()
            val recovery = FileRepository(root).localDirectoryRecovery; val review = recovery.prepareArchive(item.token!!, "out/rescue.tar")
            if (replaceLock) {
                val lock = File(root, "${LocalDirectoryDeleteCustody.HOME}/${item.operation}/lock")
                Files.move(lock.toPath(), File(root, "old-lock").toPath()); lock.writeBytes(byteArrayOf())
            } else {
                Files.move(File(root, "out").toPath(), File(root, "old-out").toPath()); File(root, "out").mkdir()
            }
            assertNotNull(runCatching { recovery.archive(review, cache(), {}) }.exceptionOrNull())
            assertFalse(File(root, "out/rescue.tar").exists())
            assertEquals("original", File(root, item.retainedPath).resolve("nested/data").readText())
        }
    }

    @Test fun preparedDeletionIsNeverArchivedOrReplayed() = runBlocking {
        val root = root(); source(root)
        val custody = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "prepared") throw IOException("stop") })
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, directoryCustody = custody)
        assertNotNull(runCatching { repo.delete("src") }.exceptionOrNull())
        val item = repo.localDirectoryRecovery.list().items.single()
        assertEquals(LocalDirectoryDeleteCustody.State.PREPARED, item.state)
        assertNotNull(runCatching { repo.localDirectoryRecovery.prepareArchive(item.token!!, "rescue.tar") }.exceptionOrNull())
        assertEquals("original", File(root, "src/nested/data").readText())
    }

    @Test fun movedUnconfirmedReconcilesOnlyExactCustodyWithoutDeleteObserverReplay() = runBlocking {
        val root = root(); source(root)
        val custody = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "after-move") throw IOException("lost move return") })
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, directoryCustody = custody)
        assertNotNull(runCatching { repo.delete("src") }.exceptionOrNull())
        val recovery = FileRepository(root).localDirectoryRecovery; val item = recovery.list().items.single()
        assertEquals(LocalDirectoryDeleteCustody.State.MOVED_UNCONFIRMED, item.state)
        val result = recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {})
        assertTrue(result.committed)
        val final = recovery.inspect(item.token)
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, final.state)
        assertEquals(LocalDirectoryDeleteCustody.ObserverState.NOT_STARTED, final.observer)
    }

    @Test fun captureFailureIntentCanSafelyRetryAfterRestart() = runBlocking {
        val root = root(); val item = deleted(root); val repo = FileRepository(root)
        val recovery = LocalDirectoryRecovery(repo, checkpoint = { if (it == "before-capture") throw IOException("ENOSPC before capture") })
        assertNotNull(runCatching { recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {}) }.exceptionOrNull())
        assertFalse(File(root, "rescue.tar").exists())
        val restarted = FileRepository(root).localDirectoryRecovery
        assertFalse(restarted.archive(restarted.prepareArchive(item.token!!, "rescue.tar"), cache(), {}).historical)
    }

    @Test fun unsealedAttemptRefusesMatchingBytesButDoesNotBlockFreshDestination() = runBlocking {
        val root = root(); val item = deleted(root)
        val recovery = LocalDirectoryRecovery(FileRepository(root), checkpoint = { if (it == "receipt") throw IOException("lost receipt force return") })
        assertNotNull(runCatching { recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {}) }.exceptionOrNull())
        assertEquals("original", tarText(File(root, "rescue.tar"), "nested/data"))
        val restarted = FileRepository(root).localDirectoryRecovery
        assertNotNull(runCatching { restarted.prepareArchive(item.token!!, "rescue.tar") }.exceptionOrNull())
        assertFalse(restarted.archive(restarted.prepareArchive(item.token!!, "fresh.tar"), cache(), {}).historical)
    }

    @Test fun actualPublicationThenLostNativeReturnRemainsUnknownAfterRestart() = runBlocking {
        val root = root(); val item = deleted(root)
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher { stage, destination ->
            NewProjectFilePublisher.platform.publish(stage, destination)
            throw IOException("native publication happened but return was lost")
        })
        val recovery = repo.localDirectoryRecovery
        val failure = runCatching { recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {}) }.exceptionOrNull()
        assertTrue(failure is StreamPublicationUncertainException)
        assertEquals("original", tarText(File(root, "rescue.tar"), "nested/data"))
        assertTrue(root.listFiles()!!.any { it.name.startsWith(".droide-tmp-") })
        val restarted = FileRepository(root).localDirectoryRecovery
        assertEquals("UNKNOWN", restarted.inspectArchive(item.token!!, "rescue.tar").status)
        assertNotNull(runCatching { restarted.prepareArchive(item.token, "rescue.tar") }.exceptionOrNull())
        assertEquals("original", File(root, item.retainedPath).resolve("nested/data").readText())
    }

    @Test fun sealedLostReturnReplaysReceiptEvenAfterDestinationBecomesSymlink() = runBlocking {
        val root = root(); val item = deleted(root)
        val repo = FileRepository(root, onMutation = { throw CancellationException("lost response") })
        val recovery = repo.localDirectoryRecovery
        assertTrue(runCatching { recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {}) }.exceptionOrNull() is CancellationException)
        Files.move(File(root, "rescue.tar").toPath(), File(root, "actual-archive.tar").toPath())
        val outside = File(cache(), "external").apply { writeText("do not touch") }
        Files.createSymbolicLink(File(root, "rescue.tar").toPath(), outside.toPath())
        val restarted = FileRepository(root).localDirectoryRecovery
        val result = restarted.archive(restarted.prepareArchive(item.token!!, "rescue.tar"), cache(), { }, beforePublication = { error("must not check dirty document for historical retry") })
        assertTrue(result.historical); assertFalse(result.currentFileReverified)
        assertNull(result.syncPending); assertEquals("UNKNOWN_NOT_REPLAYED", result.synchronizationStatus)
        assertEquals("do not touch", outside.readText()); assertTrue(Files.isSymbolicLink(File(root, "rescue.tar").toPath()))
    }

    @Test fun postPublicationObserverFailureReportsCommittedPendingNotFreshFailure() = runBlocking {
        val root = root(); val item = deleted(root)
        val recovery = FileRepository(root, onMutation = { throw IOException("observer unavailable") }).localDirectoryRecovery
        val result = recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {})
        assertTrue(result.committed); assertEquals(true, result.syncPending)
        assertEquals("original", tarText(File(root, "rescue.tar"), "nested/data"))
    }

    @Test fun lateDescriptorWritesRemainInOriginalWithoutPretendingArchiveIsFrozenSource() = runBlocking {
        val root = root(); val item = deleted(root); val original = File(root, item.retainedPath).resolve("nested/data")
        RandomAccessFile(original, "rw").use { writer ->
            val recovery = LocalDirectoryRecovery(FileRepository(root), checkpoint = { if (it == "captured") {
                writer.setLength(0); writer.write("late bytes".toByteArray()); writer.fd.sync()
            } })
            val result = recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {})
            assertEquals("original", tarText(File(root, "rescue.tar"), "nested/data"))
            assertEquals("late bytes", original.readText()); assertTrue(result.detail.contains("may still change"))
        }
    }

    @Test fun readAdmissionChecksBeforePayloadAndNewlyAppearingPrivateFileFailsClosed() = runBlocking {
        val root = root(); val item = deleted(root); val recovery = FileRepository(root).localDirectoryRecovery
        val review = recovery.prepareArchive(item.token!!, "rescue.tar"); val approved = mutableSetOf<String>()
        recovery.authorizeReadEntries(review) { approved += it }
        assertTrue(approved.containsAll(listOf("", "nested", "nested/data")))
        File(root, item.retainedPath).resolve(".secret").writeText("not approved")
        assertNotNull(runCatching { recovery.archive(review, cache(), {}, checkReadEntry = { check(it in approved) { "Read not approved: $it" } }) }.exceptionOrNull())
        assertFalse(File(root, "rescue.tar").exists())
        assertEquals("not approved", File(root, item.retainedPath).resolve(".secret").readText())
    }

    @Test fun cancellationAndDirtyDestinationBeforePublicationNeverPublish() = runBlocking {
        for (cancel in listOf(false, true)) {
            val root = root(); val item = deleted(root); val recovery = FileRepository(root).localDirectoryRecovery
            var calls = 0
            assertNotNull(runCatching { recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {},
                beforePublication = { calls++; if (calls == 2) { if (cancel) throw CancellationException("request closed") else error("dirty destination document") } }) }.exceptionOrNull())
            assertFalse(File(root, "rescue.tar").exists())
        }
    }

    @Test fun finalSuspendingAdmissionCannotOutliveRequestAuthority() = runBlocking {
        val root = root(); val item = deleted(root); var admitted = true; var calls = 0
        val repo = FileRepository.forAdmittedStreamWrites(root) {
            calls++
            if (calls == 2) { yield(); admitted = false }
        }
        val recovery = repo.localDirectoryRecovery
        assertNotNull(runCatching { recovery.archive(recovery.prepareArchive(item.token!!, "rescue.tar"), cache(), { check(admitted) { "request owner closed" } }) }.exceptionOrNull())
        assertEquals(2, calls); assertFalse(File(root, "rescue.tar").exists())
        assertTrue(File(root, LocalDirectoryArchiveJournal.HOME).walkTopDown().any { it.name == "attempt.json" })
        assertNotNull(runCatching { FileRepository(root).localDirectoryRecovery.prepareArchive(item.token!!, "rescue.tar") }.exceptionOrNull())
    }

    @Test fun cancellationDuringFinalNonCancellableAdmissionStillPreventsPublication() = runBlocking {
        val root = root(); val item = deleted(root); val reached = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>(); var calls = 0
        val recovery = FileRepository.forAdmittedStreamWrites(root) {
            calls++; if (calls == 2) { reached.complete(Unit); resume.await() }
        }.localDirectoryRecovery
        val review = recovery.prepareArchive(item.token!!, "rescue.tar")
        val task = async { recovery.archive(review, cache(), {}) }
        reached.await(); task.cancel(); resume.complete(Unit); task.join()
        assertFalse(File(root, "rescue.tar").exists())
        assertEquals("original", File(root, item.retainedPath).resolve("nested/data").readText())
    }

    @Test fun sameProcessLosingCustodyContenderDoesNotDropKernelLease() = runBlocking {
        val root = root(); val item = deleted(root); val custody = LocalDirectoryDeleteCustody(root)
        val lease = WorkspaceFileMutationLocks.withCaptureRoot(root) { custody.acquireRecovery(item.token!!, it) }
        val path = File(root, "${LocalDirectoryDeleteCustody.HOME}/${item.operation}/lock")
        lease.use {
            assertNotNull(runCatching { FileRepository(root).reconcileDirectoryDeletion(item.token!!) }.exceptionOrNull())
            assertEquals(3, probe(path)); it.validate()
            assertNotNull(runCatching { WorkspaceFileMutationLocks.withCaptureRoot(root) { g -> custody.acquireRecovery(item.token!!, g) } }.exceptionOrNull())
            assertEquals(3, probe(path)); it.validate()
        }
        assertEquals(0, probe(path))
    }

    @Test fun ordinaryReadsAndCanonicalAliasesCannotDropDedicatedKernelLocks() = runBlocking {
        val root = root(); val item = deleted(root); val repo = FileRepository(root)
        val custody = LocalDirectoryDeleteCustody(root); val journal = LocalDirectoryArchiveJournal(root)
        val leases = WorkspaceFileMutationLocks.withCaptureRoot(root) { guard ->
            custody.acquireRecovery(item.token!!, guard) to journal.begin(item.token, "rescue.tar", journal.parents("rescue.tar"), guard)
        }
        leases.first.use { original -> leases.second.use { archive ->
            val paths = listOf("${LocalDirectoryDeleteCustody.HOME}/${item.operation}/lock",
                "${LocalDirectoryArchiveJournal.HOME}/${item.operation}/${archive.intent.id}/lock")
            for ((index, path) in paths.withIndex()) {
                val lock = File(root, path); assertEquals(3, probe(lock))
                assertNotNull(runCatching { repo.readText(path) }.exceptionOrNull())
                val alias = "lock-alias-$index"; Files.createSymbolicLink(File(root, alias).toPath(), lock.toPath())
                assertNotNull(runCatching { repo.readText(alias) }.exceptionOrNull())
                assertNotNull(runCatching { repo.resolveChecked(path) }.exceptionOrNull())
                original.validate(); archive.validate(); assertEquals(3, probe(lock))
            }
            assertEquals("original", repo.readText(item.retainedPath + "/nested/data"))
            assertTrue(repo.readText("${LocalDirectoryDeleteCustody.HOME}/${item.operation}/prepared.json").contains(item.operation))
        } }
    }

    @Test fun fullProjectExportPreservesEmptyLockWithoutOpeningItsDescriptor() = runBlocking {
        val root = root(); val item = deleted(root); val custody = LocalDirectoryDeleteCustody(root)
        val lease = WorkspaceFileMutationLocks.withCaptureRoot(root) { custody.acquireRecovery(item.token!!, it) }
        val lock = File(root, "${LocalDirectoryDeleteCustody.HOME}/${item.operation}/lock")
        lease.use {
            assertEquals(3, probe(lock))
            val permit = object : ExportCapturePermit {
                override val root = root
                override val scope = ExportScope("")
                override val stabilityAssumptions = listOf("Observed test tree, no writer exclusion")
                override fun validate() { lease.validate() }
            }
            ExportCaptureStore(cache()).capture(permit).use { capture ->
                var capturedLock = false
                ExportManifest.each(capture.manifest, capture.manifestDigest) { entry ->
                    if (entry.path.endsWith("/${item.operation}/lock")) {
                        capturedLock = true; assertEquals(0L, entry.size)
                        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", entry.digest)
                    }
                }
                assertTrue(capturedLock)
            }
            lease.validate(); assertEquals(3, probe(lock))
        }
        assertEquals(0, probe(lock))
        val empty = root().apply { resolve("zero").writeBytes(byteArrayOf()) }
        val permit = object : ExportCapturePermit {
            override val root = empty; override val scope = ExportScope("")
            override val stabilityAssumptions = emptyList<String>(); override fun validate() = Unit
        }
        ExportCaptureStore(cache()).capture(permit).use { assertEquals(0L, it.sourceFilesRead); assertEquals(0L, it.sourceBytesRead); assertEquals(2L, it.entries) }
    }

    @Test fun hardlinkedMetadataLockRejectedWithoutOpeningAlias() = runBlocking {
        val root = root(); val item = deleted(root)
        val lock = File(root, "${LocalDirectoryDeleteCustody.HOME}/${item.operation}/lock")
        Files.createLink(File(root, "alias-lock").toPath(), lock.toPath())
        val recovery = FileRepository(root).localDirectoryRecovery
        assertNotNull(runCatching { recovery.prepareArchive(item.token!!, "rescue.tar") }.exceptionOrNull())
        assertTrue(lock.exists()); assertTrue(File(root, "alias-lock").exists())
    }

    @Test fun archiveAuthorityCannotBeMutatedThroughOrdinaryRepository() = runBlocking {
        val root = root(); val item = deleted(root); val repo = FileRepository(root)
        val result = repo.localDirectoryRecovery.archive(repo.localDirectoryRecovery.prepareArchive(item.token!!, "rescue.tar"), cache(), {})
        val receipt = File(root, result.receiptPath).readBytes()
        assertNotNull(runCatching { repo.writeText(result.receiptPath, "replace") }.exceptionOrNull())
        assertNotNull(runCatching { repo.delete(LocalDirectoryArchiveJournal.HOME) }.exceptionOrNull())
        assertArrayEquals(receipt, File(root, result.receiptPath).readBytes())
    }

    private fun probe(lock: File): Int = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
        "-cp", System.getProperty("java.class.path"), F160MetadataLockProbe::class.java.name, lock.path)
        .redirectErrorStream(true).start().let { process -> val output = process.inputStream.bufferedReader().readText(); val result = process.waitFor(); assertTrue(output, result in setOf(0, 3)); result }
}

object F160MetadataLockProbe {
    @JvmStatic fun main(args: Array<String>) {
        FileChannel.open(File(args[0]).toPath(), READ, WRITE).use { channel ->
            val lock = channel.tryLock()
            if (lock == null) { println("busy"); kotlin.system.exitProcess(3) }
            lock.use { println("acquired") }
        }
    }
}

package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileRepositoryPreparedStageRetentionTest {
    @Test fun textPreparationFailureKeepsTheExactStageNamedInTheIntent() = fixture { root ->
        val failure = IOException("intent publication outcome unknown")
        var recorded: WorkspaceLocalPreparation? = null
        val repo = repository(root, prepare = { recorded = it; throw failure })
        val actual = runCatching { repo.writeText("new.txt", "editor proposal") }.exceptionOrNull()
        assertSame(failure, actual)
        assertFalse(root.resolve("new.txt").exists())
        assertRetained(root, recorded!!, "editor proposal".toByteArray())
    }

    @Test fun canceledStreamPreparationClosesInputButKeepsTheAdmittedPayload() = fixture { root ->
        val failure = CancellationException("stop after intent")
        val bytes = byteArrayOf(0, -1, 1, 42)
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        var recorded: WorkspaceLocalPreparation? = null
        val actual = runCatching {
            repository(root, prepare = { recorded = it; throw failure }).writeStream("new.bin", input)
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertTrue(closed)
        assertFalse(root.resolve("new.bin").exists())
        assertRetained(root, recorded!!, bytes)
    }

    @Test fun lateLocalAdmissionFailureKeepsTextAndStreamStages() = fixture { root ->
        for (stream in listOf(false, true)) {
            val bytes = if (stream) byteArrayOf(0, 1, -1) else "proposal".toByteArray()
            var recorded: WorkspaceLocalPreparation? = null
            val failure = IOException("attempt force failed")
            val repo = repository(root, prepare = { recorded = it }, attempt = { throw failure })
            val actual = runCatching {
                if (stream) repo.writeStream("asset.bin", bytes.inputStream())
                else repo.writeText("asset.bin", bytes.toString(Charsets.UTF_8))
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertFalse(root.resolve("asset.bin").exists())
            assertRetained(root, recorded!!, bytes)
        }
    }

    @Test fun observerThatThrowsAfterAdmittingPublicationCannotLoseTheOnlyStreamCopy() = fixture { root ->
        var stageIdentity: String? = null
        var publicationCalls = 0
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ -> publicationCalls++ })
        val failure = IOException("journal force result unknown")
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) {
                stageIdentity = preparation.stageIdentity
                throw failure
            }
            override fun onPublished(receipt: StreamPublicationReceipt) { fail("No publication") }
        }
        assertSame(failure, runCatching {
            repo.writeStream("asset.bin", "sole provider bytes".byteInputStream(), publicationObserver = observer)
        }.exceptionOrNull())
        assertEquals(0, publicationCalls)
        val kept = stages(root).single()
        assertEquals(stageIdentity, ProjectCopyEvidence.identity(kept).key)
        assertEquals("sole provider bytes", kept.readText())
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun lostPublisherResponseKeepsPayloadAndReportsUnknownWithoutInventingReceipt() = fixture { root ->
        var recorded: WorkspaceLocalPreparation? = null
        var receipt: WorkspaceLocalReceipt? = null
        val repo = repository(root, prepare = { recorded = it }, returned = { receipt = it },
            publisher = NewProjectFilePublisher { _, _ -> throw IOException("publisher response lost") })
        val actual = runCatching {
            repo.writeStream("asset.bin", "unpublished proposal".byteInputStream(), requireAbsent = true)
        }.exceptionOrNull()
        assertTrue(actual is StreamPublicationUncertainException)
        assertFalse((actual as StreamPublicationUncertainException).publicationReturned)
        assertFalse(receipt!!.successful)
        assertRetained(root, recorded!!, "unpublished proposal".toByteArray())
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun receiptFailureAfterHardLinkPublicationKeepsBothNamesAndTheOriginalError() = fixture { root ->
        var recorded: WorkspaceLocalPreparation? = null
        val failure = IOException("mandatory receipt force failed")
        val repo = repository(root, prepare = { recorded = it }, returned = { throw failure },
            publisher = NewProjectFilePublisher { stage, destination -> Files.createLink(destination.toPath(), stage.toPath()) })
        val actual = runCatching {
            repo.writeStream("asset.bin", "complete proposal".byteInputStream(), requireAbsent = true)
        }.exceptionOrNull()
        assertTrue(actual is StreamPublicationUncertainException)
        assertSame(failure, actual!!.cause)
        assertEquals("complete proposal", root.resolve("asset.bin").readText())
        assertRetained(root, recorded!!, "complete proposal".toByteArray())
    }

    @Test fun verifiedReceiptsPreserveHardLinkedStagesForTextAndStream() = fixture { root ->
        for (stream in listOf(false, true)) {
            val receipts = mutableListOf<WorkspaceLocalReceipt>()
            val path = if (stream) "stream.bin" else "text.txt"
            val repo = repository(root, returned = { receipts += it },
                publisher = NewProjectFilePublisher { stage, destination -> Files.createLink(destination.toPath(), stage.toPath()) })
            if (stream) repo.writeStream(path, "complete".byteInputStream(), requireAbsent = true)
            else repo.writeTextIfUnchanged(path, "complete", DiskFileVersion.fromText(null))
            assertEquals("complete", root.resolve(path).readText())
            val notice = repo.retainedWork.value.notices.single()
            assertTrue(notice.historicalWriteReturned)
            assertEquals(path, notice.target)
            assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
            val retained = root.resolve(notice.candidate.path)
            assertEquals("complete", retained.readText())
            assertEquals(ProjectCopyEvidence.identity(root.resolve(path)), ProjectCopyEvidence.identity(retained))
            assertEquals(if (stream) 2 else 1, stages(root).size)
            assertEquals(1, receipts.size)
            assertTrue(receipts.single().successful)
            assertEquals(stream, receipts.single().durable)
        }
    }

    @Test fun synchronizationFailureAfterVerifiedReceiptKeepsCanonicalFileAndStageEvidence() = fixture { root ->
        val failure = IOException("cross-view sync unavailable")
        val repo = FileRepository(root, onMutation = { throw failure },
            newFilePublisher = NewProjectFilePublisher { stage, destination -> Files.createLink(destination.toPath(), stage.toPath()) },
            participant = participant())
        val actual = runCatching { repo.writeStream("asset.bin", "saved".byteInputStream(), requireAbsent = true) }.exceptionOrNull()
        assertTrue(actual is ProjectFileSyncException)
        assertSame(failure, actual!!.cause)
        assertEquals("saved", root.resolve("asset.bin").readText())
        val retained = stages(root).single()
        assertEquals("saved", retained.readText())
        assertEquals(ProjectCopyEvidence.identity(root.resolve("asset.bin")), ProjectCopyEvidence.identity(retained))
    }

    @Test fun cancellationAfterParentReplacementPreservesBothOldAndNewNamedStages() = fixture { root ->
        val parent = root.resolve("sub").apply { mkdir() }
        val retired = root.resolve("retained-parent")
        var recorded: WorkspaceLocalPreparation? = null
        val repo = repository(root, prepare = {
            recorded = it
            Files.move(parent.toPath(), retired.toPath())
            parent.mkdir()
            File(parent, it.sourcePath!!.substringAfterLast('/')).writeText("foreign replacement")
            throw CancellationException("parent changed after intent")
        })
        assertTrue(runCatching { repo.writeText("sub/new.txt", "retained proposal") }.exceptionOrNull() is CancellationException)
        val name = recorded!!.sourcePath!!.substringAfterLast('/')
        assertEquals("retained proposal", File(retired, name).readText())
        assertEquals("foreign replacement", File(parent, name).readText())
        assertFalse(parent.resolve("new.txt").exists())
    }

    @Test fun retainedDescriptorsCloseEvenAcrossRepeatedPreparationFailures() = fixture { root ->
        repeat(5) { assertTrue(root.resolve("batch-$it").mkdir()) }
        val before = File("/proc/self/fd").list()!!.size
        val recorded = mutableListOf<WorkspaceLocalPreparation>()
        val failure = IOException("uncertain prepare")
        val repo = repository(root, prepare = { recorded += it; throw failure })
        repeat(80) { index ->
            assertSame(failure, runCatching {
                repo.writeText("batch-${index / 16}/file-$index.txt", "proposal-$index")
            }.exceptionOrNull())
        }
        assertEquals(80, recorded.size)
        assertEquals(80, stages(root).size)
        recorded.forEachIndexed { index, preparation ->
            assertRetained(root, preparation, "proposal-$index".toByteArray())
        }
        assertTrue(File("/proc/self/fd").list()!!.size <= before + 2)
    }

    @Test fun actualCopyJournalRestartsUnknownWithTheOnlyProviderPayloadStillAvailable() = fixture { root ->
        val journal = ProjectCopyJournal(root)
        val kind = ProjectCopyJournal.Kind.EXPLORER_IMPORT
        val scope = ProjectCopyJournal.Scope(kind)
        val owner = ProjectCopyJournal.Owner(kind, null, "original-picker", "a".repeat(64))
        var preparation: StreamPublicationPreparation? = null
        journal.begin(owner, "asset.bin").use { lease ->
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(value: StreamPublicationPreparation) {
                    preparation = value
                    lease.beforePublish(value)
                }
                override fun onPublished(receipt: StreamPublicationReceipt) = lease.onPublished(receipt)
            }
            val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ ->
                throw IOException("publication outcome unknown")
            })
            assertTrue(runCatching {
                repo.writeStream("asset.bin", "single-use provider payload".byteInputStream(),
                    requireAbsent = true, publicationObserver = observer)
            }.exceptionOrNull() is StreamPublicationUncertainException)
        }
        val payload = stages(root).single()
        assertEquals(preparation!!.stageIdentity, ProjectCopyEvidence.identity(payload).key)
        assertEquals("single-use provider payload", payload.readText())
        val restarted = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
        val record = restarted.recovery(scope).records.single()
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, record.status)
        assertNull(record.receipt)
        assertFalse(record.active)
        assertThrows(ProjectCopyJournal.Blocked::class.java) {
            restarted.begin(owner.copy(runId = "new-picker"), "renamed.bin")
        }
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, restarted.recovery(scope).records.single().status)
        assertEquals("single-use provider payload", payload.readText())
        assertFalse(root.resolve("asset.bin").exists())
        assertFalse(root.resolve("renamed.bin").exists())
    }

    @Test fun actualCopyJournalAttemptForceFailureKeepsUncertainMetadataAndPayload() = fixture { root ->
        val journal = ProjectCopyJournal(root, durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) {
                if (root.resolve(".droide/copy-recovery").walkTopDown().any { it.name == "attempt.json" }) {
                    throw IOException("attempt data force failed")
                }
                channel.force(true)
            }
            override fun forceDirectory(directory: File) = ProjectCopyJournal.Durability.platform.forceDirectory(directory)
        })
        val kind = ProjectCopyJournal.Kind.EXPLORER_IMPORT
        journal.begin(ProjectCopyJournal.Owner(kind, null, "picker", "b".repeat(64)), "asset.bin").use { lease ->
            assertTrue(runCatching {
                FileRepository(root).writeStream("asset.bin", "retain on failed fsync".byteInputStream(),
                    requireAbsent = true, publicationObserver = lease)
            }.exceptionOrNull() is IOException)
        }
        assertEquals("retain on failed fsync", stages(root).single().readText())
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(ProjectCopyJournal.Scope(kind)).records.single().status)
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun conditionalBinaryObserverFailureKeepsExistingFileAndNewPayload() = fixture { root ->
        val original = root.resolve("asset.bin").apply { writeText("original binary") }
        val expected = StreamFileVersion.capture(root, original.name)
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { throw IOException("uncertain intent") }
            override fun onPublished(receipt: StreamPublicationReceipt) { fail("No receipt") }
        }
        assertTrue(runCatching {
            FileRepository(root).writeStream(original.name, byteArrayOf(0, -1, 11).inputStream(),
                expectedVersion = expected, publicationObserver = observer)
        }.exceptionOrNull() is IOException)
        assertEquals("original binary", original.readText())
        assertArrayEquals(byteArrayOf(0, -1, 11), stages(root).single().readBytes())
    }

    private fun participant(
        prepare: suspend (WorkspaceLocalPreparation) -> Unit = {},
        attempt: (WorkspaceLocalPreparation) -> Unit = {},
        returned: (WorkspaceLocalReceipt) -> Unit = {},
    ) = WorkspaceMutationParticipant { guard, mutation -> object : WorkspaceMutationSession(guard, mutation) {
        override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) = prepare(preparation)
        override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) = attempt(preparation)
        override fun localReturned(receipt: WorkspaceLocalReceipt) = returned(receipt)
    } }

    private fun repository(
        root: File,
        prepare: suspend (WorkspaceLocalPreparation) -> Unit = {},
        attempt: (WorkspaceLocalPreparation) -> Unit = {},
        returned: (WorkspaceLocalReceipt) -> Unit = {},
        publisher: NewProjectFilePublisher = NewProjectFilePublisher.platform,
    ) = FileRepository(root, newFilePublisher = publisher, participant = participant(prepare, attempt, returned))

    private fun stages(root: File) = root.walkTopDown().filter { it.isFile && it.name.startsWith(".droide-tmp-") }.toList()

    private fun assertRetained(root: File, preparation: WorkspaceLocalPreparation, bytes: ByteArray) {
        val stage = root.resolve(preparation.sourcePath!!)
        assertEquals(preparation.sourceIdentity, ProjectCopyEvidence.identity(stage).key)
        assertArrayEquals(bytes, stage.readBytes())
    }

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-prepared-stage-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

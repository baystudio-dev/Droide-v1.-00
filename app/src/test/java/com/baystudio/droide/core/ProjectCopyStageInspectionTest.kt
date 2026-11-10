package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyStageInspectionTest {
    private val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)
    private val payload = byteArrayOf(0, 1, -1, 42, 10) + "single-use provider bytes".toByteArray()

    @Test fun preAttemptFailureRetainsTheExactPlannedPayloadAcrossRestart() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("original"), "asset.bin", planStage = true)
            val path = requireNotNull(lease.intent.plannedStagePath)
            val intent = intentFile(root, lease.intent.id).readBytes()
            var publisherCalls = 0
            val failure = IOException("observer admission stopped")
            val observer = object : StreamPublicationObserver, StreamStagePlan {
                override val plannedStagePath: String? get() = lease.plannedStagePath
                override fun beforePublish(preparation: StreamPublicationPreparation) { throw failure }
                override fun onPublished(receipt: StreamPublicationReceipt) { fail("Unadmitted publication") }
            }
            val actual = runCatching {
                FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ -> publisherCalls++ })
                    .writeStream("asset.bin", payload.inputStream(), requireAbsent = true, publicationObserver = observer)
            }.exceptionOrNull()
            lease.close()
            assertSame(failure, actual)
            assertEquals(0, publisherCalls)
            assertArrayEquals(payload, root.resolve(path).readBytes())
            assertFalse(root.resolve("asset.bin").exists())
            assertFalse(File(intentFile(root, lease.intent.id).parentFile, "attempt.json").exists())
            val restarted = journal(root)
            val before = metadata(root)
            val record = restarted.recovery(scope).records.single()
            assertEquals(ProjectCopyJournal.Status.PREPARED, record.status)
            assertTrue(record.needsStageInspection)
            assertEquals(ProjectCopyEvidence.StagePresence.PRESENT, record.stagePresence)
            val stage = requireNotNull(restarted.inspect(scope, record.intent.id).stage)
            assertEquals(path, stage.relativePath)
            assertEquals(ProjectCopyEvidence.StageStatus.UNVERIFIED, stage.status)
            assertEquals(payload.size.toLong(), stage.bytes)
            assertNotNull(stage.identity)
            assertNull(stage.sha256)
            assertEquals(before, metadata(root))
            assertArrayEquals(intent, intentFile(root, lease.intent.id).readBytes())
        }
    }

    @Test fun plannedStageCollisionPreservesForeignBytesAndClosesInputWithoutFallback() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("collision"), "asset.bin", planStage = true)
            val stage = root.resolve(requireNotNull(lease.intent.plannedStagePath)).apply { writeText("foreign owner") }
            val identity = ProjectCopyEvidence.identity(stage)
            var closed = false
            var publisherCalls = 0
            val input = object : ByteArrayInputStream(payload) {
                override fun close() { closed = true; super.close() }
            }
            val result = runCatching {
                FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ -> publisherCalls++ })
                    .writeStream("asset.bin", input, requireAbsent = true, publicationObserver = lease)
            }
            lease.close()
            assertTrue(result.isFailure)
            assertTrue(closed)
            assertEquals(0, publisherCalls)
            assertEquals(identity, ProjectCopyEvidence.identity(stage))
            assertEquals("foreign owner", stage.readText())
            assertEquals(listOf(stage.name), root.listFiles()!!.filter { it.name.startsWith(".droide-tmp-") }.map { it.name })
            assertFalse(root.resolve("asset.bin").exists())
            val inspection = journal(root).inspect(scope, lease.intent.id)
            assertEquals(ProjectCopyEvidence.StageStatus.UNVERIFIED, requireNotNull(inspection.stage).status)
        }
    }

    @Test fun decoratorThatOmitsThePlanCannotAdmitAnUnrelatedRandomStage() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("missing-forwarding"), "asset.bin", planStage = true)
            var publisherCalls = 0
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) = lease.beforePublish(preparation)
                override fun onPublished(receipt: StreamPublicationReceipt) = lease.onPublished(receipt)
            }
            val result = runCatching {
                FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ -> publisherCalls++ })
                    .writeStream("asset.bin", payload.inputStream(), requireAbsent = true, publicationObserver = observer)
            }
            lease.close()
            assertTrue(result.isFailure)
            assertEquals(0, publisherCalls)
            assertFalse(root.resolve(requireNotNull(lease.intent.plannedStagePath)).exists())
            val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
            assertArrayEquals(payload, retained.readBytes())
            assertFalse(root.resolve("asset.bin").exists())
            assertFalse(File(intentFile(root, lease.intent.id).parentFile, "attempt.json").exists())
        }
    }

    @Test fun recordedStageIdentityAndContentCanBeVerifiedWithoutMetadataWrites() {
        withProject { root ->
            val prepared = attempt(root)
            val journal = journal(root)
            val before = metadata(root)
            val record = journal.recovery(scope).records.single()
            assertEquals(prepared.preparation, record.preparation)
            val stage = requireNotNull(journal.inspect(scope, prepared.id).stage)
            assertEquals(ProjectCopyEvidence.StageStatus.VERIFIED, stage.status)
            assertEquals(prepared.stage.relativeTo(root).invariantSeparatorsPath, stage.relativePath)
            assertEquals(prepared.preparation.stageIdentity, stage.identity)
            assertEquals(prepared.preparation.sha256, stage.sha256)
            assertEquals(payload.size.toLong(), stage.bytes)
            assertEquals(before, metadata(root))
            assertArrayEquals(payload, prepared.stage.readBytes())
        }
    }

    @Test fun identicalBytesInAReplacementInodeAreNotAcceptedAsTheRecordedStage() {
        withProject { root ->
            val prepared = attempt(root)
            val retained = root.resolve("original-inode")
            Files.move(prepared.stage.toPath(), retained.toPath())
            prepared.stage.writeBytes(payload)
            val before = metadata(root)
            val stage = requireNotNull(journal(root).inspect(scope, prepared.id).stage)
            assertEquals(ProjectCopyEvidence.StageStatus.MISMATCH, stage.status)
            assertNotEquals(prepared.preparation.stageIdentity, stage.identity)
            assertArrayEquals(payload, retained.readBytes())
            assertArrayEquals(payload, prepared.stage.readBytes())
            assertEquals(before, metadata(root))
        }
    }

    @Test fun sameLengthContentChangeWithRestoredMtimeRemainsAMismatch() {
        withProject { root ->
            val prepared = attempt(root)
            val modified = Files.getLastModifiedTime(prepared.stage.toPath())
            val changed = payload.copyOf().also { it[0] = 100 }
            prepared.stage.writeBytes(changed)
            Files.setLastModifiedTime(prepared.stage.toPath(), modified)
            val before = metadata(root)
            val stage = requireNotNull(journal(root).inspect(scope, prepared.id).stage)
            assertEquals(ProjectCopyEvidence.StageStatus.MISMATCH, stage.status)
            assertEquals(prepared.preparation.stageIdentity, stage.identity)
            assertArrayEquals(changed, prepared.stage.readBytes())
            assertEquals(before, metadata(root))
        }
    }

    @Test fun largeRetainedStageReportsUnverifiedSizeWithoutHashingIt() {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("large"), "large.bin", planStage = true)
            val stage = root.resolve(requireNotNull(lease.intent.plannedStagePath))
            val size = ProjectCopyEvidence.MAX_INSPECTION_BYTES + 1L
            FileChannel.open(stage.toPath(), CREATE_NEW, WRITE).use { channel ->
                channel.position(size - 1L)
                channel.write(ByteBuffer.wrap(byteArrayOf(0)))
                channel.force(true)
            }
            lease.beforePublish(preparation(root, lease, stage, size, "0".repeat(64)))
            lease.close()
            val before = metadata(root)
            val observed = requireNotNull(journal(root).inspect(scope, lease.intent.id).stage)
            assertEquals(ProjectCopyEvidence.StageStatus.TOO_LARGE, observed.status)
            assertEquals(size, observed.bytes)
            assertNull(observed.sha256)
            assertEquals(size, stage.length())
            assertEquals(before, metadata(root))
        }
    }

    @Test fun symbolicLinkCandidateIsReportedUnsafeWithoutReadingItsTarget() {
        withProject { root -> withProject { outside ->
            val journal = journal(root)
            val lease = journal.begin(owner("symlink"), "asset.bin", planStage = true)
            lease.close()
            val target = outside.resolve("private.bin").apply { writeText("outside private bytes") }
            val targetIdentity = ProjectCopyEvidence.identity(target)
            val stage = root.resolve(requireNotNull(lease.intent.plannedStagePath))
            Files.createSymbolicLink(stage.toPath(), target.toPath())
            val before = metadata(root)
            val record = journal(root).recovery(scope).records.single()
            assertEquals(ProjectCopyEvidence.StagePresence.UNVERIFIABLE, record.stagePresence)
            assertTrue(record.needsStageInspection)
            val observed = requireNotNull(journal(root).inspect(scope, lease.intent.id).stage)
            assertEquals(ProjectCopyEvidence.StageStatus.UNSAFE, observed.status)
            assertNull(observed.sha256)
            assertEquals(targetIdentity, ProjectCopyEvidence.identity(target))
            assertEquals("outside private bytes", target.readText())
            assertTrue(Files.isSymbolicLink(stage.toPath()))
            assertEquals(before, metadata(root))
        } }
    }

    @Test fun intermediateSymlinkFailsClosedAndPreservesBothParentTrees() {
        withProject { root -> withProject { outside ->
            root.resolve("nested").mkdir()
            val journal = journal(root)
            val lease = journal.begin(owner("parent-link"), "nested/asset.bin", planStage = true)
            lease.close()
            val stageName = requireNotNull(lease.intent.plannedStagePath).substringAfterLast('/')
            root.resolve("nested/$stageName").writeBytes(payload)
            val retired = root.resolve("retained-parent")
            Files.move(root.resolve("nested").toPath(), retired.toPath())
            val foreign = outside.resolve(stageName).apply { writeText("foreign private data") }
            val foreignIdentity = ProjectCopyEvidence.identity(foreign)
            Files.createSymbolicLink(root.resolve("nested").toPath(), outside.toPath())
            val before = metadata(root)
            val restarted = journal(root)
            assertTrue(restarted.recovery(scope).records.single().needsStageInspection)
            val observed = runCatching { restarted.inspect(scope, lease.intent.id) }
            if (observed.isSuccess) {
                assertEquals(ProjectCopyEvidence.StageStatus.UNSAFE, requireNotNull(observed.getOrThrow().stage).status)
                assertNull(observed.getOrThrow().stage!!.sha256)
            } else assertNotNull(observed.exceptionOrNull())
            assertArrayEquals(payload, retired.resolve(stageName).readBytes())
            assertEquals(foreignIdentity, ProjectCopyEvidence.identity(foreign))
            assertEquals("foreign private data", foreign.readText())
            assertEquals(before, metadata(root))
        } }
    }

    @Test fun directoryCandidateIsNotTraversedOrRemovedByInspection() {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("directory"), "asset.bin", planStage = true)
            lease.close()
            val stage = root.resolve(requireNotNull(lease.intent.plannedStagePath)).apply { mkdir() }
            val child = stage.resolve("user-work.bin").apply { writeBytes(payload) }
            val identity = ProjectCopyEvidence.identity(child)
            val before = metadata(root)
            val observed = requireNotNull(journal(root).inspect(scope, lease.intent.id).stage)
            assertEquals(ProjectCopyEvidence.StageStatus.UNSAFE, observed.status)
            assertNull(observed.sha256)
            assertEquals(identity, ProjectCopyEvidence.identity(child))
            assertArrayEquals(payload, child.readBytes())
            assertEquals(before, metadata(root))
        }
    }

    @Test fun preparedCandidateInspectionCannotAuthorizeAnotherCopy() {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("prepared"), "asset.bin", planStage = true)
            root.resolve(requireNotNull(lease.intent.plannedStagePath)).writeBytes(payload)
            lease.close()
            val before = metadata(root)
            val inspection = journal.inspect(scope, lease.intent.id)
            assertEquals(ProjectCopyJournal.Status.PREPARED, inspection.record.status)
            assertEquals(ProjectCopyEvidence.StageStatus.UNVERIFIED, requireNotNull(inspection.stage).status)
            assertTrue(runCatching { journal.acknowledge(scope, inspection.token) }.isFailure)
            assertEquals(before, metadata(root))
            assertFalse(root.resolve("asset.bin").exists())
        }
    }

    @Test fun committedSyncFailureRetainsItsLocatorAndReceiptThroughSuccessfulHistoryChurn() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("sync-pending"), "asset.bin", planStage = true)
            val result = runCatching {
                FileRepository(root, onMutation = { throw IOException("cross-view synchronization failed") },
                    newFilePublisher = hardLinkPublisher()).writeStream("asset.bin", payload.inputStream(),
                    requireAbsent = true, publicationObserver = lease)
            }
            lease.close()
            assertTrue(result.exceptionOrNull() is ProjectFileSyncException)
            val originalReceipt = requireNotNull(lease.committedReceipt)
            val stage = root.resolve(requireNotNull(lease.intent.plannedStagePath))
            val identity = ProjectCopyEvidence.identity(stage)
            val before = recordMetadata(root, lease.intent.id)
            repeat(8) { commit(root, journal, "history-$it") }
            val restarted = journal(root)
            val record = restarted.recovery(scope).records.single { it.intent.id == lease.intent.id }
            assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
            assertEquals(originalReceipt, record.receipt)
            assertTrue(record.needsStageInspection)
            assertEquals(ProjectCopyEvidence.StagePresence.PRESENT, record.stagePresence)
            val inspection = restarted.inspect(scope, lease.intent.id)
            assertEquals(ProjectCopyEvidence.StageStatus.VERIFIED, requireNotNull(inspection.stage).status)
            assertTrue(runCatching { restarted.acknowledge(scope, inspection.token) }.isFailure)
            assertEquals(before, recordMetadata(root, lease.intent.id))
            assertEquals(identity, ProjectCopyEvidence.identity(stage))
            assertArrayEquals(payload, stage.readBytes())
            assertArrayEquals(payload, root.resolve("asset.bin").readBytes())
        }
    }

    @Test fun nativePlannedImportsConsumeTheirStagesAndKeepHistoryBounded() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            repeat(8) { commit(root, journal, "success-$it") }
            val records = journal(root).recovery(scope).records
            assertEquals(2, records.size)
            assertTrue(records.all { it.status == ProjectCopyJournal.Status.COMMITTED })
            assertTrue(records.all { it.stagePresence == ProjectCopyEvidence.StagePresence.ABSENT && !it.needsStageInspection })
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".droide-tmp-") })
            repeat(8) { assertEquals("success-$it payload", root.resolve("success-$it.bin").readText()) }
        }
    }

    @Test fun stageDisappearanceAfterAttemptDoesNotInventHistoricalSuccess() {
        withProject { root ->
            val prepared = attempt(root)
            Files.move(prepared.stage.toPath(), root.resolve("asset.bin").toPath())
            val before = metadata(root)
            val inspection = journal(root).inspect(scope, prepared.id)
            assertEquals(ProjectCopyJournal.Status.UNKNOWN, inspection.record.status)
            assertNull(inspection.record.receipt)
            assertEquals(ProjectCopyEvidence.StageStatus.ABSENT, requireNotNull(inspection.stage).status)
            assertArrayEquals(payload, root.resolve("asset.bin").readBytes())
            assertEquals(before, metadata(root))
        }
    }

    @Test fun changedRetainedContentInvalidatesAnEarlierUnknownInspectionPermission() {
        withProject { root ->
            val prepared = attempt(root)
            val journal = journal(root)
            val inspection = journal.inspect(scope, prepared.id)
            assertEquals(ProjectCopyEvidence.StageStatus.VERIFIED, requireNotNull(inspection.stage).status)
            val changed = payload.copyOf().also { it[0] = 99 }
            prepared.stage.writeBytes(changed)
            val before = metadata(root)
            assertTrue(runCatching { journal.acknowledge(scope, inspection.token) }.isFailure)
            assertArrayEquals(changed, prepared.stage.readBytes())
            assertEquals(before, metadata(root))
            assertFalse(root.resolve("asset.bin").exists())
        }
    }

    @Test fun candidateAppearingDuringRetirementStopsMetadataDeletion() = runBlocking {
        withProject { root ->
            var candidate: File? = null
            var armed = false
            val io = object : ProjectCopyJournal.Durability {
                override fun forceFile(channel: FileChannel) = ProjectCopyJournal.Durability.platform.forceFile(channel)
                override fun forceDirectory(directory: File) {
                    ProjectCopyJournal.Durability.platform.forceDirectory(directory)
                    if (armed && directory.resolve("retired.json").isFile) {
                        armed = false
                        requireNotNull(candidate).writeBytes(payload)
                    }
                }
            }
            val journal = ProjectCopyJournal(root, durability = io, historyLimit = 1, processEpoch = UUID.randomUUID().toString())
            val lease = journal.begin(owner("retirement-race"), "asset.bin", planStage = true)
            FileRepository(root, newFilePublisher = NewProjectFilePublisher.native()).writeStream("asset.bin", payload.inputStream(),
                requireAbsent = true, publicationObserver = lease)
            lease.close()
            candidate = root.resolve(requireNotNull(lease.intent.plannedStagePath))
            assertFalse(candidate!!.exists())
            val before = recordMetadata(root, lease.intent.id)
            armed = true
            runCatching { journal.begin(owner("new-copy"), "other.bin", planStage = true) }.getOrNull()?.close()
            assertFalse(armed)
            assertArrayEquals(payload, candidate!!.readBytes())
            val record = journal(root).recovery(scope).records.single { it.intent.id == lease.intent.id }
            assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
            assertTrue(record.needsStageInspection)
            assertFalse(record.retired)
            before.forEach { (name, value) -> assertEquals(value, recordMetadata(root, lease.intent.id)[name]) }
            assertTrue(intentFile(root, lease.intent.id).parentFile.resolve("retired.json").isFile)
            assertArrayEquals(payload, root.resolve("asset.bin").readBytes())
            assertFalse(root.resolve("other.bin").exists())
        }
    }

    @Test fun movedOrReplacedParentCannotTurnRetainedPayloadIntoPrunableAbsence() = runBlocking {
        for (replacement in listOf("missing", "empty", "foreign-stage")) withProject { root ->
            val parent = root.resolve("nested").apply { mkdir() }
            val journal = journal(root)
            val lease = journal.begin(owner("moved-$replacement"), "nested/asset.bin", planStage = true)
            assertTrue(runCatching {
                FileRepository(root, onMutation = { throw IOException("sync unavailable") },
                    newFilePublisher = hardLinkPublisher()).writeStream("nested/asset.bin", payload.inputStream(),
                    requireAbsent = true, publicationObserver = lease)
            }.exceptionOrNull() is ProjectFileSyncException)
            lease.close()
            val receipt = requireNotNull(lease.committedReceipt)
            val name = requireNotNull(lease.plannedStagePath).substringAfterLast('/')
            val oldStage = parent.resolve(name)
            val originalIdentity = ProjectCopyEvidence.identity(oldStage)
            val before = recordMetadata(root, lease.intent.id)
            val retired = root.resolve("retained-parent")
            Files.move(parent.toPath(), retired.toPath())
            if (replacement != "missing") parent.mkdir()
            val foreign = if (replacement == "foreign-stage") parent.resolve(name).apply { writeText("foreign bytes") } else null
            repeat(8) { commit(root, journal, "unrelated-$it") }
            val restarted = journal(root)
            val record = restarted.recovery(scope).records.single { it.intent.id == lease.intent.id }
            assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
            assertEquals(receipt, record.receipt)
            assertEquals(ProjectCopyEvidence.StagePresence.UNVERIFIABLE, record.stagePresence)
            assertTrue(record.needsStageInspection)
            val inspection = restarted.inspect(scope, lease.intent.id)
            assertEquals(ProjectCopyEvidence.StageStatus.UNSAFE, requireNotNull(inspection.stage).status)
            assertTrue(runCatching { restarted.acknowledge(scope, inspection.token) }.isFailure)
            assertEquals(before, recordMetadata(root, lease.intent.id))
            assertEquals(originalIdentity, ProjectCopyEvidence.identity(retired.resolve(name)))
            assertArrayEquals(payload, retired.resolve(name).readBytes())
            assertArrayEquals(payload, retired.resolve("asset.bin").readBytes())
            foreign?.let { assertEquals("foreign bytes", it.readText()) }
        }
    }

    @Test fun preAttemptParentReplacementCannotHideRetainedPayloadFromRecovery() = runBlocking {
        for (replacement in listOf("empty", "missing", "foreign-stage")) withProject { root ->
            val parent = root.resolve("nested").apply { mkdir() }
            val journal = journal(root)
            val lease = journal.begin(owner("prepared-$replacement"), "nested/asset.bin", planStage = true)
            val observer = object : StreamPublicationObserver, StreamStagePlan {
                override val plannedStagePath: String? get() = lease.plannedStagePath
                override fun beforePublish(preparation: StreamPublicationPreparation) { throw IOException("admission stopped") }
                override fun onPublished(receipt: StreamPublicationReceipt) { fail("Unadmitted publication") }
            }
            assertTrue(runCatching {
                FileRepository(root).writeStream("nested/asset.bin", payload.inputStream(),
                    requireAbsent = true, publicationObserver = observer)
            }.isFailure)
            lease.close()
            val name = requireNotNull(lease.plannedStagePath).substringAfterLast('/')
            val identity = ProjectCopyEvidence.identity(parent.resolve(name))
            val before = recordMetadata(root, lease.intent.id)
            val retained = root.resolve("retained-parent")
            Files.move(parent.toPath(), retained.toPath())
            if (replacement != "missing") parent.mkdir()
            val foreign = if (replacement == "foreign-stage") parent.resolve(name).apply { writeText("foreign bytes") } else null
            repeat(8) { commit(root, journal, "prepared-churn-$it") }
            val restarted = journal(root)
            val record = restarted.recovery(scope).records.single { it.intent.id == lease.intent.id }
            assertEquals(ProjectCopyJournal.Status.PREPARED, record.status)
            assertTrue(record.needsStageInspection)
            assertNull(record.preparation)
            val inspection = restarted.inspect(scope, lease.intent.id)
            if (foreign == null) {
                assertEquals(ProjectCopyEvidence.StagePresence.UNVERIFIABLE, record.stagePresence)
                assertEquals(ProjectCopyEvidence.StageStatus.UNSAFE, requireNotNull(inspection.stage).status)
            } else {
                assertEquals(ProjectCopyEvidence.StagePresence.PRESENT, record.stagePresence)
                assertEquals(ProjectCopyEvidence.StageStatus.UNVERIFIED, requireNotNull(inspection.stage).status)
                assertEquals("foreign bytes", foreign.readText())
                assertNull(inspection.stage!!.sha256)
            }
            assertTrue(runCatching { restarted.acknowledge(scope, inspection.token) }.isFailure)
            assertEquals(before, recordMetadata(root, lease.intent.id))
            assertEquals(identity, ProjectCopyEvidence.identity(retained.resolve(name)))
            assertArrayEquals(payload, retained.resolve(name).readBytes())
            assertFalse(retained.resolve("asset.bin").exists())
        }
    }

    @Test fun largeCommittedDestinationDoesNotBlockBoundedReadOnlyStageInspection() {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("large-committed"), "large.bin", planStage = true)
            val stage = root.resolve(requireNotNull(lease.plannedStagePath))
            val size = ProjectCopyEvidence.MAX_INSPECTION_BYTES + 1
            FileChannel.open(stage.toPath(), CREATE_NEW, WRITE).use { channel ->
                channel.position(size - 1); channel.write(ByteBuffer.wrap(byteArrayOf(0))); channel.force(true)
            }
            val preparation = preparation(root, lease, stage, size, "0".repeat(64))
            lease.beforePublish(preparation)
            Files.createLink(root.resolve("large.bin").toPath(), stage.toPath())
            lease.onPublished(StreamPublicationReceipt(preparation, preparation.stageIdentity))
            lease.close()
            val before = metadata(root)
            val inspection = journal(root).inspect(scope, lease.intent.id)
            assertEquals(ProjectCopyJournal.Status.COMMITTED, inspection.record.status)
            assertEquals(size, requireNotNull(inspection.current.target).size)
            assertNull(inspection.current.sha256)
            assertEquals(ProjectCopyEvidence.StageStatus.TOO_LARGE, requireNotNull(inspection.stage).status)
            assertNull(inspection.stage!!.sha256)
            assertTrue(runCatching { journal.acknowledge(scope, inspection.token) }.isFailure)
            assertEquals(before, metadata(root))
        }
    }

    @Test fun unknownLargeDestinationRetainsTheFullInspectionPermissionBarrier() {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("large-unknown"), "large.bin", planStage = true)
            val stage = root.resolve(requireNotNull(lease.plannedStagePath))
            val size = ProjectCopyEvidence.MAX_INSPECTION_BYTES + 1
            FileChannel.open(stage.toPath(), CREATE_NEW, WRITE).use { channel ->
                channel.position(size - 1); channel.write(ByteBuffer.wrap(byteArrayOf(0))); channel.force(true)
            }
            lease.beforePublish(preparation(root, lease, stage, size, "0".repeat(64)))
            lease.close()
            Files.createLink(root.resolve("large.bin").toPath(), stage.toPath())
            val before = metadata(root)
            assertTrue(runCatching { journal(root).inspect(scope, lease.intent.id) }.isFailure)
            assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal(root).recovery(scope).records.single().status)
            assertEquals(before, metadata(root))
        }
    }

    private data class Attempt(val id: String, val stage: File, val preparation: StreamPublicationPreparation)

    private fun attempt(root: File): Attempt {
        val journal = journal(root)
        val lease = journal.begin(owner("attempt"), "asset.bin", planStage = true)
        val stage = root.resolve(requireNotNull(lease.intent.plannedStagePath)).apply { writeBytes(payload) }
        val preparation = preparation(root, lease, stage, payload.size.toLong(), ProjectCopyEvidence.sha256(payload))
        lease.beforePublish(preparation)
        lease.close()
        return Attempt(lease.intent.id, stage, preparation)
    }

    private fun preparation(root: File, lease: ProjectCopyJournal.Lease, stage: File, bytes: Long, hash: String) =
        StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath, ProjectCopyEvidence.root(root).second,
            lease.intent.destination, bytes, hash, ProjectCopyEvidence.identity(stage).key)

    private suspend fun commit(root: File, journal: ProjectCopyJournal, id: String) {
        journal.begin(owner(id), "$id.bin", planStage = true).use { lease ->
            FileRepository(root, newFilePublisher = NewProjectFilePublisher.native()).writeStream("$id.bin", "$id payload".byteInputStream(),
                requireAbsent = true, publicationObserver = lease)
        }
    }

    private fun hardLinkPublisher() = NewProjectFilePublisher { stage, destination ->
        Files.createLink(destination.toPath(), stage.toPath())
    }

    private fun owner(id: String) = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
        "picker-$id", ProjectCopyJournal.explorerSourceId("content://stage-test/$id"))

    private fun journal(root: File) = ProjectCopyJournal(root, historyLimit = 2, processEpoch = UUID.randomUUID().toString())
    private fun intentFile(root: File, id: String) = root.resolve(".droide/copy-recovery/$id/intent.json")
    private fun metadata(root: File) = snapshot(root.resolve(".droide/copy-recovery"))
    private fun recordMetadata(root: File, id: String) = snapshot(intentFile(root, id).parentFile)
    private fun snapshot(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        val identity = ProjectCopyEvidence.identity(it)
        it.relativeTo(directory).invariantSeparatorsPath to
            "${identity.key}:${identity.size}:${identity.modified}:${ProjectCopyEvidence.sha256(it.readBytes())}"
    }

    private inline fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("copy-stage-inspection-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

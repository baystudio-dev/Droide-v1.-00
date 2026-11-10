package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyEmptyRecordTest {
    private val explorer = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)
    private val agent = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-a")
    private val partial = "retained partial provider bytes".toByteArray()

    @Test fun emptyUuidRecordIsNotSilentlySkippedByTheSameJournal() = fixture { f ->
        val empty = emptyRecord(f.root)
        val journal = journal(f.root)
        val before = snapshot(f.base)
        assertBlockedRecovery(journal, explorer, empty.name)
        assertEquals(before, snapshot(f.base))
        assertTrue(empty.isDirectory)
        assertTrue(empty.listFiles()!!.isEmpty())
    }

    @Test fun freshOwnerBlocksBeforeOpeningSourceCreatingStageOrCallingPublisher() = fixture { f ->
        val empty = emptyRecord(f.root)
        f.root.resolve("sentinel.txt").writeText("original project data")
        val before = snapshot(f.base)
        var sourcesOpened = 0
        var sourceReads = 0
        var publisherCalls = 0
        val native = NewProjectFilePublisher.native()
        val files = FileRepository(f.root, newFilePublisher = NewProjectFilePublisher { stage, target ->
            publisherCalls++
            native.publish(stage, target)
        })
        val failure = runCatching {
            journal(f.root).begin(owner("new"), "new.bin", planStage = true).use { lease ->
                sourcesOpened++
                val input = object : ByteArrayInputStream(partial) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        sourceReads++
                        return super.read(bytes, offset, length)
                    }
                }
                runBlocking { files.writeStream("new.bin", input, requireAbsent = true, publicationObserver = lease) }
            }
        }.exceptionOrNull()
        assertBlocked(failure, empty.name)
        assertEquals(0, sourcesOpened)
        assertEquals(0, sourceReads)
        assertEquals(0, publisherCalls)
        assertEquals(before, snapshot(f.base))
        assertFalse(f.root.resolve("new.bin").exists())
    }

    @Test fun missingPreparedIntentCannotHideTheExactRetainedPartialStageAfterRestart() = fixture { f ->
        val journal = journal(f.root)
        val lease = journal.begin(owner("partial"), "partial.bin", planStage = true)
        val stage = createPartial(f.root, lease)
        lease.close()
        val prepared = journal.recovery(explorer).records.single()
        assertEquals(ProjectCopyJournal.Status.PREPARED, prepared.status)
        assertEquals(ProjectCopyEvidence.StagePresence.PRESENT, prepared.stagePresence)
        val intent = record(f.root, lease.intent.id).resolve("intent.json")
        val intentBytes = intent.readBytes()
        val heldIntent = f.control.resolve("retained-intent.json")
        Files.move(intent.toPath(), heldIntent.toPath())
        val before = snapshot(f.base)
        val restarted = journal(f.root)
        assertBlockedRecovery(restarted, explorer, lease.intent.id)
        assertBlockedBegin(restarted, owner("independent"), "independent.bin", lease.intent.id)
        assertEquals(before, snapshot(f.base))
        assertArrayEquals(intentBytes, heldIntent.readBytes())
        assertArrayEquals(partial, stage.readBytes())
        assertFalse(f.root.resolve("partial.bin").exists())
        assertFalse(f.root.resolve("independent.bin").exists())
    }

    @Test fun emptyRecordAmongValidRecordsBlocksAdmissionWithoutAlteringAnyEvidence() = fixture { f ->
        val journal = journal(f.root)
        val lease = journal.begin(owner("valid"), "valid.bin", planStage = true)
        val stage = createPartial(f.root, lease)
        lease.close()
        val validIntent = record(f.root, lease.intent.id).resolve("intent.json").readBytes()
        val empty = emptyRecord(f.root)
        val before = snapshot(f.base)
        assertBlockedRecovery(journal, explorer, empty.name)
        assertBlockedBegin(journal, owner("unrelated"), "unrelated.bin", empty.name)
        assertEquals(before, snapshot(f.base))
        assertArrayEquals(validIntent, record(f.root, lease.intent.id).resolve("intent.json").readBytes())
        assertArrayEquals(partial, stage.readBytes())
    }

    @Test fun interruptedRecordDirectoryForcePreservesTheEmptyEntryAndBlocksAFreshEpoch() = fixture { f ->
        val originalFailure = IOException("record directory force interrupted")
        var rejectedForces = 0
        val durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) = ProjectCopyJournal.Durability.platform.forceFile(channel)
            override fun forceDirectory(directory: File) {
                if (directory == journalDirectory(f.root) && directory.listFiles().orEmpty().any {
                        ProjectCopyEvidence.UUID_PATTERN.matches(it.name) && it.isDirectory && it.listFiles()!!.isEmpty()
                    }) {
                    rejectedForces++
                    throw originalFailure
                }
                ProjectCopyJournal.Durability.platform.forceDirectory(directory)
            }
        }
        val interrupted = ProjectCopyJournal(f.root, durability = durability, processEpoch = epoch())
        val actual = runCatching { interrupted.begin(owner("interrupted"), "interrupted.bin", planStage = true) }
            .exceptionOrNull()
        assertSame(originalFailure, actual)
        assertEquals(1, rejectedForces)
        val empty = journalDirectory(f.root).listFiles()!!.single()
        assertTrue(ProjectCopyEvidence.UUID_PATTERN.matches(empty.name))
        assertTrue(empty.listFiles()!!.isEmpty())
        val before = snapshot(f.base)
        val restarted = journal(f.root)
        assertBlockedRecovery(restarted, explorer, empty.name)
        assertBlockedBegin(restarted, owner("later"), "later.bin", empty.name)
        assertEquals(before, snapshot(f.base))
        assertFalse(f.root.resolve("interrupted.bin").exists())
        assertFalse(f.root.resolve("later.bin").exists())
    }

    @Test fun emptyCommittedRecordDirectoryIsNotInferredToBeSafeCompletedPruning() = fixture { f ->
        val journal = journal(f.root)
        val id = commit(f.root, journal, "completed")
        val directory = record(f.root, id)
        val heldMetadata = f.control.resolve("retired-$id").apply { check(mkdir()) }
        directory.listFiles()!!.forEach { Files.move(it.toPath(), heldMetadata.resolve(it.name).toPath()) }
        assertEquals(setOf("intent.json", "attempt.json", "receipt.json", "seal.json", "membership.json"),
            heldMetadata.listFiles()!!.map { it.name }.toSet())
        assertTrue(directory.listFiles()!!.isEmpty())
        val before = snapshot(f.base)
        val restarted = journal(f.root)
        assertBlockedRecovery(restarted, explorer, id)
        assertBlockedBegin(restarted, owner("new"), "new.bin", id)
        assertEquals(before, snapshot(f.base))
        assertArrayEquals(partial, f.root.resolve("completed.bin").readBytes())
    }

    @Test fun journalDirectoryWithZeroRecordEntriesStillAllowsItsFirstIntent() = fixture { f ->
        Files.createDirectories(journalDirectory(f.root).toPath())
        val journal = journal(f.root)
        val before = snapshot(f.base)
        val recovery = journal.recovery(explorer)
        assertNull(recovery.blockingProblem)
        assertTrue(recovery.records.isEmpty())
        assertEquals(before, snapshot(f.base))
        val lease = journal.begin(owner("first"), "first.bin", planStage = true)
        lease.close()
        val current = journal(f.root).recovery(explorer)
        assertNull(current.blockingProblem)
        assertEquals(ProjectCopyJournal.Status.PREPARED, current.records.single().status)
        assertEquals(lease.intent.id, current.records.single().intent.id)
        assertTrue(record(f.root, lease.intent.id).resolve("intent.json").isFile)
    }

    @Test fun untouchedProjectCanLazilyCreateAnIntactPreparedRecord() = fixture { f ->
        val journal = journal(f.root)
        val before = snapshot(f.base)
        assertNull(journal.recovery(explorer).blockingProblem)
        assertEquals(before, snapshot(f.base))
        assertFalse(f.root.resolve(".droide").exists())
        val lease = journal.begin(owner("prepared"), "prepared.bin", planStage = true)
        lease.close()
        val after = snapshot(f.base)
        val recovered = journal(f.root).recovery(explorer)
        assertNull(recovered.blockingProblem)
        assertEquals(lease.intent, recovered.records.single().intent)
        assertEquals(ProjectCopyJournal.Status.PREPARED, recovered.records.single().status)
        assertFalse(recovered.records.single().active)
        assertEquals(after, snapshot(f.base))
        assertFalse(f.root.resolve(requireNotNull(lease.plannedStagePath)).exists())
    }

    @Test fun intactNativeCommitStillRecoversItsHistoricalReceiptWithoutRewriting() = fixture { f ->
        val journal = journal(f.root)
        val id = commit(f.root, journal, "committed")
        val before = snapshot(f.base)
        val recovered = journal(f.root).recovery(explorer)
        assertNull(recovered.blockingProblem)
        val record = recovered.records.single()
        assertEquals(id, record.intent.id)
        assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
        assertEquals(ProjectCopyEvidence.StagePresence.ABSENT, record.stagePresence)
        assertEquals(ProjectCopyEvidence.sha256(partial), requireNotNull(record.receipt).preparation.sha256)
        assertEquals(before, snapshot(f.base))
        assertArrayEquals(partial, f.root.resolve("committed.bin").readBytes())
        journal(f.root).begin(owner("independent"), "independent.bin", planStage = true).close()
        assertFalse(f.root.resolve("independent.bin").exists())
    }

    @Test fun nonUuidEntryRemainsGloballyBlockingAndItsUserBytesArePreserved() = fixture { f ->
        val foreign = journalDirectory(f.root).resolve("foreign-private-record")
        Files.createDirectories(foreign.toPath())
        foreign.resolve("user-data.bin").writeBytes(partial)
        val journal = journal(f.root)
        val before = snapshot(f.base)
        assertNotNull(journal.recovery(explorer).blockingProblem)
        val failure = runCatching { journal.begin(owner("new"), "new.bin", planStage = true) }.exceptionOrNull()
        assertTrue(failure is ProjectCopyJournal.Blocked)
        assertEquals(before, snapshot(f.base))
        assertArrayEquals(partial, foreign.resolve("user-data.bin").readBytes())
    }

    @Test fun agentAndExplorerScopesCannotHideAnOwnerlessEmptyRecord() = fixture { f ->
        val empty = emptyRecord(f.root)
        val journal = journal(f.root)
        val before = snapshot(f.base)
        listOf(explorer, agent, ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-b")).forEach {
            assertBlockedRecovery(journal, it, empty.name)
        }
        assertBlockedBegin(journal, owner("explorer"), "explorer.bin", empty.name)
        val agentOwner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-a", "run-a", "media-a")
        assertBlockedBegin(journal, agentOwner, "agent.bin", empty.name)
        assertEquals(before, snapshot(f.base))
    }

    @Test fun activeLeaseAndSameProcessOwnershipCannotAuthorizeSkippingAMissingIntent() = fixture { f ->
        val process = epoch()
        val original = ProjectCopyJournal(f.root, processEpoch = process)
        val lease = original.begin(owner("active"), "active.bin", planStage = true)
        try {
            val intent = record(f.root, lease.intent.id).resolve("intent.json")
            Files.move(intent.toPath(), f.control.resolve("active-intent.json").toPath())
            val before = snapshot(f.base)
            listOf(original, ProjectCopyJournal(f.root, processEpoch = process), journal(f.root)).forEach {
                assertBlockedRecovery(it, explorer, lease.intent.id)
                assertBlockedBegin(it, owner("independent"), "independent.bin", lease.intent.id)
            }
            assertEquals(before, snapshot(f.base))
        } finally { lease.close() }
    }

    private fun assertBlockedRecovery(journal: ProjectCopyJournal, scope: ProjectCopyJournal.Scope, id: String) {
        val problem = journal.recovery(scope).blockingProblem
        assertNotNull("Ownerless empty record $id must block recovery", problem)
        assertTrue("Blocking recovery must identify $id: $problem", requireNotNull(problem).contains(id))
    }

    private fun assertBlockedBegin(journal: ProjectCopyJournal, owner: ProjectCopyJournal.Owner, path: String, id: String) {
        assertBlocked(runCatching { journal.begin(owner, path, planStage = true).close() }.exceptionOrNull(), id)
    }

    private fun assertBlocked(failure: Throwable?, id: String) {
        assertTrue("Expected blocked admission for $id, got $failure", failure is ProjectCopyJournal.Blocked)
        assertTrue("Blocked admission must identify $id: ${failure?.message}", failure!!.message.orEmpty().contains(id))
    }

    private fun createPartial(root: File, lease: ProjectCopyJournal.Lease): File {
        val path = requireNotNull(lease.plannedStagePath)
        OwnedStreamStage.create(root.resolve(lease.intent.destination), path.substringAfterLast('/')).use { stage ->
            assertEquals(root.resolve(path), stage.file)
            stage.output.write(partial)
            stage.output.flush()
            stage.force(StreamPublicationDurability.platform)
        }
        return root.resolve(path)
    }

    private fun commit(root: File, journal: ProjectCopyJournal, name: String): String = runBlocking {
        journal.begin(owner(name), "$name.bin", planStage = true).use { lease ->
            FileRepository(root, newFilePublisher = NewProjectFilePublisher.native()).writeStream(
                "$name.bin", partial.inputStream(), requireAbsent = true, publicationObserver = lease)
            lease.intent.id
        }
    }

    private fun owner(id: String) = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
        "run-$id", ProjectCopyJournal.explorerSourceId("content://test/$id"))

    private fun journal(root: File) = ProjectCopyJournal(root, processEpoch = epoch())
    private fun epoch() = UUID.randomUUID().toString()
    private fun journalDirectory(root: File) = root.resolve(".droide/copy-recovery")
    private fun record(root: File, id: String) = journalDirectory(root).resolve(id)

    private fun emptyRecord(root: File): File = record(root, epoch()).also { Files.createDirectories(it.toPath()) }

    private data class Node(val identity: String, val directory: Boolean, val bytes: Long,
        val modified: String, val sha256: String?)

    private fun snapshot(root: File): Map<String, Node> = Files.walk(root.toPath()).use { paths ->
        paths.iterator().asSequence().associate { path ->
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory || attrs.isRegularFile)
            root.toPath().relativize(path).joinToString("/") to Node(requireNotNull(attrs.fileKey()).toString(),
                attrs.isDirectory, attrs.size(), attrs.lastModifiedTime().toString(),
                if (attrs.isRegularFile) ProjectCopyEvidence.sha256(Files.readAllBytes(path)) else null)
        }
    }

    private data class Fixture(val base: File, val root: File, val control: File)

    private fun fixture(block: (Fixture) -> Unit) {
        val base = Files.createTempDirectory("copy-empty-record-").toFile()
        val root = base.resolve("project").apply { check(mkdir()) }
        val control = base.resolve("control").apply { check(mkdir()) }
        try { block(Fixture(base, root, control)) } finally { PathSecurity.deleteTreeNoFollow(base) }
    }
}

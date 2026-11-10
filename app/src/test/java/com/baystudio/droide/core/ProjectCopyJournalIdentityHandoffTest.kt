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

class ProjectCopyJournalIdentityHandoffTest {
    private val explorer = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)
    private val agent = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-a")
    private val payload = ByteArray(4096) { (it % 251).toByte() }

    @Test fun lostJournalBlocksNewOwnerBeforeSourceStageOrPublisher() = fixture { f ->
        prepare(f)
        moveToControl(f, journalDirectory(f.root), "old-journal")
        val next = journal(f)
        val before = snapshot(f.base)
        assertRejectedBegin(next, f.root, "new.bin")
        assertRejectedRecovery(next, explorer)
        assertEquals(before, snapshot(f.base))
    }

    @Test fun replacedJournalCannotBeAdoptedByNewSameEpochOwner() = fixture { f ->
        prepare(f)
        moveToControl(f, journalDirectory(f.root), "old-journal")
        check(journalDirectory(f.root).mkdir())
        val before = snapshot(f.base)
        val next = journal(f)
        assertRejectedRecovery(next, explorer)
        assertRejectedBegin(next, f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
    }

    @Test fun lostMetadataCannotBeRecreatedByNewSameEpochOwner() = fixture { f ->
        prepare(f)
        moveToControl(f, f.root.resolve(".droide"), "old-metadata")
        val before = snapshot(f.base)
        val next = journal(f)
        assertRejectedRecovery(next, explorer)
        assertRejectedBegin(next, f.root, "new.bin")
        assertFalse(f.root.resolve(".droide").exists())
        assertEquals(before, snapshot(f.base))
    }

    @Test fun replacedMetadataCannotBeAdoptedByNewSameEpochOwner() = fixture { f ->
        prepare(f)
        moveToControl(f, f.root.resolve(".droide"), "old-metadata")
        check(f.root.resolve(".droide").mkdir())
        val before = snapshot(f.base)
        val next = journal(f)
        assertRejectedRecovery(next, explorer)
        assertRejectedBegin(next, f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
    }

    @Test fun observedMetadataWithoutJournalStillProtectsItsLossAcrossHandoff() = fixture { f ->
        observeMetadataOnly(f)
        moveToControl(f, f.root.resolve(".droide"), "old-metadata")
        val before = snapshot(f.base)
        val next = journal(f)
        assertRejectedRecovery(next, explorer)
        assertRejectedBegin(next, f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
        assertFalse(f.root.resolve(".droide").exists())
    }

    @Test fun sameObjectMustRejectMissingObservedMetadataBeforeRecreatingIt() = fixture { f ->
        val original = observeMetadataOnly(f)
        moveToControl(f, f.root.resolve(".droide"), "old-metadata")
        val before = snapshot(f.base)
        assertRejectedBegin(original, f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
        assertFalse(f.root.resolve(".droide").exists())
    }

    @Test fun healthyHandoffRetainsActualPreparedPartialStageAndEvidence() = fixture { f ->
        val retained = prepare(f)
        val before = snapshot(f.base)
        val recovered = journal(f).recovery(explorer)
        assertNull(recovered.blockingProblem)
        val record = recovered.records.single()
        assertEquals(retained.id, record.intent.id)
        assertEquals(ProjectCopyJournal.Status.PREPARED, record.status)
        assertEquals(ProjectCopyEvidence.StagePresence.PRESENT, record.stagePresence)
        assertFalse(record.active)
        assertArrayEquals(payload, retained.stage.readBytes())
        assertEquals(before, snapshot(f.base))
    }

    @Test fun agentAndExplorerScopesCannotHideKnownJournalLoss() = fixture { f ->
        prepare(f)
        moveToControl(f, journalDirectory(f.root), "old-journal")
        val before = snapshot(f.base)
        listOf(explorer, agent, ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-b")).forEach {
            assertRejectedRecovery(journal(f), it)
        }
        assertRejectedBegin(journal(f), f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
    }

    @Test fun differentCanonicalRootWithSameEpochDoesNotInheritAnotherRootsIdentity() = fixture { f ->
        prepare(f)
        moveToControl(f, journalDirectory(f.root), "old-journal")
        val other = f.base.resolve("other-project").apply { check(mkdir()) }
        val first = ProjectCopyJournal(other, processEpoch = f.epoch)
        val beforeRead = snapshot(f.base)
        assertNull(first.recovery(explorer).blockingProblem)
        assertEquals(beforeRead, snapshot(f.base))
        first.begin(owner("other"), "other.bin", planStage = true).close()
        val afterBegin = snapshot(f.base)
        val recovered = ProjectCopyJournal(other, processEpoch = f.epoch).recovery(explorer)
        assertNull(recovered.blockingProblem)
        assertEquals(ProjectCopyJournal.Status.PREPARED, recovered.records.single().status)
        assertEquals(afterBegin, snapshot(f.base))
        assertFalse(other.resolve("other.bin").exists())
    }

    @Test fun untouchedProjectRemainsLazyAcrossSameEpochInstances() = fixture { f ->
        val before = snapshot(f.base)
        repeat(3) { assertNull(journal(f).recovery(explorer).blockingProblem) }
        assertEquals(before, snapshot(f.base))
        assertFalse(f.root.resolve(".droide").exists())
        journal(f).begin(owner("first"), "first.bin", planStage = true).close()
        val recovery = journal(f).recovery(explorer)
        assertNull(recovery.blockingProblem)
        assertEquals(ProjectCopyJournal.Status.PREPARED, recovery.records.single().status)
        assertFalse(f.root.resolve("first.bin").exists())
    }

    @Test fun ordinaryTextSaveDoesNotRecreateOrClearMissingCopyJournal() = fixture { f ->
        val retained = prepare(f)
        moveToControl(f, journalDirectory(f.root), "old-journal")
        val beforeControl = snapshot(f.control)
        val stageBefore = snapshot(f.root).getValue(retained.stage.name)
        val files = FileRepository(f.root, newFilePublisher = NewProjectFilePublisher.native())
        runBlocking { files.writeTextIfUnchanged("source.kt", "fun main() = Unit\n", DiskFileVersion.fromText(null)) }
        assertEquals("fun main() = Unit\n", runBlocking { files.readText("source.kt") })
        assertFalse(journalDirectory(f.root).exists())
        assertEquals(beforeControl, snapshot(f.control))
        assertEquals(stageBefore, snapshot(f.root).getValue(retained.stage.name))
        assertArrayEquals(payload, retained.stage.readBytes())
    }

    @Test fun failedRootForceDoesNotEraseObservedIdentityAtHandoff() = fixture { f ->
        val interrupted = IOException("root directory force interrupted")
        var forced = 0
        val durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) = ProjectCopyJournal.Durability.platform.forceFile(channel)
            override fun forceDirectory(directory: File) {
                if (directory == f.root) { forced++; throw interrupted }
                ProjectCopyJournal.Durability.platform.forceDirectory(directory)
            }
        }
        val first = ProjectCopyJournal(f.root, durability = durability, processEpoch = f.epoch)
        assertSame(interrupted, runCatching { first.begin(owner("interrupted"), "first.bin", planStage = true) }.exceptionOrNull())
        assertEquals(1, forced)
        assertTrue(journalDirectory(f.root).isDirectory)
        assertTrue(journalDirectory(f.root).listFiles()!!.isEmpty())
        moveToControl(f, journalDirectory(f.root), "unforced-journal")
        val before = snapshot(f.base)
        val next = journal(f)
        assertRejectedRecovery(next, explorer)
        assertRejectedBegin(next, f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
    }

    @Test fun sameObjectContinuesToRejectLostJournalWithoutMutation() = fixture { f ->
        val first = journal(f)
        prepare(f, first)
        moveToControl(f, journalDirectory(f.root), "old-journal")
        val before = snapshot(f.base)
        assertRejectedRecovery(first, explorer)
        assertRejectedBegin(first, f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
    }

    @Test fun sameObjectContinuesToRejectReplacedMetadataWithoutMutation() = fixture { f ->
        val first = journal(f)
        prepare(f, first)
        moveToControl(f, f.root.resolve(".droide"), "old-metadata")
        check(f.root.resolve(".droide").mkdir())
        val before = snapshot(f.base)
        assertRejectedRecovery(first, explorer)
        assertRejectedBegin(first, f.root, "new.bin")
        assertEquals(before, snapshot(f.base))
    }

    private fun assertRejectedRecovery(journal: ProjectCopyJournal, scope: ProjectCopyJournal.Scope) {
        val attempt = runCatching { journal.recovery(scope) }
        val problem = attempt.exceptionOrNull()?.message ?: attempt.getOrNull()?.blockingProblem
        assertNotNull("Known storage identity loss must be reported, never empty successful recovery", problem)
        assertTrue("Expected clear journal/metadata recovery problem: $problem",
            problem!!.contains("journal", ignoreCase = true) || problem.contains("metadata", ignoreCase = true))
    }

    private fun assertRejectedBegin(journal: ProjectCopyJournal, root: File, destination: String) {
        var sourceOpens = 0
        var sourceReads = 0
        var publications = 0
        val native = NewProjectFilePublisher.native()
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher { stage, target ->
            publications++
            native.publish(stage, target)
        })
        val failure = runCatching {
            journal.begin(owner(destination), destination, planStage = true).use { lease ->
                sourceOpens++
                val input = object : ByteArrayInputStream(payload) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        sourceReads++
                        return super.read(bytes, offset, length)
                    }
                }
                runBlocking { files.writeStream(destination, input, requireAbsent = true, publicationObserver = lease) }
            }
        }.exceptionOrNull()
        assertNotNull("Storage loss must block before the provider opens", failure)
        assertTrue("Expected explicit journal/metadata failure: $failure", failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("journal", ignoreCase = true) ||
            failure.message.orEmpty().contains("metadata", ignoreCase = true))
        assertEquals(0, sourceOpens)
        assertEquals(0, sourceReads)
        assertEquals(0, publications)
        assertFalse(root.resolve(destination).exists())
    }

    private data class Prepared(val id: String, val stage: File)

    private fun prepare(f: Fixture, journal: ProjectCopyJournal = journal(f)): Prepared {
        val lease = journal.begin(owner("prepared"), "retained.bin", planStage = true)
        val stageFile = f.root.resolve(requireNotNull(lease.plannedStagePath))
        lease.use {
            OwnedStreamStage.create(f.root.resolve("retained.bin"), stageFile.name).use { stage ->
                assertEquals(stageFile, stage.file)
                stage.output.write(payload)
                stage.output.flush()
                stage.force(StreamPublicationDurability.platform)
            }
        }
        assertArrayEquals(payload, stageFile.readBytes())
        return Prepared(lease.intent.id, stageFile)
    }

    private fun observeMetadataOnly(f: Fixture): ProjectCopyJournal {
        check(f.root.resolve(".droide").mkdir())
        f.root.resolve(".droide/user-note.txt").writeText("preexisting metadata work")
        val before = snapshot(f.base)
        val first = journal(f)
        assertNull(first.recovery(explorer).blockingProblem)
        assertEquals(before, snapshot(f.base))
        assertFalse(journalDirectory(f.root).exists())
        return first
    }

    private fun moveToControl(f: Fixture, from: File, name: String) =
        Files.move(from.toPath(), f.control.resolve(name).toPath())

    private fun journal(f: Fixture) = ProjectCopyJournal(f.root, processEpoch = f.epoch)
    private fun journalDirectory(root: File) = root.resolve(".droide/copy-recovery")
    private fun owner(id: String) = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
        "run-$id", ProjectCopyJournal.explorerSourceId("content://identity-handoff/$id"))

    private data class Node(val key: String, val directory: Boolean, val bytes: Long,
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

    private data class Fixture(val base: File, val root: File, val control: File, val epoch: String)

    private fun fixture(block: (Fixture) -> Unit) {
        val base = Files.createTempDirectory("copy-journal-handoff-").toFile()
        val root = base.resolve("project").apply { check(mkdir()) }
        val control = base.resolve("control").apply { check(mkdir()) }
        root.resolve("original.txt").writeText("original project work")
        try { block(Fixture(base, root, control, UUID.randomUUID().toString())) }
        finally { PathSecurity.deleteTreeNoFollow(base) }
    }
}

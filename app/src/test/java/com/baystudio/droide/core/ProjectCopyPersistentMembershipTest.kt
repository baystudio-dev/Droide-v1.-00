package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectCopyPersistentMembershipTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)
    private fun owner(run: String = "first") = ProjectCopyJournal.Owner(
        ProjectCopyJournal.Kind.EXPLORER_IMPORT, null, run, ProjectCopyJournal.explorerSourceId("content://fixture/document"))
    private fun reopened(root: File) = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
    private fun prepare(root: File): String = reopened(root).begin(owner(), "asset.bin", planStage = true).use {
        File(root, requireNotNull(it.plannedStagePath)).writeText("unpublished user bytes")
        it.intent.id
    }
    private fun files(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to it.readBytes().toList()
    }
    private fun assertBlocked(journal: ProjectCopyJournal) {
        val recovery = runCatching { journal.recovery(scope) }
        assertTrue("Missing admitted history must remain visible after restart",
            recovery.isFailure || recovery.getOrThrow().blockingProblem != null)
        assertNotNull("New copy must not bypass missing history",
            runCatching { journal.begin(owner("retry"), "next.bin", planStage = true) }.exceptionOrNull())
    }

    @Test fun closedRecordLossIsDetectedByFreshProcessEpoch() {
        val root = temp.newFolder("project")
        val id = prepare(root)
        Files.move(File(root, ".droide/copy-recovery/$id").toPath(), File(temp.root, "retained-record").toPath())
        val before = files(temp.root)
        assertBlocked(reopened(root))
        assertEquals(before, files(temp.root))
    }

    @Test fun wholeJournalLossCannotBootstrapEmptyHistoryAfterRestart() {
        val root = temp.newFolder("project")
        prepare(root)
        Files.move(File(root, ".droide/copy-recovery").toPath(), File(temp.root, "retained-journal").toPath())
        val before = files(temp.root)
        assertBlocked(reopened(root))
        assertFalse(File(root, ".droide/copy-recovery").exists())
        assertEquals(before, files(temp.root))
    }

    @Test fun emptyReplacementJournalIsNotAdoptedAfterRestart() {
        val root = temp.newFolder("project")
        prepare(root)
        val directory = File(root, ".droide/copy-recovery")
        Files.move(directory.toPath(), File(temp.root, "retained-journal").toPath())
        check(directory.mkdir())
        val before = files(temp.root)
        assertBlocked(reopened(root))
        assertEquals(before, files(temp.root))
    }

    @Test fun healthyRestartPreservesPreparedRecordAndPayloadWithoutWrites() {
        val root = temp.newFolder("project")
        val id = prepare(root)
        val before = files(temp.root)
        val result = reopened(root).recovery(scope)
        assertNull(result.blockingProblem)
        assertEquals(id, result.records.single().intent.id)
        assertEquals(ProjectCopyJournal.Status.PREPARED, result.records.single().status)
        assertEquals(before, files(temp.root))
    }

    @Test fun emptyProjectReadDoesNotInitializeRecoveryStorage() {
        val root = temp.newFolder("project")
        repeat(3) { assertNull(reopened(root).recovery(scope).blockingProblem) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun repository(root: File, home: File) = FileRepository(root).apply { configureCopyRecoveryHome(home) }

    @Test fun applicationAuthorityLivesOutsideWorkspaceAndIsSharedByRepositoryOwners() {
        val root = temp.newFolder("project")
        val home = File(temp.root, "host-recovery")
        val id = repository(root, home).copyRecoveryJournal().begin(owner(), "asset.bin", planStage = true).use { it.intent.id }
        assertFalse(File(root, ".droide/copy-recovery-membership.json").exists())
        assertTrue(home.walkTopDown().any { it.name == "$id.json" })
        val before = files(temp.root)
        val recovery = repository(root, home).copyRecoveryJournal().recovery(scope)
        assertNull(recovery.blockingProblem)
        assertEquals(id, recovery.records.single().intent.id)
        assertEquals(before, files(temp.root))
    }

    @Test fun workspaceMetadataLossLeavesTheApplicationWitnessIntact() {
        val root = temp.newFolder("project")
        val home = File(temp.root, "host-recovery")
        repository(root, home).copyRecoveryJournal().begin(owner(), "asset.bin", planStage = true).close()
        Files.move(File(root, ".droide").toPath(), File(temp.root, "retained-metadata").toPath())
        val before = files(temp.root)
        assertBlocked(repository(root, home).copyRecoveryJournal())
        assertEquals(before, files(temp.root))
        assertFalse(File(root, ".droide").exists())
    }

    @Test fun recreatedWorkspaceAtTheSamePathCannotStartANewHistory() {
        val root = temp.newFolder("project")
        val home = File(temp.root, "host-recovery")
        repository(root, home).copyRecoveryJournal().begin(owner(), "asset.bin", planStage = true).use {
            File(root, requireNotNull(it.plannedStagePath)).writeText("unpublished user bytes")
        }
        Files.move(root.toPath(), File(temp.root, "retained-project").toPath())
        check(root.mkdir())
        val before = files(temp.root)
        assertBlocked(repository(root, home).copyRecoveryJournal())
        assertEquals(before, files(temp.root))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun missingAuthorityDoesNotReenrollAnAdmittedCopy() {
        val root = temp.newFolder("project")
        val home = File(temp.root, "host-recovery")
        repository(root, home).copyRecoveryJournal().begin(owner(), "asset.bin", planStage = true).close()
        Files.move(home.toPath(), File(temp.root, "retained-authority").toPath())
        val before = files(temp.root)
        assertBlocked(repository(root, home).copyRecoveryJournal())
        assertEquals(before, files(temp.root))
        assertFalse(home.exists())
    }

    @Test fun authorityIsLazyAndCannotBeRedirectedAfterConfiguration() {
        val root = temp.newFolder("project")
        val home = File(temp.root, "host-recovery")
        val files = repository(root, home)
        assertNull(files.copyRecoveryJournal().recovery(scope).blockingProblem)
        assertFalse(home.exists())
        assertNotNull(runCatching { files.configureCopyRecoveryHome(File(temp.root, "elsewhere")) }.exceptionOrNull())
        assertNotNull(runCatching { repository(root, File(root, "unsafe")) }.exceptionOrNull())
    }

    @Test fun corruptMembershipBlocksEvenAnUnrelatedNewCopy() {
        val root = temp.newFolder("project")
        val id = prepare(root)
        File(root, ".droide/copy-recovery/$id/membership.json").writeText("{broken")
        val next = reopened(root)
        assertNotNull(next.recovery(scope).blockingProblem)
        val other = owner("other").copy(sourceId = ProjectCopyJournal.explorerSourceId("content://fixture/unrelated"))
        val before = files(temp.root)
        assertNotNull(runCatching { next.begin(other, "elsewhere.bin", planStage = true) }.exceptionOrNull())
        assertEquals(before, files(temp.root))
    }

    private fun legacyRecord(root: File): String {
        val id = UUID.randomUUID().toString()
        val path = ".droide-tmp-$id"
        val intent = ProjectCopyJournal.Intent(version = 2, id = id, canonicalRoot = root.canonicalPath,
            rootIdentity = ProjectCopyEvidence.root(root).second, owner = owner("legacy"), destination = "old.bin",
            createdAt = 1L, plannedStagePath = path)
        val directory = File(root, ".droide/copy-recovery/$id").apply { check(mkdirs()) }
        File(directory, "intent.json").writeText(Json { encodeDefaults = true }.encodeToString(intent))
        File(root, path).writeText("legacy unpublished bytes")
        return id
    }

    @Test fun legacyMembershipIsPersistedBeforeTheNextCopyWithoutRewritingItsIntent() {
        val root = temp.newFolder("project")
        val home = File(temp.root, "host-recovery")
        val id = legacyRecord(root)
        val intent = File(root, ".droide/copy-recovery/$id/intent.json")
        val before = intent.readBytes()
        repository(root, home).copyRecoveryJournal().begin(owner("next"), "next.bin", planStage = true).close()
        assertTrue("Legacy history needs an independent witness", home.walkTopDown().any { it.name == "$id.json" })
        assertArrayEquals(before, intent.readBytes())
        assertEquals("legacy unpublished bytes", File(root, ".droide-tmp-$id").readText())
    }

    @Test fun migratedLegacyRecordLossBlocksLaterCopies() {
        val root = temp.newFolder("project")
        val home = File(temp.root, "host-recovery")
        val id = legacyRecord(root)
        repository(root, home).copyRecoveryJournal().begin(owner("next"), "next.bin", planStage = true).close()
        Files.move(File(root, ".droide/copy-recovery/$id").toPath(), File(temp.root, "retained-legacy-record").toPath())
        val before = files(temp.root)
        assertBlocked(repository(root, home).copyRecoveryJournal())
        assertEquals(before, files(temp.root))
    }

    private fun checkpoint(afterDirectory: (File) -> Unit) = object : ProjectCopyJournal.Durability {
        override fun forceFile(channel: FileChannel) = ProjectCopyJournal.Durability.platform.forceFile(channel)
        override fun forceDirectory(directory: File) {
            ProjectCopyJournal.Durability.platform.forceDirectory(directory)
            afterDirectory(directory)
        }
    }

    @Test fun interruptedLegacyEnrollmentRetainsPayloadAndBlocksAnUnrelatedCopy() {
        val root = temp.newFolder("project")
        val id = legacyRecord(root)
        val intent = File(root, ".droide/copy-recovery/$id/intent.json").readBytes()
        val durability = checkpoint { directory ->
            if (directory.name == "copy-recovery-membership") throw IOException("injected durability failure")
        }
        assertNotNull(runCatching {
            ProjectCopyJournal(root, durability = durability).begin(owner("next"), "next.bin", planStage = true)
        }.exceptionOrNull())
        val before = files(temp.root)
        assertBlocked(reopened(root))
        assertEquals(before, files(temp.root))
        assertArrayEquals(intent, File(root, ".droide/copy-recovery/$id/intent.json").readBytes())
        assertEquals("legacy unpublished bytes", File(root, ".droide-tmp-$id").readText())
    }

    @Test fun replacementRecordCannotReceiveTheAdmittedIntent() {
        val root = temp.newFolder("project")
        val retained = File(temp.root, "retained-record")
        var replacement: File? = null
        val durability = checkpoint { directory ->
            if (directory.name == "copy-recovery-membership") {
                val record = File(root, ".droide/copy-recovery").listFiles()!!.single()
                Files.move(record.toPath(), retained.toPath())
                check(record.mkdir())
                File(record, "foreign.txt").writeText("external change")
                replacement = record
            }
        }
        assertNotNull(runCatching {
            ProjectCopyJournal(root, durability = durability).begin(owner(), "asset.bin", planStage = true)
        }.exceptionOrNull())
        assertEquals(listOf("foreign.txt"), checkNotNull(replacement).listFiles()!!.map { it.name })
        assertEquals("external change", File(replacement, "foreign.txt").readText())
        assertTrue(retained.isDirectory)
        val before = files(temp.root)
        assertBlocked(reopened(root))
        assertEquals(before, files(temp.root))
    }

    @Test fun sidecarReplacementCannotFollowASymlinkOrOverwriteItsTarget() {
        val root = temp.newFolder("project")
        val target = temp.newFile("user-file").apply { writeText("user bytes") }
        val durability = checkpoint { directory ->
            if (directory.name == "copy-recovery-membership") {
                val record = File(root, ".droide/copy-recovery").listFiles()!!.single()
                Files.createSymbolicLink(File(record, "membership.json").toPath(), target.toPath())
            }
        }
        assertNotNull(runCatching {
            ProjectCopyJournal(root, durability = durability).begin(owner(), "asset.bin", planStage = true)
        }.exceptionOrNull())
        assertEquals("user bytes", target.readText())
        val before = files(temp.root)
        assertBlocked(reopened(root))
        assertEquals(before, files(temp.root))
    }

    @Test fun failedFileSyncDoesNotAuthorizeAnIncompleteMembership() {
        val root = temp.newFolder("project")
        val id = legacyRecord(root)
        val durability = object : ProjectCopyJournal.Durability {
            private var calls = 0
            override fun forceFile(channel: FileChannel) {
                if (++calls == 2) throw IOException("injected ENOSPC on membership witness")
                ProjectCopyJournal.Durability.platform.forceFile(channel)
            }
            override fun forceDirectory(directory: File) = ProjectCopyJournal.Durability.platform.forceDirectory(directory)
        }
        assertNotNull(runCatching {
            ProjectCopyJournal(root, durability = durability).begin(owner("next"), "next.bin", planStage = true)
        }.exceptionOrNull())
        val before = files(temp.root)
        assertBlocked(reopened(root))
        assertEquals(before, files(temp.root))
        assertEquals("legacy unpublished bytes", File(root, ".droide-tmp-$id").readText())
    }

    @Test fun failedEnrollmentSyncCanRetryOnlyWhileTheLegacyEvidenceRemainsIntact() {
        val root = temp.newFolder("project")
        val id = legacyRecord(root)
        val durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) { throw IOException("injected enrollment sync failure") }
            override fun forceDirectory(directory: File) = ProjectCopyJournal.Durability.platform.forceDirectory(directory)
        }
        assertNotNull(runCatching {
            ProjectCopyJournal(root, durability = durability).begin(owner("next"), "next.bin", planStage = true)
        }.exceptionOrNull())
        val before = files(temp.root)
        assertNull(reopened(root).recovery(scope).blockingProblem)
        assertEquals(before, files(temp.root))
        reopened(root).begin(owner("retry"), "next.bin", planStage = true).close()
        assertTrue(File(root, ".droide/copy-recovery-membership/$id.json").isFile)
        assertEquals("legacy unpublished bytes", File(root, ".droide-tmp-$id").readText())
    }
}

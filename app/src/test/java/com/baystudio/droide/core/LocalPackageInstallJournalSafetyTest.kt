package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalPackageInstallJournalSafetyTest {
    private val kind = LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE

    @Test fun legalIdentitiesAndLongNamesHaveDistinctBoundedJournals() {
        val first = LocalPackageInstallJournal.paths("/app", "runtime.node-22", "3", kind)
        val second = LocalPackageInstallJournal.paths("/app", "runtime.node", "22-3", kind)
        assertNotEquals(first.stage, second.stage)
        assertNotEquals(first.previous, second.previous)
        assertEquals(first.legacyPrevious, second.legacyPrevious)
        val long = LocalPackageInstallJournal.paths("/app", "f".repeat(120), "v".repeat(120), kind)
        assertTrue(long.stage.name.length <= 255)
        assertTrue(long.previous.name.length <= 255)
    }

    @Test fun dotPathSegmentsAreRejected() {
        for (bad in listOf(".", "..")) {
            assertTrue(runCatching { LocalPackageInstallJournal.paths("/app", bad, "1", kind) }.isFailure)
            assertTrue(runCatching { LocalPackageInstallJournal.paths("/app", "runtime.node", bad, kind) }.isFailure)
        }
    }

    @Test fun overlappingPathsFailBeforeDeletingPayload() = fixture { root ->
        val final = tree(File(root, "final"), "keep")
        val stage = tree(File(final, "stage"), "staged")
        assertTrue(runCatching {
            LocalPackageInstallJournal.recover(stage, final, File(root, "previous"), LocalPackageInstallJournal.Receipt(null))
        }.isFailure)
        assertEquals("staged", File(stage, "payload").readText())
        assertEquals("keep", File(final, "payload").readText())
    }

    @Test fun wrongGenerationBackupPreservesBothCopies() = fixture { root ->
        val final = tree(File(root, "final"), "new")
        LocalPackageInstallJournal.prepare(final)
        val previous = tree(File(root, "previous"), "unrelated")
        LocalPackageInstallJournal.prepare(previous)
        assertTrue(runCatching {
            LocalPackageInstallJournal.recover(File(root, "stage"), final, previous,
                LocalPackageInstallJournal.Receipt(java.util.UUID.randomUUID().toString()))
        }.isFailure)
        assertEquals("new", File(final, "payload").readText())
        assertEquals("unrelated", File(previous, "payload").readText())
    }

    @Test fun matchingLegacyBackupRestoresInterruptedInstallAfterUpgrade() = fixture { root ->
        val paths = LocalPackageInstallJournal.paths(root.path, "runtime.node", "22.1", kind)
        val old = tree(requireNotNull(paths.legacyPrevious), "receipted")
        val id = LocalPackageInstallJournal.prepare(old)
        tree(paths.final, "uncommitted")
        LocalPackageInstallJournal.prepare(paths.final)
        val oldStage = tree(requireNotNull(paths.legacyStage), "partial-other-identity")
        LocalPackageInstallJournal.recover(paths, LocalPackageInstallJournal.Receipt(id))
        assertEquals("receipted", File(paths.final, "payload").readText())
        assertTrue(LocalPackageInstallJournal.matchesActivation(paths.final, id))
        assertFalse(old.exists())
        assertFalse(paths.previous.exists())
        assertEquals("partial-other-identity", File(oldStage, "payload").readText())
    }

    @Test fun ambiguousLegacyBackupCannotStealAnotherIdentity() = fixture { root ->
        val first = LocalPackageInstallJournal.paths(root.path, "runtime.node-22", "3", kind)
        val second = LocalPackageInstallJournal.paths(root.path, "runtime.node", "22-3", kind)
        val old = tree(requireNotNull(first.legacyPrevious), "other-package")
        LocalPackageInstallJournal.prepare(old)
        tree(second.final, "new-package")
        assertTrue(runCatching { LocalPackageInstallJournal.recover(second, null) }.isFailure)
        assertEquals("other-package", File(old, "payload").readText())
        assertEquals("new-package", File(second.final, "payload").readText())
    }

    @Test fun committedFinalSurvivesUnattributableLegacyBackup() = fixture { root ->
        val paths = LocalPackageInstallJournal.paths(root.path, "runtime.node", "22.1", kind)
        tree(paths.final, "committed")
        val id = LocalPackageInstallJournal.prepare(paths.final)
        val old = tree(requireNotNull(paths.legacyPrevious), "unattributed")
        LocalPackageInstallJournal.recover(paths, LocalPackageInstallJournal.Receipt(id))
        assertEquals("committed", File(paths.final, "payload").readText())
        assertEquals("unattributed", File(old, "payload").readText())
    }

    @Test fun rollbackWithMissingBackupDoesNotDeleteOnlyRemainingCopy() = fixture { root ->
        val stage = tree(File(root, "stage"), "new")
        val final = tree(File(root, "final"), "old")
        val previous = File(root, "previous")
        val activation = LocalPackageInstallJournal.activate(stage, final, previous)
        assertTrue(PathSecurity.deleteTreeNoFollow(previous))
        assertTrue(runCatching { LocalPackageInstallJournal.rollback(final, previous, activation) }.isFailure)
        assertEquals("new", File(final, "payload").readText())
    }

    @Test fun invalidReceiptIsRejectedBeforeStagingDeletion() = fixture { root ->
        val stage = tree(File(root, "stage"), "partial")
        assertTrue(runCatching {
            LocalPackageInstallJournal.recover(stage, File(root, "final"), File(root, "previous"), LocalPackageInstallJournal.Receipt("bad"))
        }.isFailure)
        assertEquals("partial", File(stage, "payload").readText())
    }

    @Test fun symlinkedStagingParentCannotPurgeOutsideTree() = fixture { root ->
        val outside = Files.createTempDirectory("droide-stage-outside-").toFile()
        try {
            val paths = LocalPackageInstallJournal.paths(root.path, "runtime.node", "22", kind)
            check(paths.stage.parentFile.mkdirs())
            check(paths.stage.parentFile.delete())
            Files.createSymbolicLink(paths.stage.parentFile.toPath(), outside.toPath())
            val outsidePayload = tree(File(outside, paths.stage.name), "outside-data")
            assertTrue(runCatching { LocalPackageInstallJournal.recover(paths, null) }.isFailure)
            assertEquals("outside-data", File(outsidePayload, "payload").readText())
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    private fun tree(file: File, value: String): File = file.apply { mkdirs(); File(this, "payload").writeText(value) }
    private inline fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-install-safety-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

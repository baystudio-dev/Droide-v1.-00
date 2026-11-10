package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalPackageRemovalJournalTest {
    @Test fun collidingOldIdentitiesHaveDistinctRemovalJournals() {
        val first = record("runtime.node-22", "3", "/app/packages/first")
        val second = record("runtime.node", "22-3", "/app/packages/second")
        assertNotEquals(LocalPackageRemovalJournal.entryName(first), LocalPackageRemovalJournal.entryName(second))
    }

    @Test fun durableSiblingIntentLeavesSealedTreeUnchangedAndRestoresAfterProcessDeath() = fixture { root ->
        val source = tree(File(root, "source"), "receipted")
        val id = LocalPackageInstallJournal.prepare(source)
        val receipt = record("runtime.node", "22", source.path, id)
        val trash = File(root, LocalPackageRemovalJournal.entryName(receipt))
        val before = source.listFiles()!!.map { it.name }.sorted()
        LocalPackageRemovalJournal.prepare(trash, receipt)
        assertEquals(before, source.listFiles()!!.map { it.name }.sorted())
        assertTrue(source.renameTo(trash))
        val intent = requireNotNull(LocalPackageRemovalJournal.read(trash))
        assertTrue(LocalPackageRemovalJournal.belongsTo(intent, receipt))
        assertTrue(LocalPackageRemovalJournal.matchesIntent(intent, trash))
        LocalPackageRemovalJournal.restore(source, trash, File(root, "conflicts"))
        assertEquals("receipted", File(source, "payload").readText())
        assertFalse(trash.exists())
        assertFalse(LocalPackageRemovalJournal.metadata(trash).exists())
    }

    @Test fun brokenConflictingSourceIsPreservedWhileReceiptedBackupIsRestored() = fixture { root ->
        val source = tree(File(root, "source"), "good-backup")
        val id = LocalPackageInstallJournal.prepare(source)
        val receipt = record("runtime.node", "22", source.path, id)
        val trash = File(root, LocalPackageRemovalJournal.entryName(receipt))
        LocalPackageRemovalJournal.prepare(trash, receipt)
        assertTrue(source.renameTo(trash))
        tree(source, "conflicting-copy")
        val conflicts = File(root, "conflicts")
        LocalPackageRemovalJournal.restore(source, trash, conflicts)
        assertEquals("good-backup", File(source, "payload").readText())
        assertEquals("conflicting-copy", File(conflicts.listFiles()!!.single(), "payload").readText())
    }

    @Test fun removalIntentCannotDeleteWrongGenerationBackup() = fixture { root ->
        val source = tree(File(root, "source"), "good")
        val id = LocalPackageInstallJournal.prepare(source)
        val receipt = record("runtime.node", "22", source.path, id)
        val trash = File(root, LocalPackageRemovalJournal.entryName(receipt))
        LocalPackageRemovalJournal.prepare(trash, receipt)
        tree(trash, "other-generation")
        LocalPackageInstallJournal.prepare(trash)
        assertTrue(runCatching { LocalPackageRemovalJournal.discard(trash) }.isFailure)
        assertEquals("good", File(source, "payload").readText())
        assertEquals("other-generation", File(trash, "payload").readText())
        assertTrue(LocalPackageRemovalJournal.metadata(trash).exists())
    }

    @Test fun intentIdentityTamperingIsRejectedWithoutTouchingPayload() = fixture { root ->
        val receipt = record("runtime.node", "22", File(root, "source").path)
        val trash = tree(File(root, LocalPackageRemovalJournal.entryName(receipt)), "keep")
        LocalPackageRemovalJournal.prepare(trash, receipt)
        val metadata = LocalPackageRemovalJournal.metadata(trash)
        metadata.writeText(metadata.readText().replace("runtime.node", "runtime.python"))
        assertTrue(runCatching { LocalPackageRemovalJournal.discard(trash) }.isFailure)
        assertEquals("keep", File(trash, "payload").readText())
    }

    @Test fun symlinkIntentCannotCauseOutsideDeletion() = fixture { root ->
        val receipt = record("runtime.node", "22", File(root, "source").path)
        val trash = tree(File(root, LocalPackageRemovalJournal.entryName(receipt)), "keep")
        val outside = Files.createTempFile("droide-removal-outside-", ".json").toFile()
        try {
            outside.writeText("outside")
            Files.createSymbolicLink(LocalPackageRemovalJournal.metadata(trash).toPath(), outside.toPath())
            assertTrue(runCatching { LocalPackageRemovalJournal.discard(trash) }.isFailure)
            assertEquals("outside", outside.readText())
            assertEquals("keep", File(trash, "payload").readText())
        } finally { outside.delete() }
    }

    @Test fun committedRemovalReclaimsTreeAndItsOwnIntent() = fixture { root ->
        val source = tree(File(root, "source"), "removed")
        val id = LocalPackageInstallJournal.prepare(source)
        val receipt = record("runtime.node", "22", source.path, id)
        val trash = File(root, LocalPackageRemovalJournal.entryName(receipt))
        LocalPackageRemovalJournal.prepare(trash, receipt)
        assertTrue(source.renameTo(trash))
        assertTrue(LocalPackageRemovalJournal.discard(trash))
        assertFalse(source.exists())
        assertFalse(trash.exists())
        assertFalse(LocalPackageRemovalJournal.metadata(trash).exists())
    }

    private fun record(family: String, version: String, root: String, activationId: String? = null) =
        ManagedPackageRecord(family, version, "LOCAL_LINUX_ARM64", root, 1,
            metadata = activationId?.let { mapOf(LocalManagedPackageMetadata.ACTIVATION_ID_KEY to it) } ?: emptyMap())
    private fun tree(file: File, payload: String): File = file.apply { mkdirs(); File(this, "payload").writeText(payload) }
    private inline fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-removal-journal-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

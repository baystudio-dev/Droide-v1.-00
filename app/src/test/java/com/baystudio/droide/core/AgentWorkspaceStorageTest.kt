package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspaceStorageTest {
    private data class Fixture(val project: File, val legacy: File, val persistent: File) {
        fun storage(hook: (String) -> Unit = {}) = AgentWorkspaceStorage(project, legacy, persistent, hook)
        fun metadata(directory: File, suffix: String) = File(directory.parentFile, ".${directory.name}-$suffix")
        val journal get() = metadata(persistent, "storage-migration")
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("agent-storage-migration-").toFile()
        try {
            val project = File(root, "projects/main").apply { mkdirs() }
            val legacy = File(root, "cache/agent-workspaces/identity").apply { parentFile.mkdirs() }
            val persistent = File(root, "files/agent-workspaces/identity")
            Fixture(project, legacy, persistent).block()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun migrationMovesCompleteWorkingTreeAndActualMirrorMetadataWithoutLosingPendingWork() = fixture {
        File(project, "main.kt").writeText("original source")
        val mirror = LocalAgentWorkspaceMirror(project, legacy)
        mirror.refresh()
        val originalBase = mirror.baseline()
        val baseline = metadata(legacy, "sync-baseline.tsv")
        val baselineBytes = baseline.readBytes()
        File(legacy, "main.kt").writeText("pending CLI edit")
        File(legacy, "kucing.glb").writeBytes(byteArrayOf(0, -1, 71))
        File(legacy, "node_modules/cache.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(-1, 0)) }
        File(legacy, "empty-directory").mkdir()
        mirror.preserveAttachmentConflict("kucing.glb")
        val conflicts = metadata(legacy, "attachment-conflicts.txt").readBytes()

        storage().prepare()

        assertFalse(legacy.exists())
        assertEquals("pending CLI edit", File(persistent, "main.kt").readText())
        assertArrayEquals(byteArrayOf(0, -1, 71), File(persistent, "kucing.glb").readBytes())
        assertArrayEquals(byteArrayOf(-1, 0), File(persistent, "node_modules/cache.bin").readBytes())
        assertTrue(File(persistent, "empty-directory").isDirectory)
        assertArrayEquals(baselineBytes, metadata(persistent, "sync-baseline.tsv").readBytes())
        assertArrayEquals(conflicts, metadata(persistent, "attachment-conflicts.txt").readBytes())
        assertFalse(baseline.exists())
        assertFalse(metadata(legacy, "attachment-conflicts.txt").exists())
        val recreated = LocalAgentWorkspaceMirror(project, persistent)
        assertEquals(originalBase, recreated.baseline())
        recreated.refresh()
        assertEquals("pending CLI edit", File(persistent, "main.kt").readText())
        assertArrayEquals(byteArrayOf(0, -1, 71), File(persistent, "kucing.glb").readBytes())
        assertEquals("original source", File(project, "main.kt").readText())
    }

    @Test fun existingLegacyAndPersistentTreesAreBothPreservedOnCollision() = fixture {
        legacy.mkdirs(); persistent.mkdirs()
        File(legacy, "asset.bin").writeBytes(byteArrayOf(1, 0, -1))
        File(persistent, "asset.bin").writeBytes(byteArrayOf(2, 0, -1))
        try { storage().prepare(); fail("Two workspaces were silently merged") }
        catch (_: IllegalStateException) { }
        assertArrayEquals(byteArrayOf(1, 0, -1), File(legacy, "asset.bin").readBytes())
        assertArrayEquals(byteArrayOf(2, 0, -1), File(persistent, "asset.bin").readBytes())
    }

    @Test fun targetMetadataCollisionIsDetectedBeforeMovingTheLegacyTree() = fixture {
        legacy.mkdirs(); persistent.parentFile.mkdirs()
        File(legacy, "asset.bin").writeBytes(byteArrayOf(1, 0, -1))
        val old = metadata(legacy, "sync-baseline.tsv").apply { writeText("old trusted baseline") }
        val existing = metadata(persistent, "sync-baseline.tsv").apply { writeText("other trusted baseline") }
        try { storage().prepare(); fail("Migration attached unrelated target metadata to this workspace") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(1, 0, -1), File(legacy, "asset.bin").readBytes())
        assertFalse(persistent.exists())
        assertEquals("old trusted baseline", old.readText())
        assertEquals("other trusted baseline", existing.readText())
    }

    @Test fun unjournaledPartialMigrationCannotAdoptDestinationMetadataOnRetry() = fixture {
        File(project, "main.kt").writeText("baseline")
        val mirror = LocalAgentWorkspaceMirror(project, legacy)
        mirror.refresh()
        val baselineBytes = metadata(legacy, "sync-baseline.tsv").readBytes()
        File(legacy, "pending.glb").writeBytes(byteArrayOf(0, -1, 23))
        mirror.preserveAttachmentConflict("pending.glb")
        persistent.parentFile.mkdirs()
        Files.move(legacy.toPath(), persistent.toPath())
        Files.move(metadata(legacy, "attachment-conflicts.txt").toPath(),
            metadata(persistent, "attachment-conflicts.txt").toPath())
        assertTrue(metadata(legacy, "sync-baseline.tsv").isFile)
        val failure = runCatching { storage().prepare() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("without a migration intent"))
        assertArrayEquals(baselineBytes, metadata(legacy, "sync-baseline.tsv").readBytes())
        assertFalse(metadata(persistent, "sync-baseline.tsv").exists())
        assertEquals("pending.glb\n", metadata(persistent, "attachment-conflicts.txt").readText())
        assertArrayEquals(byteArrayOf(0, -1, 23), File(persistent, "pending.glb").readBytes())
        assertFalse(journal.exists())
    }

    private class Interrupted : Error("injected storage interruption")

    @Test fun interruptionBeforeIntentPublicationLeavesAllUserDataInPlaceAndCanRetryFreshMigration() = fixture {
        legacy.mkdir()
        File(legacy, "pending").writeText("unpublished")
        metadata(legacy, "sync-baseline.tsv").writeText("original metadata")
        val failure = runCatching { storage { if (it == "intent-staged") throw Interrupted() }.prepare() }.exceptionOrNull()
        assertTrue(failure is Interrupted)
        assertEquals("unpublished", File(legacy, "pending").readText())
        assertFalse(persistent.exists())
        assertFalse(journal.exists())
        val retainedStage = persistent.parentFile.listFiles()!!.single { it.name.startsWith(".cli-storage-") }
        val preparedBytes = File(retainedStage, "intent").readBytes()
        storage().prepare()
        assertEquals("unpublished", File(persistent, "pending").readText())
        assertEquals("original metadata", metadata(persistent, "sync-baseline.tsv").readText())
        assertArrayEquals(preparedBytes, File(retainedStage, "intent").readBytes())
    }

    @Test fun concurrentDestinationCreationAfterIntentCannotReplaceEitherWorkingTree() = fixture {
        legacy.mkdir()
        File(legacy, "pending").writeText("unpublished CLI")
        val failure = runCatching { storage { step -> if (step == "before-entry:workspace") {
            persistent.mkdir(); File(persistent, "foreign").writeText("concurrent destination")
        } }.prepare() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("errno=17"))
        assertEquals("unpublished CLI", File(legacy, "pending").readText())
        assertEquals("concurrent destination", File(persistent, "foreign").readText())
        assertTrue(File(journal, "intent").isFile)
        assertFalse(File(journal, "complete").exists())
        assertNotNull(runCatching { storage().prepare() }.exceptionOrNull())
        assertEquals("unpublished CLI", File(legacy, "pending").readText())
    }

    @Test fun concurrentSidecarCreationIsNotOverwrittenAfterTheWorkingDirectoryMove() = fixture {
        legacy.mkdir()
        File(legacy, "pending").writeText("unpublished CLI")
        val old = metadata(legacy, "sync-baseline.tsv").apply { writeText("legacy metadata") }
        val target = metadata(persistent, "sync-baseline.tsv")
        val failure = runCatching { storage { step -> if (step == "before-entry:sync-baseline.tsv") {
            target.writeText("concurrent metadata")
        } }.prepare() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("errno=17"))
        assertEquals("legacy metadata", old.readText())
        assertEquals("concurrent metadata", target.readText())
        assertEquals("unpublished CLI", File(persistent, "pending").readText())
        assertNotNull(runCatching { storage().prepare() }.exceptionOrNull())
        assertEquals("legacy metadata", old.readText())
        assertEquals("concurrent metadata", target.readText())
    }

    @Test fun interruptedMigrationPreservesIntentAndEveryVersionWithoutUnprovenReplay() = fixture {
        legacy.mkdir()
        File(legacy, "pending").writeText("unpublished CLI")
        metadata(legacy, "attachment-conflicts.txt").writeText("pending\n")
        val baseline = metadata(legacy, "sync-baseline.tsv").apply { writeText("preserved baseline") }
        val failure = runCatching { storage { if (it == "entry-moved:workspace") throw Interrupted() }.prepare() }.exceptionOrNull()
        assertTrue(failure is Interrupted)
        assertFalse(legacy.exists())
        assertEquals("unpublished CLI", File(persistent, "pending").readText())
        assertEquals("preserved baseline", baseline.readText())
        val next = runCatching { storage().prepare() }.exceptionOrNull()
        assertTrue(next is IllegalStateException)
        assertTrue(next!!.message.orEmpty().contains("was interrupted"))
        assertEquals("pending\n", metadata(legacy, "attachment-conflicts.txt").readText())
        assertFalse(metadata(persistent, "sync-baseline.tsv").exists())
        assertTrue(File(journal, "intent").isFile)
    }

    @Test fun completedReceiptSurvivesCacheRemovalAndDoesNotRejectLegitimateLaterMetadataUpdates() = fixture {
        File(project, "file").writeText("source")
        LocalAgentWorkspaceMirror(project, legacy).refresh()
        storage().prepare()
        File(persistent, "file").writeText("pending CLI edit")
        File(project, "new-file").writeText("later source")
        LocalAgentWorkspaceMirror(project, persistent).refresh()
        assertTrue(PathSecurity.deleteTreeNoFollow(legacy.parentFile.parentFile))
        val intent = File(journal, "intent").readBytes()
        val completed = File(journal, "complete").readBytes()
        storage().prepare()
        assertEquals("pending CLI edit", File(persistent, "file").readText())
        assertEquals("later source", File(persistent, "new-file").readText())
        assertArrayEquals(intent, File(journal, "intent").readBytes())
        assertArrayEquals(completed, File(journal, "complete").readBytes())
        assertFalse(legacy.parentFile.exists())
    }

    @Test fun completedMigrationCannotRecreateALostOrSubstitutedWorkingDirectory() = fixture {
        legacy.mkdir(); File(legacy, "pending").writeText("unpublished")
        storage().prepare()
        val retained = File(persistent.parentFile, "retained-working")
        Files.move(persistent.toPath(), retained.toPath())
        persistent.mkdir(); File(persistent, "foreign").writeText("replacement")
        val failure = runCatching { storage().prepare() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("disappeared or changed"))
        assertEquals("unpublished", File(retained, "pending").readText())
        assertEquals("replacement", File(persistent, "foreign").readText())
    }

    @Test fun corruptMigrationReceiptStopsWithoutChangingEitherProjectOrWorkingCopy() = fixture {
        legacy.mkdir(); File(legacy, "pending").writeText("unpublished")
        storage().prepare()
        File(journal, "complete").writeText("truncated")
        assertNotNull(runCatching { storage().prepare() }.exceptionOrNull())
        assertEquals("truncated", File(journal, "complete").readText())
        assertEquals("unpublished", File(persistent, "pending").readText())
        assertTrue(project.listFiles()!!.isEmpty())
    }

    @Test fun symlinkInAnExistingStorageParentCannotRedirectMigration() = fixture {
        legacy.mkdir(); File(legacy, "pending").writeText("unpublished")
        val outside = File(project.parentFile.parentFile, "outside").apply { mkdir() }
        persistent.parentFile.parentFile.parentFile.mkdirs()
        Files.createSymbolicLink(persistent.parentFile.parentFile.toPath(), outside.toPath())
        val failure = runCatching { storage().prepare() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("unpublished", File(legacy, "pending").readText())
        assertTrue(outside.listFiles()!!.isEmpty())
        assertFalse(journal.exists())
    }

    @Test fun fullAuthorityDirectoryMovesWithTheWorkingCopyAndRetainsItsInodes() = fixture {
        legacy.mkdir(); File(legacy, "pending").writeText("unpublished")
        val authority = metadata(legacy, "sync-authority").apply { mkdir() }
        val old = File(authority, "retained/inode").apply { parentFile.mkdir(); writeText("late CLI bytes") }
        val identity = Files.readAttributes(old.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        storage().prepare()
        val moved = File(metadata(persistent, "sync-authority"), "retained/inode")
        assertEquals("late CLI bytes", moved.readText())
        assertEquals(identity, Files.readAttributes(moved.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertFalse(authority.exists())
        assertEquals("unpublished", File(persistent, "pending").readText())
    }

    @Test fun symlinkMetadataIsRejectedBeforeMovingTheLegacyDirectory() = fixture {
        legacy.mkdirs()
        File(legacy, "pending.glb").writeBytes(byteArrayOf(0, -1, 29))
        val outside = File(project.parentFile.parentFile, "outside-metadata").apply { writeText("outside") }
        Files.createSymbolicLink(metadata(legacy, "sync-baseline.tsv").toPath(), outside.toPath())
        try { storage().prepare(); fail("Symlink metadata was adopted") }
        catch (_: IllegalArgumentException) { }
        assertEquals("outside", outside.readText())
        assertArrayEquals(byteArrayOf(0, -1, 29), File(legacy, "pending.glb").readBytes())
        assertFalse(persistent.exists())
    }

    @Test fun symlinkWorkspaceIsRejectedWithoutMovingOrChangingTheTarget() = fixture {
        val outside = File(project.parentFile.parentFile, "outside").apply { mkdirs() }
        File(outside, "asset.bin").writeBytes(byteArrayOf(0, -1, 42))
        Files.createSymbolicLink(legacy.toPath(), outside.toPath())
        try { storage().prepare(); fail("Legacy workspace symlink was admitted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(Files.isSymbolicLink(legacy.toPath()))
        assertArrayEquals(byteArrayOf(0, -1, 42), File(outside, "asset.bin").readBytes())
        assertFalse(persistent.exists())
    }

    @Test fun persistenceCannotMoveWorkingFilesIntoTheProjectTree() = fixture {
        legacy.mkdirs()
        File(legacy, "pending.glb").writeBytes(byteArrayOf(0, -1, 7))
        val insideProject = File(project, "agent-workspace")
        try { AgentWorkspaceStorage(project, legacy, insideProject).prepare(); fail("Storage admitted a project descendant") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(0, -1, 7), File(legacy, "pending.glb").readBytes())
        assertFalse(insideProject.exists())
    }

    @Test fun concurrentAndRepeatedPreparationMigrateOnlyOnceAndRetainLaterCliEdits() = fixture {
        legacy.mkdirs()
        File(legacy, "pending.glb").writeBytes(byteArrayOf(0, -1, 7))
        val first = storage()
        coroutineScope {
            (0 until 12).map { index ->
                async(Dispatchers.Default) { (if (index % 2 == 0) first else storage()).prepare() }
            }.awaitAll()
        }
        assertFalse(legacy.exists())
        assertArrayEquals(byteArrayOf(0, -1, 7), File(persistent, "pending.glb").readBytes())
        File(persistent, "pending.glb").writeBytes(byteArrayOf(0, -1, 8))
        first.prepare()
        storage().prepare()
        assertArrayEquals(byteArrayOf(0, -1, 8), File(persistent, "pending.glb").readBytes())
        assertFalse(File(project, "pending.glb").exists())
    }

    @Test fun cancelledPreparationWaitingForWorkspaceLockPreservesTheLegacyCopy() = fixture {
        legacy.mkdirs()
        File(legacy, "pending.glb").writeBytes(byteArrayOf(0, -1, 9))
        val storage = storage()
        val lock = WorkspaceFileMutationLocks.forRoot(project)
        lock.lock()
        try {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val request = launch(Dispatchers.IO) { entered.complete(Unit); storage.prepare() }
                entered.await()
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
            }
            assertArrayEquals(byteArrayOf(0, -1, 9), File(legacy, "pending.glb").readBytes())
            assertFalse(persistent.exists())
        } finally { lock.unlock() }
        storage.prepare()
        assertArrayEquals(byteArrayOf(0, -1, 9), File(persistent, "pending.glb").readBytes())
    }
}

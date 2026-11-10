package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkspaceMaintenanceTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("droide-startup").toFile()
        try { block(dir) } finally { PathSecurity.deleteTreeNoFollow(dir) }
    }

    @Test fun unregisteredWorkspaceIsPreservedWithoutDeletionEvidence() = fixture { root ->
        val owned = File(root, "p-owned").apply { mkdirs() }
        File(owned, "source.kt").writeText("keep")
        val orphan = File(root, "p-orphan").apply { mkdirs() }
        File(orphan, "large.src").writeText("unfinished")
        val pending = ProjectWorkspaceMaintenance.prepare(root, mapOf("p-owned" to owned))
        assertTrue(pending.isEmpty())
        assertEquals("unfinished", File(orphan, "large.src").readText())
        assertEquals("keep", File(owned, "source.kt").readText())
        assertTrue(ProjectWorkspaceMaintenance.collect(root, pending).isEmpty())
        assertTrue(orphan.exists())
        assertFalse(File(root, ".delete-p-orphan").exists())
    }

    @Test fun registeredInterruptedDeletionIsRestored() = fixture { app ->
        val root = File(app, "projects").apply { check(mkdir()) }
        val target = File(root, "p-owned").apply { check(mkdir()) }
        File(target, "source").writeText("recover")
        val originalKey = Files.readAttributes(target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        val project = DroideProject("p-owned", "Owned", target.absolutePath)
        val store = FileProjectStore(app)
        store.save(listOf(project))
        val interrupted = ProjectWorkspaceRelocation(app)
        interrupted.withLock {
            val intent = interrupted.prepare(project, listOf(project), emptyList(), store)
            interrupted.move(intent)
            interrupted.abandon()
        }
        assertFalse(target.exists())
        val restarted = ProjectWorkspaceRelocation(app)
        restarted.withLock { restarted.recover(store) }
        assertTrue(ProjectWorkspaceMaintenance.prepare(root, mapOf(project.id to target)).isEmpty())
        assertEquals("recover", File(target, "source").readText())
        assertEquals(originalKey, Files.readAttributes(target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertFalse(File(root, ".delete-p-owned").exists())
    }

    @Test fun registeredLegacyDeletionNameDoesNotInventRestorationAuthority() = fixture { root ->
        val backup = File(root, ".delete-p-owned").apply { mkdirs() }
        File(backup, "source").writeText("recover")
        val target = File(root, "p-owned")
        assertTrue(runCatching { ProjectWorkspaceMaintenance.prepare(root, mapOf("p-owned" to target)) }.exceptionOrNull() is IllegalStateException)
        assertFalse(target.exists())
        assertEquals("recover", File(backup, "source").readText())
    }

    @Test fun registeredDuplicateBackupIsPreserved() = fixture { root ->
        val target = File(root, "p-owned").apply { mkdirs() }
        val backup = File(root, ".delete-p-owned").apply { mkdirs() }
        File(backup, "source").writeText("backup")
        assertTrue(runCatching { ProjectWorkspaceMaintenance.prepare(root, mapOf("p-owned" to target)) }.exceptionOrNull() is IllegalStateException)
        assertTrue(target.isDirectory)
        assertEquals("backup", File(backup, "source").readText())
    }

    @Test fun unregisteredDetachedBackupIsPreservedForRecovery() = fixture { root ->
        val backup = File(root, ".delete-p-unlisted").apply { mkdirs() }
        File(backup, "source").writeText("backup")
        val pending = ProjectWorkspaceMaintenance.prepare(root, emptyMap())
        assertTrue(pending.isEmpty())
        ProjectWorkspaceMaintenance.collect(root, pending)
        assertEquals("backup", File(backup, "source").readText())
    }

    @Test fun orphanSymlinkNeverDeletesItsExternalTarget() = fixture { root ->
        val outside = Files.createTempDirectory("droide-outside").toFile()
        try {
            File(outside, "source").writeText("external")
            Files.createSymbolicLink(File(root, "p-link").toPath(), outside.toPath())
            assertTrue(ProjectWorkspaceMaintenance.prepare(root, emptyMap()).isEmpty())
            assertEquals("external", File(outside, "source").readText())
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun detachedNestedSymlinkIsPreservedWithoutDeletionCustody() = fixture { root ->
        val outside = Files.createTempDirectory("droide-outside").toFile()
        try {
            File(outside, "source").writeText("external")
            val orphan = File(root, ".delete-p-orphan").apply { mkdirs() }
            Files.createSymbolicLink(File(orphan, "link").toPath(), outside.toPath())
            try { ProjectWorkspaceMaintenance.collect(root, listOf(orphan)); fail("unproven cleanup accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(orphan.isDirectory)
            assertEquals(outside.toPath(), Files.readSymbolicLink(File(orphan, "link").toPath()))
            assertEquals("external", File(outside, "source").readText())
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun mixedBatchRejectsBeforeRemovingAnyEarlierDetachedTree() = fixture { root ->
        val detached = File(root, ".delete-p-retained").apply { mkdirs() }
        val live = File(root, "p-other-workspace").apply { mkdirs() }
        File(detached, "draft").writeText("unpublished work")
        File(live, "source").writeText("other workspace")
        try { ProjectWorkspaceMaintenance.collect(root, listOf(detached, live)); fail("mixed cleanup accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals("unpublished work", File(detached, "draft").readText())
        assertEquals("other workspace", File(live, "source").readText())
    }

    @Test fun replacedDetachedNameDoesNotGrantDeletionAuthority() = fixture { root ->
        val detached = File(root, ".delete-p-retained").apply { mkdirs() }
        File(detached, "draft").writeText("original work")
        val held = File(root, "observer-held-original")
        Files.move(detached.toPath(), held.toPath())
        check(detached.mkdir())
        File(detached, "draft").writeText("replacement work")
        try { ProjectWorkspaceMaintenance.collect(root, listOf(detached)); fail("replacement cleanup accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals("original work", File(held, "draft").readText())
        assertEquals("replacement work", File(detached, "draft").readText())
    }

    @Test fun missingAndOutsideTargetsAreNotSilentlyReportedAsCollected() = fixture { root ->
        val absent = File(root, ".delete-p-missing")
        val outside = File(root.parentFile, ".delete-outside-not-created")
        for (target in listOf(absent, outside)) {
            try { ProjectWorkspaceMaintenance.collect(root, listOf(target)); fail("unproven cleanup accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(absent.exists())
    }

    @Test fun emptyCollectionDoesNotRequireAnExistingStorageContainer() = fixture { root ->
        val missingContainer = File(root, "not-created")
        assertTrue(ProjectWorkspaceMaintenance.collect(missingContainer, emptyList()).isEmpty())
        assertFalse(missingContainer.exists())
    }

    @Test fun cancelledCollectionPreservesAllTargets() = fixture { root ->
        val detached = File(root, ".delete-p-retained").apply { mkdirs() }
        File(detached, "draft").writeText("keep")
        for (batch in listOf(emptyList(), listOf(detached))) {
            val job = Job().apply { cancel() }
            try { withContext(job) { ProjectWorkspaceMaintenance.collect(root, batch) }; fail("cancel ignored") }
            catch (_: CancellationException) { }
        }
        assertEquals("keep", File(detached, "draft").readText())
    }

    @Test fun cancellationAndLiveRootsCannotEnterCollector() = fixture { root ->
        val target = File(root, "p-owned").apply { mkdirs() }
        File(target, "source").writeText("keep")
        try { ProjectWorkspaceMaintenance.collect(root, listOf(target)); fail("live root accepted") }
        catch (_: IllegalArgumentException) { }
        val job = Job().apply { cancel() }
        try { withContext(job) { ProjectWorkspaceMaintenance.prepare(root, emptyMap()) }; fail("cancel ignored") }
        catch (_: CancellationException) { }
        assertEquals("keep", File(target, "source").readText())
    }
}

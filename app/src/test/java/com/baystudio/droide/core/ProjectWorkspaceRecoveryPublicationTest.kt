package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkspaceRecoveryPublicationTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-recovery-publication").toFile()
        val root = File(app, "projects").apply { check(mkdir()) }
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private suspend fun stageTracked(root: File, target: File): File {
        val project = DroideProject("owned", "Owned", target.absolutePath)
        val store = FileProjectStore(root.parentFile)
        store.save(listOf(project))
        val coordinator = ProjectWorkspaceRelocation(root.parentFile)
        coordinator.withLock {
            val intent = coordinator.prepare(project, listOf(project), emptyList(), store)
            coordinator.move(intent)
            coordinator.abandon() // Simulate loss of the in-memory owner before catalog publication.
        }
        return File(root, ".delete-owned")
    }

    private suspend fun recoverTracked(root: File) {
        val coordinator = ProjectWorkspaceRelocation(root.parentFile)
        coordinator.withLock { coordinator.recover(FileProjectStore(root.parentFile)) }
    }

    private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    @Test fun restoredTreePreservesDirectoryAndUnpublishedFileIdentity() = fixture { root ->
        val target = File(root, "custom-root").apply { check(mkdir()) }
        val source = File(target, "draft.kt").apply { writeText("unpublished edit") }
        val treeKey = key(target)
        val fileKey = key(source)
        val retained = stageTracked(root, target)
        recoverTracked(root)
        assertEquals(treeKey, key(target))
        assertEquals(fileKey, key(File(target, source.name)))
        assertEquals("unpublished edit", File(target, source.name).readText())
        assertFalse(retained.exists())
    }

    @Test fun successfulRecoveryDoesNotFollowNestedLinksOrChangeTheirTarget() = fixture { root ->
        val outside = File(root, "external").apply { check(mkdir()) }
        val source = File(outside, "source").apply { writeText("external work") }
        val sourceKey = key(source)
        val target = File(root, "🧩 project").apply { check(mkdir()) }
        Files.createSymbolicLink(File(target, "dependency").toPath(), outside.toPath())
        val retained = stageTracked(root, target)
        recoverTracked(root)
        assertFalse(retained.exists())
        assertTrue(Files.isSymbolicLink(File(target, "dependency").toPath()))
        assertEquals(outside.toPath(), Files.readSymbolicLink(File(target, "dependency").toPath()))
        assertEquals(sourceKey, key(source))
        assertEquals("external work", source.readText())
    }

    @Test fun existingEmptyDestinationAndRetainedWorkRemainDistinct() = fixture { root ->
        val retained = File(root, ".delete-owned").apply { check(mkdir()) }
        File(retained, "source").writeText("unpublished")
        val target = File(root, "work").apply { check(mkdir()) }
        val retainedKey = key(retained)
        val targetKey = key(target)
        assertTrue(runCatching { ProjectWorkspaceMaintenance.prepare(root, mapOf("owned" to target)) }.exceptionOrNull() is IllegalStateException)
        assertEquals(retainedKey, key(retained))
        assertEquals(targetKey, key(target))
        assertEquals("unpublished", File(retained, "source").readText())
        assertTrue(checkNotNull(target.list()).isEmpty())
    }

    @Test fun repeatedStartupDoesNotRewriteRecoveredSourceOrDirectory() = fixture { root ->
        val target = File(root, "work").apply { check(mkdir()) }
        File(target, "source").writeText("keep exact source")
        val retained = stageTracked(root, target)
        recoverTracked(root)
        assertFalse(retained.exists())
        val source = File(target, "source")
        val treeKey = key(target)
        val sourceKey = key(source)
        val treeTime = Files.getLastModifiedTime(target.toPath(), NOFOLLOW_LINKS)
        val sourceTime = Files.getLastModifiedTime(source.toPath(), NOFOLLOW_LINKS)
        repeat(3) { recoverTracked(root) }
        assertEquals(treeKey, key(target))
        assertEquals(sourceKey, key(source))
        assertEquals(treeTime, Files.getLastModifiedTime(target.toPath(), NOFOLLOW_LINKS))
        assertEquals(sourceTime, Files.getLastModifiedTime(source.toPath(), NOFOLLOW_LINKS))
        assertEquals("keep exact source", source.readText())
    }
}

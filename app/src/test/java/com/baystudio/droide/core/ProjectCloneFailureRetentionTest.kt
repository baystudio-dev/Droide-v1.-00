package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProjectCloneFailureRetentionTest {
    private class Fixture(val app: File, val store: FileProjectStore, val manager: ProjectManager) {
        val projectsRoot = File(app, "projects")
        val defaultRoot = File(projectsRoot, "default")
        val defaultSource = File(defaultRoot, "source.txt").apply { writeText("existing Default work\n") }
        val defaultRootKey = key(defaultRoot)
        val defaultSourceKey = key(defaultSource)
        val activeFile = File(app, ".droide/active_project.txt")
        val activeBytes = activeFile.readBytes()

        suspend fun assertCatalogAndDefault(expected: List<DroideProject>) {
            assertEquals(expected, manager.projects.value)
            assertEquals(expected, store.load())
            assertEquals("default", manager.activeId.value)
            assertEquals("default", store.loadActiveId())
            assertArrayEquals(activeBytes, activeFile.readBytes())
            assertEquals(defaultRootKey, key(defaultRoot))
            assertEquals(defaultSourceKey, key(defaultSource))
            assertEquals("existing Default work\n", defaultSource.readText())
        }
    }

    private class PendingClone(
        val root: File,
        private val failure: Throwable,
        private val release: CompletableDeferred<Unit>,
        private val clone: Deferred<Result<DroideProject>>,
    ) {
        val rootKey = key(root)
        val source = File(root, "nested/cli-work.bin").apply {
            check(parentFile.mkdirs())
            writeBytes(payload)
        }
        val sourceKey = key(source)
        val sourceModified = Files.getLastModifiedTime(source.toPath(), NOFOLLOW_LINKS)

        suspend fun failAndVerifyOriginalError() {
            release.complete(Unit)
            val result = withTimeout(5_000) { clone.await() }
            assertTrue(result.isFailure)
            assertSame(failure, result.exceptionOrNull())
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }

        fun assertSourceRetained() {
            assertTrue("Clone failure must retain the allocated workspace", root.isDirectory)
            assertEquals(rootKey, key(root))
            assertEquals(sourceKey, key(source))
            assertEquals(sourceModified, Files.getLastModifiedTime(source.toPath(), NOFOLLOW_LINKS))
            assertArrayEquals(payload, source.readBytes())
            assertFalse(Files.exists(File(root.parentFile, ".delete-${root.name}").toPath(), NOFOLLOW_LINKS))
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking(Dispatchers.IO) {
        val app = Files.createTempDirectory("droide-clone-failure-retention-").toFile()
        try {
            val store = FileProjectStore(app)
            val manager = ProjectManager(app, store).apply { init() }
            withTimeout(15_000) { Fixture(app, store, manager).block() }
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private suspend fun Fixture.withPendingClone(
        failure: Throwable = IOException("credential resolution stopped before clone transport"),
        block: suspend (PendingClone) -> Unit,
    ) = coroutineScope {
        val originalChildren = projectsRoot.listFiles().orEmpty().map { it.name }.toSet()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        manager.bindGitHubCredentialResolver {
            entered.complete(Unit)
            release.await()
            throw failure
        }
        val clone = async { runCatching { manager.cloneFromGit("Pending clone", "https://github.com/droide-fixture/never-contacted.git") } }
        var primaryFailure: Throwable? = null
        try {
            withTimeout(5_000) { entered.await() }
            val root = projectsRoot.listFiles().orEmpty().filter { it.name !in originalChildren }.single()
            assertTrue(root.name.matches(Regex("p-[0-9a-f-]{36}")))
            assertEquals(projectsRoot.canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.isDirectory)
            block(PendingClone(root, failure, release, clone))
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            release.complete(Unit)
            try {
                withContext(NonCancellable) { withTimeout(5_000) { clone.cancelAndJoin() } }
            } catch (cleanup: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanup
                if (original !== cleanup) original.addSuppressed(cleanup)
            }
        }
    }

    @Test fun concurrentRegistrationKeepsItsFilesWhenTheOriginalCloneFails() = fixture {
        withPendingClone { pending ->
            val adopted = manager.add("Adopted CLI work", rootPath = pending.root.path)
            assertNotEquals(pending.root.name, adopted.id)
            val expected = manager.projects.value.toList()
            val catalogBytes = File(app, ".droide/projects.json").readBytes()

            pending.failAndVerifyOriginalError()

            pending.assertSourceRetained()
            assertCatalogAndDefault(expected)
            assertArrayEquals(catalogBytes, File(app, ".droide/projects.json").readBytes())
            val restarted = ProjectManager(app).apply { init() }
            assertEquals(expected, restarted.projects.value)
            restarted.switch(adopted.id)
            assertEquals(adopted, restarted.active())
            assertEquals(pending.root.canonicalPath, restarted.activeRoot().canonicalPath)
            pending.assertSourceRetained()
        }
    }

    @Test fun removedRegistrationKeepsItsFilesWhenTheOriginalCloneFails() = fixture {
        withPendingClone { pending ->
            val adopted = manager.add("Stored CLI work", rootPath = pending.root.path)
            val stored = checkNotNull(manager.removeWorkspace(adopted.id))
            assertTrue(stored.workspaceRemoved)
            val expected = manager.projects.value.toList()
            val catalogBytes = File(app, ".droide/projects.json").readBytes()

            pending.failAndVerifyOriginalError()

            pending.assertSourceRetained()
            assertCatalogAndDefault(expected)
            assertArrayEquals(catalogBytes, File(app, ".droide/projects.json").readBytes())
            val restarted = ProjectManager(app).apply { init() }
            assertEquals(expected, restarted.projects.value)
            assertEquals(listOf(stored), restarted.storedProjects.value)
            assertEquals("default", restarted.activeId.value)
            pending.assertSourceRetained()
        }
    }

    @Test fun removeThenRestoreKeepsItsFilesWhenTheOlderCloneFails() = fixture {
        withPendingClone { pending ->
            val adopted = manager.add("Restored CLI work", rootPath = pending.root.path)
            manager.removeWorkspace(adopted.id)
            assertEquals(adopted, manager.restoreWorkspace(adopted.id))
            val expected = manager.projects.value.toList()
            val catalogBytes = File(app, ".droide/projects.json").readBytes()

            pending.failAndVerifyOriginalError()

            pending.assertSourceRetained()
            assertCatalogAndDefault(expected)
            assertTrue(manager.storedProjects.value.isEmpty())
            assertArrayEquals(catalogBytes, File(app, ".droide/projects.json").readBytes())
            val restarted = ProjectManager(app).apply { init() }
            assertEquals(expected, restarted.projects.value)
            assertTrue(restarted.storedProjects.value.isEmpty())
            assertEquals(adopted, restarted.projects.value.single { it.id == adopted.id })
            pending.assertSourceRetained()
        }
    }

    @Test fun unregisteredPartialCloneSurvivesFailureAndRestartWithoutFalseRegistration() = fixture {
        val expected = manager.projects.value.toList()
        val catalogBytes = File(app, ".droide/projects.json").readBytes()
        withPendingClone { pending ->
            pending.failAndVerifyOriginalError()

            pending.assertSourceRetained()
            assertCatalogAndDefault(expected)
            assertArrayEquals(catalogBytes, File(app, ".droide/projects.json").readBytes())
            val restarted = ProjectManager(app).apply { init() }
            assertEquals(expected, restarted.projects.value)
            assertTrue(restarted.projects.value.none { File(it.rootPath).canonicalFile == pending.root.canonicalFile })
            assertTrue(restarted.collectStartupGarbage().isEmpty())
            assertEquals(expected, store.load())
            assertArrayEquals(catalogBytes, File(app, ".droide/projects.json").readBytes())
            pending.assertSourceRetained()
        }
    }

    @Test fun cancellationRetainsTheAdoptedWorkspaceAndOriginalCancellation() = fixture {
        val cancelled = CancellationException("clone credential resolution cancelled before transport")
        withPendingClone(cancelled) { pending ->
            val adopted = manager.add("Cancelled clone CLI work", rootPath = pending.root.path)
            val expected = manager.projects.value.toList()

            pending.failAndVerifyOriginalError()

            pending.assertSourceRetained()
            assertCatalogAndDefault(expected)
            assertFalse(manager.selectionIsUncertain)
            val restarted = ProjectManager(app).apply { init() }
            assertEquals(expected, restarted.projects.value)
            assertEquals(adopted, restarted.projects.value.single { it.id == adopted.id })
            pending.assertSourceRetained()
        }
    }

    companion object {
        private val payload = ByteArray(4_096) { (it % 251).toByte() }
        private fun key(file: File) =
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    }
}

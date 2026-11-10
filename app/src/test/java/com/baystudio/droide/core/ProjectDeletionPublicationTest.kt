package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectDeletionPublicationTest {
    // Isolate application failure ownership from coroutine diagnostic stacktrace copying.
    @OptIn(ExperimentalCoroutinesApi::class)
    private class FixtureFatal(message: String) : AssertionError(message), CopyableThrowable<FixtureFatal> {
        override fun createCopy(): FixtureFatal? = null
    }

    private class Store(private val delegate: ProjectStore) : ProjectStore by delegate {
        var failure: Throwable? = null
        var beforeFailure: () -> Unit = {}
        override suspend fun save(all: List<DroideProject>) {
            val refused = failure
            if (refused == null) delegate.save(all) else {
                failure = null
                beforeFailure()
                throw refused
            }
        }
    }

    private class Fixture(val app: File, val store: Store, val manager: ProjectManager, val project: DroideProject) {
        val root = File(project.rootPath)
        val recovery = File(root.parentFile, ".delete-${project.id}")
        val source = File(root, "unpublished.kt")
        val catalog = File(app, ".droide/projects.json")
        val catalogBytes = catalog.readBytes()
        val records = manager.projects.value.toList()
        val active = manager.activeId.value
        fun assertRegistryPreserved() {
            assertEquals(records, manager.projects.value)
            assertEquals(active, manager.activeId.value)
            assertArrayEquals(catalogBytes, catalog.readBytes())
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-delete-publication-").toFile()
        try {
            val store = Store(FileProjectStore(app))
            val manager = ProjectManager(app, store).apply { init() }
            val project = manager.add("Retained")
            File(project.rootPath, "unpublished.kt").writeText("preserve CLI and editor work")
            block(Fixture(app, store, manager, project))
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    private fun causes(failure: Throwable?) = generateSequence(failure) { it.cause }.toList()

    @Test fun danglingRecoveryLinkCannotRedirectDeletionOutsideTheProjectContainer() = fixture { f ->
        val outside = File(f.app, "external-parent").apply { check(mkdir()) }
        val absent = File(outside, "must-not-create")
        Files.createSymbolicLink(f.recovery.toPath(), absent.toPath())
        val rootKey = key(f.root)
        val sourceKey = key(f.source)
        assertNotNull(runCatching { f.manager.deleteWorkspace(f.project.id) }.exceptionOrNull())
        assertEquals(rootKey, key(f.root))
        assertEquals(sourceKey, key(f.source))
        assertEquals("preserve CLI and editor work", f.source.readText())
        assertEquals(absent.toPath(), Files.readSymbolicLink(f.recovery.toPath()))
        assertFalse(absent.exists())
        f.assertRegistryPreserved()
    }

    @Test fun existingEmptyRecoveryDirectoryCannotBeReplacedOrCollected() = fixture { f ->
        check(f.recovery.mkdir())
        val recoveryKey = key(f.recovery)
        val rootKey = key(f.root)
        assertNotNull(runCatching { f.manager.deleteWorkspace(f.project.id) }.exceptionOrNull())
        assertEquals(recoveryKey, key(f.recovery))
        assertEquals(rootKey, key(f.root))
        assertEquals("preserve CLI and editor work", f.source.readText())
        f.assertRegistryPreserved()
    }

    @Test fun verifiedNoncommitRestoresExactOriginalTreeWithoutChangingRegistry() = fixture { f ->
        val rootKey = key(f.root)
        val sourceKey = key(f.source)
        val refused = IOException("catalog write refused")
        f.store.failure = refused
        val failure = runCatching { f.manager.deleteWorkspace(f.project.id) }.exceptionOrNull()
        assertTrue(causes(failure).contains(refused))
        assertEquals(rootKey, key(f.root))
        assertEquals(sourceKey, key(f.source))
        assertEquals("preserve CLI and editor work", f.source.readText())
        assertFalse(f.recovery.exists())
        f.assertRegistryPreserved()
    }

    @Test fun rollbackConflictReportsBothPathsAndRetainsBothTreesAndCatalogCause() = fixture { f ->
        val originalKey = key(f.root)
        val refused = IOException("catalog write refused")
        f.store.failure = refused
        f.store.beforeFailure = { check(f.root.mkdir()); File(f.root, "external.kt").writeText("new user work") }
        val failure = runCatching { f.manager.deleteWorkspace(f.project.id) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(causes(failure).contains(refused))
        assertTrue(failure!!.message.orEmpty().contains(f.root.path))
        assertTrue(failure.message.orEmpty().contains(f.recovery.path))
        assertEquals(originalKey, key(f.recovery))
        assertEquals("preserve CLI and editor work", File(f.recovery, f.source.name).readText())
        assertEquals("new user work", File(f.root, "external.kt").readText())
        f.assertRegistryPreserved()
    }

    @Test fun missingRecoverySourceIsReportedWithoutInventingAnEmptyRestoration() = fixture { f ->
        val held = File(f.app, "observer-retained-tree")
        val originalKey = key(f.root)
        val refused = IOException("catalog write refused")
        f.store.failure = refused
        f.store.beforeFailure = { Files.move(f.recovery.toPath(), held.toPath()) }
        val failure = runCatching { f.manager.deleteWorkspace(f.project.id) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(causes(failure).contains(refused))
        assertTrue(failure!!.message.orEmpty().contains(f.root.path))
        assertTrue(failure.message.orEmpty().contains(f.recovery.path))
        assertFalse(f.root.exists())
        assertEquals(originalKey, key(held))
        assertEquals("preserve CLI and editor work", File(held, f.source.name).readText())
        f.assertRegistryPreserved()
    }

    @Test fun fatalCatalogFailureRemainsPrimaryWithTheRollbackFailureAttached() = fixture { f ->
        val refused = FixtureFatal("fatal catalog refusal")
        f.store.failure = refused
        f.store.beforeFailure = { check(f.root.mkdir()); File(f.root, "external.kt").writeText("new user work") }
        val failure = runCatching { f.manager.deleteWorkspace(f.project.id) }.exceptionOrNull()
        assertSame(refused, failure)
        assertTrue(refused.suppressed.any { it.message.orEmpty().contains(f.root.path) })
        assertEquals("preserve CLI and editor work", File(f.recovery, f.source.name).readText())
        assertEquals("new user work", File(f.root, "external.kt").readText())
        f.assertRegistryPreserved()
    }
}

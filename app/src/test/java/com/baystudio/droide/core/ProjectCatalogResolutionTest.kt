package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual ProjectManager commits, including durable-write / lost-return ambiguity. */
class ProjectCatalogResolutionTest {
    @get:Rule val temp = TemporaryFolder()

    private class Store(app: File? = null) : ProjectStore {
        private val disk = app?.let(::FileProjectStore)
        var records: List<DroideProject> = emptyList()
        var active: String? = null
        var nextSaveFailure: Throwable? = null
        var commitBeforeFailure = true
        var failResolutionRead = false
        var ambiguousResolution = false
        var resolving = false
        var afterFailedWrite: (suspend (List<DroideProject>) -> Unit)? = null
        var writes = 0
        var resolutionReads = 0

        override suspend fun load(): List<DroideProject> {
            if (resolving) {
                resolutionReads++
                if (failResolutionRead) throw IOException("injected read failure")
                if (ambiguousResolution) return emptyList()
            }
            return disk?.load() ?: records
        }

        override suspend fun save(all: List<DroideProject>) {
            writes++
            val failure = nextSaveFailure.also { nextSaveFailure = null }
            if (failure == null || commitBeforeFailure) {
                disk?.save(all)
                records = all.toList()
            }
            if (failure != null) {
                resolving = true
                afterFailedWrite?.invoke(all)
                throw failure
            }
        }

        override suspend fun loadActiveId() = active
        override suspend fun saveActiveId(id: String) { active = id }
    }

    private class Recents : ProjectRecentsStore {
        private var records: List<String>? = null
        override suspend fun load() = records
        override suspend fun save(ids: List<String>) { records = ids.toList() }
    }

    private suspend fun manager(store: Store) = ProjectManager(temp.root, store, Recents()).apply { init() }

    @Test fun committedRegistrationWithLostReturnIsPublishedAndNotLostByNextRegistration() = runBlocking {
        val store = Store()
        val manager = manager(store)
        store.nextSaveFailure = IOException("write committed but return lost")
        val first = manager.add("First")
        assertEquals(store.records, manager.projects.value)
        assertEquals(1, store.resolutionReads)
        val second = manager.add("Second")
        assertTrue(manager.projects.value.map { it.id }.containsAll(listOf(first.id, second.id)))
        assertEquals(store.records, manager.projects.value)
        assertTrue(File(first.rootPath).isDirectory)
    }

    @Test fun committedRegistrationCancellationPropagatesAfterPublishingOwnership() = runBlocking {
        val store = Store()
        val manager = manager(store)
        val cancelled = CancellationException("committed cancellation")
        store.nextSaveFailure = cancelled
        assertSame(cancelled, runCatching { manager.add("Cancelled return") }.exceptionOrNull())
        assertEquals(store.records, manager.projects.value)
        val retained = manager.projects.value.single { it.id != "default" }
        assertTrue(File(retained.rootPath).isDirectory)
        manager.add("Following")
        assertTrue(manager.projects.value.contains(retained))
    }

    @Test fun committedRegistrationFatalErrorPropagatesWithoutLosingOwnership() = runBlocking {
        val store = Store()
        val manager = manager(store)
        val fatal = AssertionError("injected fatal return")
        store.nextSaveFailure = fatal
        assertSame(fatal, runCatching { manager.add("Retained") }.exceptionOrNull())
        assertEquals(store.records, manager.projects.value)
        assertTrue(File(manager.projects.value.single { it.id != "default" }.rootPath).isDirectory)
    }

    @Test fun actuallyCancelledWriteStillResolvesAndPublishesTheCommittedRegistry() = runBlocking {
        val store = Store()
        val manager = manager(store)
        store.nextSaveFailure = CancellationException("write interrupted")
        store.afterFailedWrite = {
            currentCoroutineContext().cancel(CancellationException("cancel the actual IO owner"))
            currentCoroutineContext().ensureActive()
        }
        assertTrue(runCatching { manager.add("Retained on actual cancellation") }.exceptionOrNull() is CancellationException)
        assertEquals(store.records, manager.projects.value)
        assertEquals(1, store.resolutionReads)
        assertTrue(File(manager.projects.value.single { it.id != "default" }.rootPath).isDirectory)
        assertFalse(manager.selectionIsUncertain)
    }

    @Test fun rootReplacedWithAliasDuringResolutionIsNotBlessedAsACommit() = runBlocking {
        val store = Store()
        val manager = manager(store)
        val defaultRoot = File(checkNotNull(manager.active()).rootPath)
        File(defaultRoot, "source.txt").writeText("default source")
        store.nextSaveFailure = IOException("write committed but root changed")
        store.afterFailedWrite = { records ->
            val newRoot = File(records.single { it.id != "default" }.rootPath)
            assertTrue(newRoot.delete())
            Files.createSymbolicLink(newRoot.toPath(), defaultRoot.toPath())
        }
        assertTrue(runCatching { manager.add("Alias") }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertTrue(manager.selectionIsUncertain)
        assertEquals("default source", File(defaultRoot, "source.txt").readText())
        assertEquals(listOf("default"), manager.projects.value.map { it.id })
    }

    @Test fun verifiedNonCommitPreservesOldRegistryAndAllowsLaterRegistration() = runBlocking {
        val store = Store()
        val manager = manager(store)
        val before = manager.projects.value
        val failure = IOException("not committed")
        store.commitBeforeFailure = false
        store.nextSaveFailure = failure
        assertSame(failure, runCatching { manager.add("Failed") }.exceptionOrNull())
        assertEquals(before, store.records)
        assertEquals(before, manager.projects.value)
        assertFalse(manager.selectionIsUncertain)
        manager.add("Following")
        assertEquals(store.records, manager.projects.value)
    }

    @Test fun unreadableRegistrationOutcomeRetainsFilesAndBlocksFurtherMutations() = runBlocking {
        val store = Store()
        val manager = manager(store)
        val existing = manager.add("Existing")
        File(existing.rootPath, "keep.txt").writeText("keep")
        store.nextSaveFailure = IOException("lost return")
        store.failResolutionRead = true
        assertTrue(runCatching { manager.add("Unknown") }.exceptionOrNull() is ProjectCatalogUncertainException)
        val written = store.records.single { it.name == "Unknown" }
        assertTrue(File(written.rootPath).isDirectory)
        assertTrue(manager.selectionIsUncertain)
        val writes = store.writes
        assertTrue(runCatching { manager.add("Blocked") }.isFailure)
        assertTrue(runCatching { manager.switch(existing.id) }.isFailure)
        assertTrue(runCatching { manager.deleteWorkspace(existing.id) }.isFailure)
        assertTrue(runCatching { manager.init() }.isFailure)
        assertEquals(writes, store.writes)
        assertEquals("keep", File(existing.rootPath, "keep.txt").readText())
        assertEquals(1, store.resolutionReads)
    }

    @Test fun unexpectedCatalogOutcomeAlsoFailsClosed() = runBlocking {
        val store = Store()
        val manager = manager(store)
        store.nextSaveFailure = IOException("lost return")
        store.ambiguousResolution = true
        assertTrue(runCatching { manager.add("Unknown") }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertTrue(manager.selectionIsUncertain)
        assertTrue(File(store.records.single { it.name == "Unknown" }.rootPath).isDirectory)
    }

    @Test fun committedRemovalWithLostReturnDoesNotResurrectDeletedCatalogEntry() = runBlocking {
        val store = Store(temp.root)
        val manager = manager(store)
        val removed = manager.add("Removed")
        File(removed.rootPath, "source.txt").writeText("explicit deletion")
        val sourceKey = Files.readAttributes(File(removed.rootPath, "source.txt").toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        store.nextSaveFailure = IOException("committed removal")
        manager.deleteWorkspace(removed.id)
        assertEquals(store.records, manager.projects.value)
        assertFalse(manager.projects.value.any { it.id == removed.id })
        assertFalse(File(removed.rootPath).exists())
        val retained = File(temp.root, "projects/.delete-${removed.id}/source.txt")
        assertEquals("explicit deletion", retained.readText())
        assertEquals(sourceKey, Files.readAttributes(retained.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
    }

    @Test fun committedRemovalCancellationRetainsQuarantineWithoutRestoringOwnership() = runBlocking {
        val store = Store(temp.root)
        val manager = manager(store)
        val removed = manager.add("Removed")
        File(removed.rootPath, "source.txt").writeText("retained after interruption")
        val cancelled = CancellationException("committed removal")
        store.nextSaveFailure = cancelled
        assertSame(cancelled, runCatching { manager.deleteWorkspace(removed.id) }.exceptionOrNull())
        assertEquals(store.records, manager.projects.value)
        assertFalse(manager.projects.value.any { it.id == removed.id })
        assertFalse(File(removed.rootPath).exists())
        assertEquals("retained after interruption",
            File(temp.root, "projects/.delete-${removed.id}/source.txt").readText())
    }

    @Test fun verifiedUncommittedRemovalRestoresSourceAndRemainsUsable() = runBlocking {
        val store = Store(temp.root)
        val manager = manager(store)
        val retained = manager.add("Retained")
        File(retained.rootPath, "source.txt").writeText("keep")
        store.commitBeforeFailure = false
        val failure = IOException("removal not committed")
        store.afterFailedWrite = {
            assertFalse("The injected catalog failure must follow relocation", File(retained.rootPath).exists())
            assertEquals("keep", File(temp.root, "projects/.delete-${retained.id}/source.txt").readText())
        }
        store.nextSaveFailure = failure
        assertSame(failure, runCatching { manager.deleteWorkspace(retained.id) }.exceptionOrNull())
        assertTrue("Catalog refusal must occur after relocation", store.resolving)
        assertTrue(store.resolutionReads > 0)
        assertEquals("keep", File(retained.rootPath, "source.txt").readText())
        assertEquals(store.records, manager.projects.value)
        assertFalse(manager.selectionIsUncertain)
        manager.add("Following")
        assertTrue(manager.projects.value.contains(retained))
    }

    @Test fun ambiguousRemovalKeepsQuarantineAndBlocksFurtherSelection() = runBlocking {
        val store = Store(temp.root)
        val manager = manager(store)
        val retained = manager.add("Retained")
        File(retained.rootPath, "source.txt").writeText("uncertain but preserved")
        store.nextSaveFailure = IOException("lost return")
        store.failResolutionRead = true
        assertTrue(runCatching { manager.deleteWorkspace(retained.id) }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertTrue(manager.selectionIsUncertain)
        assertFalse(File(retained.rootPath).exists())
        assertEquals("uncertain but preserved",
            File(temp.root, "projects/.delete-${retained.id}/source.txt").readText())
        val writes = store.writes
        assertTrue(runCatching { manager.switch(retained.id) }.isFailure)
        assertTrue(runCatching { manager.add("Blocked") }.isFailure)
        assertEquals(writes, store.writes)
    }

    @Test fun restartAfterUncommittedAmbiguousRemovalRestoresQuarantineFromDurableCatalog() = runBlocking {
        val store = Store(temp.root)
        val manager = manager(store)
        val retained = manager.add("Retained")
        File(retained.rootPath, "source.txt").writeText("recover on restart")
        store.commitBeforeFailure = false
        store.nextSaveFailure = IOException("unknown removal")
        store.failResolutionRead = true
        assertTrue(runCatching { manager.deleteWorkspace(retained.id) }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertFalse(File(retained.rootPath).exists())
        store.failResolutionRead = false
        store.resolving = false
        val restarted = manager(store)
        assertEquals(store.records, restarted.projects.value)
        assertEquals("recover on restart", File(retained.rootPath, "source.txt").readText())
        assertFalse(File(temp.root, "projects/.delete-${retained.id}").exists())
    }
}

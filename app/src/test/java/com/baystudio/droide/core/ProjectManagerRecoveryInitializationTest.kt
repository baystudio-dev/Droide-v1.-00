package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectManagerRecoveryInitializationTest {
    private class Store(var records: List<DroideProject>, var selected: String?) : ProjectStore {
        var reads = 0; var saves = 0; var failReads = false
        override suspend fun load(): List<DroideProject> { check(!failReads); reads++; return records }
        override suspend fun loadActiveId(): String? { check(!failReads); reads++; return selected }
        override suspend fun save(all: List<DroideProject>) { saves++; records = all }
        override suspend fun saveActiveId(id: String) { saves++; selected = id }
    }
    private class Recents : ProjectRecentsStore {
        var records: List<String>? = listOf("p-a")
        var reads = 0; var saves = 0; var failReads = false
        override suspend fun load(): List<String>? { check(!failReads); reads++; return records }
        override suspend fun save(ids: List<String>) { saves++; records = ids }
    }
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("recovery-project-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun project(root: File, id: String = "p-a", create: Boolean = true): DroideProject {
        val path = File(root, "projects/$id")
        if (create) check(path.mkdirs())
        return DroideProject(id, id, path.absolutePath)
    }
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().associate { file ->
        file.relativeTo(root).path to if (file.isDirectory) "directory" else file.readBytes().joinToString("") { "%02x".format(it) }
    }

    @Test fun coldRecoveryLoadsExactlySavedActiveWithoutPersistingOrCreatingDefault() = fixture { root ->
        val active = project(root); File(active.rootPath, "keep.txt").writeText("source sentinel")
        val store = Store(listOf(active), active.id); val recents = Recents()
        val before = snapshot(root)
        val manager = ProjectManager(root, store, recents)
        manager.init(readOnlyExisting = true)
        assertSame(active, manager.active())
        assertEquals(2, store.reads); assertEquals(1, recents.reads)
        assertEquals(0, store.saves); assertEquals(0, recents.saves)
        assertEquals(before, snapshot(root))
        assertEquals(listOf(active), manager.projects.value)
    }

    @Test fun recoveryReusesAlreadyLoadedValidProjectWithoutAnyStoreRead() = fixture { root ->
        val active = project(root); val store = Store(listOf(active), active.id)
        // Establish a real initial migration before testing cached read-only recovery.
        val recents = Recents().apply { records = null }
        val manager = ProjectManager(root, store, recents)
        manager.init()
        assertTrue(store.saves > 0); assertTrue(recents.saves > 0)
        assertEquals(listOf(active.id), recents.records)
        val loaded = manager.active(); val before = snapshot(root)
        val reads = store.reads; val recentReads = recents.reads
        val writes = store.saves; val recentWrites = recents.saves
        store.failReads = true; recents.failReads = true
        manager.init(readOnlyExisting = true)
        assertSame(loaded, manager.active())
        assertEquals(reads, store.reads); assertEquals(recentReads, recents.reads)
        assertEquals(writes, store.saves); assertEquals(recentWrites, recents.saves)
        assertEquals(before, snapshot(root))
    }

    @Test fun missingSavedActiveFailsWithoutDefaultFallbackOrPartialPublication() = fixture { root ->
        val active = project(root); val store = Store(listOf(active), "missing"); val recents = Recents()
        val manager = ProjectManager(root, store, recents); val before = snapshot(root)
        assertNotNull(runCatching { manager.init(readOnlyExisting = true) }.exceptionOrNull())
        assertNull(manager.active()); assertTrue(manager.projects.value.isEmpty())
        assertEquals(0, store.saves); assertEquals(0, recents.saves)
        assertEquals(before, snapshot(root))
    }

    @Test fun missingRootDoesNotRestoreDetachedTreeOrCreateDirectories() = fixture { root ->
        val active = project(root, create = false)
        File(root, "projects/.delete-p-a").apply { mkdirs(); resolve("keep.txt").writeText("detached sentinel") }
        val store = Store(listOf(active), active.id); val recents = Recents()
        val manager = ProjectManager(root, store, recents); val before = snapshot(root)
        assertNotNull(runCatching { manager.init(readOnlyExisting = true) }.exceptionOrNull())
        assertFalse(File(active.rootPath).exists()); assertNull(manager.active())
        assertEquals(0, store.saves); assertEquals(0, recents.saves)
        assertEquals(before, snapshot(root))
    }

    @Test fun failedRecentsReadPublishesNoPartialCatalog() = fixture { root ->
        val active = project(root); val store = Store(listOf(active), active.id); val recents = Recents().apply { failReads = true }
        val manager = ProjectManager(root, store, recents); val before = snapshot(root)
        assertNotNull(runCatching { manager.init(readOnlyExisting = true) }.exceptionOrNull())
        assertNull(manager.active()); assertTrue(manager.projects.value.isEmpty())
        assertEquals(0, store.saves); assertEquals(0, recents.saves)
        assertEquals(before, snapshot(root))
    }

    @Test fun validNoncanonicalCatalogAndSavedPresentationBytesRemainUnchanged() = fixture { root ->
        val active = project(root)
        File(root, ".droide").mkdirs()
        File(root, ".droide/projects.json").writeText("""[ { "id": "p-a", "name": "A", "rootPath": "${root.absolutePath}/projects/./p-a" } ]""")
        File(root, ".droide/active_project.txt").writeText("p-a\n")
        File(root, ".droide/recent_projects.json").writeText("[ \"p-a\", \"p-a\" ]\n")
        File(root, "projects/.delete-unregistered").apply { mkdirs(); resolve("keep").writeText("detached") }
        val before = snapshot(root)
        val manager = ProjectManager(root)
        manager.init(readOnlyExisting = true)
        assertEquals(active.id, manager.active()?.id)
        assertEquals(File(active.rootPath).canonicalPath, manager.active()?.rootPath)
        assertEquals(before, snapshot(root))
    }
}

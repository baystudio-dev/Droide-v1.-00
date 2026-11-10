package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectManagerRegistryConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun waitHere() { entered.complete(Unit); release.await() }
    }

    private class Store(app: File) : ProjectStore {
        private val disk = FileProjectStore(app)
        @Volatile var records: List<DroideProject> = emptyList()
        @Volatile var active: String? = null
        @Volatile var nextSave: Gate? = null
        @Volatile var nextActiveSave: Gate? = null
        @Volatile var failNextSave = false
        private val lock = Any()
        override suspend fun load() = disk.load()
        override suspend fun loadActiveId() = active
        override suspend fun save(all: List<DroideProject>) {
            val gate = synchronized(lock) { nextSave.also { nextSave = null } }
            gate?.waitHere()
            if (failNextSave) { failNextSave = false; throw IOException("injected registry failure") }
            disk.save(all)
            records = all.toList()
        }
        override suspend fun saveActiveId(id: String) {
            val gate = synchronized(lock) { nextActiveSave.also { nextActiveSave = null } }
            gate?.waitHere()
            active = id
        }
    }

    private class Recents : ProjectRecentsStore {
        private var ids: List<String>? = null
        override suspend fun load() = ids
        override suspend fun save(ids: List<String>) { this.ids = ids.toList() }
    }

    @Test fun concurrentRegistrationsPreserveBothProjectsInMemoryAndDurableCatalog() = runBlocking(Dispatchers.IO) {
        val store = Store(temp.root)
        val manager = ProjectManager(temp.root, store, Recents()).apply { init() }
        val gate = Gate().also { store.nextSave = it }
        val first = async(start = CoroutineStart.UNDISPATCHED) { manager.add("First") }
        withTimeout(5_000) { gate.entered.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { manager.add("Second") }
        try {
            assertFalse("Second registration must await the current registry commit", second.isCompleted)
        } finally { gate.release.complete(Unit) }
        val a = withTimeout(5_000) { first.await() }
        val b = withTimeout(5_000) { second.await() }
        assertTrue(manager.projects.value.map { it.id }.containsAll(listOf("default", a.id, b.id)))
        assertEquals(manager.projects.value, store.records)
        assertTrue(File(a.rootPath).isDirectory)
        assertTrue(File(b.rootPath).isDirectory)
    }

    @Test fun registrationAndRemovalCannotResurrectDeletedProjectOrLoseNewProject() = runBlocking(Dispatchers.IO) {
        val store = Store(temp.root)
        val manager = ProjectManager(temp.root, store, Recents()).apply { init() }
        val removed = manager.add("Remove")
        File(removed.rootPath, "source.txt").writeText("removed explicitly")
        val gate = Gate().also { store.nextSave = it }
        val added = async(start = CoroutineStart.UNDISPATCHED) { manager.add("Keep") }
        withTimeout(5_000) { gate.entered.await() }
        val removal = async(start = CoroutineStart.UNDISPATCHED) { manager.remove(removed.id, deleteWorkspace = true) }
        try { assertFalse(removal.isCompleted) } finally { gate.release.complete(Unit) }
        val kept = withTimeout(5_000) { added.await() }
        withTimeout(5_000) { removal.await() }
        assertFalse(manager.projects.value.any { it.id == removed.id })
        assertTrue(manager.projects.value.any { it.id == kept.id })
        assertEquals(manager.projects.value, store.records)
        assertFalse(File(removed.rootPath).exists())
        assertTrue(File(kept.rootPath).isDirectory)
    }

    @Test fun deletionCommitAndNewRegistrationPreserveLatestRegistry() = runBlocking(Dispatchers.IO) {
        val store = Store(temp.root)
        val manager = ProjectManager(temp.root, store, Recents()).apply { init() }
        val removed = manager.add("Remove")
        val gate = Gate().also { store.nextSave = it }
        val removal = async(start = CoroutineStart.UNDISPATCHED) { manager.deleteWorkspace(removed.id) }
        withTimeout(5_000) { gate.entered.await() }
        val added = async(start = CoroutineStart.UNDISPATCHED) { manager.add("Keep") }
        try { assertFalse(added.isCompleted) } finally { gate.release.complete(Unit) }
        withTimeout(5_000) { removal.await() }
        val kept = withTimeout(5_000) { added.await() }
        assertFalse(manager.projects.value.any { it.id == removed.id })
        assertTrue(manager.projects.value.any { it.id == kept.id })
        assertEquals(manager.projects.value, store.records)
    }

    @Test fun switchCommitPreventsConcurrentDeletionOfItsSelectedWorkspace() = runBlocking(Dispatchers.IO) {
        val store = Store(temp.root)
        val manager = ProjectManager(temp.root, store, Recents()).apply { init() }
        val project = manager.add("Selected")
        File(project.rootPath, "source.txt").writeText("must survive")
        val gate = Gate().also { store.nextActiveSave = it }
        val switching = async(start = CoroutineStart.UNDISPATCHED) { manager.switch(project.id) }
        withTimeout(5_000) { gate.entered.await() }
        val deletion = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.deleteWorkspace(project.id) } }
        try { assertFalse(deletion.isCompleted) } finally { gate.release.complete(Unit) }
        withTimeout(5_000) { switching.await() }
        assertTrue(withTimeout(5_000) { deletion.await() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(project.id, manager.activeId.value)
        assertEquals(project.id, store.active)
        assertTrue(manager.projects.value.any { it.id == project.id })
        assertEquals("must survive", File(project.rootPath, "source.txt").readText())
    }

    @Test fun failedCommitReleasesRegistryWithoutLosingPreviousProjects() = runBlocking(Dispatchers.IO) {
        val store = Store(temp.root)
        val manager = ProjectManager(temp.root, store, Recents()).apply { init() }
        val existing = manager.add("Existing")
        val before = manager.projects.value
        store.failNextSave = true
        assertTrue(runCatching { manager.add("Failed") }.exceptionOrNull() is IOException)
        assertEquals(before, manager.projects.value)
        assertEquals(before, store.records)
        val following = manager.add("Following")
        assertTrue(manager.projects.value.map { it.id }.containsAll(listOf(existing.id, following.id)))
        assertEquals(manager.projects.value, store.records)
    }
}

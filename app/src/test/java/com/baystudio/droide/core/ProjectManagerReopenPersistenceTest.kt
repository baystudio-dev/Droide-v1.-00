package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Reopening normal application state must not depend on rewriting unchanged presentation state. */
class ProjectManagerReopenPersistenceTest {
    private fun root(): File = Files.createTempDirectory("f163-catalog-reopen-").toFile()
    private fun metadata(root: File, name: String) = File(root, ".droide/$name")
    private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    private class Counts {
        var active = 0
        var recent = 0
        var failActive = false
        var failRecent = false
    }

    private fun manager(root: File, counts: Counts): ProjectManager {
        val store = FileProjectStore(root) { from, to, options ->
            if (to.fileName.toString() == "active_project.txt") {
                counts.active++
                if (counts.failActive) throw IOException("Active selection publication unavailable")
            }
            Files.move(from, to, *options)
        }
        val recents = FileProjectRecentsStore(root) { from, to, options ->
            counts.recent++
            if (counts.failRecent) throw IOException("Recent projects publication unavailable")
            Files.move(from, to, *options)
        }
        return ProjectManager(root, store, recents)
    }

    @Test fun unchangedReopenDoesNotNeedActivePublication() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        val counts = Counts().apply { failActive = true }
        val reopened = manager(root, counts).apply { init() }
        assertEquals("default", reopened.activeId.value)
        assertEquals(0, counts.active)
        assertEquals(0, counts.recent)
        assertFalse(reopened.selectionIsUncertain)
    }

    @Test fun unchangedReopenDoesNotNeedRecentPublication() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        val counts = Counts().apply { failRecent = true }
        val reopened = manager(root, counts).apply { init() }
        assertEquals("default", reopened.activeId.value)
        assertEquals(0, counts.active)
        assertEquals(0, counts.recent)
    }

    @Test fun freshStartPersistsSelectionAndExplicitEmptyRecentsOnce() = runBlocking {
        val root = root()
        val counts = Counts()
        val manager = manager(root, counts)
        manager.init()
        assertEquals(1, counts.active)
        assertEquals(1, counts.recent)
        assertEquals("default", FileProjectStore(root).loadActiveId())
        assertEquals(emptyList<String>(), FileProjectRecentsStore(root).load())
        manager.init()
        assertEquals(1, counts.active)
        assertEquals(1, counts.recent)
    }

    @Test fun existingNonDefaultSelectionAndRecentsSurviveRepeatedReopen() = runBlocking {
        val root = root()
        val initial = ProjectManager(root).apply { init() }
        val first = initial.add("First")
        val second = initial.add("Second")
        initial.switch(first.id)
        initial.switch(second.id)
        val counts = Counts()
        repeat(3) {
            val reopened = manager(root, counts).apply { init() }
            assertEquals(second.id, reopened.activeId.value)
            assertEquals(listOf("default", second.id, first.id), reopened.recentProjects.value.map { it.id })
        }
        assertEquals(0, counts.active)
        assertEquals(0, counts.recent)
    }

    @Test fun unknownSelectionIsRepairedButEqualRecentsAreNotRewritten() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        metadata(root, "active_project.txt").writeText("missing-project\n")
        val counts = Counts()
        val reopened = manager(root, counts).apply { init() }
        assertEquals("default", reopened.activeId.value)
        assertEquals("default", FileProjectStore(root).loadActiveId())
        assertEquals(1, counts.active)
        assertEquals(0, counts.recent)
    }

    @Test fun malformedSelectionStillRequiresRepair() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        metadata(root, "active_project.txt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val counts = Counts()
        manager(root, counts).init()
        assertEquals(1, counts.active)
        assertEquals("default", FileProjectStore(root).loadActiveId())
    }

    @Test fun missingRecentStateIsMigratedAndThenStable() = runBlocking {
        val root = root()
        val initial = ProjectManager(root, recentsStore = object : ProjectRecentsStore {
            override suspend fun load(): List<String>? = null
            override suspend fun save(ids: List<String>) = Unit
        }).apply { init() }
        val first = initial.add("First")
        val second = initial.add("Second")
        assertFalse(metadata(root, "recent_projects.json").exists())
        val counts = Counts()
        manager(root, counts).init()
        assertEquals(listOf(first.id, second.id), FileProjectRecentsStore(root).load())
        manager(root, counts).init()
        assertEquals(0, counts.active)
        assertEquals(1, counts.recent)
    }

    @Test fun staleRecentsAreRepairedAndActiveMovesToFront() = runBlocking {
        val root = root()
        val initial = ProjectManager(root).apply { init() }
        val first = initial.add("First")
        val second = initial.add("Second")
        initial.switch(second.id)
        FileProjectRecentsStore(root).save(listOf(first.id, "unknown", second.id))
        val counts = Counts()
        val reopened = manager(root, counts).apply { init() }
        assertEquals(listOf("default", second.id, first.id), reopened.recentProjects.value.map { it.id })
        assertEquals(0, counts.active)
        assertEquals(1, counts.recent)
    }

    @Test fun failedRequiredSelectionRepairDoesNotPublishLoadedState() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        metadata(root, "active_project.txt").writeText("unknown\n")
        val counts = Counts().apply { failActive = true }
        val reopened = manager(root, counts)
        assertNotNull(runCatching { reopened.init() }.exceptionOrNull())
        assertTrue(reopened.projects.value.isEmpty())
        assertNull(reopened.activeId.value)
        assertEquals("unknown", FileProjectStore(root).loadActiveId())
        assertEquals(1, counts.active)
        assertEquals(0, counts.recent)
    }

    @Test fun failedRequiredRecentsRepairDoesNotPublishLoadedState() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        FileProjectRecentsStore(root).save(listOf("unknown"))
        val counts = Counts().apply { failRecent = true }
        val reopened = manager(root, counts)
        assertNotNull(runCatching { reopened.init() }.exceptionOrNull())
        assertTrue(reopened.projects.value.isEmpty())
        assertNull(reopened.activeId.value)
        assertEquals(listOf("unknown"), FileProjectRecentsStore(root).load())
        assertEquals(0, counts.active)
        assertEquals(1, counts.recent)
    }

    @Test fun readOnlyExistingStillDoesNotRepairPresentation() = runBlocking {
        val root = root()
        val initial = ProjectManager(root).apply { init() }
        val project = initial.add("Known")
        initial.switch(project.id)
        FileProjectRecentsStore(root).save(listOf("unknown"))
        val counts = Counts().apply { failActive = true; failRecent = true }
        val reopened = manager(root, counts).apply { init(readOnlyExisting = true) }
        assertEquals(project.id, reopened.activeId.value)
        assertEquals(listOf("default", project.id), reopened.recentProjects.value.map { it.id })
        assertEquals(listOf("unknown"), FileProjectRecentsStore(root).load())
        assertEquals(0, counts.active)
        assertEquals(0, counts.recent)
    }

    @Test fun normalizedPresentationBytesArePreservedOnNoOpReopen() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        val active = metadata(root, "active_project.txt")
        val recent = metadata(root, "recent_projects.json")
        active.writeText("  default  \n")
        recent.writeText("{invalid presentation json")
        val activeBytes = active.readBytes()
        val recentBytes = recent.readBytes()
        val counts = Counts()
        val reopened = manager(root, counts).apply { init() }
        assertEquals("default", reopened.activeId.value)
        assertEquals(listOf("default"), reopened.recentProjects.value.map { it.id })
        assertArrayEquals(activeBytes, active.readBytes())
        assertArrayEquals(recentBytes, recent.readBytes())
        assertEquals(0, counts.active)
        assertEquals(0, counts.recent)
    }

    @Test fun recentLoadFailureCannotPublishStartupState() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        val failure = IOException("Presentation read unavailable")
        val recents = object : ProjectRecentsStore {
            override suspend fun load(): List<String>? = throw failure
            override suspend fun save(ids: List<String>) { error("No save after failed read") }
        }
        val reopened = ProjectManager(root, recentsStore = recents)
        assertSame(failure, runCatching { reopened.init() }.exceptionOrNull())
        assertTrue(reopened.projects.value.isEmpty())
        assertNull(reopened.activeId.value)
    }

    @Test fun cancellationDuringEqualRecentLoadCannotPublishStartupState() = runBlocking {
        val root = root()
        ProjectManager(root).init()
        val recents = object : ProjectRecentsStore {
            override suspend fun load(): List<String> {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                return emptyList()
            }
            override suspend fun save(ids: List<String>) = Unit
        }
        val reopened = ProjectManager(root, recentsStore = recents)
        val failure = runCatching { reopened.init() }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(reopened.projects.value.isEmpty())
        assertNull(reopened.activeId.value)
    }

}

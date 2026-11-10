package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectManagerCatalogConflictTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalog get() = File(temp.root, ".droide/projects.json")
    private val active get() = File(temp.root, ".droide/active_project.txt")
    private val recent get() = File(temp.root, ".droide/recent_projects.json")
    private fun stages() = File(temp.root, ".droide").listFiles().orEmpty()
        .filter { it.name.startsWith(".projects.json.tmp-") }

    private suspend fun twoManagers(sharedStore: Boolean) {
        val firstStore = FileProjectStore(temp.root)
        val first = ProjectManager(temp.root, firstStore).apply { init() }
        val second = ProjectManager(temp.root, if (sharedStore) firstStore else FileProjectStore(temp.root)).apply { init() }
        val oldRegistry = first.projects.value
        val external = second.add("Other manager")
        val catalogBytes = catalog.readBytes()
        val activeBytes = active.readBytes()
        val recentBytes = recent.readBytes()
        val failure = runCatching { first.add("Stale proposal") }.exceptionOrNull()
        assertTrue(failure is ProjectCatalogConflictException)
        assertEquals(oldRegistry, first.projects.value)
        assertTrue(first.selectionIsUncertain)
        assertArrayEquals(catalogBytes, catalog.readBytes())
        assertArrayEquals(activeBytes, active.readBytes())
        assertArrayEquals(recentBytes, recent.readBytes())
        assertTrue(File(external.rootPath).isDirectory)
        assertTrue(stages().single().readText().contains("Stale proposal"))
        assertEquals(3, File(temp.root, "projects").listFiles().orEmpty().count { it.isDirectory })
        assertTrue(runCatching { first.add("Must reload") }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertArrayEquals(catalogBytes, catalog.readBytes())
    }

    @Test fun separateManagersRejectStaleRegistryWithoutSelectionWrites() = runBlocking { twoManagers(false) }
    @Test fun managersSharingStoreStillOwnIndependentBaselines() = runBlocking { twoManagers(true) }

    @Test fun freshInitializationCapturesMissingBaselineBeforeDefaultCreation() = runBlocking {
        val manager = ProjectManager(temp.root).apply { init() }
        assertEquals("default", manager.activeId.value)
        assertEquals(manager.projects.value, FileProjectStore(temp.root).load())
        manager.add("After default")
        assertEquals(2, FileProjectStore(temp.root).load().size)
    }

    private fun injectBeforeReplace(action: () -> Unit): StreamPublicationDurability =
        object : StreamPublicationDurability {
            private var calls = 0
            override fun forceFile(channel: FileChannel) = StreamPublicationDurability.platform.forceFile(channel)
            override fun forceDirectory(directory: File) {
                StreamPublicationDurability.platform.forceDirectory(directory)
                if (++calls == 2) action()
            }
        }

    @Test fun initializationConflictDoesNotPublishRegistryOrSelection() = runBlocking {
        val store = FileProjectStore(temp.root, injectBeforeReplace { catalog.writeText("[]") }, ProjectMetadataPublication::replace)
        val manager = ProjectManager(temp.root, store)
        assertTrue(runCatching { manager.init() }.exceptionOrNull() is ProjectCatalogConflictException)
        assertTrue(manager.projects.value.isEmpty())
        assertNull(manager.activeId.value)
        assertFalse(active.exists())
        assertFalse(recent.exists())
        assertEquals("[]", catalog.readText())
        assertTrue(File(temp.root, "projects/default").isDirectory)
        assertEquals(1, stages().size)
    }

    @Test fun typedConflictIsNeverPromotedWhenExternalBytesEqualProposal() = runBlocking {
        ProjectManager(temp.root).init()
        var inject = false
        var directoryFlushes = 0
        val durability = object : StreamPublicationDurability {
            override fun forceFile(channel: FileChannel) = StreamPublicationDurability.platform.forceFile(channel)
            override fun forceDirectory(directory: File) {
                StreamPublicationDurability.platform.forceDirectory(directory)
                if (inject && ++directoryFlushes == 2) catalog.writeBytes(stages().single().readBytes())
            }
        }
        val store = FileProjectStore(temp.root, durability, ProjectMetadataPublication::replace)
        val manager = ProjectManager(temp.root, store).apply { init() }
        val old = manager.projects.value
        val activeBytes = active.readBytes()
        val recentBytes = recent.readBytes()
        inject = true
        assertTrue(runCatching { manager.add("Externally identical proposal") }.exceptionOrNull() is ProjectCatalogConflictException)
        assertTrue(catalog.readText().contains("Externally identical proposal"))
        assertEquals(old, manager.projects.value)
        assertTrue(manager.selectionIsUncertain)
        assertArrayEquals(activeBytes, active.readBytes())
        assertArrayEquals(recentBytes, recent.readBytes())
    }

    @Test fun parsedEqualityDoesNotBlessDifferentBytesAfterLostReturn() = runBlocking {
        var loseReturn = false
        val store = FileProjectStore(temp.root) { from, to, options ->
            Files.move(from, to, *options)
            if (loseReturn && to.fileName.toString() == "projects.json") {
                val external = Files.newBufferedReader(to, UTF_8).use { it.readText() } + "\n"
                Files.newBufferedWriter(to, UTF_8).use { it.write(external) }
                throw IOException("Lost return followed by a distinct external revision")
            }
        }
        val manager = ProjectManager(temp.root, store).apply { init() }
        val before = manager.projects.value
        loseReturn = true
        assertTrue(runCatching { manager.add("Different external bytes") }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertEquals(before, manager.projects.value)
        assertTrue(manager.selectionIsUncertain)
        assertTrue(FileProjectStore(temp.root).load().any { it.name == "Different external bytes" })
    }

    @Test fun exactCommittedReturnLossAdvancesBaselineForNextSave() = runBlocking {
        var loseReturn = false
        val store = FileProjectStore(temp.root) { from, to, options ->
            Files.move(from, to, *options)
            if (loseReturn && to.fileName.toString() == "projects.json") throw IOException("Committed return lost")
        }
        val manager = ProjectManager(temp.root, store).apply { init() }
        loseReturn = true
        manager.add("First committed")
        manager.add("Second committed")
        assertFalse(manager.selectionIsUncertain)
        assertEquals(manager.projects.value, FileProjectStore(temp.root).load())
        assertEquals(3, manager.projects.value.size)
    }

    @Test fun committedCancellationAdvancesBaselineButStillPropagates() = runBlocking {
        val cancellation = CancellationException("Committed return cancelled")
        var cancelReturn = false
        val store = FileProjectStore(temp.root) { from, to, options ->
            Files.move(from, to, *options)
            if (cancelReturn && to.fileName.toString() == "projects.json") throw cancellation
        }
        val manager = ProjectManager(temp.root, store).apply { init() }
        cancelReturn = true
        assertOriginatingFailure(cancellation, runCatching { manager.add("Committed before cancellation") }.exceptionOrNull())
        assertEquals(2, manager.projects.value.size)
        assertFalse(manager.selectionIsUncertain)
        cancelReturn = false
        manager.add("After cancellation")
        assertEquals(manager.projects.value, FileProjectStore(temp.root).load())
        assertEquals(3, manager.projects.value.size)
    }

}

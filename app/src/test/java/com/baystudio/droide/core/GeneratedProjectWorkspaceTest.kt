package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test

/** Plain JVM tests. Manager initialization and nativeCreate require verified Linux host JNI. */
class GeneratedProjectWorkspaceTest {
    private class Recents : ProjectRecentsStore {
        private var ids: List<String>? = null
        override suspend fun load() = ids
        override suspend fun save(ids: List<String>) { this.ids = ids.toList() }
    }

    private class Store(root: File) : ProjectStore {
        private val disk = FileProjectStore(root)
        var beforeSave: (List<DroideProject>) -> Unit = {}
        var saves = 0
        override suspend fun load() = disk.load()
        override suspend fun loadActiveId() = disk.loadActiveId()
        override suspend fun saveActiveId(id: String) = disk.saveActiveId(id)
        override suspend fun save(all: List<DroideProject>) {
            saves++
            beforeSave(all)
            disk.save(all)
        }
    }

    private fun nativeCreate(parent: File, leaf: String) = AnchoredWorkspaceFiles(parent).use {
        it.createDirectory(leaf)
    }

    @Test fun cancellationKeepsItsCategoryAndRetainedPath() {
        val app = Files.createTempDirectory("f164-cancel-").toFile()
        val root = File(app, "generated")
        val creator = GeneratedProjectWorkspace { _, _ ->
            check(root.mkdir())
            throw CancellationException("injected cancellation")
        }
        val failure = runCatching { creator.create(app, root) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(failure!!.message!!.contains(root.path))
        assertTrue(root.isDirectory)
    }

    @Test fun fatalErrorEscapesUnchangedAndRetainsPublishedFolder() {
        val app = Files.createTempDirectory("f164-fatal-").toFile()
        val root = File(app, "generated")
        val fatal = AssertionError("injected fatal failure")
        val creator = GeneratedProjectWorkspace { _, _ ->
            check(root.mkdir())
            File(root, "keep.txt").writeText("keep")
            throw fatal
        }
        val failure = runCatching { creator.create(app, root) }.exceptionOrNull()
        assertSame(fatal, failure)
        assertEquals("keep", File(root, "keep.txt").readText())
    }

    @Test fun cancelledCreationCallRetainsDurableRootWithoutCatalogPublication() = runBlocking {
        val app = Files.createTempDirectory("f164-call-cancel-").toFile()
        val store = Store(app)
        lateinit var call: Job
        var created: File? = null
        val manager = ProjectManager(app, store, Recents(), GeneratedProjectWorkspace { parent, leaf ->
            nativeCreate(parent, leaf).also { receipt ->
                assertTrue(receipt.durable)
                created = File(parent, leaf)
                call.cancel(CancellationException("Cancel only this creation call"))
            }
        }).apply { init() }
        val before = manager.projects.value
        val active = manager.activeId.value
        val saves = store.saves
        val attempt = async(start = CoroutineStart.LAZY) {
            manager.createFromTemplate("Cancelled", "empty")
        }
        call = attempt
        attempt.start()
        val failure = runCatching { attempt.await() }.exceptionOrNull()
        attempt.join()
        assertTrue(failure is CancellationException)
        currentCoroutineContext().ensureActive()
        assertTrue(checkNotNull(created).isDirectory)
        assertEquals(saves, store.saves)
        assertEquals(before, manager.projects.value)
        assertEquals(before, store.load())
        assertEquals(active, manager.activeId.value)
        assertEquals(active, store.loadActiveId())
    }

    @Test fun emptyTemplateRequiresDurableReceiptBeforeCatalogAndReopens() = runBlocking {
        val app = Files.createTempDirectory("f164-order-").toFile()
        val store = Store(app)
        val events = mutableListOf<String>()
        val manager = ProjectManager(app, store, Recents(), GeneratedProjectWorkspace { parent, leaf ->
            nativeCreate(parent, leaf).also {
                assertTrue(it.durable)
                events.add("durable")
            }
        }).apply { init() }
        store.beforeSave = { all ->
            assertEquals(listOf("durable"), events)
            assertTrue(File(all.last().rootPath).isDirectory)
            events.add("catalog")
        }
        val (project, primary) = manager.createFromTemplate("Empty", "empty")
        assertNull(primary)
        assertEquals(listOf("durable", "catalog"), events)
        assertEquals("default", manager.activeId.value)
        val reopened = ProjectManager(app).apply { init() }
        assertEquals(project, reopened.projects.value.single { it.id == project.id })
    }

    @Test fun productionAdapterCreatesRootAndReopens() = runBlocking {
        val app = Files.createTempDirectory("f164-native-").toFile()
        val manager = ProjectManager(app).apply { init() }
        val project = manager.createFromTemplate("Empty", "empty").first
        assertTrue(File(project.rootPath).isDirectory)
        assertEquals(project, ProjectManager(app).apply { init() }.projects.value.single { it.id == project.id })
    }

    private suspend fun rejectedCreation(mode: String) {
        val app = Files.createTempDirectory("f164-$mode-").toFile()
        val store = Store(app)
        var attempted: File? = null
        val creator = GeneratedProjectWorkspace { parent, leaf ->
            val root = File(parent, leaf).also { attempted = it }
            when (mode) {
                "before" -> AnchoredWorkspaceFiles.MoveReceipt(false, 5)
                "sync" -> {
                    assertTrue(nativeCreate(parent, leaf).durable)
                    File(root, "retained.txt").writeText("keep")
                    AnchoredWorkspaceFiles.MoveReceipt(true, 5)
                }
                "throw" -> {
                    assertTrue(nativeCreate(parent, leaf).durable)
                    File(root, "retained.txt").writeText("keep")
                    throw IOException("injected post-publication failure")
                }
                "file" -> {
                    root.writeText("keep")
                    nativeCreate(parent, leaf)
                }
                "symlink" -> {
                    val target = File(app, "private-target").apply { check(mkdir()) }
                    File(target, "keep.txt").writeText("keep")
                    Files.createSymbolicLink(root.toPath(), target.toPath())
                    nativeCreate(parent, leaf)
                }
                "directory" -> {
                    check(root.mkdir())
                    File(root, "retained.txt").writeText("keep")
                    nativeCreate(parent, leaf)
                }
                else -> error(mode)
            }
        }
        val manager = ProjectManager(app, store, Recents(), creator).apply { init() }
        val before = manager.projects.value
        val active = manager.activeId.value
        val saves = store.saves
        val failure = runCatching { manager.createFromTemplate("Rejected", "empty") }.exceptionOrNull()
        val path = checkNotNull(attempted)
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains(path.path))
        assertEquals(saves, store.saves)
        assertEquals(before, manager.projects.value)
        assertEquals(before, store.load())
        assertEquals(active, manager.activeId.value)
        assertEquals(active, store.loadActiveId())
        when (mode) {
            "before" -> assertFalse(path.exists())
            "file" -> assertEquals("keep", path.readText())
            "symlink" -> {
                val target = File(app, "private-target")
                assertTrue(Files.isSymbolicLink(path.toPath()))
                assertEquals(target.toPath(), Files.readSymbolicLink(path.toPath()))
                assertEquals("keep", File(target, "keep.txt").readText())
            }
            else -> assertEquals("keep", File(path, "retained.txt").readText())
        }
    }

    @Test fun prepublicationFailureDoesNotSaveCatalog() = runBlocking { rejectedCreation("before") }
    @Test fun unconfirmedSyncRetainsFolderWithoutCatalogPublication() = runBlocking { rejectedCreation("sync") }
    @Test fun exceptionAfterPublicationRetainsFolderAndReportsPath() = runBlocking { rejectedCreation("throw") }
    @Test fun generatedFileCollisionIsNotReplacedOrAdopted() = runBlocking { rejectedCreation("file") }
    @Test fun generatedSymlinkCollisionPreservesLinkAndTarget() = runBlocking { rejectedCreation("symlink") }
    @Test fun generatedDirectoryCollisionIsNotReplacedOrAdopted() = runBlocking { rejectedCreation("directory") }

    @Test fun catalogFailureRetainsDurablyCreatedFolderAndPreviousState() = runBlocking {
        val app = Files.createTempDirectory("f164-catalog-").toFile()
        val store = Store(app)
        var created: File? = null
        val manager = ProjectManager(app, store, Recents(), GeneratedProjectWorkspace { parent, leaf ->
            nativeCreate(parent, leaf).also {
                assertTrue(it.durable)
                created = File(parent, leaf)
            }
        }).apply { init() }
        val before = manager.projects.value
        store.beforeSave = { throw IOException("injected catalog failure") }
        assertTrue(runCatching { manager.add("Failed") }.exceptionOrNull() is IOException)
        assertTrue(checkNotNull(created).isDirectory)
        assertEquals(before, manager.projects.value)
        assertEquals(before, store.load())
        assertEquals("default", manager.activeId.value)
        assertEquals("default", store.loadActiveId())
    }

    @Test fun explicitlySuppliedExistingRootDoesNotInvokeGeneratedCreator() = runBlocking {
        val app = Files.createTempDirectory("f164-existing-").toFile()
        val store = Store(app)
        val manager = ProjectManager(app, store, Recents(), GeneratedProjectWorkspace { _, _ ->
            error("Explicit registration must not create a generated root")
        }).apply { init() }
        val existing = File(app, "projects/existing").apply { check(mkdir()) }
        File(existing, "keep.txt").writeText("keep")
        val project = manager.add("Imported", existing.path)
        assertEquals(existing.canonicalPath, project.rootPath)
        assertEquals("keep", File(existing, "keep.txt").readText())
        assertTrue(store.load().contains(project))
    }
    @Test fun explicitlySuppliedMissingRootRetainsLegacyCreationBehavior() = runBlocking {
        val app = Files.createTempDirectory("f164-explicit-missing-").toFile()
        val store = Store(app)
        val manager = ProjectManager(app, store, Recents(), GeneratedProjectWorkspace { _, _ ->
            error("Explicit registration must not invoke generated-root creation")
        }).apply { init() }
        val missing = File(app, "projects/explicit-missing")
        assertFalse(missing.exists())
        val project = manager.add("Explicit", missing.path)
        assertTrue(missing.isDirectory)
        assertEquals(missing.canonicalPath, project.rootPath)
        assertTrue(store.load().contains(project))
    }

}

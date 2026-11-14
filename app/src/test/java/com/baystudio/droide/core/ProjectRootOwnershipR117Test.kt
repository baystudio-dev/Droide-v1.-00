package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectRootOwnershipR117Test {
    private class Store(var records: List<DroideProject>, var active: String? = null) : ProjectStore {
        var saves = 0
        override suspend fun load() = records
        override suspend fun save(all: List<DroideProject>) { saves++; records = all }
        override suspend fun loadActiveId() = active
        override suspend fun saveActiveId(id: String) { saves++; active = id }
    }

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("project-root-ownership-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun project(app: File, id: String, leaf: String = id, name: String = id): DroideProject {
        val root = File(app, "projects/$leaf")
        check(root.isDirectory || root.mkdirs())
        return DroideProject(id, name, root.absolutePath)
    }

    private fun writeCatalog(app: File, records: List<DroideProject>): File {
        val catalog = File(app, ".droide/projects.json").apply { parentFile.mkdirs() }
        catalog.writeText(JsonArray(records.map { record ->
            buildJsonObject {
                put("id", record.id); put("name", record.name); put("rootPath", record.rootPath)
                record.treeUri?.let { put("treeUri", it) }
            }
        }).toString())
        return catalog
    }

    @Test fun persistedDistinctIdsCannotClaimSameCanonicalRoot() = fixture { app ->
        val a = project(app, "p-a", "shared")
        val b = DroideProject("p-b", "B", "${a.rootPath}/.")
        val sentinel = File(a.rootPath, "work.bin").apply { writeBytes(byteArrayOf(0, -1, 3)) }
        val catalog = writeCatalog(app, listOf(a, b))
        val original = catalog.readBytes()
        val failure = runCatching { FileProjectStore(app).load() }.exceptionOrNull()
        assertNotNull(failure)
        // Coroutine stack recovery may add another IOException around the preserved
        // cause. Assert the ownership diagnostic without depending on wrapper depth.
        assertTrue(failure.toString(), generateSequence(failure) { it.cause }
            .any { it.message.orEmpty().contains("share one workspace") })
        assertArrayEquals(original, catalog.readBytes())
        assertArrayEquals(byteArrayOf(0, -1, 3), sentinel.readBytes())
        assertFalse(File(app, "projects/p-a").exists())
        assertFalse(File(app, "projects/p-b").exists())
    }

    @Test fun registryValidationPrecedesRecoveryMovesAndPublication() = fixture { app ->
        val a = project(app, "p-a", "shared")
        val b = DroideProject("p-b", "B", a.rootPath)
        val detached = File(app, "projects/.delete-default").apply {
            mkdirs(); resolve("retained.txt").writeText("uncommitted delete evidence")
        }
        val store = Store(listOf(a, b), a.id)
        val manager = ProjectManager(app, store)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertEquals(0, store.saves)
        assertTrue(manager.projects.value.isEmpty())
        assertNull(manager.active())
        assertTrue(detached.resolve("retained.txt").isFile)
        assertFalse(File(app, "projects/default").exists())
        assertEquals(listOf(a, b), store.records)
    }

    @Test fun readonlyRecoveryRejectsAliasedOwnershipWithoutWriting() = fixture { app ->
        val a = project(app, "p-a", "shared")
        val b = DroideProject("p-b", "B", a.rootPath)
        val store = Store(listOf(a, b), a.id)
        val manager = ProjectManager(app, store)
        assertNotNull(runCatching { manager.init(readOnlyExisting = true) }.exceptionOrNull())
        assertEquals(0, store.saves)
        assertTrue(manager.projects.value.isEmpty())
        assertNull(manager.active())
        assertFalse(File(app, "projects/default").exists())
    }

    @Test fun defaultRootIsReservedEvenWhenDefaultCatalogEntryIsMissing() = fixture { app ->
        val alias = project(app, "p-alias", "default")
        val sentinel = File(alias.rootPath, "keep.kt").apply { writeText("user source") }
        val store = Store(listOf(alias), alias.id)
        val manager = ProjectManager(app, store)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertEquals(0, store.saves)
        assertEquals("user source", sentinel.readText())
        assertNull(manager.active())
        val catalog = writeCatalog(app, listOf(alias))
        val original = catalog.readBytes()
        assertNotNull(runCatching { FileProjectStore(app).load() }.exceptionOrNull())
        assertArrayEquals(original, catalog.readBytes())
    }

    @Test fun duplicateIdsAreRejectedInsteadOfSilentlyDroppingRecords() = fixture { app ->
        val a = project(app, "p-a", "one")
        val b = project(app, "p-a", "two")
        val store = Store(listOf(a, b), a.id)
        val manager = ProjectManager(app, store)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertEquals(0, store.saves)
        assertEquals(listOf(a, b), store.records)
        assertTrue(manager.projects.value.isEmpty())
    }

    @Test fun equalDisplayNamesRemainValidWhenRootsDiffer() = fixture { app ->
        val a = project(app, "p-a", name = "matap")
        val b = project(app, "p-b", name = "matap")
        val store = Store(listOf(a, b), a.id)
        val manager = ProjectManager(app, store)
        manager.init()
        assertEquals(a.id, manager.active()!!.id)
        assertEquals(2, manager.projects.value.count { it.name == "matap" })
        manager.switch(b.id)
        assertEquals(File(b.rootPath).canonicalFile, manager.activeRoot())
        assertTrue(File(a.rootPath).isDirectory)
    }

    @Test fun rejectedSavePreservesPreviousCatalog() = fixture { app ->
        val a = project(app, "p-a")
        val store = FileProjectStore(app)
        store.save(listOf(a))
        val catalog = File(app, ".droide/projects.json")
        val original = catalog.readBytes()
        val alias = DroideProject("p-b", "B", a.rootPath)
        assertNotNull(runCatching { store.save(listOf(a, alias)) }.exceptionOrNull())
        assertArrayEquals(original, catalog.readBytes())
        assertEquals(listOf(a.copy(rootPath = File(a.rootPath).canonicalPath)), store.load())
    }

    @Test fun symbolicRootCannotBeRepairedIntoAnEmptyAliasOrDeleteAnotherProject() = fixture { app ->
        val manager = ProjectManager(app, Store(emptyList()))
        manager.init()
        val a = manager.add("A")
        val b = manager.add("B")
        manager.switch(a.id)
        val aRoot = File(a.rootPath)
        aRoot.resolve("keep.txt").writeText("active project source")
        val bRoot = File(b.rootPath)
        assertTrue(bRoot.delete())
        Files.createSymbolicLink(bRoot.toPath(), aRoot.toPath())
        assertNotNull(runCatching { manager.deleteWorkspace(b.id) }.exceptionOrNull())
        assertEquals("active project source", aRoot.resolve("keep.txt").readText())
        assertTrue(Files.isSymbolicLink(bRoot.toPath()))
        assertEquals(3, manager.projects.value.size)
        assertFalse(File(app, "projects/.delete-${b.id}").exists())
        assertNotNull(runCatching {
            ProjectWorkspacePolicy.restoreRoot(File(app, "projects"), "p-other", b.rootPath)
        }.exceptionOrNull())
        assertFalse(File(app, "projects/p-other").exists())
    }

    @Test fun deletionRecoveryDirectoriesCannotBeRegisteredAsProjectRoots() = fixture { app ->
        val manager = ProjectManager(app, Store(emptyList()))
        manager.init()
        val detached = File(app, "projects/.delete-p-old").apply {
            mkdirs(); resolve("keep").writeText("retained")
        }
        assertNotNull(runCatching { manager.add("Alias", detached.absolutePath) }.exceptionOrNull())
        assertNotNull(runCatching {
            ProjectWorkspacePolicy.restoreRoot(File(app, "projects"), "p-new", detached.absolutePath)
        }.exceptionOrNull())
        assertEquals("retained", detached.resolve("keep").readText())
        assertEquals(1, manager.projects.value.size)
        assertFalse(File(app, "projects/p-new").exists())
    }

    @Test fun projectsContainerSymlinkCannotAuthorizeOutsideWorkspaceData() = fixture { app ->
        val outside = File(app, "unrelated-storage").apply { mkdirs() }
        outside.resolve("default").mkdirs()
        val sentinel = outside.resolve("default/private.txt").apply { writeText("outside") }
        val container = File(app, "projects")
        Files.createSymbolicLink(container.toPath(), outside.toPath())
        val manager = ProjectManager(app, Store(emptyList()))
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertTrue(manager.projects.value.isEmpty())
        assertNotNull(runCatching {
            ProjectWorkspacePolicy.requireDirectChild(container, container.resolve("default"))
        }.exceptionOrNull())
        assertEquals("outside", sentinel.readText())
        assertTrue(Files.isSymbolicLink(container.toPath()))
    }

    @Test fun trailingDotDoesNotHideSymbolicWorkspaceAlias() = fixture { app ->
        val real = project(app, "p-real")
        val link = File(app, "projects/p-alias")
        Files.createSymbolicLink(link.toPath(), File(real.rootPath).toPath())
        assertNotNull(runCatching {
            ProjectWorkspacePolicy.requireDirectChild(File(app, "projects"), File(link, "."))
        }.exceptionOrNull())
        assertNotNull(runCatching {
            ProjectWorkspacePolicy.restoreRoot(File(app, "projects"), "p-unused", File(link, ".").path)
        }.exceptionOrNull())
        assertFalse(File(app, "projects/p-unused").exists())
    }

    @Test fun platformAppParentAliasStillResolvesToItsPrivateProjectContainer() = fixture { app ->
        val real = File(app, "real-app/files").apply { mkdirs() }
        val aliasParent = File(app, "platform-app-alias")
        Files.createSymbolicLink(aliasParent.toPath(), real.parentFile.toPath())
        val filesThroughAlias = File(aliasParent, "files")
        assertFalse(Files.isSymbolicLink(filesThroughAlias.toPath()))
        assertEquals(File(real, "projects").canonicalFile, ProjectWorkspacePolicy.projectsRoot(filesThroughAlias))
        val manager = ProjectManager(filesThroughAlias, Store(emptyList()))
        manager.init()
        assertEquals(File(real, "projects/default").canonicalFile, manager.activeRoot())
    }

    @Test fun replacingTheFilesDirectoryItselfWithASymlinkCannotPublishMetadata() = fixture { app ->
        val real = File(app, "real-app-files").apply { mkdirs() }
        val sentinel = real.resolve("user.txt").apply { writeText("unpublished work") }
        val alias = File(app, "replaced-files")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val failure = runCatching { FileProjectRecentsStore(alias).save(listOf("default")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("Workspace directory is unsafe"))
        assertEquals("unpublished work", sentinel.readText())
        assertFalse(real.resolve(".droide").exists())
        assertTrue(Files.isSymbolicLink(alias.toPath()))
    }

    @Test fun missingLegacyChildIsRejectedWithoutCopyingOrDeletingSiblingProjectData() = fixture { app ->
        val container = File(app, "projects").apply { mkdirs() }
        val sibling = project(app, "p-sibling")
        val loose = container.resolve("unattributed.zip").apply { writeText("keep outside repaired root") }
        val sentinel = File(sibling.rootPath, "keep.kt").apply { writeText("sibling") }
        val broken = DroideProject("p-repaired", "Previous root", container.absolutePath)
        val original = listOf(sibling, broken)
        val store = Store(original, broken.id)
        val manager = ProjectManager(app, store)
        val failure = runCatching { manager.init() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains(container.resolve(broken.id).path))
        assertTrue(manager.projects.value.isEmpty())
        assertNull(manager.activeId.value)
        assertEquals(0, store.saves)
        assertEquals(original, store.records)
        assertEquals(broken.id, store.active)
        assertFalse(container.resolve(broken.id).exists())
        assertFalse(container.resolve("default").exists())
        assertEquals("keep outside repaired root", loose.readText())
        assertEquals("sibling", sentinel.readText())
    }
}

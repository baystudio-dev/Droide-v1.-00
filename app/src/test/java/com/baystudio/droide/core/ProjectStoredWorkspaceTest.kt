package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectStoredWorkspaceTest {
    private class CallbackStore(val delegate: FileProjectStore) : ProjectStore by delegate {
        var writes = 0
        var beforeSave: (suspend () -> Unit)? = null
        var afterSave: (suspend () -> Unit)? = null
        override suspend fun save(all: List<DroideProject>) {
            writes++
            val before = beforeSave.also { beforeSave = null }
            before?.invoke()
            delegate.save(all)
            val after = afterSave.also { afterSave = null }
            after?.invoke()
        }
    }

    private class Recents : ProjectRecentsStore {
        var ids: List<String>? = null
        override suspend fun load() = ids
        override suspend fun save(ids: List<String>) { this.ids = ids }
    }

    private class Fixture(val app: File, val store: CallbackStore, val recents: Recents,
                          val manager: ProjectManager, val project: DroideProject) {
        val root = File(project.rootPath)
        val source = File(root, "source.txt")
        val catalog = File(app, ".droide/projects.json")
        suspend fun assertStored() {
            val expected = project.copy(workspaceRemoved = true)
            assertEquals(expected, manager.projects.value.single { it.id == project.id })
            assertEquals(expected, store.load().single { it.id == project.id })
            assertEquals(listOf(expected), manager.storedProjects.value)
            assertFalse(manager.recentProjects.value.any { it.id == project.id })
            assertFalse(manager.otherProjects.value.any { it.id == project.id })
            assertEquals("default", manager.activeId.value)
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-stored-project-").toFile()
        try {
            val store = CallbackStore(FileProjectStore(app))
            val recents = Recents()
            val manager = ProjectManager(app, store, recents).apply { init() }
            val project = manager.add("Sample", treeUri = "content://fixture/tree/project")
            manager.restoreToRecent(project.id)
            val f = Fixture(app, store, recents, manager, project)
            f.source.writeText("unpublished source")
            block(f)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private fun key(file: File) =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    private fun rawCatalog(app: File, id: String, root: File, flag: String?): File {
        val entry = buildJsonObject {
            put("id", id)
            put("name", "Saved project")
            put("rootPath", root.absolutePath)
            flag?.let { put("workspaceRemoved", Json.parseToJsonElement(it)) }
        }
        return File(app, ".droide/projects.json").apply {
            parentFile!!.mkdirs()
            writeText(JsonArray(listOf(entry)).toString())
        }
    }

    @Test fun legacyCatalogWithoutMembershipFieldRemainsOpenWithoutAReadTimeRewrite() = runBlocking {
        val app = Files.createTempDirectory("droide-legacy-membership-").toFile()
        try {
            val root = File(app, "projects/saved").apply { mkdirs() }
            val catalog = rawCatalog(app, "saved", root, null)
            val before = catalog.readBytes()
            val restored = FileProjectStore(app).load().single()
            assertFalse(restored.workspaceRemoved)
            assertEquals("saved", restored.id)
            assertEquals(root.absolutePath, restored.rootPath)
            assertArrayEquals(before, catalog.readBytes())
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun malformedMembershipValuesCannotSilentlyReopenAStoredProject() = runBlocking {
        val app = Files.createTempDirectory("droide-malformed-membership-").toFile()
        try {
            val root = File(app, "projects/saved").apply { mkdirs() }
            val source = File(root, "keep.txt").apply { writeText("keep original source") }
            for (flag in listOf("null", "\"true\"", "\"false\"", "0", "1", "[]", "{}")) {
                val catalog = rawCatalog(app, "saved", root, flag)
                val before = catalog.readBytes()
                assertThrows(IOException::class.java) { runBlocking { FileProjectStore(app).load() } }
                assertArrayEquals(before, catalog.readBytes())
                assertEquals("keep original source", source.readText())
            }
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun defaultCannotBeLoadedAsRemovedMembership() = runBlocking {
        val app = Files.createTempDirectory("droide-removed-default-").toFile()
        try {
            val root = File(app, "projects/default").apply { mkdirs() }
            val catalog = rawCatalog(app, "default", root, "true")
            val before = catalog.readBytes()
            assertThrows(IOException::class.java) { runBlocking { FileProjectStore(app).load() } }
            assertArrayEquals(before, catalog.readBytes())
            assertTrue(root.isDirectory)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun invalidStoredLocationCannotBeReboundToAnExistingSameNamedFolder() = fixture { f ->
        val entries = Json.parseToJsonElement(f.catalog.readText()).jsonArray
        val before = JsonArray(entries.map { value ->
            val record = value.jsonObject
            if (record["id"]?.jsonPrimitive?.content == f.project.id)
                JsonObject(record + mapOf("workspaceRemoved" to JsonPrimitive(true),
                    "rootPath" to JsonPrimitive(File(f.app, "outside-project-container").path)))
            else record
        }).toString()
        f.catalog.writeText(before)
        assertThrows(IOException::class.java) { runBlocking { FileProjectStore(f.app).load() } }
        assertEquals(before, f.catalog.readText())
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun removeRestartAndRestoreKeepIdentityPathsDraftsAndPendingCliWork() = fixture { f ->
        val preserved = listOf(
            f.source,
            File(f.root, ".droide/sessions/history.json"),
            File(f.app, ".droide/editor-recovery/${f.project.id}.bin"),
            File(f.app, ".droide/editor-recovery/${f.project.id}.blobs/pending.bin"),
            File(f.app, "agent-workspaces/persistent/pending-cli.txt"),
        )
        preserved.drop(1).forEachIndexed { index, file ->
            file.parentFile!!.mkdirs(); file.writeText("pending state $index")
        }
        val before = preserved.associateWith { Triple(key(it), it.lastModified(), it.readBytes().toList()) }
        val rootKey = key(f.root)
        val rootMtime = f.root.lastModified()

        assertEquals(f.project.copy(workspaceRemoved = true), f.manager.removeWorkspace(f.project.id))
        f.assertStored()
        val restarted = ProjectManager(f.app, recentsStore = f.recents).apply { init() }
        assertTrue(restarted.collectStartupGarbage().isEmpty())
        assertEquals(listOf(f.project.copy(workspaceRemoved = true)), restarted.storedProjects.value)
        assertEquals(f.project, restarted.restoreWorkspace(f.project.id))

        assertTrue(restarted.storedProjects.value.isEmpty())
        assertEquals(f.project, restarted.projects.value.single { it.id == f.project.id })
        assertEquals(f.project, FileProjectStore(f.app).load().single { it.id == f.project.id })
        assertEquals("default", restarted.activeId.value)
        assertFalse(restarted.recentProjects.value.any { it.id == f.project.id })
        assertTrue(restarted.otherProjects.value.any { it.id == f.project.id })
        assertEquals(rootKey, key(f.root))
        assertEquals(rootMtime, f.root.lastModified())
        assertEquals(before, preserved.associateWith { Triple(key(it), it.lastModified(), it.readBytes().toList()) })
        assertFalse(File(f.root.parentFile, ".delete-${f.project.id}").exists())
    }

    @Test fun missingStoredRootDoesNotBlockOpenStartupOrRestoreAnUnrelatedRetainedTree() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        val held = File(f.app, "held-source")
        Files.move(f.root.toPath(), held.toPath())
        val retained = File(f.root.parentFile, ".delete-${f.project.id}").apply { mkdir() }
        val retainedSource = File(retained, "keep.txt").apply { writeText("retained transaction evidence") }
        val retainedKey = key(retained)
        val defaultSource = File(f.app, "projects/default/keep.txt").apply { writeText("open default work") }

        val restarted = ProjectManager(f.app, recentsStore = f.recents).apply { init() }

        assertEquals("default", restarted.activeId.value)
        assertEquals(listOf(f.project.copy(workspaceRemoved = true)), restarted.storedProjects.value)
        assertFalse(f.root.exists())
        assertEquals(retainedKey, key(retained))
        assertEquals("retained transaction evidence", retainedSource.readText())
        assertEquals("open default work", defaultSource.readText())
        assertEquals("unpublished source", File(held, "source.txt").readText())
        assertTrue(restarted.collectStartupGarbage().isEmpty())
        assertFalse(f.root.exists())
    }

    @Test fun missingRootRestoreKeepsItsSavedRecordAndDoesNotCreateAnEmptyProject() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        val held = File(f.app, "held-source")
        Files.move(f.root.toPath(), held.toPath())
        val before = f.catalog.readBytes()
        val writes = f.store.writes
        assertThrows(Exception::class.java) { runBlocking { f.manager.restoreWorkspace(f.project.id) } }
        assertEquals(writes, f.store.writes)
        f.assertStored()
        assertArrayEquals(before, f.catalog.readBytes())
        assertFalse(f.root.exists())
        assertEquals("unpublished source", File(held, "source.txt").readText())
    }

    @Test fun symlinkRootRestoreDoesNotFollowTheReplacementOrForgetStoredMembership() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        val held = File(f.app, "held-source")
        Files.move(f.root.toPath(), held.toPath())
        val outside = File(f.app, "unrelated-source").apply { mkdir() }
        val unrelated = File(outside, "keep.txt").apply { writeText("unrelated source") }
        Files.createSymbolicLink(f.root.toPath(), outside.toPath())
        val before = f.catalog.readBytes()
        val writes = f.store.writes
        assertThrows(Exception::class.java) { runBlocking { f.manager.restoreWorkspace(f.project.id) } }
        assertEquals(writes, f.store.writes)
        assertTrue(f.manager.projects.value.single { it.id == f.project.id }.workspaceRemoved)
        assertEquals(listOf(f.project.copy(workspaceRemoved = true)), f.manager.storedProjects.value)
        assertArrayEquals(before, f.catalog.readBytes())
        assertEquals(outside.toPath(), Files.readSymbolicLink(f.root.toPath()))
        assertEquals("unrelated source", unrelated.readText())
        assertEquals("unpublished source", File(held, "source.txt").readText())
    }

    @Test fun fileAtTheOriginalRootIsNotReplacedByRestore() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        val held = File(f.app, "held-source")
        Files.move(f.root.toPath(), held.toPath())
        f.root.writeText("new file at the saved path")
        val rootKey = key(f.root)
        val before = f.catalog.readBytes()
        assertThrows(Exception::class.java) { runBlocking { f.manager.restoreWorkspace(f.project.id) } }
        f.assertStored()
        assertEquals(rootKey, key(f.root))
        assertEquals("new file at the saved path", f.root.readText())
        assertArrayEquals(before, f.catalog.readBytes())
        assertEquals("unpublished source", File(held, "source.txt").readText())
    }

    @Test fun repeatedRemoveAndRestoreDoNotRewriteUnchangedMetadata() = fixture { f ->
        val stored = f.manager.removeWorkspace(f.project.id)
        val removedWrites = f.store.writes
        val storedBytes = f.catalog.readBytes()
        assertEquals(stored, f.manager.removeWorkspace(f.project.id))
        assertEquals(removedWrites, f.store.writes)
        assertArrayEquals(storedBytes, f.catalog.readBytes())
        assertEquals(f.project, f.manager.restoreWorkspace(f.project.id))
        val restoredWrites = f.store.writes
        val restoredBytes = f.catalog.readBytes()
        assertEquals(f.project, f.manager.restoreWorkspace(f.project.id))
        assertEquals(restoredWrites, f.store.writes)
        assertArrayEquals(restoredBytes, f.catalog.readBytes())
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun rejectedRemovalCommitKeepsOpenMembershipAndOriginalFiles() = fixture { f ->
        val failure = IOException("removal commit rejected")
        val before = f.catalog.readBytes()
        f.store.beforeSave = { throw failure }
        val caught = assertThrows(IOException::class.java) { runBlocking { f.manager.removeWorkspace(f.project.id) } }
        assertSame(failure, caught)
        assertEquals(f.project, f.manager.projects.value.single { it.id == f.project.id })
        assertTrue(f.manager.storedProjects.value.isEmpty())
        assertTrue(f.manager.recentProjects.value.any { it.id == f.project.id })
        assertArrayEquals(before, f.catalog.readBytes())
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun rejectedRestoreCommitKeepsStoredMembershipAndOriginalFiles() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        val failure = IOException("restore commit rejected")
        val before = f.catalog.readBytes()
        f.store.beforeSave = { throw failure }
        val caught = assertThrows(IOException::class.java) { runBlocking { f.manager.restoreWorkspace(f.project.id) } }
        assertSame(failure, caught)
        f.assertStored()
        assertArrayEquals(before, f.catalog.readBytes())
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun committedRemovalLostReturnPublishesThePersistedMembershipFlag() = fixture { f ->
        f.store.afterSave = { throw IOException("removal return lost after commit") }
        assertEquals(f.project.copy(workspaceRemoved = true), f.manager.removeWorkspace(f.project.id))
        f.assertStored()
        assertFalse(f.manager.selectionIsUncertain)
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun committedRestoreLostReturnPublishesTheSameOriginalProject() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        f.store.afterSave = { throw IOException("restore return lost after commit") }
        assertEquals(f.project, f.manager.restoreWorkspace(f.project.id))
        assertEquals(f.project, f.store.load().single { it.id == f.project.id })
        assertEquals(f.project, f.manager.projects.value.single { it.id == f.project.id })
        assertTrue(f.manager.storedProjects.value.isEmpty())
        assertFalse(f.manager.selectionIsUncertain)
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun cancellationAfterRestoreCommitPublishesBeforeRethrowing() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        val cancellation = CancellationException("restore interrupted after commit")
        f.store.afterSave = { throw cancellation }
        val caught = assertThrows(CancellationException::class.java) { runBlocking { f.manager.restoreWorkspace(f.project.id) } }
        assertSame(cancellation, caught)
        assertEquals(f.project, f.store.load().single { it.id == f.project.id })
        assertEquals(f.project, f.manager.projects.value.single { it.id == f.project.id })
        assertTrue(f.manager.storedProjects.value.isEmpty())
        assertEquals("default", f.manager.activeId.value)
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun storedProjectCannotBypassRestoreThroughSelectionRecentsOrDuplicateRegistration() = fixture { f ->
        f.manager.removeWorkspace(f.project.id)
        val before = f.catalog.readBytes()
        val writes = f.store.writes
        assertThrows(Exception::class.java) { runBlocking { f.manager.switch(f.project.id) } }
        assertThrows(Exception::class.java) { runBlocking { f.manager.restoreToRecent(f.project.id) } }
        assertThrows(Exception::class.java) { runBlocking { f.manager.add("Duplicate", rootPath = f.root.path) } }
        assertEquals(writes, f.store.writes)
        f.assertStored()
        assertArrayEquals(before, f.catalog.readBytes())
        assertEquals("unpublished source", f.source.readText())
    }
}

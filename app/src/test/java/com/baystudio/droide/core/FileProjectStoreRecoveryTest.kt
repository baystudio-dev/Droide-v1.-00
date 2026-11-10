package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileProjectStoreRecoveryTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("droide-catalog-recovery-").toFile()
        try { block(dir) } finally { PathSecurity.deleteTreeNoFollow(dir) }
    }

    private fun project(dir: File, name: String = "p-owned"): File =
        File(dir, "projects/$name").apply { mkdirs(); resolve("source.kt").writeText("keep source") }

    private fun catalog(dir: File, text: String): File = File(dir, ".droide/projects.json").apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private suspend fun assertCatalogRejected(dir: File) {
        try { FileProjectStore(dir).load(); fail("Invalid catalog accepted as an empty installation") }
        catch (failure: IOException) { assertTrue(failure.message.orEmpty().contains("preserved")) }
    }

    @Test fun newInstallationWithoutProjectFilesLoadsEmptyCatalog() = fixture { dir ->
        assertTrue(FileProjectStore(dir).load().isEmpty())
        assertFalse(File(dir, ".droide/projects.json").exists())
    }

    @Test fun corruptCatalogIsNotReplacedAndProjectFilesStayAvailable() = fixture { dir ->
        val source = project(dir).resolve("source.kt")
        val saved = catalog(dir, "[{truncated")
        assertCatalogRejected(dir)
        assertEquals("[{truncated", saved.readText())
        assertEquals("keep source", source.readText())
        assertFalse(File(dir, "projects/.delete-p-owned").exists())
    }

    @Test fun oversizedCatalogIsNotTreatedAsAnEmptyInstallation() = fixture { dir ->
        val source = project(dir).resolve("source.kt")
        val saved = catalog(dir, " ".repeat(2_000_001))
        assertCatalogRejected(dir)
        assertEquals(2_000_001L, saved.length())
        assertEquals("keep source", source.readText())
    }

    @Test fun missingCatalogWithExistingProjectIsRejectedWithoutTouchingIt() = fixture { dir ->
        val source = project(dir).resolve("source.kt")
        assertCatalogRejected(dir)
        assertEquals("keep source", source.readText())
        assertFalse(File(dir, ".droide/projects.json").exists())
    }

    @Test fun missingCatalogWithDetachedBackupKeepsTheBackup() = fixture { dir ->
        val backup = project(dir, ".delete-p-owned")
        assertCatalogRejected(dir)
        assertEquals("keep source", backup.resolve("source.kt").readText())
    }

    @Test fun wrongCatalogRootDoesNotDiscardExistingProjects() = fixture { dir ->
        val source = project(dir).resolve("source.kt")
        for (text in listOf("{}", "null", "\"projects\"")) {
            val saved = catalog(dir, text)
            assertCatalogRejected(dir)
            assertEquals(text, saved.readText())
            assertEquals("keep source", source.readText())
        }
    }

    @Test fun malformedEntryCannotSilentlyDropPartOfTheCatalog() = fixture { dir ->
        val source = project(dir).resolve("source.kt")
        for (bad in listOf("null", "{}", "{\"id\":23}", "{\"id\":\"../escape\"}", "{\"id\":\"p-other\",\"treeUri\":{}}")) {
            val text = "[{\"id\":\"p-owned\",\"name\":\"Owned\"},$bad]"
            val saved = catalog(dir, text)
            assertCatalogRejected(dir)
            assertEquals(text, saved.readText())
            assertEquals("keep source", source.readText())
        }
    }

    @Test fun duplicateProjectIdsDoNotSilentlyHideAProject() = fixture { dir ->
        val text = "[{\"id\":\"p-owned\"},{\"id\":\"p-owned\",\"rootPath\":\"other\"}]"
        val saved = catalog(dir, text)
        assertCatalogRejected(dir)
        assertEquals(text, saved.readText())
    }

    @Test fun directoryInsteadOfCatalogIsRejected() = fixture { dir ->
        File(dir, ".droide/projects.json").mkdirs()
        assertCatalogRejected(dir)
        assertTrue(File(dir, ".droide/projects.json").isDirectory)
    }

    @Test fun catalogSymlinkDoesNotFollowOrOverwriteItsTarget() = fixture { dir ->
        val original = File(dir, "saved-catalog.json").apply { writeText("[]") }
        val saved = File(dir, ".droide/projects.json").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(saved.toPath(), original.toPath())
        assertCatalogRejected(dir)
        assertEquals("[]", original.readText())
        assertTrue(Files.isSymbolicLink(saved.toPath()))
    }

    @Test fun legacyCatalogFieldsAndNullOptionalValuesStillLoad() = fixture { dir ->
        val root = project(dir)
        catalog(dir, "[{\"id\":\"p-owned\",\"name\":\"Owned\",\"treeUri\":null,\"trusted\":true}]")
        val restored = FileProjectStore(dir).load().single()
        assertEquals("p-owned", restored.id)
        assertEquals("Owned", restored.name)
        assertEquals(root.canonicalPath, restored.rootPath)
        assertNull(restored.treeUri)
    }

    @Test fun validEmptyCatalogDoesNotAuthorizeDeletingUnregisteredSource() = fixture { dir ->
        val root = project(dir)
        catalog(dir, "[]")
        val loaded = FileProjectStore(dir).load()
        val pending = ProjectWorkspaceMaintenance.prepare(File(dir, "projects"), loaded.associate { it.id to File(it.rootPath) })
        assertTrue(pending.isEmpty())
        ProjectWorkspaceMaintenance.collect(File(dir, "projects"), pending)
        assertEquals("keep source", root.resolve("source.kt").readText())
    }

    @Test fun savedCatalogRoundTripsProjectAndActiveIdentity() = fixture { dir ->
        val root = project(dir)
        val expected = DroideProject("p-owned", "Owned", root.canonicalPath, "content://documents/tree/owned")
        val store = FileProjectStore(dir)
        store.save(listOf(expected))
        store.saveActiveId(expected.id)
        assertEquals(listOf(expected), store.load())
        assertEquals(expected.id, store.loadActiveId())
    }
}

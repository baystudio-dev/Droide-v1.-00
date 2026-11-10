package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProjectStoreNoOpTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalog get() = File(temp.root, ".droide/projects.json")
    private suspend fun seed(): ProjectCatalogSnapshot {
        val project = DroideProject("sample", "Sample", File(temp.root, "projects/sample").apply { check(mkdirs()) }.canonicalPath)
        return FileProjectStore(temp.root).also { it.save(listOf(project)) }.loadCatalog()
    }
    private fun refusingPublicationStore() = FileProjectStore(temp.root, object : StreamPublicationDurability {
        override fun forceFile(channel: FileChannel) { throw IOException("Unchanged catalog unexpectedly attempted file fsync") }
        override fun forceDirectory(directory: File) { throw IOException("Unchanged catalog unexpectedly attempted directory fsync") }
    }, replace = { _, _, _ -> throw AssertionError("Unchanged catalog unexpectedly attempted replacement") })

    @Test fun unchangedVersionedSaveDoesNotPublishOrRequireWriteDurability() = runBlocking {
        val snapshot = seed()
        val before = catalog.readBytes()
        val time = FileTime.fromMillis(1_000)
        Files.setLastModifiedTime(catalog.toPath(), time)
        val identity = Files.readAttributes(catalog.toPath(), BasicFileAttributes::class.java).fileKey()
        val children = catalog.parentFile!!.list()!!.toSet()
        val store = refusingPublicationStore()
        repeat(3) { assertEquals(snapshot.revision, store.saveCatalog(snapshot.projects, snapshot.revision)) }
        assertArrayEquals(before, catalog.readBytes())
        assertEquals(time, Files.getLastModifiedTime(catalog.toPath()))
        assertEquals(identity, Files.readAttributes(catalog.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(children, catalog.parentFile!!.list()!!.toSet())
    }

    @Test fun unchangedLegacySaveAlsoAvoidsReplacingTheCatalog() = runBlocking {
        val snapshot = seed()
        val identity = Files.readAttributes(catalog.toPath(), BasicFileAttributes::class.java).fileKey()
        refusingPublicationStore().save(snapshot.projects)
        assertEquals(snapshot, FileProjectStore(temp.root).loadCatalog())
        assertEquals(identity, Files.readAttributes(catalog.toPath(), BasicFileAttributes::class.java).fileKey())
    }

    @Test fun reopeningProjectManagerDoesNotRepublishAnUnchangedCatalog() = runBlocking {
        val initial = ProjectManager(temp.root).apply { init() }
        val bytes = catalog.readBytes()
        val identity = Files.readAttributes(catalog.toPath(), BasicFileAttributes::class.java).fileKey()
        var catalogReplacements = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            if (to.fileName.toString() == "projects.json") catalogReplacements++
            Files.move(from, to, *options)
        }
        repeat(3) {
            val reopened = ProjectManager(temp.root, store).apply { init() }
            assertEquals(initial.projects.value, reopened.projects.value)
            assertEquals(initial.activeId.value, reopened.activeId.value)
        }
        assertEquals(0, catalogReplacements)
        assertArrayEquals(bytes, catalog.readBytes())
        assertEquals(identity, Files.readAttributes(catalog.toPath(), BasicFileAttributes::class.java).fileKey())
    }

    @Test fun identicalProposalCannotBypassAStaleExpectedRevision() = runBlocking {
        val old = seed()
        val store = FileProjectStore(temp.root)
        val external = old.projects.map { it.copy(name = "External") }
        store.save(external)
        val preserved = catalog.readBytes()
        val failure = runCatching { store.saveCatalog(external, old.revision) }.exceptionOrNull()
        assertTrue("Stale revision must remain a typed conflict: $failure", failure is ProjectCatalogConflictException)
        assertArrayEquals(preserved, catalog.readBytes())
        assertEquals(1, catalog.parentFile!!.list()!!.count { it.startsWith(".projects.json.tmp-") })
    }

    @Test fun equivalentParsedDataWithDifferentBytesStillUsesThePublicationPath() = runBlocking {
        seed()
        val canonical = catalog.readText()
        catalog.writeText("\n$canonical\n")
        var replacements = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            replacements++
            Files.move(from, to, *options)
        }
        val observed = store.loadCatalog()
        val revision = store.saveCatalog(observed.projects, observed.revision)
        assertEquals(1, replacements)
        assertEquals(canonical, catalog.readText())
        assertEquals(DiskFileVersion.fromText(canonical), revision)
        assertNotEquals(observed.revision, revision)
    }

    @Test fun unchangedSaveStillRejectsASymlinkMetadataDirectory() = runBlocking {
        val observed = seed()
        val metadata = checkNotNull(catalog.parentFile)
        val retained = File(temp.root, "retained-metadata")
        Files.move(metadata.toPath(), retained.toPath())
        Files.createSymbolicLink(metadata.toPath(), retained.toPath())
        val bytes = File(retained, "projects.json").readBytes()
        try {
            val failure = runCatching { FileProjectStore(temp.root).saveCatalog(observed.projects, observed.revision) }.exceptionOrNull()
            assertTrue("No-op must retain directory safety checks: $failure", failure is IllegalStateException || failure is IOException)
            assertTrue(Files.isSymbolicLink(metadata.toPath()))
            assertArrayEquals(bytes, File(retained, "projects.json").readBytes())
            assertEquals(setOf("projects.json"), retained.list()!!.toSet())
        } finally { Files.deleteIfExists(metadata.toPath()) }
    }
}

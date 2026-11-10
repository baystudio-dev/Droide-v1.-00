package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMetadataBudgetTest {
    @get:Rule val temp = TemporaryFolder()

    private fun project() = DroideProject("p-owned", "Owned",
        File(temp.root, "projects/p-owned").apply { mkdirs(); resolve("source.kt").writeText("unsynced source") }.canonicalPath,
        treeUri = "")

    private fun encode(projects: List<DroideProject>) = JsonArray(projects.map { p -> buildJsonObject {
        put("id", p.id); put("name", p.name); put("rootPath", p.rootPath)
        p.treeUri?.let { put("treeUri", it) }
        if (p.workspaceRemoved) put("workspaceRemoved", true)
    } }).toString()

    private fun sized(project: DroideProject, bytes: Int, prefix: String = ""): DroideProject {
        val overhead = encode(listOf(project.copy(treeUri = ""))).toByteArray(Charsets.UTF_8).size
        val padding = bytes - overhead - JsonPrimitive(prefix).toString().toByteArray(Charsets.UTF_8).size + 2
        require(padding >= 0)
        return project.copy(treeUri = prefix + "a".repeat(padding)).also {
            assertEquals(bytes, encode(listOf(it)).toByteArray(Charsets.UTF_8).size)
        }
    }

    private data class Entry(val hash: String, val key: String, val modified: Long)
    private fun metadata(): Map<String, Entry> = File(temp.root, ".droide").listFiles().orEmpty()
        .filter { it.isFile }.associate { file ->
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            file.name to Entry(java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }, attrs.fileKey().toString(), attrs.lastModifiedTime().toMillis())
        }

    private fun assertRejected(failure: Throwable?) {
        assertTrue("Oversize metadata must fail before publication, got $failure", failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("preserved"))
    }

    @Test fun firstOversizeCatalogDoesNotCreateMetadataOrCallThePublisher() = runBlocking {
        val p = sized(project(), 2_000_001)
        var calls = 0
        val io = object : StreamPublicationDurability {
            override fun forceFile(channel: FileChannel) { calls++ }
            override fun forceDirectory(directory: File) { calls++ }
        }
        val store = FileProjectStore(temp.root, io, replace = { _, _, _ -> calls++ })
        assertRejected(runCatching { store.save(listOf(p)) }.exceptionOrNull())
        assertEquals(0, calls); assertFalse(File(temp.root, ".droide").exists())
        assertEquals("unsynced source", File(p.rootPath, "source.kt").readText())
    }

    @Test fun exactCatalogByteLimitCanBeSavedAndLoadedByANewStore() = runBlocking {
        val p = sized(project(), 2_000_000)
        FileProjectStore(temp.root).save(listOf(p))
        assertEquals(2_000_000L, File(temp.root, ".droide/projects.json").length())
        assertEquals(listOf(p), FileProjectStore(temp.root).load())
    }

    @Test fun oneByteCatalogOverflowKeepsTheOldCatalogAndUnpublishedStage() = runBlocking {
        val p = project(); val store = FileProjectStore(temp.root); store.save(listOf(p))
        File(temp.root, ".droide/.projects.json.tmp-retained").writeText("earlier unfinished proposal")
        val before = metadata()
        assertRejected(runCatching { store.save(listOf(sized(p, 2_000_001))) }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(listOf(p), FileProjectStore(temp.root).load())
        assertEquals("unsynced source", File(p.rootPath, "source.kt").readText())
    }

    @Test fun unicodeCatalogUsesUtf8BytesRatherThanCharacterCount() = runBlocking {
        val p = project(); val atLimit = sized(p, 2_000_000, "🌱漢é")
        FileProjectStore(temp.root).save(listOf(atLimit))
        assertEquals(listOf(atLimit), FileProjectStore(temp.root).load())
        val before = metadata(); val over = atLimit.copy(treeUri = atLimit.treeUri + "é")
        assertTrue(encode(listOf(over)).length < 2_000_000)
        assertRejected(runCatching { FileProjectStore(temp.root).save(listOf(over)) }.exceptionOrNull())
        assertEquals(before, metadata())
    }

    @Test fun jsonEscapingCountsTowardTheStoredCatalogLimit() = runBlocking {
        val p = project(); FileProjectStore(temp.root).save(listOf(p)); val before = metadata()
        val escaped = p.copy(treeUri = "\n".repeat(1_000_000))
        assertTrue(escaped.treeUri!!.toByteArray().size < 2_000_000)
        assertTrue(encode(listOf(escaped)).toByteArray().size > 2_000_000)
        assertRejected(runCatching { FileProjectStore(temp.root).save(listOf(escaped)) }.exceptionOrNull())
        assertEquals(before, metadata())
    }

    @Test fun catalogLimitAppliesToTheCombinedProjectList() = runBlocking {
        val p = project(); val other = p.copy(id = "p-other", rootPath = File(temp.root, "projects/p-other").absolutePath)
        val all = listOf(sized(p, 1_000_001), sized(other, 1_000_001))
        assertTrue(all.all { encode(listOf(it)).toByteArray().size < 2_000_000 })
        FileProjectStore(temp.root).save(listOf(p)); val before = metadata()
        assertRejected(runCatching { FileProjectStore(temp.root).save(all) }.exceptionOrNull())
        assertEquals(before, metadata())
    }

    @Test fun rejectedRegistrationLeavesTheManagerAndSavedProjectsUsable() = runBlocking {
        val manager = ProjectManager(temp.root).apply { init() }
        val owned = manager.add("Owned")
        val source = File(owned.rootPath, "unpublished.kt").apply { writeText("do not lose") }
        val proposal = File(temp.root, "projects/proposed").apply { mkdirs(); resolve("draft.kt").writeText("keep draft") }
        val before = metadata(); val projects = manager.projects.value
        assertRejected(runCatching { manager.add("Too large", proposal.path, "x".repeat(2_000_000)) }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(projects, manager.projects.value)
        assertFalse(manager.selectionIsUncertain)
        assertEquals("do not lose", source.readText()); assertEquals("keep draft", proposal.resolve("draft.kt").readText())
        assertEquals(projects, ProjectManager(temp.root).apply { init() }.projects.value)
        val added = manager.add("Still usable")
        assertTrue(FileProjectStore(temp.root).load().any { it.id == added.id })
    }

    @Test fun activeIdExactByteLimitIncludesItsTrailingNewline() = runBlocking {
        val id = "a".repeat(511)
        FileProjectStore(temp.root).saveActiveId(id)
        assertEquals(512L, File(temp.root, ".droide/active_project.txt").length())
        assertEquals(id, FileProjectStore(temp.root).loadActiveId())
    }

    @Test fun activeIdOverflowCannotEraseThePreviousSelectionOnRestart() = runBlocking {
        FileProjectStore(temp.root).saveActiveId("p-owned"); val before = metadata()
        assertRejected(runCatching { FileProjectStore(temp.root).saveActiveId("a".repeat(512)) }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals("p-owned", FileProjectStore(temp.root).loadActiveId())
    }

    @Test fun multibyteActiveIdIsCheckedByEncodedBytes() = runBlocking {
        val id = "🌱".repeat(127) + "abc"
        FileProjectStore(temp.root).saveActiveId(id)
        assertEquals(id, FileProjectStore(temp.root).loadActiveId())
        val before = metadata()
        assertRejected(runCatching { FileProjectStore(temp.root).saveActiveId(id + "é") }.exceptionOrNull())
        assertEquals(before, metadata())
    }

    @Test fun rejectedFirstActiveIdDoesNotCreateAMetadataDirectory() = runBlocking {
        assertRejected(runCatching { FileProjectStore(temp.root).saveActiveId("z".repeat(512)) }.exceptionOrNull())
        assertFalse(File(temp.root, ".droide").exists())
    }

    @Test fun smallerCatalogRemainsWritableAfterARejectedOversizeAttempt() = runBlocking {
        val p = project(); val store = FileProjectStore(temp.root); store.save(listOf(p))
        assertRejected(runCatching { store.save(listOf(sized(p, 2_000_001))) }.exceptionOrNull())
        val changed = p.copy(name = "Renamed")
        store.save(listOf(changed))
        assertEquals(listOf(changed), FileProjectStore(temp.root).load())
    }
}

package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectCatalogCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()

    private fun project(id: String = "p-owned", removed: Boolean = false) = DroideProject(
        id, "Owned", File(temp.root, "projects/$id").apply {
            mkdirs(); resolve("unpublished.kt").writeText("keep CLI and editor work")
        }.canonicalPath, workspaceRemoved = removed)

    private fun entry(p: DroideProject) = buildJsonObject {
        put("id", p.id); put("name", p.name); put("rootPath", p.rootPath)
        p.treeUri?.let { put("treeUri", it) }
        if (p.workspaceRemoved) put("workspaceRemoved", true)
    }

    private fun catalog(text: String) = File(temp.root, ".droide/projects.json").apply {
        requireNotNull(parentFile).mkdirs(); writeText(text)
    }

    private fun future(p: DroideProject, value: JsonElement = JsonPrimitive("retained-generation")) =
        JsonArray(listOf(JsonObject(entry(p) + ("expectedCopyJournal" to value)))).toString()

    private fun snapshot(): Map<String, String> = temp.root.walkTopDown().associate { file ->
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val value = when {
            a.isSymbolicLink -> "link:${Files.readSymbolicLink(file.toPath())}"
            a.isDirectory -> "directory"
            else -> MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } + ":${a.lastModifiedTime()}"
        }
        file.relativeTo(temp.root).path to "${a.fileKey()}:$value"
    }

    private class Writes : StreamPublicationDurability {
        var calls = 0
        override fun forceFile(channel: FileChannel) { calls++ }
        override fun forceDirectory(directory: File) { calls++ }
    }

    private fun rejectingStore(writes: Writes) = FileProjectStore(temp.root, writes, replace = { _, _, _ ->
        writes.calls++
        fail("Rejected metadata must not reach the publisher")
    })

    private fun rejected(failure: Throwable?, unsupported: Boolean = false) {
        assertTrue("Expected a recoverable catalog error, got $failure", failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("preserved"))
        if (unsupported) assertTrue(failure?.message.orEmpty().contains("compatible Droide version"))
    }

    @Test fun unknownEntryFieldNeverBecomesAReadableLossyCatalog() = runBlocking {
        val p = project()
        for (value in listOf(JsonNull, JsonPrimitive(3), JsonArray(emptyList()), buildJsonObject { put("generation", "g1") })) {
            catalog(future(p, value)); val before = snapshot()
            rejected(runCatching { FileProjectStore(temp.root).load() }.exceptionOrNull(), unsupported = true)
            assertEquals(before, snapshot())
        }
    }

    @Test fun unknownFieldInLaterEntryRejectsTheWholeCatalog() = runBlocking {
        val p = project(); val other = project("p-other")
        catalog(JsonArray(listOf(entry(p), JsonObject(entry(other) + ("future" to JsonPrimitive(true))))).toString())
        val before = snapshot()
        rejected(runCatching { FileProjectStore(temp.root).load() }.exceptionOrNull(), unsupported = true)
        assertEquals(before, snapshot())
    }

    @Test fun startupDoesNotNormalizeOrCreateDefaultForUnsupportedCatalog() = runBlocking {
        catalog(future(project())); val before = snapshot()
        val manager = ProjectManager(temp.root)
        rejected(runCatching { manager.init() }.exceptionOrNull(), unsupported = true)
        assertTrue(manager.projects.value.isEmpty()); assertNull(manager.active())
        assertEquals(before, snapshot()); assertFalse(File(temp.root, "projects/default").exists())
    }

    @Test fun readOnlyRecoveryAlsoRefusesUnknownAuthorityFields() = runBlocking {
        val p = project(); catalog(future(p))
        File(temp.root, ".droide/active_project.txt").writeText(p.id)
        val before = snapshot(); val manager = ProjectManager(temp.root)
        rejected(runCatching { manager.init(readOnlyExisting = true) }.exceptionOrNull(), unsupported = true)
        assertNull(manager.active()); assertEquals(before, snapshot())
    }

    @Test fun externalFormatChangeAfterLoadIsNotErasedByTheNextSave() = runBlocking {
        val p = project(); catalog(JsonArray(listOf(entry(p))).toString())
        val writes = Writes(); val store = rejectingStore(writes)
        assertEquals(listOf(p), store.load())
        val changed = CountDownLatch(1)
        val writer = thread { catalog(future(p)); changed.countDown() }
        assertTrue(changed.await(5, TimeUnit.SECONDS)); writer.join()
        val before = snapshot()
        rejected(runCatching { store.save(listOf(p.copy(name = "Changed"))) }.exceptionOrNull(), unsupported = true)
        assertEquals(0, writes.calls); assertEquals(before, snapshot())
    }

    @Test fun aFreshWriterCannotBypassTheCompatibilityRead() = runBlocking {
        val p = project(); catalog(future(p))
        File(temp.root, ".droide/.projects.json.tmp-retained").writeText("earlier pending registration")
        val before = snapshot(); val writes = Writes()
        rejected(runCatching { rejectingStore(writes).save(listOf(p)) }.exceptionOrNull(), unsupported = true)
        assertEquals(0, writes.calls); assertEquals(before, snapshot())
    }

    @Test fun futureVersionEnvelopeIsPreservedByReadersAndWriters() = runBlocking {
        val p = project()
        catalog(buildJsonObject { put("version", 2); put("projects", JsonArray(listOf(entry(p)))) }.toString())
        val before = snapshot(); val writes = Writes(); val store = rejectingStore(writes)
        rejected(runCatching { store.load() }.exceptionOrNull(), unsupported = true)
        rejected(runCatching { store.save(listOf(p)) }.exceptionOrNull(), unsupported = true)
        assertEquals(0, writes.calls); assertEquals(before, snapshot())
    }

    @Test fun malformedExistingCatalogCannotBeReplacedWithoutRecovery() = runBlocking {
        val p = project()
        for (text in listOf("[{truncated", "[null]", "[{\"id\":23}]", "[{\"id\":\"p-owned\"},{\"id\":\"p-owned\"}]")) {
            catalog(text); val before = snapshot(); val writes = Writes()
            rejected(runCatching { rejectingStore(writes).save(listOf(p)) }.exceptionOrNull())
            assertEquals(0, writes.calls); assertEquals(before, snapshot())
        }
    }

    @Test fun oversizedExistingCatalogIsPreservedBeforeAnyWrite() = runBlocking {
        val p = project(); catalog(" ".repeat(2_000_001))
        val before = snapshot(); val writes = Writes()
        rejected(runCatching { rejectingStore(writes).save(listOf(p)) }.exceptionOrNull())
        assertEquals(0, writes.calls); assertEquals(before, snapshot())
    }

    @Test fun malformedUtf8ExistingCatalogIsPreserved() = runBlocking {
        val p = project(); catalog("").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        val before = snapshot(); val writes = Writes()
        rejected(runCatching { rejectingStore(writes).save(listOf(p)) }.exceptionOrNull())
        assertEquals(0, writes.calls); assertEquals(before, snapshot())
    }

    @Test fun catalogSymlinkIsNeitherFollowedNorReplacedOnSave() = runBlocking {
        val p = project(); val target = File(temp.root, "other.json").apply { writeText("other metadata") }
        val link = File(temp.root, ".droide/projects.json").apply { requireNotNull(parentFile).mkdirs() }
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val before = snapshot(); val writes = Writes()
        rejected(runCatching { rejectingStore(writes).save(listOf(p)) }.exceptionOrNull())
        assertEquals(0, writes.calls); assertEquals(before, snapshot())
    }

    @Test fun catalogDirectoryDoesNotReachThePublisher() = runBlocking {
        val p = project(); File(temp.root, ".droide/projects.json").mkdirs()
        val before = snapshot(); val writes = Writes()
        rejected(runCatching { rejectingStore(writes).save(listOf(p)) }.exceptionOrNull())
        assertEquals(0, writes.calls); assertEquals(before, snapshot())
    }

    @Test fun managerStopsFurtherRegistrationAfterAnExternalFormatChange() = runBlocking {
        val manager = ProjectManager(temp.root).apply { init() }
        val p = manager.add("Owned"); val old = manager.projects.value
        val text = JsonArray(old.map { if (it.id == p.id) JsonObject(entry(it) + ("future" to JsonPrimitive(2))) else entry(it) }).toString()
        catalog(text); val proposal = project("proposal")
        assertTrue(runCatching { manager.add("Pending", proposal.rootPath) }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertTrue(manager.selectionIsUncertain); assertEquals(old, manager.projects.value)
        assertEquals(text, File(temp.root, ".droide/projects.json").readText())
        assertEquals("keep CLI and editor work", File(proposal.rootPath, "unpublished.kt").readText())
        assertTrue(runCatching { manager.add("Must remain blocked") }.exceptionOrNull() is ProjectCatalogUncertainException)
    }

    @Test fun oldestMinimalEntryRetainsItsSupportedDefaults() = runBlocking {
        val p = project(); catalog("[{\"id\":\"${p.id}\"}]")
        val before = snapshot(); val loaded = FileProjectStore(temp.root).load().single()
        assertEquals(p.copy(name = "Project"), loaded); assertEquals(before, snapshot())
    }

    @Test fun recognizedNullableFieldsStayCompatible() = runBlocking {
        val p = project(); catalog("[{\"id\":\"${p.id}\",\"name\":null,\"rootPath\":null,\"treeUri\":null,\"workspaceRemoved\":false}]")
        val store = FileProjectStore(temp.root); val loaded = store.load()
        assertEquals(listOf(p.copy(name = "Project")), loaded)
        store.save(loaded); assertEquals(loaded, FileProjectStore(temp.root).load())
    }

    @Test fun legacyTrustBooleansAndNullRemainInertCompatibilityFields() = runBlocking {
        val p = project()
        for (value in listOf(JsonPrimitive(true), JsonPrimitive(false), JsonNull)) {
            catalog(JsonArray(listOf(JsonObject(entry(p) + ("trusted" to value)))).toString())
            val store = FileProjectStore(temp.root)
            assertEquals(listOf(p), store.load())
            store.save(listOf(p)); assertEquals(listOf(p), FileProjectStore(temp.root).load())
            assertFalse(File(temp.root, ".droide/permission-policies-v1").exists())
        }
    }

    @Test fun structuredOrStringLegacyTrustCannotHideUnknownMetadata() = runBlocking {
        val p = project()
        for (value in listOf(buildJsonObject { put("generation", "g1") }, JsonPrimitive("true"), JsonPrimitive(1))) {
            catalog(JsonArray(listOf(JsonObject(entry(p) + ("trusted" to value)))).toString())
            val before = snapshot(); val writes = Writes(); val store = rejectingStore(writes)
            rejected(runCatching { store.load() }.exceptionOrNull(), unsupported = true)
            rejected(runCatching { store.save(listOf(p)) }.exceptionOrNull(), unsupported = true)
            assertEquals(0, writes.calls); assertEquals(before, snapshot())
        }
    }

    @Test fun storedWorkspaceMembershipAndUriSurviveNormalUpdates() = runBlocking {
        val p = project(removed = true).copy(treeUri = "content://provider/tree/folder")
        catalog(JsonArray(listOf(entry(p))).toString())
        val after = p.copy(name = "Proyek 🌱")
        FileProjectStore(temp.root).save(listOf(after))
        assertEquals(listOf(after), FileProjectStore(temp.root).load())
        assertEquals("keep CLI and editor work", File(p.rootPath, "unpublished.kt").readText())
    }

    @Test fun supportedLegacyRootNormalizationStillWorks() = runBlocking {
        val p = project(); catalog(JsonArray(listOf(entry(p.copy(rootPath = "/old-install/projects/${p.id}")))).toString())
        val store = FileProjectStore(temp.root); val loaded = store.load()
        assertEquals(listOf(p), loaded); store.save(loaded)
        assertEquals(loaded, FileProjectStore(temp.root).load())
    }

    @Test fun firstInstallationAndRestartKeepExistingBehavior() = runBlocking {
        val first = ProjectManager(temp.root).apply { init() }
        val p = first.add("Usable"); first.switch(p.id)
        val restarted = ProjectManager(temp.root).apply { init() }
        assertEquals(first.projects.value, restarted.projects.value); assertEquals(p, restarted.active())
    }

    @Test fun explicitRepairWithSupportedBytesAllowsTheSameStoreToRetry() = runBlocking {
        val p = project(); catalog(future(p)); val store = FileProjectStore(temp.root)
        rejected(runCatching { store.save(listOf(p)) }.exceptionOrNull(), unsupported = true)
        catalog(JsonArray(listOf(entry(p))).toString())
        store.save(listOf(p.copy(name = "Recovered")))
        assertEquals("Recovered", FileProjectStore(temp.root).load().single().name)
    }

    @Test fun unsupportedFieldDoesNotProduceAnUnboundedErrorMessage() = runBlocking {
        val p = project()
        catalog(JsonArray(listOf(JsonObject(entry(p) + ("x".repeat(100_000) to JsonPrimitive("private value"))))).toString())
        val before = snapshot(); val failure = runCatching { FileProjectStore(temp.root).load() }.exceptionOrNull()
        rejected(failure, unsupported = true)
        assertTrue(failure!!.message!!.length < 300); assertFalse(failure.message!!.contains("private value"))
        assertEquals(before, snapshot())
    }
}

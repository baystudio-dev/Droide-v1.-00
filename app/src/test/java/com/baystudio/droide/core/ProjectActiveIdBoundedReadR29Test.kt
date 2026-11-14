package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectActiveIdBoundedReadR29Test {
    @get:Rule val temporary = TemporaryFolder()
    private fun active() = File(temporary.root, ".droide/active_project.txt").apply { parentFile!!.mkdirs() }

    @Test fun absentEmptyAndTrimmedIdsRetainExistingResults() = runTest(timeout = 15.seconds) {
        val store = FileProjectStore(temporary.root)
        assertNull(store.loadActiveId())
        active().writeText(" \n\t")
        assertNull(store.loadActiveId())
        active().writeText("  p-valid\n")
        assertEquals("p-valid", store.loadActiveId())
    }

    @Test fun exactByteLimitIsAcceptedAndOneByteOverIsNull() = runTest(timeout = 15.seconds) {
        val store = FileProjectStore(temporary.root)
        active().writeText("x".repeat(512))
        assertEquals(512, store.loadActiveId()?.length)
        val oversized = "x".repeat(513)
        active().writeText(oversized)
        assertNull(store.loadActiveId())
        assertEquals(oversized, active().readText())
    }

    @Test fun malformedUtf8CannotInventASelectedIdentity() = runTest(timeout = 15.seconds) {
        active().writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertNull(FileProjectStore(temporary.root).loadActiveId())
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0x28), active().readBytes())
    }

    @Test fun leafSymlinkIsNotReadOrChanged() = runTest(timeout = 15.seconds) {
        val outside = temporary.newFile("outside.txt").apply { writeText("outside-id") }
        Files.createSymbolicLink(active().toPath(), outside.toPath())
        assertNull(FileProjectStore(temporary.root).loadActiveId())
        assertTrue(Files.isSymbolicLink(active().toPath()))
        assertEquals("outside-id", outside.readText())
    }

    @Test fun metadataDirectorySymlinkIsNotTraversed() = runTest(timeout = 15.seconds) {
        val outside = temporary.newFolder("outside")
        File(outside, "active_project.txt").writeText("outside-id")
        Files.createSymbolicLink(File(temporary.root, ".droide").toPath(), outside.toPath())
        assertNull(FileProjectStore(temporary.root).loadActiveId())
        assertEquals("outside-id", File(outside, "active_project.txt").readText())
    }

    @Test fun oversizedAmbiguousReadbackLatchesUncertaintyAndKeepsProjectData() = runTest(timeout = 15.seconds) {
        val disk = FileProjectStore(temporary.root)
        var corruptNextReturn = false
        val store = object : ProjectStore by disk {
            override suspend fun saveActiveId(id: String) {
                disk.saveActiveId(id)
                if (corruptNextReturn) {
                    corruptNextReturn = false
                    active().writeText("x".repeat(513))
                    throw IOException("fixture write return lost")
                }
            }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load(): List<String>? = null
            override suspend fun save(ids: List<String>) = Unit
        }
        val manager = ProjectManager(temporary.root, store, recents)
        manager.init()
        val target = manager.add("Target")
        val source = File(target.rootPath, "keep.txt").apply { writeText("source sentinel") }
        corruptNextReturn = true
        assertTrue(runCatching { manager.switch(target.id) }.exceptionOrNull() is ProjectSelectionUncertainException)
        assertEquals("default", manager.activeId.value)
        assertTrue(manager.selectionIsUncertain)
        assertTrue(runCatching { manager.deleteWorkspace(target.id) }.exceptionOrNull() is ProjectSelectionUncertainException)
        assertEquals(513L, active().length())
        assertEquals("source sentinel", source.readText())
    }
}

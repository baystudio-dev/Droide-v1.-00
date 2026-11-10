package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AgentWorkspaceStorageAuthorityTest {
    @Test fun completeSqliteAuthorityAndEstablishedLinkRemainBoundAfterAnInodePreservingStorageMove() = runBlocking {
        val root = Files.createTempDirectory("cli-storage-sqlite-").toFile()
        try {
            val source = File(root, "projects/main").apply { mkdirs() }
            val legacy = File(root, "cache/workspaces/project").apply { parentFile.mkdirs() }
            val destination = File(root, "files/workspaces/project")
            val store = LocalMirrorAuthorityBootstrap(source, legacy).prepare()
            File(legacy, "pending").writeText("unpublished CLI version")
            val state = store.commit(store.load().revision, emptyMap(), setOf("pending"))
            val retained = File(store.directory, "retained/inode").apply { writeText("retained late CLI bytes") }
            val identity = Files.readAttributes(retained.toPath(), BasicFileAttributes::class.java).fileKey()
            val database = File(store.directory, "state.sqlite")
            val before = database.readBytes()
            val link = File(legacy.parentFile, ".${legacy.name}-sync-baseline.tsv").readBytes()
            AgentWorkspaceStorage(source, legacy, destination).prepare()
            val reopened = LocalMirrorAuthorityBootstrap(source, destination).prepare()
            assertEquals(state, reopened.load())
            assertArrayEquals(before, File(reopened.directory, "state.sqlite").readBytes())
            assertArrayEquals(link, File(destination.parentFile, ".${destination.name}-sync-baseline.tsv").readBytes())
            val moved = File(reopened.directory, "retained/inode")
            assertEquals("retained late CLI bytes", moved.readText())
            assertEquals(identity, Files.readAttributes(moved.toPath(), BasicFileAttributes::class.java).fileKey())
            assertEquals("unpublished CLI version", File(destination, "pending").readText())
            assertFalse(legacy.exists())
            assertFalse(store.directory.exists())
            assertTrue(source.listFiles()!!.isEmpty())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

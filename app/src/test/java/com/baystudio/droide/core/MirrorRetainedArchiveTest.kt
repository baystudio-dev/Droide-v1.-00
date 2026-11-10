package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorRetainedArchiveTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Fixture {
        val root = Files.createTempDirectory("retained-archive-").toFile()
        val source = root.resolve("source").apply { mkdir() }
        val working = root.resolve("working")
        val cache = root.resolve("cache").apply { mkdir() }
        val authorityRoot = root.resolve(".working-sync-authority")
        val authority get() = MirrorMetadataAuthority(authorityRoot, source, working)
        val recovery by lazy { MirrorRetainedRecovery(source, working) }
        lateinit var item: MirrorRetainedRecovery.Item
        lateinit var retained: File
        var repository = FileRepository(source, newFilePublisher = NewProjectFilePublisher.native())
        suspend fun prepare() {
            LocalAgentWorkspaceMirror.prepareRetained(source, working)
            val folder = working.resolve("group").apply { mkdir() }
            folder.resolve("empty").mkdir()
            folder.resolve(".git").mkdir()
            folder.resolve(".git/private-state").writeText("keep hidden contents")
            folder.resolve("binary.glb").writeBytes(byteArrayOf(0, -1, 32, 13, 10))
            folder.resolve("script").writeText("#!/bin/sh\ntrue\n"); folder.resolve("script").setExecutable(true)
            root.resolve("outside").writeText("must not be followed")
            Files.createSymbolicLink(folder.resolve("link").toPath(), java.nio.file.Paths.get("../../../outside"))
            val record = authority.prepare("group", "delete", MirrorMetadataAuthority.Node.directory("ab".repeat(32)), MirrorMetadataAuthority.Node.absent)
            retained = authorityRoot.resolve("retained/${record.retainedName}")
            Files.move(folder.toPath(), retained.toPath())
            authority.transition(record.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.CONFLICT)
            val state = authority.load(); authority.commit(state.revision, state.baseline, setOf("group"))
            item = recovery.list().items.single { it.role == MirrorRetainedRecovery.Role.ORIGINAL }
        }
        suspend fun archive(path: String = "recovered.tar", current: () -> Unit = {}) = recovery.archive(item, repository, path, cache, current)
        fun retainedBytes(): Map<String, String> = Files.walk(retained.toPath()).use { paths ->
            paths.iterator().asSequence().filter { !Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.associate { path ->
                retained.toPath().relativize(path).toString() to if (Files.isSymbolicLink(path)) "link:" + Files.readSymbolicLink(path)
                    else MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
    }
    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(30_000) { f.prepare(); f.action() }; assertTrue(f.cache.listFiles()!!.isEmpty()) }
        finally { assertTrue(PathSecurity.deleteTreeNoFollow(f.root)) }
    }

    @Test fun folderArchiveIncludesOpaqueBytesHiddenContentsEmptyFoldersAndLinks() = fixture {
        val original = retainedBytes(); val metadata = authority.load()
        val bytes = archive()
        assertEquals(source.resolve("recovered.tar").length(), bytes)
        val names = mutableSetOf<String>()
        TarArchiveInputStream(source.resolve("recovered.tar").inputStream()).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                names += entry.name
                when (entry.name) {
                    "binary.glb" -> assertArrayEquals(byteArrayOf(0, -1, 32, 13, 10), tar.readBytes())
                    ".git/private-state" -> assertEquals("keep hidden contents", tar.readBytes().toString(Charsets.UTF_8))
                    "link" -> { assertTrue(entry.isSymbolicLink); assertEquals("../../../outside", entry.linkName) }
                    "script" -> assertTrue(entry.mode and 64 != 0)
                }
            }
        }
        assertTrue(names.toString(), names.containsAll(listOf("empty/", ".git/private-state", "binary.glb", "script", "link")))
        assertEquals(original, retainedBytes()); assertEquals(metadata, authority.load())
        assertEquals("must not be followed", root.resolve("outside").readText())
    }

    @Test fun existingDestinationIsNeverOverwritten() = fixture {
        source.resolve("recovered.tar").writeText("user file")
        val original = retainedBytes()
        assertNotNull(runCatching { archive() }.exceptionOrNull())
        assertEquals("user file", source.resolve("recovered.tar").readText()); assertEquals(original, retainedBytes())
    }

    @Test fun cancellationDuringCaptureCleansOnlyPrivateStaging() = fixture {
        val original = retainedBytes(); var checks = 0
        assertTrue(runCatching { archive { if (++checks == 4) throw CancellationException("cancel") } }.exceptionOrNull() is CancellationException)
        assertFalse(source.resolve("recovered.tar").exists()); assertEquals(original, retainedBytes())
    }

    @Test fun repositoryDirtyAdmissionStillBlocksArchivePublication() = fixture {
        repository = FileRepository(source, beforeMutation = { error("Unsaved editor buffer") }, newFilePublisher = NewProjectFilePublisher.native())
        val original = retainedBytes()
        assertNotNull(runCatching { archive() }.exceptionOrNull())
        assertFalse(source.resolve("recovered.tar").exists()); assertEquals(original, retainedBytes())
    }

    @Test fun wrongWorkspaceAndMisleadingArchiveNameAreRefused() = fixture {
        assertNotNull(runCatching { archive("recovered.zip") }.exceptionOrNull())
        val other = root.resolve("other").apply { mkdir() }
        val wrong = FileRepository(other, newFilePublisher = NewProjectFilePublisher.native())
        assertNotNull(runCatching { recovery.archive(item, wrong, "recovered.tar", cache) {} }.exceptionOrNull())
        assertTrue(other.listFiles()!!.isEmpty()); assertTrue(source.listFiles()!!.isEmpty())
    }
}

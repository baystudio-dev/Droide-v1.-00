package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitRuntimeExclusionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun existingRepositoryReceivesRuntimeExclusionBeforeStatusAndStageAll() = runBlocking {
        Git.init().setDirectory(temp.root).call().close()
        val ignore = File(temp.root, ".gitignore").apply { writeText("user-owned-ignore/\n") }
        val exclude = File(temp.root, ".git/info/exclude").apply { parentFile.mkdirs(); writeText("# user-owned excludes\nprivate-cache/\n") }
        val paths = listOf("run/native/program", "tmp/lsp-check.jar", "vision/photo.jpg",
            ".runtime-agent-plugins/adapter", "subagents/session.json", "future-runtime/state.bin")
        paths.forEach { path -> File(temp.root, ".droide/$path").apply { parentFile.mkdirs(); writeText("runtime") } }
        File(temp.root, "Main.kt").writeText("fun main() = Unit\n")
        val manager = GitManager(temp.root)
        val changes = manager.changes()
        assertTrue(changes.any { it.path == "Main.kt" })
        assertFalse(changes.any { it.path.startsWith(".droide/") })
        assertEquals("user-owned-ignore/\n", ignore.readText())
        assertTrue(exclude.readText().startsWith("# user-owned excludes\nprivate-cache/\n"))
        assertTrue(exclude.readLines().contains("/.droide/"))
        assertTrue(manager.stageAll().startsWith("Staged"))
        assertFalse(manager.changes().any { it.path.startsWith(".droide/") })
        paths.forEach { assertEquals("runtime", File(temp.root, ".droide/$it").readText()) }
    }

    @Test fun runtimeExclusionNeverUntracksExistingProjectConfiguration() = runBlocking {
        val config = File(temp.root, ".droide/launch.json").apply { parentFile.mkdirs(); writeText("{\"version\":1}\n") }
        Git.init().setDirectory(temp.root).call().use { raw ->
            raw.repository.config.apply {
                setString("user", null, "name", "Workflow Test")
                setString("user", null, "email", "workflow@example.com")
                save()
            }
            raw.add().addFilepattern(".droide/launch.json").call()
            raw.commit().setMessage("existing tracked config").call()
        }
        config.writeText("{\"version\":2}\n")
        val manager = GitManager(temp.root)
        assertTrue(manager.changes().any { it.path == ".droide/launch.json" && it.kind == GitChangeKind.MODIFIED })
        Git.open(temp.root).use { raw -> assertNotNull(raw.repository.readDirCache().getEntry(".droide/launch.json")) }
        assertEquals("{\"version\":2}\n", config.readText())
    }

    @Test fun oversizedUserExcludesAreNeverReplaced() = runBlocking {
        Git.init().setDirectory(temp.root).call().close()
        val custom = "#" + "x".repeat(1_000_000) + "\n"
        val exclude = File(temp.root, ".git/info/exclude").apply { parentFile.mkdirs(); writeText(custom) }
        assertTrue(GitManager(temp.root).isRepository())
        assertEquals(custom, exclude.readText())
    }

    @Test fun exclusionMaintenanceNeverFollowsLinkIntoProjectSource() = runBlocking {
        Git.init().setDirectory(temp.root).call().close()
        val source = File(temp.root, "keep.txt").apply { writeText("user source stays intact\n") }
        val exclude = File(temp.root, ".git/info/exclude").apply { parentFile.mkdirs() }
        exclude.delete()
        Files.createSymbolicLink(exclude.toPath(), source.toPath())
        assertTrue(GitManager(temp.root).isRepository())
        assertTrue(Files.isSymbolicLink(exclude.toPath()))
        assertEquals("user source stays intact\n", source.readText())
    }
}

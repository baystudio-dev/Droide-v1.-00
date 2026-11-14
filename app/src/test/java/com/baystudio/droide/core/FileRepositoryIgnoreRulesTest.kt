package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FileRepositoryIgnoreRulesTest {
    @Test fun oversizedOrNonregularRootIgnoreFailsEverySearchWithoutLeakingResults() = withWorkspace { root ->
        write(root, "hidden.kt", "needle")
        val ignore = root.resolve(".gitignore")
        ignore.writeText("#".repeat(RootIgnoreRules.MAX_BYTES.toInt() + 1))
        val repo = FileRepository(root)
        assertSearchUnavailable(repo)
        check(ignore.delete()); check(ignore.mkdir())
        assertSearchUnavailable(repo)
    }

    @Test fun invalidUtf8RootIgnoreFailsEverySearchWithoutLeakingResults() = withWorkspace { root ->
        write(root, "hidden.kt", "needle")
        root.resolve(".gitignore").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertSearchUnavailable(FileRepository(root))
    }

    @Test fun symbolicRootIgnoreFailsSearchEvenWhenItsTargetIsMissing() = withWorkspace { root ->
        write(root, "hidden.kt", "needle")
        val target = root.resolve("ignore-rules.txt").apply { writeText("hidden.kt\n") }
        Files.createSymbolicLink(root.resolve(".gitignore").toPath(), target.toPath())
        val repo = FileRepository(root)
        assertSearchUnavailable(repo)
        check(target.delete())
        assertSearchUnavailable(repo)
    }

    @Test fun ignoredPathsRemainAvailableToExplorerListingsAndExplicitReads() = withWorkspace { root ->
        root.resolve(".gitignore").writeText("dependency/\n")
        write(root, "dependency/file.kt", "project-owned bytes")
        runBlocking {
            val repo = FileRepository(root)
            assertTrue(repo.findFilesPage("file.kt").paths.isEmpty())
            assertTrue(repo.listFiles().any { it.path == "dependency" && it.isDir })
            assertTrue(repo.listFilesPage().entries.any { it.path == "dependency" && it.isDir })
            assertEquals(listOf("dependency/file.kt"), repo.listFiles("dependency").map { it.path })
            assertEquals("project-owned bytes", repo.readText("dependency/file.kt"))
            assertEquals("project-owned bytes", repo.readTextForEdit("dependency/file.kt"))
        }
    }

    @Test fun ignoredLargeTreeDoesNotExhaustScanBudgetBeforeVisibleSource() = withWorkspace { root ->
        root.resolve(".gitignore").writeText("dependency/\n!dependency/keep.kt\n")
        // Regression fixture is intentionally larger than MAX_SCAN_ENTRIES; never run as an ad hoc probe.
        val ignored = root.resolve("dependency").apply { mkdir() }
        repeat(50_001) { ignored.resolve("file-$it.kt").writeText("needle") }
        write(root, "source.kt", "needle")
        runBlocking {
            val repo = FileRepository(root)
            val quick = repo.findFilesPage("source")
            assertEquals(listOf("source.kt"), quick.paths)
            assertFalse(quick.scanLimitReached)
            assertTrue(quick.scannedEntries < 10)
            assertEquals(listOf("source.kt"), repo.globPage("**/*.kt").paths)
            assertEquals(listOf("source.kt"), repo.searchPage("needle").hits.map { it.path })
        }
    }

    @Test fun `directory rules and negation agree across quick open glob disk and dirty search`() = withWorkspace { root ->
        root.resolve(".gitignore").writeText(
            "build/\n/target/\ngenerated/*\n!generated/keep.kt\nblocked/\n!blocked/keep.kt\n"
        )
        val files = listOf(
            "build", "module/build/hidden.kt", "target/hidden.kt", "nested/target/visible.kt",
            "generated/keep.kt", "generated/drop.kt", "blocked/keep.kt", "source.kt",
        )
        files.forEach { write(root, it, "diskNeedle\n") }
        val expected = listOf("build", "generated/keep.kt", "nested/target/visible.kt", "source.kt")
        val repo = FileRepository(root)
        val live = files.map { dirty(it, "liveNeedle\n") } + listOf(
            dirty("module/build/not-saved.kt", "liveNeedle\n"),
            dirty("new-unsaved.kt", "liveNeedle\n"),
        )
        runBlocking {
            assertEquals(expected, repo.findFilesPage("").paths.filterNot { it == ".gitignore" }.sorted())
            assertEquals(expected, repo.globPage("**").paths.filterNot { it == ".gitignore" }.sorted())
            assertEquals(expected, repo.searchPage("diskNeedle").hits.map { it.path }.sorted())
            assertEquals((expected + "new-unsaved.kt").sorted(),
                repo.searchPage("liveNeedle", documents = authority(live)).hits.map { it.path }.sorted())
            assertTrue(repo.searchPage("diskNeedle", documents = authority(live)).hits.isEmpty())
            assertTrue(repo.isSearchVisible("build"))
            assertFalse(repo.isSearchVisible("module/build"))
            assertFalse(repo.isSearchVisible("target"))
            assertTrue(repo.isSearchVisible("nested/target"))
            files.forEach { path -> assertEquals(path, path in expected, repo.isSearchVisible(path)) }
        }
    }

    @Test fun `escaped marker names and trailing spaces agree across search entry points`() = withWorkspace { root ->
        root.resolve(".gitignore").writeText("\\#hidden.kt\n\\!hidden.kt\n spaced.kt\ntrailing.kt\\ \n")
        val hidden = listOf("#hidden.kt", "!hidden.kt", " spaced.kt", "trailing.kt ")
        val visible = listOf("spaced.kt", "trailing.kt", "visible.kt")
        (hidden + visible).forEach { write(root, it, "diskNeedle\n") }
        val repo = FileRepository(root)
        runBlocking {
            assertEquals(visible.sorted(), repo.findFilesPage("kt").paths.sorted())
            assertEquals(visible.sorted(), repo.globPage("**").paths.filterNot { it == ".gitignore" }.sorted())
            assertEquals(visible.sorted(), repo.searchPage("diskNeedle").hits.map { it.path }.sorted())
            val live = (hidden + visible).map { dirty(it, "liveNeedle\n") }
            assertEquals(visible.sorted(), repo.searchPage("liveNeedle", documents = authority(live)).hits.map { it.path }.sorted())
        }
    }

    @Test fun `root-only scope does not load nested ignore files`() = withWorkspace { root ->
        root.resolve(".gitignore").writeText("root-hidden.kt\n")
        write(root, "nested/.gitignore", "child.kt\n")
        write(root, "nested/child.kt", "diskNeedle\n")
        write(root, "nested/root-hidden.kt", "diskNeedle\n")
        val repo = FileRepository(root)
        runBlocking {
            assertEquals(listOf("nested/child.kt"), repo.findFilesPage(".kt").paths)
            assertEquals(listOf("nested/child.kt"), repo.globPage("**/*.kt").paths)
            assertEquals(listOf("nested/child.kt"), repo.searchPage("diskNeedle").hits.map { it.path })
        }
    }

    @Test fun `ignore negation cannot expose sensitive or internal paths`() = withWorkspace { root ->
        root.resolve(".gitignore").writeText("!**\n!.env\n!.git/\n!.droide/\n")
        val files = listOf("visible.kt", ".env", ".git/config", ".droide/runtime.kt")
        files.forEach { write(root, it, "diskNeedle\n") }
        val repo = FileRepository(root)
        runBlocking {
            assertEquals(listOf("visible.kt"), repo.searchPage("diskNeedle").hits.map { it.path })
            assertEquals(listOf("visible.kt"), repo.globPage("**").paths.filterNot { it == ".gitignore" })
            assertEquals(listOf("visible.kt"), repo.findFilesPage("").paths.filterNot { it == ".gitignore" })
            val live = files.map { dirty(it, "liveNeedle\n") }
            assertEquals(listOf("visible.kt"), repo.searchPage("liveNeedle", documents = authority(live)).hits.map { it.path })
            assertFalse(repo.isSearchVisible(".env"))
            assertFalse(repo.isSearchVisible(".git/config"))
            assertFalse(repo.isSearchVisible(".droide/runtime.kt"))
        }
    }

    private fun assertSearchUnavailable(repo: FileRepository) {
        assertThrows(RootIgnoreException::class.java) { runBlocking { repo.findFilesPage("") } }
        assertThrows(RootIgnoreException::class.java) { runBlocking { repo.globPage("**") } }
        assertThrows(RootIgnoreException::class.java) { runBlocking { repo.searchPage("needle") } }
        assertThrows(RootIgnoreException::class.java) { runBlocking { repo.isSearchVisible("hidden.kt") } }
        assertTrue(runBlocking { repo.glob("**") }.startsWith("(glob error: Root .gitignore filtering unavailable:"))
    }

    private fun write(root: File, path: String, text: String) {
        root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    }

    private fun dirty(path: String, text: String) = WorkspaceDocumentSnapshot(
        path = path,
        content = text,
        loaded = true,
        kind = WorkspaceDocumentKind.TEXT,
        dirty = true,
        editable = true,
        revision = 1,
        changeVersion = 1,
        selectionStart = 0,
        selectionEnd = 0,
        savedContentLength = 0,
    )

    private fun authority(values: List<WorkspaceDocumentSnapshot>) = object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String) = values.firstOrNull { it.path == path }
        override suspend fun snapshots() = values
        override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest) =
            request.captureFiniteSnapshots(values)
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-ignore-rules-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

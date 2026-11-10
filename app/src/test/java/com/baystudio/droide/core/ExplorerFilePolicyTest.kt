package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerFilePolicyTest {
    @Test fun hiddenRuntimeTreeDoesNotConsumePageOrProduceAnEmptyContinuation() = withWorkspace { root ->
        root.resolve(".droide/run").mkdirs()
        root.resolve("a.kt").writeText("source")
        val page = runBlocking {
            FileRepository(root).listFilesPage(pageSize = 1, options = DirectoryListingOptions.EXPLORER)
        }
        assertEquals(listOf("a.kt"), page.entries.map { it.path })
        assertNull(page.nextCursor)
    }

    @Test fun projectDotfilesAndNestedDroideNamesRemainVisible() = withWorkspace { root ->
        root.resolve(".droide").mkdir()
        root.resolve(".git").mkdir()
        root.resolve(".vscode").mkdir()
        root.resolve(".env").writeText("config")
        root.resolve(".editorconfig").writeText("config")
        root.resolve("package/.droide").mkdirs()
        val repo = FileRepository(root)
        val entries = runBlocking { repo.listFilesPage(options = DirectoryListingOptions.EXPLORER).entries }
        assertFalse(entries.any { it.path == ".droide" })
        assertTrue(entries.map { it.path }.containsAll(listOf(".git", ".vscode", ".env", ".editorconfig")))
        assertEquals("package/.droide", runBlocking {
            repo.listFilesPage("package", options = DirectoryListingOptions.EXPLORER).entries.single().path
        })
    }

    @Test fun explicitExplorerPolicyRejectsInternalDirectoryAndCanonicalAlias() = withWorkspace { root ->
        root.resolve(".droide/run").mkdirs()
        Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve(".droide").toPath())
        val repo = FileRepository(root)
        listOf(".droide", ".droide/run", "alias").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.listFilesPage(path, options = DirectoryListingOptions.EXPLORER) }
            }
        }
    }

    @Test fun defaultRepositoryListingAndRuntimeWritesRemainAvailableForRecoveryAndAgent() = withWorkspace { root ->
        val repo = FileRepository(root)
        runBlocking { repo.writeText(".droide/run/internal.json", "{}") }
        assertEquals(".droide", runBlocking { repo.listFilesPage().entries.single().path })
        assertEquals(".droide/run/internal.json", runBlocking { repo.listFilesPage(".droide/run").entries.single().path })
        assertEquals("{}", runBlocking { repo.readText(".droide/run/internal.json") })
    }

    @Test fun everySortHasFolderFirstStableOrderingAcrossAllPagesIncludingCaseAndTimestampTies() = withWorkspace { root ->
        directory(root, "A-dir", 40L)
        directory(root, "b-dir", 30L)
        directory(root, ".droide", 1L)
        file(root, ".editorconfig", 10L)
        file(root, ".env", 10L)
        file(root, "Alpha.txt", 50L)
        file(root, "alpha.txt", 50L)
        file(root, "b.kt", 50L)
        file(root, "b.py", 20L)
        file(root, "notes", 20L)
        file(root, "Z.kt", 30L)
        val expected = mapOf(
            ExplorerSortOrder.NAME_ASC to listOf("A-dir", "b-dir", ".editorconfig", ".env", "Alpha.txt", "alpha.txt", "b.kt", "b.py", "notes", "Z.kt"),
            ExplorerSortOrder.NAME_DESC to listOf("b-dir", "A-dir", "Z.kt", "notes", "b.py", "b.kt", "alpha.txt", "Alpha.txt", ".env", ".editorconfig"),
            ExplorerSortOrder.TYPE to listOf("A-dir", "b-dir", "notes", ".editorconfig", ".env", "b.kt", "Z.kt", "b.py", "Alpha.txt", "alpha.txt"),
            ExplorerSortOrder.MODIFIED_DESC to listOf("A-dir", "b-dir", "Alpha.txt", "alpha.txt", "b.kt", "Z.kt", "b.py", "notes", ".editorconfig", ".env"),
        )
        val repo = FileRepository(root)
        expected.forEach { (sort, paths) ->
            val actual = mutableListOf<String>()
            val options = DirectoryListingOptions(sort, hideDroideInternal = true)
            var cursor: DirectoryPageCursor? = null
            do {
                val page = runBlocking { repo.listFilesPage(after = cursor, pageSize = 3, options = options) }
                assertTrue(page.entries.size <= 3)
                actual += page.entries.map { it.path }
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals("Sort $sort", paths, actual)
        }
    }

    @Test fun cursorRejectsChangedSortVisibilityFolderAndMalformedPath() = withWorkspace { root ->
        root.resolve("folder").mkdir()
        root.resolve("a.txt").writeText("a")
        root.resolve("b.txt").writeText("b")
        val repo = FileRepository(root)
        val options = DirectoryListingOptions.EXPLORER
        val cursor = runBlocking { repo.listFilesPage(pageSize = 1, options = options).nextCursor!! }
        listOf(options.copy(sortOrder = ExplorerSortOrder.TYPE), options.copy(hideDroideInternal = false)).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.listFilesPage(after = cursor, options = changed) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.listFilesPage("folder", cursor, options = options) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.listFilesPage(after = cursor.copy(path = "a//b"), options = options) }
        }
    }

    @Test fun modifiedCursorRetainsItsTimestampEvenIfBoundaryFileDisappears() = withWorkspace { root ->
        file(root, "a.txt", 300L)
        file(root, "b.txt", 200L)
        file(root, "c.txt", 100L)
        val repo = FileRepository(root)
        val options = DirectoryListingOptions(ExplorerSortOrder.MODIFIED_DESC, true)
        val cursor = runBlocking { repo.listFilesPage(pageSize = 1, options = options).nextCursor!! }
        assertEquals(300L, cursor.modified)
        assertTrue(root.resolve("a.txt").delete())
        val next = runBlocking { repo.listFilesPage(after = cursor, options = options) }
        assertEquals(listOf("b.txt", "c.txt"), next.entries.map { it.path })
    }

    @Test fun userTargetsUseCanonicalPathsAndKeepConfigurationFilesUsable() = withWorkspace { root ->
        root.resolve("src").mkdir()
        assertEquals("src/hello.kt", ExplorerFilePolicy.requireUserTarget(root, "src\\hello.kt"))
        assertEquals("hello.kt", ExplorerFilePolicy.requireUserTarget(root, "src/../hello.kt"))
        assertEquals(".vscode/settings.json", ExplorerFilePolicy.requireUserTarget(root, ".vscode/settings.json"))
        assertEquals("package/.droide/user.txt", ExplorerFilePolicy.requireUserTarget(root, "package/.droide/user.txt"))
        assertEquals(".droide-project/readme.md", ExplorerFilePolicy.requireUserTarget(root, ".droide-project/readme.md"))
        listOf(".droide", "src/../.droide/file.kt", ".droide\\file.kt", "../outside", root.absolutePath, "x\u0000y").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ExplorerFilePolicy.requireUserTarget(root, path) }
        }
    }

    @Test fun userTargetCannotReachInternalOrOutsideDirectoryThroughSymlink() = withWorkspace { root ->
        root.resolve(".droide").mkdir()
        root.resolve("ordinary").mkdir()
        Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve(".droide").toPath())
        Files.createSymbolicLink(root.resolve("ordinary-alias").toPath(), root.resolve("ordinary").toPath())
        Files.createSymbolicLink(root.resolve("outside").toPath(), root.parentFile.toPath())
        listOf("alias/user.kt", "ordinary-alias/user.kt", "ordinary-alias/../user.kt", "outside/escape.kt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ExplorerFilePolicy.requireUserTarget(root, path) }
        }
    }

    @Test fun cancelledQueuedRequestDoesNotScanAndSubsequentSmallPageRemainsUsable() = withWorkspace { root ->
        repeat(2_001) { root.resolve("file-%04d.txt".format(it)).writeText("x") }
        runBlocking {
            withTimeout(5_000) {
                var started = false
                val task = async(start = CoroutineStart.LAZY) {
                    started = true
                    FileRepository(root).listFilesPage(pageSize = 1, options = DirectoryListingOptions.EXPLORER)
                }
                task.cancelAndJoin()
                assertTrue(task.isCancelled)
                assertFalse(started)
                assertEquals("file-0000.txt", FileRepository(root).listFilesPage(pageSize = 1).entries.single().path)
            }
        }
    }

    private fun file(root: File, name: String, modified: Long) {
        root.resolve(name).apply { writeText("x"); check(setLastModified(modified)) }
    }

    private fun directory(root: File, name: String, modified: Long) {
        root.resolve(name).apply { check(mkdir()); check(setLastModified(modified)) }
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-explorer-policy-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

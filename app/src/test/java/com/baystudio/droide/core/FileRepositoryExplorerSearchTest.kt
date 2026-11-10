package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileRepositoryExplorerSearchTest {
    @Test fun runtimeAndGitDescendantsDoNotConsumeSearchBudgetOrHideProjectMatches() = withWorkspace { root ->
        root.resolve(".droide/run").mkdirs()
        root.resolve(".git/objects").mkdirs()
        repeat(1_001) { index ->
            root.resolve(".droide/run/internal-$index.kt").writeText("needle")
            root.resolve(".git/objects/internal-$index.kt").writeText("needle")
        }
        root.resolve("visible.kt").writeText("needle")
        root.resolve("src/ordinary").mkdirs()
        root.resolve("src/ordinary/project.kt").writeText("needle")
        val repo = FileRepository(root)
        runBlocking {
            val expected = listOf("src/ordinary/project.kt", "visible.kt")
            val glob = repo.globPage("**/*.kt")
            val quick = repo.findFilesPage(".kt")
            val text = repo.searchPage("needle")
            assertEquals(expected, glob.paths.sorted())
            assertEquals(expected, quick.paths.sorted())
            assertEquals(expected, text.hits.map { it.path }.sorted())
            assertTrue(glob.scannedEntries <= 6)
            assertTrue(quick.scannedEntries <= 6)
            assertTrue(text.scannedEntries <= 6)
            assertFalse(glob.scanLimitReached || quick.scanLimitReached || text.scanLimitReached)
        }
    }

    @Test fun cancelledSearchRequestsReleasePromptlyAndLeaveRepositoryUsable() = withWorkspace { root ->
        root.resolve("visible.kt").writeText("needle")
        val repo = FileRepository(root)
        val operations: List<suspend () -> Unit> = listOf(
            { repo.globPage("**/*.kt"); Unit },
            { repo.findFilesPage("visible"); Unit },
            { repo.searchPage("needle"); Unit },
        )
        runBlocking {
            withTimeout(5_000) {
                operations.forEach { operation ->
                    val cancelled = Job().apply { cancel() }
                    try {
                        withContext(cancelled) { operation() }
                        fail("Cancelled search must not execute")
                    } catch (_: CancellationException) {
                        assertTrue(cancelled.isCancelled)
                    }
                }
                assertEquals(listOf("visible.kt"), repo.findFilesPage("visible").paths)
            }
        }
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-explorer-search-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

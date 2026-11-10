package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryDiscoveryStatusTest {
    @Test fun depthReasonReachesPagesAndHumanAgentWrappers(): Unit = runBlocking {
        val deepPath = (1..21).joinToString("/") { "level$it" } + "/deep.kt"
        withDiscoveryFiles(deepPath, "visible.kt") { root ->
            val repository = FileRepository(root)
            val reason = setOf(DiscoveryStopReason.DEPTH_LIMIT)
            assertEquals(reason, repository.findFilesPage(".kt").scanStopReasons)
            assertEquals(reason, repository.globPage("**/*.kt").scanStopReasons)
            assertEquals(reason, repository.searchPage("needle").scanStopReasons)
            assertTrue(repository.glob("**/*.kt").contains("depth_limit"))
            assertTrue(repository.search("needle").contains("depth_limit"))
            val agent = AgentWorkspaceSearch(repository).search("needle")
            assertTrue(agent.contains("scan_limit=true"))
            assertTrue(agent.contains("scanned_entries=21 "))
            assertTrue(agent.contains("scan_stop=depth_limit"))
            assertTrue(agent.contains("Results are incomplete"))
            assertFalse(agent.contains("increase max_results"))
        }
    }

    @Test fun excludedDeepDependenciesDoNotCreateAFakeDepthLimit(): Unit = runBlocking {
        val deepPath = "node_modules/" + (1..21).joinToString("/") { "level$it" } + "/deep.kt"
        withDiscoveryFiles(deepPath, "visible.kt") { root ->
            val repository = FileRepository(root)
            val quick = repository.findFilesPage(".kt")
            val glob = repository.globPage("**/*.kt")
            val text = repository.searchPage("needle")
            assertEquals(listOf("visible.kt"), quick.paths)
            assertFalse(quick.scanLimitReached || glob.scanLimitReached || text.scanLimitReached)
            assertTrue(quick.scanStopReasons.isEmpty() && glob.scanStopReasons.isEmpty() && text.scanStopReasons.isEmpty())
            assertEquals("needle\n", repository.readText(deepPath))
            assertEquals(setOf(DiscoveryStopReason.DEPTH_LIMIT), repository.globPage("**/*.kt", includeDependencies = true).scanStopReasons)
        }
    }
}

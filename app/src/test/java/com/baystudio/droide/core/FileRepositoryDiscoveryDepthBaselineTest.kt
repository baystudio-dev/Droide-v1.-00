package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses only the frozen R12 API; the coordinator owns execution and the baseline record. */
class FileRepositoryDiscoveryDepthBaselineTest {
    @Test fun depthLimitedDiscoveryReportsIncompleteResults(): Unit = runBlocking {
        val root = Files.createTempDirectory("droide-discovery-depth-").toFile()
        try {
            val deepDirectory = (1..21).joinToString("/") { "level$it" }
            val deepPath = "$deepDirectory/deep.kt"
            root.resolve(deepDirectory).apply { check(mkdirs()) }
            root.resolve(deepPath).writeText("needle\n")
            root.resolve("shallow.kt").writeText("needle\n")
            val repo = FileRepository(root)

            // Depth-limited discovery must not change intentional direct access.
            assertEquals("needle\n", repo.readText(deepPath))
            val quick = repo.findFilesPage(".kt")
            val glob = repo.globPage("**/*.kt")
            val text = repo.searchPage("needle")
            assertEquals(listOf("shallow.kt"), quick.paths)
            assertEquals(listOf("shallow.kt"), glob.paths)
            assertEquals(listOf("shallow.kt"), text.hits.map { it.path })
            assertFalse(quick.resultLimitReached || glob.resultLimitReached || text.resultLimitReached)

            assertTrue("Quick Open omitted a depth-limited subtree without disclosing incompleteness", quick.scanLimitReached)
            assertTrue("Glob omitted a depth-limited subtree without disclosing incompleteness", glob.scanLimitReached)
            assertTrue("Text search omitted a depth-limited subtree without disclosing incompleteness", text.scanLimitReached)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}

package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileRepositoryBoundedDirectoryTest {
    @Test fun incompleteUnorderedScanNeverReturnsASortedPageOrCursor(): Unit = runBlocking {
        withDiscoveryFiles("z.kt", "y.kt", "x.kt", "a.kt", "b.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("z.kt", "y.kt", "x.kt", "a.kt", "b.kt")))
            val budget = DiscoveryBudget(DiscoveryLimits(directoryProbes = 3), nanoTime = { 0L })
            var returned: DirectoryPage? = null
            try {
                returned = FileRepository(root).listFilesPageBounded("", null, 2, DirectoryListingOptions(), budget, streams.traversal)
                fail("The lexical minimum has not been enumerated")
            } catch (expected: DirectoryScanLimitException) {
                assertEquals(setOf(DiscoveryStopReason.PROBE_LIMIT), expected.stopReasons)
                assertEquals(3, expected.scannedEntries)
                assertEquals(3, expected.directoryProbes)
            }
            assertNull(returned)
            assertEquals(3, streams.probes)
            assertEquals(3, streams.delivered)
            assertEquals(1, streams.closed.size)
        }
    }

    @Test fun completedSortedPagesRetainTheirHonestRepeatedEnumerationCost(): Unit = runBlocking {
        withDiscoveryFiles("z.kt", "y.kt", "x.kt", "a.kt", "b.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("z.kt", "y.kt", "x.kt", "a.kt", "b.kt")))
            val repository = FileRepository(root)
            val found = mutableListOf<String>()
            var cursor: DirectoryPageCursor? = null
            do {
                val budget = DiscoveryBudget(DiscoveryLimits(directoryProbes = 6), nanoTime = { 0L })
                val page = repository.listFilesPageBounded("", cursor, 2, DirectoryListingOptions(), budget, streams.traversal)
                found += page.entries.map { it.path }
                cursor = page.nextCursor
                assertEquals(5, budget.scannedEntries)
                assertEquals(6, budget.directoryProbes)
                assertFalse(budget.incomplete)
            } while (cursor != null)
            assertEquals(listOf("a.kt", "b.kt", "x.kt", "y.kt", "z.kt"), found)
            assertEquals(3, streams.opened.size)
            assertEquals(3, streams.closed.size)
            assertEquals(15, streams.delivered)
            assertEquals(18, streams.probes)
        }
    }

    @Test fun exactProbeBoundaryIsConservativeUntilEofWasAdmitted(): Unit = runBlocking {
        withDiscoveryFiles("a.kt", "b.kt", "c.kt") { root ->
            val repository = FileRepository(root)
            for (allowance in listOf(3, 4)) {
                val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("a.kt", "b.kt", "c.kt")))
                val budget = DiscoveryBudget(DiscoveryLimits(directoryProbes = allowance), nanoTime = { 0L })
                try {
                    val page = repository.listFilesPageBounded("", null, 3, DirectoryListingOptions(), budget, streams.traversal)
                    assertEquals(4, allowance)
                    assertEquals(3, page.entries.size)
                    assertNull(page.nextCursor)
                } catch (expected: DirectoryScanLimitException) {
                    assertEquals(3, allowance)
                    assertEquals(setOf(DiscoveryStopReason.PROBE_LIMIT), expected.stopReasons)
                }
                assertEquals(allowance, streams.probes)
                assertEquals(3, streams.delivered)
                assertEquals(1, streams.closed.size)
            }
        }
    }

    @Test fun flatListChargesDependencyEntriesEvenWhenTheyAreFiltered(): Unit = runBlocking {
        withDiscoveryFiles("node_modules/dependency.kt", "visible.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("node_modules", "visible.kt")))
            val budget = DiscoveryBudget(DiscoveryLimits(directoryProbes = 1), nanoTime = { 0L })
            try {
                FileRepository(root).listFilesBounded("", 1, false, budget, streams.traversal)
                fail("Filtered entries must still consume the scan allowance")
            } catch (expected: DirectoryScanLimitException) {
                assertEquals(1, expected.scannedEntries)
                assertEquals(setOf(DiscoveryStopReason.PROBE_LIMIT), expected.stopReasons)
            }
            assertEquals(1, streams.probes)
            assertEquals(1, streams.delivered)
            assertEquals(1, streams.closed.size)
            assertTrue(FileRepository(root).listFiles("node_modules").any { it.path == "node_modules/dependency.kt" })
        }
    }

    @Test fun hiddenExplorerEntriesAlsoConsumeTheScanAllowance(): Unit = runBlocking {
        withDiscoveryFiles(".droide/internal.kt", "visible.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf(".droide", "visible.kt")))
            val budget = DiscoveryBudget(DiscoveryLimits(directoryProbes = 1), nanoTime = { 0L })
            try {
                FileRepository(root).listFilesPageBounded("", null, 1, DirectoryListingOptions.EXPLORER, budget, streams.traversal)
                fail("A hidden entry must not permit an unbounded scan")
            } catch (expected: DirectoryScanLimitException) {
                assertEquals(1, expected.scannedEntries)
            }
            assertEquals(1, streams.probes)
            assertEquals(1, streams.closed.size)
        }
    }
}

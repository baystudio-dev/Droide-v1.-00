package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileRepositoryTextBudgetTest {
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("droide-text-budget-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun snapshot(path: String, content: String, editable: Boolean = true) = WorkspaceDocumentSnapshot(
        path, content, true, if (editable) WorkspaceDocumentKind.TEXT else WorkspaceDocumentKind.BINARY,
        true, editable, 1, 1, 0, 0, 0,
    )
    private fun authority(vararg values: WorkspaceDocumentSnapshot) = object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String) = values.firstOrNull { it.path == path }
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("No legacy list fallback")
        override suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> = error("No legacy dirty fallback")
        override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest) =
            request.captureFiniteSnapshots(values.asIterable())
    }

    @Test(timeout = 15_000) fun dirtyFilterWorkStopPrecedesTextAndTraversalOpens(): Unit = runBlocking {
        fixture { root ->
            root.resolve("active.kt").writeText("needle saved\n")
            var textOpens = 0
            var directoryOpens = 0
            val page = FileRepository(root).searchPageBounded("needle",
                options = TextSearchOptions(includeGlob = "*.kt"),
                documents = authority(snapshot("active.kt", "needle live\n")),
                limits = WorkspaceSearchLimits(matchUnits = 0),
                openInput = { textOpens++; it.inputStream() },
                traversal = WorkspaceDiscoveryTraversal { directoryOpens++; Files.newDirectoryStream(it.toPath()) })
            assertTrue(page.scanLimitReached)
            assertEquals(setOf(WorkspaceSearchStopReason.MATCH_LIMIT), page.searchStopReasons)
            assertFalse(page.dirtyCaptureComplete)
            assertTrue(page.hits.isEmpty())
            assertEquals(0, page.unreadableFiles)
            assertEquals(1, page.searchStats.snapshotsCaptured)
            assertEquals(0, textOpens)
            assertEquals(0, directoryOpens)
            assertEquals("needle saved\n", FileRepository(root).readText("active.kt"))
        }
    }

    @Test(timeout = 15_000) fun globExhaustionIsTypedIncompleteNotFalseNoMatch(): Unit = runBlocking {
        fixture { root ->
            root.resolve("ab.kt").writeText("needle\n")
            val page = FileRepository(root).globPageBounded("*", limits = WorkspaceSearchLimits(matchUnits = 0))
            assertTrue(page.scanLimitReached)
            assertFalse(page.resultLimitReached)
            assertEquals(setOf(WorkspaceSearchStopReason.MATCH_LIMIT), page.searchStopReasons)
            assertEquals(1, page.scannedEntries)
            assertTrue(page.paths.isEmpty())
            assertEquals(0L, page.searchStats.matchUnits)
        }
    }

    @Test(timeout = 15_000) fun exclusionsAreAdmittedBeforeNormalizationOrCapture(): Unit = runBlocking {
        fixture { root ->
            root.resolve("a.kt").writeText("needle\n")
            var captures = 0
            var textOpens = 0
            var traversalOpens = 0
            val documents = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
                override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("legacy")
                override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                    captures++
                    return WorkspaceSearchCapture()
                }
            }
            // The invalid first path would throw if normalization ran before the count admission.
            val page = FileRepository(root).searchPageBounded("needle",
                excludedPaths = linkedSetOf("../outside", "b.kt", "c.kt"), documents = documents,
                limits = WorkspaceSearchLimits(excludedPaths = 2),
                openInput = { textOpens++; it.inputStream() },
                traversal = WorkspaceDiscoveryTraversal { traversalOpens++; Files.newDirectoryStream(it.toPath()) })
            assertEquals(setOf(WorkspaceSearchStopReason.EXCLUSION_LIMIT), page.searchStopReasons)
            assertTrue(page.hits.isEmpty())
            assertEquals(0, captures)
            assertEquals(0, textOpens)
            assertEquals(0, traversalOpens)
        }
    }

    @Test(timeout = 15_000) fun captureDeadlineIsTheTraversalDeadline(): Unit = runBlocking {
        fixture { root ->
            root.resolve("a.kt").writeText("needle saved\n")
            var now = 0L
            var opens = 0
            val live = snapshot("a.kt", "needle live\n")
            val documents = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String) = live
                override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("legacy")
                override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                    val captured = request.captureFiniteSnapshots(listOf(live))
                    now = 6L
                    return captured
                }
            }
            val page = FileRepository(root).searchPageBounded("needle", documents = documents,
                limits = WorkspaceSearchLimits(durationNanos = 5), nanoTime = { now },
                openInput = { opens++; it.inputStream() },
                traversal = WorkspaceDiscoveryTraversal { opens++; Files.newDirectoryStream(it.toPath()) })
            assertTrue(page.hits.isEmpty())
            assertFalse(page.dirtyCaptureComplete)
            assertEquals(setOf(WorkspaceSearchStopReason.DEADLINE), page.searchStopReasons)
            assertEquals(setOf(DiscoveryStopReason.DEADLINE), page.scanStopReasons)
            assertEquals(0, opens)
        }
    }

    @Test(timeout = 15_000) fun eligibleOverlayAndSuppressedCountsAreDistinct(): Unit = runBlocking {
        fixture { root ->
            listOf("active.kt", "binary.kt", "ignored.kt").forEach { root.resolve(it).writeText("needle saved\n") }
            root.resolve(".gitignore").writeText("ignored.kt\n")
            val page = FileRepository(root).searchPage("needle", documents = authority(
                snapshot("active.kt", "needle live\n"),
                snapshot("binary.kt", "needle hidden\n", editable = false),
                snapshot("ignored.kt", "needle ignored\n"),
            ))
            assertFalse(page.scanLimitReached)
            assertTrue(page.dirtyCaptureComplete)
            assertEquals(listOf("active.kt"), page.hits.map { it.path })
            assertEquals(1, page.dirtyOverlays)
            assertEquals(2, page.diskExcluded)
            assertEquals(1, page.skippedDirtyBuffers)
            assertEquals(3, page.searchStats.snapshotsCaptured)
        }
    }

    @Test(timeout = 15_000) fun unsupportedBoundAuthorityFailsClosedButUnboundBridgeSearchesDisk(): Unit = runBlocking {
        fixture { root ->
            root.resolve("a.kt").writeText("needle\n")
            val files = FileRepository(root)
            val bridge = WorkspaceDocumentAuthorityBridge()
            assertEquals(1, files.searchPage("needle", documents = bridge).hits.size)
            bridge.bind(object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = error("legacy snapshot")
                override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("legacy snapshots")
                override suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> = error("legacy dirty snapshots")
            })
            val page = files.searchPage("needle", documents = bridge)
            assertTrue(page.hits.isEmpty())
            assertTrue(page.scanLimitReached)
            assertFalse(page.dirtyCaptureComplete)
            assertEquals(setOf(WorkspaceSearchStopReason.UNSUPPORTED_AUTHORITY), page.searchStopReasons)
            assertEquals(0, page.scannedEntries)
            assertEquals(0, page.searchStats.sourceOpens)
        }
    }

    @Test(timeout = 15_000) fun rawAdmissionRunsBeforeTrimAndAnyCapture(): Unit = runBlocking {
        fixture { root ->
            var captures = 0
            val documents = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
                override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("legacy")
                override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                    captures++
                    return WorkspaceSearchCapture()
                }
            }
            val page = FileRepository(root).searchPageBounded("  needle  ", documents = documents,
                limits = WorkspaceSearchLimits(rawInputChars = 8))
            assertEquals(setOf(WorkspaceSearchStopReason.INPUT_LIMIT), page.searchStopReasons)
            assertEquals(0, captures)
            assertEquals(0, page.scannedEntries)
            assertEquals(0, page.searchStats.sourceOpens)
        }
    }

    @Test(timeout = 15_000) fun sniffAndDecodeBytesAndReadsShareTheBudget(): Unit = runBlocking {
        fixture { root ->
            root.resolve("a.kt").writeText("needle\n")
            val page = FileRepository(root).searchPage("needle")
            assertFalse(page.scanLimitReached)
            assertEquals(1, page.hits.size)
            assertEquals(2, page.searchStats.sourceOpens)
            assertEquals(3, page.searchStats.readCalls)
            assertEquals(14L, page.searchStats.diskBytes)
            assertEquals(1, page.searchStats.lines)
        }
    }
}

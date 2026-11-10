package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Current API only. The coordinator records the actual pre-fix result. */
class AgentSearchSnapshotGenerationBaselineTest {
    @Test(timeout = 15_000) fun searchHitsAndOverlayStateUseOneSnapshotAcquisition(): Unit = runBlocking {
        val root = Files.createTempDirectory("droide-search-generation-").toFile()
        try {
            root.resolve("active.kt").writeText("needle saved\n")
            var acquisitions = 0
            val live = WorkspaceDocumentSnapshot(
                "active.kt", "needle live\n", true, WorkspaceDocumentKind.TEXT,
                true, true, 1, 1, 0, 0, 0,
            )
            val authority = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String) = live.takeIf { it.path == path }
                override suspend fun snapshots() = listOf(live)
                override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest) =
                    request.captureFiniteSnapshots(dirtySnapshots())
                override suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> {
                    acquisitions++
                    // A later editor generation must not overwrite reporting for already computed hits.
                    return if (acquisitions == 1) listOf(live) else emptyList()
                }
            }
            val repository = FileRepository(root)
            assertEquals("needle saved\n", repository.readText("active.kt"))
            val result = AgentWorkspaceSearch(repository, authority).search("needle")
            assertTrue(result.contains("active.kt:1: needle live"))
            assertFalse(result.contains("needle saved"))
            assertEquals("Search and result formatting must share one snapshot acquisition", 1, acquisitions)
            assertTrue(result.contains("[DROIDE_SEARCH_STATE dirty_overlays=1 disk_excluded=1]"))
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}

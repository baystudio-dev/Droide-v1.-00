package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchCaptureBridgeTest {
    @Test(timeout = 15_000) fun rebindAndOldCloseCannotSpliceResultGenerations(): Unit = runBlocking {
        val root = Files.createTempDirectory("droide-search-bridge-").toFile()
        try {
            root.resolve("a.kt").writeText("needle saved\n")
            val bridge = WorkspaceDocumentAuthorityBridge()
            fun live(text: String, version: Int) = WorkspaceDocumentSnapshot(
                "a.kt", text, true, WorkspaceDocumentKind.TEXT, true, true, version, version, 0, 0, 0,
            )
            val old = live("needle old live\n", 1)
            val newer = live("needle new live\n", 2)
            var oldCaptures = 0
            var newCaptures = 0
            var newBinding: WorkspaceDocumentAuthorityBridge.Binding? = null
            lateinit var oldBinding: WorkspaceDocumentAuthorityBridge.Binding
            val newAuthority = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String) = error("Search must use its capture")
                override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("Legacy list forbidden")
                override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                    newCaptures++
                    return request.captureFiniteSnapshots(listOf(newer))
                }
            }
            oldBinding = bridge.bind(object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String) = error("Search must use its capture")
                override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("Legacy list forbidden")
                override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
                    oldCaptures++
                    newBinding = bridge.bind(newAuthority)
                    oldBinding.close()
                    return request.captureFiniteSnapshots(listOf(old))
                }
            })
            val search = AgentWorkspaceSearch(FileRepository(root), bridge)
            val first = search.search("needle")
            assertTrue(first.contains("needle old live"))
            assertFalse(first.contains("needle new live"))
            assertTrue(first.contains("[DROIDE_SEARCH_STATE dirty_overlays=1 disk_excluded=1]"))
            assertEquals(1, oldCaptures)
            assertEquals(0, newCaptures)
            assertTrue(bridge.isBound)
            val second = search.search("needle")
            assertTrue(second.contains("needle new live"))
            assertEquals(1, oldCaptures)
            assertEquals(1, newCaptures)
            newBinding?.close()
            assertFalse(bridge.isBound)
            assertEquals("needle saved\n", FileRepository(root).readText("a.kt"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

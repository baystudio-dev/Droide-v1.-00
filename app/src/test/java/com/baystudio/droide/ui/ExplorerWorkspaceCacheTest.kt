package com.baystudio.droide.ui

import com.baystudio.droide.core.DirectoryPage
import com.baystudio.droide.core.DirectoryPageCursor
import com.baystudio.droide.core.FileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ExplorerWorkspaceCacheTest {
    private fun directory(path: String) = FileEntry(path, true, 0, 0)
    private fun file(path: String) = FileEntry(path, false, 4, 0)
    private fun page(vararg entries: FileEntry) = DirectoryPage(entries.toList(), null)
    private fun populated(): ExplorerWorkspaceState = ExplorerWorkspaceState().apply {
        expandedPaths = listOf("src", "src/sub", "other")
        children[""] = listOf(directory("src"), directory("other"))
        children["src"] = listOf(directory("src/sub"), file("src/main.kt"))
        children["src/sub"] = listOf(file("src/sub/child.kt"))
        children["other"] = listOf(file("other/keep.kt"))
        moreAfter["src"] = DirectoryPageCursor("src/main.kt", false)
        moreAfter["src/sub"] = DirectoryPageCursor("src/sub/child.kt", false)
        moreAfter["other"] = DirectoryPageCursor("other/keep.kt", false)
    }

    @Test fun collapseDropsWholeSubtreePagesButRetainsRootOtherPagesAndExpansionIntent() {
        val state = populated()
        val rootPage = state.children[""]
        val otherPage = state.children["other"]
        state.collapseDirectory("src")
        assertSame(rootPage, state.children[""])
        assertSame(otherPage, state.children["other"])
        assertEquals(setOf("", "other"), state.children.keys)
        assertEquals(setOf("other"), state.moreAfter.keys)
        assertEquals(listOf("src/sub", "other"), state.expandedPaths)
        assertFalse(state.isDirectoryPageVisible("src/sub"))
        assertNull(state.firstMissingExpandedDirectory())
        assertEquals(1, state.pageGeneration)
    }

    @Test fun reopeningParentRestoresRetainedDescendantIntentFromFreshPages() = runTest {
        val state = populated()
        state.collapseDirectory("src")
        state.expandedPaths = state.expandedPaths + "src"
        assertEquals("src", state.firstMissingExpandedDirectory())
        assertTrue(state.loadExpandedDirectoryPage("src") { page(directory("src/sub"), file("src/new.kt")) })
        assertEquals("src/sub", state.firstMissingExpandedDirectory())
        assertTrue(state.loadExpandedDirectoryPage("src/sub") { page(file("src/sub/fresh.kt")) })
        assertNull(state.firstMissingExpandedDirectory())
        assertEquals(listOf("src/sub/fresh.kt"), state.children["src/sub"]!!.map { it.path })
    }

    @Test fun lateDirectoryReadCannotRepopulateCollapsedCache() = runTest {
        val state = populated().apply { children.remove("src") }
        val response = CompletableDeferred<DirectoryPage>()
        val admitted = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadExpandedDirectoryPage("src") { admitted.complete(Unit); response.await() }
        }
        admitted.await()
        state.collapseDirectory("src")
        response.complete(page(directory("src/sub"), file("src/stale.kt")))
        assertFalse(operation.await())
        assertFalse("src" in state.children)
        assertFalse("src/sub" in state.children)
        assertFalse("src" in state.moreAfter)
    }

    @Test fun lateOldGenerationCannotReplaceReopenedDirectoryFreshResult() = runTest {
        val state = populated().apply { children.remove("src") }
        val oldResponse = CompletableDeferred<DirectoryPage>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadExpandedDirectoryPage("src") { oldResponse.await() }
        }
        state.collapseDirectory("src")
        state.expandedPaths = state.expandedPaths + "src"
        assertTrue(state.loadExpandedDirectoryPage("src") { page(file("src/fresh.kt")) })
        oldResponse.complete(page(file("src/stale.kt")))
        assertFalse(operation.await())
        assertEquals(listOf("src/fresh.kt"), state.children["src"]!!.map { it.path })
    }

    @Test fun cancelledDirectoryReadCannotPublishEvenIfLoaderFinishesNonCancellableCleanup() = runTest {
        val state = populated().apply { children.remove("src") }
        val response = CompletableDeferred<DirectoryPage>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadExpandedDirectoryPage("src") { withContext(NonCancellable) { response.await() } }
        }
        operation.cancel()
        response.complete(page(file("src/cancelled.kt")))
        operation.join()
        assertTrue(operation.isCancelled)
        assertFalse("src" in state.children)
    }

    @Test fun refreshRemovesDeletedPagesAndOrphanIntentCannotBlockVisibleDirectoryLoads() {
        val state = populated()
        val generation = ++state.pageGeneration
        assertTrue(state.publishRefreshedDirectoryPages(generation, state.refreshToken,
            mapOf("" to listOf(directory("other"))), emptyMap()))
        assertEquals(setOf(""), state.children.keys)
        assertTrue(state.moreAfter.isEmpty())
        assertEquals("other", state.firstMissingExpandedDirectory())
        assertFalse(state.isDirectoryPageVisible("src"))
        assertFalse(state.isDirectoryPageVisible("src/sub"))
        assertTrue("src/sub" in state.expandedPaths)
    }

    @Test fun failedDirectoryExclusionDoesNotBlockOtherVisibleExpansionOrDescendantLoading() {
        val state = populated().apply { children.remove("src"); children.remove("other") }
        assertEquals("other", state.firstMissingExpandedDirectory())
        assertEquals("src", state.firstMissingExpandedDirectory(setOf("other")))
        assertNull(state.firstMissingExpandedDirectory(setOf("src", "other")))
        // The retained descendant is not loadable until a valid parent directory page exists.
        assertFalse(state.isDirectoryPageVisible("src/sub"))
    }

    @Test fun refreshPublishesOnlyAdmittedGenerationAndNeverMarksANewerTokenLoaded() {
        val state = populated()
        val requestToken = state.refreshToken
        val generation = ++state.pageGeneration
        state.requestRefresh()
        assertTrue(state.publishRefreshedDirectoryPages(generation, requestToken,
            mapOf("" to listOf(directory("src"))), emptyMap()))
        assertEquals(requestToken, state.loadedRefreshToken)
        assertNotEquals(state.refreshToken, state.loadedRefreshToken)
        state.collapseDirectory("src")
        val rootPage = state.children[""]
        assertFalse(state.publishRefreshedDirectoryPages(generation, requestToken,
            mapOf("" to listOf(directory("stale"))), emptyMap()))
        assertSame(rootPage, state.children[""])
    }

    @Test fun hundredsOfExpandCollapseCyclesRetainNoDirectoryPages() = runTest {
        val state = ExplorerWorkspaceState().apply { children[""] = listOf(directory("src")) }
        repeat(300) { iteration ->
            state.expandedPaths = listOf("src")
            assertTrue(state.loadExpandedDirectoryPage("src") {
                DirectoryPage(List(250) { file("src/$iteration-file-$it") }, DirectoryPageCursor("src/last", false))
            })
            state.collapseDirectory("src")
            assertEquals(setOf(""), state.children.keys)
            assertTrue(state.moreAfter.isEmpty())
        }
        state.collapseDirectory("")
        assertEquals(setOf(""), state.children.keys)
    }

    @Test fun iterativeTreeWalkPreservesOrderDepthAndLoadMoreRows() {
        val state = populated()
        val rows = flattenExplorerRows(state.children, state.expandedPaths, state.moreAfter)
        assertEquals(listOf("src", "src/sub", "src/sub/child.kt", null, "src/main.kt", null, "other", "other/keep.kt", null),
            rows.map { it.entry?.path })
        assertEquals(listOf(0, 1, 2, 2, 1, 1, 0, 1, 1), rows.map { it.depth })
        assertEquals(listOf("src/sub", "src", "other"), rows.filter { it.entry == null }.map { it.parent })
    }

    @Test fun deepRetainedTreeUsesNoRecursiveCallStack() {
        val children = linkedMapOf<String, List<FileEntry>>()
        val expanded = mutableListOf<String>()
        var parent = ""
        repeat(10_000) { index ->
            val path = "directory-$index"
            children[parent] = listOf(directory(path))
            expanded += path
            parent = path
        }
        val rows = flattenExplorerRows(children, expanded, emptyMap())
        assertEquals(10_000, rows.size)
        assertEquals(9_999, rows.last().depth)
        assertEquals("directory-9999", rows.last().entry?.path)
    }

    @Test fun cyclicOrDuplicateCachedEntriesCannotCreateDuplicateLazyKeys() {
        val child = directory("src")
        val leaf = file("src/main.kt")
        val rows = flattenExplorerRows(mapOf("" to listOf(child, child), "src" to listOf(child, leaf, leaf)),
            listOf("src"), mapOf("src" to DirectoryPageCursor(leaf.path, false)))
        assertEquals(listOf("src", "src/main.kt", null), rows.map { it.entry?.path })
        assertEquals("src", rows.last().parent)
    }
}

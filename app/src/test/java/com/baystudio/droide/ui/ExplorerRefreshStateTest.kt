package com.baystudio.droide.ui

import com.baystudio.droide.core.DirectoryPage
import com.baystudio.droide.core.DirectoryPageCursor
import com.baystudio.droide.core.FileEntry
import org.junit.Assert.*
import org.junit.Test

class ExplorerRefreshStateTest {
    private fun dir(path: String) = FileEntry(path, true, 0, 0)
    private fun file(path: String) = FileEntry(path, false, 1, 0)

    @Test fun cleanedQueryPredicateExactlyMatchesRenderingIncludingWildcardAndSpaceEdges() {
        listOf("", " ", "a", "ab", "*a?[]", "**ab**", " [*?] ", " [ * ? ] ", "a".repeat(200)).forEach { query ->
            val previousRendering = query.trim().filterNot { it == '*' || it == '?' || it == '[' || it == ']' }.length < 2
            assertEquals(query, previousRendering, explorerTreeMode(query))
        }
        assertEquals(80, cleanedExplorerQuery("a".repeat(200)).length)
    }

    @Test fun unchangedVisibleRowKeysGainAnEmptyDirectoryPageWhenExpanded() {
        val state = ExplorerWorkspaceState().apply { children[""] = listOf(dir("empty")) }
        val sameKeys = setOf("entry:empty")
        assertEquals(setOf(""), explorerViewportPages(state, sameKeys))
        state.expandedPaths = listOf("empty")
        assertEquals(setOf("", "empty"), explorerViewportPages(state, sameKeys))
        assertEquals(setOf("empty"), explorerViewportPages(state, setOf("more:empty")))
        state.collapseDirectory("empty")
        assertEquals(setOf(""), explorerViewportPages(state, sameKeys))
    }

    @Test fun completePatchPreservesUnvisitedPagesCursorsAndOtherDirectoryFailure() {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "b")
            children["a"] = listOf(file("a/old")); children["b"] = listOf(file("b/keep"))
            moreAfter["b"] = DirectoryPageCursor("b/keep", false, options)
            pageFreshness["b"] = ExplorerPageFreshness(failure = "unreadable", failedAt = 5)
        }
        assertTrue(state.patchDirectoryPage("a", state.pageGeneration, DirectoryPage(listOf(file("a/new")), null)))
        assertEquals("b/keep", state.children["b"]!!.single().path)
        assertNotNull(state.moreAfter["b"])
        assertEquals("unreadable", state.pageFreshness["b"]!!.failure)
        assertEquals(-1, state.loadedRefreshToken)
    }

    @Test fun onlyAuthoritativeParentReplacementPrunesDescendantsAndRefreshMetadata() {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(dir("a"), dir("b")); expandedPaths = listOf("a", "a/sub", "b")
            children["a"] = listOf(dir("a/sub")); children["a/sub"] = listOf(file("a/sub/old")); children["b"] = emptyList()
            pageFreshness["a/sub"] = ExplorerPageFreshness(failure = "old failure", failedAt = 1)
        }
        state.patchDirectoryPage("a", state.pageGeneration, DirectoryPage(emptyList(), null))
        assertFalse("a/sub" in state.children)
        assertFalse("a/sub" in state.pageFreshness)
        assertTrue("b" in state.children)
        assertTrue("a/sub" in state.expandedPaths)
    }

    @Test fun collapseCyclesDoNotRetainPerEverSeenDirectoryFailureRecords() {
        val state = ExplorerWorkspaceState()
        repeat(300) { index ->
            val path = "directory$index"
            state.children[""] = listOf(dir(path)); state.expandedPaths = listOf(path)
            state.pageFreshness[path] = ExplorerPageFreshness(failure = "unreadable", failedAt = index.toLong())
            state.collapseDirectory(path)
            assertTrue(state.pageFreshness.isEmpty())
        }
    }
}

package com.baystudio.droide.ui

import com.baystudio.droide.core.DirectoryListingOptions
import com.baystudio.droide.core.DirectoryPage
import com.baystudio.droide.core.DirectoryPageCursor
import com.baystudio.droide.core.ExplorerSortOrder
import com.baystudio.droide.core.FileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Restored Explorer state cannot route new project content into Droide's runtime tree. */
class ExplorerSelectionStateTest {
    private fun directory(path: String) = FileEntry(path, true, 0, 0)
    private fun file(path: String) = FileEntry(path, false, 4, 0)

    @Test fun restoredInternalDestinationExpansionAndSearchAreRemovedTogether() {
        val state = ExplorerWorkspaceState().apply {
            activeDirectory = ".droide/run"
            expandedPaths = listOf("src", ".droide", ".droide/run")
            children[""] = listOf(directory("src"), directory(".droide"))
            children[".droide/run"] = listOf(file(".droide/run/generated.kt"))
            moreAfter[".droide/run"] = DirectoryPageCursor(".droide/run/generated.kt", false)
            searchResults = listOf("src/main.kt", ".droide/MainActivity.kt")
            searchNotice = "Old results"
        }
        val admittedGeneration = state.pageGeneration
        state.sanitizeInternalSelection()
        assertEquals("", state.activeDirectory)
        assertEquals(listOf("src"), state.expandedPaths)
        assertTrue(state.children.isEmpty())
        assertTrue(state.moreAfter.isEmpty())
        assertEquals(listOf("src/main.kt"), state.searchResults)
        assertNull(state.searchNotice)
        assertTrue(state.pageGeneration > admittedGeneration)
        assertFalse(state.publishRefreshedDirectoryPages(admittedGeneration, 0,
            mapOf("" to listOf(directory(".droide"))), emptyMap()))
    }

    @Test fun orphanInternalCursorInvalidatesLatePageWithoutDroppingProjectSelection() {
        val state = ExplorerWorkspaceState().apply {
            activeDirectory = "src"
            children[""] = listOf(directory("src"))
            moreAfter[".droide/tmp"] = DirectoryPageCursor(".droide/tmp/last", false)
        }
        val admittedGeneration = state.pageGeneration
        state.sanitizeInternalSelection()
        assertEquals("src", state.activeDirectory)
        assertTrue(state.moreAfter.isEmpty())
        assertTrue(state.pageGeneration > admittedGeneration)
        assertFalse(state.publishRefreshedDirectoryPages(admittedGeneration, 0,
            mapOf("" to listOf(file("stale.kt"))), emptyMap()))
    }

    @Test fun fileSelectionUsesItsParentAndExplicitRootSelectionClearsDestination() {
        val state = ExplorerWorkspaceState()
        state.selectEntry(directory("src/sub"))
        assertEquals("src/sub", state.activeDirectory)
        state.selectEntry(file("src/main.kt"))
        assertEquals("src", state.activeDirectory)
        state.selectEntry(file("README.md"))
        assertEquals("", state.activeDirectory)
        state.selectEntry(directory(".droide/run"))
        assertEquals("", state.activeDirectory)
        state.selectEntry(directory("src"))
        state.selectRoot()
        assertEquals("", state.activeDirectory)
    }

    @Test fun sortChangeRejectsPendingExpandedPageAndPreservesProjectDestination() = runTest {
        val state = ExplorerWorkspaceState().apply {
            activeDirectory = "src"
            expandedPaths = listOf("src")
            children[""] = listOf(directory("src"))
            moreAfter[""] = DirectoryPageCursor("src", true, options)
            treeFirstVisibleItemIndex = 20
            treeFirstVisibleItemScrollOffset = 15
        }
        val pending = CompletableDeferred<DirectoryPage>()
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadExpandedDirectoryPage("src") { pending.await() }
        }
        state.changeSortOrder(ExplorerSortOrder.NAME_DESC)
        pending.complete(DirectoryPage(listOf(file("src/late.kt")), null))
        assertFalse(read.await())
        assertEquals("src", state.activeDirectory)
        assertEquals(listOf("src"), state.expandedPaths)
        assertTrue(state.children.isEmpty())
        assertTrue(state.moreAfter.isEmpty())
        assertEquals(0, state.treeFirstVisibleItemIndex)
        assertEquals(0, state.treeFirstVisibleItemScrollOffset)
        assertEquals(DirectoryListingOptions(ExplorerSortOrder.NAME_DESC, hideDroideInternal = true), state.options)
    }

    @Test fun restoredCursorWithDifferentSortPolicyCannotBeAppended() {
        val state = ExplorerWorkspaceState().apply {
            children[""] = listOf(file("a.kt"))
            moreAfter[""] = DirectoryPageCursor("a.kt", false, DirectoryListingOptions())
        }
        val admittedGeneration = state.pageGeneration
        state.sanitizeInternalSelection()
        assertTrue(state.children.isEmpty())
        assertTrue(state.moreAfter.isEmpty())
        assertTrue(state.pageGeneration > admittedGeneration)
    }

    @Test fun collapsingSelectedSubtreeSelectsVisibleParentAndPreservesOtherSelection() {
        val state = ExplorerWorkspaceState().apply {
            activeDirectory = "src/sub/deeper"
            expandedPaths = listOf("src", "src/sub", "src/sub/deeper", "other")
            children[""] = listOf(directory("src"), directory("other"))
            children["src"] = listOf(directory("src/sub"))
            children["src/sub"] = listOf(directory("src/sub/deeper"))
            children["src/sub/deeper"] = listOf(file("src/sub/deeper/main.kt"))
        }
        state.collapseDirectory("src/sub")
        assertEquals("src", state.activeDirectory)
        assertFalse("src/sub" in state.children)
        assertFalse("src/sub/deeper" in state.children)
        state.selectEntry(directory("other"))
        state.collapseDirectory("src")
        assertEquals("other", state.activeDirectory)
        state.selectEntry(directory("other"))
        state.collapseDirectory("other")
        assertEquals("", state.activeDirectory)
    }
}

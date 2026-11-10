package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class ExplorerRenameSelectionTest {
    @Test fun selectedFileFollowsRename() {
        val state = ExplorerWorkspaceState().apply { selectedPath = "src/old.kt"; activeDirectory = "src" }
        state.renamed("src/old.kt", "src/new.kt")
        assertEquals("src/new.kt", state.selectedPath)
        assertEquals("src", state.activeDirectory)
        assertNull(state.pendingReveal)
    }
    @Test fun selectedDescendantAndPendingRevealFollowParentRename() {
        val state = ExplorerWorkspaceState().apply {
            selectedPath = "src/sub/main.kt"; pendingReveal = selectedPath
            activeDirectory = "src/sub"; expandedPaths = listOf("src", "src/sub", "src2")
        }
        state.renamed("src", "source")
        assertEquals("source/sub/main.kt", state.selectedPath)
        assertEquals("source/sub/main.kt", state.pendingReveal)
        assertEquals("source/sub", state.activeDirectory)
        assertEquals(listOf("source", "source/sub", "src2"), state.expandedPaths)
    }
    @Test fun unrelatedPrefixAndNullSelectionStayUnchanged() {
        val state = ExplorerWorkspaceState().apply {
            selectedPath = "src2/a.kt"; pendingReveal = "src2"; activeDirectory = "src2"
        }
        state.renamed("src", "source")
        assertEquals("src2/a.kt", state.selectedPath)
        assertEquals("src2", state.pendingReveal)
        assertEquals("src2", state.activeDirectory)
        state.selectRoot()
        state.renamed("src", "source")
        assertNull(state.selectedPath)
        assertNull(state.pendingReveal)
        assertEquals("", state.activeDirectory)
    }
}

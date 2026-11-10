package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class ExplorerExportPolicyTest {
    @Test fun rootIncludesHiddenAndFilteredPaths() {
        listOf(".git/objects/a", ".env", ".droide/state", "node_modules/a", "build/a").forEach {
            assertTrue(exportPathInScope(it, ""))
        }
    }
    @Test fun folderScopeHasPathSegmentBoundary() {
        assertTrue(exportPathInScope("src", "src"))
        assertTrue(exportPathInScope("src/main/a.kt", "src"))
        assertFalse(exportPathInScope("src2/main/a.kt", "src"))
        assertFalse(exportPathInScope("other/src/a.kt", "src"))
    }
    @Test fun entryTitlesDoNotConfuseWorkspaceWithFolder() {
        assertEquals("Export entire workspace", exportScopeTitle("", true))
        assertEquals("Export folder", exportScopeTitle("src", true))
        assertEquals("Export file", exportScopeTitle("src/a.kt", false))
    }
    @Test fun destinationIsOnlyALeafName() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "a\nfile", "a\u0000").forEach { assertFalse(validExportName(it)) }
        assertTrue(validExportName("my project.tar"))
    }
}

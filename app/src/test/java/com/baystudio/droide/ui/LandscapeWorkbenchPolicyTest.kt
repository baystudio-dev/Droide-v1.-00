package com.baystudio.droide.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class LandscapeWorkbenchPolicyTest {
    @Test fun bothCompanionPanesRemainInBudgetAndClosingEitherReturnsItsSpaceToTheEditor() {
        for (width in listOf(320, 480, 600, 720, 840, 1024, 1280, 1600, 2400)) {
            val layout = droideWorkbenchLayout(width.dp, 360.dp, 210.dp, physicalLandscape = true)
            for (tool in DroideRailTool.entries) for (request in listOf(0, 176, 336, 520)) {
                fun fit(sidebar: Boolean, chat: Boolean) = droidePaneFit(layout, width.dp, tool, sidebar, chat, request.dp)
                val both = fit(true, true)
                val onlyChat = fit(false, true)
                val onlySidebar = fit(true, false)
                val editor = fit(false, false)
                assertTrue("Sidebar missing at $width", both.sidebarWidth > 0.dp)
                assertTrue("Chat missing at $width", both.agentWidth > 0.dp)
                assertTrue("Editor missing at $width", both.editorViewportWidth > 0.dp)
                assertEquals(both.agentWidth, onlyChat.agentWidth)
                assertEquals(both.sidebarWidth, onlySidebar.sidebarWidth)
                assertEquals(both.editorViewportWidth.value + both.sidebarWidth.value + 1f, onlyChat.editorViewportWidth.value, .02f)
                assertEquals(both.editorViewportWidth.value + both.agentWidth.value + 10f, onlySidebar.editorViewportWidth.value, .02f)
                for (pane in listOf(both, onlyChat, onlySidebar, editor)) {
                    assertEquals(0f, pane.workbenchOverflowWidth.value, .02f)
                    assertEquals((width.dp - DroideDimensions.ActivityRail).value, pane.workbenchContentWidth.value, .02f)
                    assertTrue(pane.agentMinWidth <= pane.agentWidth && pane.agentWidth <= pane.agentMaxWidth)
                }
            }
        }
    }

    @Test fun tabletChatCanBeResizedWithoutOverlappingTheEditorOrSidebar() {
        val layout = droideWorkbenchLayout(1280.dp, 800.dp, 210.dp, physicalLandscape = true)
        val normal = droidePaneFit(layout, 1280.dp, DroideRailTool.Files, true, true, 336.dp)
        val wider = droidePaneFit(layout, 1280.dp, DroideRailTool.Files, true, true, 460.dp)
        assertEquals(336.dp, normal.agentWidth)
        assertTrue(wider.agentWidth > normal.agentWidth)
        assertEquals(normal.sidebarWidth, wider.sidebarWidth)
        assertTrue(wider.editorViewportWidth >= 400.dp)
    }

    @Test fun verySmallWindowsAndInvalidRestoredSizesStayFinite() {
        for (width in listOf(0, 1, 40, 52, 60, 64, 120)) {
            val layout = droideWorkbenchLayout(width.dp, 80.dp, 210.dp, physicalLandscape = true)
            for (request in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
                val fit = droidePaneFit(layout, width.dp, DroideRailTool.Files, true, true, request.dp)
                assertTrue(fit.editorViewportWidth.value.isFinite())
                assertTrue(fit.sidebarWidth >= 0.dp && fit.agentWidth >= 0.dp)
                assertEquals(0f, fit.workbenchOverflowWidth.value, .02f)
            }
        }
    }

    @Test fun headerReclaimsSpaceOnlyForLandscapeEditorOrExpandedTerminal() {
        assertFalse(droideHideLandscapeHeader(false, true, true))
        assertFalse(droideHideLandscapeHeader(true, false, false))
        assertTrue(droideHideLandscapeHeader(true, true, false))
        assertTrue(droideHideLandscapeHeader(true, false, true))
    }
}

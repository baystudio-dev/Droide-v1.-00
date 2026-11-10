package com.baystudio.droide.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

/** Geometry invariants prevent inaccessible panels after removal of the virtual-workbench slider. */
class WorkbenchPaneBudgetTest {
    @Test fun everyPaneCombinationFitsTheWindowAndKeepsValidResizeBounds() {
        var cases = 0
        for (width in listOf(0, 40, 52, 180, 360, 506, 600, 640, 720, 840, 1024, 1280, 1600, 2400))
        for (height in listOf(360, 960))
        for (sidebar in listOf(false, true))
        for (agent in listOf(false, true))
        for (tool in DroideRailTool.entries)
        for (request in listOf(0, 264, 336, 1024)) {
            val layout = droideWorkbenchLayout(width.dp, height.dp, 210.dp)
            val fit = droidePaneFit(layout, width.dp, tool, sidebar, agent, request.dp)
            val usable = (width.dp - DroideDimensions.ActivityRail).coerceAtLeast(0.dp).value
            val label = "width=$width height=$height sidebar=$sidebar agent=$agent tool=$tool request=$request"
            assertTrue(label, fit.workbenchContentWidth.value <= usable + .01f)
            assertTrue(label, fit.workbenchOverflowWidth.value <= .01f)
            assertTrue(label, fit.editorViewportWidth.value in 0f..(usable + .01f))
            assertEquals(label, fit.editorViewportWidth.value, fit.editorCanvasWidth.value, .01f)
            assertTrue(label, fit.agentMinWidth <= fit.agentMaxWidth)
            assertTrue(label, fit.agentWidth >= fit.agentMinWidth && fit.agentWidth <= fit.agentMaxWidth)
            if (!sidebar) assertEquals(label, 0f, fit.sidebarWidth.value, .01f)
            if (!agent) assertEquals(label, 0f, fit.agentWidth.value, .01f)
            cases++
        }
        assertEquals(2688, cases)
    }

    @Test fun aPhoneKeepsTheEditorAndAgentUsableInsteadOfHidingTheAgentPastTheScreenEdge() {
        val layout = droideWorkbenchLayout(640.dp, 360.dp, 210.dp)
        val fit = droidePaneFit(layout, 640.dp, DroideRailTool.Run, true, true, 336.dp)
        assertTrue(fit.sidebarWidth >= 140.dp)
        assertTrue(fit.agentWidth >= layout.agentPanelMinWidth)
        assertTrue(fit.editorViewportWidth >= 180.dp)
        assertEquals(0f, fit.workbenchOverflowWidth.value, .01f)
        val sidebarOnly = droidePaneFit(layout, 640.dp, DroideRailTool.Run, true, false, 336.dp)
        assertEquals(fit.sidebarWidth, sidebarOnly.sidebarWidth)
        assertTrue(sidebarOnly.editorViewportWidth >= 180.dp)
    }

    @Test fun aWideWindowKeepsBothSidePanelsAndHonorsTheRequestedAgentWidth() {
        val layout = droideWorkbenchLayout(1280.dp, 800.dp, 210.dp)
        val fit = droidePaneFit(layout, 1280.dp, DroideRailTool.Files, true, true, 296.dp)
        assertEquals(layout.sidebarWidth.value, fit.sidebarWidth.value, .01f)
        assertEquals(296f, fit.agentWidth.value, .01f)
        assertTrue(fit.editorViewportWidth >= layout.minimumEditorWidth)
        assertEquals(0f, fit.workbenchOverflowWidth.value, .01f)
    }

    @Test fun invalidRestoredAgentWidthsCannotCreateNonFinitePaneMeasurements() {
        val layout = droideWorkbenchLayout(840.dp, 360.dp, 210.dp)
        for (request in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val fit = droidePaneFit(layout, 840.dp, DroideRailTool.Files, true, true, request.dp)
            assertTrue(fit.agentWidth.value.isFinite())
            assertTrue(fit.editorViewportWidth.value.isFinite())
            assertTrue(fit.workbenchContentWidth.value.isFinite())
            assertEquals(0f, fit.workbenchOverflowWidth.value, .01f)
        }
    }
}

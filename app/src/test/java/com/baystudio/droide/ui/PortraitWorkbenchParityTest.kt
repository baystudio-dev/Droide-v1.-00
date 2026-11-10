package com.baystudio.droide.ui

import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden captured from the complete, owner-approved pre-landscape source backup. */
class PortraitWorkbenchParityTest {
    @Test fun allPortraitPaneAndWindowValuesMatchTheApprovedBaseline() {
        val actual = buildString {
            for (width in listOf(240, 360, 600, 840, 1280)) for (height in listOf(160, 320, 800, 1400)) {
                val layout = droideWorkbenchLayout(width.dp, height.dp, 210.dp, physicalLandscape = false)
                appendLine(layout)
                for (tool in DroideRailTool.entries) for (sidebar in listOf(false, true))
                for (agent in listOf(false, true)) for (request in listOf(0, 176, 336, 520)) {
                    appendLine(droidePaneFit(layout, width.dp, tool, sidebar, agent, request.dp))
                }
            }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(actual.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("5d9d3653836db30cb97c836693f95d54506be7c77b55da0c3f4bd2def66e379a", fingerprint)
    }
}

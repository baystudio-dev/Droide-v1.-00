package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.ApprovalRequest
import com.baystudio.droide.core.ApprovalResolution
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveUiBoundsTest {
    @get:Rule val compose = createComposeRule()
    private val viewport = mutableStateOf(360.dp to 640.dp)
    private val scale = mutableStateOf(1f)
    private val direction = mutableStateOf(LayoutDirection.Ltr)

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value),
                LocalLayoutDirection provides direction.value) {
                DroideTheme {
                    Box(Modifier.size(viewport.value.first, viewport.value.second).testTag("viewport")) { content() }
                }
            }
        }
    }

    private fun textFitsVertically(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()

        assertTrue("$text extends below its viewport (${viewport.value}, font ${scale.value})", layout.getLineBottom(layout.lineCount - 1) <=
            node.fetchSemanticsNode().boundsInRoot.height + 1f)
    }

    private fun request() = ApprovalRequest(1, "browser", "Fill this form", "Details of the requested action.",
        permissionScope = "build", approvalSessionId = "session", sessionGrantAction = "browser",
        sessionGrantResource = "https://example.test")

    @Test fun panelTitleAndSubtitleRemainReadableAfterFontAndWindowChanges() {
        mount { DroidePanelHeader("Project", "Current workspace", onClose = {}, actions = {
            repeat(3) { DroideIconButton(Icons.Default.Add, "Action $it", {}) }
        }) }
        for (font in listOf(1f, 1.3f, 2f)) for (width in listOf(240, 360, 600, 1024)) {
            compose.runOnIdle { scale.value = font; viewport.value = width.dp to 400.dp }
            textFitsVertically("Project")
            textFitsVertically("Current workspace")
            compose.onNodeWithContentDescription("Close Project").assertIsDisplayed()
        }
    }

    @Test fun narrowHeaderActionsCanBeScrolledWithoutLosingCloseInEitherDirection() {
        viewport.value = 240.dp to 400.dp
        scale.value = 2f
        val clicks = mutableListOf<Int>()
        mount { DroidePanelHeader("Project", onClose = {}, actions = {
            repeat(4) { index -> DroideIconButton(Icons.Default.Add, "Action $index", { clicks += index }) }
        }) }
        for (rtl in listOf(false, true)) {
            compose.runOnIdle { direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr }
            compose.onNodeWithContentDescription("Action 3").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Close Project").assertIsDisplayed()
        }
        assertEquals(listOf(3, 3), clicks)
    }

    @Test fun primaryHeaderRetainsBothProjectAndFileAtLargeFont() {
        viewport.value = 1024.dp to 400.dp
        scale.value = 2f
        mount { DroideTopBar("Project", "App.kt", compact = false, agentOpen = false, landscape = true,
            focusMode = false, running = false, runLabel = "Run", runDescription = "Run file",
            onProjects = {}, onRun = {}, showProfessionalGroups = false, buildActions = emptyList(),
            debugActions = emptyList(), toolActions = emptyList(), onDevice = {}, onSettings = {},
            onAgent = {}, onFocusMode = {}, mcpHealth = emptyList(), onMcpRetry = {}, onManageMcp = {}, onMore = {}) }
        textFitsVertically("Project")
        textFitsVertically("App.kt")
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
    }

    @Test fun composerActionsAndUsageBadgeGrowWithTextWithoutLosingTheirClickTargets() {
        var clicks = 0
        mount { Row {
            AgentModelControl("openai", "luna", 24.dp, 15.dp, true, onClick = { clicks++ })
            AgentControlButton("Build", Icons.Default.Add, 24.dp, true, onClick = { clicks++ })
            AgentUsageHeaderButton(null, 360.dp, { clicks++ })
        } }
        for (font in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { scale.value = font }
            textFitsVertically("luna")
            textFitsVertically("Build")
            textFitsVertically("?")
            compose.onNodeWithText("Build").performClick()
        }
        assertEquals(3, clicks)
    }

    @Test fun shortPermissionAtLargeFontKeepsBothDecisionsReachable() {
        viewport.value = 240.dp to 180.dp
        scale.value = 2f
        val decisions = mutableListOf<ApprovalResolution>()
        mount { AgentApprovalContent(request(), viewport.value.second, decisions::add) }
        for (label in listOf("Reject", "Allow once", "Always allow for this session")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            textFitsVertically(label)
        }
        compose.onNodeWithText("Always allow for this session").performClick()
        assertEquals(listOf(ApprovalResolution.ALLOW_SESSION), decisions)
    }

    @Test fun resizingPermissionPreservesReadableContentAndAllDecisions() {
        viewport.value = 240.dp to 640.dp
        mount { AgentApprovalContent(request(), viewport.value.second) { error("Resize must not approve") } }
        for (font in listOf(1f, 1.3f, 2f)) for (height in listOf(180, 320, 640)) {
            compose.runOnIdle { scale.value = font; viewport.value = 240.dp to height.dp }
            for (label in listOf("Fill this form", "Reject", "Allow once", "Always allow for this session")) {
                val node = compose.onNodeWithText(label, useUnmergedTree = true)
                if (generateSequence(node.fetchSemanticsNode().parent) { it.parent }
                        .any { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }) {
                    node.performScrollTo()
                }
                textFitsVertically(label)
            }
        }
    }

    @Test fun browserFailureRecoveryRemainsReachableInAShortPane() {
        viewport.value = 240.dp to 220.dp
        scale.value = 2f
        val root = Files.createTempDirectory("adaptive-browser-").toFile()
        try {
            val browser = AgentBrowserController(root)
            mount { WorkspaceBrowserPane("", {}, browser, createWebView = { error("WebView unavailable. ".repeat(12)) }) }
            compose.onNodeWithText("Retry browser").performScrollTo().assertIsDisplayed()
            textFitsVertically("Retry browser")
        } finally { root.deleteRecursively() }
    }

    @Test fun editorTabsKeepLargeTextWithinTheTabStrip() {
        scale.value = 2f
        mount { DroideEditorTabs(listOf("App.kt", "Other.kt"), "App.kt", { false }, {}, {}) }
        textFitsVertically("App.kt")
        compose.onNodeWithContentDescription("Close App.kt").assertIsDisplayed()
    }

    @Test fun compactBottomPanelNeverRequestsMoreThanTheWindow() {
        for (height in listOf(0, 40, 120, 180, 240, 360, 800)) {
            val layout = droideWorkbenchLayout(840.dp, height.dp, 210.dp)
            assertTrue("height=$height", layout.effectiveBottomPanelHeight <= height.dp)
            assertTrue(layout.effectiveBottomPanelHeight >= 0.dp)
        }
        for (requested in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertTrue(droideWorkbenchLayout(840.dp, 360.dp, requested.dp).effectiveBottomPanelHeight.value.isFinite())
        }
    }

    @Test fun deepExplorerIndentationReservesContentAndActionsAtEveryPaneWidth() {
        for (width in listOf(170, 240, 360, 600, 1024)) for (depth in listOf(0, 1, 8, 100, Int.MAX_VALUE)) {
            val indent = explorerIndent(depth, width.dp, 160.dp)
            assertTrue(indent >= 0.dp)
            assertTrue("width=$width depth=$depth", indent + 160.dp <= width.dp)
        }
        assertEquals(28.dp, explorerIndent(2, 360.dp, 160.dp))
        assertEquals(0.dp, explorerIndent(10, 40.dp, 160.dp))
    }
}

package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
class EditorZoomToolbarTest {
    @get:Rule val compose = createComposeRule()
    private var width by mutableStateOf(360.dp)
    private var markdown by mutableStateOf(false)
    private var rtl by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)
    private var resetAvailable by mutableStateOf(true)
    private var resetClicks = 0
    private var symbolClicks = 0
    private fun mount() = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            DroideTheme {
                Box(Modifier.width(width).testTag("toolbar")) {
                    DroideEditorToolbar("app/src/main/example/VeryLongFileName.kt", true, true, {}, {}, {},
                        { symbolClicks++ }, {},
                        markdownPreviewMode = if (markdown) MarkdownPreviewMode.EDITOR else null,
                        onMarkdownPreviewModeChange = if (markdown) ({ _: MarkdownPreviewMode -> }) else null,
                        onResetZoom = if (resetAvailable) ({ resetClicks++ }) else null)
                }
            }
        }
    }
    @Test fun resetStaysBesideSymbolsWithUnchangedToolbarHeightAndNoOverlap() {
        mount()
        for (font in listOf(1f, 2f)) for (direction in listOf(false, true)) for (md in listOf(false, true)) {
            compose.runOnIdle { fontScale = font; rtl = direction; markdown = md }
            for (w in listOf(126, 180, 220, 240, 286, 320, 360, 600, 1024)) {
                compose.runOnIdle { width = w.dp; resetAvailable = false }
                val originalHeight = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot.height
                compose.runOnIdle { resetAvailable = true }
                val root = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
                assertEquals(originalHeight, root.height, 0f)
                if (w < if (md) 220 else 126) {
                    compose.onNodeWithContentDescription("Reset editor zoom").assertDoesNotExist()
                    continue
                }
                val reset = compose.onNodeWithContentDescription("Reset editor zoom").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val save = compose.onNodeWithContentDescription("Save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("reset outside toolbar at $w", reset.left >= root.left && reset.right <= root.right)
                assertFalse(reset.overlaps(save))
                if (w >= 286) {
                    val symbols = compose.onNodeWithContentDescription("Workspace symbols").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val line = compose.onNodeWithContentDescription("Go to line").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertFalse(reset.overlaps(symbols)); assertFalse(reset.overlaps(line))
                    assertEquals(if (direction) symbols.left else symbols.right, if (direction) reset.right else reset.left, .1f)
                }
            }
        }
    }
    @Test fun resetDispatchesOnlyItsActionAndIsAbsentForNonEditorSurfaces() {
        mount()
        compose.onNodeWithContentDescription("Reset editor zoom").performClick()
        assertEquals(1, resetClicks); assertEquals(0, symbolClicks)
        compose.onNodeWithContentDescription("Workspace symbols").performClick()
        assertEquals(1, symbolClicks)
        compose.runOnIdle { resetAvailable = false }
        compose.onNodeWithContentDescription("Reset editor zoom").assertDoesNotExist()
    }
}

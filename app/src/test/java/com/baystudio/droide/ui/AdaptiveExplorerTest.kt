package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.FileEntry
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveExplorerTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("adaptive-explorer-").toFile()
    private val width = mutableStateOf(240.dp)
    private val scale = mutableStateOf(1f)
    @After fun cleanup() { root.deleteRecursively() }

    private fun mount(state: ExplorerWorkspaceState = ExplorerWorkspaceState()) {
        val files = FileRepository(root)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                DroideTheme { Box(Modifier.size(width.value, 600.dp)) {
                    compose.TestFileExplorer(files, state, onOpen = {})
                } }
            }
        }
    }

    @Test fun narrowToolbarKeepsCreationImportAndRefreshInItsExistingMenu() {
        mount()
        for (font in listOf(1f, 2f)) {
            compose.runOnIdle { scale.value = font }
            compose.onNodeWithContentDescription("Explorer options").performClick()
            for (action in listOf("Import file", "New file", "New folder", "Refresh Explorer and linked folder")) {
                compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText("New file").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
        }
        compose.runOnIdle { scale.value = 1f; width.value = 600.dp }
        compose.onNodeWithContentDescription("New file").assertIsDisplayed()
        compose.onNodeWithContentDescription("New folder").assertIsDisplayed()
    }

    @Test fun deepTreeKeepsTheFilenameAndItsActionsInsideThePane() {
        scale.value = 2f
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val portrait = mutableStateOf(true)
        var actions = 0
        val entry = FileEntry("d/".repeat(18) + "leaf.kt", false, 7, 0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value),
                LocalLayoutDirection provides direction.value) {
                DroideTheme { Box(Modifier.size(width.value, 160.dp)) {
                    ExplorerEntryRow(entry, 18, expanded = false, selected = false,
                        style = explorerEntryStyle(portrait.value), onOpen = {}, onActions = { actions++ })
                } }
            }
        }
        for (paneWidth in listOf(240.dp, 360.dp, 600.dp)) {
            for (rtl in listOf(false, true)) {
                for (tall in listOf(false, true)) {
                    compose.runOnIdle {
                        width.value = paneWidth
                        direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        portrait.value = tall
                    }
                    val name = compose.onNodeWithText("leaf.kt", useUnmergedTree = true)
                        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue("Filename must retain usable width", name.width >= 70f)
                    assertTrue(name.left >= 0f && name.right <= paneWidth.value)
                    val action = compose.onNodeWithContentDescription("File actions").assertIsDisplayed()
                    val bounds = action.fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= 0f && bounds.right <= paneWidth.value)
                    action.performClick()
                }
            }
        }
        assertEquals(12, actions)
    }
}

package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h360dp-land")
class EditorTabsLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private var files by mutableStateOf(listOf("README.md"))
    private var active by mutableStateOf("README.md")
    private var dirty by mutableStateOf(setOf<String>())

    private fun mount() {
        compose.setContent { DroideTheme {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(Modifier.width(maxWidth)) {
                    DroideEditorTabs(files, active, { it in dirty }, { active = it }, { path ->
                        files = files - path
                        if (active == path) active = files.lastOrNull().orEmpty()
                    })
                }
            }
        } }
    }

    private fun selectNew(path: String) = compose.runOnIdle {
        files = files + path
        active = path
    }

    @Test fun openingFilesUpdatesSelectionBeforeTheNewTabPositionsExist() {
        mount()
        for (path in listOf("a.txt", "b.kt", "c.md")) {
            selectNew(path)
            compose.onNode(hasText(path) and isSelectable()).assertIsSelected()
        }
    }

    @Test fun restoringTabsCanSelectTheLastFileOnTheFirstRecoveryFrame() {
        mount()
        compose.runOnIdle {
            files = listOf("README.md") + (1..12).map { "restored$it.txt" }
            active = files.last()
            dirty = setOf(active)
        }
        compose.onNode(hasText("restored12.txt") and isSelectable()).assertIsSelected().assertIsDisplayed()
        compose.onNodeWithContentDescription("Unsaved").assertExists()
    }

    @Test fun closingAndReopeningTabsPreservesSelectionAndDirtyState() {
        mount()
        selectNew("a.txt")
        selectNew("b.kt")
        compose.runOnIdle { dirty = setOf("a.txt") }
        compose.onNodeWithContentDescription("Close b.kt").performClick()
        compose.onNode(hasText("a.txt") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Close README.md").performClick()
        compose.onNode(hasText("a.txt") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Unsaved").assertExists()
        compose.onNodeWithContentDescription("Close a.txt").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), files) }
        selectNew("again.txt")
        compose.onNode(hasText("again.txt") and isSelectable()).assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun portraitKeepsTheSameTabSelectionBehavior() {
        mount()
        selectNew("a.txt")
        compose.onNodeWithText("README.md", useUnmergedTree = true).performClick()
        compose.onNode(hasText("README.md") and isSelectable()).assertIsSelected()
    }
}

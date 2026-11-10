package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.FileRepository
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class FrontendExplorerUserWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("droide-frontend-explorer-").toFile()
    private val other = Files.createTempDirectory("droide-frontend-other-").toFile()
    private var repository by mutableStateOf(FileRepository(root))
    private var explorer by mutableStateOf(ExplorerWorkspaceState())
    private var allowed = true
    private var releaseMutation: CompletableDeferred<Unit>? = null

    @After fun release() {
        releaseMutation?.complete(Unit)
        root.deleteRecursively()
        other.deleteRecursively()
    }

    private fun mount() {
        compose.setContent { DroideTheme { Box(Modifier.width(360.dp).height(640.dp)) {
            compose.TestFileExplorer(repository, explorer, onOpen = {}, canMutatePath = { allowed })
        } } }
        val treeMode = explorerTreeMode(explorer.query)
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            if (treeMode) explorer.children[""] != null else explorer.searchResults.isNotEmpty()
        }
        if (!treeMode) org.junit.Assert.assertNull("Search must not enumerate the hidden tree", explorer.children[""])
    }

    private fun form(): SemanticsNodeInteraction {
        compose.mainClock.advanceTimeBy(400)
        return compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
    }
    private fun confirm(label: String): SemanticsNodeInteraction {
        compose.mainClock.advanceTimeBy(400)
        return compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
    }
    private fun awaitText(value: String) = compose.waitUntil(5_000) {
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun sameSearchInAnotherWorkspaceUsesThatWorkspaceFiles() {
        root.resolve("needle-first.kt").writeText("first")
        other.resolve("needle-second.kt").writeText("second")
        explorer.query = "needle"
        mount()
        awaitText("needle-first.kt")
        compose.runOnIdle {
            repository = FileRepository(other)
            explorer = ExplorerWorkspaceState().apply { query = "needle" }
        }
        awaitText("needle-second.kt")
        assertEquals(listOf("needle-second.kt"), explorer.searchResults)
        compose.onNodeWithText("needle-first.kt").assertDoesNotExist()
    }

    @Test fun refreshRepeatsTheActiveSearchAfterTerminalCreatesAFile() {
        root.resolve("needle-first.kt").writeText("first")
        explorer.query = "needle"
        mount()
        awaitText("needle-first.kt")
        root.resolve("needle-new.kt").writeText("created outside Explorer")
        compose.onNodeWithContentDescription("Refresh Explorer and linked folder").performClick()
        awaitText("needle-new.kt")
        assertEquals(setOf("needle-first.kt", "needle-new.kt"), explorer.searchResults.toSet())
    }

}

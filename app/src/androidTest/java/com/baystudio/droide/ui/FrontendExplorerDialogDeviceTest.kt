package com.baystudio.droide.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import com.baystudio.droide.MainActivity
import com.baystudio.droide.core.FileRepository
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrontendExplorerDialogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val root = Files.createTempDirectory(ApplicationProvider.getApplicationContext<Context>().cacheDir.toPath(), "droide-frontend-explorer-").toFile()
    private val other = Files.createTempDirectory(ApplicationProvider.getApplicationContext<Context>().cacheDir.toPath(), "droide-frontend-other-").toFile()
    private var repository by mutableStateOf(FileRepository(root))
    private var explorer by mutableStateOf(ExplorerWorkspaceState())
    private var allowed = true
    private var releaseMutation: CompletableDeferred<Unit>? = null

    @After fun release() {
        releaseMutation?.complete(Unit)
        root.deleteRecursively()
        other.deleteRecursively()
    }

    // Fixture-owned surface only; production retains the controller in WorkspaceRuntimeHost.
    @Composable
    private fun ExplorerSurface() {
        val files = repository
        val state = explorer
        val scope = rememberCoroutineScope()
        val refresh = remember(files, state) {
            ExplorerRefreshController(state, scope, Mutex(),
                load = { path, after, options, budget, traversal ->
                    files.listFilesPageBounded(path, after, 250, options, budget, traversal)
                }, watchFactory = ExplorerDirectoryWatchFactory { _, _ -> null })
        }
        val lease = remember(refresh) { refresh.newUiLease() }
        SideEffect { refresh.updateVisibility(lease, true, true, explorerTreeMode(state.query)) }
        DisposableEffect(refresh) { onDispose { refresh.detach(lease); refresh.close() } }
        FileExplorer(files, state, refresh, lease, onOpen = {}, canMutatePath = { allowed })
    }

    private fun mount() {
        compose.runOnUiThread {
            compose.activity.setContent { DroideTheme { Box(Modifier.fillMaxSize()) {
                ExplorerSurface()
            } } }
        }
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); explorer.children[""] != null }
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

    @Test fun failedCreateExplainsTheProblemInsideTheDialogAndAllowsRetry() {
        root.resolve("exists.kt").writeText("user source")
        mount()
        compose.onNodeWithContentDescription("New file").performClick()
        form().performTextReplacement("exists.kt")
        confirm("Create").performClick()
        val error = "A file named exists.kt already exists here"
        awaitText(error)
        compose.onNodeWithText(error).assert(hasAnyAncestor(isDialog()))
        form().assertTextContains("exists.kt")
        assertEquals("user source", root.resolve("exists.kt").readText())
        form().performTextReplacement("retry.kt")
        confirm("Create").performClick()
        compose.waitUntil(5_000) { root.resolve("retry.kt").isFile }
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun blockedRenameKeepsTheErrorAndEnteredNameInTheDialog() {
        root.resolve("source.kt").writeText("unsaved buffer has authority")
        allowed = false
        mount()
        compose.onNodeWithContentDescription("File actions").performClick()
        confirm("Rename").performClick()
        form().performTextReplacement("renamed.kt")
        confirm("Rename").performClick()
        val error = "Save or discard unsaved editor changes before renaming this path"
        awaitText(error)
        compose.onNodeWithText(error).assert(hasAnyAncestor(isDialog()))
        form().assertTextContains("renamed.kt")
        assertTrue(root.resolve("source.kt").isFile)
        assertFalse(root.resolve("renamed.kt").exists())
    }

    @Test fun blockedDeleteKeepsItsDialogOpenWithTheReason() {
        root.resolve("source.kt").writeText("keep")
        allowed = false
        mount()
        compose.onNodeWithContentDescription("File actions").performClick()
        confirm("Delete").performClick()
        confirm("Delete").performClick()
        val error = "Save or discard unsaved editor changes before deleting this path"
        awaitText(error)
        compose.onNodeWithText("Delete file?").assertExists()
        compose.onNodeWithText(error).assert(hasAnyAncestor(isDialog()))
        assertEquals("keep", root.resolve("source.kt").readText())
    }

    @Test fun fileCreatedConcurrentlyIsPreservedWhenTheNewFileOperationResumes() {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>().also { releaseMutation = it }
        repository = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write) { entered.complete(Unit); gate.await() }
        })
        mount()
        compose.onNodeWithContentDescription("New file").performClick()
        form().performTextReplacement("race.kt")
        confirm("Create").performClick()
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); entered.isCompleted }
        root.resolve("race.kt").writeText("Created outside Explorer")
        compose.runOnIdle { gate.complete(Unit) }
        val error = "File changed on disk: race.kt; newer workspace data was preserved. Review or reload before retrying."
        awaitText(error)
        compose.onNodeWithText(error).assert(hasAnyAncestor(isDialog()))
        assertEquals("Created outside Explorer", root.resolve("race.kt").readText())
    }

    @Test fun pendingCreationDisablesDuplicateSubmissionAndNameChanges() {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>().also { releaseMutation = it }
        repository = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write) { entered.complete(Unit); gate.await() }
        })
        mount()
        compose.onNodeWithContentDescription("New file").performClick()
        form().performTextReplacement("single.kt")
        confirm("Create").performClick()
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); entered.isCompleted }
        confirm("Create").assertIsNotEnabled()
        form().assertIsNotEnabled()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); root.resolve("single.kt").isFile }
        assertEquals(1, root.listFiles()!!.count { it.name == "single.kt" })
    }
}

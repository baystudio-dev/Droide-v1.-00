package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExplorerMutationContinuationUiTest {
    private lateinit var mainWindowRoot: View
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("droide-mutation-ui-").toFile()
    private val state = ExplorerWorkspaceState()
    private val visible = androidx.compose.runtime.mutableStateOf(true)
    private var release: CompletableDeferred<Unit>? = null
    @After fun cleanup() { release?.complete(Unit); root.deleteRecursively() }
    private fun mount(allowed: () -> Boolean = { true }, prepare: suspend (List<String>) -> Unit) {
        val files = FileRepository(root)
        compose.setContent { mainWindowRoot = LocalView.current.rootView; DroideTheme { Box(Modifier.size(360.dp,640.dp)) {
            if (visible.value) compose.TestFileExplorer(files, state, onOpen = {}, canMutatePath = { allowed() }, preparePathMutation = prepare)
        } } }
        await { state.children[""]?.isNotEmpty() == true }
    }
    private fun openAction(label: String) {
        compose.onNodeWithContentDescription("File actions").performClick()
        compose.mainClock.advanceTimeBy(400)
        traverseNativeDialogWindow()
        compose.onNodeWithText(label).performClick()
        compose.mainClock.advanceTimeBy(400)
        traverseNativeDialogWindow()
    }
    private fun confirm(label: String): SemanticsNodeInteraction {
        traverseNativeDialogWindow()
        return compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.mainClock.advanceTimeByFrame()
        // Import progress returns from real I/O through Android's Main handler, which is distinct from
        // the Compose test clock even without a dialog.
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        if (hasNativeDialogWindow()) traverseNativeDialogWindow()
        condition()
    }

    private fun hasNativeDialogWindow(): Boolean {
        if (!::mainWindowRoot.isInitialized) return false
        var present = false
        compose.runOnUiThread {
            val environment = ReflectionHelpers.getField<Any>(compose, "environment")
            val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
            val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
            present = owners.any { owner ->
                val view = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                view.rootView !== mainWindowRoot && view.isAttachedToWindow && view.isShown &&
                    view.windowVisibility == View.VISIBLE
            }
        }
        return present
    }

    // Same bounded SDK 35 window traversal used by BrowserHumanDialogTest.

    private fun traverseNativeDialogWindow() {
        assertTrue("Automatic Compose idling stays enabled", compose.mainClock.autoAdvance)
        var idleAfterTraversal = false
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
                for (owner in owners) {
                    val ownerView = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (ownerView.rootView !== mainWindowRoot && ownerView.isAttachedToWindow && ownerView.isShown &&
                        ownerView.windowVisibility == View.VISIBLE &&
                        ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                        ReflectionHelpers.callInstanceMethod<Void>(ownerView, "measureAndLayoutForTest")
                    }
                }
                idleAfterTraversal = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
            }
        }
        assertTrue("Native dialog layout and the normal Compose clock must settle", idleAfterTraversal)
    }

    private fun replaceForm(text: String) {
        traverseNativeDialogWindow()
        val input = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        input.performSemanticsAction(SemanticsActions.RequestFocus)
        traverseNativeDialogWindow()
        input.assertIsFocused().performTextReplacement(text)
        traverseNativeDialogWindow()
    }

    @Test fun renamePreparesOnceAndChecksEligibilityAfterResume() {
        root.resolve("a.kt").writeText("A")
        var allowed = false; val scopes = mutableListOf<List<String>>()
        val gate = CompletableDeferred<Unit>(); release = gate
        mount(allowed = { allowed }) { paths -> scopes += paths; gate.await(); allowed = true }
        openAction("Rename")
        replaceForm("b.kt")
        confirm("Rename").performClick()
        await { scopes.isNotEmpty() }
        confirm("Rename").assertIsNotEnabled()
        assertEquals(listOf(listOf("a.kt", "b.kt")), scopes)
        assertTrue(root.resolve("a.kt").exists()); assertFalse(root.resolve("b.kt").exists())
        compose.runOnIdle { gate.complete(Unit) }
        await { root.resolve("b.kt").exists() }
        assertEquals(1, scopes.size); assertEquals("A", root.resolve("b.kt").readText())
    }

    @Test fun disposedWorkspaceCancelsPreparationWithoutMutatingEitherPath() {
        root.resolve("a.kt").writeText("A")
        var entered = false; var cancelled = false
        val gate = CompletableDeferred<Unit>(); release = gate
        mount { entered = true; try { gate.await() } finally { cancelled = true } }
        openAction("Rename"); replaceForm("b.kt"); confirm("Rename").performClick()
        await { entered }
        confirm("Rename").assertIsNotEnabled()
        compose.runOnIdle { visible.value = false }
        await { cancelled }
        assertEquals("A", root.resolve("a.kt").readText()); assertFalse(root.resolve("b.kt").exists())
    }

    @Test fun cancelNeverPreparesOrMutates() {
        root.resolve("a.kt").writeText("A"); var calls = 0
        mount { calls++ }
        openAction("Rename"); confirm("Cancel").performClick()
        compose.waitForIdle(); assertEquals(0, calls); assertEquals("A", root.resolve("a.kt").readText())
    }

    @Test fun sameNameRenameIsNoOpWithoutSaveOrGuardRequirement() {
        root.resolve("a.kt").writeText("A"); var calls = 0
        mount(allowed = { false }) { calls++ }
        openAction("Rename"); confirm("Rename").performClick()
        await { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, calls); assertEquals("A", root.resolve("a.kt").readText())
    }

    @Test fun retainedFolderDeleteExplainsStorageBeforeConfirmation() {
        root.resolve("folder").mkdirs(); var calls = 0
        mount { calls++ }
        openAction("Delete")
        compose.onNodeWithText("Droide keeps recovery data", substring = true).assertExists()
        compose.onNodeWithText("storage will not be freed", substring = true).assertExists()
        confirm("Cancel").performClick(); compose.waitForIdle()
        assertEquals(0, calls); assertTrue(root.resolve("folder").exists())
    }
}

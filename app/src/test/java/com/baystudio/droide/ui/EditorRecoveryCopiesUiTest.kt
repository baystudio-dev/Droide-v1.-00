package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRecoveryCopiesUiTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("recovery-copy-ui-").toFile()
    private val state = EditorWorkspaceState()
    private var opens = 0
    private var visible by mutableStateOf(false)
    private lateinit var mainWindowRoot: View
    private val gate = CompletableDeferred<Unit>()
    @After fun after() { gate.complete(Unit); PathSecurity.deleteTreeNoFollow(root) }
    private fun show(allowed: Boolean = true, issues: List<EditorRecoveryEvidenceIssue> = emptyList()) {
        compose.setContent {
            mainWindowRoot = LocalView.current.rootView
            val files = remember { FileRepository(root) }
            DroideTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    if (visible) EditorRecoveryCopiesDialog(state, files, allowed,
                        onOpen = { it.open(); opens++ }, onDismiss = { visible = false }, archivedIssues = issues)
                }
            }
        }
        compose.runOnIdle { visible = true }
        settleNativeDialog()
    }
    @Test fun archivedIssueRemainsVisibleWhenReopenedAndDoesNotDisableHealthyCopy() {
        state.retainRecoveryConflict("original.txt", "older text", null)
        val issue = EditorRecoveryEvidenceIssue("12345678-1234-1234-1234-123456789abc.bin", true, 4L, "0".repeat(64), null, null)
        show(issues = listOf(issue))
        compose.onNodeWithText("Archived recovery evidence needs attention").assertExists()
        compose.onNodeWithText("Missing archived evidence: ${issue.blobName}").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { visible = true }; settleNativeDialog()
        compose.onNodeWithText("Archived recovery evidence needs attention").assertExists()
        compose.onNodeWithText("original.txt").performClick(); settleNativeDialog()
        compose.onNodeWithText("Open unsaved copy").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { opens == 1 }
        assertFalse(root.resolve("original.txt.recovered").exists())
    }

    @Test fun explicitActionOpensUnsavedCopyWithoutPublishingFile() {
        state.retainRecoveryConflict("original.txt", "older text", null)
        show()
        compose.onNodeWithText("original.txt").performClick()
        settleNativeDialog()
        compose.onNodeWithText("Open unsaved copy").performClick()
        compose.waitUntil(5_000) { opens == 1 }
        compose.runOnIdle {
            assertTrue(state.document("original.txt.recovered").dirty)
            assertEquals("older text", state.document("original.txt.recovered").content)
            assertFalse(root.resolve("original.txt.recovered").exists())
            assertEquals("older text", state.recoverySnapshot(emptyList(), "")!!.conflictBuffers["original.txt"])
        }
    }
    @Test fun inactiveWorkspaceCannotOpenCopy() {
        state.retainRecoveryConflict("original.txt", "older", null)
        show(allowed = false)
        compose.onNodeWithText("original.txt").performClick()
        settleNativeDialog()
        compose.onNodeWithText("Open unsaved copy").assertIsNotEnabled()
        assertEquals(0, opens)
    }
    @Test fun closingDuringDeferredReadNeverCreatesDocument() {
        state.retainRecoveryConflict("original.txt", null,
            EditorRecoveryBuffer("original.txt", "blob", null, Any(), 5) { gate.await(); "older" })
        show()
        compose.onNodeWithText("original.txt").performClick()
        settleNativeDialog()
        compose.onNodeWithText("Open unsaved copy").performClick()
        settleNativeDialog()
        compose.onNodeWithText("Close").performClick()
        gate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, opens); assertTrue(state.allDocuments().isEmpty()) }
    }

    @Test fun backDuringDeferredReadImmediatelyRevokesOpenAuthority() {
        state.retainRecoveryConflict("original.txt", null,
            EditorRecoveryBuffer("original.txt", "blob", null, Any(), 5) { gate.await(); "older" })
        show()
        compose.onNodeWithText("original.txt").performClick(); settleNativeDialog()
        compose.onNodeWithText("Open unsaved copy").performClick(); settleNativeDialog()
        androidx.test.espresso.Espresso.pressBack()
        gate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(visible); assertEquals(0, opens); assertTrue(state.allDocuments().isEmpty()) }
    }

    // SDK35 modal roots require a real owner traversal, as in RetainedWorkUiTest. Do not disable
    // Compose idling: require five final quiet frames with no manual layout.
    private fun settleNativeDialog() {
        assertTrue(compose.mainClock.autoAdvance)
        var quietFrames = 0
        var modalSeen = false
        val samples = mutableListOf<String>()
        repeat(20) { frame ->
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
                var layouts = 0
                var modals = 0
                for (owner in owners) {
                    val view = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (view.rootView !== mainWindowRoot && view.isAttachedToWindow && view.isShown &&
                        view.windowVisibility == View.VISIBLE) {
                        modals++
                        if (ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                            layouts++
                            ReflectionHelpers.callInstanceMethod<Void>(view, "measureAndLayoutForTest")
                        }
                    }
                }
                modalSeen = modalSeen || modals > 0
                val idle = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                quietFrames = if (modals > 0 && layouts == 0 && idle) quietFrames + 1 else 0
                samples += "$frame:modals=$modals,layouts=$layouts,idle=$idle"
            }
        }
        println("RECOVERY_COPY_DIALOG_TRAVERSAL " + samples.joinToString(";"))
        assertTrue("Real modal owner must register: $samples", modalSeen)
        assertTrue("Dialog must remain idle for five frames without manual layout: $samples", quietFrames >= 5)
        compose.waitForIdle()
    }

}

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers

/** Real dialog interaction with a fake irreversible-action boundary. No payload deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRecoveryDiscardUiTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("recovery-discard-ui-").toFile()
    private val state = EditorWorkspaceState()
    private var visible by mutableStateOf(false)
    private var allowed by mutableStateOf(true)
    private lateinit var mainWindowRoot: View
    private val actions = FakeActions()
    @After fun cleanup() { actions.gate?.complete(Unit); PathSecurity.deleteTreeNoFollow(root) }
    private class FakeActions : EditorRecoveryDiscardActions {
        override val state = MutableStateFlow(EditorRecoveryDiscardUiState())
        var prepares = 0; var confirms = 0; var reconciles = 0
        val revoked = mutableListOf<EditorRecoveryDiscardPreview>()
        var gate: CompletableDeferred<Unit>? = null
        var lateReturn = false
        override suspend fun prepare(copy: EditorWorkspaceState.RetainedCopy): EditorRecoveryDiscardPreview {
            prepares++
            if (lateReturn) withContext(NonCancellable) { gate?.await() } else gate?.await()
            return EditorRecoveryDiscardPreview(copy, 128, Any())
        }
        override fun confirm(preview: EditorRecoveryDiscardPreview) {
            confirms++
            state.value = EditorRecoveryDiscardUiState(EditorRecoveryDiscardMode.WORKING, preview.path)
        }
        override fun revoke(preview: EditorRecoveryDiscardPreview) { if (revoked.none { it === preview }) revoked += preview }
        override fun reconcile() { reconciles++; state.value = state.value.copy(mode = EditorRecoveryDiscardMode.WORKING) }
    }
    private fun show(issues: List<EditorRecoveryEvidenceIssue> = emptyList()) {
        state.retainRecoveryConflict("original.txt", "older text", null)
        compose.setContent {
            mainWindowRoot = LocalView.current.rootView
            val files = remember { FileRepository(root) }
            DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
                if (visible) EditorRecoveryCopiesDialog(state, files, allowed, onOpen = { it.open() },
                    onDismiss = { visible = false }, archivedIssues = issues, discardActions = actions)
            } }
        }
        compose.runOnIdle { visible = true }; settleNativeDialog()
    }
    private fun select() { compose.onNodeWithText("original.txt").performClick(); settleNativeDialog() }
    private fun prepare() {
        select(); compose.onNodeWithText("Prepare permanent removal").performScrollTo().performClick()
        compose.waitUntil(5_000) { actions.prepares == 1 }
        settleNativeDialog()
    }
    @Test fun prepareRequiresSeparateExplicitConfirmationAndKeepRevokesIt() {
        show(); prepare()
        compose.onNodeWithText("Permanently remove older copy?").assertExists()
        compose.onNodeWithText("Older recovery copy: original.txt").assertExists()
        compose.onNodeWithText("Encrypted copy size: 128 bytes").assertExists()
        assertEquals(0, actions.confirms)
        compose.onNodeWithText("Keep copy").performClick(); settleNativeDialog()
        assertEquals(1, actions.revoked.size); assertEquals(0, actions.confirms)
        assertEquals("older text", state.recoverySnapshot(emptyList(), "")!!.conflictBuffers["original.txt"])
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun keepThenCloseNeverConfirmsOrRetainsAnApproval() {
        show(); prepare()
        compose.onNodeWithText("Keep copy").performClick(); settleNativeDialog()
        compose.onNodeWithText("Close").performClick(); compose.waitForIdle()
        assertFalse(visible); assertEquals(1, actions.revoked.size); assertEquals(0, actions.confirms)
        compose.runOnIdle { visible = true }; settleNativeDialog()
        compose.onNodeWithText("Permanently remove older copy?").assertDoesNotExist()
        assertEquals(0, actions.confirms)
    }
    @Test fun hostDismissalRevokesUnconfirmedPreviewBeforeReopening() {
        show(); prepare()
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        assertEquals(1, actions.revoked.size); assertEquals(0, actions.confirms)
        compose.runOnIdle { visible = true }; settleNativeDialog()
        compose.onNodeWithText("Permanently remove older copy?").assertDoesNotExist()
        assertEquals("older text", state.recoverySnapshot(emptyList(), "")!!.conflictBuffers["original.txt"])
    }
    @Test fun backCancelsOnlyUnconfirmedSelection() {
        show(); prepare()
        androidx.test.espresso.Espresso.pressBack(); settleNativeDialog()
        assertTrue(visible); assertEquals(0, actions.confirms); assertEquals(1, actions.revoked.size)
        compose.onNodeWithText("Permanently remove older copy?").assertDoesNotExist()
    }
    @Test fun confirmIsSingleUseAndPendingReopensWithoutNewSelection() {
        show(); prepare(); compose.onNodeWithText("Permanently remove").performClick(); settleNativeDialog()
        assertEquals(1, actions.confirms)
        compose.onNodeWithText("Permanently remove older copy?").assertDoesNotExist()
        compose.onNodeWithText("Open unsaved copy").assertIsNotEnabled()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            actions.state.value = EditorRecoveryDiscardUiState(EditorRecoveryDiscardMode.PENDING, "original.txt", "Directory sync is unconfirmed")
            visible = true
        }
        settleNativeDialog()
        compose.onNodeWithText("Confirmed removal needs reconciliation. Completion is not yet verified.").assertExists()
        compose.onNodeWithText("Finish confirmed removal").performScrollTo().performClick(); settleNativeDialog()
        assertEquals(1, actions.reconciles); assertEquals(1, actions.prepares); assertEquals(1, actions.confirms)
        assertEquals(0, actions.revoked.size)
    }
    @Test fun closingDuringPreparationRevokesLatePreviewWithoutConfirming() {
        actions.gate = CompletableDeferred(); actions.lateReturn = true
        show(); select(); compose.onNodeWithText("Prepare permanent removal").performScrollTo().performClick()
        compose.waitUntil(5_000) { actions.prepares == 1 }; settleNativeDialog()
        compose.onNodeWithText("Close").performClick()
        actions.gate!!.complete(Unit)
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); actions.revoked.size == 1 }
        assertEquals(0, actions.confirms); assertFalse(visible)
    }
    @Test fun workspacePauseDisablesExplicitConfirmation() {
        show(); prepare(); compose.runOnIdle { allowed = false }; settleNativeDialog()
        compose.onNodeWithText("Permanently remove").assertIsNotEnabled()
        compose.onNodeWithText("Keep copy").performClick(); settleNativeDialog()
        assertEquals(0, actions.confirms); assertEquals(1, actions.revoked.size)
    }
    @Test fun archivedUnknownEvidenceHasNoRemovalActionAndResultDoesNotClaimFreedBytes() {
        val issue = EditorRecoveryEvidenceIssue("12345678-1234-1234-1234-123456789abc.bin", true, 4, "0".repeat(64), null, null)
        show(listOf(issue))
        compose.onNodeWithText("Prepare permanent removal").assertDoesNotExist()
        compose.onNodeWithText("Healthy drafts remain available. These archived records were kept unchanged and cannot be opened or removed here.").assertExists()
        compose.runOnIdle { actions.state.value = EditorRecoveryDiscardUiState(EditorRecoveryDiscardMode.REMOVED, "original.txt", revision = 1) }
        settleNativeDialog()
        compose.onNodeWithText("Selected older recovery copy removed. Physical disk space released was not measured.").assertExists()
        assertEquals(0, actions.prepares); assertEquals(0, actions.confirms)
    }

    // SDK35 modal roots require a real owner traversal, as in RetainedWorkUiTest.
    // Do not disable Compose idling: require five final quiet frames with no manual layout.
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

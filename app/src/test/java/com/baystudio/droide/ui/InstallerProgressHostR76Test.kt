package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authored, not device-certified. Only fixture jobs; no packages, network or permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstallerProgressHostR76Test {
    @get:Rule val compose = createComposeRule()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val panel = InstallerProgressPresentation()
    private val details = InstallerProgressDetails("npm", "npm", "latest", listOf("npm: latest", "Uses the same Ubuntu workstation"))
    private val release = CompletableDeferred<Unit>()
    private var starts = 0
    private var workspaceClicks = 0
    private var showHost by mutableStateOf(true)
    private var footerHeight = 0
    private var density = 1f
    private var job: Deferred<UnifiedPackageTransactionResult>? = null
    private var result = UnifiedPackageTransactionResult(true, "npm installed and verified")

    @After fun close() { owner.cancel() }
    private fun start() {
        job = panel.launch(owner, details) { report ->
            starts++
            report(PackageProgressSnapshot("downloading", "Downloaded 60099 KiB / 61116 KiB", "Checking Ubuntu runtime…\nDownloaded 60099 KiB"), .98f)
            release.await(); result
        }
    }
    @Composable private fun Fixture(width: Int, height: Int, largeFont: Boolean, inset: WindowInsets, approval: Boolean) {
        val current = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(current.density, if (largeFont) 1.8f else 1f)) {
            MaterialTheme {
                density = LocalDensity.current.density
                Box(Modifier.size(width.dp, height.dp)) {
                    Scaffold(bottomBar = {
                        if (showHost) InstallerProgressHost(panel,
                            Modifier.testTag("footer-root").onSizeChanged { footerHeight = it.height }, insets = inset) {
                            if (approval) Box(Modifier.windowInsetsPadding(inset).height(48.dp).fillMaxWidth().testTag("approval-fixture")) {
                                Text("Approval waiting")
                            }
                        }
                    }) { padding ->
                        Column(Modifier.padding(padding)) {
                            Button({ workspaceClicks++ }, Modifier.testTag("workspace-action")) { Text("Use workspace") }
                            OutlinedTextField("", {}, Modifier.testTag("workspace-editor"), label = { Text("Editor") })
                        }
                    }
                }
            }
        }
    }
    private fun mount(width: Int = 360, height: Int = 640, largeFont: Boolean = false, inset: WindowInsets = WindowInsets(0), approval: Boolean = false) {
        start()
        compose.setContent { Fixture(width, height, largeFont, inset, approval) }
    }
    private fun minimize() = compose.onNodeWithContentDescription("Minimize installation progress").performClick()
    private fun reopen() = compose.onNodeWithTag("installer-progress-reopen").performClick()

    @Test fun minusRemovesDialogAndWorkspaceReceivesRealPointerClick() {
        mount()
        compose.onNodeWithTag("installer-progress-dialog").assertExists()
        minimize()
        compose.onNodeWithTag("installer-progress-dialog").assertDoesNotExist()
        compose.onNodeWithTag("installer-progress-indicator").assertIsDisplayed()
        compose.onNodeWithTag("workspace-action").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, workspaceClicks); assertEquals(1, starts); assertTrue(job!!.isActive) }
    }

    @Test fun repeatedReopenShowsSameOutputAndNeverRestarts() {
        mount()
        repeat(5) { minimize(); reopen() }
        compose.onNodeWithText("Downloaded 60099 KiB / 61116 KiB").assertExists()
        compose.runOnIdle { assertEquals(1, starts); assertFalse(job!!.isCancelled) }
    }

    @Test fun systemBackMinimizesInsteadOfCanceling() {
        mount()
        // Send Back through the Android window, not a Compose node key handler.
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("installer-progress-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(job!!.isActive); assertFalse(panel.state.value.entries.single().cancelRequested) }
    }

    @Test fun successWhileMinimizedStaysCompactUntilReopened() {
        mount(); minimize()
        compose.runOnIdle { release.complete(Unit) }
        compose.onNodeWithTag("installer-progress-dialog").assertDoesNotExist()
        compose.onNodeWithText("npm · Complete").assertExists()
        reopen()
        compose.onNodeWithText("npm installed and verified").assertExists()
        compose.onNodeWithTag("installer-progress-close").performClick()
        compose.onNodeWithTag("installer-progress-indicator").assertDoesNotExist()
    }

    @Test fun failureWhileMinimizedRemainsReadableWithoutBlockingWorkspace() {
        result = UnifiedPackageTransactionResult(false, "Checksum mismatch")
        mount(); minimize()
        compose.runOnIdle { release.complete(Unit) }
        compose.onNodeWithText("npm · Failed").assertExists()
        compose.onNodeWithTag("workspace-action").performTouchInput { click() }
        reopen()
        compose.onNodeWithText("Checksum mismatch").assertExists()
        compose.runOnIdle { assertEquals(1, workspaceClicks) }
    }

    @Test fun cancelIsSeparateFromMinusAndReopen() {
        mount(); minimize(); reopen()
        compose.runOnIdle { assertTrue(job!!.isActive) }
        compose.onNodeWithTag("installer-progress-cancel").performClick()
        compose.waitUntil { job!!.isCompleted }
        compose.onNodeWithText("Installation canceled.").assertExists()
        compose.runOnIdle { assertTrue(job!!.isCancelled) }
    }

    @Test fun leavingAndReturningToHostRetainsSameJobAndMinimizedState() {
        mount(); minimize()
        compose.runOnIdle { showHost = false }
        compose.onNodeWithTag("installer-progress-indicator").assertDoesNotExist()
        compose.runOnIdle { assertTrue(job!!.isActive); showHost = true }
        compose.onNodeWithTag("installer-progress-indicator").assertExists()
        compose.onNodeWithTag("installer-progress-dialog").assertDoesNotExist()
        reopen()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun recreationRestoresPresentationFromRetainedOwnerWithoutRelaunch() {
        start()
        val restore = StateRestorationTester(compose)
        restore.setContent { Fixture(360, 640, false, WindowInsets(0), false) }
        minimize()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("installer-progress-indicator").assertExists()
        compose.onNodeWithTag("installer-progress-dialog").assertDoesNotExist()
        reopen()
        compose.runOnIdle { assertEquals(1, starts); assertTrue(job!!.isActive) }
    }

    @Test fun compactFooterHasAccessibleTargetsAtNarrowWidthAndLargeFont() {
        mount(width = 320, height = 480, largeFont = true)
        compose.onNodeWithTag("installer-progress-minimize").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        minimize()
        compose.onNodeWithTag("installer-progress-reopen").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithTag("workspace-action").assertIsDisplayed()
    }

    @Test fun installerAndApprovalConsumeKeyboardInsetOnlyOnce() {
        mount(width = 640, height = 480, inset = WindowInsets(bottom = 120), approval = true)
        minimize()
        compose.onNodeWithTag("approval-fixture").assertExists()
        compose.runOnIdle {
            // 48dp install row + 4dp progress + 48dp approval + one 120px keyboard inset.
            val expected = (100f * density).toInt() + 120
            assertTrue("footer=$footerHeight expected~$expected", kotlin.math.abs(footerHeight - expected) <= 3)
        }
        compose.onNodeWithTag("workspace-action").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, workspaceClicks) }
    }

    @Test fun emptyOrExpandedHostDoesNotReservePhantomKeyboardFooter() {
        mount(inset = WindowInsets(bottom = 120))
        compose.runOnIdle { assertEquals(0, footerHeight) }
        compose.onNodeWithTag("installer-progress-cancel").performClick()
        compose.waitUntil { job!!.isCompleted }
        compose.onNodeWithTag("installer-progress-close").performClick()
        compose.runOnIdle { assertEquals(0, footerHeight) }
    }

    @Test fun multipleEntriesKeepExactSelectedResultAndCancelOnlySelectedJob() {
        mount()
        val secondRelease = CompletableDeferred<Unit>()
        var second: Deferred<UnifiedPackageTransactionResult>? = null
        compose.runOnIdle {
            second = panel.launch(owner, details.copy(familyId = "git", familyName = "git")) { secondRelease.await(); result }
        }
        compose.onNodeWithText("Install git").assertExists()
        compose.onNodeWithTag("installer-progress-cancel").performClick()
        compose.waitUntil { second!!.isCompleted }
        compose.runOnIdle { assertTrue(job!!.isActive) }
        compose.onNodeWithText("Previous").performClick()
        compose.onNodeWithText("Install npm").assertExists()
        compose.runOnIdle { assertFalse(job!!.isCancelled) }
    }
}

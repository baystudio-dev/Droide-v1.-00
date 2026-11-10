package com.baystudio.droide.ui
import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalCliFilesControlTest {
 @get:Rule val compose = createComposeRule()
 @Test fun idleHasNoBannerAndFilesStillOpensReview() {
  var calls = 0
  compose.setContent { DroideTheme { AgentWorkspaceSyncNotice(WorkspaceAgentSyncState(sourceRoot="/project"), false, { calls++ }) } }
  compose.onNodeWithText("CLI uses a separate review workspace").assertDoesNotExist()
  compose.onNodeWithText("Files").performClick()
  compose.onNodeWithText("Review files").performClick()
  compose.runOnIdle { assertEquals(1,calls) }
 }
 @Test fun pendingCountIsVisibleAndBusyReviewCannotBeSubmitted() {
  var calls=0
  compose.setContent { DroideTheme { AgentWorkspaceSyncNotice(WorkspaceAgentSyncState(phase=WorkspaceAgentSyncPhase.PENDING,sourceRoot="/project",pendingCount=3),true,{ calls++ }) } }
  compose.onNodeWithText("Files (3)").performClick()
  compose.onNodeWithText("Review files").assertIsNotEnabled()
  compose.runOnIdle { assertEquals(0,calls) }
 }
 @Test fun retainedVersionsRemainAccessibleWithoutDismissibleBanner() {
  var recovered=0
  compose.setContent { DroideTheme { AgentWorkspaceSyncNotice(WorkspaceAgentSyncState(sourceRoot="/project"),false,{}, { recovered++ }) } }
  compose.onNodeWithText("Files").performClick()
  compose.onNodeWithText("Retained versions").performClick()
  compose.runOnIdle { assertEquals(1,recovered) }
  compose.onNodeWithText("CLI workspace synchronization").assertDoesNotExist()
 }
 @Test fun reviewModalYieldsToPermissionWhenReviewBecomesBusy() {
  var busy by mutableStateOf(false)
  compose.setContent { DroideTheme { AgentWorkspaceSyncNotice(WorkspaceAgentSyncState(sourceRoot="/project"),busy,{ busy=true }) } }
  compose.onNodeWithText("Files").performClick()
  compose.onNodeWithText("Review files").performClick()
  compose.onNodeWithText("CLI workspace synchronization").assertDoesNotExist()
  compose.runOnIdle { busy=false }
  compose.onNodeWithText("CLI workspace synchronization").assertExists()
 }
}

package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ProjectCopyJournal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class ProjectCopyRetirementUiTest {
    @get:Rule val compose = createComposeRule()
    private val inspected = ProjectCopyJournal.RetirementInspection("token", "00000000-0000-0000-0000-000000000001",
        ProjectCopyJournal.RetirementLocation.ARCHIVE, 5000)
    private val blocked = ProjectCopyRecoveryUiState(open = true, supportsHistoryRecovery = true,
        blockingProblem = "History archiving was interrupted")

    @Test fun inspectionActionIsVisibleWithoutOfferingPrematureConfirmation() {
        var checks = 0
        mount(blocked, onInspect = { checks++ })
        compose.onNodeWithText("Inspect interrupted history").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, checks)
        compose.onNodeWithText("Finish archiving history").assertDoesNotExist()
        compose.onNodeWithText("Allow a new import").assertDoesNotExist()
    }

    @Test fun inspectedHistoryOffersASeparateExplicitConfirmation() {
        var confirmations = 0
        mount(blocked.copy(historyInspection = inspected), onFinish = { confirmations++ })
        compose.onNodeWithText("Finish archiving history").assertIsDisplayed().performClick()
        assertEquals(1, confirmations)
        compose.onNodeWithText("The verified completed record is already in the history archive.").assertExists()
        compose.onNodeWithText("Back to transactions").assertIsDisplayed()
    }

    @Test fun shortViewportKeepsConfirmationAndCloseReachableDuringWork() {
        mount(blocked.copy(historyInspection = inspected, working = true), height = 240)
        compose.onNodeWithText("Finish archiving history").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun conversationPanelNeverOffersProjectHistoryMutation() {
        mount(blocked.copy(historyInspection = inspected), imports = false)
        compose.onNodeWithText("Finish archiving history").assertDoesNotExist()
        compose.onNodeWithText("Inspect interrupted history").assertDoesNotExist()
    }

    private fun mount(state: ProjectCopyRecoveryUiState, imports: Boolean = true, height: Int = 580,
        onInspect: () -> Unit = {}, onFinish: () -> Unit = {}) {
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, height.dp)) {
            ProjectCopyRecoveryContent(state, imports, "/project", null, height.dp,
                {}, {}, {}, {}, {}, onInspect, onFinish)
        } } }
    }
}

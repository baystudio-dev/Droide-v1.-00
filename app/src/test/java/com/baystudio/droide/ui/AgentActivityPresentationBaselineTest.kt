package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentActivityPresentationBaselineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryLiveViewDoesNotExposeInternalTurnCounter() {
        val step = AgentStreamPart("step:7", 7, AgentStreamPartKind.STEP, AgentStreamPartState.RUNNING, title = "Step 7")
        compose.setContent { DroideTheme { Column {
            AgentStreamRunHeader(AgentStreamState(status = AgentStreamState.Status.BUSY, activeStep = 7, parts = listOf(step)), 0, false, {})
            AgentStreamPartItem(step, false)
        } } }
        compose.onAllNodesWithText("Step 7").assertCountEquals(0)
    }

    @Test fun runningTerminalIsDescribedAsACommandInsteadOfAModelRound() {
        val tool = AgentStreamPart("tool:7:0", 7, AgentStreamPartKind.TOOL, AgentStreamPartState.RUNNING,
            toolName = "run_command", title = "run command", detail = "Running")
        compose.setContent { DroideTheme {
            AgentStreamRunHeader(AgentStreamState(status = AgentStreamState.Status.BUSY, activeStep = 7, parts = listOf(tool)), 0, false, {})
        } }
        compose.onNodeWithText("Running command…").assertExists()
    }
}

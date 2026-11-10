package com.baystudio.droide.ui

import android.app.Application
import android.app.ApplicationExitInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baystudio.droide.core.PreviousProcessExit
import com.baystudio.droide.core.StartupRecoveryPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose SaveableStateRegistry restoration; does not simulate a native process crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StartupRecoveryRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun WorkspaceRoot(policy: StartupRecoveryPolicy) {

        key("default", "/project", policy.deferEditorReopen, policy.recoveryGeneration) {
            var panel by rememberSaveable { mutableStateOf("Files") }
            Column {
                Text(panel)
                Button(onClick = { panel = "Web" }) { Text("Open Web") }
            }
        }
    }

    @Test fun consecutiveNativeCrashRecoveryDoesNotRestoreThePriorRecoveryWebPanel() {
        val firstExit = PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_CRASH_NATIVE)
        val secondExit = PreviousProcessExit(2_000, 202, ApplicationExitInfo.REASON_CRASH_NATIVE)
        // Not Compose state: change the process decision only when the registry is recreated, without
        // disposing the old key before its saved instance state has been captured.
        var processPolicy = StartupRecoveryPolicy.decide(firstExit, null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DroideTheme { WorkspaceRoot(processPolicy) } }
        compose.onNodeWithText("Open Web").performClick()
        compose.onNodeWithText("Web").assertExists()
        compose.runOnIdle { processPolicy = StartupRecoveryPolicy.decide(secondExit, firstExit.identity) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Files").assertExists()
        compose.onNodeWithText("Web").assertDoesNotExist()
        // Explicit retry remains available even in the recovery process.
        compose.onNodeWithText("Open Web").performClick()
        compose.onNodeWithText("Web").assertExists()
    }

    @Test fun sameRecoveryProcessRecreationPreservesTheUserSelectedPanel() {
        val processPolicy = StartupRecoveryPolicy.decide(
            PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_CRASH_NATIVE), null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DroideTheme { WorkspaceRoot(processPolicy) } }
        compose.onNodeWithText("Open Web").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Web").assertExists()
        compose.onNodeWithText("Files").assertDoesNotExist()
    }

    @Test fun healthyProcessRecreationKeepsNormalPanelRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DroideTheme { WorkspaceRoot(StartupRecoveryPolicy.NORMAL) } }
        compose.onNodeWithText("Open Web").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Web").assertExists()
        compose.onNodeWithText("Files").assertDoesNotExist()
    }
}

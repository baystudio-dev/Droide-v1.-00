package com.baystudio.droide.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class ToolchainLicenseApprovalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longLicenseHasAllTermsAndExplicitConsentWithReachableFooter() {
        val detail = "Exact SDK terms\n".repeat(900) + "FINAL CLAUSE MUST REMAIN"
        val request = ApprovalRequest(1, ToolchainInstallConsent.KIND, "Install SDK 36", detail)
        val resolutions = mutableListOf<ApprovalResolution>()
        compose.setContent { MaterialTheme { AgentApprovalContent(request, 540.dp, resolutions::add) } }
        compose.onNodeWithText(detail, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Accept & install").assertIsDisplayed().performClick()
        assertEquals(listOf(ApprovalResolution.ALLOW_ONCE), resolutions)
        compose.onNodeWithText("Always allow for this session").assertDoesNotExist()
        compose.onNodeWithText("Reject").assertIsDisplayed().performClick()
        assertEquals(ApprovalResolution.DENY, resolutions.last())
    }

    @Test fun ordinaryPermissionKeepsItsExistingActionLabel() {
        compose.setContent { MaterialTheme { AgentApprovalContent(ApprovalRequest(2, "shell", "Build", "make"), 540.dp) {} } }
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.onNodeWithText("Accept & install").assertDoesNotExist()
    }
}

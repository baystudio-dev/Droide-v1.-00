package com.baystudio.droide.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.baystudio.droide.core.ApprovalInputReview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class AgentInputReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun commandSuffixAccessibleThroughPagingWithoutExportAction() {
        val review = ApprovalInputReview.text(ApprovalInputReview.Label.COMMAND, "x".repeat(4096) + "REVIEW THE SUFFIX")
        compose.setContent { MaterialTheme { AgentInputReview(review) } }
        compose.onNodeWithTag("agent-read-full-input").performClick()
        compose.waitForIdle()
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("x".repeat(4096)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("REVIEW THE SUFFIX").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("REVIEW THE SUFFIX").assertExists()
        compose.onNodeWithText("Export proposal").assertDoesNotExist()
    }
    @Test fun stdinLabelAndFlagsAreNotPresentedAsFileProposal() {
        val review = ApprovalInputReview.text(ApprovalInputReview.Label.STDIN, "yes", "Append newline: true; close stdin: false")
        compose.setContent { MaterialTheme { AgentInputReview(review) } }
        compose.onNodeWithText("Read full terminal input").assertExists()
        compose.onNodeWithText("Append newline: true; close stdin: false").assertExists()
        compose.onNodeWithText("Read full proposed file").assertDoesNotExist()
    }
}

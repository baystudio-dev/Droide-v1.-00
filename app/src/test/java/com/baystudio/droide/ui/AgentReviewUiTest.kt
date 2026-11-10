package com.baystudio.droide.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class AgentReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private fun request(sessionGrant: Boolean = true) = ApprovalRequest(1, "edit_file", "Edit src/Main.kt",
        AgentFileChanges.preview("src/Main.kt", "val old = 1\n".repeat(300), "val revised = 2\n".repeat(300)).diff,
        action = "edit", resource = "src/Main.kt", permissionScope = "build",
        approvalSessionId = if (sessionGrant) "session" else "", sessionGrantAction = "edit", sessionGrantResource = "src/Main.kt")

    private fun assertFooterVisible(height: Int) {
        listOf("Reject", "Allow once", "Always allow for this session").forEach { label ->
            val node = compose.onNodeWithText(label)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$label below viewport: $bounds", bounds.bottom.value <= height)
            assertTrue("$label above viewport: $bounds", bounds.top.value >= 0)
        }
    }

    @Test fun shortModalPermissionWrapsContentInsteadOfCoveringTheChat() {
        val short = ApprovalRequest(2, "websearch", "websearch: opencode", "opencode",
            action = "websearch", resource = "opencode", permissionScope = "build",
            approvalSessionId = "session", sessionGrantAction = "websearch", sessionGrantResource = "opencode")
        compose.setContent { DroideTheme { AgentApprovalSheet(short, onDismiss = {}) {} } }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(700)
        val bounds = compose.onNodeWithTag("agent-approval-content").getUnclippedBoundsInRoot()
        assertTrue("Short permission took the full viewport: $bounds", (bounds.bottom - bounds.top).value < 320)
        listOf("Reject", "Allow once", "Always allow for this session").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun modalExpandsForLongDiffThenShrinksForTheNextShortPermission() {
        var pending by mutableStateOf(request())
        compose.setContent { DroideTheme { AgentApprovalSheet(pending, onDismiss = {}) {} } }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(700)
        val longBounds = compose.onNodeWithTag("agent-approval-content").getUnclippedBoundsInRoot()
        val longHeight = (longBounds.bottom - longBounds.top).value
        compose.runOnIdle {
            pending = ApprovalRequest(3, "websearch", "Search documentation", "opencode",
                action = "websearch", resource = "opencode")
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(700)
        val shortBounds = compose.onNodeWithTag("agent-approval-content").getUnclippedBoundsInRoot()
        val shortHeight = (shortBounds.bottom - shortBounds.top).value
        assertTrue("Short request retained the old long sheet height", shortHeight < longHeight)
        compose.onNodeWithText("Reject").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed()
    }

    @Test fun approvalActionsStayVisibleWithoutScrollingLongDiffAndResolveCorrectly() {
        val decisions = mutableListOf<ApprovalResolution>()
        compose.setContent { DroideTheme { Box(Modifier.width(360.dp).height(250.dp)) {
            AgentApprovalContent(request(), 250.dp, decisions::add)
        } } }
        assertFooterVisible(250)
        compose.onNodeWithText("Allow once").performClick()
        compose.onNodeWithText("Always allow for this session").performClick()
        compose.onNodeWithText("Reject").performClick()
        assertEquals(listOf(ApprovalResolution.ALLOW_ONCE, ApprovalResolution.ALLOW_SESSION, ApprovalResolution.DENY), decisions)
    }

    @Test fun compactApprovalViewportReservesAllActionsBeforePreview() {
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp).height(170.dp)) {
            AgentApprovalContent(request(), 170.dp) {}
        } } }
        assertFooterVisible(170)
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeApprovalActionsFitInAKeyboardReducedViewport() {
        compose.setContent { DroideTheme { Box(Modifier.width(720.dp).height(190.dp)) {
            AgentApprovalContent(request(), 190.dp) {}
        } } }
        assertFooterVisible(190)
    }

    @Test fun sessionGrantActionIsHiddenWhenRequestCannotBeGrantedForSession() {
        compose.setContent { DroideTheme { Box(Modifier.height(200.dp)) { AgentApprovalContent(request(false), 200.dp) {} } } }
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.onNodeWithText("Always allow for this session").assertDoesNotExist()
    }

    @Test fun modalApprovalFooterStaysAboveKeyboardInsets() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            DroideTheme { AgentApprovalSheet(request(), onDismiss = {}) {} }
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(700)
        val rootHeight = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.boundsInRoot.height / density }
        fun findComposeView(view: View): View? {
            if (view.javaClass.simpleName == "AndroidComposeView") return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findComposeView(view.getChildAt(index))?.let { return it }
            return null
        }
        val view = requireNotNull(findComposeView(requireNotNull(ShadowDialog.getLatestDialog().window).decorView))
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (260 * density).roundToInt()))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(700)
        assertFooterVisible((rootHeight - 260).toInt())
    }

    @Test fun completedTaskDockDisappearsButHistoryRemainsAndNewTasksAppear() {
        val manager = TodoManager()
        manager.set(listOf(Todo("Fix keyboard", "in_progress", "high")), "session")
        compose.setContent {
            val tasks by manager.flow("session").collectAsState()
            DroideTheme { AgentTaskDock(tasks, 140.dp, false, false, false, false, {}, {}) }
        }
        compose.onNodeWithText("Tasks").assertIsDisplayed()
        compose.runOnIdle { manager.set(listOf(Todo("Fix keyboard", "completed", "high")), "session") }
        compose.onNodeWithText("Tasks").assertDoesNotExist()
        assertEquals("completed", manager.get("session").single().status)
        compose.runOnIdle { manager.set(manager.get("session") + Todo("Review diff", "pending", "high"), "session") }
        compose.onNodeWithText("Tasks").assertIsDisplayed()
        compose.onNodeWithText("Review diff").assertIsDisplayed()
    }

    @Test fun compactOkToolResultStillShowsStoredDiffCountsAndSyntax() {
        val change = AgentFileChanges.preview("Main.kt", "val old = 1\n", "val revised = 2\n")
        compose.setContent { DroideTheme {
            AgentStreamPartItem(AgentStreamPart("edit", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
                toolName = "edit_file", input = "{\"path\":\"Main.kt\"}", output = "OK: 1 replacement(s) in Main.kt", fileChanges = listOf(change)), false)
        } }
        compose.onAllNodesWithText("+1").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("-1").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Succeeded · Edit").performClick()
        val line = compose.onNodeWithText("+val revised = 2").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(line.config[SemanticsProperties.Text].single().spanStyles.isNotEmpty())
        compose.onNodeWithText("INPUT").assertDoesNotExist()
        compose.onNodeWithText("STATE").assertDoesNotExist()
    }

    @Test fun fileReadUsesCodeSyntaxWithoutDocumentControlBanner() {
        val output = "[DROIDE_DOCUMENT_STATE source=editor_buffer dirty=true revision=2]\n… showing lines 10–11 …\n  10│   <button class=\"run\">Run</button>\n  11│   const count = 3;"
        compose.setContent { DroideTheme { AgentCompletedToolPart("read_file", "{\"path\":\"index.html\"}", output) } }
        compose.onNodeWithText("Succeeded · Read").performClick()
        compose.onNodeWithText("CODE").assertIsDisplayed()
        val line = compose.onNodeWithText("  <button class=\"run\">Run</button>").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(line.config[SemanticsProperties.Text].single().spanStyles.isNotEmpty())
        compose.onNodeWithText("DROIDE_DOCUMENT_STATE", substring = true).assertDoesNotExist()
        assertEquals("  const count = 3;", agentReadCodeLines(output)[1].code)
    }

    @Test fun terminalOutputKeepsLiteralText() {
        val terminal = "const count = 3; [DROIDE_DOCUMENT_STATE literal terminal output]"
        compose.setContent { DroideTheme { AgentCompletedToolPart("run_command", "{\"command\":\"echo test\"}", terminal) } }
        compose.onNodeWithText("Succeeded · Terminal").performClick()
        val text = compose.onNodeWithText(terminal).assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test fun addedCodeWrapsInsteadOfNeedingHorizontalScrolling() {
        val line = "const a = '" + "long word ".repeat(24) + "';"
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp).height(500.dp)) {
            DiffText("--- a/main.js\n+++ b/main.js\n@@ -0,0 +1 @@\n+$line")
        } } }
        val bounds = compose.onNodeWithText("+$line").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom.value - bounds.top.value > 30)
        assertTrue(bounds.right.value <= 320)
    }
}

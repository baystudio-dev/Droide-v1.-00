package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun workerTask(id: String = "worker-one") = SubagentTaskView(
    id = id, childSessionId = "child-$id", profile = "build", description = "Worker $id",
    state = SubagentTaskState.RUNNING, background = true, workspaceMode = SubagentWorkspaceMode.SHARED,
    workspaceName = "", integration = SubagentIntegrationState.NONE, attempt = 1, providerId = "provider",
    modelId = "model", createdAtMs = 1, startedAtMs = 1, endedAtMs = 0, updatedAtMs = 1,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h860dp")
class WorkerSteerAdmissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rejectedWorkerMessagePreservesTheExactDraft() {
        val calls = mutableListOf<String>()
        compose.setContent { DroideTheme { AgentParallelAgentsPanel(
            SubagentDashboardState("parent", listOf(workerTask())),
            onAction = { action, task, message ->
                calls += "$action:$task:$message"
                "ERROR: worker reached a turn boundary"
            },
        ) } }
        compose.onNodeWithText("Worker worker-one").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("  keep this instruction  ")
        compose.onNodeWithContentDescription("Steer worker").performClick()
        compose.onNodeWithText("ERROR: worker reached a turn boundary").assertExists()
        assertEquals(listOf("message:worker-one:keep this instruction"), calls)
        compose.onNode(hasSetTextAction()).assertTextEquals("  keep this instruction  ")
    }

    @Test fun pendingWorkerMessageDoesNotAllowADuplicateSubmission() {
        val response = CompletableDeferred<String>()
        var calls = 0
        compose.setContent { DroideTheme { AgentParallelAgentsPanel(
            SubagentDashboardState("parent", listOf(workerTask())),
            onAction = { _, _, _ -> calls++; response.await() },
        ) } }
        compose.onNodeWithText("Worker worker-one").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("first instruction")
        compose.onNodeWithContentDescription("Steer worker").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("newer instruction")
        compose.onNodeWithContentDescription("Steer worker").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("One worker admission may be pending at a time", 1, calls) }
        compose.runOnIdle { response.complete("ERROR: worker stopped") }
        compose.onNode(hasSetTextAction()).assertTextEquals("newer instruction")
        compose.onNodeWithContentDescription("Steer worker").assertIsEnabled()
    }
}

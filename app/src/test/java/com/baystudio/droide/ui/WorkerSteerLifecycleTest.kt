package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h860dp")
class WorkerSteerLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val task = workerTask()
    private var dashboard by mutableStateOf(SubagentDashboardState("parent", listOf(task)))
    private var reply: suspend (String, String, String?) -> String = { _, _, _ -> admitted() }
    private val calls = mutableListOf<Triple<String, String, String?>>()
    private fun admitted(id: String = task.id, child: String = task.childSessionId) =
        "SUBAGENT_STEER_ADMITTED task_id=$id child_session_id=$child"
    private fun mount(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = { DroideTheme { AgentParallelAgentsPanel(dashboard) { action, id, message ->
            calls += Triple(action, id, message)
            reply(action, id, message)
        } } }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.onNodeWithText(task.description).performClick()
    }
    private fun input(value: String) = compose.onNode(hasSetTextAction()).performTextReplacement(value)
    private fun send() = compose.onNodeWithContentDescription("Steer worker").performClick()
    private fun assertDraft(value: String) = assertEquals(value,
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

    @Test fun onlyAcknowledgedAdmissionClearsSubmittedDraft() {
        mount()
        input("  accepted instruction  "); send()
        assertDraft("")
        assertEquals(listOf(Triple("message", task.id, "accepted instruction")), calls)
    }

    @Test fun unrelatedOrIncompleteReceiptsDoNotClearDraft() {
        var response = "OK"
        reply = { _, _, _ -> response }
        mount()
        val draft = "retain the exact draft"
        input(draft)
        for (result in listOf("OK", "No active parent session.", admitted(id = "other"), admitted(child = "other"),
            "SUBAGENT_STEER_ADMITTED", "ERROR: " + admitted())) {
            compose.runOnIdle { response = result }
            send(); assertDraft(draft)
        }
        assertEquals(6, calls.size)
    }

    @Test fun acknowledgedOlderMessageCannotEraseNewerDraft() {
        val response = CompletableDeferred<String>()
        reply = { _, _, _ -> response.await() }
        mount(); input("submitted"); send(); input("new text")
        compose.runOnIdle { response.complete(admitted()) }
        assertDraft("new text")
        compose.onNodeWithContentDescription("Steer worker").assertIsEnabled()
        assertEquals(1, calls.size)
    }

    @Test fun editsBackToOriginalTextAreStillANewDraftRevision() {
        val response = CompletableDeferred<String>()
        reply = { _, _, _ -> response.await() }
        mount(); input("same text"); send(); input("different text"); input("same text")
        compose.runOnIdle { response.complete(admitted()) }
        assertDraft("same text")
    }

    @Test fun collapsingWhilePendingDoesNotDiscardRejectedDraft() {
        val response = CompletableDeferred<String>()
        reply = { _, _, _ -> response.await() }
        mount(); input("unfinished instruction"); send()
        compose.onNodeWithText("Parallel agents").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { response.complete("ERROR: worker stopped") }
        compose.onNodeWithText("Parallel agents").performClick()
        assertDraft("unfinished instruction")
        compose.onNodeWithText("ERROR: worker stopped").assertExists()
    }

    @Test fun collapsingAnUnsentDraftKeepsItsText() {
        mount(); input("not submitted")
        compose.onNodeWithText("Parallel agents").performClick()
        compose.onNodeWithText("Parallel agents").performClick()
        assertDraft("not submitted")
        assertTrue(calls.isEmpty())
    }

    @Test fun taskReorderingKeepsEachDraftWithItsWorker() {
        val other = workerTask("worker-two")
        compose.runOnIdle { dashboard = dashboard.copy(tasks = listOf(task, other)) }
        mount(); input("first worker draft")
        compose.onNodeWithText(other.description).performClick(); input("second worker draft")
        compose.runOnIdle { dashboard = dashboard.copy(tasks = listOf(other, task)) }
        assertDraft("second worker draft")
        compose.onNodeWithText(task.description).performClick(); assertDraft("first worker draft")
        assertTrue(calls.isEmpty())
    }

    @Test fun completedWorkerStillExposesItsRejectedDraftForRecovery() {
        val response = CompletableDeferred<String>()
        reply = { _, _, _ -> response.await() }
        mount(); input("recover this draft"); send()
        compose.runOnIdle {
            dashboard = dashboard.copy(tasks = listOf(task.copy(state = SubagentTaskState.IDLE, endedAtMs = 100)))
            response.complete("ERROR: worker completed")
        }
        assertDraft("recover this draft")
        compose.onNodeWithContentDescription("Steer worker").assertIsNotEnabled()
        compose.onNodeWithText("Worker no longer accepts live instructions. Your draft is kept here.").assertExists()
    }

    @Test fun callbackFailureKeepsDraftAndAllowsExplicitRetry() {
        reply = { _, _, _ -> throw IllegalStateException("transport failed") }
        mount(); input("retry me"); send(); assertDraft("retry me")
        compose.onNodeWithText("ERROR: worker instruction failed. Draft kept. transport failed").assertExists()
        compose.runOnIdle { reply = { _, _, _ -> admitted() } }
        send(); assertDraft(""); assertEquals(2, calls.size)
    }

    @Test fun cancelledAdmissionKeepsDraftAndReleasesPendingGuard() {
        val response = CompletableDeferred<String>()
        reply = { _, _, _ -> response.await() }
        mount(); input("cancelled instruction"); send()
        compose.runOnIdle { response.completeExceptionally(CancellationException("stopped")) }
        assertDraft("cancelled instruction")
        compose.onNodeWithContentDescription("Steer worker").assertIsEnabled()
        compose.onNodeWithText("Worker instruction interrupted. Draft kept; check the worker before retrying.").assertExists()
        compose.runOnIdle { reply = { _, _, _ -> admitted() } }
        send(); assertDraft("")
    }

    @Test fun savedInstanceRestorationKeepsExactRejectedDraft() {
        val restoration = StateRestorationTester(compose)
        reply = { _, _, _ -> "ERROR: not admitted" }
        mount(restoration); input("  saved instruction\nsecond line  "); send()
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("  saved instruction\nsecond line  ")
        assertEquals(1, calls.size)
    }

    @Test fun oldParentAcknowledgementCannotClearAnotherParentsDraft() {
        val response = CompletableDeferred<String>()
        reply = { _, _, _ -> response.await() }
        mount(); input("old parent"); send()
        compose.runOnIdle { dashboard = dashboard.copy(parentSessionId = "other-parent") }
        compose.onNodeWithText(task.description).performClick()
        assertDraft(""); input("new parent")
        compose.runOnIdle { response.complete(admitted()) }
        assertDraft("new parent")
        compose.onNodeWithText(admitted()).assertDoesNotExist()
    }

    @Test fun existingWorkerActionsKeepTheirDestinationsAndDiscardConfirmation() {
        reply = { action, _, _ -> "Action $action completed" }
        mount()
        compose.onNodeWithText("Transcript").performClick()
        compose.onNodeWithText("Stop").performClick()
        compose.runOnIdle { dashboard = dashboard.copy(tasks = listOf(task.copy(state = SubagentTaskState.IDLE,
            workspaceMode = SubagentWorkspaceMode.ISOLATED, integration = SubagentIntegrationState.PENDING))) }
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("Merge").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("transcript", "cancel", "review", "merge"), calls.map { it.first })
        compose.onNodeWithText("Discard").performClick()
        compose.onAllNodesWithText("Discard").onLast().performClick()
        compose.runOnIdle { assertEquals(listOf("transcript", "cancel", "review", "merge", "discard"), calls.map { it.first }) }
        assertTrue(calls.all { it.second == task.id && it.third == null })
    }
}

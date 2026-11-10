package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1000dp-h600dp")
class TaskPanelUserWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("task-panel-user-").toFile()
    private val entered = CompletableDeferred<Job>()
    private val finish = CompletableDeferred<Unit>()
    private val workspaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = WorkspaceTaskController(workspaceScope)
    private var visible by mutableStateOf(true)
    private val manager = TaskManager(root, taskExecutor = { task, _ ->
        entered.complete(requireNotNull(currentCoroutineContext()[Job]))
        finish.await()
        TaskResult(task, 0, "BUILD FINISHED", false, 1)
    })

    @After fun cleanUp() { finish.complete(Unit); runBlocking { controller.closeAndJoin() }; workspaceScope.cancel(); PathSecurity.deleteTreeNoFollow(root) }

    private fun mount(tests: Boolean) {
        root.resolve(".droide").mkdir()
        root.resolve(".droide/tasks.json").writeText("""{"tasks":[{"label":"Long test","command":["true"],"group":"test"}]}""")
        compose.setContent {
            DroideTheme {
                if (visible) {
                    if (tests) TestExplorerSheet(manager, { visible = false }, {}, controller)
                    else TasksSheet(manager, { visible = false }, {}, controller)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Long test")).fetchSemanticsNodes().isNotEmpty() }
        if (tests) compose.onNodeWithContentDescription("Run Long test").performClick()
        else compose.onNodeWithText("Long test").performClick()
    }

    private fun assertDismissKeepsCommand(tests: Boolean) {
        mount(tests)
        val command = runBlocking { withTimeout(10_000) { entered.await() } }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertTrue("Closing a panel must keep its workspace task running", command.isActive)
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Stop").assertExists()
        finish.complete(Unit)
        runBlocking { withTimeout(10_000) { command.join() } }
        compose.waitUntil(10_000) { !controller.state.value.busy }
        compose.onNodeWithText("BUILD FINISHED", substring = true).assertExists()
    }

    @Test fun closingTasksPanelKeepsBuildRunning() = assertDismissKeepsCommand(tests = false)
    @Test fun closingTestExplorerKeepsTestsRunning() = assertDismissKeepsCommand(tests = true)
}

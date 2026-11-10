package com.baystudio.droide.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact chrome geometry and real actions, without launching a process or claiming device tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w640dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalCompactChromeTest {
    @get:Rule val compose = createComposeRule()

    private class Session : ITerminalSession, TerminalWorkingDirectorySession,
        TerminalCommandActivitySession, TerminalAgentActivitySession {
        override val output = MutableStateFlow("\$ ")
        override val workingDirectory = MutableStateFlow<String?>("/workspace/project")
        override val commandActivity = MutableStateFlow(TerminalCommandActivity(TerminalCommandPhase.READY))
        override val agentActivity = MutableStateFlow(TerminalAgentActivity())
        var interrupts = 0
        var clears = 0
        override fun noteCommandInput() = Unit
        override fun agentCommandStarted() = Unit
        override fun agentCommandFinished(exitCode: Int?) = Unit
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() { interrupts++ }
        override fun clear() { clears++; output.value = "" }
        override fun destroy() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }

    private fun tab(id: String, title: String, session: Session = Session()) =
        TerminalManager.Tab(id, session, title, TerminalManager.Profile.LOCAL_LINUX_ARM64)

    private fun terminalTab(title: String) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(title))

    @Test fun creationSelectionAndCloseShareOneCompactRowWithoutShrinkingTouchTargets() {
        var snapshot by mutableStateOf(TerminalManager.Snapshot(listOf(tab("one", "Ubuntu"), tab("two", "Build")), "one"))
        var selected: String? = null
        var closed: String? = null
        var created = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                DroideTheme {
                    Box(Modifier.width(360.dp)) {
                        TerminalTabs(snapshot, onSelect = { selected = it; snapshot = snapshot.copy(activeId = it) },
                            onClose = { id -> closed = id; snapshot = snapshot.copy(tabs = snapshot.tabs.filterNot { it.id == id }) },
                            onCreate = { created++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("terminal-tab-strip").assertHeightIsEqualTo(DroideDimensions.EditorTabBar)
        terminalTab("Ubuntu").assertWidthIsEqualTo(128.dp).assertHeightIsEqualTo(DroideDimensions.EditorTabBar).assertIsSelected()
        terminalTab("Build").assertIsDisplayed().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("two", selected) }
        compose.onNodeWithText("+ Ubuntu").assertHeightIsEqualTo(DroideDimensions.EditorTabBar).performClick()
        compose.runOnIdle { assertEquals(1, created); selected = null }
        compose.onNodeWithContentDescription("Close terminal Ubuntu").assertWidthIsEqualTo(DroideDimensions.EditorTabBar)
            .assertHeightIsEqualTo(DroideDimensions.EditorTabBar).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("one", closed); assertNull("Closing a tab must not select it", selected) }
        terminalTab("Ubuntu").assertDoesNotExist()
        terminalTab("Build").assertIsSelected()
    }

    @Test fun emptyTerminalStillExposesCreationAndBusyStateDoesNotLaunchAnotherSession() {
        var enabled by mutableStateOf(false)
        var created = 0
        compose.setContent { DroideTheme {
            TerminalTabs(TerminalManager.Snapshot(emptyList(), null), {}, {}, onCreate = { created++ }, createEnabled = enabled)
        } }
        compose.onNodeWithText("+ Ubuntu").assertIsDisplayed().assertIsNotEnabled().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, created); enabled = true }
        compose.onNodeWithText("+ Ubuntu").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, created) }
    }

    @Test fun terminalAndEditorStripMatchAndAdjacentControlRemainsReachable() {
        var adjacentClicks = 0
        compose.setContent { DroideTheme { Column {
            Box(Modifier.testTag("editor-strip")) { DroideEditorTabs(listOf("file.kt"), "file.kt", { false }, {}, {}) }
            TerminalTabs(TerminalManager.Snapshot(listOf(tab("one", "Ubuntu")), "one"), {}, {}, onCreate = {})
            androidx.compose.material3.Button(onClick = { adjacentClicks++ }, Modifier.testTag("adjacent-control")) { Text("Adjacent") }
        } } }
        compose.onNodeWithTag("editor-strip").assertHeightIsEqualTo(DroideDimensions.EditorTabBar)
        compose.onNodeWithTag("terminal-tab-strip").assertHeightIsEqualTo(DroideDimensions.EditorTabBar)
        compose.onNodeWithTag("adjacent-control").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, adjacentClicks) }
    }

    @Test fun headerKeepsStopAndKeysAccessibleWhileFullDirectoryAndClearRemainInCommands() {
        val session = Session()
        val path = "/workspace/" + "long-project-directory/".repeat(3) + "src"
        session.workingDirectory.value = path
        var keys = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                DroideTheme {
                    Box(Modifier.width(360.dp)) {
                        TerminalSessionHeader(session, TerminalManager.Profile.LOCAL_LINUX_ARM64, false, null,
                            accessoryHandle = { IconButton(onClick = { keys++ }, modifier = Modifier.size(48.dp).testTag("header-keys")) { Text("Keys") } })
                    }
                }
            }
        }
        compose.onNodeWithTag("terminal-session-header").assertHeightIsEqualTo(52.dp)
        compose.onNodeWithText("Shell ready").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send Ctrl+C / interrupt shell").assertHeightIsEqualTo(48.dp).performClick()
        compose.onNodeWithTag("header-keys").assertHeightIsEqualTo(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, session.interrupts); assertEquals(1, keys); assertEquals(0, session.clears) }
        compose.onNodeWithContentDescription("Clear terminal output").assertDoesNotExist()
        compose.onNodeWithText("Commands").assertHeightIsEqualTo(48.dp).performClick()
        compose.onNodeWithText("Copy directory").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(path, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals("Opening Commands and copying a path must not clear output", 0, session.clears)
        }
        compose.onNodeWithContentDescription("Clear terminal output").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, session.clears) }
        compose.onNodeWithText("Copy directory").assertDoesNotExist()
    }

    @Test fun keyboardSizedLandscapeAndLargeTextKeepUsableViewportAndConcurrentFeedback() {
        val session = Session()
        session.commandActivity.value = TerminalCommandActivity(TerminalCommandPhase.READY, lastExitCode = 7)
        session.agentActivity.value = TerminalAgentActivity(running = 1)
        var width by mutableStateOf(360)
        var height by mutableStateOf(320)
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                DroideTheme {
                    Column(Modifier.requiredSize(width.dp, height.dp)) {
                        TerminalTabs(TerminalManager.Snapshot(listOf(tab("one", "Ubuntu", session)), "one"), {}, {}, onCreate = {})
                        TerminalSessionHeader(session, TerminalManager.Profile.LOCAL_LINUX_ARM64, true, null,
                            status = "Connection active", accessoryHandle = {
                                IconButton(onClick = {}, modifier = Modifier.size(48.dp).testTag("header-keys")) { Text("Keys") }
                            })
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("terminal-viewport"))
                    }
                }
            }
        }
        compose.onNodeWithText("Ready · last command failed (exit 7)").assertIsDisplayed()
        compose.onNodeWithText("Agent command running (1)").assertIsDisplayed()
        compose.onNodeWithText("Connection active").assertIsDisplayed()
        compose.onNodeWithTag("terminal-viewport").assertHeightIsAtLeast(216.dp)
        compose.runOnIdle { width = 640; height = 240; fontScale = 2f }
        compose.onNodeWithContentDescription("Send Ctrl+C / interrupt shell").assertHeightIsEqualTo(48.dp).assertIsDisplayed()
        compose.onNodeWithTag("header-keys").assertHeightIsEqualTo(48.dp).assertIsDisplayed()
        compose.onNodeWithTag("terminal-viewport").assertHeightIsAtLeast(110.dp)
        compose.onNodeWithText("Commands").performClick()
        compose.onNode(hasText("Agent command running (1)") and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("terminal-viewport").assertHeightIsAtLeast(110.dp)
    }
}

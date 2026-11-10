package com.baystudio.droide.ui

import org.junit.Before

import org.robolectric.RuntimeEnvironment

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class TerminalInteractionTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val directory: File = Files.createTempDirectory("terminal-ui-").toFile()
    private val sessions = mutableListOf<Session>()
    private var failStart = false
    private var failClose = false
    private val manager = TerminalManager(scope, directory) { _, _, _ ->
        Session(failStart, failClose).also { sessions += it }
    }
    private var next = 0
    private var width by mutableStateOf(360)
    private var height by mutableStateOf(480)
    private var fontScale by mutableStateOf(1f)

    private class Session(val failStart: Boolean, val failClose: Boolean) : ITerminalSession {
        override val output = MutableStateFlow("$ ")
        var destroyed = 0
        override fun start() { if (failStart) throw IOException("Cannot start PTY") }
        override fun destroy() { destroyed++; if (failClose) throw IOException("Cannot stop PTY") }
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }

    private fun mount() {
        val theme = ThemeManager(directory).snapshot()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                DroideTheme {
                    val state by manager.snapshot.collectAsState()
                    var failure by remember { mutableStateOf<String?>(null) }
                    val focus = remember { AccessoryInputFocusController() }
                    Column(Modifier.requiredSize(width.dp, height.dp)) {
                        Button(onClick = {
                            runUiCatching(onError = { failure = it.message }) { manager.createLocal("Local ${++next}") }
                        }, enabled = manager.canCreateTab) { Text("New terminal") }
                        TerminalTabs(state, onSelect = manager::switch,
                            onClose = { id -> manager.close(id).onFailure { failure = it.message } })
                        failure?.let { Text(it) }
                        state.activeTab?.let { selected ->
                            TerminalPane(selected.session, selected.profile, theme = theme,
                                accessoryInputFocus = focus, accessoryKeysExpanded = false,
                                onAccessoryKeysExpandedChange = {})
                        } ?: Text("No terminal session")
                    }
                }
            }
        }
    }

    @After fun release() { manager.dispose(false); scope.cancel(); directory.deleteRecursively() }

    @Test fun addSecondSwitchCloseBothAndReopenUsingActualClicks() {
        mount()
        compose.onNodeWithText("New terminal").performClick()
        compose.onNodeWithText("New terminal").performClick()
        compose.onNodeWithText("Local 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Local 1", manager.snapshot.value.activeTab?.title) }
        compose.onNodeWithContentDescription("Close terminal Local 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Local 2", manager.snapshot.value.activeTab?.title) }
        compose.onNodeWithContentDescription("Close terminal Local 2").performScrollTo().performClick()
        compose.onNodeWithText("No terminal session").assertExists()
        compose.runOnIdle { assertTrue(sessions.all { it.destroyed == 1 }) }
        compose.onNodeWithText("New terminal").performClick()
        compose.onNodeWithText("Local 3").assertExists()
    }

    @Test fun failedCreationAndCloseAreVisibleWithoutBreakingFurtherClicks() {
        mount()
        compose.onNodeWithText("New terminal").performClick()
        compose.runOnIdle { failStart = true }
        compose.onNodeWithText("New terminal").performClick()
        compose.onNodeWithText("Cannot start PTY").assertExists()
        compose.onNodeWithText("Local 1").assertExists()
        compose.runOnIdle { failStart = false; failClose = true }
        compose.onNodeWithText("New terminal").performClick()
        compose.onNodeWithContentDescription("Close terminal Local 3").performScrollTo().performClick()
        compose.onNodeWithText("Cannot stop PTY").assertExists()
        compose.onNodeWithText("Local 3").assertDoesNotExist()
        compose.onNodeWithText("Local 1").assertExists()
    }

    @Test fun keyboardSizedViewportLargeFontAndRepeatedClicksKeepTabsUsable() {
        mount()
        repeat(8) {
            compose.onNodeWithText("New terminal").performClick()
            compose.runOnIdle { width = if (it % 2 == 0) 640 else 320; height = 160; fontScale = 2f }
            compose.onNodeWithContentDescription("Close terminal Local ${it + 1}").performClick()
            compose.runOnIdle { assertNull(manager.snapshot.value.activeId) }
        }
        compose.onNodeWithText("No terminal session").assertExists()
    }
}

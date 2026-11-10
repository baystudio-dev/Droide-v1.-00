package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ExecResult
import com.baystudio.droide.core.ITerminalSession
import com.baystudio.droide.core.TerminalManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapePanelControlsTest {
    @get:Rule val compose = createComposeRule()
    private class Session : ITerminalSession {
        override val output = MutableStateFlow("shell output")
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }
    private fun tab(id: String, title: String) =
        TerminalManager.Tab(id, Session(), title, TerminalManager.Profile.LOCAL_LINUX_ARM64)
    private fun menu() = compose.onNodeWithContentDescription("Terminal sessions").performClick()

    @Test fun sessionActionsRemainReachableInANarrowPanelAndCloseTheSelectedSessionOnly() {
        var snapshot by mutableStateOf(TerminalManager.Snapshot(listOf(tab("one", "Ubuntu"), tab("two", "Build")), "one"))
        var selected: String? = null
        var closed: String? = null
        var interrupts = 0
        var created = 0
        compose.setContent { DroideTheme { Box(Modifier.width(176.dp)) {
            LandscapeTerminalSessions(snapshot, true,
                onSelect = { selected = it; snapshot = snapshot.copy(activeId = it) },
                onClose = { closed = it }, onCreate = { created++ }, onInterrupt = { interrupts++ })
        } } }
        menu()
        compose.onNodeWithText("Build").performClick()
        compose.runOnIdle { assertEquals("two", selected); assertNull(closed) }
        menu()
        compose.onNodeWithText("Send Ctrl+C").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, interrupts); assertNull(closed) }
        menu()
        compose.onNodeWithText("Close terminal Build").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("two", closed); assertEquals("two", selected) }
        menu()
        compose.onNodeWithText("New Ubuntu terminal").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, created) }
    }

    @Test fun anEmptyPanelCanCreateASessionButHonorsPreparationLock() {
        var enabled by mutableStateOf(false)
        var creates = 0
        compose.setContent { DroideTheme {
            LandscapeTerminalSessions(TerminalManager.Snapshot(emptyList(), null), enabled, {}, {}, { creates++ }, {})
        } }
        menu()
        compose.onNodeWithText("New Ubuntu terminal").assertIsNotEnabled()
        compose.onNodeWithText("Send Ctrl+C").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, creates); enabled = true }
        compose.onNodeWithText("New Ubuntu terminal").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, creates) }
    }

    @Test fun shortPanelKeepsAnInputViewportAndRestoresSessionControlsWhenEnlarged() {
        var height by mutableStateOf(80.dp)
        val session = Session()
        compose.setContent { DroideTheme { Column(Modifier.size(320.dp, height)) {
            TerminalSessionHeader(session, TerminalManager.Profile.LOCAL_LINUX_ARM64, true, null)
            Box(Modifier.weight(1f).fillMaxWidth().testTag("input"))
        } } }
        compose.onNodeWithTag("terminal-session-header").assertDoesNotExist()
        compose.onNodeWithTag("input").assertHeightIsEqualTo(80.dp)
        compose.runOnIdle { height = 160.dp }
        compose.onNodeWithTag("terminal-session-header").assertHeightIsEqualTo(52.dp)
        compose.onNodeWithTag("input").assertHeightIsEqualTo(108.dp)
        compose.onNodeWithText("Commands").assertIsDisplayed()
    }

    @Test fun sharedKeyboardInsetKeepsTheShortTerminalTouchableAndChatWidthStable() {
        var keyboardHeight by mutableIntStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            val ime = WindowInsets(bottom = keyboardHeight)
            Box(Modifier.size(720.dp, 360.dp).landscapeWorkbenchImePadding(true, ime)) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("editor"))
                        Box(Modifier.fillMaxWidth().height(80.dp).windowInsetsPadding(ime)) {
                            Box(Modifier.fillMaxSize().testTag("terminal"))
                        }
                    }
                    Box(Modifier.width(180.dp).fillMaxHeight().windowInsetsPadding(ime)) {
                        Box(Modifier.fillMaxSize().testTag("chat"))
                    }
                }
            }
        } }
        for (pixels in listOf(0, 60, 180, 100, 0)) {
            compose.runOnIdle { keyboardHeight = pixels }
            compose.onNodeWithTag("terminal").assertHeightIsEqualTo(80.dp)
            compose.onNodeWithTag("editor").assertHeightIsEqualTo((280 - pixels).dp)
            compose.onNodeWithTag("chat").assertWidthIsEqualTo(180.dp).assertHeightIsEqualTo((360 - pixels).dp)
        }
    }

    @Test fun narrowLandscapeSessionControlsRemainReachableWithLargeText() {
        val session = Session()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            DroideTheme { Column(Modifier.size(176.dp, 180.dp)) {
                TerminalSessionHeader(session, TerminalManager.Profile.LOCAL_LINUX_ARM64, true, null)
                Box(Modifier.weight(1f).fillMaxWidth().testTag("input"))
            } }
        } }
        compose.onNodeWithTag("input").assertHeightIsAtLeast(80.dp)
        compose.onNodeWithContentDescription("Send Ctrl+C / interrupt shell").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Commands").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Find / Export retained history").assertExists()
    }

    @Test fun parentConsumedKeyboardInsetIsNotAppliedAgainAndPortraitRemainsUntouched() {
        var landscape by mutableStateOf(true)
        var parentConsumes by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            val ime = WindowInsets(bottom = 120)
            Box(Modifier.size(720.dp, 360.dp).then(if (parentConsumes) Modifier.windowInsetsPadding(ime) else Modifier)) {
                Box(Modifier.fillMaxSize().landscapeWorkbenchImePadding(landscape, ime)) {
                    Box(Modifier.fillMaxSize().testTag("workspace"))
                }
            }
        } }
        compose.onNodeWithTag("workspace").assertHeightIsEqualTo(240.dp)
        compose.runOnIdle { parentConsumes = false }
        compose.onNodeWithTag("workspace").assertHeightIsEqualTo(240.dp)
        compose.runOnIdle { landscape = false }
        compose.onNodeWithTag("workspace").assertHeightIsEqualTo(360.dp)
    }
}

package com.baystudio.droide.ui

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise actual Back handling and separate raw/stable keyboard visibility. */
@OptIn(ExperimentalLayoutApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeTerminalImeDismissalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var landscape by mutableStateOf(true)
    private var stableImeVisible by mutableStateOf(true)
    private var terminalFocused by mutableStateOf(true)
    private var latestState: LandscapeInputExpansion? = null
    private var composeView: View? = null
    private var rawImeVisible = false
    private var density = 1f
    private val keyboard = RecordingKeyboard()

    private class RecordingKeyboard : SoftwareKeyboardController {
        var hideCalls = 0
        var showCalls = 0
        override fun hide() { hideCalls++ }
        override fun show() { showCalls++ }
    }

    private fun state() = requireNotNull(latestState)

    private fun mount(controller: SoftwareKeyboardController? = keyboard) {
        compose.setContent {
            val view = LocalView.current
            val currentDensity = LocalDensity.current
            val rawVisible = WindowInsets.isImeVisible
            CompositionLocalProvider(LocalSoftwareKeyboardController provides controller) {
                val current = rememberLandscapeInputExpansion(
                    workspaceKey = "landscape-dismissal-fixture",
                    landscape = landscape,
                    imeVisible = stableImeVisible,
                    focusedInput = if (terminalFocused) LandscapeInputTarget.TERMINAL else LandscapeInputTarget.NONE,
                )
                SideEffect {
                    latestState = current
                    composeView = view
                    density = currentDensity.density
                    rawImeVisible = rawVisible
                }
                Box(Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
        dispatchRawIme(visible = true)
        compose.runOnIdle { assertTrue("Fixture must enter landscape terminal focus", state().terminalExpanded) }
    }

    private fun dispatchRawIme(visible: Boolean) {
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(composeView), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0,
                    if (visible) (120 * density).roundToInt() else 0))
                .setVisible(WindowInsetsCompat.Type.ime(), visible).build())
        }
        compose.waitForIdle()
        assertEquals("Fixture must deliver raw platform visibility", visible, rawImeVisible)
    }

    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun firstBackRequestsHideAndRetainsTheActiveSurfaceWithoutAnExitTimer() {
        mount()
        pressBack()
        compose.runOnIdle {
            assertEquals(1, keyboard.hideCalls)
            assertTrue(state().terminalExpanded)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertTrue("An ignored hide request must not end the surface on a timer", state().terminalExpanded)
            assertEquals(1, keyboard.hideCalls)
            assertEquals(0, keyboard.showCalls)
        }
    }

    @Test fun secondBackEscapesIfTheKeyboardIgnoredTheFirstHideRequest() {
        mount()
        pressBack()
        pressBack()
        compose.runOnIdle {
            assertFalse(state().terminalExpanded)
            assertEquals(2, keyboard.hideCalls)
            terminalFocused = false
        }
        compose.waitForIdle()
        compose.runOnIdle { terminalFocused = true }
        compose.runOnIdle {
            assertFalse("The same still-visible keyboard must not re-enter after explicit escape", state().terminalExpanded)
            assertEquals(0, keyboard.showCalls)
        }
    }

    @Test fun missingKeyboardControllerExitsOnTheFirstDismissal() {
        mount(controller = null)
        // The exposed callback is also used by terminal chrome, independently of Back.
        compose.runOnIdle { state().dismissKeyboard() }
        compose.runOnIdle {
            assertFalse(state().terminalExpanded)
            assertEquals(0, keyboard.hideCalls)
        }
    }

    @Test fun observedKeyboardHideKeepsExpansionUntilExplicitRestore() {
        mount()
        pressBack()
        dispatchRawIme(visible = false)
        compose.runOnIdle {
            assertTrue("Raw hide must not replace the surface during caller visibility grace", state().terminalExpanded)
            stableImeVisible = false
        }
        compose.runOnIdle {
            assertTrue("Keyboard dismissal must preserve the expanded surface", state().terminalExpanded)
            assertEquals(1, keyboard.hideCalls)
        }
        dispatchRawIme(visible = true)
        compose.runOnIdle { stableImeVisible = true }
        compose.runOnIdle {
            assertTrue("A later keyboard show may enter normally", state().terminalExpanded)
            assertEquals(1, keyboard.hideCalls)
        }
    }

    @Test fun rotationClearsPendingDismissalWithoutSendingAnotherKeyboardRequest() {
        mount()
        pressBack()
        compose.runOnIdle { landscape = false }
        compose.runOnIdle {
            assertFalse(state().terminalExpanded)
            assertEquals(1, keyboard.hideCalls)
            landscape = true
        }
        compose.runOnIdle {
            assertTrue("Returning to landscape must not inherit a pending dismissal", state().terminalExpanded)
            assertEquals(1, keyboard.hideCalls)
            assertEquals(0, keyboard.showCalls)
        }
    }

    @Test fun aFreshShowInsideVisibilityGraceCancelsThePendingDismissal() {
        mount()
        pressBack()
        dispatchRawIme(visible = false)
        dispatchRawIme(visible = true)
        compose.runOnIdle { assertTrue(state().terminalExpanded) }
        pressBack()
        compose.runOnIdle {
            assertTrue("The next Back is a new hide request, not the old second-Back escape", state().terminalExpanded)
            assertEquals(2, keyboard.hideCalls)
        }
    }
}

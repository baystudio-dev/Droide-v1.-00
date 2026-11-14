package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Real scaffold input and layout with injected insets; physical IME behavior is tested separately.
@OptIn(ExperimentalLayoutApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessoryTapRoutingR73Test {
    private fun advanceFrame() {
        // Commit state before advancing the manually controlled recomposition clock.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    @get:Rule val compose = createComposeRule()
    private val focus = AccessoryInputFocusController()
    private var owner = focus.owner(AccessoryInputTarget.TERMINAL_NATIVE)
    private val modifiers = AccessoryModifierController()
    private val delivered = mutableListOf<Pair<String, Int>>()
    private var view: View? = null
    private var density = 1f
    private var expanded by mutableStateOf(true)

    private fun mount(editor: Boolean = false) {
        if (editor) owner = focus.owner(AccessoryInputTarget.EDITOR)
        focus.onFocusChanged(owner, true)
        compose.setContent {
            val host = LocalView.current
            val scale = LocalDensity.current.density
            SideEffect { view = host; density = scale }
            DroideTheme {
                Box(Modifier.size(360.dp, 560.dp)) {
                    AccessoryKeysScaffold(
                        focusOwner = owner, focusController = focus, expanded = expanded,
                        onExpandedChange = { expanded = it }, modifiers = modifiers, languageId = "python",
                        onInsertText = { delivered += it to modifiers.androidMetaState() },
                        onKeyCode = { key, extra -> delivered += "key:$key" to modifiers.androidMetaState(extra) },
                    ) { Box(Modifier.weight(1f).fillMaxWidth()) }
                }
            }
        }
        compose.waitForIdle()
        ime(true)
        compose.mainClock.autoAdvance = false
    }

    private fun ime(visible: Boolean) {
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(view), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) (200 * density).toInt() else 0))
                .setVisible(WindowInsetsCompat.Type.ime(), visible).build())
        }
        advanceFrame()
        compose.waitForIdle()
    }

    private fun tap(description: String) {
        compose.onNodeWithContentDescription(description, substring = true).performTouchInput {
            down(center); advanceEventTime(16); up()
        }
    }

    @Test fun controlReleaseArmsBeforeAFollowingSlashWithoutWaitingForDoubleTapTimeout() {
        mount()
        tap("Control modifier")
        compose.runOnIdle { assertEquals(AccessoryModifierMode.ARMED, modifiers.ctrl) }
        tap("Slash")
        compose.runOnIdle {
            assertEquals(listOf("/" to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)), delivered)
            assertEquals(AccessoryModifierMode.OFF, modifiers.ctrl)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(AccessoryModifierMode.OFF, modifiers.ctrl) }
    }

    @Test fun altReleaseAndRapidArrowTapsProduceOneActionPerCompletedGesture() {
        mount()
        tap("Alt modifier")
        compose.runOnIdle { assertEquals(AccessoryModifierMode.ARMED, modifiers.alt) }
        repeat(8) { tap("Up arrow") }
        compose.runOnIdle {
            assertEquals(8, delivered.size)
            assertTrue(delivered.all { it.first == "key:${KeyEvent.KEYCODE_DPAD_UP}" })
            assertEquals(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, delivered.first().second)
            assertTrue(delivered.drop(1).all { it.second == 0 })
        }
    }

    @Test fun arrowLongPressDeliversPageNavigationOnceThenAFreshTapDeliversTheArrow() {
        mount()
        compose.onNodeWithContentDescription("Up arrow", substring = true).performTouchInput { longClick() }
        tap("Up arrow")
        compose.runOnIdle {
            assertEquals(listOf("key:${KeyEvent.KEYCODE_PAGE_UP}" to 0,
                "key:${KeyEvent.KEYCODE_DPAD_UP}" to 0), delivered)
        }
    }

    @Test fun semanticAccessibilityActivationArmsImmediatelyAndForcedControlCIsPreserved() {
        mount()
        compose.onNodeWithContentDescription("Control modifier").performClick()
        compose.runOnIdle { assertEquals(AccessoryModifierMode.ARMED, modifiers.ctrl) }
        compose.onNodeWithContentDescription("Control C").performClick()
        compose.runOnIdle {
            assertEquals(listOf("key:${KeyEvent.KEYCODE_C}" to
                (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)), delivered)
            assertEquals(AccessoryModifierMode.OFF, modifiers.ctrl)
        }
    }

    @Test fun editorPortraitExposesAndDispatchesEveryDirection() {
        mount(editor = true)
        listOf("Left arrow", "Up arrow", "Down arrow", "Right arrow").forEach(::tap)
        compose.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT).map { "key:$it" to 0 }, delivered)
        }
    }

    @Test fun ownerReplacementBetweenDownAndUpCannotDispatchToEitherSession() {
        mount()
        val position = compose.onNodeWithContentDescription("Up arrow", substring = true).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(position) }
        compose.runOnIdle { focus.onFocusChanged(focus.owner(AccessoryInputTarget.TERMINAL_NATIVE), true) }
        advanceFrame()
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(AccessoryModifierMode.OFF, modifiers.ctrl)
            assertTrue(delivered.isEmpty())
        }
    }

    @Test fun imeHideOrCollapseCancelsAnInFlightTapAndReopeningAcceptsAFreshTap() {
        mount()
        for (collapse in listOf(false, true)) {
            val position = compose.onNodeWithContentDescription("Up arrow", substring = true).fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { down(position) }
            if (collapse) compose.runOnIdle { expanded = false } else ime(false)
            advanceFrame()
            compose.onRoot().performTouchInput { up() }
            compose.runOnIdle { assertTrue(delivered.isEmpty()) }
            if (collapse) compose.runOnIdle { expanded = true } else ime(true)
            compose.mainClock.advanceTimeBy(AccessoryMotionMillis.toLong() + 32)
            compose.waitForIdle()
        }
        tap("Up arrow")
        compose.runOnIdle { assertEquals(listOf("key:${KeyEvent.KEYCODE_DPAD_UP}" to 0), delivered) }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeHorizontalDragScrollsInsteadOfActivatingAKeyThenFreshTapWorks() {
        mount()
        val control = compose.onNodeWithContentDescription("Control modifier")
        control.performTouchInput {
            down(center)
            moveBy(Offset(-120f, 0f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(AccessoryModifierMode.OFF, modifiers.ctrl)
            assertTrue(delivered.isEmpty())
        }
        // Settle the active stock scroll fling, then locate a visible key by scrolling.
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithContentDescription("Tab").performScrollTo()
        tap("Tab")
        compose.runOnIdle { assertEquals(listOf("key:${KeyEvent.KEYCODE_TAB}" to 0), delivered) }
    }
}

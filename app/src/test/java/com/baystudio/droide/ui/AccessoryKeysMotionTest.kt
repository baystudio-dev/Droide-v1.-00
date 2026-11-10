package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessoryKeysMotionTest {
    private fun advanceFrame() {

        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    private var motionScaleFactor = 1f
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float get() = motionScaleFactor
    })

    private class SampledInsets : WindowInsets {
        var bottom by mutableStateOf(0)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = bottom
    }

    private val ime = SampledInsets()
    private val source = SampledInsets()
    private val target = SampledInsets()
    private var rawVisible by mutableStateOf(false)
    private var density = 1f
    private var viewportHeight = -1
    private var revealHeight = -1
    private var measuredBodyHeight = -1
    private var sampledMotion: AccessoryImeMotion? = null
    private var hostHeight = -1
    private var composeView: View? = null
    private var receivedImeBottom = -1
    private var receivedImeVisible = false
    private var observedMotionEnabled = true
    private var expanded by mutableStateOf(true)
    private val focus = AccessoryInputFocusController()
    private val owner = focus.owner(AccessoryInputTarget.TERMINAL_FALLBACK)
    private val modifiers = AccessoryModifierController()
    private val keyCodes = mutableListOf<Int>()

    private fun px(dp: Int): Int = (dp * density).roundToInt()

    private fun mountReveal(current: Int, from: Int, to: Int, visible: Boolean) {
        ime.bottom = current
        source.bottom = from
        target.bottom = to
        rawVisible = visible
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val currentDensity = LocalDensity.current
            SideEffect { density = currentDensity.density }
            val motion = rememberAccessoryImeMotion(ime, source, target, rawVisible)
            SideEffect { sampledMotion = motion }
            Column(Modifier.size(320.dp, 400.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height })
                AccessoryRevealLayout(
                    fullHeight = 80.dp,
                    fraction = motion::fraction,
                    modifier = Modifier.fillMaxWidth().onSizeChanged { revealHeight = it.height },
                ) {
                    Box(Modifier.fillMaxWidth().onSizeChanged { measuredBodyHeight = it.height })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sample(current: Int, from: Int, to: Int, visible: Boolean) {
        compose.runOnIdle {
            ime.bottom = current
            source.bottom = from
            target.bottom = to
            rawVisible = visible
        }
        compose.waitForIdle()
    }

    private fun assertReveal(fraction: Float) {
        compose.runOnIdle {
            val expected = (px(80) * fraction).roundToInt()
            assertEquals("The key deck is measured at full touch-target height", px(80), measuredBodyHeight)
            assertEquals("Native geometry determines the real occupied height", expected, revealHeight)
            assertEquals("The viewport receives every unrevealed pixel", px(400) - expected, viewportHeight)
        }
    }

    @Test fun nativeShowAndHideTrackIntermediateInsetsWithoutASecondAnimation() {
        mountReveal(current = 0, from = 0, to = 200, visible = true)
        val clockBefore = compose.mainClock.currentTime
        assertReveal(0f)
        for (bottom in listOf(50, 100, 150, 200)) {
            sample(bottom, 0, 200, visible = true)
            assertReveal(bottom / 200f)
        }
        for (bottom in listOf(200, 150, 100, 50, 0)) {
            sample(bottom, 200, 0, visible = false)
            assertReveal(bottom / 200f)
        }
        sample(0, 0, 0, visible = false)
        assertReveal(0f)
        assertEquals("Native reveal needs no local tween time", clockBefore, compose.mainClock.currentTime)
    }

    @Test fun reversingNativeHideKeepsTheRenderedHeightAndUnsquashedBody() {
        mountReveal(current = 200, from = 200, to = 200, visible = true)
        assertReveal(1f)
        sample(100, 200, 0, visible = false)
        assertReveal(.5f)
        val beforeReversal = revealHeight
        sample(100, 100, 200, visible = true)
        assertEquals("A new positive source must not jump the deck to full height", beforeReversal, revealHeight)
        assertReveal(.5f)
        sample(150, 100, 200, visible = true)
        assertReveal(.75f)
        sample(200, 100, 200, visible = true)
        assertReveal(1f)
    }

    @Test fun nativeCloseWithStaleRawVisibilityCannotReviveThroughFloatingFallback() {
        mountReveal(current = 200, from = 200, to = 200, visible = true)
        assertReveal(1f)
        for (bottom in listOf(200, 100, 0)) {
            sample(bottom, 200, 0, visible = true)
            assertReveal(bottom / 200f)
        }
        sample(0, 0, 0, visible = true)

        advanceFrame()
        advanceFrame()
        assertReveal(0f)
        compose.runOnIdle { assertFalse(requireNotNull(sampledMotion).allowsInput()) }
        compose.mainClock.advanceTimeBy(AccessoryMotionMillis / 2L)
        assertReveal(0f)
        compose.mainClock.advanceTimeBy(AccessoryMotionMillis / 2L)
        assertReveal(0f)
        advanceFrame()
        assertReveal(0f)

        sample(0, 0, 0, visible = false)
        // The wrapper must observe the real hidden boundary before the new show.
        advanceFrame()
        advanceFrame()
        assertReveal(0f)
        sample(0, 0, 0, visible = true)
        advanceFrame()
        advanceFrame()
        compose.mainClock.advanceTimeBy(AccessoryMotionMillis.toLong() + 32)
        assertReveal(1f)
        compose.runOnIdle { assertTrue(requireNotNull(sampledMotion).allowsInput()) }
    }

    private fun mountScaffold() {
        focus.onFocusChanged(owner, true)
        compose.setContent {
            val view = LocalView.current
            val currentDensity = LocalDensity.current
            val bottom = WindowInsets.ime.getBottom(currentDensity)
            val visible = WindowInsets.isImeVisible
            val motionEnabled = rememberDroideMotionEnabled()
            SideEffect {
                composeView = view
                density = currentDensity.density
                receivedImeBottom = bottom
                receivedImeVisible = visible
                observedMotionEnabled = motionEnabled
            }
            DroideTheme {
                Box(Modifier.size(360.dp, 560.dp).onSizeChanged { hostHeight = it.height }) {
                    AccessoryKeysScaffold(
                        focusOwner = owner,
                        focusController = focus,
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifiers = modifiers,
                        onInsertText = {},
                        onKeyCode = { keyCode, _ -> keyCodes += keyCode },
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth()
                            .onSizeChanged { viewportHeight = it.height }.testTag("accessory-viewport"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun dispatchIme(heightDp: Int) {
        val pixels = px(heightDp)
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(composeView), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, pixels))
                .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0).build())
        }
        compose.waitForIdle()
        assertEquals("Fixture must deliver platform IME geometry", pixels, receivedImeBottom)
        assertEquals("Fixture must deliver raw IME visibility", heightDp > 0, receivedImeVisible)
    }

    private fun savedClick(description: String): () -> Boolean = requireNotNull(
        compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action,
    )

    @Test fun realScaffoldReturnsKeyboardAndDeckHeightToTheViewportAfterHide() {
        mountScaffold()
        dispatchIme(200)
        compose.onNodeWithContentDescription("Escape").assertExists()
        assertEquals("Portrait deck occupies two real 44 dp rows", hostHeight - px(200) - px(88), viewportHeight)
        dispatchIme(0)
        compose.onNodeWithContentDescription("Escape").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide terminal keys").assertDoesNotExist()
        assertEquals("Hidden retained children must reserve no layout height", hostHeight, viewportHeight)
    }

    @Test fun manualCollapseAnimatesViewportHeightButDisablesRetainedKeysImmediately() {
        mountScaffold()
        dispatchIme(200)
        val staleClick = savedClick("Escape")
        val expandedHeight = viewportHeight
        val collapsedHeight = hostHeight - px(200)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Hide terminal keys").performClick()
        advanceFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertTrue("Collapse must reclaim height progressively", viewportHeight > expandedHeight)
        assertTrue("The exiting body is still retained mid-animation", viewportHeight < collapsedHeight)
        compose.onNodeWithContentDescription("Escape").assertDoesNotExist()
        compose.runOnIdle {
            staleClick()
            assertTrue("An old accessibility callback cannot type after collapse", keyCodes.isEmpty())
        }
        compose.mainClock.advanceTimeBy(AccessoryMotionMillis.toLong() + 32)
        compose.waitForIdle()
        assertEquals(collapsedHeight, viewportHeight)
        compose.onNodeWithContentDescription("Show terminal keys").assertExists()
    }

    @Test fun focusLossGuardsAlreadyCapturedKeyAndModifierCallbacksWhileBodyExits() {
        mountScaffold()
        dispatchIme(200)
        val staleKey = savedClick("Escape")
        val staleModifier = savedClick("Control modifier")
        compose.runOnIdle {
            staleKey()
            assertEquals(listOf(KeyEvent.KEYCODE_ESCAPE), keyCodes)
            keyCodes.clear()
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            focus.clearIf(owner)

            staleKey()
            staleModifier()
            assertTrue(keyCodes.isEmpty())
            assertEquals(AccessoryModifierMode.OFF, modifiers.ctrl)
        }
        advanceFrame()
        compose.waitForIdle()
        assertTrue("Focus exit retains pixels briefly", viewportHeight < hostHeight - px(200))
        compose.onNodeWithContentDescription("Escape").assertDoesNotExist()
        compose.onNodeWithContentDescription("Control modifier").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(AccessoryMotionMillis.toLong() + 32)
        compose.waitForIdle()
        assertEquals(hostHeight - px(200), viewportHeight)
    }

    @Test fun reducedMotionCollapsesTheRealScaffoldWithoutWaitingForTweenDuration() {
        motionScaleFactor = 0f
        mountScaffold()
        dispatchIme(200)
        assertFalse("The production coroutine context must observe reduced motion", observedMotionEnabled)
        assertEquals(hostHeight - px(200) - px(88), viewportHeight)
        compose.mainClock.autoAdvance = false
        val before = compose.mainClock.currentTime
        compose.onNodeWithContentDescription("Hide terminal keys").performClick()

        advanceFrame()
        advanceFrame()
        compose.waitForIdle()
        assertTrue(compose.mainClock.currentTime - before < AccessoryMotionMillis)
        assertEquals(hostHeight - px(200), viewportHeight)
        compose.onNodeWithContentDescription("Escape").assertDoesNotExist()
    }
}

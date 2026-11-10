package com.baystudio.droide.ui

import android.app.Application
import android.os.Build
import androidx.compose.runtime.snapshots.Snapshot
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalLayoutApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 35], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessoryExitTimingTest {
    private fun advanceFrame() {

        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    @get:Rule val compose = createComposeRule()
    private val focus = AccessoryInputFocusController()
    private val owner = focus.owner(AccessoryInputTarget.TERMINAL_FALLBACK)
    private val modifiers = AccessoryModifierController()
    private var composeView: View? = null
    private var density = 1f
    private var viewportHeight = 0
    private var hostHeight = 0
    private var navigationClicks = 0
    private var keyClicks = 0
    private var receivedImeBottom = 0
    private var receivedImeVisible = false

    private fun mountScaffold() {
        focus.onFocusChanged(owner, true)
        compose.setContent {
            val view = LocalView.current
            val d = LocalDensity.current
            val bottom = WindowInsets.ime.getBottom(d)
            val visible = WindowInsets.isImeVisible
            SideEffect {
                composeView = view
                density = d.density
                receivedImeBottom = bottom
                receivedImeVisible = visible
            }
            DroideTheme {
                Box(Modifier.size(360.dp, 560.dp).onSizeChanged { hostHeight = it.height }) {

                    Box(Modifier.fillMaxSize().testTag("navigation-underlay")
                        .clickable { navigationClicks++ })
                    AccessoryKeysScaffold(
                        focusOwner = owner, focusController = focus, expanded = true,
                        onExpandedChange = {}, modifiers = modifiers, onInsertText = {},
                        onKeyCode = { _, _ -> keyClicks++ },
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun dispatchIme(bottomDp: Int, visible: Boolean) {
        compose.runOnIdle {
            val pixels = Insets.of(0, 0, 0, (bottomDp * density).toInt())
            val insets = if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), pixels)
                    .setVisible(WindowInsetsCompat.Type.ime(), visible).build()
            } else {
                WindowInsetsCompat.Builder()
                    .setSystemWindowInsets(pixels)
                    .setStableInsets(Insets.NONE).build()
            }
            assertEquals("Fixture must preserve requested IME visibility", visible, insets.isVisible(WindowInsetsCompat.Type.ime()))
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(composeView), insets)
        }
        compose.waitForIdle()
        assertEquals("Scaffold must receive the injected IME height", (bottomDp * density).toInt(), receivedImeBottom)
        assertEquals("Scaffold must receive the injected IME visibility", visible, receivedImeVisible)
    }

    private fun tapNavigation(xFraction: Float, yFraction: Float) {
        compose.onNodeWithTag("navigation-underlay").performTouchInput {
            click(Offset(width * xFraction, height * yFraction))
        }
        compose.runOnIdle { assertEquals(0, keyClicks) }
    }

    @Test fun hiddenRetainedOverlayHandleDoesNotShieldTheBottomRightTarget() {
        mountScaffold()
        dispatchIme(200, true)
        dispatchIme(0, false)
        compose.runOnIdle { assertEquals(hostHeight, viewportHeight) }
        tapNavigation(.95f, .97f)
        compose.runOnIdle { assertEquals(1, navigationClicks) }
    }

    private fun assertExitingDeckPassesThrough() {
        mountScaffold()
        // API 29 derives IME visibility from height; floating visibility requires API 30.
        dispatchIme(if (Build.VERSION.SDK_INT >= 30) 0 else 200, true)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { focus.clearIf(owner) }
        advanceFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Exit pixels should still be retained", viewportHeight < hostHeight) }
        tapNavigation(.5f, .97f)
        compose.runOnIdle { assertEquals(1, navigationClicks) }

        compose.mainClock.advanceTimeBy(AccessoryMotionMillis.toLong() + 32)
        compose.waitForIdle()
        tapNavigation(.95f, .97f)
        compose.runOnIdle { assertEquals(2, navigationClicks) }
    }

    @Test fun portraitDeckReleasesTouchesBeforeItsExitAnimationCompletes() = assertExitingDeckPassesThrough()

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeScrolledRowReleasesTouchesBeforeItsExitAnimationCompletes() = assertExitingDeckPassesThrough()

    @Test fun decorativeScrollKeepsLtrRtlOffsetAndResizeBoundsThenReopens() {
        val state = ScrollState(80)
        var interactive by mutableStateOf(true)
        var width by mutableStateOf(100.dp)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var childX = Float.NaN
        var parentX = Float.NaN
        var measuredWidth = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.width(width).height(44.dp)
                    .onGloballyPositioned { parentX = it.positionInRoot().x; measuredWidth = it.size.width }) {
                    Row(Modifier.fillMaxSize().accessoryHorizontalScroll(state, interactive)) {
                        Box(Modifier.width(300.dp).fillMaxHeight().onGloballyPositioned { childX = it.positionInRoot().x })
                    }
                }
            }
        }
        compose.waitForIdle()
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction = layoutDirection; interactive = true; width = 100.dp }
            compose.waitForIdle()
            val shownX = childX - parentX
            val shownWidth = measuredWidth
            compose.runOnIdle { interactive = false }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(shownX, childX - parentX, .01f)
                assertEquals(shownWidth, measuredWidth)
                assertEquals(80, state.value)
            }
            // Resizing the decorative row clamps its placement without rewriting saved scroll.
            compose.runOnIdle { width = 280.dp }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(if (layoutDirection == LayoutDirection.Ltr) -20f else 0f, childX - parentX, .01f)
                assertEquals(80, state.value)
            }
            compose.runOnIdle { width = 100.dp; interactive = true }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(shownX, childX - parentX, .01f) }
        }
        compose.runOnIdle { interactive = false; width = 0.dp }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, measuredWidth); assertEquals(80, state.value) }
    }
}

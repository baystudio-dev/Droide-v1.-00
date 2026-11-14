package com.baystudio.droide.ui

import android.app.Application
import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.baystudio.droide.WorkspaceStartupFailureContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R51 authored, source-only regression candidates; not compiled or executed in this review.
 * These measure the production inset host and a real descendant Material3 Scaffold with injected
 * native inset samples. They do not establish OS inset dispatch, Activity recreation, device IME,
 * Sora, terminal-emulator, or Chromium behavior. Native identity probes below are plain Views.
 *
 * Authored matrix targets SDK 29/30/34/35. SDK 36 integration remains deferred: the pinned
 * Robolectric 4.14.1 runner does not supply that SDK, and no runner/dependency update is authorized.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 34, 35], application = Application::class,
    qualifiers = "w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarInsetsHostTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    private class SampledInsets(
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ) : WindowInsets {
        var left by mutableStateOf(left)
        var top by mutableStateOf(top)
        var right by mutableStateOf(right)
        var bottom by mutableStateOf(bottom)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = left
        override fun getTop(density: Density) = top
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = right
        override fun getBottom(density: Density) = bottom
    }

    private val status = SampledInsets(top = 100)
    private val shownStatus = SampledInsets(top = 100)
    private val navigation = SampledInsets(bottom = 20)
    private val caption = SampledInsets()
    private val cutout = SampledInsets()
    private val ime = SampledInsets()
    private val system = status.union(navigation).union(caption)
    private val rawSafe = system.union(cutout)
    private var motionEnabled by mutableStateOf(true)
    private var requestedHidden by mutableStateOf(false)
    private var reclaimTopCutout by mutableStateOf(false)
    private var metricsKey by mutableStateOf<Any>("portrait@1")
    private var rootBounds = Rect.Zero
    private var hostBounds = Rect.Zero
    private var childBounds = Rect.Zero
    private var paneBounds = Rect.Zero
    private val createdOwners = mutableListOf<Pair<String, Any>>()
    private val currentOwners = mutableMapOf<String, Any>()
    private val createdViews = mutableListOf<Pair<String, View>>()
    private val currentViews = mutableMapOf<String, View>()
    private val disposedOwners = mutableListOf<String>()

    private fun Modifier.recordBounds(record: (Rect) -> Unit): Modifier = onGloballyPositioned {
        // Unclipped measured coordinates expose a real relayout rather than a visual translation.
        record(Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())))
    }

    @Composable
    private fun RawInsetScaffold(childHandlesIme: Boolean = false) {
        Scaffold(contentWindowInsets = rawSafe) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
                .recordBounds { childBounds = it }) {
                Box(Modifier.fillMaxSize().then(
                    if (childHandlesIme) Modifier.windowInsetsPadding(ime) else Modifier,
                )) {
                    Box(Modifier.fillMaxSize().recordBounds { paneBounds = it })
                }
            }
        }
    }

    private fun mount(
        legacyImeResize: Boolean = Build.VERSION.SDK_INT < 35,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit = { RawInsetScaffold() },
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DroideTheme {
                    Box(Modifier.size(320.dp, 560.dp).recordBounds { rootBounds = it }) {
                        StatusBarInsetsHost(
                            status = status,
                            shownStatus = shownStatus,
                            system = system,
                            navigation = navigation,
                            caption = caption,
                            cutout = cutout,
                            ime = ime,
                            legacyImeResize = legacyImeResize,
                            statusBarRequestedHidden = requestedHidden,
                            reclaimTopCutout = reclaimTopCutout,
                            metricsKey = metricsKey,
                            motionEnabled = motionEnabled,
                        ) {
                            Box(Modifier.fillMaxSize().recordBounds { hostBounds = it }) {
                                content()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sample(top: Int) {
        compose.runOnIdle { status.top = top }
        compose.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun startFallbackClock() {
        // Commit the layout-produced plan, then supply the animation's initial frame.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun finishFallback() = advance(StatusBarFallbackMillis.toLong() + 64)

    private fun totalTop() = childBounds.top - rootBounds.top

    private fun assertGeometry(top: Float, bottom: Int = 20, left: Int = 0, right: Int = 0) {
        compose.runOnIdle {
            assertTrue("Fixture must have a measured root", rootBounds.width > 0 && rootBounds.height > 0)
            assertEquals("Root padding must reserve the rendered status/cutout geometry",
                top, hostBounds.top - rootBounds.top, 1f)
            assertEquals("Total root + child top must not restore a raw endpoint inset",
                top, totalTop(), 1f)
            assertEquals("Descendant Scaffold must not pad the same top again",
                0f, childBounds.top - hostBounds.top, 1f)
            assertEquals(left.toFloat(), childBounds.left - rootBounds.left, 1f)
            assertEquals(right.toFloat(), rootBounds.right - childBounds.right, 1f)
            assertEquals(bottom.toFloat(), rootBounds.bottom - childBounds.bottom, 1f)
            assertEquals("Returned top pixels must actually enlarge the measured content",
                rootBounds.height - top - bottom, childBounds.height, 1f)
        }
    }

    @Test fun nativeProgressMeasuresRootAndChildAtEverySampleWithoutASecondClock() {
        mount()
        val before = compose.mainClock.currentTime
        for (top in listOf(100, 76, 43, 12, 0, 0, 19, 54, 83, 100, 100)) {
            sample(top)
            assertGeometry(top.toFloat())
        }
        assertEquals("Native insets require no local tween time", before, compose.mainClock.currentTime)
        finishFallback()
        assertGeometry(100f)
    }

    @Test fun endpointHideReturnsMeasuredViewportHeightProgressivelyAndFinishesExactly() {
        mount()
        assertGeometry(100f)
        sample(0)
        assertGeometry(100f)
        startFallbackClock()
        advance(64)
        val middle = totalTop()
        assertTrue("Endpoint-only hide needs an actual intermediate layout", middle > 0f && middle < 100f)
        assertGeometry(middle)
        finishFallback()
        assertGeometry(0f)
        finishFallback()
        assertGeometry(0f)
    }

    @Test fun endpointShowConsumesRawChildInsetBeforeTheDisplayedRootReachesIt() {
        status.top = 0
        mount()
        assertGeometry(0f)
        sample(100)
        assertGeometry(0f)
        startFallbackClock()
        advance(64)
        val middle = totalTop()
        assertTrue("Child Scaffold cannot jump to the raw 100 px show endpoint", middle > 0f && middle < 100f)
        assertGeometry(middle)
        finishFallback()
        assertGeometry(100f)
    }

    @Test fun endpointReversalsStartFromTheLastMeasuredPositionInBothDirections() {
        mount()
        sample(0)
        startFallbackClock()
        advance(64)
        val hiding = totalTop()
        assertTrue(hiding > 0f && hiding < 100f)
        sample(100)
        assertGeometry(hiding)
        startFallbackClock()
        advance(48)
        val showing = totalTop()
        assertTrue("Reversal must advance toward shown geometry", showing > hiding && showing < 100f)
        sample(0)
        assertGeometry(showing)
        startFallbackClock()
        advance(48)
        assertTrue("Second reversal must advance toward hidden geometry", totalTop() < showing && totalTop() > 0f)
        finishFallback()
        assertGeometry(0f)
    }

    @Test fun nativeSamplesTakeOverAnActiveFallbackWithoutStaleFramesOrAnimatedTail() {
        mount()
        sample(0)
        startFallbackClock()
        advance(48)
        for (top in listOf(63, 29, 0)) {
            sample(top)
            assertGeometry(top.toFloat())
        }
        finishFallback()
        assertGeometry(0f)
    }

    @Test fun reducedMotionCancelsTheActiveFallbackAndSubsequentEndpointChangesSnap() {
        mount()
        sample(0)
        startFallbackClock()
        advance(48)
        assertTrue(totalTop() > 0f && totalTop() < 100f)
        compose.runOnIdle { motionEnabled = false }
        startFallbackClock()
        assertGeometry(0f)
        val before = compose.mainClock.currentTime
        sample(100)
        assertGeometry(100f)
        sample(0)
        assertGeometry(0f)
        assertEquals(before, compose.mainClock.currentTime)
        finishFallback()
        assertGeometry(0f)
    }

    @Test fun cutoutRemainsAnIndependentFloorDuringNativeAndFallbackMotion() {
        cutout.top = 30
        mount()
        for (top in listOf(100, 64, 20, 0)) {
            sample(top)
            assertGeometry(maxOf(30, top).toFloat())
        }
        sample(100)
        assertGeometry(30f)
        startFallbackClock()
        advance(64)
        assertTrue("Cutout clearance is a maximum, never an added animated contribution",
            totalTop() >= 30f && totalTop() < 100f)
        assertGeometry(totalTop())
        finishFallback()
        assertGeometry(100f)
        sample(0)
        startFallbackClock()
        finishFallback()
        assertGeometry(30f)
    }

    @Test fun captionNavigationAndPhysicalSideSafetySurviveStatusChangesInRtl() {
        navigation.left = 8
        navigation.top = 12
        navigation.right = 9
        caption.top = 44
        cutout.left = 36
        cutout.top = 30
        cutout.right = 28
        cutout.bottom = 4
        mount(direction = LayoutDirection.Rtl)
        for (top in listOf(100, 60, 20, 0)) {
            sample(top)
            assertGeometry(maxOf(44, top).toFloat(), bottom = 20, left = 36, right = 28)
        }
        compose.runOnIdle { caption.top = 0; cutout.top = 0 }
        compose.waitForIdle()
        assertGeometry(12f, bottom = 20, left = 36, right = 28)
    }

    @Test
    @Config(sdk = [29, 30, 34])
    fun legacyRootConsumesOnlyResidualImeAndDescendantDoesNotResizeTwice() {
        mount { RawInsetScaffold(childHandlesIme = true) }
        for (bottom in listOf(200, 110, 20, 0)) {
            compose.runOnIdle { ime.bottom = bottom }
            compose.waitForIdle()
            val safeBottom = maxOf(20, bottom)
            assertGeometry(100f, bottom = safeBottom)
            assertEquals("The pane's own IME padding is already consumed by legacy root resize",
                safeBottom.toFloat(), rootBounds.bottom - paneBounds.bottom, 1f)
            assertEquals(bottom, ime.bottom)
        }
        sample(48)
        assertGeometry(48f)
    }

    @Test
    @Config(sdk = [29, 30, 34])
    fun metricsChangeDuringFallbackAdoptsNewStatusAndImeWithoutStaleMotion() {
        ime.bottom = 180
        mount { RawInsetScaffold(childHandlesIme = true) }
        assertGeometry(100f, bottom = 180)
        sample(0)
        startFallbackClock()
        advance(64)
        assertTrue("Fixture must interrupt a real fallback", totalTop() > 0f && totalTop() < 100f)

        compose.runOnIdle {
            // Inject the metrics boundary a rotation/density update would deliver. This is
            // deliberately not an Activity recreation or native rotation integration claim.
            metricsKey = "landscape@2"
            shownStatus.top = 80
            status.top = 80
            ime.bottom = 240
        }
        startFallbackClock()
        assertGeometry(80f, bottom = 240)
        assertEquals("New IME height is consumed exactly once across root and pane",
            240f, rootBounds.bottom - paneBounds.bottom, 1f)
        finishFallback()
        assertGeometry(80f, bottom = 240)
        assertEquals(240f, rootBounds.bottom - paneBounds.bottom, 1f)

        // Old-generation frames must neither revive the interrupted hide nor prevent a
        // subsequent legitimate endpoint transition using the new shown height.
        sample(0)
        assertGeometry(80f, bottom = 240)
        startFallbackClock()
        advance(64)
        assertTrue(totalTop() > 0f && totalTop() < 80f)
        finishFallback()
        assertGeometry(0f, bottom = 240)
        assertEquals(240f, rootBounds.bottom - paneBounds.bottom, 1f)
    }

    @Test
    @Config(sdk = [35])
    fun modernRootLeavesImeResizeWithThePaneAndRetainsNavigationClearance() {
        mount { RawInsetScaffold(childHandlesIme = true) }
        for (bottom in listOf(200, 110, 20, 0)) {
            compose.runOnIdle { ime.bottom = bottom }
            compose.waitForIdle()
            assertGeometry(100f, bottom = 20)
            assertEquals("Modern pane still receives its unconsumed IME residual",
                maxOf(20, bottom).toFloat(), rootBounds.bottom - paneBounds.bottom, 1f)
            assertEquals(bottom, ime.bottom)
        }
    }

    @Test fun actualStartupFailureControlsStayInsideSafeContentAndKeepTheirCallbacks() {
        cutout.top = 32
        var retries = 0
        var temporaryStarts = 0
        mount {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WorkspaceStartupFailureContent(
                    error = "Test startup failure",
                    onRetry = { retries++ },
                    onTemporaryBuiltins = { temporaryStarts++ },
                )
            }
        }
        for (top in listOf(100, 50, 0)) {
            sample(top)
            assertEquals(maxOf(32, top).toFloat(), hostBounds.top - rootBounds.top, 1f)
            for (label in listOf("Retry normal startup", "Open temporarily with built-ins")) {
                val node = compose.onNodeWithText(label).assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("Startup action must be below root top safety", bounds.top >= hostBounds.top)
                assertTrue("Startup action must be above navigation safety", bounds.bottom <= hostBounds.bottom)
                node.performClick()
            }
        }
        assertEquals(3, retries)
        assertEquals(3, temporaryStarts)
    }

    @Test fun temporaryStartupBannerAndChildScaffoldShareOneMovingTopReservation() {
        mount {
            Column(Modifier.fillMaxSize()) {
                // Models the existing Activity banner ahead of IdeScreen's Scaffold.
                Text("Temporary built-in startup.", Modifier.fillMaxWidth().height(40.dp).testTag("startup-banner"))
                Box(Modifier.weight(1f)) { RawInsetScaffold() }
            }
        }
        fun assertBanner(top: Float) {
            val banner = compose.onNodeWithTag("startup-banner").fetchSemanticsNode().boundsInRoot
            assertEquals(top, banner.top - rootBounds.top, 1f)
            assertEquals("Child Scaffold adds no second status inset after the banner",
                banner.bottom, childBounds.top, 1f)
            assertEquals(20f, rootBounds.bottom - childBounds.bottom, 1f)
        }
        assertBanner(100f)
        sample(0)
        assertBanner(100f)
        startFallbackClock()
        advance(64)
        val middle = hostBounds.top - rootBounds.top
        assertTrue(middle > 0f && middle < 100f)
        assertBanner(middle)
        finishFallback()
        assertBanner(0f)
    }

    @Composable
    private fun RetainedOwner(name: String) {
        val owner = remember { Any().also { createdOwners += name to it } }
        DisposableEffect(owner) { onDispose { disposedOwners += name } }
        SideEffect { currentOwners[name] = owner }
        AndroidView(
            factory = { context ->
                View(context).also { createdViews += name to it; currentViews[name] = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test fun insetMotionRetainsEveryContentOwnerAndItsNativeView() {
        val names = listOf("editor", "terminal", "browser", "agent")
        mount {
            Column(Modifier.fillMaxSize()) {
                for (name in names) Box(Modifier.weight(1f).fillMaxWidth()) { RetainedOwner(name) }
            }
        }
        val owners = currentOwners.toMap()
        val views = currentViews.toMap()
        for (top in listOf(72, 31, 0, 45, 100)) sample(top)
        sample(0)
        startFallbackClock()
        advance(48)
        sample(100)
        startFallbackClock()
        finishFallback()
        compose.runOnIdle { metricsKey = "landscape@1" }
        startFallbackClock()
        compose.runOnIdle {
            assertEquals(names.size, createdOwners.size)
            assertEquals(names.size, createdViews.size)
            assertTrue("Changing insets must not dispose content owners", disposedOwners.isEmpty())
            for (name in names) {
                assertSame(owners.getValue(name), currentOwners.getValue(name))
                assertSame(views.getValue(name), currentViews.getValue(name))
                assertTrue("Retained native view still has measured layout", currentViews.getValue(name).height > 0)
            }
        }
    }

    @Test fun completedImmersiveHideKeepsTransientRevealOutOfRootAndChildGeometry() {
        requestedHidden = true
        mount()
        for (top in listOf(100, 66, 23, 0)) {
            sample(top)
            assertGeometry(top.toFloat())
        }
        val before = compose.mainClock.currentTime
        for (top in listOf(18, 57, 100, 100, 61, 17, 0)) {
            sample(top)
            assertGeometry(0f)
        }
        assertEquals("A transient overlay must not launch a root tween", before, compose.mainClock.currentTime)
        compose.runOnIdle { requestedHidden = false }
        startFallbackClock()
        sample(100)
        startFallbackClock()
        finishFallback()
        assertGeometry(100f)
    }
    @Test fun editorReclaimsStatusSpaceBeforeNativeHideWithoutGrowingTheHeader() {
        cutout.top = 30
        var headerControls = Rect.Zero
        mount {
            Column(Modifier.fillMaxSize()) {
                ReclaimedCutoutHeader {
                    Box(Modifier.fillMaxWidth().height(DroideDimensions.PrimaryTouch)
                        .recordBounds { headerControls = it })
                }
                RawInsetScaffold()
            }
        }
        val before = headerControls.bottom
        compose.runOnIdle { requestedHidden = true; reclaimTopCutout = true }
        startFallbackClock()
        advance(64)
        assertTrue("The requested editor transition starts while the native bar is still shown",
            hostBounds.top > rootBounds.top && hostBounds.top < rootBounds.top + 100f)
        finishFallback()
        compose.runOnIdle {
            assertEquals("The hidden status strip is reclaimed", rootBounds.top, hostBounds.top, 1f)
            assertEquals("The header does not consume the reclaimed status space", before - 100f, headerControls.bottom, 1f)
            assertEquals("The native callback is deliberately still delayed", 100, status.top)
        }
        for (top in listOf(83, 28, 0, 100)) sample(top)
        assertEquals(rootBounds.top, hostBounds.top, 1f)
    }

    @Test fun initialEditorAndReducedMotionReclaimImmediatelyWithoutTouchingNavigationOrIme() {
        requestedHidden = true
        reclaimTopCutout = true
        motionEnabled = false
        cutout.top = 30
        cutout.left = 18
        mount()
        assertGeometry(0f, left = 18)
        compose.runOnIdle { ime.bottom = 180 }
        compose.waitForIdle()
        assertGeometry(0f, bottom = if (Build.VERSION.SDK_INT < 35) 180 else 20, left = 18)
    }

}

package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** These intentionally do not create WebView or invoke native project tooling. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h600dp")
class WorkspaceBrowserHostTest {
    @get:Rule val compose = createComposeRule()

    private val placement = WorkspaceBrowserPlacement()
    private var selected by mutableStateOf<WorkspaceBrowserSlot?>(WorkspaceBrowserSlot.MAIN)
    private var keepActive by mutableStateOf(false)
    private var stopping by mutableStateOf(false)
    private var showReporters by mutableStateOf(true)
    private var mainWidth by mutableStateOf(350.dp)
    private var mainHeight by mutableStateOf(260.dp)
    private var workspaceWidth by mutableStateOf(620.dp)
    private var workspaceHeight by mutableStateOf(460.dp)
    private val created = mutableListOf<Any>()
    private val disposed = mutableListOf<Any>()
    private val measuredSizes = mutableListOf<IntSize>()
    private val measuredBounds = mutableListOf<Rect>()
    private var currentToken: Any? = null
    private var currentPresented: Boolean? = null
    private var currentCaptureReady: Boolean? = null
    private var browserClicks = 0
    private var workspaceClicks = 0

    @Composable
    private fun FakeBrowser(presented: Boolean, captureReady: Boolean) {
        val token = remember { Any().also { created += it } }
        DisposableEffect(token) { onDispose { disposed += token } }
        SideEffect { currentToken = token; currentPresented = presented; currentCaptureReady = captureReady }
        Box(Modifier.fillMaxSize()
            .testTag("fake-browser")
            .semantics { contentDescription = "Browser toolbar action" }
            .clickable { browserClicks++ }
            .onGloballyPositioned {
                measuredSizes += it.size
                measuredBounds += Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
            })
    }

    private fun mount(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {

                Box(Modifier.size(workspaceWidth, workspaceHeight).padding(start = 13.dp, top = 17.dp)) {
                    Box(Modifier.fillMaxSize().testTag("test-workspace")
                        .onGloballyPositioned { placement.updateRoot(it.boundsInRoot()) }) {
                        Box(Modifier.fillMaxSize().testTag("workspace-underlay")
                            .clickable { workspaceClicks++ })
                        if (showReporters) {
                            when (selected) {
                                WorkspaceBrowserSlot.MAIN -> Box(
                                    Modifier.absoluteOffset(117.dp, 31.dp).size(mainWidth, mainHeight),
                                ) { WorkspaceBrowserPlaceholder(placement, WorkspaceBrowserSlot.MAIN) }
                                WorkspaceBrowserSlot.SIDEBAR -> Box(
                                    Modifier.absoluteOffset(19.dp, 43.dp).size(170.dp, 320.dp),
                                ) { WorkspaceBrowserPlaceholder(placement, WorkspaceBrowserSlot.SIDEBAR) }
                                WorkspaceBrowserSlot.EXPANDED -> Box(Modifier.fillMaxSize()) {
                                    WorkspaceBrowserPlaceholder(placement, WorkspaceBrowserSlot.EXPANDED)
                                }
                                null -> Unit
                            }
                        }
                        WorkspaceBrowserHost(placement, selected, keepActive, stopping) { presented, captureReady ->
                            FakeBrowser(presented, captureReady)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun expandedTransitionsRetainOneOwnerAndRestoreBothNormalSlots() {
        mount()
        val original = currentToken
        for (normal in listOf(WorkspaceBrowserSlot.MAIN, WorkspaceBrowserSlot.SIDEBAR)) {
            for (retained in listOf(false, true)) {
                compose.runOnIdle { keepActive = retained; selected = normal }
                repeat(5) {
                    compose.runOnIdle { selected = WorkspaceBrowserSlot.EXPANDED }
                    assertHostMatchesSlot(WorkspaceBrowserSlot.EXPANDED)
                    compose.runOnIdle { selected = normal }
                    assertHostMatchesSlot(normal)
                }
            }
        }
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(1, created.size)
            assertTrue(disposed.isEmpty())
        }
    }

    @Test fun expandedConstraintChangesRetainOwnerWithoutClaimingActivityRotation() {
        mount()
        val original = currentToken
        compose.runOnIdle { selected = WorkspaceBrowserSlot.EXPANDED }
        assertHostMatchesSlot(WorkspaceBrowserSlot.EXPANDED)
        compose.runOnIdle { workspaceWidth = 380.dp; workspaceHeight = 580.dp }
        assertHostMatchesSlot(WorkspaceBrowserSlot.EXPANDED)
        compose.runOnIdle { workspaceWidth = 620.dp; workspaceHeight = 340.dp }
        assertHostMatchesSlot(WorkspaceBrowserSlot.EXPANDED)
        compose.runOnIdle { assertSame(original, currentToken); assertTrue(disposed.isEmpty()) }
    }

    private fun assertHostMatchesSlot(slot: WorkspaceBrowserSlot) {
        val expected = compose.onNodeWithTag("browser-slot-${slot.name.lowercase()}")
            .fetchSemanticsNode().boundsInRoot
        val actual = compose.onNodeWithTag("workspace-browser-host").fetchSemanticsNode().boundsInRoot
        assertEquals("Physical left coordinate", expected.left, actual.left, 1f)
        assertEquals("Physical top coordinate", expected.top, actual.top, 1f)
        assertEquals("Measured width", expected.width, actual.width, 1f)
        assertEquals("Measured height", expected.height, actual.height, 1f)
    }

    @Test fun measuredMainSlotCreatesOnePositiveSizedBrowserAtItsPhysicalBounds() {
        mount()
        assertHostMatchesSlot(WorkspaceBrowserSlot.MAIN)
        compose.runOnIdle {
            assertEquals(1, created.size)
            assertEquals(true, currentPresented)
            assertEquals(true, currentCaptureReady)
            assertTrue(measuredSizes.isNotEmpty())
            assertTrue("Do not instantiate in a zero-size off-screen slot", measuredSizes.all { it.width > 0 && it.height > 0 })
        }
    }

    @Test fun rtlUsesMeasuredPhysicalCoordinatesWithoutMirroringTheHostOffset() {
        mount(LayoutDirection.Rtl)
        assertHostMatchesSlot(WorkspaceBrowserSlot.MAIN)
        compose.runOnIdle { selected = WorkspaceBrowserSlot.SIDEBAR }
        assertHostMatchesSlot(WorkspaceBrowserSlot.SIDEBAR)
        compose.runOnIdle { assertEquals(1, created.size); assertTrue(disposed.isEmpty()) }
    }

    @Test fun visibleMainToSidebarAndBackRetainsTheSameCompositionEvenWithKeepActiveOff() {
        mount()
        val original = currentToken
        compose.runOnIdle { selected = WorkspaceBrowserSlot.SIDEBAR }
        assertHostMatchesSlot(WorkspaceBrowserSlot.SIDEBAR)
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(true, currentPresented)
            selected = WorkspaceBrowserSlot.MAIN
        }
        assertHostMatchesSlot(WorkspaceBrowserSlot.MAIN)
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(1, created.size)
            assertTrue(disposed.isEmpty())
        }
    }

    @Test fun keepActiveOffDisposesWhenHiddenAndCreatesFreshContentOnReopen() {
        mount()
        val original = currentToken
        compose.runOnIdle { selected = null }
        compose.onNodeWithTag("workspace-browser-host").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(original), disposed); selected = WorkspaceBrowserSlot.MAIN }
        compose.onNodeWithTag("workspace-browser-host").assertExists()
        compose.runOnIdle {
            assertEquals(2, created.size)
            assertTrue(original !== currentToken)
        }
    }

    @Test fun keepActiveOnRetainsIdentityAndExactLastPositiveViewportThroughHideAndReveal() {
        keepActive = true
        mount()
        val original = currentToken
        compose.runOnIdle { mainWidth = 307.dp; mainHeight = 229.dp }
        compose.waitForIdle()
        val lastVisibleSize = measuredSizes.last()
        compose.runOnIdle { selected = null }
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(false, currentPresented)
            assertEquals(false, currentCaptureReady)
            assertEquals(lastVisibleSize, measuredSizes.last())
            assertTrue(disposed.isEmpty())
            selected = WorkspaceBrowserSlot.SIDEBAR
        }
        assertHostMatchesSlot(WorkspaceBrowserSlot.SIDEBAR)
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(1, created.size)
            assertEquals(true, currentPresented)
        }
    }

    @Test fun idleDisableWhileHiddenDisposesTheRetainedBrowser() {
        keepActive = true
        mount()
        compose.runOnIdle { selected = null }
        compose.runOnIdle { keepActive = false }
        compose.onNodeWithTag("workspace-browser-host").assertDoesNotExist()
        compose.runOnIdle { assertEquals(created, disposed) }
    }

    @Test fun stoppingKeepsOneHiddenCompositionUntilCancellationFinishes() {
        keepActive = true
        mount()
        val original = currentToken
        compose.runOnIdle { selected = null; stopping = true; keepActive = false }
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(false, currentPresented)
            assertTrue(disposed.isEmpty())
            stopping = false
        }
        compose.onNodeWithTag("workspace-browser-host").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(original), disposed) }
    }

    @Test fun openingWithoutAnyMeasuredSlotDoesNotMountAZeroSizeBrowser() {
        keepActive = true
        showReporters = false
        mount()
        compose.runOnIdle {
            assertTrue(created.isEmpty())
            assertTrue(measuredSizes.isEmpty())
            showReporters = true
        }
        compose.onNodeWithTag("workspace-browser-host").assertExists()
        compose.runOnIdle {
            assertEquals(1, created.size)
            assertTrue(measuredSizes.all { it.width > 0 && it.height > 0 })
        }
    }

    @Test fun aMissingDestinationMeasurementHidesButDoesNotRemountAnExistingBrowser() {
        mount()
        val original = currentToken
        val visibleSize = measuredSizes.last()
        compose.runOnIdle { showReporters = false; selected = WorkspaceBrowserSlot.SIDEBAR }
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(false, currentPresented)
            assertEquals(visibleSize, measuredSizes.last())
            assertEquals(1, created.size)
            assertTrue(disposed.isEmpty())
            showReporters = true
        }
        assertHostMatchesSlot(WorkspaceBrowserSlot.SIDEBAR)
        compose.runOnIdle { assertSame(original, currentToken); assertEquals(true, currentPresented) }
    }

    @Test fun hiddenRetainedBrowserHasNoToolbarSemanticsAndDoesNotInterceptWorkspaceTouch() {
        keepActive = true
        mount()
        compose.onNodeWithContentDescription("Browser toolbar action").assertExists()
        val root = compose.onNodeWithTag("test-workspace").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("workspace-browser-host").fetchSemanticsNode().boundsInRoot
        val target = Offset(host.center.x - root.left, host.center.y - root.top)
        compose.runOnIdle { selected = null }
        compose.onNodeWithContentDescription("Browser toolbar action").assertDoesNotExist()
        compose.onNodeWithTag("test-workspace").performTouchInput { click(target) }
        compose.runOnIdle {
            assertEquals("Hidden toolbar must never receive the tap", 0, browserClicks)
            assertEquals("The visible workspace must remain interactive", 1, workspaceClicks)
        }
    }

    @Test fun staleOwnerDisposalCannotRemoveTheNewSlotMeasurement() {
        val first = Any()
        val replacement = Any()
        val oldBounds = Rect(10f, 20f, 210f, 320f)
        val newBounds = Rect(30f, 40f, 330f, 440f)
        placement.select(WorkspaceBrowserSlot.MAIN)
        placement.claim(WorkspaceBrowserSlot.MAIN, first)
        placement.update(WorkspaceBrowserSlot.MAIN, first, oldBounds)
        placement.claim(WorkspaceBrowserSlot.MAIN, replacement)
        placement.update(WorkspaceBrowserSlot.MAIN, replacement, newBounds)
        placement.release(WorkspaceBrowserSlot.MAIN, first)
        assertEquals(newBounds, placement.bounds(WorkspaceBrowserSlot.MAIN))
        placement.release(WorkspaceBrowserSlot.MAIN, replacement)
        assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals("Disposal keeps the last nonzero fallback", newBounds, placement.lastGeometry?.viewport)
    }

    @Test fun zeroAndNegativeMeasurementsNeverReplaceTheLastPositiveDimensions() {
        val owner = Any()
        val positive = Rect(17f, 23f, 318f, 252f)
        placement.select(WorkspaceBrowserSlot.MAIN)
        placement.claim(WorkspaceBrowserSlot.MAIN, owner)
        placement.update(WorkspaceBrowserSlot.MAIN, owner, positive)
        placement.update(WorkspaceBrowserSlot.MAIN, owner, Rect(17f, 23f, 17f, 252f))
        assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals(positive, placement.lastGeometry?.viewport)
        placement.update(WorkspaceBrowserSlot.MAIN, owner, Rect(17f, 23f, 318f, 22f))
        assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals(positive, placement.lastGeometry?.viewport)
    }

    @Test fun staleMeasurementCannotReclaimAReplacedOrReleasedSlot() {
        val oldOwner = Any()
        val newOwner = Any()
        val oldBounds = Rect(0f, 0f, 200f, 300f)
        val newBounds = Rect(20f, 30f, 320f, 430f)
        placement.select(WorkspaceBrowserSlot.MAIN)
        placement.claim(WorkspaceBrowserSlot.MAIN, oldOwner)
        placement.update(WorkspaceBrowserSlot.MAIN, oldOwner, oldBounds)
        placement.claim(WorkspaceBrowserSlot.MAIN, newOwner)
        placement.update(WorkspaceBrowserSlot.MAIN, newOwner, newBounds)
        placement.update(WorkspaceBrowserSlot.MAIN, oldOwner, oldBounds)
        placement.release(WorkspaceBrowserSlot.MAIN, oldOwner)
        assertEquals(newBounds, placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals(newBounds, placement.lastGeometry?.viewport)
        placement.release(WorkspaceBrowserSlot.MAIN, newOwner)
        placement.update(WorkspaceBrowserSlot.MAIN, newOwner, oldBounds)
        assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals(newBounds, placement.lastGeometry?.viewport)
    }

    @Test fun measurementsFromAnUnselectedSlotCannotChangeTheRetainedViewport() {
        val main = Any()
        val sidebar = Any()
        val mainBounds = Rect(40f, 50f, 390f, 310f)
        val sidebarBounds = Rect(5f, 10f, 175f, 330f)
        placement.claim(WorkspaceBrowserSlot.MAIN, main)
        placement.claim(WorkspaceBrowserSlot.SIDEBAR, sidebar)
        placement.select(WorkspaceBrowserSlot.MAIN)
        placement.update(WorkspaceBrowserSlot.MAIN, main, mainBounds)
        placement.update(WorkspaceBrowserSlot.SIDEBAR, sidebar, sidebarBounds)
        assertEquals(mainBounds, placement.lastGeometry?.viewport)
        placement.select(null)
        placement.update(WorkspaceBrowserSlot.SIDEBAR, sidebar, Rect(8f, 11f, 178f, 331f))
        assertEquals("Hidden unselected layout callbacks cannot resize the browser", mainBounds, placement.lastGeometry?.viewport)
        placement.select(WorkspaceBrowserSlot.SIDEBAR)
        assertEquals(placement.geometry(WorkspaceBrowserSlot.SIDEBAR)?.viewport, placement.lastGeometry?.viewport)
    }

    @Test fun clippedSlotRetainsFullViewportDimensionsAndDefersScreenshotReadiness() {
        showReporters = false
        keepActive = true
        mount()
        val root = placement.rootBounds
        val owner = Any()
        val viewport = Rect(root.left + 20f, root.top + 30f, root.left + 320f, root.top + 270f)
        val clipped = Rect(viewport.left + 50f, viewport.top + 40f, viewport.right, viewport.bottom)
        compose.runOnIdle {
            placement.claim(WorkspaceBrowserSlot.MAIN, owner)
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport, clipped)
        }
        compose.runOnIdle {
            assertEquals(IntSize(300, 240), measuredSizes.last())
            assertEquals(viewport, measuredBounds.last())
            assertEquals(true, currentPresented)
            assertEquals("A partial viewport is not screenshot ready", false, currentCaptureReady)
            assertEquals(viewport, placement.lastGeometry?.viewport)
            assertEquals(viewport, placement.lastGeometry?.visible)
            selected = null
        }
        compose.runOnIdle {
            assertEquals(IntSize(300, 240), measuredSizes.last())
            assertEquals(false, currentPresented)
            assertEquals(false, currentCaptureReady)
            selected = WorkspaceBrowserSlot.MAIN
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport)
        }
        compose.runOnIdle {
            assertEquals("Revealing for a capture preserves the browser composition", 1, created.size)
            assertTrue(disposed.isEmpty())
            assertEquals(IntSize(300, 240), measuredSizes.last())
            assertEquals(true, currentPresented)
            assertEquals("The full measured viewport is capture ready", true, currentCaptureReady)
        }
    }

    @Test fun fullyClippedSlotHidesTheSamePositiveSizedBrowserUntilVisibleAgain() {
        showReporters = false
        keepActive = true
        mount()
        val root = placement.rootBounds
        val owner = Any()
        val viewport = Rect(root.left + 20f, root.top + 30f, root.left + 320f, root.top + 270f)
        compose.runOnIdle {
            placement.claim(WorkspaceBrowserSlot.MAIN, owner)
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport)
        }
        compose.runOnIdle {
            assertEquals(true, currentCaptureReady)
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport, Rect(viewport.left, viewport.top, viewport.left, viewport.top))
        }
        compose.runOnIdle {
            assertEquals(IntSize(300, 240), measuredSizes.last())
            assertEquals(false, currentPresented)
            assertEquals(false, currentCaptureReady)
            assertEquals(1, created.size)
            assertTrue(disposed.isEmpty())
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport)
        }
        compose.runOnIdle {
            assertEquals(true, currentPresented)
            assertEquals(true, currentCaptureReady)
            assertEquals(1, created.size)
            assertTrue(disposed.isEmpty())
            assertTrue(measuredSizes.all { it.width > 0 && it.height > 0 })
        }
    }

    @Test fun knownFullyClippedSlotDisposesTheExistingBrowserWhenKeepActiveIsOff() {
        showReporters = false
        keepActive = false
        mount()
        val root = placement.rootBounds
        val owner = Any()
        val viewport = Rect(root.left + 20f, root.top + 30f, root.left + 320f, root.top + 270f)
        compose.runOnIdle {
            placement.claim(WorkspaceBrowserSlot.MAIN, owner)
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport)
        }
        compose.waitForIdle()
        val original = currentToken
        compose.runOnIdle {
            assertEquals(1, created.size)
            assertEquals(true, currentPresented)
            assertEquals(true, currentCaptureReady)
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport,
                Rect(viewport.left, viewport.top, viewport.left, viewport.top))
        }
        compose.onNodeWithTag("workspace-browser-host").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("This remains the selected slot, with a known positive viewport", WorkspaceBrowserSlot.MAIN, selected)
            assertEquals(viewport, placement.geometry(WorkspaceBrowserSlot.MAIN)?.viewport)
            assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
            assertEquals("Default OFF disposes instead of retaining a fully clipped browser", listOf(original), disposed)
            assertEquals(1, created.size)
            assertEquals(viewport, placement.lastGeometry?.viewport)
        }
    }

    @Test fun stoppingPreservesTheFullyClippedBrowserUntilCleanupFinishes() {
        showReporters = false
        keepActive = true
        mount()
        val root = placement.rootBounds
        val owner = Any()
        val viewport = Rect(root.left + 20f, root.top + 30f, root.left + 320f, root.top + 270f)
        compose.runOnIdle {
            placement.claim(WorkspaceBrowserSlot.MAIN, owner)
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport)
        }
        compose.waitForIdle()
        val original = currentToken
        compose.runOnIdle {
            stopping = true
            keepActive = false
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport,
                Rect(viewport.left, viewport.top, viewport.left, viewport.top))
        }
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(1, created.size)
            assertTrue("Cleanup retains the existing browser until its stop completes", disposed.isEmpty())
            assertEquals(false, currentPresented)
            assertEquals(false, currentCaptureReady)
            assertEquals(IntSize(300, 240), measuredSizes.last())
            assertEquals(viewport, placement.geometry(WorkspaceBrowserSlot.MAIN)?.viewport)
            assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
            stopping = false
        }
        compose.onNodeWithTag("workspace-browser-host").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("Finishing cleanup disposes the same browser exactly once", listOf(original), disposed)
            assertEquals(1, created.size)
        }
    }

    @Test fun rtlClippingDoesNotRightAlignOrMoveTheFullBrowserViewport() {
        showReporters = false
        mount(LayoutDirection.Rtl)
        val root = placement.rootBounds
        val owner = Any()
        val viewport = Rect(root.left + 20f, root.top + 30f, root.left + 320f, root.top + 270f)
        val clipped = Rect(viewport.left + 50f, viewport.top, viewport.right, viewport.bottom)
        compose.runOnIdle {
            placement.claim(WorkspaceBrowserSlot.MAIN, owner)
            placement.update(WorkspaceBrowserSlot.MAIN, owner, viewport, clipped)
        }
        compose.runOnIdle {
            assertEquals(IntSize(300, 240), measuredSizes.last())
            assertEquals(viewport, measuredBounds.last())
            assertEquals(false, currentCaptureReady)
        }
    }

    @Test fun shrinkingTheWorkspaceWhileHiddenDoesNotShrinkTheRetainedViewport() {
        keepActive = true
        mount()
        val original = currentToken
        val lastVisibleSize = measuredSizes.last()
        compose.runOnIdle { selected = null; workspaceWidth = 190.dp; workspaceHeight = 130.dp }
        compose.runOnIdle {
            assertSame(original, currentToken)
            assertEquals(lastVisibleSize, measuredSizes.last())
            assertEquals(false, currentPresented)
            assertEquals(false, currentCaptureReady)
            assertEquals(1, created.size)
        }
    }

    @Test fun nonFiniteGeometryIsNeverPresentedOrRetained() {
        val owner = Any()
        val positive = Rect(10f, 20f, 310f, 260f)
        placement.select(WorkspaceBrowserSlot.MAIN)
        placement.claim(WorkspaceBrowserSlot.MAIN, owner)
        placement.update(WorkspaceBrowserSlot.MAIN, owner, positive)
        placement.update(WorkspaceBrowserSlot.MAIN, owner, Rect(Float.NaN, 20f, 310f, 260f))
        assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals(positive, placement.lastGeometry?.viewport)
        placement.update(WorkspaceBrowserSlot.MAIN, owner, Rect(10f, 20f, Float.POSITIVE_INFINITY, 260f))
        assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals(positive, placement.lastGeometry?.viewport)
        placement.update(WorkspaceBrowserSlot.MAIN, owner, positive, Rect(10f, Float.NaN, 310f, 260f))
        assertNull(placement.bounds(WorkspaceBrowserSlot.MAIN))
        assertEquals(positive, placement.lastGeometry?.viewport)
    }
}

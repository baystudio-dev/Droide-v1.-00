package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.ApplicationWorkAdmission
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

private class ExpandedTrackedWebView(context: Context, val page: String) : WebView(context) {
    var destroyed = 0
    var pauses = 0
    var stops = 0
    val navigations = mutableListOf<String>()
    val measuredHeights = mutableListOf<Int>()
    override fun getUrl() = page
    override fun getTitle() = "Expanded browser fixture"
    override fun loadUrl(url: String) { navigations += url }
    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) = Unit
    override fun stopLoading() { stops++; super.stopLoading() }
    override fun onPause() { pauses++; super.onPause() }
    override fun destroy() { destroyed++; super.destroy() }
    // Override the hidden runtime frame hook through the real Android View contract.
    fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        org.robolectric.shadow.api.Shadow.directlyOn(this, View::class.java, "setFrame",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, left),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, top),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, right),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, bottom))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        measuredHeights += height
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), height)
    }
}

/**
 * Authored regression coverage for native ownership and measured chrome geometry, without Chromium.
 * Static review only in R48: the Gradle dependency environment is blocked; these tests have not
 * been compiled or run. Device WebView, keyboard, accessibility and motion QA remain necessary.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp",
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
class BrowserExpandedPaneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("browser-expanded-pane-").toFile()
    private val controller = AgentBrowserController(root)
    private val views = mutableListOf<ExpandedTrackedWebView>()
    private var expanded by mutableStateOf(false)
    private var presented by mutableStateOf(true)
    private var width by mutableStateOf(360.dp)
    private var reviewRequests = 0
    private var urlChanges = 0
    private var creationAttempts = 0
    private var failCreation = false

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        root.resolve("index.html").writeText("<html><body>Ready</body></html>")
        controller.bindOpenRequest {}
    }

    @After fun cleanup() { root.deleteRecursively() }

    private fun fixture(context: Context): WebView {
        creationAttempts++
        if (failCreation) error("Controlled WebView failure")
        return ExpandedTrackedWebView(context, controller.htmlPreviewUrl("index.html")).also(views::add)
    }

    @Composable private fun Content() {
        DroideTheme {
            Box(Modifier.width(width).height(640.dp)) {
                WorkspaceBrowserPane("", { urlChanges++ }, controller, presented = presented,
                    expanded = expanded, onExpandedChange = { expanded = it },
                    onReviewAgentApproval = { reviewRequests++ }, createWebView = ::fixture)
            }
        }
    }

    private fun mount(showControls: Boolean = true) {
        compose.setContent { Content() }
        compose.waitForIdle()
        if (showControls && !expanded) {
            if (width < 296.dp) {
                openMenu()
                compose.onNodeWithTag("browser-menu-disclosure").performClick()
            } else compose.onNodeWithContentDescription("Show Keep active controls").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
    }
    private fun generation(): Long = ReflectionHelpers.getField(controller, "viewGeneration")
    private fun operationEpoch(): Long = ReflectionHelpers.getField(controller, "operationEpoch")
    private fun openMenu() { compose.onNodeWithContentDescription("Browser actions").performClick() }
    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test fun controlsStartCollapsedAndRevealingThemPreservesRetentionAndTheNativePage() {
        assertTrue(controller.setKeepActive(true))
        mount(showControls = false)
        val original = views.single()
        val initialHeight = original.height
        val initialGeneration = generation()
        compose.onNodeWithTag("browser-keep-active-controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show Keep active controls").assert(state("Collapsed")).performClick()
        compose.onNodeWithTag("browser-keep-active-controls").assertExists()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle {
            assertTrue(original.height < initialHeight)
            assertTrue(controller.retention.value.keepActive)
            assertSame(original, views.single())
            assertEquals(initialGeneration, generation())
            assertEquals(0, original.stops)
            assertEquals(0, original.destroyed)
            assertEquals(1, urlChanges)
        }
    }

    @Test fun repeatedModeChangesKeepOneNativeViewUrlDraftAndBothRetentionChoices() {
        mount()
        compose.onNodeWithTag("browser-url").performTextReplacement("https://draft.example/unsubmitted")
        val original = views.single()
        val originalGeneration = generation()
        val originalEpoch = operationEpoch()
        for (keep in listOf(false, true)) {
            compose.runOnIdle { assertTrue(controller.setKeepActive(keep)) }
            repeat(4) {
                compose.onNodeWithContentDescription("Expand browser").performClick()
                compose.onNodeWithContentDescription("Exit expanded browser").assertIsEnabled().performClick()
                compose.onNodeWithContentDescription("Collapse Keep active controls").performClick()
                compose.onNodeWithContentDescription("Show Keep active controls").performClick()
                compose.runOnIdle {
                    assertSame(original, views.single())
                    assertEquals(originalGeneration, generation())
                    assertEquals(originalEpoch, operationEpoch())
                    assertEquals(keep, controller.retention.value.keepActive)
                    assertTrue(controller.retention.value.presented)
                    assertEquals(0, original.destroyed)
                    assertEquals(0, original.pauses)
                    assertEquals(0, original.stops)
                    assertTrue(original.navigations.isEmpty())
                    assertEquals(1, urlChanges)
                }
                compose.onNodeWithTag("browser-url").assertTextEquals("https://draft.example/unsubmitted")
            }
        }
    }

    @Test fun expandedToolbarIsExactly50dpWith48dpTargetsAndNoExtraChromeSpace() {
        expanded = true
        mount()
        compose.onNodeWithTag("browser-toolbar").assertHeightIsEqualTo(50.dp)
        compose.onNodeWithTag("browser-url").assertHeightIsEqualTo(48.dp).assertWidthIsAtLeast(128.dp)
        compose.onNodeWithContentDescription("Browser actions").assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Exit expanded browser").assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(48.dp)
        compose.onNodeWithTag("browser-viewport").assertHeightIsEqualTo(590.dp)
        compose.onNodeWithTag("browser-keep-active-controls").assertDoesNotExist()
        val viewport = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertEquals(viewport.height.toInt(), views.single().height) }
    }

    @Test fun responsiveToolbarKeepsExpandVisibleAndMovesOnlyDisclosureIntoNarrowMenu() {
        width = 170.dp
        mount()
        for (narrowWidth in listOf(170.dp, 240.dp)) {
            compose.runOnIdle { width = narrowWidth }
            compose.onNodeWithTag("browser-toolbar").assertHeightIsEqualTo(60.dp)
            compose.onNodeWithTag("browser-url").assertWidthIsAtLeast(if (narrowWidth == 170.dp) 62.dp else 132.dp)
            val expand = compose.onNodeWithContentDescription("Expand browser")
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithContentDescription("Browser actions")
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
            assertEquals("Expand and menu share one row", menu.top, expand.top, 1f)
            compose.onNodeWithContentDescription("Collapse Keep active controls").assertDoesNotExist()
            openMenu()
            compose.onNodeWithTag("browser-menu-disclosure").assert(state("Expanded"))
                .assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("browser-keep-active-controls").assertDoesNotExist()
            openMenu()
            compose.onNodeWithText("Show Keep active controls").assert(state("Collapsed")).performClick()
            compose.onNodeWithTag("browser-keep-active-controls").assertExists()
            compose.onNodeWithContentDescription("Expand browser").performClick()
            compose.onNodeWithTag("browser-toolbar").assertHeightIsEqualTo(50.dp)
            compose.onNodeWithContentDescription("Exit expanded browser").performClick()
        }
        compose.runOnIdle { width = 295.dp }
        compose.onNodeWithContentDescription("Collapse Keep active controls").assertDoesNotExist()
        compose.runOnIdle { width = 296.dp }
        compose.onNodeWithTag("browser-toolbar").assertHeightIsEqualTo(60.dp)
        compose.onNodeWithTag("browser-url").assertWidthIsAtLeast(128.dp)
        compose.onNodeWithContentDescription("Expand browser").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Collapse Keep active controls").assertWidthIsEqualTo(48.dp).performClick()
        openMenu()
        compose.onNodeWithTag("browser-menu-disclosure").assertDoesNotExist()
        compose.onNodeWithText("Review Agent permission").performClick()
        compose.runOnIdle {
            assertEquals(1, views.size)
            assertFalse(controller.retention.value.keepActive)
        }
    }

    @Test fun foldingReclaimsActualNativeViewportHeightThroughIntermediateFrames() {
        mount()
        val before = views.single().height
        val disclosureHeight = compose.onNodeWithTag("browser-keep-active-controls").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Collapse Keep active controls").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        val during = views.single().height
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val after = views.single().height
        assertTrue("Layout height changes before the animation ends", during > before)
        assertTrue("The animation has a real intermediate layout", during < after)
        assertEquals("No blank space replaces the folded row", disclosureHeight, (after - before).toFloat(), 1f)
        compose.onNodeWithTag("browser-keep-active-controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show Keep active controls").assert(state("Collapsed"))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Expand browser").performClick()
        compose.onNodeWithContentDescription("Exit expanded browser").performClick()
        compose.onNodeWithContentDescription("Show Keep active controls").assertExists()
        compose.runOnIdle {
            assertEquals(after, views.single().height)
            assertFalse(controller.retention.value.keepActive)
            assertEquals(0, views.single().destroyed)
        }
    }

    @Test fun interruptedDisclosureAnimationsSettleWithoutReplacingOrStoppingTheBrowser() {
        mount()
        val original = views.single()
        val originalGeneration = generation()
        compose.mainClock.autoAdvance = false
        repeat(5) {
            compose.onNodeWithContentDescription("Collapse Keep active controls").performClick()
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithContentDescription("Show Keep active controls").performClick()
            compose.mainClock.advanceTimeBy(48)
        }
        compose.onNodeWithContentDescription("Collapse Keep active controls").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("browser-keep-active-controls").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(original, views.single())
            assertEquals(originalGeneration, generation())
            assertEquals(0, original.stops)
            assertEquals(0, original.destroyed)
            assertTrue(original.measuredHeights.all { it > 0 })
        }
    }

    @Test fun expandedMenuExposesNavigationRetentionReviewAndStatus() {
        expanded = true
        mount()
        openMenu()
        listOf("Back", "Reload", "Go").forEach { compose.onNodeWithText(it).assertIsEnabled() }
        compose.onNodeWithTag("browser-menu-keep-active").assert(state("Off"))
        compose.onNodeWithTag("browser-menu-status").assertTextEquals("Expanded browser fixture")
        compose.onNodeWithText("Review Agent permission").performClick()
        compose.runOnIdle { assertEquals(1, reviewRequests); assertFalse(controller.retention.value.keepActive) }
        openMenu()
        compose.onNodeWithTag("browser-menu-keep-active").performClick()
        compose.runOnIdle { assertTrue(controller.retention.value.keepActive) }
        compose.onNodeWithContentDescription("Exit expanded browser").performClick()
        compose.onNodeWithContentDescription("Show Keep active controls").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { assertEquals(1, views.size); assertEquals(0, views.single().stops) }
    }

    @Test fun hidingRetainedBrowserDismissesItsMenuWithoutReopeningItOnReveal() {
        expanded = true
        mount()
        compose.runOnIdle { assertTrue(controller.setKeepActive(true)) }
        openMenu()
        compose.onNodeWithText("Review Agent permission").assertExists()
        compose.runOnIdle { presented = false }
        compose.onNodeWithText("Review Agent permission").assertDoesNotExist()
        compose.onNodeWithTag("browser-menu-status").assertDoesNotExist()
        compose.runOnIdle { presented = true }
        compose.onNodeWithText("Review Agent permission").assertDoesNotExist()
        openMenu()
        compose.onNodeWithText("Review Agent permission").performClick()
        compose.runOnIdle {
            assertEquals(1, reviewRequests)
            assertTrue(controller.retention.value.keepActive)
            assertEquals(1, views.size)
            assertEquals(0, views.single().destroyed)
        }
    }

    @Test fun queuedWorkAndStopConfirmationSurviveExpandedModeChangesUntilExplicitDecision() {
        mount()
        compose.onNode(isToggleable()).performClick()
        val ticket = controller.registerToolWork()
        val epoch = operationEpoch()
        try {
            compose.onNodeWithContentDescription("Collapse Keep active controls").performClick()
            compose.onNodeWithContentDescription("Expand browser").performClick()
            openMenu()
            compose.onNodeWithTag("browser-menu-keep-active").performClick()
            compose.onNodeWithText("Stop browser work?").assertExists()
            repeat(3) {
                compose.runOnIdle { expanded = false }
                compose.onNodeWithText("Stop browser work?").assertExists()
                compose.runOnIdle { expanded = true }
                compose.onNodeWithText("Stop browser work?").assertExists()
            }
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle {
                assertFalse(ticket.invalidated.isCompleted)
                assertEquals(epoch, operationEpoch())
                assertTrue(controller.retention.value.keepActive)
                assertEquals(1, controller.retention.value.activeOperations)
                assertEquals(1, views.size)
                assertEquals(0, views.single().stops)
            }
        } finally { controller.releaseToolWork(ticket) }
    }

    @Test fun websitePromptDraftAndSingleResponderSurviveChromeModeChanges() {
        mount()
        compose.mainClock.autoAdvance = true
        val result = BrowserDialogTestResults.prompt()
        compose.runOnIdle {
            val view = views.single()
            assertTrue(view.webChromeClient!!.onJsPrompt(view, view.page, "Choose a title", "Saved draft", result))
        }
        val id = requireNotNull(controller.pendingDialog.value).id
        compose.mainClock.advanceTimeBy(400)
        settlePlatformWindows()
        repeat(3) {
            compose.runOnUiThread { expanded = true }
            compose.mainClock.advanceTimeBy(400)
            settlePlatformWindows()
            compose.onNodeWithText("Saved draft").assertExists()
            compose.runOnUiThread { expanded = false }
            compose.mainClock.advanceTimeBy(400)
            settlePlatformWindows()
            compose.onNodeWithText("Saved draft").assertExists()
        }
        compose.runOnIdle {
            assertEquals(id, controller.pendingDialog.value?.id)
            assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
            assertEquals(0, BrowserDialogTestResults.state(result).cancellations)
            assertEquals(1, views.size)
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertNull(controller.pendingDialog.value)
            assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
        }
    }

    private fun settlePlatformWindows() {
        val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
        ReflectionHelpers.getField<List<Any>>(global, "mRoots").toList().forEach { root ->
            ReflectionHelpers.callInstanceMethod<Unit>(root, "handleAppVisibility",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(32, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Pump Compose after native traversal before querying the dialog's semantics roots.
        val environment = ReflectionHelpers.getField<Any>(compose, "environment")
        val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
        ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
    }

    @Test fun expandedExitAndPermissionReviewRemainAvailableAfterCreationFailure() {
        expanded = true
        failCreation = true
        mount()
        compose.onNodeWithContentDescription("Exit expanded browser").assertIsEnabled()
        openMenu()
        compose.onNodeWithText("Go").assertIsNotEnabled()
        compose.onNodeWithTag("browser-menu-status").assertTextContains("Controlled WebView failure", substring = true)
        compose.onNodeWithText("Review Agent permission").performClick()
        compose.onNodeWithContentDescription("Exit expanded browser").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Expand browser").assertExists()
        compose.onNodeWithText("Controlled WebView failure").assertExists()
        compose.runOnIdle { assertEquals(1, creationAttempts); assertEquals(1, reviewRequests) }
    }

    @Test fun savedDisclosureAndUrlDraftRestoreWithoutChangingKeepActiveChoice() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }
        compose.onNodeWithTag("browser-url").performTextReplacement("https://draft.example/restored")
        compose.onNodeWithContentDescription("Show Keep active controls").performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("browser-keep-active-controls").assertExists()
        compose.onNodeWithContentDescription("Collapse Keep active controls").performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Show Keep active controls").assert(state("Collapsed"))
        compose.onNodeWithTag("browser-keep-active-controls").assertDoesNotExist()
        compose.onNodeWithTag("browser-url").assertTextEquals("https://draft.example/restored")
        compose.runOnIdle { assertFalse(controller.retention.value.keepActive); assertEquals(1, urlChanges) }
    }
}

/** Authored only; confirms the production transition consumes the system duration scale. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp")
class BrowserExpandedReducedMotionTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })
    private val root = Files.createTempDirectory("browser-expanded-reduced-motion-").toFile()
    private val controller = AgentBrowserController(root)
    private val views = mutableListOf<ExpandedTrackedWebView>()

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun zeroSystemDurationScaleFinishesDisclosureOnTheNextFrame() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        controller.bindOpenRequest {}
        compose.setContent {
            DroideTheme {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    WorkspaceBrowserPane("", {}, controller, createWebView = { context ->
                        ExpandedTrackedWebView(context, "about:blank").also(views::add)
                    })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show Keep active controls")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Collapse Keep active controls").assertExists()
        val before = views.single().height
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Collapse Keep active controls").performClick()
        // One frame applies the new target and one frame settles the zero-duration transition.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("browser-keep-active-controls").assertDoesNotExist()
        assertTrue(views.single().height > before)
        assertEquals(0, views.single().destroyed)
        assertFalse(controller.retention.value.keepActive)
    }
}

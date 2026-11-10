package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.webkit.JsResult
import android.webkit.WebView
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso.pressBack
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.baystudio.droide.core.AgentBrowserController
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp",
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserHumanDialogTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("browser-human-dialog-").toFile()
    private val browser = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var view: DialogView
    private var browserWindowRoot: View? = null

    private fun registeredOwners(): Set<Any> {
        val environment = ReflectionHelpers.getField<Any>(compose, "environment")
        val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
        return ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
    }

    // A bounded twenty-frame diagnostic confirmed one actual owner pass drains that work
    // permanently.
    private fun traverseNativeDialogWindow() {
        assertTrue("Dialog traversal keeps automatic Compose idling enabled", compose.mainClock.autoAdvance)
        var idleAfterTraversal = false
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val mainWindow = requireNotNull(browserWindowRoot)
                for (owner in registeredOwners()) {
                    val ownerView = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (ownerView.rootView !== mainWindow && ownerView.isAttachedToWindow && ownerView.isShown &&
                        ownerView.windowVisibility == View.VISIBLE &&
                        ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                        ReflectionHelpers.callInstanceMethod<Void>(ownerView, "measureAndLayoutForTest")
                    }
                }
                idleAfterTraversal = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
            }
        }
        assertTrue("Native dialog traversal and the normal automatic Compose clock must settle", idleAfterTraversal)
    }

    private class DialogView(context: Context, private val page: String) : WebView(context) {
        override fun getUrl() = page
    }

    @Before fun setup() {
        com.baystudio.droide.core.ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        com.baystudio.droide.core.ApplicationWorkAdmission.reopenFromUser()
        root.resolve("index.html").writeText("<html>Dialog fixture</html>")
        browser.bindOpenRequest {}
    }

    @After fun cleanup() {
        caller.cancel(); root.deleteRecursively()
    }

    private fun mount(visible: State<Boolean>? = null) {
        compose.setContent {
            DroideTheme {
                if (visible?.value != false) WorkspaceBrowserPane("", {}, browser) { context ->
                    DialogView(context, browser.htmlPreviewUrl("index.html")).also { view = it }
                } else Text("Browser closed")
            }
        }
        compose.onNodeWithContentDescription("Browser actions").performClick()
        compose.onNodeWithText("Go").assertExists()
        pressBack()
        compose.runOnIdle { browserWindowRoot = view.rootView }
    }

    private fun confirm(message: String): JsResult {
        val result = BrowserDialogTestResults.alert()
        compose.runOnIdle { assertTrue(view.webChromeClient!!.onJsConfirm(view, view.url!!, message, result)) }
        return result
    }

    @Test fun humanCanDismissWebsiteConfirmationAndNativeCallbackCompletesOnce() {
        mount()
        val result = confirm("Keep this draft?")
        compose.onNodeWithText("Website confirmation").assertExists()
        compose.onNodeWithText("Keep this draft?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Website confirmation").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
            assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
            assertNull(browser.pendingDialog.value)
        }
    }

    @Test fun humanDialogLabelsTheObservedFrameOriginAndDoesNotInventAnUnknownOrigin() {
        mount()
        val topOrigin = java.net.URI(view.url).let { uri ->
            "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
        }
        val frameResult = BrowserDialogTestResults.alert()
        compose.runOnIdle {
            assertTrue(view.webChromeClient!!.onJsConfirm(view, "https://frame.example/widget?secret=value", "Frame confirmation", frameResult))
        }
        compose.onNodeWithText("https://frame.example").assertIsDisplayed()
        compose.onNodeWithText(topOrigin).assertDoesNotExist()
        compose.onNodeWithText("Unknown website origin").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, BrowserDialogTestResults.state(frameResult).cancellations) }

        val unknownResult = BrowserDialogTestResults.alert()
        compose.runOnIdle {
            assertTrue(view.webChromeClient!!.onJsConfirm(view, "about:blank", "Unknown source confirmation", unknownResult))
        }
        compose.onNodeWithText("Unknown website origin").assertIsDisplayed()
        compose.onNodeWithText("https://frame.example").assertDoesNotExist()
        compose.onNodeWithText(topOrigin).assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle {
            assertEquals(1, BrowserDialogTestResults.state(unknownResult).confirmations)
            assertNull(browser.pendingDialog.value)
        }
    }

    @Test fun humanPromptAnswerReachesNativeResultAndIsNotRetainedAsAgentObservation() {
        mount()
        val result = BrowserDialogTestResults.prompt()
        compose.runOnIdle { assertTrue(view.webChromeClient!!.onJsPrompt(view, view.url!!, "Draft title", "Old title", result)) }
        traverseNativeDialogWindow()
        compose.onNodeWithText("Website prompt").assertExists()
        compose.onNodeWithText("Response").assertIsDisplayed()
        val response = compose.onNode(hasSetTextAction() and hasText("Response"))

        response.performSemanticsAction(SemanticsActions.RequestFocus)
        traverseNativeDialogWindow()
        response.assertIsFocused().performTextReplacement("Human draft title")
        traverseNativeDialogWindow()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Website prompt").assertDoesNotExist()
        compose.runOnIdle {
            val state = BrowserDialogTestResults.state(result)
            assertEquals(1, state.confirmations)
            assertEquals("Human draft title", state.promptText)
            assertNull(browser.pendingDialog.value)
        }
    }

    @Test fun agentResponseRemovesTheSameVisibleDialogWithoutDoubleHumanCompletion() {
        mount()
        val result = confirm("Continue preview?")
        compose.onNodeWithText("Website confirmation").assertExists()
        val id = browser.pendingDialog.value!!.id
        val operation = caller.async { browser.execute("dialog_respond", target = id, text = "accept", expectedCurrentUrl = view.url) }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!operation.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
        }
        assertTrue(operation.isCompleted)
        assertTrue(Json.parseToJsonElement(runBlocking { operation.await() }).jsonObject["success"]!!.jsonPrimitive.boolean)
        compose.onNodeWithText("Website confirmation").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(browser.respondHumanDialog(id, false))
            assertEquals(1, BrowserDialogTestResults.state(result).confirmations)
            assertEquals(0, BrowserDialogTestResults.state(result).cancellations)
        }
    }

    @Test fun closingBrowserWhileHumanDialogIsOpenCancelsItsOwnedNativeResult() {
        val visible = mutableStateOf(true)
        mount(visible)
        val result = confirm("Pending confirmation")
        compose.onNodeWithText("Website confirmation").assertExists()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Browser closed").assertExists()
        compose.onNodeWithText("Website confirmation").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(browser.pendingDialog.value)
            assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
        }
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeLargeDialogKeepsHumanDecisionButtonsVisible() {
        mount()
        val result = confirm("Long website message\n".repeat(1_000))
        compose.onNodeWithText("Website confirmation").assertExists()
        compose.onNodeWithText("OK").assertIsDisplayed().performClick()
        compose.onNodeWithText("Website confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, BrowserDialogTestResults.state(result).confirmations) }
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapePromptKeepsOneEditableLineAndPreservesTheWholeAnswer() {
        mount()
        val answer = "draft ".repeat(800)
        val result = BrowserDialogTestResults.prompt()
        compose.runOnIdle {
            assertTrue(view.webChromeClient!!.onJsPrompt(view, view.url!!, "Draft title", answer, result))
        }
        traverseNativeDialogWindow()
        compose.onNodeWithText("Website prompt").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasSetTextAction() and hasText("Response"))
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Prompt input must expose its actual measured text layout", layouts.isNotEmpty())
        assertEquals("The dialog owns vertical scrolling; its answer field remains one line", 1, layouts.single().lineCount)
        compose.onNodeWithText("OK").assertIsDisplayed().performClick()
        compose.onNodeWithText("Website prompt").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, BrowserDialogTestResults.state(result).confirmations)
            assertEquals(answer, BrowserDialogTestResults.state(result).promptText)
            assertNull(browser.pendingDialog.value)
        }
    }
}

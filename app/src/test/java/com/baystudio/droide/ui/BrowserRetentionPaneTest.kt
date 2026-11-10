package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.ApplicationWorkAdmission
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the native host and explicit user toggle without running Chromium. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BrowserDialogResultShadow::class, BrowserDialogPromptResultShadow::class])
class BrowserRetentionPaneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("browser-retention-pane-").toFile()
    private val controller = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val views = mutableListOf<TrackedView>()

    private class TrackedView(context: Context, val page: String) : WebView(context) {
        var destroyed = 0
        var pauses = 0
        val evaluations = mutableListOf<ValueCallback<String>>()
        override fun getUrl() = page
        override fun getTitle() = "Retained pane"
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.let { evaluations += it }
        }
        override fun onPause() { pauses++; super.onPause() }
        override fun destroy() { destroyed++; super.destroy() }

        fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean =
            org.robolectric.shadow.api.Shadow.directlyOn(this, View::class.java, "setFrame",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, left),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, top),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, right),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, bottom))
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
    }

    @After fun cleanup() { caller.cancel(); root.deleteRecursively() }

    private fun fixture(context: Context): WebView =
        TrackedView(context, controller.htmlPreviewUrl("index.html")).also { views += it }

    private fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        root.resolve("index.html").writeText("<html><body>Ready</body></html>")
        controller.bindOpenRequest {}
    }

    @Test fun explicitToggleDefaultsOffAndHiddenPaneKeepsSameMeasuredNativeView() {
        prepare()
        var presented by mutableStateOf(true)
        compose.setContent {
            DroideTheme {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    WorkspaceBrowserPane("", {}, controller, presented = presented, createWebView = ::fixture)
                }
            }
        }
        compose.onNodeWithContentDescription("Show Keep active controls").performClick()
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        var size = 0 to 0
        compose.runOnIdle {
            size = views.single().width to views.single().height
            assertTrue(size.first > 0 && size.second > 0)
            presented = false
        }
        compose.runOnIdle {
            val view = views.single()
            assertEquals(size, view.width to view.height)
            assertEquals(View.INVISIBLE, view.visibility)
            assertEquals(0, view.destroyed)
            assertEquals(0, view.pauses)
            assertTrue(controller.retention.value.keepActive)
            assertFalse(controller.retention.value.presented)
        }
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.runOnIdle { presented = true }
        compose.runOnIdle {
            assertEquals(1, views.size)
            assertEquals(View.VISIBLE, views.single().visibility)
            assertEquals(0, views.single().destroyed)
        }
    }

    @Test fun busyToggleOffOffersCancelAndExplicitStopWithoutCancellingCaller() {
        prepare()
        compose.setContent {
            DroideTheme {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    WorkspaceBrowserPane("", {}, controller, createWebView = ::fixture)
                }
            }
        }
        compose.onNodeWithContentDescription("Show Keep active controls").performClick()
        compose.onNode(isToggleable()).performClick()
        val read = caller.async { runCatching { controller.execute("read") } }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (views.single().evaluations.isEmpty() && System.nanoTime() < deadline) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (views.single().evaluations.isEmpty()) Thread.sleep(2)
        }
        assertEquals(1, views.single().evaluations.size)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Stop browser work?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        assertFalse(read.isCompleted)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Stop and turn off").performClick()
        compose.waitUntil(timeoutMillis = 2_000) { read.isCompleted }
        compose.onNode(isToggleable()).assertIsOff()
        assertTrue(caller.isActive)
        compose.runOnIdle { assertEquals(0, views.single().destroyed) }
    }

    @Test fun hiddenWebsitePromptStaysPendingAndReappearsOnlyAfterReveal() {
        prepare()
        var presented by mutableStateOf(false)
        var reviewRequests = 0
        compose.setContent {
            DroideTheme {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    WorkspaceBrowserPane("", {}, controller, presented = presented,
                        onReviewAgentApproval = { reviewRequests++ }, createWebView = ::fixture)
                }
            }
        }
        val result = BrowserDialogTestResults.prompt()
        compose.runOnIdle {
            controller.setKeepActive(true)
            val view = views.single()
            view.webChromeClient!!.onJsPrompt(view, view.page, "Choose a title", "Saved draft", result)
        }
        val id = requireNotNull(controller.pendingDialog.value).id
        compose.onNodeWithText("Website prompt").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, BrowserDialogTestResults.state(result).cancellations)
            assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
            presented = true
        }
        compose.onNodeWithText("Website prompt").assertExists()
        compose.onNodeWithText("Saved draft").assertExists()
        compose.onNodeWithText("Review Agent permission").performClick()
        compose.runOnIdle {
            assertEquals(1, reviewRequests)
            assertEquals(id, controller.pendingDialog.value?.id)
            assertEquals(0, BrowserDialogTestResults.state(result).cancellations)
            assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
        }
        compose.runOnIdle { presented = false }
        compose.onNodeWithText("Website prompt").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(id, controller.pendingDialog.value?.id)
            assertEquals(0, BrowserDialogTestResults.state(result).cancellations)
            assertEquals(0, BrowserDialogTestResults.state(result).confirmations)
            presented = true
        }
        compose.onNodeWithText("Saved draft").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertNull(controller.pendingDialog.value)
            assertEquals(1, BrowserDialogTestResults.state(result).cancellations)
        }
    }
}

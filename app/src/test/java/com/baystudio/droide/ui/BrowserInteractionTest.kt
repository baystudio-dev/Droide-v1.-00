package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebBackForwardList
import android.webkit.WebView
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso.pressBack
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.BrowserInitializationDiagnostics
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class BrowserInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val directory = Files.createTempDirectory("browser-ui-").toFile()
    private val controller = AgentBrowserController(directory)

    @Before fun openApplicationSession() {
        com.baystudio.droide.core.ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        com.baystudio.droide.core.ApplicationWorkAdmission.reopenFromUser()
    }

    private class TrackedWebView(context: Context, val failSave: Boolean = false) : WebView(context) {
        var destroyed = 0
        var saves = 0
        override fun destroy() { destroyed++; super.destroy() }
        override fun saveState(outState: Bundle): WebBackForwardList? {
            saves++
            if (failSave) throw IOException("Cannot save browser history")
            return super.saveState(outState)
        }
    }

    @After fun release() { directory.deleteRecursively() }

    @Test fun unavailableWebViewShowsErrorAndActualRetryCreatesWorkingSurface() {
        ShadowLog.clear()
        var attempts = 0
        compose.setContent {
            DroideTheme {
                WorkspaceBrowserPane("", {}, controller) { context ->
                    attempts++
                    if (attempts == 1) throw IllegalStateException("Android System WebView unavailable")
                    TrackedWebView(context)
                }
            }
        }
        compose.onNodeWithText("Android System WebView unavailable").assertExists()
        compose.runOnIdle {
            val lines = initializationLogs()
            assertEquals(2, lines.size)
            assertTrue(lines.first().contains("stage=create event=begin"))
            assertTrue(lines.last().contains("stage=create event=failed"))
            assertFalse("Failed construction must not query or initialize a provider for diagnostics", lines.any { it.contains("event=provider") })
        }
        compose.onNodeWithContentDescription("Browser actions").performClick()
        compose.onNodeWithText("Go").assertIsNotEnabled()
        pressBack()
        compose.onNodeWithText("Retry browser").performClick()
        compose.onNodeWithContentDescription("Browser actions").performClick()
        compose.onNodeWithText("Back").assertIsEnabled()
        pressBack()
        compose.onNodeWithText("Retry browser").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, attempts)
            val lines = initializationLogs()
            assertEquals(2, lines.count { it.contains("stage=create event=begin") })
            assertEquals(1, lines.count { it.contains("stage=mount event=complete") })
            assertEquals(1, lines.count { it.contains("event=provider") })
        }
    }

    private fun initializationLogs() = ShadowLog.getLogsForTag(BrowserInitializationDiagnostics.TAG).map { it.msg }

    @Test fun recompositionAndUrlEditingDoNotRepeatInitializationOrRecordPageInput() {
        ShadowLog.clear()
        var label by mutableStateOf("initial")
        var attempts = 0
        compose.setContent {
            DroideTheme {
                Text(label)
                WorkspaceBrowserPane("", {}, controller) { context -> attempts++; TrackedWebView(context) }
            }
        }
        compose.runOnIdle {
            assertEquals(1, attempts)
            assertEquals(9, initializationLogs().size)
            label = "changed"
        }
        compose.onNode(hasSetTextAction()).performTextInput("https://private.example/token=secret")
        compose.runOnIdle {
            assertEquals(1, attempts)
            val lines = initializationLogs()
            assertEquals(9, lines.size)
            assertFalse(lines.joinToString().contains("private.example"))
            assertFalse(lines.joinToString().contains("secret"))
        }
    }

    @Test fun rendererDeathIsAcknowledgedDestroyedAndRecoverableThroughRetry() {
        val views = mutableListOf<TrackedWebView>()
        compose.setContent {
            DroideTheme { WorkspaceBrowserPane("", {}, controller) { context -> TrackedWebView(context).also { views += it } } }
        }
        compose.runOnIdle {
            val view = views.single()
            val detail = object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            }
            assertTrue(view.webViewClient.onRenderProcessGone(view, detail))
        }
        compose.onNodeWithText("Browser renderer stopped unexpectedly. Retry to reopen the browser.").assertExists()
        compose.runOnIdle { assertEquals(1, views[0].destroyed); assertEquals(0, views[0].saves) }
        compose.onNodeWithText("Retry browser").performClick()
        compose.onNodeWithContentDescription("Browser actions").performClick()
        compose.onNodeWithText("Reload").assertIsEnabled()
        pressBack()
        compose.runOnIdle { assertEquals(2, views.size); assertEquals(1, views[0].destroyed) }
    }

    @Test fun failedHistorySaveStillDestroysWebViewWhenClosingBrowserPane() {
        var show by mutableStateOf(true)
        var view: TrackedWebView? = null
        compose.setContent {
            DroideTheme {
                if (show) WorkspaceBrowserPane("", {}, controller) { context -> TrackedWebView(context, failSave = true).also { view = it } }
                else Text("Browser closed")
            }
        }
        compose.onNodeWithContentDescription("Browser actions").performClick()
        compose.onNodeWithText("Back").assertExists()
        pressBack()
        compose.runOnIdle { show = false }
        compose.onNodeWithText("Browser closed").assertExists()
        compose.runOnIdle { assertEquals(1, view!!.saves); assertEquals(1, view!!.destroyed) }
    }
}

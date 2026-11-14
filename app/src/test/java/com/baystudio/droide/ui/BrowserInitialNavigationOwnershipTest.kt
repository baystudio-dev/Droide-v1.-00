package com.baystudio.droide.ui

import org.robolectric.RuntimeEnvironment

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Application
import android.content.Context
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentBrowserController
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Exercises the production host and pane with a controlled renderer.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserInitialNavigationOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("browser-first-navigation-").toFile()
    private val controller = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val placement = WorkspaceBrowserPlacement()
    private val views = mutableListOf<InitialView>()
    private var selected by mutableStateOf<WorkspaceBrowserSlot?>(null)

    private class InitialView(context: Context) : WebView(context) {
        var page: String? = null
        val navigations = mutableListOf<String>()
        override fun getUrl(): String? = page
        override fun getTitle() = "Initial fixture"
        override fun loadUrl(url: String) {
            navigations += url
            page = url
            webViewClient.onPageStarted(this, url, null)
            webViewClient.onPageFinished(this, url)
        }
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.onReceiveValue(JsonPrimitive(buildJsonObject {
                put("ok", true); put("url", page.orEmpty())
            }.toString()).toString())
        }
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        root.resolve("requested.html").writeText("<html><body>Requested</body></html>")
        root.resolve("default.html").writeText("<html><body>Default</body></html>")
        controller.bindOpenRequest { selected = WorkspaceBrowserSlot.MAIN }
    }

    @After fun cleanup() { caller.cancel(); root.deleteRecursively() }

    private fun mount() {
        compose.setContent {
            DroideTheme {
                Box(Modifier.size(360.dp, 640.dp).onGloballyPositioned { placement.updateRoot(it.boundsInRoot()) }) {
                    if (selected != null) Box(Modifier.fillMaxSize()) {
                        WorkspaceBrowserPlaceholder(placement, WorkspaceBrowserSlot.MAIN)
                    }
                    WorkspaceBrowserHost(placement, selected, keepActive = false, stopping = false) { presented, ready ->
                        WorkspaceBrowserPane(controller.htmlPreviewUrl("default.html"), {}, controller,
                            presented = presented, captureReady = ready,
                            createWebView = { InitialView(it).also(views::add) })
                    }
                }
            }
        }
    }

    private fun awaitNavigation(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
        }
        assertTrue("Browser navigation did not complete before the test deadline", condition())
    }

    @Test fun agentFirstOpenCannotBeOverwrittenByNonemptyPaneDefaultQueuedDuringMount() {
        mount()
        assertTrue(views.isEmpty())
        val requested = controller.htmlPreviewUrl("requested.html")
        val open = caller.async { controller.execute("open", url = requested, waitMs = 250) }
        awaitNavigation { open.isCompleted && controller.retention.value.activeOperations == 0 }
        compose.waitForIdle()
        assertTrue(runBlocking { open.await() }.contains(requested))
        assertEquals(1, views.size)
        assertEquals(listOf(requested), views.single().navigations)
        assertEquals(requested, controller.currentUrl())
    }

    @Test fun ordinaryHumanFirstMountStillLoadsItsDefaultExactlyOnce() {
        selected = WorkspaceBrowserSlot.MAIN
        mount()
        awaitNavigation {
            views.singleOrNull()?.navigations?.isNotEmpty() == true && controller.retention.value.activeOperations == 0
        }
        compose.waitForIdle()
        assertEquals(listOf(controller.htmlPreviewUrl("default.html")), views.single().navigations)
    }
}

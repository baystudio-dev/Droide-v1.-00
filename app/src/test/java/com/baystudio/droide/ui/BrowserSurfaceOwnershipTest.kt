package com.baystudio.droide.ui

import org.junit.Before

import org.robolectric.RuntimeEnvironment

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebBackForwardList
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentBrowserController
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp")
class BrowserSurfaceOwnershipTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("browser-ownership-").toFile()
    private val controller = AgentBrowserController(root)
    private val views = mutableListOf<TrackedWebView>()
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class TrackedWebView(context: Context, val region: String) : WebView(context) {
        var destroyed = 0
        var saved = 0
        var restored = 0
        var pauses = 0
        var resumes = 0
        override fun onPause() { pauses++; super.onPause() }
        override fun onResume() { resumes++; super.onResume() }
        override fun destroy() { destroyed++; super.destroy() }
        override fun saveState(outState: Bundle): WebBackForwardList? {
            saved++
            return super.saveState(outState)
        }
        override fun restoreState(inState: Bundle): WebBackForwardList? {
            restored++
            return super.restoreState(inState)
        }
    }

    private data class Surface(
        val width: Int,
        val height: Int,
        val chat: Boolean = false,
        val sidebar: Boolean = true,
        val sidebarTool: DroideRailTool = DroideRailTool.Web,
    )

    @After fun cleanup() { caller.cancel(); root.deleteRecursively() }

    @Composable
    private fun Browser(region: String) {
        Box(Modifier.width(240.dp).height(320.dp)) {
            WorkspaceBrowserPane("", {}, controller) { context ->
                TrackedWebView(context, region).also { views += it }
            }
        }
    }

    @Composable
    private fun SurfaceHost(surface: Surface) {
        val layout = droideWorkbenchLayout(surface.width.dp, surface.height.dp, 210.dp)
        val fit = droidePaneFit(layout, surface.width.dp, surface.sidebarTool, surface.sidebar, surface.chat, 336.dp)
        val renderedSidebar = (layout.expanded || surface.chat) && surface.sidebar && fit.sidebarWidth > 0.dp
        val sidebarOwnsBrowser = renderedSidebar && surface.sidebarTool == DroideRailTool.Web
        Row {
            if (renderedSidebar) {
                if (surface.sidebarTool == DroideRailTool.Web) Browser("sidebar") else Text("Files sidebar")
            }
            Column {
                if (layout.expanded) Text("Project editor") else FocusedWorkbenchBrowser(
                    sidebarOwnsBrowser = sidebarOwnsBrowser,
                    browser = { Browser("main") },
                    editor = { Text("Project editor") },
                )
            }
            if (surface.chat) Text("Agent chat")
        }
    }

    private fun assertSingleLiveBrowser(region: String) = compose.runOnIdle {
        val live = views.filter { it.destroyed == 0 }
        assertEquals(browserDiagnostics(), 1, live.size)
        assertEquals(region, live.single().region)
        val current = live.single()
        current.webViewClient.onPageStarted(current, "https://$region.example.test/", null)
        assertEquals("https://$region.example.test/", controller.currentUrl())
        assertTrue(browserDiagnostics(), views.all { it.destroyed in 0..1 })
    }

    private fun browserDiagnostics() = "Browser hosts: " + views.mapIndexed { index, view ->
        "#$index ${view.region} destroyed=${view.destroyed} saved=${view.saved} restored=${view.restored}"
    }.joinToString("; ")

    private fun executeBrowser(operation: String, expectedUrl: String? = null): JsonObject {
        val result = caller.async { controller.execute(operation, expectedCurrentUrl = expectedUrl) }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!result.isCompleted && System.nanoTime() < deadline) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (!result.isCompleted) Thread.sleep(2)
        }
        assertTrue("Visible browser operations must complete without a placement retry", result.isCompleted)
        return Json.parseToJsonElement(runBlocking { result.await() }).jsonObject
    }

    @Test fun compactMainBrowserIsNotSuppressedByAnUnrenderedSidebar() {
        compose.setContent { DroideTheme { SurfaceHost(Surface(360, 640)) } }
        assertSingleLiveBrowser("main")
        compose.onNodeWithText("Project editor").assertDoesNotExist()
    }

    @Test fun retainedPaneTracksHostStopAndStartWithoutDestroyingOrReloadingItsView() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { DroideTheme { Browser("main") } }
        }
        compose.runOnIdle {
            assertEquals(1, views.size)
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle {
            assertEquals(1, views.single().pauses)
            assertEquals(0, views.single().destroyed)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertEquals(1, views.single().resumes)
            assertEquals(1, views.size)
            assertEquals(0, views.single().destroyed)
        }
    }

    @Test fun mediumChatAndWebSidebarMountOneBrowserAndKeepTheEditorVisible() {
        compose.setContent { DroideTheme { SurfaceHost(Surface(820, 360, chat = true)) } }
        assertSingleLiveBrowser("sidebar")
        compose.onNodeWithText("Project editor").assertExists()
        compose.onNodeWithText("Agent chat").assertExists()
        compose.runOnIdle { assertEquals(1, views.size) }
    }

    @Test fun expandedWebSidebarKeepsOneBrowserBesideTheEditorAndChat() {
        compose.setContent { DroideTheme { SurfaceHost(Surface(1280, 800, chat = true)) } }
        assertSingleLiveBrowser("sidebar")
        compose.onNodeWithText("Project editor").assertExists()
        compose.onNodeWithText("Agent chat").assertExists()
        compose.runOnIdle { assertEquals(1, views.size) }
    }

    @Test fun browserOwnerTracksActualSidebarVisibilityThroughChatWidthAndToolChanges() {
        var surface by mutableStateOf(Surface(820, 360, chat = false))
        compose.setContent { DroideTheme { SurfaceHost(surface) } }
        assertSingleLiveBrowser("main")

        compose.runOnIdle { surface = surface.copy(chat = true) }
        assertSingleLiveBrowser("sidebar")
        compose.onNodeWithText("Project editor").assertExists()

        // This medium window cannot fit a Web sidebar alongside the editor and chat.
        compose.runOnIdle { surface = surface.copy(width = 640) }
        assertSingleLiveBrowser("main")
        compose.onNodeWithText("Project editor").assertDoesNotExist()

        compose.runOnIdle { surface = surface.copy(width = 820, sidebarTool = DroideRailTool.Files) }
        assertSingleLiveBrowser("main")
        compose.onNodeWithText("Files sidebar").assertExists()

        compose.runOnIdle { surface = surface.copy(sidebarTool = DroideRailTool.Web) }
        assertSingleLiveBrowser("sidebar")
        compose.runOnIdle { surface = surface.copy(sidebar = false) }
        assertSingleLiveBrowser("main")
        compose.runOnIdle {
            assertTrue(browserDiagnostics(), views.size >= 5)
            assertTrue(browserDiagnostics(), views.dropLast(1).all { it.destroyed == 1 && it.saved == 1 })
        }
    }

    @Test fun movingBrowserToTheSidebarTransfersActualBackForwardHistory() {
        var surface by mutableStateOf(Surface(820, 360, chat = false))
        compose.setContent { DroideTheme { SurfaceHost(surface) } }
        val first = "https://history.example.test/first"
        val second = "https://history.example.test/second"
        compose.runOnIdle {
            val initial = views.single()
            // Robolectric's public WebView shadow supplies the history entries. The production controller
            // must transfer them via real saveState/restoreState calls during the move.
            shadowOf(initial).pushEntryToHistory(first)
            shadowOf(initial).pushEntryToHistory(second)
            initial.loadUrl(second)
            initial.webViewClient.onPageStarted(initial, second, null)
            assertEquals(2, initial.copyBackForwardList().size)
        }

        compose.runOnIdle { surface = surface.copy(chat = true) }
        compose.runOnIdle {
            val initial = views.first()
            val replacement = views.single { it.destroyed == 0 }
            assertEquals(browserDiagnostics(), "sidebar", replacement.region)
            assertEquals(browserDiagnostics(), 1, initial.saved)
            assertEquals(browserDiagnostics(), 1, initial.destroyed)
            assertEquals(browserDiagnostics(), 1, replacement.restored)
            val history = replacement.copyBackForwardList()
            assertEquals(browserDiagnostics(), 2, history.size)
            assertEquals(first, history.getItemAtIndex(0).url)
            assertEquals(second, history.getItemAtIndex(1).url)
            assertEquals(1, history.currentIndex)
            assertEquals(second, replacement.url)
            assertEquals(second, controller.currentUrl())
        }
    }

    @Test fun visibleMainBrowserReadsDoNotRequestAPlacementMove() {
        root.resolve("index.html").writeText("<html><body>Ready</body></html>")
        var surface by mutableStateOf(Surface(820, 360, chat = true, sidebarTool = DroideRailTool.Files))
        var openRequests = 0
        controller.bindOpenRequest {
            openRequests++
            surface = surface.copy(sidebarTool = DroideRailTool.Web)
        }
        compose.setContent { DroideTheme { SurfaceHost(surface) } }
        val page = controller.htmlPreviewUrl("index.html")
        compose.runOnIdle {

            val decor = compose.activity.window.decorView
            val attachInfo = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
            ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
            decor.dispatchWindowVisibilityChanged(View.VISIBLE)
            val view = views.single()
            assertEquals("main", view.region)
            assertTrue("The regression requires a genuinely attached visible AndroidView", view.isAttachedToWindow && view.isShown)
            assertEquals(View.VISIBLE, view.windowVisibility)
            view.loadUrl(page)
            view.webViewClient.onPageStarted(view, page, null)
            view.webViewClient.onPageFinished(view, page)
            requireNotNull(view.webChromeClient).onConsoleMessage(ConsoleMessage("visible-page-log", page, 1, ConsoleMessage.MessageLevel.LOG))
        }

        val console = executeBrowser("console", expectedUrl = page)
        assertEquals("browser_console", console["kind"]!!.jsonPrimitive.content)
        assertTrue(console["lines"]!!.jsonArray.single().jsonPrimitive.content.contains("visible-page-log"))
        assertEquals("browser_status", executeBrowser("status", expectedUrl = page)["kind"]!!.jsonPrimitive.content)
        compose.runOnIdle {
            assertEquals("An existing visible browser must retain its placement", 0, openRequests)
            assertEquals(browserDiagnostics(), 1, views.size)
            assertEquals(browserDiagnostics(), 0, views.single().destroyed)
            assertEquals("main", views.single().region)
        }
        compose.onNodeWithText("Files sidebar").assertExists()
        compose.onNodeWithText("Project editor").assertDoesNotExist()
    }

    @Test fun cachedStatusDoesNotExposeOrRemountHiddenAndDetachedBrowser() {
        compose.setContent { DroideTheme { SurfaceHost(Surface(360, 640)) } }
        lateinit var view: TrackedWebView
        lateinit var parent: ViewGroup
        var openRequests = 0
        compose.runOnIdle {
            view = views.single()
            parent = view.parent as ViewGroup
            controller.bindOpenRequest {
                openRequests++
                if (view.parent == null) parent.addView(view)
                view.visibility = View.VISIBLE
            }
            view.visibility = View.INVISIBLE
            assertTrue(view.isAttachedToWindow)
            assertTrue(!view.isShown)
        }
        executeBrowser("status")
        compose.runOnIdle {
            assertEquals(0, openRequests)
            assertTrue(!view.isShown)
            parent.removeView(view)
            assertTrue(!view.isAttachedToWindow)
        }
        executeBrowser("status")
        compose.runOnIdle {
            assertEquals(0, openRequests)
            assertTrue(!view.isAttachedToWindow)
            assertEquals(browserDiagnostics(), 1, views.size)
            assertEquals(0, view.destroyed)
        }
    }

    @Test fun cachedStatusDoesNotCreateAMissingBrowserSurface() {
        var show by mutableStateOf(false)
        var openRequests = 0
        controller.bindOpenRequest { openRequests++; show = true }
        compose.setContent { DroideTheme { if (show) Browser("main") else Text("Browser not open") } }
        compose.onNodeWithText("Browser not open").assertExists()
        assertEquals("browser_status", executeBrowser("status")["kind"]!!.jsonPrimitive.content)
        compose.runOnIdle {
            assertEquals(0, openRequests)
            assertEquals(browserDiagnostics(), 0, views.size)
        }
        compose.onNodeWithText("Browser not open").assertExists()
    }
}

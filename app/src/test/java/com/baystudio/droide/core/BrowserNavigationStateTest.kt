package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.webkit.WebView
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserNavigationStateTest {
    private class Page(context: Context) : WebView(context) {
        var page = "https://example.com/start"
        var pageTitle = "Start"
        override fun getUrl() = page
        override fun getTitle() = pageTitle
    }

    @Test fun rendererNavigationTitlesAndHistoryUpdateTheStateObservedByTheAddressBar() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication()); ApplicationWorkAdmission.reopenFromUser()
        val root = Files.createTempDirectory("browser-state-").toFile()
        val controller = AgentBrowserController(root)
        val view = Page(RuntimeEnvironment.getApplication())
        try {
            controller.configure(view); controller.attach(view)
            assertEquals(view.page, controller.navigation.value.url)
            view.page = "https://example.com/form"
            view.webViewClient.onPageStarted(view, view.page, null)
            assertEquals(view.page, controller.navigation.value.url); assertTrue(controller.navigation.value.loading)
            view.pageTitle = "Form"; view.webViewClient.onPageFinished(view, view.page)
            assertEquals("Form", controller.navigation.value.title); assertFalse(controller.navigation.value.loading)
            view.webChromeClient!!.onReceivedTitle(view, "Updated form")
            assertEquals("Updated form", controller.navigation.value.title)
            view.page = "https://example.com/form#section"
            view.webViewClient.doUpdateVisitedHistory(view, view.page, false)
            assertEquals(view.page, controller.navigation.value.url)
            view.webViewClient.onPageFinished(view, "https://example.com/old")
            assertEquals(view.page, controller.navigation.value.url)
        } finally { view.destroy(); root.deleteRecursively() }
    }
}

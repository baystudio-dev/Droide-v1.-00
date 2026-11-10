package com.baystudio.droide.ui

import org.junit.Before

import org.robolectric.RuntimeEnvironment

import android.app.Application
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real tool dispatch must keep HTTP research independent of the interactive surface. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserResearchRoutingTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    private suspend fun fixture(
        respond: suspend (String) -> AgentWebResponse,
        block: suspend (AgentTools.Ctx, WebFixtureTransport) -> Unit,
    ) {
        val root = Files.createTempDirectory("browser-research-routing-").toFile()
        val transport = WebFixtureTransport(respond)
        val web = AgentWebResearch(root, transport, WebFixturePdf())
        val browser = AgentBrowserController(root)
        var surfaceRequests = 0
        val binding = browser.bindOpenRequest { surfaceRequests++; error("HTTP research must not mount a browser") }
        try {
            block(agentWorkflowContext(root, webResearch = web, browserController = browser), transport)
            assertEquals("No hidden browser work during text research", 0, surfaceRequests)
            assertEquals("", browser.currentUrl())
            assertFalse("No screenshots during text research", root.resolve(".droide/browser").exists())
        } finally {
            browser.unbindOpenRequest(binding)
            web.close()
            root.deleteRecursively()
        }
    }

    private suspend fun call(ctx: AgentTools.Ctx, name: String = "web", vararg fields: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(AgentTools.execute(name, buildJsonObject { fields.forEach { put(it.first, it.second) } }, ctx)).jsonObject

    @Test fun researchAndCachedContinuationNeverOpenTheBrowser() = runBlocking {
        fixture({ url ->
            if (url.contains("duckduckgo.com")) webHtml(url, webSearchHtml())
            else webHtml(url, "<main>" + (0..200).joinToString("") { "<p>Document line $it</p>" } + "</main>")
        }) { ctx, transport ->
            val search = call(ctx, fields = arrayOf("operation" to "search", "query" to "documentation"))
            val ref = search["results"]!!.jsonArray.first().jsonObject["ref_id"]!!.jsonPrimitive.content
            val page = call(ctx, fields = arrayOf("operation" to "open", "ref_id" to ref))
            val next = page["next_start_line"]!!.jsonPrimitive.int
            val continuation = Json.parseToJsonElement(AgentTools.execute("web", buildJsonObject {
                put("operation", "open"); put("ref_id", page.getValue("ref_id")); put("start_line", next)
            }, ctx)).jsonObject
            assertTrue(continuation["cached"]!!.jsonPrimitive.boolean)
            assertEquals(page["content_sha256"], continuation["content_sha256"])
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun blockedSearchReturnsOptionsWithoutLaunchingInteractiveFallbacks() = runBlocking {
        fixture({ url -> AgentWebResponse(403, byteArrayOf(), url) }) { ctx, transport ->
            val result = call(ctx, "websearch", "query" to "documentation")
            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(3, result["browser_fallbacks"]!!.jsonArray.size)
            assertTrue("Search attempts remain bounded", transport.requests.size in 3..6)
        }
    }

    @Test fun javascriptHintDoesNotAutomaticallyCreateARenderer() = runBlocking {
        fixture({ url -> webHtml(url, "<script>application()</script><p>Enable JavaScript</p>") }) { ctx, transport ->
            val page = call(ctx, "webfetch", "url" to "https://example.com/application")
            assertTrue(page["success"]!!.jsonPrimitive.boolean)
            assertTrue(page["javascript_likely"]!!.jsonPrimitive.boolean)
            assertEquals(1, transport.requests.size)
        }
    }
}

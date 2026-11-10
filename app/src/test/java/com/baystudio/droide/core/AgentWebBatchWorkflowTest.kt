package com.baystudio.droide.core

import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentWebBatchWorkflowTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun open(values: List<String>, field: String = "urls", start: Int = 0, count: Int = 80,
        refresh: Boolean = false) = buildJsonObject {
        put("operation", "open"); put(field, JsonArray(values.map(::JsonPrimitive)))
        put("start_line", start); put("num_lines", count); put("refresh", refresh)
    }
    private fun items(result: JsonObject) = result.getValue("results").jsonArray.map { it.jsonObject }
    private suspend fun fixture(transport: AgentWebTransport,
        block: suspend (java.io.File, AgentWebResearch, WebFixturePdf) -> Unit) {
        val root = Files.createTempDirectory("droide-web-batch-").toFile()
        val pdf = WebFixturePdf()
        val web = AgentWebResearch(root, transport, pdf)
        try { block(root, web, pdf) } finally { web.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun nativeBatchOverlapsThreeTransfersQueuesTheRestAndAssociatesDuplicatesInInputOrder() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val firstThree = CompletableDeferred<Unit>(); val slow = CompletableDeferred<Unit>()
        val otherItemsFinished = CompletableDeferred<Unit>()
        val transport = AgentWebTransport { url, authorize ->
            authorize(url); requests += url
            val running = active.incrementAndGet(); peak.accumulateAndGet(running, ::maxOf)
            if (running == 3) firstThree.complete(Unit)
            try {
                firstThree.await()
                if (url.endsWith("/0")) slow.await()
                webHtml(url, "<p>Evidence for $url</p>")
            } finally { active.decrementAndGet() }
        }
        fixture(transport) { root, web, _ ->
            val inputs = (0..6).map { "https://example.com/$it" } + "http://example.com/1#same-source"
            val progress = Collections.synchronizedList(mutableListOf<String>())
            val context = agentWorkflowContext(root, webResearch = web, onProgress = {
                it.detail.let { detail ->
                    progress += detail
                    if (detail == "Reading web sources · 7/8 complete") otherItemsFinished.complete(Unit)
                }
            })
            val pending = async { obj(AgentTools.execute("web", open(inputs), context)) }
            withTimeout(5_000) { firstThree.await(); otherItemsFinished.await() }
            assertFalse(pending.isCompleted)
            assertEquals(3, peak.get()); assertEquals(7, requests.size)
            slow.complete(Unit)
            val result = withTimeout(5_000) { pending.await() }
            assertEquals(JsonPrimitive(true), result["success"])
            val sources = items(result)
            assertEquals((0..7).toList(), sources.map { it.getValue("index").jsonPrimitive.int })
            assertEquals(inputs, sources.map { it.getValue("requested_url").jsonPrimitive.content })
            assertEquals(sources[1]["ref_id"], sources[7]["ref_id"])
            assertEquals(JsonPrimitive(1), sources[7]["duplicate_of"])
            assertEquals("Reading web sources · 8/8 complete", progress.last())
            assertEquals(7, progress.count { it.endsWith("complete") })
        }
    }

    @Test fun deniedInvalidHttpAndBinarySourcesKeepSuccessfulEvidenceWithoutImagesOrPdfWork() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val transport = AgentWebTransport { url, authorize ->
            authorize(url); requests += url
            when {
                url.endsWith("/broken") -> AgentWebResponse(503, byteArrayOf(), url)
                url.endsWith("/image") -> AgentWebResponse(200, webFixturePng, url, "image/png")
                url.endsWith("/pdf") -> AgentWebResponse(200, "%PDF-1.7 fixture".toByteArray(), url, "application/pdf")
                else -> webHtml(url, "<p>Useful surviving evidence</p>")
            }
        }
        fixture(transport) { root, web, pdf ->
            var attached = 0
            val permissions = PermissionEngine(listOf(PermRule("webfetch", "https://denied.example/*", PermEffect.DENY),
                PermRule("*", "*", PermEffect.ALLOW)))
            val context = agentWorkflowContext(root, permissions = permissions, webResearch = web, imageSink = { attached += it.size })
            val result = obj(AgentTools.execute("web", open(listOf("https://example.com/ok", "https://denied.example/source",
                "https://example.com/broken", "https://example.com/image", "https://example.com/pdf", "not a URL")), context))
            assertEquals(JsonPrimitive(false), result["success"])
            assertEquals(JsonPrimitive(true), result["partial_success"])
            assertEquals(JsonPrimitive(1), result["success_count"])
            val sources = items(result)
            assertTrue(sources[0].toString().contains("Useful surviving evidence"))
            assertNotNull(sources[0]["ref_id"])
            assertEquals(JsonPrimitive(true), sources[1]["permission_denied"])
            assertTrue(sources.drop(1).all { it["success"] == JsonPrimitive(false) && it["error"] != null })
            assertTrue(sources[3].getValue("error").jsonPrimitive.content.contains("individually", true))
            assertTrue(sources[4].getValue("error").jsonPrimitive.content.contains("individually", true))
            assertFalse(requests.any { it.contains("denied.example") })
            assertEquals(0, attached); assertTrue(pdf.pagesRead.isEmpty())
            assertFalse(root.resolve(".droide/web-cache").walkTopDown().any { it.isFile })
            assertFalse(java.io.File(ChatMediaStoragePaths.directory(root), "scratch/web").walkTopDown().any { it.isFile })
        }
    }

    @Test fun cachedBatchReferencesContinueOriginalSnapshotAndRefreshCreatesNewReferences() = runBlocking {
        val requests = AtomicInteger()
        fixture(AgentWebTransport { url, authorize ->
            authorize(url)
            val version = requests.incrementAndGet()
            webHtml(url, (0..30).joinToString("") { "<p>Version $version line $it</p>" })
        }) { root, web, _ ->
            val context = agentWorkflowContext(root, webResearch = web)
            val first = items(obj(AgentTools.execute("web", open(listOf("https://example.com/a", "https://example.com/b"), count = 2), context)))
            val refs = first.map { it.getValue("ref_id").jsonPrimitive.content }
            val later = items(obj(AgentTools.execute("web", open(refs, "ref_ids", start = 2, count = 2), context)))
            assertEquals(2, requests.get())
            later.forEachIndexed { index, item ->
                assertEquals(JsonPrimitive(true), item["cached"])
                assertEquals(first[index]["content_sha256"], item["content_sha256"])
                assertEquals(JsonPrimitive(2), item["start_line"])
            }
            val refreshed = items(obj(AgentTools.execute("web", open(refs, "ref_ids", count = 2, refresh = true), context)))
            assertEquals(4, requests.get())
            assertTrue(refreshed.zip(first).all { (fresh, original) -> fresh["ref_id"] != original["ref_id"] })
            val original = items(obj(AgentTools.execute("web", open(refs, "ref_ids", count = 2), context)))
            assertEquals(first.map { it["content_sha256"] }, original.map { it["content_sha256"] })
            assertEquals(4, requests.get())
        }
    }

    @Test fun parentCancellationCancelsActiveWorkersAndDoesNotStartQueuedSources() = runBlocking {
        val requests = AtomicInteger(); val cancelled = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        fixture(AgentWebTransport { url, authorize ->
            authorize(url)
            if (requests.incrementAndGet() == 3) started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }) { root, web, _ ->
            val pending = async { AgentTools.execute("web", open((0..7).map { "https://example.com/$it" }),
                agentWorkflowContext(root, webResearch = web)) }
            withTimeout(5_000) { started.await() }
            pending.cancel()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            pending.join()
            assertEquals(3, requests.get()); assertEquals(3, cancelled.get())
        }
    }

    @Test fun nativeSearchBatchOverlapsQueriesAndRetainsSuccessfulQueriesWhenAnotherIsDenied() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger(); val requests = AtomicInteger()
        val overlap = CompletableDeferred<Unit>()
        fixture(AgentWebTransport { url, authorize ->
            authorize(url); requests.incrementAndGet()
            val running = active.incrementAndGet(); peak.accumulateAndGet(running, ::maxOf)
            if (running == 2) overlap.complete(Unit)
            try { overlap.await(); webHtml(url, webSearchHtml()) } finally { active.decrementAndGet() }
        }) { root, web, _ ->
            val permissions = PermissionEngine(listOf(PermRule("websearch", "blocked", PermEffect.DENY), PermRule("*", "*", PermEffect.ALLOW)))
            val args = buildJsonObject {
                put("operation", "search"); put("engine", "duckduckgo")
                put("queries", JsonArray(listOf("first", "blocked", "third").map(::JsonPrimitive)))
            }
            val result = withTimeout(5_000) { obj(AgentTools.execute("web", args, agentWorkflowContext(root, permissions = permissions, webResearch = web))) }
            val searches = result.getValue("searches").jsonArray.map { it.jsonObject }
            assertEquals(2, peak.get()); assertEquals(2, requests.get())
            assertEquals(JsonPrimitive(true), result["partial_success"])
            assertEquals(listOf("first", "blocked", "third"), searches.map { it.getValue("requested_query").jsonPrimitive.content })
            assertEquals(JsonPrimitive(true), searches[1]["permission_denied"])
            assertNotNull(searches[0]["results"]); assertNotNull(searches[2]["results"])
        }
    }

    @Test fun invalidBatchSelectorsAndSizesFailBeforeAnyNetworkRequest() = runBlocking {
        val requests = AtomicInteger()
        fixture(AgentWebTransport { url, _ -> requests.incrementAndGet(); webHtml(url, "unexpected") }) { root, web, _ ->
            val context = agentWorkflowContext(root, webResearch = web)
            val valid = open(listOf("https://example.com/a"))
            val cases = listOf(
                JsonObject(valid + ("url" to JsonPrimitive("https://example.com/single"))),
                JsonObject(valid + ("ref_id" to JsonPrimitive("web_ref"))),
                JsonObject(valid + ("ref_ids" to JsonArray(listOf(JsonPrimitive("web_ref"))))),
                open(emptyList()), open((0..8).map { "https://example.com/$it" }),
                obj("""{"operation":"open","urls":"https://example.com"}"""),
                obj("""{"operation":"open","urls":[7]}"""),
                open(listOf("https://example.com"), count = 0),
            )
            cases.forEach { assertEquals(JsonPrimitive(false), obj(AgentTools.execute("web", it, context))["success"]) }
            assertEquals(0, requests.get())
        }
    }

    @Test fun nativeLargeBatchRetainsEveryReferenceAndAccurateContinuationWithinHistoryBudget() = runBlocking {
        fixture(AgentWebTransport { url, authorize ->
            authorize(url)
            AgentWebResponse(200, (0..500).joinToString("\n") { "line $it " + "\\\"\t".repeat(55) }.toByteArray(), url, "text/plain")
        }) { root, web, _ ->
            val context = agentWorkflowContext(root, webResearch = web)
            val raw = AgentTools.execute("web", open((0..7).map { "https://example.com/$it" }, count = 200), context)
            assertTrue(raw.length <= 24_000)
            val result = obj(raw)
            assertEquals(JsonPrimitive(true), result["output_truncated"])
            assertFalse(result.containsKey("preview"))
            val sources = items(result)
            assertEquals(8, sources.size)
            sources.forEachIndexed { index, item ->
                assertEquals(JsonPrimitive(index), item["index"])
                assertNotNull(item["ref_id"]); assertNotNull(item["url"])
                val count = item.getValue("lines").jsonArray.size
                assertEquals(JsonPrimitive(count), item["end_line"])
                assertEquals(JsonPrimitive(count), item["next_start_line"])
            }
            val first = sources.first()
            val continued = obj(AgentTools.execute("web", buildJsonObject {
                put("operation", "open"); put("ref_id", first.getValue("ref_id")); put("start_line", first.getValue("next_start_line")); put("num_lines", 1)
            }, context))
            assertEquals(first["next_start_line"], continued["start_line"])
            val annotated = AgentToolResultSemantics.classify("web", raw).annotate("web", raw)
            assertEquals(result, obj(AgentToolResultSemantics.stripAnnotation(AgentWebTools.forAgentHistory("web", annotated))))
        }
    }

    @Test fun oversizedRedirectUrlsAreExplicitlyOmittedWhileAllReferencesAndZeroLineOffsetsSurvive() = runBlocking {
        fixture(AgentWebTransport { url, authorize ->
            authorize(url)
            webHtml("https://example.com/redirect?" + "x".repeat(7_000), "<p>Read with the reference</p>")
        }) { root, web, _ ->
            val raw = AgentTools.execute("web", open((0..7).map { "https://example.com/$it" }), agentWorkflowContext(root, webResearch = web))
            assertTrue(raw.length <= 24_000)
            items(obj(raw)).forEach {
                assertNotNull(it["ref_id"]); assertNotNull(it["index"])
                assertEquals(JsonPrimitive(true), it["url_omitted"]); assertNull(it["url"])
                assertEquals(JsonPrimitive(0), it["next_start_line"])
                assertEquals(JsonPrimitive(0), it["end_line"])
            }
        }
    }

    @Test fun maximumLengthRequestedUrlsAndEscapedInvalidInputsKeepEveryOutcomeInsideTheJsonBudget() = runBlocking {
        val requests = AtomicInteger()
        fixture(AgentWebTransport { url, authorize ->
            authorize(url); requests.incrementAndGet(); webHtml(url, "<p>Valid long source</p>")
        }) { root, web, _ ->
            val inputs = (0..3).map { index ->
                val prefix = "https://example.com/$index?value="
                prefix + "x".repeat(8_192 - prefix.length)
            } + (0..3).map { "$it" + "\u0001".repeat(8_191) }
            val raw = AgentTools.execute("web", open(inputs), agentWorkflowContext(root, webResearch = web))
            assertTrue(raw.length <= 24_000)
            assertEquals(4, requests.get())
            val result = obj(raw)
            assertEquals(JsonPrimitive(true), result["partial_success"])
            val sources = items(result)
            assertEquals(8, sources.size)
            sources.forEachIndexed { index, item ->
                assertEquals(JsonPrimitive(index), item["index"])
                assertEquals(JsonPrimitive(true), item["requested_url_omitted"])
                assertFalse(item.containsKey("requested_url"))
                if (index < 4) {
                    assertNotNull(item["ref_id"])
                    assertEquals(JsonPrimitive(true), item["url_omitted"])
                } else {
                    assertEquals(JsonPrimitive(false), item["success"])
                    assertNotNull(item["error"])
                }
            }
        }
    }
}

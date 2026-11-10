package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentWebConcurrencyTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private val owner = "concurrent-session"
    private val allow: suspend (String, String) -> Unit = { _, _ -> }

    /** The timeout only detects a deadlock; concurrency assertions use explicit barriers. */
    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000, block)
    }

    private suspend fun withServices(
        transport: AgentWebTransport,
        count: Int = 1,
        pdfFactory: () -> AgentWebPdfBackend = { WebFixturePdf() },
        block: suspend CoroutineScope.(List<AgentWebResearch>) -> Unit,
    ) = coroutineScope {
        val root = Files.createTempDirectory("droide-web-concurrency-").toFile()
        val services = List(count) { AgentWebResearch(root, transport, pdfFactory()) }
        try { block(services) } finally {
            services.forEach(AgentWebResearch::close)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private suspend fun AgentWebResearch.openTextUrl(
        url: String,
        authorize: suspend (String, String) -> Unit = allow,
    ) = openText(owner, url, null, 0, 20, false, authorize)

    private fun response(url: String): AgentWebResponse = webHtml(url,
        if (url.contains("duckduckgo.com")) webSearchHtml()
        else "<title>Concurrent source</title><p>Evidence for $url</p>")

    private class ConcurrentTransport(
        private val handler: suspend (String) -> AgentWebResponse,
    ) : AgentWebTransport {
        val requests = ConcurrentLinkedQueue<String>()
        val entered = Channel<String>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        override suspend fun get(url: String, beforeRequest: suspend (String) -> Unit): AgentWebResponse {
            beforeRequest(url)
            requests.add(url)
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            check(entered.trySend(url).isSuccess)
            try { return handler(url) } finally { active.decrementAndGet() }
        }
    }

    @Test fun distinctSearchesAndTextOpensOverlapWithinOneGlobalTransferLimit() = bounded {
        val release = CompletableDeferred<Unit>()
        val transport = ConcurrentTransport { url -> release.await(); response(url) }
        withServices(transport, count = 3) { services ->
            val jobs = (0 until 9).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    val service = services[index % services.size]
                    if (index == 0) service.open(owner, "https://example.com/source-$index", null, 0, 20, false, allow, null)
                    else if (index % 2 == 0) service.openTextUrl("https://example.com/source-$index")
                    else service.search(owner, AgentWebSearch.Request("query-$index", engine = "duckduckgo"), allow, {})
                }
            }
            try {
                val first = List(3) { transport.entered.receive() }
                assertEquals(3, first.distinct().size)
                assertTrue(first.any { it.contains("duckduckgo.com") })
                assertTrue(first.any { it.contains("example.com") })
                assertEquals(3, transport.requests.size)
                assertEquals(3, transport.active.get())
                assertTrue(jobs.none { it.isCompleted })
            } finally { release.complete(Unit) }
            val results = jobs.awaitAll()
            assertEquals(9, transport.requests.size)
            assertEquals(9, transport.requests.distinct().size)
            assertEquals(3, transport.maximum.get())
            assertEquals(5, results.count { it["kind"] == JsonPrimitive("page") })
            assertEquals(4, results.count { it["kind"] == JsonPrimitive("search") })
            assertTrue(results.all { it["success"] == JsonPrimitive(true) })
        }
    }

    @Test fun cachedOpenFindAndLinksCompleteWhileEveryTransferSlotIsBlocked() = bounded {
        val release = CompletableDeferred<Unit>()
        val transport = ConcurrentTransport { url ->
            if (url.endsWith("/cached")) webHtml(url,
                "<p>cached needle evidence</p><a href='/related'>Related source</a>")
            else { release.await(); response(url) }
        }
        withServices(transport, count = 2) { services ->
            val service = services.first()
            val original = service.openTextUrl("https://example.com/cached")
            val id = original.getValue("ref_id").jsonPrimitive.content
            transport.entered.receive()
            val blocked = (0 until 3).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
                services[index % services.size].openTextUrl("https://example.com/blocked-$index")
            } }
            try {
                repeat(3) { transport.entered.receive() }
                val reopened = service.open(owner, null, id, 0, 20, false, allow, null)
                val found = service.find(owner, id, "needle", 0, 10, false, allow)
                val links = service.links(owner, id, 0, 10, allow)
                assertEquals(JsonPrimitive(true), reopened["cached"])
                assertEquals(original["content_sha256"], reopened["content_sha256"])
                assertEquals(1, found.getValue("matches").jsonArray.size)
                assertEquals(1, links.getValue("links").jsonArray.size)
                assertEquals(4, transport.requests.size)
                assertEquals(3, transport.active.get())
                assertTrue(blocked.none { it.isCompleted })
            } finally { release.complete(Unit) }
            blocked.awaitAll()
        }
    }

    @Test fun concurrentOperationsSerializePermissionCallbacksBeforeNetworkAccess() = bounded {
        val calls = Channel<Pair<String, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val approvals = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        val authorize: suspend (String, String) -> Unit = { action, target ->
            assertEquals("webfetch", action)
            val release = CompletableDeferred<Unit>().also(approvals::add)
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            try { calls.send(target to release); release.await() } finally { active.decrementAndGet() }
        }
        val transport = ConcurrentTransport(::response)
        withServices(transport) { services ->
            val jobs = (0 until 3).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
                services.single().openTextUrl("https://example.com/permission-$index", authorize)
            } }
            try {
                val seen = mutableSetOf<String>()
                repeat(3) { index ->
                    val (target, approve) = calls.receive()
                    assertTrue(seen.add(target))
                    assertEquals(index, transport.requests.size)
                    assertEquals(1, active.get())
                    assertTrue(calls.tryReceive().isFailure)
                    approve.complete(Unit)
                }
                jobs.awaitAll()
                assertEquals(1, maximum.get())
                assertEquals(3, transport.requests.size)
            } finally { approvals.forEach { it.complete(Unit) } }
        }
    }

    @Test fun closeCancelsActiveAndQueuedOperationsAndLateResponsesCannotRepopulateTheStore() = bounded {
        val release = CompletableDeferred<Unit>()
        // Simulate a transport returning a response after its caller has been cancelled.
        val transport = ConcurrentTransport { url ->
            withContext(NonCancellable) { release.await() }
            response(url)
        }
        withServices(transport) { services ->
            val service = services.single()
            val jobs = (0 until 6).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
                if (index % 2 == 0) service.openTextUrl("https://example.com/closing-$index")
                else service.search(owner, AgentWebSearch.Request("closing-$index", engine = "duckduckgo"), allow, {})
            } }
            try {
                repeat(3) { transport.entered.receive() }
                assertEquals(3, transport.requests.size)
                service.close()
                assertEquals(JsonPrimitive(0), service.status(owner)["references"])
            } finally { release.complete(Unit) }
            jobs.forEach { assertTrue(runCatching { it.await() }.exceptionOrNull() is CancellationException) }
            assertEquals(3, transport.requests.size)
            assertEquals(0, transport.active.get())
            assertEquals(JsonPrimitive(0), service.status(owner)["references"])
            assertEquals(JsonPrimitive(0), service.status(owner)["cached_documents"])
            val failure = runCatching { service.openTextUrl("https://example.com/after-close") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("closed"))
            assertEquals(3, transport.requests.size)
        }
    }

    @Test fun cancellingQueuedAndActiveRequestsReleasesCapacityForAnotherService() = bounded {
        val release = CompletableDeferred<Unit>()
        val transport = ConcurrentTransport { url ->
            if (!url.endsWith("/replacement")) release.await()
            response(url)
        }
        withServices(transport, count = 2) { services ->
            val active = (0 until 3).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
                services.first().openTextUrl("https://example.com/active-$index")
            } }
            val cancelledUrl = "https://example.com/cancelled-before-admission"
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { services.last().openTextUrl(cancelledUrl) }
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                services.last().openTextUrl("https://example.com/replacement")
            }
            try {
                repeat(3) { transport.entered.receive() }
                assertEquals(3, transport.requests.size)
                cancelled.cancelAndJoin()
                active.first().cancelAndJoin()
                assertEquals("https://example.com/replacement", transport.entered.receive())
                assertEquals(JsonPrimitive(true), replacement.await()["success"])
                assertFalse(transport.requests.contains(cancelledUrl))
                assertEquals(3, transport.maximum.get())
            } finally { release.complete(Unit) }
            active.drop(1).awaitAll()
            assertTrue(active.first().isCancelled)
            assertTrue(cancelled.isCancelled)
        }
    }

    @Test fun parserIsSerializedAndWaitingBodiesContinueToOccupyTransferSlots() = bounded {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val secondEntered = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            AgentWebWork.parse { entered.complete(Unit); release.await(); "first" }
        }
        val transport = ConcurrentTransport(::response)
        try {
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                AgentWebWork.parse { secondEntered.complete(Unit); "second" }
            }
            withServices(transport, count = 2) { services ->
                val jobs = (0 until 6).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
                    if (index % 2 == 0) services.first().openTextUrl("https://example.com/parsing-$index")
                    else services.last().search(owner, AgentWebSearch.Request("parsing-$index", engine = "duckduckgo"), allow, {})
                } }
                try {
                    repeat(3) { transport.entered.receive() }
                    assertFalse(secondEntered.isCompleted)
                    assertEquals(3, transport.requests.size)
                    assertEquals(0, transport.active.get())
                    assertTrue(jobs.none { it.isCompleted })
                } finally { release.countDown() }
                assertEquals("first", first.await())
                assertEquals("second", second.await())
                assertTrue(secondEntered.isCompleted)
                assertTrue(jobs.awaitAll().all { it["success"] == JsonPrimitive(true) })
                assertEquals(6, transport.requests.size)
            }
        } finally { release.countDown() }
    }

    @Test fun textOpensRejectCachedAndFreshBinarySourcesButAllowExtractedPdfPages() = bounded {
        val transport = ConcurrentTransport { url ->
            if (url.endsWith(".pdf")) AgentWebResponse(200, "%PDF-1.7 fixture".toByteArray(), url, "application/pdf")
            else AgentWebResponse(200, webFixturePng, url, "image/png")
        }
        withServices(transport) { services ->
            val service = services.single()
            val binaries = listOf("https://example.com/source.pdf", "https://example.com/source.png").map { url ->
                service.open(owner, url, null, 0, 20, false, allow, null)
            }
            binaries.forEach { binary ->
                for (refresh in listOf(false, true)) {
                    val failure = runCatching {
                        service.openText(owner, null, binary.getValue("ref_id").jsonPrimitive.content, 0, 20, refresh, allow)
                    }.exceptionOrNull()
                    assertTrue(failure is IllegalStateException)
                    assertTrue(failure?.message.orEmpty().contains("individually"))
                }
            }
            assertEquals(2, transport.requests.size)
            binaries.forEach { binary ->
                assertTrue(runCatching { service.openTextUrl(binary.getValue("url").jsonPrimitive.content) }.isFailure)
            }
            assertEquals(4, transport.requests.size)
            val page = service.readPdf(owner, binaries.first().getValue("ref_id").jsonPrimitive.content, 0, 0, 20, allow)
            val cachedPage = service.openText(owner, null, page.getValue("ref_id").jsonPrimitive.content, 0, 20, false, allow)
            assertEquals(JsonPrimitive("pdf_page"), cachedPage["kind"])
            assertEquals(JsonPrimitive(true), cachedPage["cached"])
            assertEquals(page["content_sha256"], cachedPage["content_sha256"])
            assertEquals(4, transport.requests.size)
        }
    }

    @Test fun pdfWorkIsSerializedAcrossServicesWhileTextTransfersRemainAvailable() = bounded {
        val firstPdfEntered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val backend = object : AgentWebPdfBackend by WebFixturePdf() {
            override fun pageCount(file: File): Int {
                val now = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, now) }
                try {
                    if (calls.incrementAndGet() == 1) {
                        firstPdfEntered.complete(Unit)
                        release.await()
                    }
                    return 2
                } finally { active.decrementAndGet() }
            }
        }
        val transport = ConcurrentTransport { url ->
            if (url.endsWith(".pdf")) AgentWebResponse(200, "%PDF-1.7 fixture".toByteArray(), url, "application/pdf")
            else response(url)
        }
        try {
            withServices(transport, count = 2, pdfFactory = { backend }) { services ->
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    services.first().open(owner, "https://example.com/first.pdf", null, 0, 20, false, allow, null)
                }
                try {
                    firstPdfEntered.await()
                    val second = async(start = CoroutineStart.UNDISPATCHED) {
                        services.last().open(owner, "https://example.com/second.pdf", null, 0, 20, false, allow, null)
                    }
                    try {

                        assertEquals(1, transport.requests.size)
                        val text = services.last().openTextUrl("https://example.com/text-during-pdf")
                        assertEquals(JsonPrimitive(true), text["success"])
                        assertEquals(1, calls.get())
                        assertFalse(first.isCompleted)
                        assertFalse(second.isCompleted)
                    } finally { release.countDown() }
                    assertEquals(JsonPrimitive("pdf"), first.await()["kind"])
                    assertEquals(JsonPrimitive("pdf"), second.await()["kind"])
                    assertEquals(2, calls.get())
                    assertEquals(1, maximum.get())
                } finally { release.countDown() }
            }
        } finally { release.countDown() }
    }

    @Test fun binaryFilesStayAvailableThroughEvictionAndCloseUntilEveryLeaseIsReleased() {
        val root = Files.createTempDirectory("droide-web-lease-").toFile()
        val store = AgentWebStore()
        try {
            val file = root.resolve("snapshot.pdf").apply { writeText("%PDF-1.7 fixture") }
            val ref = store.reference(owner, "https://example.com/source.pdf", "PDF", "pdf", "now")
            val document = AgentWebDocument(ref, AgentWebDocuments.text(""), "application/pdf", 200, "digest", false, file, 2)
            val first = store.putAndLease(document)
            val second = requireNotNull(store.lease(owner, ref.id))
            try {
                repeat(24) { index ->
                    val textRef = store.reference(owner, "https://example.com/page-$index", "Page", "page", "now")
                    store.put(AgentWebDocument(textRef, AgentWebDocuments.text("evidence"), "text/plain", 200, "digest", false))
                }
                assertNull(store.document(owner, ref.id))
                assertEquals(JsonPrimitive(24), store.status(owner)["cached_documents"])
                assertTrue(file.isFile)
                first.close()
                first.close()
                assertTrue(file.isFile)
                store.close()
                assertEquals(JsonPrimitive(0), store.status(owner)["cached_documents"])
                assertTrue(file.isFile)
                second.close()
                assertFalse(file.exists())
            } finally { first.close(); second.close() }
        } finally { store.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
}

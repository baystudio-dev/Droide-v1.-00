package com.baystudio.droide.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Real HTTP socket/redirect/cancellation checks on loopback through an internal resolver seam. */
class AgentWebTransportTest {
    @Test fun cancellationAfterHeadersClosesAStalledBodyAndRetryAfterIsPreserved() = runBlocking {
        withServer { server, client, resolver ->
            val headers = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            server.createContext("/body-stall") { e ->
                try {
                    e.sendResponseHeaders(200, 0); e.responseBody.write(byteArrayOf(1)); e.responseBody.flush()
                    headers.complete(Unit); release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                } finally { e.close() }
            }
            server.createContext("/rate-limit") { e ->
                e.responseHeaders.set("Retry-After", "120"); respond(e, "wait".toByteArray(), 429)
            }
            val transport = SafeAgentWebTransport(client, resolver)
            try {
                val task = launch(Dispatchers.IO) { transport.get("https://origin.example/body-stall") {} }
                withTimeout(5_000) { headers.await() }
                withTimeout(5_000) { task.cancelAndJoin() }
                assertTrue(task.isCancelled)
                assertEquals("120", transport.get("https://origin.example/rate-limit") {}.retryAfter)
            } finally { release.countDown() }
        }
    }
    private suspend fun withServer(block:suspend (ProviderLoopbackServer,OkHttpClient,(String)->ValidatedNetworkTarget)->Unit) {
        val server=ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1",0),0)
        val pool=Executors.newCachedThreadPool(); server.executor=pool; server.start()
        val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).build()
        val resolver:(String)->ValidatedNetworkTarget={ raw ->
            val uri=URI(raw)
            ValidatedNetworkTarget("http://127.0.0.1:${server.address.port}${uri.rawPath.ifBlank { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}","127.0.0.1",listOf(InetAddress.getByName("127.0.0.1")),true)
        }
        try { block(server,client,resolver) } finally { server.stop(0); pool.shutdownNow(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    private fun respond(e:ProviderLoopbackServer.Exchange,bytes:ByteArray,status:Int=200,type:String="text/plain") {
        e.responseHeaders.set("Content-Type",type); e.sendResponseHeaders(status,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }; e.close()
    }
    @Test fun eachRedirectIsAuthorizedBeforeRequestAndDns() = runBlocking { withServer { server,client,resolver ->
        val hops=mutableListOf<String>(); val dns=mutableListOf<String>(); val finalRequests=AtomicInteger()
        server.createContext("/start") { e -> e.responseHeaders.set("Location","https://other.example/end"); respond(e,"redirect".toByteArray(),302) }
        server.createContext("/end") { e -> finalRequests.incrementAndGet(); respond(e,"done".toByteArray()) }
        val transport=SafeAgentWebTransport(client,{ dns+=it; resolver(it) })
        val denied=runCatching { transport.get("https://origin.example/start") { target -> hops+=target; if(target.contains("other.example")) throw AgentWebPermissionDenied("DENIED") } }.exceptionOrNull()
        assertTrue(denied is AgentWebPermissionDenied); assertEquals(2,hops.size); assertEquals(1,dns.size); assertEquals(0,finalRequests.get())
        val result=transport.get("https://origin.example/start") {}
        assertEquals("done",result.text()); assertEquals(1,finalRequests.get())
    } }
    @Test fun insecureRedirectAndRedirectLoopsAreRejected() = runBlocking { withServer { server,client,resolver ->
        server.createContext("/downgrade") { e -> e.responseHeaders.set("Location","http://other.example/end"); respond(e,"redirect".toByteArray(),302) }
        server.createContext("/loop") { e -> e.responseHeaders.set("Location","https://origin.example/loop"); respond(e,"redirect".toByteArray(),302) }
        val transport=SafeAgentWebTransport(client,resolver)
        assertTrue(runCatching { transport.get("https://origin.example/downgrade") {} }.exceptionOrNull()!!.message!!.contains("Insecure"))
        assertTrue(runCatching { transport.get("https://origin.example/loop") {} }.exceptionOrNull()!!.message!!.contains("loop"))
    } }
    @Test fun streamLimitsDeclarePartialTextAndSniffPdfBeforeTheOldTextLimit() = runBlocking { withServer { server,client,resolver ->
        server.createContext("/large") { e -> respond(e,ByteArray(2*1024*1024+100) { 65 }) }
        val pdf=ByteArray(3*1024*1024) { 32 }; "%PDF-".toByteArray().copyInto(pdf)
        server.createContext("/pdf") { e ->
            e.responseHeaders.set("Content-Type","application/octet-stream"); e.sendResponseHeaders(200,pdf.size.toLong())
            e.responseBody.use { out -> out.write(pdf,0,3);out.flush();Thread.sleep(50);out.write(pdf,3,pdf.size-3) }; e.close()
        }
        val image=ByteArray(3*1024*1024); webFixturePng.copyInto(image)
        server.createContext("/image") { e -> respond(e,image,type="application/octet-stream") }
        val transport=SafeAgentWebTransport(client,resolver)
        val text=transport.get("https://origin.example/large") {}; assertTrue(text.truncated); assertEquals(2*1024*1024,text.bytes.size)
        val binary=transport.get("https://origin.example/pdf") {}; assertTrue(binary.isPdf); assertFalse(binary.truncated); assertEquals(pdf.size,binary.bytes.size)
        val picture=transport.get("https://origin.example/image") {}; assertTrue(picture.isImage); assertFalse(picture.truncated); assertEquals(image.size,picture.bytes.size)
    } }
    @Test fun ownDeadlineIsNetworkFailureButParentCancellationPropagates() = runBlocking { withServer { server,client,resolver ->
        server.createContext("/slow") { e -> try { Thread.sleep(300); respond(e,"late".toByteArray()) } catch (_:Exception) { e.close() } }
        val timeout=runCatching { SafeAgentWebTransport(client,resolver,40).get("https://origin.example/slow") {} }.exceptionOrNull()
        assertTrue("Expected IOException, got $timeout",timeout is IOException); assertTrue(timeout!!.message!!.contains("timed out"))
        val parent=runCatching { withTimeout(40) { SafeAgentWebTransport(client,resolver,1000).get("https://origin.example/slow") {} } }.exceptionOrNull()
        assertTrue(parent is CancellationException)
    } }
    @Test fun configuredProxyDnsDoesNotAllowPrivateWebDestinationsOrUnrelatedPrivateDns() {
        val target=ValidatedNetworkTarget("https://public.example/","public.example",listOf(InetAddress.getByName("8.8.8.8")))
        val privateRoute=InetAddress.getByName("10.20.30.40")
        val proxy=Proxy(Proxy.Type.HTTP,InetSocketAddress.createUnresolved("configured-proxy",8080))
        val client=OkHttpClient.Builder().proxy(proxy).build()
        val dns=SafeAgentWebTransport.pinnedRouteDns(target,client,object:Dns { override fun lookup(hostname:String)=listOf(privateRoute) })
        assertEquals(listOf(privateRoute),dns.lookup("configured-proxy")); assertEquals(target.addresses,dns.lookup("public.example"))
        assertTrue(runCatching { dns.lookup("localhost") }.isFailure)
        assertTrue(runCatching { AgentWebUrl.normalize("https://10.20.30.40/private") }.isFailure)
        val direct=SafeAgentWebTransport.pinnedRouteDns(target,OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
        assertTrue(runCatching { direct.lookup("localhost") }.isFailure)
    }
    @Test fun rejectedPermissionDoesNotPerformTargetResolution() = runBlocking {
        var resolved=false
        val transport=SafeAgentWebTransport(resolveTarget={ resolved=true; error("must not resolve") })
        assertTrue(runCatching { transport.get("https://example.com/") { throw AgentWebPermissionDenied("DENIED") } }.exceptionOrNull() is AgentWebPermissionDenied)
        assertFalse(resolved)
    }

    @Test fun textTransfersRejectDeclaredBinariesWithoutWaitingForTheirBody() = runBlocking {
        withServer { server, client, resolver ->
            val release = java.util.concurrent.CountDownLatch(1)
            server.createContext("/binary-stall") { e ->
                try {
                    e.responseHeaders.set("Content-Type", "application/pdf")
                    e.sendResponseHeaders(200, 0)
                    e.responseBody.write(byteArrayOf(1)); e.responseBody.flush()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                } finally { e.close() }
            }
            try {
                val failure = withTimeout(5_000) {
                    runCatching { SafeAgentWebTransport(client, resolver).getText("https://origin.example/binary-stall") {} }.exceptionOrNull()
                }
                assertTrue("Expected early text-only rejection, got $failure", failure is IllegalArgumentException)
                assertTrue(failure!!.message!!.contains("Open it individually"))
                assertEquals(1L, release.count)
            } finally { release.countDown() }
        }
    }

    @Test fun textTransfersSniffMislabelledPdfAndImageBeforeDownloadingTheRest() = runBlocking {
        withServer { server, client, resolver ->
            val release = java.util.concurrent.CountDownLatch(1)
            val heads = mapOf("/pdf-text" to "%PDF-1.7\n%%".toByteArray(), "/image-text" to webFixturePng.take(16).toByteArray())
            heads.forEach { (path, prefix) ->
                server.createContext(path) { e ->
                    try {
                        e.responseHeaders.set("Content-Type", "text/plain")
                        e.sendResponseHeaders(200, 0)
                        e.responseBody.write(prefix); e.responseBody.flush()
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    } finally { e.close() }
                }
            }
            try {
                val transport = SafeAgentWebTransport(client, resolver)
                for (path in heads.keys) {
                    val failure = withTimeout(5_000) {
                        runCatching { transport.getText("https://origin.example$path") {} }.exceptionOrNull()
                    }
                    assertTrue("$path must be rejected before its body finishes: $failure", failure is IllegalArgumentException)
                }
                assertEquals(1L, release.count)
            } finally { release.countDown() }
        }
    }

    @Test fun textTransfersKeepByteLimitsAndCancelStalledBodies() = runBlocking {
        withServer { server, client, resolver ->
            val release = java.util.concurrent.CountDownLatch(1)
            val headers = CompletableDeferred<Unit>()
            server.createContext("/large-text") { e -> respond(e, ByteArray(2 * 1024 * 1024 + 100) { 65 }) }
            server.createContext("/svg-text") { e -> respond(e, ByteArray(2 * 1024 * 1024 + 100) { 65 }, type = "image/svg+xml") }
            server.createContext("/text-stall") { e ->
                try {
                    e.responseHeaders.set("Content-Type", "text/plain")
                    e.sendResponseHeaders(200, 0); e.responseBody.write("first".toByteArray()); e.responseBody.flush()
                    headers.complete(Unit); release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                } finally { e.close() }
            }
            try {
                val transport = SafeAgentWebTransport(client, resolver)
                val text = transport.getText("https://origin.example/large-text") {}
                assertTrue(text.truncated); assertEquals(2 * 1024 * 1024, text.bytes.size)
                val svg = transport.getText("https://origin.example/svg-text") {}
                assertTrue(svg.truncated); assertEquals(2 * 1024 * 1024, svg.bytes.size)
                val pending = launch { transport.getText("https://origin.example/text-stall") {} }
                withTimeout(5_000) { headers.await() }
                withTimeout(5_000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
            } finally { release.countDown() }
        }
    }

    @Test fun textTransferRedirectsStillRequirePermissionBeforeDnsAndNetwork() = runBlocking {
        withServer { server, client, resolver ->
            val finalRequests = AtomicInteger()
            val resolutions = AtomicInteger()
            server.createContext("/text-start") { e ->
                e.responseHeaders.set("Location", "https://other.example/text-end")
                respond(e, "redirect".toByteArray(), 302)
            }
            server.createContext("/text-end") { e -> finalRequests.incrementAndGet(); respond(e, "source".toByteArray()) }
            val transport = SafeAgentWebTransport(client, { resolutions.incrementAndGet(); resolver(it) })
            val denied = runCatching {
                transport.getText("https://origin.example/text-start") { target ->
                    if (target.contains("other.example")) throw AgentWebPermissionDenied("DENIED")
                }
            }.exceptionOrNull()
            assertTrue(denied is AgentWebPermissionDenied)
            assertEquals(1, resolutions.get()); assertEquals(0, finalRequests.get())
            assertEquals("source", transport.getText("https://origin.example/text-start") {}.text())
            assertEquals(1, finalRequests.get())
        }
    }

    @Test fun cancellationDuringAuthorizationDoesNotStartTargetResolution() = runBlocking {
        val resolved = java.util.concurrent.atomic.AtomicBoolean()
        val transport = SafeAgentWebTransport(resolveTarget = { resolved.set(true); error("must not resolve") })
        val pending = async {
            transport.getText("https://example.com/") { currentCoroutineContext().cancel() }
        }
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertFalse(resolved.get())
    }

    @Test fun waitingForAuthorizationDoesNotSpendTheNetworkDeadline() = runBlocking {
        withServer { server, client, resolver ->
            val requests = AtomicInteger()
            server.createContext("/approved-text") { e -> requests.incrementAndGet(); respond(e, "approved source".toByteArray()) }
            // Warm the local client/server before testing the independent permission budget.
            SafeAgentWebTransport(client, resolver).getText("https://origin.example/approved-text") {}
            val entered = CompletableDeferred<Unit>()
            val approved = CompletableDeferred<Unit>()
            val pending = async {
                SafeAgentWebTransport(client, resolver, 500).getText("https://origin.example/approved-text") {
                    entered.complete(Unit); approved.await()
                }
            }
            try {
                withTimeout(5_000) { entered.await() }
                delay(700) // Longer than the network deadline, while no network is authorized.
                assertFalse(pending.isCompleted)
                assertEquals(1, requests.get())
                approved.complete(Unit)
                assertEquals("approved source", withTimeout(5_000) { pending.await() }.text())
                assertEquals(2, requests.get())
            } finally { pending.cancelAndJoin() }
        }
    }
}

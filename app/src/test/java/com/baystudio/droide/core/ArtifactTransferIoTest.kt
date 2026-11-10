package com.baystudio.droide.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactTransferIoTest {
    @Test fun cancellationClosesAResponseBodyThatHasAlreadyReturnedHeaders() = runBlocking {
        val releaseServer = CountDownLatch(1)
        val server = ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stall") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.responseBody.flush()
                releaseServer.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(20, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/stall").build())
        val firstByte = CompletableDeferred<Unit>()
        try {
            val operation = launch(Dispatchers.IO) {
                call.consumeCancellableResponse { response ->
                    val input = checkNotNull(response.body).byteStream()
                    check(input.read() == 1)
                    firstByte.complete(Unit)
                    input.read()
                }
            }
            withTimeout(2_000) { firstByte.await() }
            withTimeout(2_000) { operation.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally {
            releaseServer.countDown()
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
    @Test fun completedResponseKeepsCallUncancelledAndRealIoFailureIsPreserved() = runBlocking {
        val server = ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/done") { e ->
            e.sendResponseHeaders(200, 2); e.responseBody.use { it.write("ok".toByteArray()) }; e.close()
        }
        server.start()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        try {
            fun call() = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/done").build())
            val done = call()
            org.junit.Assert.assertEquals("ok", done.consumeCancellableResponse { it.body!!.string() })
            org.junit.Assert.assertFalse(done.isCanceled())
            val failed = call()
            val failure = runCatching { failed.consumeCancellableResponse<String> { throw java.io.IOException("real consumer failure") } }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            org.junit.Assert.assertEquals("real consumer failure", failure?.message)
        } finally {
            server.stop(0); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }

}

package com.baystudio.droide.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProviderTransportOwnershipTest {
    @Test(timeout = 10_000)
    fun delayedOldRuntimeTransportCannotBorrowNewProjectEndpoint() = runBlocking {
        Fixture().use { first ->
            Fixture().use { second ->
                val original = first.provider
                val prepared = ProviderRegistry.prepareConfigured(listOf(original))
                ProviderRegistry.publishConfigured(prepared)
                ProviderModelCertificationRegistry.certify(original, listOf(MODEL))
                try {
                    val client = LlmClient(prepared)
                    val delayed = async(start = CoroutineStart.LAZY) {
                        client.chatCompletions(original.id, original.baseUrl, "", MODEL, BODY)
                    }
                    ProviderRegistry.installConfigured(listOf(second.provider))

                    assertTrue(withTimeout(3_000) { delayed.await() }.contains("old-project"))
                    assertEquals(1, first.requests.get())
                    assertEquals(0, second.requests.get())

                    assertTrue(runCatching {
                        LlmClient().chatCompletions(original.id, original.baseUrl, "", MODEL, BODY)
                    }.isFailure)
                    assertEquals(0, second.requests.get())
                } finally {
                    ProviderModelCertificationRegistry.revoke(original.id)
                    ProviderRegistry.installConfigured(emptyList())
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun delayedOldRuntimeRetainsItsAuthAndProtocolWhenSameEndpointIsReconfigured() = runBlocking {
        Fixture().use { first ->
            val original = first.provider
            val prepared = ProviderRegistry.prepareConfigured(listOf(original))
            ProviderRegistry.publishConfigured(prepared)
            ProviderModelCertificationRegistry.certify(original, listOf(MODEL))
            try {
                val client = LlmClient(prepared)
                val delayed = async(start = CoroutineStart.LAZY) {
                    client.chatCompletionsStream(original.id, original.baseUrl, "", MODEL, BODY) {}
                }
                ProviderRegistry.installConfigured(listOf(original.copy(
                    authScheme = ProviderAuthScheme.ANTHROPIC_X_API_KEY,
                    wireProtocol = ProviderWireProtocol.ANTHROPIC_MESSAGES,
                    needsKey = true,
                )))

                assertTrue(withTimeout(3_000) { delayed.await() }.responseBody.contains("old-project"))
                assertEquals(1, first.requests.get())
            } finally {
                ProviderModelCertificationRegistry.revoke(original.id)
                ProviderRegistry.installConfigured(emptyList())
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val server = ProviderLoopbackServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0,
        )
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val provider = AiProvider(
            id = "custom.project-transport", name = "Project transport",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "", free = true,
            needsKey = false, helpUrl = "", note = "test", authScheme = ProviderAuthScheme.NONE,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                    requests.incrementAndGet()
                    val streaming = """"stream":true""" in request
                    val response = if (streaming) {
                        """data: {"choices":[{"index":0,"delta":{"content":"old-project"},"finish_reason":null}]}""" +
                            "\n\n" + """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" +
                            "\n\ndata: [DONE]\n\n"
                    } else {
                        """{"choices":[{"index":0,"message":{"role":"assistant","content":"old-project"},"finish_reason":"stop"}]}"""
                    }
                    exchange.responseHeaders.set("Content-Type", if (streaming) "text/event-stream" else "application/json")
                    val bytes = response.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        private const val MODEL = "workspace-ownership-fixture"
        private const val BODY = """{"model":"workspace-ownership-fixture","messages":[{"role":"user","content":"hello"}]}"""
    }
}

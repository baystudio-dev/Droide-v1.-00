package com.baystudio.droide.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderResponseCapacityTest {
    @Test(timeout = 60_000) fun oversizedFirstLineFailsSafelyAcrossEveryTransport() = runBlocking {
        for (protocol in ProviderWireProtocol.entries) for (sse in listOf(true, false)) {
            fixture(protocol, sse, oversized = true) { provider, model ->
                val failure = runCatching {
                    LlmClient().chatCompletionsStream(provider.id, provider.baseUrl, "", model, request(model)) {}
                }.exceptionOrNull()
                assertTrue("$protocol sse=$sse must reject the line as a handled error", failure is IllegalStateException)
                assertTrue(failure!!.message.orEmpty().contains("response capacity"))
            }
        }
    }

    @Test(timeout = 60_000) fun excessiveNestingCannotReachRecursiveParserInAnyTransport() = runBlocking {
        for (protocol in ProviderWireProtocol.entries) for (sse in listOf(true, false)) {
            fixture(protocol, sse, oversized = false) { provider, model ->
                val failure = runCatching {
                    LlmClient().chatCompletionsStream(provider.id, provider.baseUrl, "", model, request(model)) {}
                }.exceptionOrNull()
                assertTrue("$protocol sse=$sse must reject nesting as a handled error", failure is IllegalStateException)
                assertTrue(failure!!.message.orEmpty().contains("nesting"))
            }
        }
    }

    private fun request(model: String) = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hello") }) })
    }.toString()

    private suspend fun fixture(protocol: ProviderWireProtocol, sse: Boolean, oversized: Boolean,
        block: suspend (AiProvider, String) -> Unit) {
        val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val model = when (protocol) {
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> "claude-sonnet-4-5"
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> "gemini-2.5-flash"
            ProviderWireProtocol.OPENAI_RESPONSES -> "gpt-5"
            else -> "capacity-fixture"
        }
        val provider = AiProvider(id = "capacity.${UUID.randomUUID()}", name = "Capacity fixture",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = model, free = true,
            needsKey = false, helpUrl = "", note = "test", wireProtocol = protocol,
            authScheme = ProviderAuthScheme.NONE, networkScope = ProviderNetworkScope.LOOPBACK_ONLY)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.set("Content-Type", if (sse) "text/event-stream" else "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    if (sse) output.write("data: ".toByteArray())
                    if (oversized) {
                        val chunk = ByteArray(8192) { 'x'.code.toByte() }
                        repeat(LlmClient.MAX_RESPONSE_CHARS / chunk.size + 2) { output.write(chunk) }
                    } else {
                        val depth = ProviderPayloadLimits.MAX_JSON_DEPTH + 10
                        output.write(("[".repeat(depth) + "0" + "]".repeat(depth)).toByteArray())
                    }
                    if (sse) output.write("\n\n".toByteArray())
                }
            } catch (_: java.io.IOException) {

            } finally { exchange.close() }
        }
        server.start()
        ProviderRegistry.installConfigured(listOf(provider))
        ProviderModelCertificationRegistry.certify(provider, listOf(model))
        try { block(provider, model) }
        finally {
            ProviderModelCertificationRegistry.revoke(provider.id)
            ModelCapabilityRegistry.revokeRuntime(provider.id)
            ProviderRegistry.installConfigured(emptyList())
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

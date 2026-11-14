package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Local HTTP fixtures exercise the production buffered and streaming request entrances. */
class ProviderBudgetTransportTest {
    private fun install(http: ProviderStreamFixture, context: Int = 4096, output: Int = 512) {
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(http.provider.id, http.model, http.model,
            null, null, true, null, null, emptySet(), emptySet(), emptySet(), context, output,
            ModelCapabilitySource.RUNTIME_PROVIDER))
    }

    @Test(timeout = 30_000) fun missingOutputCapReachesEveryWireProtocolInBufferedAndStreamingCalls() {
        for (protocol in ProviderWireProtocol.entries) withProviderStreamFixture(protocol) { http -> runBlocking {
            install(http)
            http.forceBufferedResponse = true
            http.response = { _, _, _ -> when (protocol) {
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                ProviderWireProtocol.OPENAI_RESPONSES -> """{"id":"response_1","status":"completed","output":[]}"""
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
            } }
            http.buffered()
            http.stream(mutableListOf())
            assertEquals(2, http.requests.size)
            http.requests.forEach { request ->
                val cap = when (protocol) {
                    ProviderWireProtocol.OPENAI_RESPONSES -> request["max_output_tokens"]
                    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> request["generationConfig"]!!.jsonObject["maxOutputTokens"]
                    else -> request["max_tokens"]
                }
                assertEquals("$protocol", JsonPrimitive(512), cap)
            }
        } }
    }

    @Test(timeout = 30_000) fun advertisedLargeOutputCapacityReachesEveryWireWithoutAnEightKClamp() {
        for (protocol in ProviderWireProtocol.entries) withProviderStreamFixture(protocol) { http -> runBlocking {
            install(http, context = 1_048_576, output = 65_536)
            http.forceBufferedResponse = true
            http.response = { _, _, _ -> when (protocol) {
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                ProviderWireProtocol.OPENAI_RESPONSES -> """{"id":"response_1","status":"completed","output":[]}"""
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
            } }
            http.buffered()
            http.stream(mutableListOf())
            assertEquals(2, http.requests.size)
            http.requests.forEach { request ->
                val cap = when (protocol) {
                    ProviderWireProtocol.OPENAI_RESPONSES -> request["max_output_tokens"]
                    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> request["generationConfig"]!!.jsonObject["maxOutputTokens"]
                    else -> request["max_tokens"]
                }
                assertEquals("$protocol", JsonPrimitive(65_536), cap)
            }
        } }
    }

    @Test(timeout = 30_000) fun estimatedOverflowRejectsBeforeNetworkForEveryBufferedAndStreamingEntrance() {
        for (protocol in ProviderWireProtocol.entries) withProviderStreamFixture(protocol) { http -> runBlocking {
            install(http)
            val body = buildJsonObject {
                put("model", http.model)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "x".repeat(20000)) }) })
            }.toString()
            val client = LlmClient()
            val buffered = runCatching { client.chatCompletions(http.provider.id, http.provider.baseUrl, http.secret, http.model, body) }.exceptionOrNull()
            val streamed = runCatching { client.chatCompletionsStream(http.provider.id, http.provider.baseUrl, http.secret, http.model, body) {} }.exceptionOrNull()
            assertTrue("$protocol buffered: $buffered", buffered is ProviderContextOverflowException)
            assertTrue("$protocol streaming: $streamed", streamed is ProviderContextOverflowException)
            assertTrue(http.requests.isEmpty())
        } }
    }
}

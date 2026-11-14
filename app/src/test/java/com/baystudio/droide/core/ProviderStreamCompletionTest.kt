package com.baystudio.droide.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises terminal semantics through production HTTP/SSE transport, without live inference. */
class ProviderStreamCompletionTest {
    private val protocols = listOf(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
        ProviderWireProtocol.ANTHROPIC_MESSAGES, ProviderWireProtocol.GOOGLE_GENERATE_CONTENT)

    @Test(timeout = 30_000) fun truncatedToolCallStreamsNeverReturnAnExecutableResult() {
        protocols.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.wire = toolPrefix(protocol)
                val failure = assertThrows(IllegalStateException::class.java) { fixture.send() }
                assertTrue("$protocol: ${failure.message}", failure.message.orEmpty().contains("before"))
            }
        }
    }

    @Test(timeout = 30_000) fun transportEndMarkersWithoutModelFinishReasonsCannotCertifyCalls() {
        withFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            fixture.wire = toolPrefix(fixture.protocol) + "data: [DONE]\n\n"
            assertThrows(IllegalStateException::class.java) { fixture.send() }
        }
        withFixture(ProviderWireProtocol.ANTHROPIC_MESSAGES) { fixture ->
            fixture.wire = toolPrefix(fixture.protocol) + event(obj("""{"type":"message_stop"}"""))
            assertThrows(IllegalStateException::class.java) { fixture.send() }
        }
    }

    @Test(timeout = 30_000) fun anthropicRequiresMessageStopAfterTheModelStopReason() {
        withFixture(ProviderWireProtocol.ANTHROPIC_MESSAGES) { fixture ->
            fixture.wire = toolPrefix(fixture.protocol) + event(obj("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""))
            assertThrows(IllegalStateException::class.java) { fixture.send() }
        }
    }

    @Test(timeout = 30_000) fun completedCallsPreserveCallIdentityAndArgumentsInEveryNativeStream() {
        protocols.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.wire = toolPrefix(protocol) + terminal(protocol)
                val result = fixture.send()
                assertEquals(LlmStreamMode.NATIVE, result.mode)
                val message = message(result)
                val call = message.getValue("tool_calls").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("call_1"), call["id"])
                val function = call.getValue("function").jsonObject
                assertEquals(JsonPrimitive("read_file"), function["name"])
                assertEquals(obj("""{"path":"README.md"}"""), Json.parseToJsonElement(function.getValue("arguments").jsonPrimitive.content))
                assertEquals(JsonPrimitive("tool_calls"), choice(result)["finish_reason"])
            }
        }
    }

    @Test(timeout = 30_000) fun tokenLimitedCallsNeverReturnToolsOrPrivateFunctionContinuation() {
        protocols.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.wire = toolPrefix(protocol) + terminal(protocol, limited = true)
                val result = fixture.send()
                assertEquals(JsonPrimitive("length"), choice(result)["finish_reason"])
                assertNoExecutableCalls(result)
            }
        }
    }

    @Test(timeout = 30_000) fun filteredCallsNeverReturnToolsOrPrivateFunctionContinuation() {
        protocols.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.wire = toolPrefix(protocol) + terminal(protocol, filtered = true)
                assertNoExecutableCalls(fixture.send())
            }
        }
    }

    @Test(timeout = 30_000) fun commentAndMultilineSseRecordsStillRequireAndPreserveCompletion() {
        protocols.forEach { protocol ->
            withFixture(protocol) { fixture ->
                // Each JSON data object spans two SSE data lines, with an initial heartbeat.
                fixture.wire = ": heartbeat\n\n" + (toolPrefix(protocol) + terminal(protocol)).replace("data: {", "data: {\ndata: ")
                assertEquals(1, message(fixture.send()).getValue("tool_calls").jsonArray.size)
                fixture.wire = ": heartbeat\n\n" + toolPrefix(protocol).replace("data: {", "data: {\ndata: ")
                assertThrows(IllegalStateException::class.java) { fixture.send() }
            }
        }
    }

    @Test(timeout = 30_000) fun compatibleChatVendorCanEndAtEofAfterAnExplicitFinishReason() {
        withFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            fixture.wire = toolPrefix(fixture.protocol) + terminal(fixture.protocol).removeSuffix("data: [DONE]\n\n")
            assertEquals(1, message(fixture.send()).getValue("tool_calls").jsonArray.size)
        }
    }

    @Test(timeout = 30_000) fun googlePromptBlockIsAnExplicitNonExecutableTerminalResponse() {
        withFixture(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) { fixture ->
            fixture.wire = event(obj("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
            val result = fixture.send()
            assertEquals(JsonPrimitive("content_filter"), choice(result)["finish_reason"])
            assertNoExecutableCalls(result)
        }
    }

    @Test(timeout = 30_000) fun googleTrailingUsageAfterFinishReasonIsRetained() {
        withFixture(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) { fixture ->
            fixture.wire = toolPrefix(fixture.protocol) + terminal(fixture.protocol) +
                event(obj("""{"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2,"totalTokenCount":6}}"""))
            val result = fixture.send()
            val usage = obj(result.responseBody).getValue("usage").jsonObject
            assertEquals(JsonPrimitive(4), usage["prompt_tokens"])
            assertEquals(JsonPrimitive(6), usage["total_tokens"])
            assertEquals(1, message(result).getValue("tool_calls").jsonArray.size)
        }
    }

    private fun assertNoExecutableCalls(result: LlmStreamResult) {
        val message = message(result)
        assertTrue((message["tool_calls"] as? JsonArray).isNullOrEmpty())
        assertFalse("Incomplete function continuation must not be replayed", message.keys.any { it.startsWith("_droide_") })
    }

    private fun choice(result: LlmStreamResult) = obj(result.responseBody).getValue("choices").jsonArray.first().jsonObject
    private fun message(result: LlmStreamResult) = choice(result).getValue("message").jsonObject
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun event(payload: JsonObject) = "data: $payload\n\n"

    private fun toolPrefix(protocol: ProviderWireProtocol): String = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> event(obj("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]},"finish_reason":null}]}"""))
        ProviderWireProtocol.ANTHROPIC_MESSAGES ->
            event(obj("""{"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-4-5","content":[]}}""")) +
                event(obj("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"read_file","input":{}}}""")) +
                event(obj("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"README.md\"}"}}""")) +
                event(obj("""{"type":"content_block_stop","index":0}"""))
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> event(obj("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call_1","name":"read_file","args":{"path":"README.md"}}}]}}]}"""))
        else -> error("Responses has its own terminal-response regression coverage")
    }

    private fun terminal(protocol: ProviderWireProtocol, limited: Boolean = false, filtered: Boolean = false): String = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> {
            val reason = if (limited) "length" else if (filtered) "content_filter" else "tool_calls"
            event(obj("""{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}""")) + "data: [DONE]\n\n"
        }
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> {
            val reason = if (limited) "max_tokens" else if (filtered) "refusal" else "tool_use"
            event(obj("""{"type":"message_delta","delta":{"stop_reason":"$reason"}}""")) + event(obj("""{"type":"message_stop"}"""))
        }
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> {
            val reason = if (limited) "MAX_TOKENS" else if (filtered) "SAFETY" else "STOP"
            event(obj("""{"candidates":[{"finishReason":"$reason"}]}"""))
        }
        else -> error("Responses has its own terminal-response regression coverage")
    }

    private fun withFixture(protocol: ProviderWireProtocol, block: (Fixture) -> Unit) {
        Fixture(protocol).use { fixture ->
            ProviderRegistry.installConfigured(listOf(fixture.provider))
            ProviderModelCertificationRegistry.certify(fixture.provider, listOf(fixture.model))
            try {
                block(fixture)
                fixture.handlerFailure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
            } finally {
                ProviderModelCertificationRegistry.revoke(fixture.provider.id)
                ModelCapabilityRegistry.revokeRuntime(fixture.provider.id)
                ProviderRegistry.installConfigured(emptyList())
            }
        }
    }

    private class Fixture(val protocol: ProviderWireProtocol) : AutoCloseable {
        private val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val executor = Executors.newSingleThreadExecutor()
        val handlerFailure = AtomicReference<Throwable?>()
        @Volatile var wire = ""
        val model = when (protocol) {
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> "claude-sonnet-4-5"
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> "gemini-2.5-flash"
            else -> "stream-fixture"
        }
        val provider = AiProvider(
            id = "stream.completion.${UUID.randomUUID()}", name = "Stream completion fixture",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = model,
            free = true, needsKey = false, helpUrl = "", note = "test",
            wireProtocol = protocol, authScheme = ProviderAuthScheme.NONE,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    exchange.requestBody.use { it.readBytes() }
                    val response = wire.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                } catch (failure: Throwable) {
                    handlerFailure.set(failure)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun send(): LlmStreamResult = runBlocking {
            withTimeout(5_000) {
                val request = buildJsonObject {
                    put("model", model)
                    put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "read README") }) })
                    put("tools", buildJsonArray { add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "read_file")
                            put("parameters", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) }) })
                        })
                    }) })
                }
                LlmClient().chatCompletionsStream(provider.id, provider.baseUrl, "", model, request.toString()) {}
            }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback fixture executor did not stop" }
        }
    }
}

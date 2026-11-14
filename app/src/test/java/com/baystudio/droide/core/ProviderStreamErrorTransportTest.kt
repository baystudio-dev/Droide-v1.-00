package com.baystudio.droide.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** HTTP-200 error events through production transport on real local sockets. */
class ProviderStreamErrorTransportTest {
    @Test(timeout = 30_000) fun errorBeforeFirstTextIsTypedRedactedAndNeverRetriedAcrossFourProtocols() {
        ProviderWireProtocol.entries.forEach { protocol -> withProviderStreamFixture(protocol) { fixture ->
            val events = mutableListOf<LlmStreamEvent>()
            val failure = assertThrows(LlmProviderStreamException::class.java) { runBlocking {
                withTimeout(4_000) { fixture.stream(events) }
            } }
            assertEquals(ProviderStreamFailureKind.TEMPORARY_SERVICE, failure.kind)
            assertFalse(failure.message!!.contains(PROVIDER_STREAM_SECRET))
            assertFalse(failure.responseBody.contains(PROVIDER_STREAM_SECRET))
            assertEquals(1, fixture.requests.size)
            assertTrue(events.isEmpty())
        } }
    }

    @Test(timeout = 30_000) fun errorAfterPartialTextAndToolArgumentsCannotCompleteOrReplayTheCall() {
        ProviderWireProtocol.entries.forEach { protocol -> withProviderStreamFixture(protocol) { fixture ->
            fixture.partial = true
            fixture.error = """{"code":429,"type":"rate_limit_error","message":"Per minute quota exceeded"}"""
            val events = mutableListOf<LlmStreamEvent>()
            val failure = assertThrows(LlmProviderStreamException::class.java) { runBlocking {
                withTimeout(4_000) { fixture.stream(events) }
            } }
            assertEquals(ProviderStreamFailureKind.RATE_LIMIT, failure.kind)
            assertFalse(failure.isQuotaFailure())
            assertEquals("public partial", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
            assertTrue("Tool input must really have arrived before failure on $protocol", events.any { it is LlmStreamEvent.ToolCallDelta })
            assertFalse(events.any { it is LlmStreamEvent.RetryScheduled })
            assertEquals(1, fixture.requests.size)
        } }
    }

    @Test(timeout = 30_000) fun failedHttp200BufferedBodiesGetTheSameCredentialSafeClassification() {
        ProviderWireProtocol.entries.forEach { protocol -> listOf(false, true).forEach { streamingRequest ->
            withProviderStreamFixture(protocol) { fixture ->
                fixture.forceBufferedResponse = true
                fixture.error = """{"type":"insufficient_quota","message":"Rate limit reached; echo $PROVIDER_STREAM_SECRET"}"""
                val events = mutableListOf<LlmStreamEvent>()
                val failure = assertThrows(LlmProviderStreamException::class.java) { runBlocking {
                    withTimeout(4_000) { if (streamingRequest) fixture.stream(events) else fixture.buffered() }
                } }
                assertTrue("Typed quota for $protocol streaming=$streamingRequest", failure.isQuotaFailure())
                assertEquals(200, failure.httpStatus)
                assertFalse(failure.message!!.contains(PROVIDER_STREAM_SECRET))
                assertFalse(failure.responseBody.contains(PROVIDER_STREAM_SECRET))
                assertEquals(1, fixture.requests.size)
                assertTrue(events.isEmpty())
            }
        } }
    }

    @Test(timeout = 10_000) fun unsupportedEventParserErrorsCannotEchoTheResolvedCredential() {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_RESPONSES) { fixture ->
            fixture.response = { _, _, _ -> fixture.prefix() + fixture.event("""{"type":"unsupported-$PROVIDER_STREAM_SECRET"}""") }
            val events = mutableListOf<LlmStreamEvent>()
            val failure = assertThrows(LlmProviderStreamException::class.java) { runBlocking {
                withTimeout(4_000) { fixture.stream(events) }
            } }
            assertEquals(ProviderStreamFailureKind.UNKNOWN, failure.kind)
            assertEquals(200, failure.httpStatus)
            assertFalse(failure.message!!.contains(PROVIDER_STREAM_SECRET))
            assertFalse(failure.responseBody.contains(PROVIDER_STREAM_SECRET))
            assertNull(failure.cause)
            assertEquals(1, fixture.requests.size)
            assertTrue(events.any { it is LlmStreamEvent.TextDelta })
            assertFalse(events.any { it is LlmStreamEvent.RetryScheduled })
        }
    }

    @Test(timeout = 30_000) fun malformedJsonCannotLeakAnAlreadyClippedCredentialPrefixOrReplay() {
        ProviderWireProtocol.entries.forEach { protocol -> listOf(false, true).forEach { streamingRequest -> withProviderStreamFixture(protocol) { fixture ->
            fixture.secret = "resolved-oauth-prefix-fixture-" + "s".repeat(700)
            fixture.forceBufferedResponse = true
            fixture.response = { _, _, _ -> """{"broken" "echo":"${fixture.secret}"}""" }
            val events = mutableListOf<LlmStreamEvent>()
            val failure = assertThrows(LlmProviderStreamException::class.java) { runBlocking {
                withTimeout(4_000) { if (streamingRequest) fixture.stream(events) else fixture.buffered() }
            } }
            assertEquals(ProviderStreamFailureKind.UNKNOWN, failure.kind)
            assertEquals(200, failure.httpStatus)
            assertTrue(failure.message!!.contains("Provider returned malformed JSON."))
            assertFalse(failure.message!!.contains("resolved-oauth-prefix"))
            assertFalse(failure.responseBody.contains("resolved-oauth-prefix"))
            assertFalse(failure.message!!.contains("JSON input"))
            assertNull(failure.cause)
            assertTrue(events.isEmpty())
            assertEquals(1, fixture.requests.size)
        } } }
    }
}

internal const val PROVIDER_STREAM_SECRET = "fixture-resolved-credential-123456"

internal fun withProviderStreamFixture(protocol: ProviderWireProtocol, authRequired: Boolean = true, block: (ProviderStreamFixture) -> Unit) {
    ProviderStreamFixture(protocol, authRequired).use { fixture ->
        val field = ProviderRegistry::class.java.getDeclaredField("configured").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val configured = field.get(ProviderRegistry) as AtomicReference<List<AiProvider>>
        synchronized(ProviderRegistry) {
            ProviderRegistry.installConfigured(configured.get() + fixture.provider)
            ProviderModelCertificationRegistry.certify(fixture.provider, listOf(fixture.model))
            try {
                block(fixture)
                fixture.handlerFailure.get()?.let { throw AssertionError("Stream fixture handler failed", it) }
            } finally {
                ProviderModelCertificationRegistry.revoke(fixture.provider.id)
                ModelCapabilityRegistry.revokeRuntime(fixture.provider.id)
                configured.updateAndGet { entries -> entries.filterNot { it.id == fixture.provider.id } }
            }
        }
    }
}

internal class ProviderStreamFixture(val protocol: ProviderWireProtocol, authRequired: Boolean = true) : AutoCloseable {
    val model = "stream-error-fixture"
    val requests = CopyOnWriteArrayList<JsonObject>()
    val handlerFailure = AtomicReference<Throwable?>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "droide-stream-error-fixture").apply { isDaemon = true } }
    private val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val provider = AiProvider(id = "stream.error.${UUID.randomUUID()}", name = "Stream error fixture",
        baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = model, free = true, needsKey = authRequired,
        helpUrl = "", note = "test", wireProtocol = protocol, authScheme = if (authRequired) ProviderAuthScheme.BEARER else ProviderAuthScheme.NONE,
        networkScope = ProviderNetworkScope.LOOPBACK_ONLY)
    var ignoreExpectedClientDisconnect = false
    var partial = false
    var forceBufferedResponse = false
    var secret = PROVIDER_STREAM_SECRET
    var error = """{"code":503,"type":"overloaded_error","message":"Overloaded; echo $PROVIDER_STREAM_SECRET"}"""
    var response: ((Int, JsonObject, Boolean) -> String)? = null
    val config get() = AgentConfig(provider.baseUrl, secret, model, provider.id)

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
                requests += request
                val streaming = request["stream"] == JsonPrimitive(true) || exchange.requestURI.rawQuery == "alt=sse"
                val wire = response?.invoke(requests.size, request, streaming) ?: if (streaming && !forceBufferedResponse) {
                    (if (partial) prefix() else "") + errorEvent()
                } else bufferedError()
                val bytes = wire.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", if (streaming && !forceBufferedResponse) "text/event-stream" else "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (failure: Throwable) {
                val expectedDisconnect = ignoreExpectedClientDisconnect && failure is java.io.IOException &&
                    (failure.message?.contains("Broken pipe", ignoreCase = true) == true ||
                     failure.message?.contains("Connection reset", ignoreCase = true) == true)
                if (!expectedDisconnect) handlerFailure.set(failure)
            }
            finally { exchange.close() }
        }
        server.start()
    }

    suspend fun stream(events: MutableList<LlmStreamEvent>) = LlmClient().chatCompletionsStream(
        provider.id, provider.baseUrl, secret, model, request(),
    ) { events += it }
    suspend fun buffered() = LlmClient().chatCompletions(provider.id, provider.baseUrl, secret, model, request())
    private fun request() = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hello") }) })
    }.toString()
    fun event(payload: String) = "data: $payload\n\n"
    fun errorEvent() = when (protocol) {
        ProviderWireProtocol.OPENAI_RESPONSES -> event("""{"type":"response.failed","response":{"status":"failed","error":$error,"output":[]}}""")
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> event("""{"type":"error","error":$error}""")
        else -> event("""{"error":$error}""")
    }
    private fun bufferedError() = if (protocol == ProviderWireProtocol.OPENAI_RESPONSES)
        """{"status":"failed","error":$error,"output":[]}""" else """{"error":$error}"""
    fun prefix() = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> event("""{"choices":[{"index":0,"delta":{"content":"public partial","tool_calls":[{"index":0,"id":"pending-call","type":"function","function":{"name":"write_file","arguments":"{\"path\":\"pending.txt\""}}]},"finish_reason":null}]}""")
        ProviderWireProtocol.OPENAI_RESPONSES -> event("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"public partial"}""") +
            event("""{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"item1","call_id":"pending-call","name":"write_file","arguments":""}}""") +
            event("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"path\":\"pending.txt\""}""")
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> event("""{"type":"message_start","message":{"id":"m1","model":"$model","content":[]}}""") +
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"public partial"}}""") +
            event("""{"type":"content_block_stop","index":0}""") +
            event("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"pending-call","name":"write_file","input":{}}}""") +
            event("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"pending.txt\""}}""")
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> event("""{"candidates":[{"content":{"role":"model","parts":[{"text":"public partial"},{"functionCall":{"id":"pending-call","name":"write_file","args":{"path":"pending.txt"}}}]}}]}""")
    }
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Stream fixture executor did not stop" }
    }
}

package com.baystudio.droide.core

import java.net.InetAddress
import java.io.IOException
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

class ProviderTransportSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun nativeResponsesOnlyModelsRouteWithoutChangingCompatibleProviders() {
        val openai = ProviderRegistry.byId("openai")
        assertEquals(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, openai.wireProtocol)
        val pro = ProviderTransportV2.spec(openai, "gpt-5.4-pro")
        assertEquals(ProviderWireProtocol.OPENAI_RESPONSES, pro.protocol)
        assertEquals("/responses", pro.chatPath)
        assertEquals(ProviderWireProtocol.OPENAI_RESPONSES, ProviderTransportV2.spec(openai, "gpt-5.4").protocol)
        assertEquals(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
            ProviderTransportV2.spec(ProviderRegistry.byId("openrouter"), "openai/gpt-5.4-pro").protocol)
    }

    @Test(timeout = 30_000) fun responsesFailedMalformedAndTruncatedSseCannotCompleteSuccessfully() {
        val failures = listOf(
            "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"message\":\"fixture failed\"}}}\n\n",
            "event: error\ndata: {\"type\":\"error\",\"message\":\"fixture error\"}\n\n",
            "data: malformed-json\n\n",
            "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"}\n\n",
        )
        withFixture(ProviderWireProtocol.OPENAI_RESPONSES) { fixture ->
            failures.forEach { wire ->
                fixture.streamResponseOverride = wire
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        withTimeout(5_000) {
                            fixture.client.chatCompletionsStream(fixture.provider.id, fixture.provider.baseUrl,
                                "", MODEL, request(AgentToolCatalog.definitions)) {}
                        }
                    }
                }
            }
            assertEquals(failures.size, fixture.requests.size)
            fixture.handlerFailure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        }
    }

    @Test(timeout = 45_000) fun temporaryHttp503RetriesBeforeStreamingAcrossFourProtocols() {
        ProviderWireProtocol.entries.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.httpFailuresRemaining = 1
                fixture.httpFailureStatus = 503
                fixture.httpRetryAfter = "0"
                try {
                    runBlocking {
                        withTimeout(8_000) { send(fixture, request(JsonArray(emptyList())), streaming = true) }
                    }
                } catch (failure: Throwable) {
                    throw AssertionError(
                        "HTTP 503 retry failed for $protocol: requests=${fixture.requests.size} " +
                            "remaining=${fixture.httpFailuresRemaining}; serverFailure=${fixture.handlerFailure.get()}",
                        failure,
                    )
                }
                assertEquals("Expected one safe retry on $protocol", 2, fixture.requests.size)
                assertEquals(fixture.requests[0].body, fixture.requests[1].body)
                fixture.handlerFailure.get()?.let { throw AssertionError("Loopback failure", it) }
            }
        }
    }

    @Test(timeout = 30_000) fun bufferedGenerationUsesTheSameBoundedExplicitResponseRecovery() {
        ProviderWireProtocol.entries.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.httpFailuresRemaining = 1
                fixture.httpRetryAfter = "0"
                runBlocking { withTimeout(5_000) { send(fixture, request(JsonArray(emptyList())), streaming = false) } }
                assertEquals(2, fixture.requests.size)
                assertEquals(fixture.requests[0].body, fixture.requests[1].body)
            }
        }
    }

    @Test(timeout = 30_000) fun okhttp503ZeroHintCannotBypassObservablePolicyRetryBudget() {
        ProviderWireProtocol.entries.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.httpFailuresRemaining = 8
                fixture.httpRetryAfter = "0"
                val events = mutableListOf<LlmStreamEvent>()
                assertThrows(LlmHttpException::class.java) {
                    runBlocking { withTimeout(5_000) {
                        fixture.client.chatCompletionsStream(fixture.provider.id, fixture.provider.baseUrl,
                            "", MODEL, request(JsonArray(emptyList()))) { events += it }
                    } }
                }
                assertEquals("Each actual retry must belong to the policy on $protocol", 3, fixture.requests.size)
                assertEquals(2, events.filterIsInstance<LlmStreamEvent.RetryScheduled>().size)
                assertTrue(events.all { it is LlmStreamEvent.RetryScheduled })
            }
        }
    }

    @Test(timeout = 30_000) fun millisecondRetryHintIsPreservedAcrossTheFourRealTransports() {
        ProviderWireProtocol.entries.forEach { protocol ->
            withFixture(protocol) { fixture ->
                fixture.httpFailuresRemaining = 1
                fixture.httpRetryAfter = "0"
                fixture.httpRetryAfterMs = "500"
                val events = mutableListOf<LlmStreamEvent>()
                runBlocking { withTimeout(5_000) {
                    fixture.client.chatCompletionsStream(fixture.provider.id, fixture.provider.baseUrl,
                        "", MODEL, request(JsonArray(emptyList()))) { events += it }
                } }
                assertEquals(2, fixture.requests.size)
                assertTrue(events.filterIsInstance<LlmStreamEvent.RetryScheduled>().single().delayMs >= 500)
                assertTrue("Server's minimum wait must be respected", fixture.requestTimes[1] - fixture.requestTimes[0] >= TimeUnit.MILLISECONDS.toNanos(500))
            }
        }
    }

    @Test(timeout = 30_000) fun permanentQuotaAndLongProviderHintsDoNotReplayEitherGenerationMode() {
        data class Failure(val status: Int, val body: String, val seconds: String? = null, val milliseconds: String? = null)
        val failures = listOf(
            Failure(429, "insufficient_quota: rate limit requests per minute", "0"),
            Failure(429, "Quota exceeded: GenerateRequestsPerDayPerProjectPerModel. See rate limits.", "0"),
            Failure(429, """{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"60s"}]}}"""),
            Failure(503, "overloaded", milliseconds = "60000"),
            Failure(503, "overloaded", seconds = "92233720368547758080"),
        )
        ProviderWireProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { streaming ->
                failures.forEach { failure ->
                    withFixture(protocol) { fixture ->
                        fixture.httpFailuresRemaining = 8
                        fixture.httpFailureStatus = failure.status
                        fixture.httpFailureBody = failure.body
                        fixture.httpRetryAfter = failure.seconds
                        fixture.httpRetryAfterMs = failure.milliseconds
                        assertThrows(LlmHttpException::class.java) {
                            runBlocking { withTimeout(3_000) { send(fixture, request(JsonArray(emptyList())), streaming) } }
                        }
                        assertEquals("No shortened wait or quota replay on $protocol / streaming=$streaming", 1, fixture.requests.size)
                    }
                }
            }
        }
    }

    @Test(timeout = 30_000) fun anAmbiguousDisconnectNeverResendsTheGenerationPost() {
        ProviderWireProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { streaming ->
                withFixture(protocol) { fixture ->
                    fixture.disconnectWithoutResponse = true
                    val failure = assertThrows(LlmProviderStreamException::class.java) {
                        runBlocking { withTimeout(3_000) { send(fixture, request(JsonArray(emptyList())), streaming) } }
                    }
                    assertEquals(ProviderStreamFailureKind.UNKNOWN, failure.kind)
                    assertNull(failure.httpStatus)
                    assertNull(failure.logicalStatus)
                    assertEquals("No transport replay after request was received", 1, fixture.requests.size)
                }
            }
        }
    }

    @Test(timeout = 15_000) fun exhaustedQuotaAndLongRetryAfterDoNotReplayRequests() {
        listOf(429 to "insufficient_quota", 429 to "credit_balance_exhausted", 503 to "server busy").forEach { (status, body) ->
            withFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
                fixture.httpFailuresRemaining = 4
                fixture.httpFailureStatus = status
                fixture.httpFailureBody = body
                fixture.httpRetryAfter = if (status == 503) "60" else "0"
                assertThrows(LlmHttpException::class.java) {
                    runBlocking {
                        withTimeout(4_000) {
                            fixture.client.chatCompletionsStream(
                                fixture.provider.id, fixture.provider.baseUrl, "", MODEL,
                                request(JsonArray(emptyList())),
                            ) {}
                        }
                    }
                }
                assertEquals("No request replay for $status $body", 1, fixture.requests.size)
            }
        }
    }

    @Test(timeout = 12_000) fun transientHttp429StopsAfterBoundedRetries() {
        withFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            fixture.httpFailuresRemaining = 8
            fixture.httpFailureStatus = 429
            fixture.httpFailureBody = "rate limit requests per minute"
            fixture.httpRetryAfter = "0"
            assertThrows(LlmHttpException::class.java) {
                runBlocking {
                    withTimeout(6_000) {
                        fixture.client.chatCompletionsStream(fixture.provider.id, fixture.provider.baseUrl,
                            "", MODEL, request(JsonArray(emptyList()))) {}
                    }
                }
            }
            assertEquals(1 + ProviderRetryPolicy.MAX_RETRIES, fixture.requests.size)
        }
    }

    @Test(timeout = 30_000) fun bufferedAndNativeStreamingSendBuiltinMcpAndPluginSchemasThroughEveryProtocol() {
        val originalCatalog = AgentToolCatalog.definitions.toString()
        val tools = JsonArray(AgentToolCatalog.definitions + mixedTools())
        val parametersByProtocol = mutableListOf<Map<String, JsonObject>>()
        ProviderWireProtocol.entries.forEach { protocol ->
            withFixture(protocol) { fixture ->
                val captured = sendBothModes(fixture, tools)
                val buffered = declarations(protocol, captured[0].body)
                val streamed = declarations(protocol, captured[1].body)
                assertEquals("Buffered and streaming schemas must agree for $protocol", buffered, streamed)
                assertEquals(tools.size, buffered.size)
                assertTodoContract(buffered.getValue("todowrite"))

                val rows = buffered.getValue("mcp_fixture").getValue("properties").jsonObject
                    .getValue("rows").jsonObject
                assertEquals(JsonPrimitive("array"), rows["type"])
                val row = rows.getValue("items").jsonObject
                assertEquals(JsonPrimitive("object"), row["type"])
                val tags = row.getValue("properties").jsonObject.getValue("tags").jsonObject
                assertEquals(JsonPrimitive("array"), tags["type"])
                assertEquals(JsonPrimitive("string"), tags.getValue("items").jsonObject["type"])
                assertFalse("Local references must be resolved before transport", "\$ref" in row)
                assertFalse("\$defs" in buffered.getValue("mcp_fixture"))

                val plugin = buffered.getValue("plugin_fixture").getValue("properties").jsonObject
                assertEquals(JsonPrimitive(true), plugin["freeform"])
                assertEquals(JsonPrimitive(false), plugin["blocked"])
                assertEquals(JsonPrimitive(true), plugin.getValue("flags").jsonObject["items"])
                assertEquals(
                    json.parseToJsonElement("""{"type":"array","${'$'}ref":"literal","required":null}"""),
                    plugin.getValue("payload").jsonObject["default"],
                )
                parametersByProtocol += buffered
            }
        }
        assertTrue("All protocols must retain the same argument contract", parametersByProtocol.distinct().size == 1)
        assertEquals("Transport must not mutate shared tool definitions", originalCatalog, AgentToolCatalog.definitions.toString())
    }

    @Test(timeout = 30_000) fun malformedSchemasAreRejectedBeforeHttpInBothModesAndAllProtocols() {
        val malformed = listOf(
            """{"type":"object","properties":{"rows":{"type":"array","items":7}}}""",
            """{"type":"object","required":"rows"}""",
            """{"type":"object","properties":{"rows":{"${'$'}ref":"https://example.invalid/schema"}}}""",
            """{"type":"object","properties":{"rows":{"${'$'}ref":"#/missing"}}}""",
            """{"type":"object","properties":{"n":{"type":"number","exclusiveMinimum":"zero"}}}""",
            """{"type":"object","properties":{"n":{"type":"number","exclusiveMaximum":[]}}}""",
        )
        ProviderWireProtocol.entries.forEach { protocol ->
            withFixture(protocol) { fixture ->
                malformed.forEach { schema ->
                    val tools = JsonArray(AgentToolCatalog.definitions + tool("mcp_invalid", json.parseToJsonElement(schema)))
                    listOf(false, true).forEach { streaming ->
                        val failure = assertThrows(IllegalArgumentException::class.java) {
                            runBlocking {
                                withTimeout(5_000) {
                                    send(fixture, request(tools), streaming)
                                }
                            }
                        }
                        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("mcp_invalid"))
                    }
                }
                assertTrue("Invalid $protocol schemas must never reach HTTP", fixture.requests.isEmpty())
            }
        }
    }

    @Test(timeout = 30_000) fun legacyTupleAssertionsAndNestedAdditionalItemsSurviveWireSerialization() {
        // This proves serializer preservation through the JSON Schema field, not acceptance by an
        // upstream vendor or a model-specific strict schema subset.
        val schema = json.parseToJsonElement("""{
          "type":"object","properties":{"rows":{
            "type":"array","items":[{"type":"string"},{"type":"integer"}],
            "additionalItems":{"${'$'}ref":"#/definitions/extra"}
          }},"definitions":{"extra":{"type":"array","items":{
            "type":"array","items":{"type":"boolean"}
          }}}
        }""")
        val tools = JsonArray(AgentToolCatalog.definitions + tool("mcp_legacy_fixture", schema))
        ProviderWireProtocol.entries.forEach { protocol ->
            withFixture(protocol) { fixture ->
                val captured = sendBothModes(fixture, tools)
                captured.forEach { request ->
                    val parameters = declarations(protocol, request.body).getValue("mcp_legacy_fixture")
                    assertFalse("definitions" in parameters)
                    val rows = parameters.getValue("properties").jsonObject.getValue("rows").jsonObject
                    assertEquals(JsonPrimitive("array"), rows["type"])
                    assertEquals(listOf(JsonPrimitive("string"), JsonPrimitive("integer")),
                        rows.getValue("items").jsonArray.map { it.jsonObject["type"] })
                    val tail = rows.getValue("additionalItems").jsonObject
                    assertFalse("\$ref" in tail)
                    assertEquals(JsonPrimitive("array"), tail["type"])
                    val nestedArray = tail.getValue("items").jsonObject
                    assertEquals(JsonPrimitive("array"), nestedArray["type"])
                    assertEquals(JsonPrimitive("boolean"), nestedArray.getValue("items").jsonObject["type"])
                }
            }
        }
    }

    private fun assertTodoContract(schema: JsonObject) {
        assertTrue("Omitting todos remains the read operation", schema.getValue("required").jsonArray.isEmpty())
        val todos = schema.getValue("properties").jsonObject.getValue("todos").jsonObject
        assertEquals(JsonPrimitive("array"), todos["type"])
        val todo = todos.getValue("items").jsonObject
        assertEquals(JsonPrimitive("object"), todo["type"])
        assertEquals(setOf("content", "status", "priority"), todo.getValue("properties").jsonObject.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("content"))), todo["required"])
        AgentPluginJsonSchema.requireValid(schema, buildJsonObject {})
        AgentPluginJsonSchema.requireValid(schema, json.parseToJsonElement("""{"todos":[{"content":"fix"}]}""").jsonObject)
    }

    private fun declarations(protocol: ProviderWireProtocol, request: JsonObject): Map<String, JsonObject> {
        val tools = request.getValue("tools").jsonArray
        val functions = when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> tools.map {
                assertEquals(JsonPrimitive("function"), it.jsonObject["type"])
                it.jsonObject.getValue("function").jsonObject
            }
            ProviderWireProtocol.OPENAI_RESPONSES -> tools.map {
                assertEquals(JsonPrimitive("function"), it.jsonObject["type"])
                assertFalse("Responses tools must use the flat native shape", "function" in it.jsonObject)
                it.jsonObject
            }
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> tools.map { it.jsonObject }
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> {
                assertEquals(1, tools.size)
                tools.single().jsonObject.getValue("functionDeclarations").jsonArray.map { it.jsonObject }
            }
        }
        val key = when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderWireProtocol.OPENAI_RESPONSES -> "parameters"
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> "input_schema"
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> "parametersJsonSchema"
        }
        return functions.associate { fn ->
            setOf("parameters", "input_schema", "parametersJsonSchema").filter { it != key }.forEach {
                assertFalse("$protocol must not send foreign schema key $it", it in fn)
            }
            fn.getValue("name").jsonPrimitive.content to fn.getValue(key).jsonObject
        }
    }

    private fun mixedTools(): List<JsonObject> = listOf(
        tool("mcp_fixture", json.parseToJsonElement("""{
          "type":"object","properties":{"rows":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/row"}}},
          "${'$'}defs":{"row":{"type":"object","properties":{
            "name":{"type":"string"},"tags":{"type":"array","items":{"type":"string"}}
          },"required":["name"]}}
        }""")),
        tool("plugin_fixture", json.parseToJsonElement("""{
          "type":"object","properties":{
            "freeform":true,"blocked":false,"flags":{"type":"array","items":true},
            "payload":{"type":"object","default":{"type":"array","${'$'}ref":"literal","required":null}}
          }
        }""")),
    )

    private fun tool(name: String, schema: JsonElement): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", "Transport regression fixture")
            put("parameters", schema)
        })
    }

    private fun request(tools: JsonArray): String = buildJsonObject {
        put("model", MODEL)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "fixture") })
        })
        put("tools", tools)
    }.toString()

    private fun sendBothModes(fixture: Fixture, tools: JsonArray): List<CapturedRequest> {
        runBlocking {
            withTimeout(10_000) {
                listOf(false, true).forEach { streaming -> send(fixture, request(tools), streaming) }
            }
        }
        assertEquals(2, fixture.requests.size)
        fixture.handlerFailure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        return fixture.requests.toList().also { requests ->
            requests.forEachIndexed { index, captured ->
                assertEquals("POST", captured.method)
                val expectedPath = when (fixture.provider.wireProtocol) {
                    ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> "/v1/chat/completions"
                    ProviderWireProtocol.OPENAI_RESPONSES -> "/v1/responses"
                    ProviderWireProtocol.ANTHROPIC_MESSAGES -> "/v1/messages"
                    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT ->
                        "/v1/models/$MODEL:" + if (index == 0) "generateContent" else "streamGenerateContent?alt=sse"
                }
                assertEquals(expectedPath, captured.path)
                if (fixture.provider.wireProtocol == ProviderWireProtocol.OPENAI_RESPONSES) {
                    assertTrue("Responses requests must send native input", captured.body["input"] is JsonArray)
                    assertFalse("messages" in captured.body)
                    assertFalse("stream_options" in captured.body)
                }
                if (fixture.provider.wireProtocol != ProviderWireProtocol.GOOGLE_GENERATE_CONTENT && index == 1) {
                    assertEquals(JsonPrimitive(true), captured.body["stream"])
                }
            }
        }
    }

    private suspend fun send(fixture: Fixture, body: String, streaming: Boolean) {
        val provider = fixture.provider
        val result = if (streaming) {
            val events = mutableListOf<LlmStreamEvent>()
            fixture.client.chatCompletionsStream(provider.id, provider.baseUrl, "", MODEL, body) { events += it }
                .also {
                    assertEquals(LlmStreamMode.NATIVE, it.mode)
                    assertEquals("ok", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { event -> event.text })
                }.responseBody
        } else fixture.client.chatCompletions(provider.id, provider.baseUrl, "", MODEL, body)
        assertEquals("ok", json.parseToJsonElement(result).jsonObject.getValue("choices").jsonArray[0]
            .jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
    }

    private fun withFixture(protocol: ProviderWireProtocol, block: (Fixture) -> Unit) {
        Fixture(protocol).use { fixture ->

            val field = ProviderRegistry::class.java.getDeclaredField("configured").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val configured = field.get(ProviderRegistry) as AtomicReference<List<AiProvider>>
            synchronized(ProviderRegistry) {
                ProviderRegistry.installConfigured(configured.get() + fixture.provider)
                try {
                    ProviderModelCertificationRegistry.certify(fixture.provider, listOf(MODEL))
                    block(fixture)
                } finally {
                    ProviderModelCertificationRegistry.revoke(fixture.provider.id)
                    // Remove only our unique fixture; retain other providers and concurrent updates.
                    configured.updateAndGet { entries -> entries.filterNot { it.id == fixture.provider.id } }
                }
            }
        }
    }

    private data class CapturedRequest(val method: String, val path: String, val body: JsonObject)

    private class Fixture(protocol: ProviderWireProtocol) : AutoCloseable {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val requestTimes = CopyOnWriteArrayList<Long>()
        val handlerFailure = AtomicReference<Throwable?>()
        @Volatile var streamResponseOverride: String? = null
        @Volatile var httpFailuresRemaining: Int = 0
        @Volatile var httpFailureStatus: Int = 503
        @Volatile var httpFailureBody: String = "overloaded"
        @Volatile var httpRetryAfter: String? = null
        @Volatile var httpRetryAfterMs: String? = null
        @Volatile var disconnectWithoutResponse: Boolean = false
        val client = LlmClient()
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "droide-provider-schema-fixture").apply { isDaemon = true }
        }
        private val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val provider = AiProvider(
            id = "test.schema.${UUID.randomUUID()}", name = "Schema transport fixture",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = MODEL,
            free = true, needsKey = false, helpUrl = "", note = "test",
            wireProtocol = protocol, authScheme = ProviderAuthScheme.NONE,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
                    requests += CapturedRequest(exchange.requestMethod, exchange.requestURI.toString(), body)
                    requestTimes += System.nanoTime()
                    if (disconnectWithoutResponse) return@createContext
                    val streaming = body["stream"] == JsonPrimitive(true) || exchange.requestURI.rawQuery == "alt=sse"
                    if (httpFailuresRemaining > 0) {
                        httpFailuresRemaining--
                        val errorBody = httpFailureBody.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        httpRetryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
                        httpRetryAfterMs?.let { exchange.responseHeaders.set("retry-after-ms", it) }
                        exchange.sendResponseHeaders(httpFailureStatus, errorBody.size.toLong())
                        exchange.responseBody.use { it.write(errorBody) }
                    } else {
                        val response = (if (streaming) streamResponseOverride ?: response(protocol, true)
                            else response(protocol, false)).toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", if (streaming) "text/event-stream" else "application/json")
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        exchange.responseBody.use { it.write(response) }
                    }
                } catch (failure: Throwable) {
                    handlerFailure.set(failure)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback fixture executor did not stop" }
        }

        private fun response(protocol: ProviderWireProtocol, streaming: Boolean): String = when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> if (streaming) {
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            } else """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            ProviderWireProtocol.OPENAI_RESPONSES -> {
                val output = """{"id":"msg_fixture","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"ok","annotations":[]}]}"""
                val completed = """{"id":"resp_fixture","object":"response","model":"$MODEL","status":"completed","output":[$output]}"""
                if (streaming) {
                    "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_fixture\",\"model\":\"$MODEL\",\"status\":\"in_progress\",\"output\":[]}}\n\n" +
                        "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}\n\n" +
                        "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"ok\"}\n\n" +
                        "event: response.output_text.done\ndata: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"text\":\"ok\"}\n\n" +
                        "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$output}\n\n" +
                        "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$completed}\n\n"
                } else completed
            }
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> if (streaming) {
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"fixture\",\"model\":\"$MODEL\",\"content\":[]}}\n\n" +
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n" +
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            } else """{"id":"fixture","model":"$MODEL","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> {
                val body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
                if (streaming) "data: $body\n\n" else body
            }
        }
    }

    private companion object {
        const val MODEL = "transport-fixture"
    }
}

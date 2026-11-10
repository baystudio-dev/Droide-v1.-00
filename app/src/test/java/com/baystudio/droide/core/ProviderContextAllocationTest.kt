package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderContextAllocationTest {
    private val providerId = "budget.fixture"
    private val model = "budget-model"

    @After fun clear() {
        ModelCapabilityRegistry.revokeRuntime(providerId)
        ModelCapabilityRegistry.installCatalog(emptyList())
        ProviderRegistry.installConfigured(emptyList())
    }

    private fun install(context: Int? = null, input: Int? = null, output: Int? = null) {
        ModelCapabilityRegistry.revokeRuntime(providerId)
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(
            providerId, model, model, null, null, true, null, null, emptySet(), emptySet(), emptySet(),
            context, output, ModelCapabilitySource.RUNTIME_PROVIDER, inputTokens = input,
        ))
    }

    private fun body(text: String = "hello", extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { add(message("user", text)) })
        extra.forEach { (key, value) -> put(key, value) }
    }
    private fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }

    @Test fun nestedCeilingsTakeTheTightestValueWithoutConflatingInputAndContext() {
        val metadata = Json.parseToJsonElement("""{
          "context_length":131072,"inputTokenLimit":65536,"outputTokenLimit":8192,
          "top_provider":{"context_length":32768,"max_completion_tokens":2048},
          "limit":{"context":65536,"input":16384,"output":4096}
        }""").jsonObject
        assertEquals(32768, ModelContextRegistry.contextLimitFromModel(metadata))
        assertEquals(16384, ModelContextRegistry.inputLimitFromModel(metadata))
        assertEquals(2048, ModelContextRegistry.outputLimitFromModel(metadata))
        val native = Json.parseToJsonElement("""{"inputTokenLimit":4096,"outputTokenLimit":8192}""").jsonObject
        assertNull(ModelContextRegistry.contextLimitFromModel(native))
        assertEquals(4096, ModelContextRegistry.inputLimitFromModel(native))
        val provider = AiProvider(providerId, "Fixture", "https://example.com/v1", model, false, true, "", "")
        val capability = ProviderRuntimeCapabilities.parse(provider, ProviderModelCatalog.Entry(model, metadata))!!
        assertEquals(32768, capability.contextTokens)
        assertEquals(16384, capability.inputTokens)
        assertEquals(2048, capability.outputTokens)
    }

    @Test fun modelsDevInputCeilingSurvivesCatalogRefreshAndPartialRuntimeMetadata() {
        val snapshot = ModelsDevCatalog.parse("""{"budget.fixture":{"npm":"@ai-sdk/openai-compatible",
          "api":"https://example.com/v1","models":{"budget-model":{"limit":{"context":32768,"input":4096,"output":2048}}}}}""")
        ModelCapabilityRegistry.installCatalog(snapshot.capabilities)
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(providerId, model, model, null, null, true, null, null,
            emptySet(), emptySet(), emptySet(), null, null, ModelCapabilitySource.RUNTIME_PROVIDER))
        assertEquals(4096, ModelContextRegistry.resolveInput(providerId, model))
        assertEquals(32768, ModelContextRegistry.resolve(providerId, model))
        assertEquals(4096, ContextBudget.effectiveInputTokens(providerId, model, 2048))
        ModelCapabilityRegistry.installCatalog(snapshot.capabilities.map { it.copy(inputTokens = 2048) })
        assertEquals(2048, ModelContextRegistry.resolveInput(providerId, model))
    }

    @Test fun invalidRefreshClearsRememberedLimitAndSmallWindowsAreNeverInflated() {
        ModelContextRegistry.remember(providerId, model, 2048)
        assertEquals(2048, ModelContextRegistry.resolve(providerId, model))
        ModelContextRegistry.remember(providerId, model, 0)
        assertNull(ModelContextRegistry.resolve(providerId, model))
        ModelContextRegistry.remember(providerId, model, 4096)
        install(input = 4096, output = 8192)
        assertNull(ModelContextRegistry.resolve(providerId, model))
        assertEquals(4096, ModelContextRegistry.resolveInput(providerId, model))
    }

    @Test fun inputOnlyCeilingDoesNotPayForOutputTwiceAndSharedCeilingDoes() {
        install(input = 4096, output = 16384)
        val prepared = ProviderRequestBudget.prepare(body("x".repeat(10000)), providerId, model)
        assertEquals(16384, ProviderRequestBudget.explicitOutputTokens(prepared))
        assertEquals(4096, ContextBudget.effectiveInputTokens(providerId, model, 16384))
        install(context = 8192, input = 3072, output = 8192)
        assertEquals(3072, ContextBudget.effectiveInputTokens(providerId, model, 1024))
        assertEquals(192, ContextBudget.effectiveInputTokens(providerId, model, 8000))
    }

    @Test fun mandatoryPromptAndToolsProduceHonestPreflightFailureWithoutChangingInput() {
        install(context = 4096, output = 2048)
        val tools = buildJsonArray { add(buildJsonObject {
            put("type", "function"); put("function", buildJsonObject {
                put("name", "required_tool"); put("description", "d".repeat(8000))
                put("parameters", buildJsonObject { put("type", "object") })
            })
        }) }
        val root = body("KEEP_ALL_USER_TEXT", mapOf(
            "tools" to tools,
            "messages" to JsonArray(listOf(message("system", "s".repeat(8000)), message("user", "KEEP_ALL_USER_TEXT"))),
        ))
        val before = root.toString()
        val error = assertThrows(ProviderContextOverflowException::class.java) { ProviderRequestBudget.prepare(root, providerId, model) }
        assertTrue(error.message.orEmpty().contains("Mandatory instructions"))
        assertTrue(error.message.orEmpty().contains("approximate"))
        assertEquals(before, root.toString())
        assertEquals(4096, ModelContextRegistry.resolve(providerId, model))
        assertEquals(1024, ProviderRequestBudget.defaultOutputTokens(providerId, model))
    }

    @Test fun allAliasesAreValidatedBeforeAnyNativeTranslation() {
        install(output = 1024)
        for (field in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            assertEquals(512, ProviderRequestBudget.explicitOutputTokens(ProviderRequestBudget.prepare(
                body(extra = mapOf(field to JsonPrimitive(512))), providerId, model)))
            for (bad in listOf<JsonElement>(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("512"), JsonPrimitive(0.5), JsonNull, JsonPrimitive(1025))) {
                assertThrows(IllegalArgumentException::class.java) { ProviderRequestBudget.prepare(body(extra = mapOf(field to bad)), providerId, model) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ProviderRequestBudget.prepare(body(extra = mapOf(
            "max_tokens" to JsonPrimitive(256), "max_output_tokens" to JsonPrimitive(512))), providerId, model) }
    }

    @Test fun absentCapIsInsertedAndAllFourProtocolsKeepTheSameExplicitReservation() {
        install(context = 16384, output = 2048)
        for (protocol in ProviderWireProtocol.entries) {
            val wireModel = if (protocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) "gemini-2.5-flash" else model
            val provider = AiProvider(providerId, "Fixture", "https://example.com/v1", wireModel, false, true, "", "", wireProtocol = protocol)
            ProviderRegistry.installConfigured(listOf(provider))
            val original = JsonObject(body() + ("model" to JsonPrimitive(wireModel)))
            if (wireModel != model) ModelCapabilityRegistry.installRuntime(ModelCapabilities(
                providerId, wireModel, wireModel, null, null, true, null, null, emptySet(), emptySet(), emptySet(),
                16384, 2048, ModelCapabilitySource.RUNTIME_PROVIDER,
            ))
            val prepared = ProviderRequestBudget.prepare(original, providerId, wireModel)
            assertEquals(2048, ProviderRequestBudget.explicitOutputTokens(prepared))
            val explicit = ProviderRequestBudget.withOutputLimit(JsonObject(original + ("max_completion_tokens" to JsonPrimitive(512))), providerId, wireModel)
            val wire = when (protocol) {
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> Json.parseToJsonElement(ProviderToolSchema.openAiRequest(explicit.toString(), Json)).jsonObject
                ProviderWireProtocol.OPENAI_RESPONSES -> Json.parseToJsonElement(OpenAiResponsesAdapter.encodeRequest(explicit.toString(), false, Json)).jsonObject
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(explicit.toString(), false, Json, providerId)).jsonObject
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(explicit.toString(), Json, providerId)).jsonObject
            }
            val cap = when (protocol) {
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> wire["generationConfig"]!!.jsonObject["maxOutputTokens"]
                ProviderWireProtocol.OPENAI_RESPONSES -> wire["max_output_tokens"]
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> wire["max_tokens"]
                else -> wire["max_completion_tokens"] ?: wire["max_tokens"]
            }
            assertEquals("$protocol", JsonPrimitive(512), cap)
        }
        assertEquals(JsonPrimitive(8192), ProviderRequestBudget.withOutputLimit(body(), "openai", "o3")["max_output_tokens"])
    }

    @Test fun googleDerivedThinkingFitsTheSameOutputReservation() {
        val googleModel = "gemini-2.5-flash"
        val provider = AiProvider(providerId, "Fixture", "https://example.com/v1", googleModel, false, true, "", "",
            wireProtocol = ProviderWireProtocol.GOOGLE_GENERATE_CONTENT)
        ProviderRegistry.installConfigured(listOf(provider))
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(providerId, googleModel, googleModel, null, true, true, null, null,
            emptySet(), emptySet(), emptySet(), 131072, 32768, ModelCapabilitySource.RUNTIME_PROVIDER))
        val request = JsonObject(body(extra = mapOf("reasoning_effort" to JsonPrimitive("high"))) + ("model" to JsonPrimitive(googleModel)))
        val prepared = ProviderRequestBudget.prepare(request, providerId, googleModel)
        assertEquals(32768, ProviderRequestBudget.explicitOutputTokens(prepared))
        val wire = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(prepared.toString(), Json, providerId)).jsonObject
        val generation = wire["generationConfig"]!!.jsonObject
        assertEquals(JsonPrimitive(32768), generation["maxOutputTokens"])
        assertEquals(JsonPrimitive(24576), generation["thinkingConfig"]!!.jsonObject["thinkingBudget"])
    }

    @Test fun agentBodyIncludesOutputCapWithoutStrippingMandatoryContent() {
        install(context = 16384, output = 1024)
        val user = message("user", "u".repeat(2000) + "KEEP_TAIL")
        val result = AgentRequestSupport.build(AgentConfig(providerId = providerId, model = model), "MANDATORY", listOf(user), null)
        assertEquals(JsonPrimitive(1024), result["max_tokens"])
        assertEquals(user, result["messages"]!!.jsonArray.last())
        assertEquals(JsonPrimitive("MANDATORY"), result["messages"]!!.jsonArray.first().jsonObject["content"])
    }
}

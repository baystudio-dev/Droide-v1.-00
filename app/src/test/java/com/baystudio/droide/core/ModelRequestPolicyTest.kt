package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ModelRequestPolicyTest {
    @After fun resetMetadata() {
        ModelCapabilityRegistry.installCatalog(emptyList())
        ProviderRegistry.installDiscovered(emptyList())
    }

    private fun capability(provider: String, model: String, efforts: Set<String> = emptySet(), output: Int? = null) = ModelCapabilities(
        providerId = provider, modelId = model, displayName = model,
        attachment = null, reasoning = true, toolCall = true, structuredOutput = null,
        temperature = null, inputModalities = emptySet(), outputModalities = emptySet(),
        reasoningEfforts = efforts, contextTokens = null, outputTokens = output,
        source = ModelCapabilitySource.MODELS_DEV,
    )

    private fun request(model: String, extra: JsonObject = JsonObject(emptyMap())) = JsonObject(buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hi") }) })
    } + extra).toString()

    private fun anthropic(model: String, extra: String = "{}", provider: String = "anthropic"): JsonObject =
        Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(
            request(model, Json.parseToJsonElement(extra).jsonObject), false, Json, provider,
        )).jsonObject

    @Test fun datedClaude40SnapshotsKeepManualThinkingAndOnlyLowHighPresets() {
        listOf("claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-sonnet-4", "claude-opus-4-latest").forEach { model ->
            assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ReasoningSupport.supported("anthropic", model))
            assertEquals(ReasoningWire.AnthropicManual(8192), ReasoningSupport.wire("anthropic", model, ReasoningEffort.HIGH))
            val extra = buildJsonObject { ReasoningSupport.apply(this, "anthropic", model, ReasoningEffort.HIGH) }
            val wire = anthropic(model, extra.toString())
            assertEquals(JsonPrimitive("enabled"), wire["thinking"]!!.jsonObject["type"])
            assertFalse("output_config" in wire)
        }
    }

    @Test fun reviewedManualAndAdaptiveFamiliesRemainDistinct() {
        listOf("claude-3-7-sonnet-20250219", "claude-sonnet-4-5-20250929", "claude-haiku-4-5-20251001").forEach { model ->
            assertTrue(ReasoningSupport.wire("anthropic", model, ReasoningEffort.HIGH) is ReasoningWire.AnthropicManual)
        }
        listOf("claude-sonnet-4-6", "claude-opus-4-8", "claude-opus-5").forEach { model ->
            assertTrue(ReasoningSupport.wire("anthropic", model, ReasoningEffort.HIGH) is ReasoningWire.AnthropicAdaptive)
        }
    }

    @Test fun datedGpt5SnapshotDoesNotAcquireFutureMinorVersionEfforts() {
        listOf("gpt-5", "gpt-5-2025-08-07", "openai/gpt-5-2025-08-07").forEach { model ->
            assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ReasoningSupport.supported("openrouter", model))
            assertNull(ReasoningSupport.wire("openrouter", model, ReasoningEffort.XTRA_HIGH))
        }
    }

    @Test fun knownProFamiliesExposeOnlyTheirReviewedDroideTiers() {
        assertEquals(listOf(ReasoningEffort.HIGH), ReasoningSupport.supported("openai", "gpt-5-pro"))
        assertEquals(listOf(ReasoningEffort.HIGH), ReasoningSupport.supported("openai", "gpt-5-pro-2025-10-06"))
        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH), ReasoningSupport.supported("openai", "gpt-5.2-pro"))
        assertNull(ReasoningSupport.wire("openai", "gpt-5.2-pro", ReasoningEffort.LOW))
    }

    @Test fun nonemptyUnrepresentableEffortMetadataNeverEnablesFallbackTiers() {
        listOf(setOf("medium"), setOf("minimal"), setOf("custom")).forEach { efforts ->
            ModelCapabilityRegistry.installCatalog(listOf(capability("openai", "gpt-5.2-pro", efforts)))
            assertTrue(ReasoningSupport.supported("openai", "gpt-5.2-pro").isEmpty())
            assertNull(ReasoningSupport.defaultFor("openai", "gpt-5.2-pro"))
        }
    }

    @Test fun authoritativeMetadataOverridesFallbackEffortsAndCanDescribeUnknownGatewayEfforts() {
        ModelCapabilityRegistry.installCatalog(listOf(
            capability("openai", "gpt-5-pro", setOf("low", "high", "xhigh")),
            capability("openrouter", "vendor/new-reasoner", setOf("high", "xhigh")),
        ))
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH), ReasoningSupport.supported("openai", "gpt-5-pro"))
        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH), ReasoningSupport.supported("openrouter", "vendor/new-reasoner"))
    }

    @Test fun unknownAndNonReasoningGatewayModelNamesDoNotInventAnEffortParameter() {
        listOf("unknown/plain-chat", "anthropic/claude-3-haiku", "google/gemini-2.0-flash", "google/gemini-mystery").forEach { model ->
            assertTrue(ReasoningSupport.supported("openrouter", model).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                buildJsonObject { ReasoningSupport.apply(this, "openrouter", model, ReasoningEffort.HIGH) }
            }
            assertTrue(buildJsonObject { ReasoningSupport.apply(this, "openrouter", model, null) }.isEmpty())
        }
        ModelCapabilityRegistry.installCatalog(listOf(capability("openrouter", "vendor/new-reasoner")))
        assertNull(ReasoningSupport.defaultFor("openrouter", "vendor/new-reasoner"))
    }

    @Test fun aReasoningBooleanOnDiscoveredProviderDoesNotInventEffortSupport() {
        ProviderRegistry.installDiscovered(listOf(AiProvider(
            id = "xai", name = "xAI", baseUrl = "https://api.x.ai/v1", model = "",
            free = false, needsKey = true, helpUrl = "", note = "fixture",
            catalogPackage = "@ai-sdk/xai", catalogDiscovered = true,
        )))
        ModelCapabilityRegistry.installCatalog(listOf(capability("xai", "grok-4-0709")))
        assertTrue(ReasoningSupport.supported("xai", "grok-4-0709").isEmpty())
    }

    @Test fun implicitAnthropicMaximumUsesKnownNativeAndCustomProviderOutputCaps() {
        ModelCapabilityRegistry.installCatalog(listOf(
            capability("anthropic", "claude-3-haiku-20240307", output = 4096),
            capability("custom-native", "claude-sonnet-4-5", output = 2048),
        ))
        assertEquals(JsonPrimitive(4096), anthropic("claude-3-haiku-20240307")["max_tokens"])
        assertEquals(JsonPrimitive(2048), anthropic("claude-sonnet-4-5", provider = "custom-native")["max_tokens"])
        assertEquals(JsonPrimitive(8192), anthropic("claude-sonnet-4-5")["max_tokens"])
    }

    @Test fun explicitMaximumAboveTheKnownCapFailsWithModelAndLimit() {
        val model = "claude-sonnet-4-5"
        ModelCapabilityRegistry.installCatalog(listOf(capability("anthropic", model, output = 4096)))
        val error = assertThrows(IllegalArgumentException::class.java) { anthropic(model, """{"max_tokens":8192}""") }
        assertTrue(error.message.orEmpty().contains(model))
        assertTrue(error.message.orEmpty().contains("4096"))
    }

    @Test fun explicitMaximumAndManualBudgetArePreservedWhenCompatible() {
        val wire = anthropic("claude-sonnet-4-5", """{"max_tokens":9000,"thinking":{"type":"enabled","budget_tokens":8192}}""")
        assertEquals(JsonPrimitive(9000), wire["max_tokens"])
        assertEquals(JsonPrimitive(8192), wire["thinking"]!!.jsonObject["budget_tokens"])
        assertThrows(IllegalArgumentException::class.java) {
            anthropic("claude-sonnet-4-5", """{"max_tokens":8192,"thinking":{"type":"enabled","budget_tokens":8192}}""")
        }
    }

    @Test fun derivedManualPresetFitsTheCapWhileExplicitOverCapBudgetFails() {
        val model = "claude-sonnet-4-5"
        ModelCapabilityRegistry.installCatalog(listOf(capability("anthropic", model, output = 4096)))
        val extra = buildJsonObject { ReasoningSupport.apply(this, "anthropic", model, ReasoningEffort.HIGH) }
        val wire = anthropic(model, extra.toString())
        val budget = wire["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int
        assertEquals(3072, budget)
        assertEquals(JsonPrimitive(4096), wire["max_tokens"])
        assertThrows(IllegalArgumentException::class.java) {
            anthropic(model, """{"thinking":{"type":"enabled","budget_tokens":8192}}""")
        }
        ModelCapabilityRegistry.installCatalog(listOf(capability("anthropic", model, output = 1024)))
        assertNull(ReasoningSupport.defaultFor("anthropic", model))
    }

    @Test fun malformedTokenScalarsAndOverflowFailLocally() {
        listOf("0", "-1", "\"8192\"", "null", "8192.5", "[]").forEach { scalar ->
            assertThrows(IllegalArgumentException::class.java) { anthropic("claude-sonnet-4-5", "{\"max_tokens\":$scalar}") }
        }
        listOf("\"8192\"", "1023", "2147483647", "[]").forEach { scalar ->
            assertThrows(IllegalArgumentException::class.java) {
                anthropic("claude-sonnet-4-5", "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":$scalar}}")
            }
        }
    }

    @Test fun incompatibleThinkingModesFailBeforeTheNativeRequest() {
        assertThrows(IllegalArgumentException::class.java) { anthropic("claude-sonnet-4-20250514", """{"thinking":{"type":"adaptive"}}""") }
        assertThrows(IllegalArgumentException::class.java) { anthropic("claude-opus-4-6", """{"thinking":{"type":"adaptive","budget_tokens":8192}}""") }
        assertThrows(IllegalArgumentException::class.java) { anthropic("claude-3-haiku-20240307", """{"thinking":{"type":"enabled","budget_tokens":8192}}""") }
    }

    @Test fun directAnthropicEncoderRejectsMalformedToolsAndNormalizesPluginArrays() {
        listOf("{}", "[null]", "[{\"type\":\"function\"}]", "[{\"type\":\"function\",\"function\":{\"name\":123}}]").forEach { tools ->
            assertThrows(IllegalArgumentException::class.java) { anthropic("claude-sonnet-4-5", "{\"tools\":$tools}") }
        }
        val wire = anthropic("claude-sonnet-4-5", """{"tools":[{"type":"function","function":{"name":"mcp_fixture","parameters":{"type":"object","properties":{"rows":{"type":"array"}}}}}]}""")
        val rows = wire["tools"]!!.jsonArray[0].jsonObject["input_schema"]!!.jsonObject["properties"]!!.jsonObject["rows"]!!.jsonObject
        assertEquals(JsonObject(emptyMap()), rows["items"])
    }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GoogleNativeRequestValidationTest {
    @After fun resetCatalog() {
        ModelCapabilityRegistry.installCatalog(emptyList())
    }

    private fun body(model: String, effort: String? = null, tools: JsonElement? = null): String = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hi") }) })
        effort?.let { put("reasoning_effort", it) }
        tools?.let { put("tools", it) }
    }.toString()

    @Test fun speechModelsNeverOfferAgentReasoningEvenWithoutModalityMetadata() {
        val provider = ProviderRegistry.byId("gemini")
        for (model in listOf("gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts", "gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")) {
            assertFalse(model, ProviderModelRequestPolicy.isConversational(provider, model))
            assertTrue(model, ReasoningSupport.supported("gemini", model).isEmpty())
            assertNull(ReasoningSupport.googleNativeThinkingMode(model))
            for (effort in listOf(null, "low", "high")) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    GoogleGenerateContentAdapter.encodeRequest(body(model, effort), Json)
                }
                assertTrue(error.message.orEmpty().contains("speech"))
            }
        }
    }

    @Test fun catalogCannotWidenTtsToTextChatOrEnableThinkingLevel() {
        val model = "gemini-3.8-flash-lite-tts"
        ModelCapabilityRegistry.installCatalog(listOf(ModelCapabilities(
            "gemini", model, model, true, true, true, true, true,
            setOf("text"), setOf("text"), setOf("low", "high"), 1_048_576, 65_536,
            ModelCapabilitySource.MODELS_DEV,
        )))
        assertTrue(ReasoningSupport.supported("gemini", model).isEmpty())
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderModelRequestPolicy.requireConversational(ProviderRegistry.byId("gemini"), model)
        }
        assertTrue(error.message.orEmpty().contains("TTS"))
    }

    @Test fun routedTtsNamesAreRejectedButNormalTextModelsRemainConversational() {
        for (id in listOf("gemini", "openrouter", "openai")) {
            val provider = ProviderRegistry.byId(id)
            for (model in listOf("google/gemini-3.8-flash-lite-tts", "models/gemini-3.1-flash-tts-preview", "GEMINI-2.5-PRO-PREVIEW-TTS")) {
                assertFalse(model, ProviderModelRequestPolicy.isConversational(provider, model))
            }
            for (model in listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-pro")) {
                assertTrue(model, ProviderModelRequestPolicy.isConversational(provider, model))
            }
        }
    }

    @Test fun olderGeminiWithoutCatalogMetadataDoesNotDefaultToUnsupportedThinkingLevel() {
        ModelCapabilityRegistry.installCatalog(emptyList())
        listOf("gemini-1.5-pro", "gemini-2.0-flash", "gemini-2.0-flash-lite").forEach { model ->
            assertTrue(ReasoningSupport.supported("gemini", model).isEmpty())
            val default = ReasoningSupport.defaultFor("gemini", model)
            assertNull(default)
            val request = buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hi") }) })
                ReasoningSupport.apply(this, "gemini", model, default)
            }
            assertFalse("reasoning_effort" in request)
            assertFalse("generationConfig" in Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(request.toString(), Json)).jsonObject)
        }
    }

    @Test fun explicitThinkingOnOlderGeminiFailsLocallyWithTheModelName() {
        listOf("gemini-1.5-pro", "gemini-2.0-flash").forEach { model ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                GoogleGenerateContentAdapter.encodeRequest(body(model, "high"), Json)
            }
            assertTrue(error.message.orEmpty().contains(model))
        }
    }

    @Test fun unknownAliasDoesNotGuessItsNativeThinkingContract() {
        assertNull(ReasoningSupport.googleNativeThinkingMode("gemini-flash-latest"))
        assertNull(ReasoningSupport.defaultFor("gemini", "gemini-flash-latest"))
        assertThrows(IllegalArgumentException::class.java) {
            GoogleGenerateContentAdapter.encodeRequest(body("gemini-flash-latest", "high"), Json)
        }
    }

    @Test fun catalogMetadataCannotInventThinkingLevelSupportOnGemini20() {
        ModelCapabilityRegistry.installCatalog(listOf(ModelCapabilities(
            providerId = "gemini", modelId = "gemini-2.0-flash", displayName = "fixture",
            attachment = null, reasoning = true, toolCall = true, structuredOutput = null,
            temperature = null, inputModalities = emptySet(), outputModalities = emptySet(),
            reasoningEfforts = setOf("low", "high"), contextTokens = null, outputTokens = null,
            source = ModelCapabilitySource.MODELS_DEV,
        )))
        assertTrue(ReasoningSupport.supported("gemini", "gemini-2.0-flash").isEmpty())
    }

    @Test fun gemini25BudgetAndGemini3PlusLevelRemainSupportedForBothTiers() {
        listOf("low", "high").forEach { effort ->
            val budget = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body("gemini-2.5-flash", effort), Json))
                .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
            assertEquals(JsonPrimitive(if (effort == "low") 1024 else 24576), budget["thinkingBudget"])
            assertFalse("thinkingLevel" in budget)
            listOf("gemini-3-flash-preview", "gemini-3.5-flash", "gemini-4-pro").forEach { model ->
                val level = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body(model, effort), Json))
                    .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
                assertEquals(JsonPrimitive(effort.uppercase()), level["thinkingLevel"])
                assertFalse("thinkingBudget" in level)
            }
        }
    }

    @Test fun directNativeEncoderRejectsMalformedToolsInsteadOfDroppingThem() {
        listOf(
            "{}",
            "[null]",
            "[{\"type\":\"function\"}]",
            "[{\"type\":\"function\",\"function\":{\"name\":123}}]",
            "[{\"type\":\"function\",\"function\":{\"name\":\"\"}}]",
            "[{\"type\":\"other\",\"function\":{\"name\":\"read_file\"}}]",
        ).forEach { tools ->
            assertThrows(IllegalArgumentException::class.java) {
                GoogleGenerateContentAdapter.encodeRequest(body("gemini-3.5-flash", tools = Json.parseToJsonElement(tools)), Json)
            }
        }
    }

    @Test fun directNativeEncoderNormalizesPluginArrayThroughTheSharedBoundary() {
        val tools = Json.parseToJsonElement("""[{"type":"function","function":{"name":"mcp_fixture","parameters":{"type":"object","properties":{"rows":{"type":"array"}}}}}]""")
        val encoded = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body("gemini-3.5-flash", tools = tools), Json)).jsonObject
        val declaration = encoded["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertFalse("parameters" in declaration)
        val rows = declaration["parametersJsonSchema"]!!.jsonObject["properties"]!!.jsonObject["rows"]!!.jsonObject
        assertEquals(JsonObject(emptyMap()), rows["items"])
    }
}

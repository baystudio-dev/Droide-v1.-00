package com.baystudio.droide.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class ProviderModelRoutingTest {
    @After fun reset() {
        ModelCapabilityRegistry.installCatalog(emptyList())
        ModelCapabilityRegistry.revokeRuntime("routing_fixture")
    }

    private fun capability(model: String, endpoints: Set<String> = emptySet(), output: Set<String> = emptySet()) = ModelCapabilities(
        "routing_fixture", model, model, null, null, null, null, null, emptySet(), output,
        emptySet(), null, null, ModelCapabilitySource.MODELS_DEV, supportedEndpoints = endpoints,
    )

    @Test fun nativeResponsesFamiliesAndSnapshotsHaveAUsableEndpoint() {
        val openai = ProviderRegistry.byId("openai")
        listOf("gpt-5-pro", "gpt-5.2-pro", "gpt-5.4-pro", "gpt-5-pro-2025-10-06",
            "gpt-5-codex", "gpt-5.1-codex-max", "gpt-5.3-codex-spark", "gpt-5.4", "gpt-6.1-terra", "o1-pro", "o3-pro-2025-06-10").forEach {
            assertEquals(it, ProviderWireProtocol.OPENAI_RESPONSES, ProviderTransportV2.spec(openai, it).protocol)
        }
        listOf("gpt-4o", "gpt-4.1", "o1-mini", "o1-preview").forEach {
            assertEquals(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderTransportV2.spec(openai, it).protocol)
        }
    }

    @Test fun routedNamesCannotChangeGatewayAuthorityOrNativeGoogleAnthropic() {
        listOf("openrouter", "groq", "deepseek").forEach { provider ->
            assertEquals(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderTransportV2.spec(ProviderRegistry.byId(provider), "openai/gpt-5.2-pro").protocol)
        }
        assertEquals(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT, ProviderTransportV2.spec(ProviderRegistry.byId("gemini"), "gpt-5-pro").protocol)
        assertEquals(ProviderWireProtocol.ANTHROPIC_MESSAGES, ProviderTransportV2.spec(ProviderRegistry.byId("anthropic"), "gpt-5-pro").protocol)
    }

    @Test fun explicitResponsesEndpointCanSelectAConfiguredCompatibleProvider() {
        val provider = ProviderRegistry.byId("openai").copy(id = "routing_fixture")
        ModelCapabilityRegistry.installCatalog(listOf(capability("future", setOf("/v1/responses"))))
        assertEquals(ProviderWireProtocol.OPENAI_RESPONSES, ProviderTransportV2.spec(provider, "future").protocol)
        ModelCapabilityRegistry.installCatalog(listOf(capability("future", setOf("responses", "chat/completions"))))
        assertEquals(ProviderWireProtocol.OPENAI_RESPONSES, ProviderTransportV2.spec(provider, "future").protocol)
    }

    @Test fun nonConversationalModelsAreNotSilentlyTreatedAsAgentChat() {
        val openai = ProviderRegistry.byId("openai")
        listOf("text-embedding-3-small", "whisper-1", "tts-1", "dall-e-3", "gpt-image-1", "gpt-4o-mini-transcribe", "davinci-002").forEach {
            assertFalse(ProviderModelRequestPolicy.isConversational(openai, it))
            assertThrows(IllegalArgumentException::class.java) { ProviderModelRequestPolicy.requireConversational(openai, it) }
        }
        assertTrue(ProviderModelRequestPolicy.isConversational(openai, "gpt-4o"))
        val provider = openai.copy(id = "routing_fixture")
        ModelCapabilityRegistry.installCatalog(listOf(
            capability("image", output = setOf("image")), capability("embedding", endpoints = setOf("embeddings")),
            capability("multimodal", output = setOf("text", "image")),
        ))
        assertFalse(ProviderModelRequestPolicy.isConversational(provider, "image"))
        assertFalse(ProviderModelRequestPolicy.isConversational(provider, "embedding"))
        assertTrue(ProviderModelRequestPolicy.isConversational(provider, "multimodal"))
    }

    @Test fun olderO1ModelsRemainTextOnlyAndDoNotInventReasoningControls() {
        for (model in listOf("o1-mini", "o1-mini-2024-09-12", "o1-preview-2024-09-12")) {
            assertFalse(ModelCapabilityRegistry.allowsToolUse("openai", model))
            assertTrue(ReasoningSupport.supported("openai", model).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                ProviderModelRequestPolicy.requireToolSupport(ProviderRegistry.byId("openai"), model, true)
            }
        }
    }

    @Test fun incompatibleAdvertisedEndpointsFailBeforeTransport() {
        val chat = ProviderRegistry.byId("openai").copy(id = "routing_fixture")
        val anthropic = ProviderRegistry.byId("anthropic").copy(id = "routing_fixture")
        val google = ProviderRegistry.byId("gemini").copy(id = "routing_fixture")
        ModelCapabilityRegistry.installCatalog(listOf(capability("model", setOf("messages"))))
        assertFalse(ProviderModelRequestPolicy.isConversational(chat, "model"))
        assertTrue(ProviderModelRequestPolicy.isConversational(anthropic, "model"))
        assertFalse(ProviderModelRequestPolicy.isConversational(google, "model"))
        ModelCapabilityRegistry.installCatalog(listOf(capability("model", setOf("responses"))))
        assertTrue(ProviderModelRequestPolicy.isConversational(chat, "model"))
        assertFalse(ProviderModelRequestPolicy.isConversational(anthropic, "model"))
        assertFalse(ProviderModelRequestPolicy.isConversational(google, "model"))
    }

    @Test fun explicitChatMetadataTakesPrecedenceOverNativeFamilyFallback() {
        ModelCapabilityRegistry.installCatalog(listOf(capability("gpt-5.2-pro", setOf("chat/completions")).copy(providerId = "openai")))
        assertEquals(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
            ProviderTransportV2.spec(ProviderRegistry.byId("openai"), "gpt-5.2-pro").protocol)
    }

    @Test fun everyOfferedResponsesEffortCanBeEncodedForNativeAndGatewayModels() {
        ModelCapabilityRegistry.installCatalog(listOf(
            capability("future", setOf("responses")).copy(providerId = "openrouter", reasoningEfforts = setOf("low", "high", "xhigh", "max")),
        ))
        listOf("openrouter" to "future", "openai" to "gpt-6-pro").forEach { (provider, model) ->
            assertTrue(ReasoningEffort.ULTRA in ReasoningSupport.supported(provider, model))
            ReasoningSupport.supported(provider, model).forEach { effort ->
                val body = buildJsonObject {
                    put("model", model)
                    put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hi") }) })
                    ReasoningSupport.apply(this, provider, model, effort)
                }
                assertTrue("reasoning_effort" in body)
                val wire = Json.parseToJsonElement(OpenAiResponsesAdapter.encodeRequest(body.toString(), false, Json)).jsonObject
                assertEquals(JsonPrimitive(effort.wireValue), wire["reasoning"]!!.jsonObject["effort"])
            }
        }
    }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TypedCompactionRequestPolicyTest {
    private val fixtureProvider = AiProvider("typed-summary.fixture", "Summary", "https://example.com/v1", "summary-model", false, false, "", "")

    @After fun reset() { ModelCapabilityRegistry.revokeRuntime(fixtureProvider.id) }

    private fun options(provider: AiProvider, model: String, effort: ReasoningEffort? = ReasoningEffort.HIGH): JsonObject =
        TypedCompactionRequestPolicy.summaryOptions(AgentConfig(model = model, providerId = provider.id, reasoningEffort = effort), provider)

    @Test fun openAiReasoningSummaryDoesNotReuseTheOldTwoKTotalCap() {
        val options = options(ProviderRegistry.byId("openai"), "gpt-6-luna")
        assertEquals(32_768, ProviderRequestBudget.explicitOutputTokens(options))
        assertEquals(JsonPrimitive("high"), options["reasoning_effort"])
        assertEquals(JsonPrimitive("none"), options["tool_choice"])
        assertFalse("tools" in options)
        assertFalse("messages" in options)
        val wire = Json.parseToJsonElement(OpenAiResponsesAdapter.encodeRequest(
            JsonObject(options + ("messages" to JsonArray(emptyList()))).toString(), true, Json)).jsonObject
        assertEquals(JsonPrimitive("high"), wire.getValue("reasoning").jsonObject["effort"])
        assertEquals(JsonPrimitive(32_768), wire["max_output_tokens"])
    }

    @Test fun nativeAndGatewaySummaryRequestsRetainSelectedReasoning() {
        val anthropic = options(ProviderRegistry.byId("anthropic"), "claude-opus-5.5")
        assertEquals(JsonPrimitive("adaptive"), anthropic.getValue("thinking").jsonObject["type"])
        assertEquals(JsonPrimitive("high"), anthropic.getValue("output_config").jsonObject["effort"])
        val google = options(ProviderRegistry.byId("gemini"), "gemini-3.8-flash")
        assertEquals(JsonPrimitive("high"), google["reasoning_effort"])
        val router = options(ProviderRegistry.byId("openrouter"), "openai/gpt-6-luna")
        assertEquals(JsonPrimitive("high"), router.getValue("reasoning").jsonObject["effort"])
        listOf(anthropic, google, router).forEach {
            assertEquals(32_768, ProviderRequestBudget.explicitOutputTokens(it))
            assertEquals(JsonPrimitive("none"), it["tool_choice"])
            assertFalse("tools" in it)
        }
    }

    @Test fun summaryUsesAdvertisedOutputCapacityAndItsActualReservationForInput() {
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(
            fixtureProvider.id, fixtureProvider.model, "summary", null, true, true, null, null,
            emptySet(), emptySet(), emptySet(), 262_144, 65_536,
            ModelCapabilitySource.RUNTIME_PROVIDER,
        ))
        val options = options(fixtureProvider, fixtureProvider.model, effort = null)
        val reserved = checkNotNull(ProviderRequestBudget.explicitOutputTokens(options))
        assertEquals(65_536, reserved)
        assertEquals(196_608, ContextBudget.effectiveInputTokens(fixtureProvider.id, fixtureProvider.model, reserved))
        assertFalse("reasoning_effort" in options)
    }

    @Test fun smallModelsKeepTheirAdvertisedOutputCeiling() {
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(
            fixtureProvider.id, fixtureProvider.model, "summary", null, null, true, null, null,
            emptySet(), emptySet(), emptySet(), 16_384, 1_024,
            ModelCapabilitySource.RUNTIME_PROVIDER,
        ))
        assertEquals(1_024, ProviderRequestBudget.explicitOutputTokens(options(fixtureProvider, fixtureProvider.model, effort = null)))
    }

    @Test fun invalidModelOrMismatchedProviderFailsLocally() {
        assertThrows(IllegalArgumentException::class.java) { options(fixtureProvider, " summary-model", effort = null) }
        assertThrows(IllegalArgumentException::class.java) {
            TypedCompactionRequestPolicy.summaryOptions(AgentConfig(providerId = "another", model = fixtureProvider.model), fixtureProvider)
        }
    }
}

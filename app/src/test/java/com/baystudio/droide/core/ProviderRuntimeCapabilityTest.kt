package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderRuntimeCapabilityTest {
    private val model = "runtime_capability_fixture"
    @After fun reset() {
        ModelCapabilityRegistry.installCatalog(emptyList())
        ModelCapabilityRegistry.revokeRuntime("gemini")
        ModelCapabilityRegistry.revokeRuntime("openai", model)
    }
    private fun capability(provider: String, tools: Boolean? = true, efforts: Set<String> = setOf("high"), output: Int? = 4096) = ModelCapabilities(
        provider, model, model, true, true, tools, true, false, setOf("text", "image"), setOf("text"),
        efforts, 32768, output, ModelCapabilitySource.MODELS_DEV,
    )
    private fun parse(provider: String, text: String) = ProviderRuntimeCapabilities.parse(
        ProviderRegistry.byId(provider), ProviderModelCatalog.Entry(model, Json.parseToJsonElement(text).jsonObject),
    )!!

    @Test fun googleCatalogMetadataIsAuthoritativeForBuiltinGemini() {
        ModelCapabilityRegistry.installCatalog(listOf(capability("google", tools = false)))
        assertFalse(ModelCapabilityRegistry.allowsToolUse("gemini", model))
        assertEquals(false, ModelCapabilityRegistry.resolve("gemini", model)?.temperature)
        assertEquals(4096, ModelCapabilityRegistry.resolve("gemini", model)?.outputTokens)
        assertEquals(true, ModelCapabilityRegistry.allowsImageInput("gemini", model))
    }

    @Test fun liveSupportedParametersRetainExplicitFalseWithoutErasingKnownLimits() {
        ModelCapabilityRegistry.installCatalog(listOf(capability("openai")))
        ModelCapabilityRegistry.installRuntime(parse("openai", """{"supported_parameters":["temperature"]}"""))
        val merged = ModelCapabilityRegistry.resolve("openai", model)!!
        assertEquals(false, merged.toolCall)
        assertEquals(false, merged.reasoning)
        assertEquals(true, merged.temperature)
        assertEquals(4096, merged.outputTokens)
        assertEquals(32768, merged.contextTokens)
        assertFalse(ModelCapabilityRegistry.allowsToolUse("openai", model))
    }

    @Test fun partialLiveRefreshDoesNotEraseAnExistingRuntimeCertificate() {
        ModelCapabilityRegistry.installRuntime(parse("openai", """{"tool_call":false,"max_output_tokens":2048}"""))
        ModelCapabilityRegistry.installRuntime(parse("openai", """{"id":"runtime_capability_fixture"}"""))
        assertFalse(ModelCapabilityRegistry.allowsToolUse("openai", model))
        assertEquals(2048, ModelCapabilityRegistry.resolve("openai", model)?.outputTokens)
    }

    @Test fun liveStructuredEffortsAndEndpointMetadataAreRetained() {
        val parsed = parse("openai", """{"supported_endpoints":["/v1/responses"],"reasoning":true,"reasoning_options":[{"type":"effort","values":["medium"]}],"output_modalities":["text"],"limit":{"output":2048}}""")
        ModelCapabilityRegistry.installRuntime(parsed)
        assertEquals(setOf("medium"), parsed.reasoningEfforts)
        assertTrue(parsed.reasoningOptionsKnown)
        assertTrue(ReasoningSupport.supported("openai", model).isEmpty())
        assertEquals(ProviderWireProtocol.OPENAI_RESPONSES, ProviderTransportV2.spec(ProviderRegistry.byId("openai"), model).protocol)
    }

    @Test fun explicitlyEmptyOrToggleOnlyReasoningOptionsDoNotFallBackToNamedEfforts() {
        for (options in listOf("[]", """[{"type":"toggle"}]""", """[{"type":"effort","values":[]}]""")) {
            val parsed = parse("openai", """{"reasoning":true,"reasoning_options":$options}""").copy(modelId = "gpt-5")
            ModelCapabilityRegistry.installCatalog(listOf(capability("openai").copy(modelId = "gpt-5")))
            ModelCapabilityRegistry.installRuntime(parsed)
            try {
                assertTrue(ReasoningSupport.supported("openai", "gpt-5").isEmpty())
            } finally { ModelCapabilityRegistry.revokeRuntime("openai", "gpt-5") }
        }
    }

    @Test fun nativeThinkingPresetsRespectDeclaredBudgetAndOutputCaps() {
        val parsed = parse("gemini", """{"reasoning":true,"reasoning_options":[{"type":"budget_tokens","min":512,"max":2048}],"outputTokenLimit":4096}""")
            .copy(modelId = "gemini-2.5-flash")
        ModelCapabilityRegistry.installRuntime(parsed)
        try {
            val encoded = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(
                """{"model":"gemini-2.5-flash","messages":[],"reasoning_effort":"high"}""", Json,
            )).jsonObject
            assertEquals(JsonPrimitive(2048), encoded["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"])
        } finally { ModelCapabilityRegistry.revokeRuntime("gemini", "gemini-2.5-flash") }
    }

    @Test fun budgetOnlyMetadataCannotInventGenericChatEffortParameters() {
        val base = capability("openrouter").copy(modelId = "google/gemini-2.5-pro", reasoningEfforts = emptySet(),
            reasoningOptionsKnown = true, reasoningOptionTypes = setOf("toggle", "budget_tokens"),
            reasoningBudgetMin = 128, reasoningBudgetMax = 24576)
        ModelCapabilityRegistry.installCatalog(listOf(base, base.copy(providerId = "openai", modelId = "claude-sonnet-4-5")))
        assertTrue(ReasoningSupport.supported("openrouter", "google/gemini-2.5-pro").isEmpty())
        assertTrue(ReasoningSupport.supported("openai", "claude-sonnet-4-5").isEmpty())
    }

    @Test fun builtinProviderAliasesRetainCatalogCapabilitiesAndContextLimits() {
        for ((builtin, catalog) in listOf("together" to "togetherai", "fireworks" to "fireworks-ai")) {
            ModelCapabilityRegistry.installCatalog(listOf(capability(catalog, tools = false)))
            assertFalse(ModelCapabilityRegistry.allowsToolUse(builtin, model))
            assertEquals(4096, ModelCapabilityRegistry.resolve(builtin, model)?.outputTokens)
            assertEquals(32768, ModelContextRegistry.resolve(builtin, model))
            assertEquals(listOf(model), ModelCapabilityRegistry.catalogModels(builtin))
            assertEquals(builtin, ProviderRegistry.findByCatalogId(catalog)?.id)
            ModelCapabilityRegistry.installRuntime(parse("openai", """{"tool_call":true}""").copy(providerId = builtin))
            try { assertTrue(ModelCapabilityRegistry.allowsToolUse(builtin, model)) }
            finally { ModelCapabilityRegistry.revokeRuntime(builtin, model) }
        }
    }
}

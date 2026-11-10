package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GoogleThinkingHeadroomTest {
    private val providerId = "google-headroom.fixture"
    private val budgetModel = "gemini-2.5-flash"

    @After fun reset() { ModelCapabilityRegistry.revokeRuntime(providerId) }

    private fun install(output: Int, minimum: Int? = null, maximum: Int? = null) {
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(
            providerId, budgetModel, budgetModel, null, true, true, null, null,
            emptySet(), emptySet(), setOf("low", "high"), null, output,
            ModelCapabilitySource.RUNTIME_PROVIDER,
            reasoningBudgetMin = minimum, reasoningBudgetMax = maximum,
        ))
    }

    private fun generation(output: Int? = null, effort: String = "high", model: String = budgetModel): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "Write a small program") })
            })
            put("reasoning_effort", effort)
            output?.let { put("max_tokens", it) }
        }
        return Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body.toString(), Json, providerId))
            .jsonObject.getValue("generationConfig").jsonObject
    }

    @Test fun knownSmallCeilingLeavesAnswerRoomInsteadOfOneToken() {
        install(output = 8_192)
        assertEquals(4_096, GoogleGenerateContentAdapter.thinkingBudget(providerId, budgetModel, "high"))
        val generation = generation(output = 8_192)
        assertEquals(JsonPrimitive(8_192), generation["maxOutputTokens"])
        assertEquals(JsonPrimitive(4_096), generation.getValue("thinkingConfig").jsonObject["thinkingBudget"])
    }

    @Test fun explicitTotalOutputIsPreservedAndTheDerivedPresetFitsWithinIt() {
        install(output = 65_536)
        val generation = generation(output = 512)
        assertEquals(JsonPrimitive(512), generation["maxOutputTokens"])
        assertEquals(JsonPrimitive(256), generation.getValue("thinkingConfig").jsonObject["thinkingBudget"])
    }

    @Test fun explicitLimitWorksWhenProviderMetadataIsAbsent() {
        val generation = generation(output = 2_048)
        assertEquals(JsonPrimitive(2_048), generation["maxOutputTokens"])
        assertEquals(JsonPrimitive(1_024), generation.getValue("thinkingConfig").jsonObject["thinkingBudget"])
    }

    @Test fun providerReasoningRangeStillBoundsThePreset() {
        install(output = 32_768, minimum = 2_048, maximum = 3_072)
        assertEquals(JsonPrimitive(3_072), generation().getValue("thinkingConfig").jsonObject["thinkingBudget"])
        assertEquals(JsonPrimitive(2_048), generation(effort = "low").getValue("thinkingConfig").jsonObject["thinkingBudget"])
    }

    @Test fun impossibleMinimumFailsBeforeSendingInsteadOfSilentlyRemovingTheAnswerAllowance() {
        install(output = 8_192, minimum = 5_000)
        val failure = assertThrows(IllegalArgumentException::class.java) { generation(output = 8_192) }
        assertTrue(failure.message.orEmpty().contains("minimum thinking budget and answer allowance"))
        assertThrows(IllegalArgumentException::class.java) { generation(output = 1) }
    }

    @Test fun unboundedPresetsKeepTheirExistingBudgets() {
        assertEquals(JsonPrimitive(24_576), generation().getValue("thinkingConfig").jsonObject["thinkingBudget"])
        assertEquals(JsonPrimitive(1_024), generation(effort = "low").getValue("thinkingConfig").jsonObject["thinkingBudget"])
    }

    @Test fun geminiLevelThinkingRemainsAnEffortSetting() {
        val generation = generation(output = 16_384, model = "gemini-3.8-flash")
        assertEquals(JsonPrimitive(16_384), generation["maxOutputTokens"])
        val thinking = generation.getValue("thinkingConfig").jsonObject
        assertEquals(JsonPrimitive("HIGH"), thinking["thinkingLevel"])
        assertFalse("thinkingBudget" in thinking)
    }
}

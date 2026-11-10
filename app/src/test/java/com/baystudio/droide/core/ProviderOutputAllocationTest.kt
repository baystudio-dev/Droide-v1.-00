package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderOutputAllocationTest {
    private val provider = "allocation.fixture"
    private val model = "discovered-model"
    @After fun clear() { ModelCapabilityRegistry.revokeRuntime(provider) }

    private fun install(output: Int?, context: Int? = null, input: Int? = null, reasoning: Boolean? = null) {
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(
            provider, model, model, null, reasoning, true, null, null, emptySet(), emptySet(), emptySet(),
            context, output, ModelCapabilitySource.RUNTIME_PROVIDER, inputTokens = input,
        ))
    }
    private fun options(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun allocation(raw: String = "{}") = ProviderRequestBudget.outputTokens(options(raw), provider, model)

    @Test fun advertisedOutputCapacityIsUsedInsteadOfTheUniversalEightKCeiling() {
        install(output = 131_072, context = 1_048_576)
        assertEquals(131_072, allocation())
        assertEquals(131_072, allocation("""{"reasoning_effort":"high"}"""))
    }

    @Test fun unknownEffortModelsHaveRoomForReasoningAndAnswerAcrossWireShapes() {
        for (request in listOf(
            """{"reasoning_effort":"high"}""",
            """{"reasoning":{"effort":"high"}}""",
            """{"thinking":{"type":"adaptive"},"output_config":{"effort":"high"}}""",
            """{"thinking":{"type":"adaptive"}}""",
        )) assertEquals(request, 32_768, allocation(request))
        assertEquals(65_536, allocation("""{"reasoning_effort":"xhigh"}"""))
        assertEquals(65_536, allocation("""{"reasoning":{"effort":"max"}}"""))
        assertEquals(8_192, allocation())
    }

    @Test fun automaticReasoningUsesAFallbackWithoutInventingAModelLimit() {
        install(output = null, reasoning = true)
        assertEquals(32_768, allocation())
        assertNull(ModelContextRegistry.resolve(provider, model))
        assertNull(ModelCapabilityRegistry.resolve(provider, model)!!.outputTokens)
    }

    @Test fun smallAdvertisedCapsAndSharedInputRoomStillConstrainAutomaticAllocation() {
        install(output = 4_096, context = 32_768)
        assertEquals(4_096, allocation("""{"reasoning_effort":"high"}"""))
        ModelCapabilityRegistry.revokeRuntime(provider)
        install(output = 65_536, context = 65_536)
        assertEquals(16_384, allocation("""{"reasoning_effort":"high"}"""))
    }

    @Test fun nativeInputOnlyCeilingDoesNotShrinkOutputCapacity() {
        install(output = 65_536, input = 8_192)
        val reserve = allocation()
        assertEquals(65_536, reserve)
        assertEquals(8_192, ContextBudget.effectiveInputTokens(provider, model, reserve))
    }

    @Test fun explicitAliasesRemainExactAndConflictingOrExcessiveValuesAreRejected() {
        install(output = 65_536)
        for (field in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            assertEquals(2_048, allocation("""{"$field":2048,"reasoning_effort":"high"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) { allocation("""{"max_tokens":131072}""") }
        assertThrows(IllegalArgumentException::class.java) { allocation("""{"max_tokens":1024,"max_output_tokens":2048}""") }
    }

    @Test fun manualThinkingKeepsAnswerRoomAndRejectsAnIncompatibleExplicitCap() {
        assertEquals(12_288, allocation("""{"thinking":{"type":"enabled","budget_tokens":8192}}"""))
        assertThrows(IllegalArgumentException::class.java) {
            allocation("""{"max_tokens":8192,"thinking":{"type":"enabled","budget_tokens":8192}}""")
        }
    }

    @Test fun repeatedPreparationKeepsTheResolvedLimitAndPreflightUsesTheSameReservation() {
        install(output = null, context = 262_144)
        val request = options("""{"model":"$model","reasoning_effort":"high","messages":[{"role":"user","content":"build"}]}""")
        val first = ProviderRequestBudget.prepare(request, provider, model)
        assertEquals(first, ProviderRequestBudget.prepare(first, provider, model))
        val reserve = ProviderRequestBudget.explicitOutputTokens(first)!!
        val estimate = ContextBudget.estimate("", emptyList(), null, provider, model, null, reserve)
        assertEquals(32_768, reserve)
        assertEquals(reserve, estimate.reservedOutputTokens)
        assertEquals(262_144 - reserve - ContextBudget.BUFFER_TOKENS, estimate.triggerTokens)
    }

    @Test fun builtRequestAndCompactionUseTheSelectedEffortReservation() {
        // Built-in OpenAI routing recognizes this family without inventing model capacities.
        val config = AgentConfig(model = "gpt-6-luna", providerId = "openai", reasoningEffort = ReasoningEffort.HIGH)
        ModelContextRegistry.remember(config.providerId, config.model, 262_144)
        try {
            val request = AgentRequestSupport.build(config, "system", emptyList(), null)
            val reserve = AgentRequestSupport.outputTokens(config)
            assertEquals(32_768, reserve)
            assertEquals(reserve, ProviderRequestBudget.explicitOutputTokens(request))
            val prefix = ContextBudget.estimate("system", emptyList(), null, config.providerId, config.model, null, reserve)
            val compact = AgentCompactionBudget.forRequest(config, "system", null)
            assertEquals(262_144 - reserve - prefix.approximateTokens - 256, compact.historyTokens)
        } finally { ModelContextRegistry.forget(config.providerId, config.model) }
    }
}

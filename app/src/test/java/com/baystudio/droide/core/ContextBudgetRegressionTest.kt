package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ContextBudgetRegressionTest {
    private val provider = "context.regression"
    private val model = "discovered-model"
    @After fun clear() {
        ModelCapabilityRegistry.revokeRuntime(provider)
        ModelCapabilityRegistry.installCatalog(emptyList())
    }

    private fun capability(context: Int, source: ModelCapabilitySource = ModelCapabilitySource.RUNTIME_PROVIDER) = ModelCapabilities(
        provider, model, model, null, null, null, null, null, emptySet(), emptySet(), emptySet(),
        context, null, source,
    )

    @Test fun latestCatalogContextReplacesOldRememberedValueAndRevokeDoesNotLeaveStaleLimit() {
        ModelContextRegistry.remember(provider, model, 8192)
        ModelCapabilityRegistry.installRuntime(capability(1_048_576))
        assertEquals(1_048_576, ModelContextRegistry.resolve(provider, model))
        ModelCapabilityRegistry.revokeRuntime(provider)
        assertNull(ModelContextRegistry.resolve(provider, model))
    }

    @Test fun catalogRefreshAndRuntimeRemovalRestoreCurrentCatalogLimits() {
        ModelCapabilityRegistry.installCatalog(listOf(capability(32_768, ModelCapabilitySource.MODELS_DEV)))
        ModelCapabilityRegistry.installRuntime(capability(65_536))
        ModelCapabilityRegistry.installCatalog(listOf(capability(131_072, ModelCapabilitySource.MODELS_DEV)))
        assertEquals(65_536, ModelContextRegistry.resolve(provider, model))
        ModelCapabilityRegistry.revokeRuntime(provider, model)
        assertEquals(131_072, ModelContextRegistry.resolve(provider, model))
        ModelCapabilityRegistry.installCatalog(emptyList())
        assertNull(ModelContextRegistry.resolve(provider, model))
    }

    @Test fun unknownModelKeepsLongerConversationsWithoutClaimingATokenLimit() {
        val estimate = ContextBudget.estimate("system", listOf(message("a".repeat(300_000))), null, provider, model)
        assertNull(estimate.knownContextLimit)
        assertNull(estimate.triggerTokens)
        assertFalse(estimate.shouldCompact)
        assertTrue(ContextBudget.recentKeepTokens(provider, model) > 15_000)
    }

    @Test fun knownLargeModelRetainsMoreRecentContextAndCompactsBeforeTransportMemoryLimit() {
        ModelCapabilityRegistry.installRuntime(capability(1_048_576))
        val moderate = ContextBudget.estimate("system", listOf(message("x".repeat(500_000))), null, provider, model)
        assertFalse(moderate.shouldCompact)
        assertEquals(1_048_576, moderate.knownContextLimit)
        assertTrue(ContextBudget.recentKeepTokens(provider, model) >= 100_000)
        val large = ContextBudget.estimate("system", listOf(message("x".repeat(ContextBudget.MOBILE_COMPACT_CHARS))), null, provider, model)
        assertTrue(large.shouldCompact)
        assertTrue(large.serializedChars < LlmClient.MAX_REQUEST_CHARS)
    }

    @Test fun smallModelStillReservesRoomAndOverflowRecoveryReducesTheRecentWindow() {
        ModelCapabilityRegistry.installRuntime(capability(8192))
        val keep = ContextBudget.recentKeepTokens(provider, model)
        assertTrue(keep in 1_000..4096)
        assertTrue(ContextBudget.overflowRecoveryKeepTokens(provider, model) < keep)
        assertTrue(ContextBudget.estimate("system", listOf(message("x".repeat(30_000))), null, provider, model).shouldCompact)
    }

    @Test fun allocationFreeJsonSizeMatchesEscapesNestedValuesAndUnicode() {
        val payload = buildJsonObject {
            put("a\n\\\"", "🌏\u0000\b\t\n\u000C\r")
            put("entries", buildJsonArray { add(JsonNull); add(true); add(13); add(buildJsonObject { put("text", "hello") }) })
        }
        assertEquals(payload.toString().length, ContextBudget.serializedChars(payload))
        assertEquals(536_870_912, ContextBudget.estimateTokensFromChars(Int.MAX_VALUE))
    }

    @Test fun discoveredNestedLimitsAndNativeInputLimitAreRecognizedWithoutUsingOutputAsContext() {
        assertEquals(1_048_576, ModelContextRegistry.contextLimitFromModel(Json.parseToJsonElement("""{"limit":{"context":1048576,"output":8192}}""").jsonObject))
        assertNull(ModelContextRegistry.contextLimitFromModel(Json.parseToJsonElement("""{"input_token_limit":65536,"output_token_limit":8192}""").jsonObject))
        assertEquals(65_536, ModelContextRegistry.inputLimitFromModel(Json.parseToJsonElement("""{"input_token_limit":65536,"output_token_limit":8192}""").jsonObject))
        assertNull(ModelContextRegistry.contextLimitFromModel(Json.parseToJsonElement("""{"limit":{"output":8192}}""").jsonObject))
    }

    @Test fun imageWireEncodingDoesNotPretendToConsumeHundredsOfThousandsOfTokens() {
        fun history(type: String, imageField: String, url: String) = listOf(buildJsonObject {
            put("role", "user"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "Use this logo") })
                add(buildJsonObject { put("type", type); put(imageField, buildJsonObject { put("url", url) }) })
            })
        })
        val reference = ContextBudget.estimate("system", history("droide_image_ref", "image_ref", "stored-image"), null, provider, model)
        val materialized = ContextBudget.estimate("system", history("image_url", "image_url", "data:image/png;base64," + "A".repeat(2_000_000)), null, provider, model)
        assertEquals(reference.approximateTokens, materialized.approximateTokens)
        assertTrue(materialized.approximateTokens in 4_096..10_000)
        assertFalse(materialized.shouldCompact)
        assertNull(materialized.knownContextLimit)
    }

    @Test fun utf8EstimatesIncludeUnicodeInSystemHistoryAndToolSchemas() {
        val text = "漢字😀\n\"\u0000".repeat(1000)
        val json = buildJsonObject { put("名😀", text) }
        assertEquals(json.toString().toByteArray(Charsets.UTF_8).size, ContextBudget.serializedUtf8Bytes(json))
        assertEquals((text.toByteArray(Charsets.UTF_8).size + 3) / 4, ContextBudget.estimateTokens(text))
        val history = listOf(message(text))
        val tools = buildJsonArray { add(json) }
        val expectedBytes = text.toByteArray(Charsets.UTF_8).size + history.sumOf { it.toString().toByteArray(Charsets.UTF_8).size } + tools.toString().toByteArray(Charsets.UTF_8).size + 1024
        assertEquals((expectedBytes + 3) / 4, ContextBudget.estimate(text, history, tools, provider, model).approximateTokens)
    }

    @Test fun compactionRetainsSystemDeveloperAndNewestUserExactlyAcrossLongToolTurns() {
        val system = buildJsonObject { put("role", "system"); put("content", "Never publish") }
        val developer = buildJsonObject { put("role", "developer"); put("content", "Preserve drafts") }
        val user = message("Review only; do not modify files")
        val response = buildJsonObject { put("role", "assistant"); put("content", "evidence ".repeat(1000)) }
        val selected = AgentHistory.selectForCompaction(listOf(system, developer, user, response, response), 100)
        assertTrue(selected.recent.flatten().containsAll(listOf(system, developer, user)))
        assertFalse(selected.historical.flatten().contains(system))
        assertEquals(response, selected.recent.last().last())
    }

    private fun message(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
}

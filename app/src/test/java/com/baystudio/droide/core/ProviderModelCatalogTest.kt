package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderModelCatalogTest {
    @Test fun googleDiscoveryExcludesEmbeddingModelsAndRemovesNativePrefix() {
        val body = """{"models":[{"name":"models/gemini-3.5-flash","supportedGenerationMethods":["generateContent","countTokens"],"inputTokenLimit":1048576},{"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]}]}"""
        val entries = ProviderModelCatalog.parse(body, ProviderRegistry.byId("gemini"), Json)
        assertEquals(listOf("gemini-3.5-flash"), entries.map { it.id })
        assertEquals(1048576, ModelContextRegistry.inputLimitFromModel(entries[0].metadata!!))
        assertNull(ModelContextRegistry.contextLimitFromModel(entries[0].metadata!!))
        assertEquals("/models?pageSize=1000", ProviderModelCatalog.modelsPath(ProviderRegistry.byId("gemini")))
    }

    @Test fun googleSupportsActionsMetadataAndLegacyCatalogsWithoutIt() {
        val body = """{"models":[{"name":"models/chat","supportedActions":["generateContent"]},{"name":"models/embed","supportedActions":["embedContent"]},{"name":"models/legacy"}]}"""
        assertEquals(listOf("chat", "legacy"), ProviderModelCatalog.parse(body, ProviderRegistry.byId("gemini"), Json).map { it.id })
    }

    @Test fun openAiAndAnthropicModelIdsAreDeduplicatedWithoutChangingProviderIdentity() {
        val body = """{"data":[{"id":"model-a"},{"id":"model-a"},{"id":"vendor/model-b"}]}"""
        listOf("openai", "anthropic", "groq", "deepseek").forEach { id ->
            assertEquals(listOf("model-a", "vendor/model-b"), ProviderModelCatalog.parse(body, ProviderRegistry.byId(id), Json).map { it.id })
        }
        assertEquals("/models?supported_parameters=tools&output_modalities=text", ProviderModelCatalog.modelsPath(ProviderRegistry.byId("openrouter")))
    }

    @Test fun malformedCatalogDoesNotMasqueradeAsSuccessfulValidation() {
        listOf("[]", "{\"error\":\"invalid key\"}", "not-json").forEach {
            assertThrows(Exception::class.java) { ProviderModelCatalog.parse(it, ProviderRegistry.byId("openai"), Json) }
        }
    }
}

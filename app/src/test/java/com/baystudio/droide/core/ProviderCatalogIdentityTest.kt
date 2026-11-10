package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderCatalogIdentityTest {
    private fun provider(protocol: ProviderWireProtocol) = AiProvider(
        "custom.catalog-identity", "Fixture", "https://example.invalid/v1", "", false, false, "", "",
        wireProtocol = protocol,
    )

    @Test fun nonGoogleCatalogsPreserveNamespacedIdentityInObjectsAndStrings() {
        for (protocol in ProviderWireProtocol.entries.filter { it != ProviderWireProtocol.GOOGLE_GENERATE_CONTENT }) {
            val rows = ProviderModelCatalog.parse(
                """{"data":[{"id":"models/company-model"},"models/company-model",{"name":"models/other-model"}]}""",
                provider(protocol), Json,
            )
            assertEquals(protocol.toString(), listOf("models/company-model", "models/other-model"), rows.map { it.id })
        }
    }

    @Test fun nativeGoogleResourcePrefixNormalizesBothCatalogRepresentations() {
        val rows = ProviderModelCatalog.parse(
            """{"models":[{"name":"models/gemini-3.8-flash"},"models/gemini-3.8-flash",{"id":"  models/gemini-2.5-flash  "}]}""",
            provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json,
        )
        assertEquals(listOf("gemini-3.8-flash", "gemini-2.5-flash"), rows.map { it.id })
    }

    @Test fun normalizationDoesNotAdmitEmptyGoogleResourcesOrControlCharacters() {
        val rows = ProviderModelCatalog.parse(
            """{"models":[{"name":"models/"},"models/",{"name":"models/bad\nname"},"plain-valid"]}""",
            provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json,
        )
        assertEquals(listOf("plain-valid"), rows.map { it.id })
    }

    @Test fun capabilityMetadataRemainsBoundToTheUnchangedProviderModelIdentity() {
        val authority = provider(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS)
        val row = ProviderModelCatalog.parse(
            """{"data":[{"id":"models/company-model","max_output_tokens":65536}]}""", authority, Json,
        ).single()
        val capability = ProviderRuntimeCapabilities.parse(authority, row)!!
        assertEquals("models/company-model", capability.modelId)
        assertEquals(65_536, capability.outputTokens)
    }
}

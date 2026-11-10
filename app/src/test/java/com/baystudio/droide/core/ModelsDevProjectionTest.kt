package com.baystudio.droide.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ModelsDevProjectionTest {
    @After fun reset() { ModelCapabilityRegistry.installCatalog(emptyList()) }

    private fun parse(models: String) = ModelsDevCatalog.parse("""{
        "projection_fixture":{"npm":"@ai-sdk/openai-compatible","api":"https://fixture.example/v1",
        "models":$models}}
    """)

    @Test fun modelProviderShapesSelectTheAdvertisedEndpoint() {
        val snapshot = parse("""{
            "response":{"provider":{"shape":"responses"}},
            "chat":{"provider":{"shape":"completions"}},
            "explicit":{"provider":{"shape":"responses"},"supported_endpoints":["chat/completions"]}}
        """)
        ModelCapabilityRegistry.installCatalog(snapshot.capabilities)
        val provider = snapshot.providers.single()
        assertEquals(ProviderWireProtocol.OPENAI_RESPONSES, ProviderModelRequestPolicy.protocol(provider, "response"))
        assertEquals(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderModelRequestPolicy.protocol(provider, "chat"))
        assertEquals(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderModelRequestPolicy.protocol(provider, "explicit"))
    }

    @Test fun modelOverridesRequiringAnotherTransportAreExcludedFromChat() {
        val snapshot = parse("""{
            "anthropic":{"provider":{"npm":"@ai-sdk/anthropic"}},
            "other_host":{"provider":{"api":"https://another.example/v1"}},
            "other_path":{"provider":{"api":"https://fixture.example/messages"}},
            "same":{"provider":{"npm":"@ai-sdk/openai","api":"https://fixture.example/v1/","shape":"responses"}}}
        """)
        ModelCapabilityRegistry.installCatalog(snapshot.capabilities)
        val provider = snapshot.providers.single()
        for (model in listOf("anthropic", "other_host", "other_path")) {
            assertFalse(ProviderModelRequestPolicy.isConversational(provider, model))
            assertThrows(IllegalArgumentException::class.java) { ProviderModelRequestPolicy.requireConversational(provider, model) }
        }
        assertTrue(ProviderModelRequestPolicy.isConversational(provider, "same"))
    }

    @Test fun declaredReasoningOptionsRetainKindsBoundsAndExplicitAbsence() {
        val snapshot = parse("""{
            "budget":{"reasoning_options":[{"type":"budget_tokens","min":1024,"max":4096}]},
            "effort":{"reasoning_options":[{"type":"effort","values":["medium","high","xhigh"]}]},
            "none":{"reasoning_options":[]}}
        """)
        val caps = snapshot.capabilities.associateBy { it.modelId }
        assertEquals(1024, caps.getValue("budget").reasoningBudgetMin)
        assertEquals(4096, caps.getValue("budget").reasoningBudgetMax)
        assertEquals(setOf("budget_tokens"), caps.getValue("budget").reasoningOptionTypes)
        assertEquals(setOf("medium", "high", "xhigh"), caps.getValue("effort").reasoningEfforts)
        assertTrue(caps.getValue("none").reasoningOptionsKnown)
        assertTrue(caps.getValue("none").reasoningEfforts.isEmpty())
    }

    @Test fun googleCatalogCapabilitiesBindToTheNativeGeminiIdentity() {
        val snapshot = ModelsDevCatalog.parse("""{"google":{"npm":"@ai-sdk/google","models":{
            "gemini-2.5-pro":{"tool_call":false,"limit":{"output":2048},"provider":{"npm":"@ai-sdk/google"}}}}}
        """)
        ModelCapabilityRegistry.installCatalog(snapshot.capabilities)
        assertFalse(ModelCapabilityRegistry.allowsToolUse("gemini", "gemini-2.5-pro"))
        assertEquals(2048, ModelCapabilityRegistry.resolve("gemini", "gemini-2.5-pro")?.outputTokens)
        assertTrue(ProviderModelRequestPolicy.isConversational(ProviderRegistry.byId("gemini"), "gemini-2.5-pro"))
    }
}

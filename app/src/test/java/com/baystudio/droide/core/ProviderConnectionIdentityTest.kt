package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ProviderConnectionIdentityTest {
    private val original = AiProvider("custom.shared", "First project", "https://one.example/v1",
        "", false, true, "", "", authScheme = ProviderAuthScheme.BEARER)
    private fun receipt(provider: AiProvider) = ProviderConnectionRecord(provider.id, "api-key", 1L, 1,
        ProviderConnectionIdentity.fingerprint(provider))

    @Test fun displayLabelsAndEquivalentEndpointFormattingDoNotRequireReconnect() {
        assertTrue(ProviderConnectionIdentity.matches(receipt(original),
            original.copy(name = "Another display label", baseUrl = "https://ONE.example:443/v1/")))
    }

    @Test fun sameIdWithDifferentEndpointAuthProtocolOrRegionCannotReuseCredential() {
        val saved = receipt(original)
        for (different in listOf(
            original.copy(baseUrl = "https://two.example/v1"),
            original.copy(baseUrl = "https://one.example/other"),
            original.copy(baseUrl = "https://one.example:444/v1"),
            original.copy(authScheme = ProviderAuthScheme.AZURE_API_KEY),
            original.copy(wireProtocol = ProviderWireProtocol.OPENAI_RESPONSES),
            original.copy(authRegion = "us-east-1"),
            original.copy(networkScope = ProviderNetworkScope.LOOPBACK_ONLY),
            original.copy(id = "custom.another"),
        )) assertFalse(ProviderConnectionIdentity.matches(saved, different))
    }

    @Test fun unboundLegacyCustomReceiptRequiresExplicitConnectionButBuiltinsDoNot() {
        assertFalse(ProviderConnectionIdentity.matches(receipt(original).copy(endpointFingerprint = null), original))
        val fixed = checkNotNull(ProviderRegistry.findById("openai"))
        assertTrue(ProviderConnectionIdentity.matches(
            ProviderConnectionRecord(fixed.id, "api-key", 1L, 1), fixed))
        assertFalse(ProviderConnectionIdentity.matches(receipt(original).copy(endpointFingerprint = ""), original))
    }
}

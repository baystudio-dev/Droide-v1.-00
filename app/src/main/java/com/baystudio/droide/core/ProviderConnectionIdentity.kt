package com.baystudio.droide.core

import java.security.MessageDigest

/** Non-secret receipt binding. A reused custom ID is not authorization for a new server. */
internal object ProviderConnectionIdentity {
    fun fingerprint(provider: AiProvider): String {
        val identity = listOf(
            "droide-provider-connection-v1",
            provider.id.trim().lowercase(),
            ProviderRuntimeGuard.endpointIdentity(provider.baseUrl),
            provider.wireProtocol.name,
            provider.authScheme.name,
            provider.networkScope.name,
            provider.authRegion.orEmpty(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun matches(record: ProviderConnectionRecord, provider: AiProvider): Boolean {
        if (!record.providerId.equals(provider.id, ignoreCase = true)) return false
        val stored = record.endpointFingerprint
        // Bootstrap endpoints cannot be replaced by a project. Preserve their old receipts. Legacy
        // configurable endpoints have no trustworthy previous destination to infer.
        if (stored == null) return ProviderRegistry.isBootstrapProvider(provider.id)
        return runCatching { stored == fingerprint(provider) }.getOrDefault(false)
    }
}

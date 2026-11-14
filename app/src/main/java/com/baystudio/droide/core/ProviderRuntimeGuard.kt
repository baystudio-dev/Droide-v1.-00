package com.baystudio.droide.core

import java.net.URI

 
internal object ProviderRuntimeGuard {
    fun requireBoundProvider(
        providerId: String,
        configuredBaseUrl: String,
        providerConfiguration: ProviderRegistry.ConfiguredSnapshot? = null,
    ): AiProvider {
        val provider = ProviderRegistry.findById(providerId, providerConfiguration)
            ?: throw IllegalArgumentException("Unknown provider: $providerId")
        require(endpointIdentity(configuredBaseUrl) == endpointIdentity(provider.baseUrl)) {
            "Provider endpoint no longer matches the active provider registry; reselect the provider"
        }
        return provider
    }

    fun requireCertifiedSelection(
        providerId: String,
        configuredBaseUrl: String,
        modelId: String,
        providerConfiguration: ProviderRegistry.ConfiguredSnapshot? = null,
    ): AiProvider {
        val canonicalModel = ProviderModelIdentity.requireCanonical(modelId)
        val provider = requireBoundProvider(providerId, configuredBaseUrl, providerConfiguration)
        ProviderModelCertificationRegistry.requireCertified(provider, canonicalModel)
        ProviderModelRequestPolicy.requireConversational(provider, canonicalModel)
        return provider
    }

    fun requireCertifiedSelection(providerId: String, modelId: String): AiProvider {
        val canonicalModel = ProviderModelIdentity.requireCanonical(modelId)
        val provider = ProviderModelCertificationRegistry.requireCertified(providerId, canonicalModel)
        ProviderModelRequestPolicy.requireConversational(provider, canonicalModel)
        return provider
    }

    internal fun endpointIdentity(raw: String): String {
        val uri = URI(raw.trim()).normalize()
        require(uri.isAbsolute) { "Provider endpoint must be absolute" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Provider endpoint contains unsupported URL components" }
        val scheme = uri.scheme?.lowercase() ?: error("Provider endpoint scheme is missing")
        val host = uri.host?.lowercase() ?: error("Provider endpoint host is missing")
        val defaultPort = when (scheme) { "https" -> 443; "http" -> 80; else -> -1 }
        val port = uri.port.takeIf { it >= 0 && it != defaultPort }
        val path = uri.rawPath.orEmpty().let { value ->
            when {
                value.isBlank() || value == "/" -> ""
                else -> value.trimEnd('/')
            }
        }
        return buildString {
            append(scheme).append("://").append(host)
            port?.let { append(':').append(it) }
            append(path)
        }
    }
}

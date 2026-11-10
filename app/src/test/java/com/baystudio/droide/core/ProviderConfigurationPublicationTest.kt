package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderConfigurationPublicationTest {
    @After fun clearConfiguration() = ProviderRegistry.installConfigured(emptyList())

    @Test fun preparingCandidateLeavesActiveProviderUntouchedUntilPublication() {
        val original = provider("https://project-a.example/v1")
        ProviderRegistry.installConfigured(listOf(original))

        val candidate = ProviderRegistry.prepareConfigured(listOf(provider("https://project-b.example/v1")))

        assertEquals(original, ProviderRegistry.findById(original.id))
        ProviderRegistry.publishConfigured(candidate)
        assertEquals("https://project-b.example/v1", ProviderRegistry.findById(original.id)?.baseUrl)
    }

    @Test fun preparedConfigurationOwnsItsCollectionsAndCannotBeChangedByCaller() {
        val env = mutableListOf("API_TOKEN")
        val models = mutableListOf("model-a")
        val providers = mutableListOf(provider("https://project-a.example/v1").copy(
            catalogEnv = env, configuredModels = models,
        ))
        val candidate = ProviderRegistry.prepareConfigured(providers)
        env.clear()
        models[0] = "model-b"
        providers.clear()

        ProviderRegistry.publishConfigured(candidate)

        val published = checkNotNull(ProviderRegistry.findById("custom.project"))
        assertEquals(listOf("API_TOKEN"), published.catalogEnv)
        assertEquals(listOf("model-a"), published.configuredModels)
        assertTrue(runCatching { (published.configuredModels as MutableList<String>).clear() }.isFailure)
        assertTrue(runCatching { (candidate.providers as MutableList<AiProvider>).clear() }.isFailure)
    }

    @Test fun candidateCatalogKeepsLocalBuiltinsAndParseFailureDoesNotPublish() {
        val root = Files.createTempDirectory("provider-candidate-").toFile()
        try {
            val original = provider("https://project-a.example/v1")
            ProviderRegistry.installConfigured(listOf(original))
            val file = root.resolve(".droide/providers.json").apply { parentFile!!.mkdirs() }
            file.writeText("""{"providers":[{"id":"custom.project","name":"Project","baseUrl":"https://project-b.example/v1","auth":"none"}]}""")
            val candidate = ProviderEndpointCatalog.prepare(root)
            assertEquals(original, ProviderRegistry.findById(original.id))
            assertEquals(setOf("ollama", "lmstudio", "vllm", "custom.project"), candidate.providers.map { it.id }.toSet())
            file.writeText("""{"providers":[{"id":"openai"}]}""")

            assertTrue(runCatching { ProviderEndpointCatalog.prepare(root) }.isFailure)
            assertEquals(original, ProviderRegistry.findById(original.id))
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun runtimeSnapshotNeverResolvesAnotherProjectsConfiguredNamespace() {
        val original = provider("https://project-a.example/v1")
        val snapshot = ProviderRegistry.prepareConfigured(listOf(original))
        val candidateOnly = provider("https://project-b.example/v1").copy(id = "custom.second-only")
        ProviderRegistry.installConfigured(listOf(candidateOnly))

        assertEquals(original, ProviderRegistry.findById(original.id, snapshot))
        assertNull(ProviderRegistry.findById(candidateOnly.id, snapshot))
        assertEquals(candidateOnly, ProviderRegistry.findById(candidateOnly.id))
        assertEquals(ProviderRegistry.findById("openai"), ProviderRegistry.findById("openai", snapshot))
    }

    @Test fun runtimeSnapshotStillChecksEndpointIdentityAndCurrentCertification() {
        val original = provider("https://project-a.example/v1")
        val snapshot = ProviderRegistry.prepareConfigured(listOf(original))
        ProviderModelCertificationRegistry.certify(original, listOf("model-a"))
        try {
            ProviderRegistry.installConfigured(listOf(provider("https://project-b.example/v1")))
            assertEquals(original, ProviderRuntimeGuard.requireCertifiedSelection(
                original.id, original.baseUrl, "model-a", snapshot,
            ))
            assertTrue(runCatching {
                ProviderRuntimeGuard.requireCertifiedSelection(
                    original.id, "https://project-b.example/v1", "model-a", snapshot,
                )
            }.isFailure)
            ProviderModelCertificationRegistry.revoke(original.id)
            assertTrue(runCatching {
                ProviderRuntimeGuard.requireCertifiedSelection(original.id, original.baseUrl, "model-a", snapshot)
            }.isFailure)
        } finally {
            ProviderModelCertificationRegistry.revoke(original.id)
        }
    }

    private fun provider(url: String) = AiProvider(
        "custom.project", "Project", url, "", false, false, "", "",
        authScheme = ProviderAuthScheme.NONE,
    )
}

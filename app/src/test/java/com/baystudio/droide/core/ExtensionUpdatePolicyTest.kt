package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExtensionUpdatePolicyTest {
    private fun items(family: ExtensionFamily): List<ExtensionVersionState> =
        (DevelopmentExtensionProjection.localVersions(family) + family.versions).map {
            ExtensionVersionState(family, it, ExtensionState.AVAILABLE, "")
        }

    @Test fun everyPublisherButtonHasAnExecutableUpdateRoute() {
        val root = File(System.getProperty("droide.source", "."))
        val document = Json.decodeFromString<DeclarativePackageCatalogDocument>(
            File(root, "app/src/main/assets/droide-package-catalog-v1.json").readText())
        val sources = PackageSourceCatalog.fromDocument(document)
        for (family in DevelopmentExtensionCatalog.families) {
            val policy = ExtensionUpdatePolicy.forFamily(family)
            assertTrue(family.id, policy.description.isNotBlank())
            if (!policy.hasVersionPicker) continue
            val selected = requireNotNull(ToolVersionRequests.select(items(family), family.id, "latest"))
            assertEquals(family.id + ": " + selected.detail, ExtensionState.AVAILABLE, selected.state)
            when (selected.version.installKind) {
                ExtensionInstallKind.USER_MANAGED -> assertTrue(family.id,
                    ToolUpdatePlanner.recipe(family, "latest", sources.find(family.id)).command.isNotBlank())
                ExtensionInstallKind.REVIEWED_RECIPE -> assertTrue(family.id,
                    family.id == "runtime.node" || LocalUbuntuVendorCatalog.find(family.id) != null ||
                        UbuntuNpmCatalog.find(family.id) != null)
                else -> fail("Unexpected publisher update installer: ${family.id}: ${selected.version.installKind}")
            }
        }
    }

    @Test fun parentLinksResolveAndDoNotFormCyclesOrPointAtThemselves() {
        for (family in DevelopmentExtensionCatalog.families) {
            val seen = mutableSetOf(family.id)
            var policy = ExtensionUpdatePolicy.forFamily(family)
            while (policy.route == ExtensionUpdatePolicy.Route.PARENT) {
                val parent = policy.relatedFamilies.single()
                assertTrue(parent, seen.add(parent))
                policy = ExtensionUpdatePolicy.forFamily(requireNotNull(DevelopmentExtensionCatalog.byId(parent)))
            }
            policy.relatedFamilies.forEach { assertNotNull(it, DevelopmentExtensionCatalog.byId(it)) }
        }
    }

    @Test fun coordinatedIntegrationsDoNotMisrepresentLatestAsTheirHistoricalVersion() {
        for (id in listOf("runtime.csharp-sdk", "lsp.csharp-ls", "lsp.vue", "lsp.graphql-library", "lsp.sqruff")) {
            val family = requireNotNull(DevelopmentExtensionCatalog.byId(id))
            assertFalse(ExtensionUpdatePolicy.forFamily(family).hasVersionPicker)
            val selected = requireNotNull(ToolVersionRequests.select(items(family), id, "latest"))
            assertEquals("latest", selected.version.version)
            assertEquals(ExtensionState.UNAVAILABLE, selected.state)
        }
    }

    @Test fun projectDependenciesAndExternalManagersAreNotRunAsGlobalInstallations() {
        for (id in listOf("framework.react", "framework.spring", "framework.rails", "lsp.haskell-hls", "lsp.metals")) {
            val family = requireNotNull(DevelopmentExtensionCatalog.byId(id))
            val policy = ExtensionUpdatePolicy.forFamily(family)
            assertFalse(policy.hasVersionPicker)
            assertEquals(ExtensionState.UNAVAILABLE,
                ToolVersionRequests.select(items(family), id, "latest")!!.state)
            if (policy.route == ExtensionUpdatePolicy.Route.UPSTREAM_MANAGER)
                assertTrue(policy.documentationUrl.orEmpty().startsWith("https://"))
        }
    }

    @Test fun allNineExternalAgentsRetainIndependentPublisherUpdates() {
        assertEquals(9, WorkspaceAgentPluginCatalog.entries.size)
        for (agent in WorkspaceAgentPluginCatalog.entries) {
            val family = requireNotNull(DevelopmentExtensionCatalog.byId(agent.familyId))
            assertTrue(agent.id, ExtensionUpdatePolicy.forFamily(family).hasVersionPicker)
            val selected = requireNotNull(ToolVersionRequests.select(items(family), family.id, "99.1.2"))
            assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, selected.version.installKind)
            assertEquals("99.1.2", selected.version.version)
        }
    }

    @Test fun solidityHasTheOfficialPublisherCommandAndAnUnpinnedVersionSelector() {
        val entry = requireNotNull(UbuntuNpmCatalog.find("lsp.nomicfoundation-solidity-language-server"))
        assertEquals("@nomicfoundation/solidity-language-server", entry.packageName)
        assertEquals(LspSolidityProfile.COMMAND, entry.command)
        assertEquals(UbuntuNpmCatalog.VERSION, entry.offeredVersion)
    }
}

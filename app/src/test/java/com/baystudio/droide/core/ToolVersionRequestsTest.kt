package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ToolVersionRequestsTest {
    private fun offer(id: String, selector: String, state: ExtensionState = ExtensionState.AVAILABLE) =
        ExtensionVersionState(ExtensionFamily(id, id, ExtensionCategory.LANGUAGE_SERVERS, "",
            listOf(ExtensionVersion(selector)), "generic"),
            ExtensionVersion(selector, installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                scope = ExecutionScope.LOCAL_LINUX_ARM64), state, "Root recovery or device compatibility requires attention")

    @Test fun everyExternalAgentCanRequestPublisherUpdatesThroughManagedInstallation() {
        for (spec in WorkspaceAgentPluginCatalog.entries) {
            val family = DevelopmentExtensionCatalog.families.single { it.id == spec.familyId }
            val items = DevelopmentExtensionProjection.localVersions(family).map {
                ExtensionVersionState(family, it, ExtensionState.AVAILABLE, "")
            }
            for (requested in listOf("latest", "99.0.0")) {
                val result = requireNotNull(ToolVersionRequests.select(items, family.id, requested))
                assertEquals(spec.id, ExtensionState.AVAILABLE, result.state)
                assertEquals(spec.id, ExtensionInstallKind.REVIEWED_RECIPE, result.version.installKind)
                if (requested != "latest") assertEquals(requested, result.version.version)
            }
        }
    }

    @Test fun unlistedVersionCannotOverrideAnUnavailableWorkstationProvider() {
        val blocked = offer("lsp.ruby-lsp", "0.26.11", ExtensionState.UNAVAILABLE)
        val requested = ToolVersionRequests.select(listOf(blocked), blocked.family.id, "99.0.0")!!
        assertEquals(ExtensionState.UNAVAILABLE, requested.state)
        assertEquals(blocked.detail, requested.detail)
    }

    @Test fun dynamicNativeVersionsPreserveReadinessFailuresButAllowHealthyFutureVersions() {
        val blocked = offer("lsp.kotlin", LocalUbuntuVendorPolicy.VERSION_CHOICE, ExtensionState.UNAVAILABLE)
        assertEquals(ExtensionState.UNAVAILABLE, ToolVersionRequests.select(listOf(blocked), "lsp.kotlin", "latest")!!.state)
        val healthy = blocked.copy(state = ExtensionState.AVAILABLE)
        val requested = ToolVersionRequests.select(listOf(healthy), "lsp.kotlin", "9.7.1")!!
        assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, requested.version.installKind)
        assertEquals("9.7.1", requested.version.version)
        assertEquals(ExtensionState.AVAILABLE, requested.state)
    }

    @Test fun npmPublisherTagRemainsARequestNotAFabricatedResolvedVersion() {
        val base = offer("plugin.codex", UbuntuNpmCatalog.VERSION)
        val requested = ToolVersionRequests.select(listOf(base), "plugin.codex", "next")!!
        assertEquals("next", requested.version.version)
        assertEquals(ExtensionState.AVAILABLE, requested.state)
        assertNotEquals(ExtensionState.INSTALLED, requested.state)
    }

    @Test fun makeOnlyReturnsTheExactReviewedOfferAndNeverSubstitutesAnotherRequest() {
        for (state in listOf(ExtensionState.AVAILABLE, ExtensionState.UNAVAILABLE, ExtensionState.INSTALLED)) {
            val exact = offer(LocalUbuntuMakePolicy.FAMILY, LocalUbuntuMakePolicy.VERSION, state)
            assertEquals(exact, ToolVersionRequests.select(listOf(exact), exact.family.id, LocalUbuntuMakePolicy.VERSION))
            for (requested in listOf("latest", "0.1.24", "0.1.26", "99.0.0")) {
                val result = ToolVersionRequests.select(listOf(exact), exact.family.id, requested)!!
                assertEquals(requested, result.version.version)
                assertEquals(ExtensionState.UNAVAILABLE, result.state)
                assertTrue(result.detail.contains("no version was substituted"))
            }
        }
    }

    @Test fun makeGenericUpdateCannotBypassTheExplicitReviewedInstaller() {
        val family = offer(LocalUbuntuMakePolicy.FAMILY, LocalUbuntuMakePolicy.VERSION).family
        for (requested in listOf(LocalUbuntuMakePolicy.VERSION, "latest", "0.1.26")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                ToolUpdatePlanner.recipe(family, requested, null)
            }
            assertTrue(error.message.orEmpty().contains("explicit reviewed Make LS"))
        }
    }

    @Test fun bothPowerShellFamiliesPreserveExactOfferAndRefuseUnreviewedRequests() {
        for ((id, version) in listOf(LocalUbuntuPowerShellRuntimePolicy.FAMILY to LocalUbuntuPowerShellRuntimePolicy.VERSION,
            LocalUbuntuPowerShellEditorServicesPolicy.FAMILY to LocalUbuntuPowerShellEditorServicesPolicy.VERSION)) {
            for (state in listOf(ExtensionState.AVAILABLE, ExtensionState.UNAVAILABLE, ExtensionState.INSTALLED)) {
                val exact=offer(id,version,state)
                assertEquals(exact,ToolVersionRequests.select(listOf(exact),id,version))
                for (request in listOf("latest","1.0.0","99.0.0")) {
                    val result=ToolVersionRequests.select(listOf(exact),id,request)!!
                    assertEquals(request,result.version.version);assertEquals(ExtensionState.UNAVAILABLE,result.state)
                    assertTrue(result.detail.contains("no version was substituted"))
                }
            }
        }
    }
    @Test fun neitherPowerShellFamilyCanBypassPinnedInstallViaGenericUpdater() {
        for ((id, version) in listOf(LocalUbuntuPowerShellRuntimePolicy.FAMILY to LocalUbuntuPowerShellRuntimePolicy.VERSION,
            LocalUbuntuPowerShellEditorServicesPolicy.FAMILY to LocalUbuntuPowerShellEditorServicesPolicy.VERSION)) {
            for (request in listOf(version,"latest","99.0.0")) {
                val error=assertThrows(IllegalArgumentException::class.java) { ToolUpdatePlanner.recipe(offer(id,version).family,request,null) }
                assertTrue(error.message.orEmpty().contains("generic updates cannot substitute"))
            }
        }
    }
}

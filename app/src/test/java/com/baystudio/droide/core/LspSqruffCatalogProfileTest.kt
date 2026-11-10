package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspSqruffCatalogProfileTest {
    @Test fun registryVendorCatalogAndManagedProjectionShareOnePinnedNativeCommand() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("lsp.sqruff"))
        val vendor = requireNotNull(LocalUbuntuVendorCatalog.find(family.id))
        val plan = requireNotNull(ManagedLanguageServerPlan.forServer("sqruff"))
        assertEquals("0.41.0", vendor.pinnedVersion); assertEquals(plan.pinnedVersion, vendor.offeredVersion)
        assertEquals("sqruff", vendor.command); assertEquals(plan.command, vendor.command)
        assertEquals(LocalUbuntuVendorFormat.TAR_GZ, vendor.format); assertEquals(LocalUbuntuVendorAdmission.STATIC_ARM64, vendor.admission)
        assertEquals(listOf("ca-certificates", "nodejs"), vendor.packages)
        assertEquals(1, vendor.maxFiles); assertTrue(vendor.auxiliaryCommands.isEmpty()); assertFalse(vendor.supportsPublisherVersionSelection)
        assertEquals(setOf("sql"), LanguageServerRegistry.builtIns.single { it.id == "sqruff" }.languageIds)
        val managed = DevelopmentExtensionProjection.localVersions(family).single()
        assertEquals("0.41.0", managed.version); assertEquals("publisher-ubuntu", managed.channel)
        assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, managed.installKind); assertEquals(setOf("sqruff"), managed.provides)
        for (term in listOf("ARM64-musl", "publisher SHA-256", "outside-root", "inline directives", "Python", "identity", "zero-document", "pending", "restart", "database"))
            assertTrue(term, managed.note.contains(term))
        assertFalse(DevelopmentExtensionProjection.canDetectExternal(managed))
    }

    @Test fun externalDetectionCannotClaimNativePinOrManagedRepairs() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("lsp.sqruff")); val external = family.versions.single()
        assertEquals("SQL · sqruff", family.name)
        for (term in listOf("lint", "semantic", "formatting", "No completion", "pending")) assertTrue(term, family.description.contains(term))
        for (term in listOf("provenance remains unverified", "does not establish", "managed identity", "outside-root", "consent"))
            assertTrue(term, external.note.contains(term))
        assertEquals("External", external.version); assertEquals("user-managed", external.channel)
        assertEquals(setOf("sqruff"), external.provides); assertTrue(DevelopmentExtensionProjection.canDetectExternal(external))
        assertNull(DevelopmentExtensionProjection.userInstalledChoice(family))
    }
}

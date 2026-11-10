package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspPrismaCatalogProfileTest {
    @Test fun registryCatalogAndRecipeProjectTheSameExactLegacyCommand() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("lsp.prisma-language-server"))
        val recipe = requireNotNull(UbuntuNpmCatalog.find(family.id))
        val plan = requireNotNull(ManagedLanguageServerPlan.forServer("prisma-language-server"))
        assertEquals("@prisma/language-server", recipe.packageName)
        assertEquals("31.13.0", recipe.reviewedVersion)
        assertEquals(plan.pinnedVersion, recipe.offeredVersion)
        assertEquals(plan.command, recipe.command)
        assertFalse(recipe.allowLifecycleScripts)
        val managed = DevelopmentExtensionProjection.localVersions(family).single()
        assertEquals("31.13.0", managed.version)
        assertEquals("npm-reviewed", managed.channel)
        assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, managed.installKind)
        assertEquals(setOf("prisma-language-server"), managed.provides)
        for (term in listOf("43 exact dependencies", "Node 22+/npm 10", "transport-only", "Legacy", "Next/ORM 8", "unverified", "does not attest"))
            assertTrue(term, managed.note.contains(term))
    }

    @Test fun externalCommandDetectionRemainsUnversionedAndSeparateFromManagedPin() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("lsp.prisma-language-server"))
        val external = family.versions.single()
        assertEquals("Prisma legacy schemas", family.name)
        assertTrue(family.description.contains("Next/ORM 8 is unavailable"))
        assertTrue(external.note.contains("provenance remains unverified"))
        assertTrue(external.note.contains("does not establish"))
        assertFalse(external.note.contains("verified editor integration"))
        assertEquals("External", external.version)
        assertEquals("user-managed", external.channel)
        assertTrue(DevelopmentExtensionProjection.canDetectExternal(external))
        assertFalse(DevelopmentExtensionProjection.canDetectExternal(DevelopmentExtensionProjection.localVersions(family).single()))
        assertNull(DevelopmentExtensionProjection.userInstalledChoice(family))
        assertEquals(setOf("prisma-language-server"), external.provides)
    }
}

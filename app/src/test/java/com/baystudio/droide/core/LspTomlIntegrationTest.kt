package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspTomlIntegrationTest {
    private val native get() = LanguageServerRegistry.builtIns.single { it.id == LspTomlProfile.ID }

    @Test fun oneExactManagedRouteDoesNotIntroduceLockfileAliases() {
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertEquals(55, LanguageRegistry.all.size)
        for (path in listOf("config.toml", "nested/config.TOML")) {
            assertEquals(listOf(native), LanguageServerRegistry.forPath(path))
            assertEquals("toml", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("Cargo.lock", "Pipfile", "Pipfile.lock", "config.toml.bak"))
            assertFalse(path, LanguageServerRegistry.forPath(path).any { it.id == native.id })
        assertEquals(listOf(listOf("droide-taplo-lsp")), native.commands)
        assertTrue(LspServerProfiles.isToml(native))
    }

    @Test fun everyCustomTomlRouteRequiresConsentWithoutManagedGuarantees() {
        val custom = native.copy(source = "custom", commands = listOf(listOf("custom-toml")))
        val variants = listOf(custom, custom.copy(id = "different"),
            LanguageServerSpec("other", emptySet(), setOf("toml"), listOf(listOf("other")), "custom"),
            LanguageServerSpec("named", emptySet(), emptySet(), listOf(listOf("named")), "custom", setOf("special.toml")))
        for (spec in variants) {
            assertFalse(LspServerProfiles.isToml(spec))
            assertTrue(LspServerProfiles.requiresProjectAnalysis(spec, "special.toml"))
            assertTrue(LspServerProfiles.approvalExplanation(spec, path = "special.toml").contains("unverified"))
        }
        assertEquals(custom, LanguageServerRegistry.forPath("a.toml", listOf(custom)).first())
        assertFalse(LspServerProfiles.isToml(native.copy(commands = listOf(listOf("droide-taplo-lsp", "--stdio")))))
        assertFalse(LspServerProfiles.isToml(native.copy(filenames = setOf("Cargo.lock"))))
    }

    @Test fun nativeConfigurationIdentityIncludesFixedProfileAndExactCommand() {
        val identity = LspServerProfiles.analysisConfiguration(native)
        assertTrue(identity.contains(LspTomlProfile.IDENTITY))
        assertNotEquals(identity, LspServerProfiles.analysisConfiguration(native.copy(commands = listOf(listOf("other")))))
        assertTrue(LspServerProfiles.installationDisclosure(native)!!.contains("22.23.3"))
        assertTrue(LspServerProfiles.optionalToolNote(native)!!.contains("same-document"))
    }

    @Test fun declarativeDisclosureIsExplicitAndPriorDefaultRemainsExact() {
        val prior = LspProjectAnalysisRequest("old", "/workspace", "/workspace", listOf(listOf("old")), "Explain.")
        assertTrue(prior.detail.contains("Project code and configuration can execute with the local Ubuntu user's file and network access. Only enable"))
        val reviewed = prior.copy(executionDisclosure = "Read admitted immutable snapshots.")
        assertTrue(reviewed.detail.contains("Read admitted immutable snapshots. Only enable"))
        assertFalse(reviewed.detail.contains("Project code and configuration can execute"))
        assertTrue(reviewed.detail.endsWith(prior.installationDisclosure))
    }
}

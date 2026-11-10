package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LspVueProfileTest {
    @Test fun typeDefinitionRemainsAnExplicitOptIn() {
        val response = kotlinx.serialization.json.Json.parseToJsonElement("""{"capabilities":{"typeDefinitionProvider":true}}""")
        assertFalse(LspNegotiatedCapabilities.fromInitialize(response).supportsOperation("goToTypeDefinition"))
        assertTrue(LspNegotiatedCapabilities.fromInitialize(response, enableTypeDefinition = true).supportsOperation("goToTypeDefinition"))
    }

    @Test fun exactRouteNeverReplacesTypeScript() {
        assertEquals(LspVueProfile.ID, LanguageServerRegistry.forPath("A.vue").single().id)
        assertTrue(LanguageServerRegistry.forPath("A.VUE").none(LspServerProfiles::isVue))
        assertEquals("typescript", LanguageServerRegistry.forPath("a.ts").single().id)
        assertEquals(listOf(listOf("typescript-language-server", "--stdio")), LanguageServerRegistry.forPath("a.ts").single().commands)
    }
    @Test fun customOverrideHasSeparateGrantWithoutManagedGuarantees() {
        val custom = LanguageServerSpec(LspVueProfile.ID, setOf("vue"), setOf("vue"), listOf(listOf("custom-vue")), "workspace")
        val selected = LanguageServerRegistry.forPath("a.vue", listOf(custom)).single()
        assertEquals(custom, selected)
        assertFalse(LspServerProfiles.isVue(selected))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(selected, "a.vue"))
        assertNull(LspBackgroundPreparationPolicy.forSpec(selected))
    }
    @Test fun rootAndMissingParentNeverSearchUpward() {
        val root = Files.createTempDirectory("vue-root-").toFile().canonicalFile
        try {
            val nested = File(root, "nested/deeper"); nested.mkdirs()
            File(nested, "tsconfig.json").writeText("{}")
            val document = File(nested, "a.vue")
            assertEquals(root, LspVueProfile.projectRoot(root, document))
            nested.deleteRecursively()
            assertEquals(root, LspVueProfile.projectRoot(root, document))
            assertFalse(LspVueProfile.documentAvailable(root, "nested/deeper/a.vue"))
            assertTrue(LspVueProfile.projectAvailable(root))
            root.deleteRecursively()
            assertEquals(root, LspVueProfile.projectRoot(root, document))
            assertFalse(LspVueProfile.projectAvailable(root))
        } finally { root.deleteRecursively() }
    }
    @Test fun boundedPreparationAndHonestConsent() {
        val spec = LanguageServerRegistry.forPath("a.vue").single()
        assertEquals(45_000L, LspBackgroundPreparationPolicy.forSpec(spec)!!.attemptMs)
        assertTrue(LspVueProfile.APPROVAL_EXPLANATION.contains("ongoing"))
        assertTrue(LspVueProfile.APPROVAL_EXPLANATION.contains("ancestor"))
        assertTrue(LspVueProfile.APPROVAL_EXPLANATION.contains("not a sandbox"))
        assertTrue(LspVueProfile.FEATURE_NOTE.contains("external/transitive"))
    }
}

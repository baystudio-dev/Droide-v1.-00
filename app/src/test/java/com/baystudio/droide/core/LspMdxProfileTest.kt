package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspMdxProfileTest {
    @Test fun sdkComesFromTheSelectedInstallationAndNeverFromTheProject() {
        val root = "/opt/droide/packages/lsp.mdx-language-server/0.6.4"
        val options = LspMdxProfile.initializationOptions("$root/payload/bin/mdx-language-server")
        assertEquals("$root/payload/npm/node_modules/typescript/lib", options["typescript"]!!.jsonObject["tsdk"]!!.jsonPrimitive.content)
        assertFalse(options["typescript"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        for (path in listOf("/usr/bin/mdx-language-server", "/workspace/node_modules/.bin/mdx-language-server",
            "$root/../other/payload/bin/mdx-language-server", "$root/payload/bin/mdx-language-server\n")) {
            assertThrows(IllegalArgumentException::class.java) { LspMdxProfile.initializationOptions(path) }
        }
    }

    @Test fun projectPluginExecutionRequiresAnExplicitGrant() {
        val builtIn = LanguageServerRegistry.forPath("Page.mdx").single()
        assertTrue(LspServerProfiles.requiresProjectAnalysis(builtIn, "Page.mdx"))
        assertTrue(LspServerProfiles.approvalExplanation(builtIn).contains("plugins"))
        val custom = builtIn.copy(id = "custom", source = "user")
        assertFalse(LspMdxProfile.matches(custom))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom, "Page.mdx"))
    }

    @Test fun healthProvidesTheSameSiblingSdkWithoutChangingOtherServerProbes() {
        assertTrue(LspMdxProfile.healthScript.contains("npm/node_modules/typescript/lib"))
        assertFalse(LocalUbuntuNpmLspHealth.script.contains("npm/node_modules/typescript/lib"))
        assertTrue(LspMdxProfile.healthScript.contains("rootUri: null, workspaceFolders: null"))
    }

    @Test fun installationRequiresTheSdkAndUsesItsOwnHealthProbe() {
        val entry = requireNotNull(UbuntuNpmCatalog.find(LspMdxProfile.FAMILY))
        val version = requireNotNull(entry.reviewedVersion)
        val recipe = WorkstationInstallRecipe(entry.familyId, version, entry.packageName, version, entry.command,
            22, entry.provenance, registryIntegrity = entry.reviewedIntegrity,
            additionalDependencies = mapOf("typescript" to LocalUbuntuTypeScriptServerPolicy.VERSION))
        assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(additionalDependencies = emptyMap())))
        val root = java.nio.file.Files.createTempDirectory("mdx-health").toFile()
        try {
            val probe = root.resolve("payload/${LocalUbuntuNpmLspHealth.FILE_NAME}")
            probe.parentFile.mkdirs()
            probe.writeText(LocalUbuntuNpmLspHealth.script)
            assertFalse(LocalUbuntuNpmPolicy.verifyLanguageServerProbe(root, recipe))
            probe.writeText(LspMdxProfile.healthScript)
            assertTrue(LocalUbuntuNpmPolicy.verifyLanguageServerProbe(root, recipe))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

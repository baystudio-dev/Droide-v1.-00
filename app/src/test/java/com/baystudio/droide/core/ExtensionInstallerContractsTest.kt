package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExtensionInstallerContractsTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe contract accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun aptRecipesHaveBoundedCommandAndPackageArguments() {
        assertTrue(UbuntuAptCatalog.entries.size >= 65)
        assertEquals(UbuntuAptCatalog.entries.size, UbuntuAptCatalog.entries.map { it.familyId }.distinct().size)
        UbuntuAptCatalog.entries.forEach { it.validate() }
        val recipe = UbuntuAptCatalog.find("cli.git")!!
        rejects { recipe.copy(packages = listOf("--allow-unauthenticated")).validate() }
        rejects { recipe.copy(packages = listOf("git;touch /tmp/injected")).validate() }
        rejects { recipe.copy(commands = mapOf("git" to "/usr/bin/../bin/sh")).validate() }
        rejects { recipe.copy(commandArgs = mapOf("missing-command" to listOf("--version"))).validate() }
        rejects { recipe.copy(commandArgs = mapOf("git" to listOf("line\nbreak"))).validate() }
        rejects { recipe.copy(commandArgs = mapOf("git" to List(9) { "extra" })).validate() }
    }
    @Test fun languageCompilerMappingsDoNotConfuseSyntaxWithExecution() {
        assertEquals("toolchain.kotlin", UbuntuAptCatalog.languageRuntime["kotlin"])
        assertEquals("toolchain.typescript", UbuntuAptCatalog.languageRuntime["typescript"])
        assertEquals("runtime.node", UbuntuAptCatalog.languageRuntime["react"])
        assertNull(UbuntuAptCatalog.languageRuntime["json"])
    }
    @Test fun npmCatalogAdmitsOnlyKnownPackagesWithScriptsBlocked() {
        assertTrue(UbuntuNpmCatalog.entries.size >= 16)
        assertEquals(UbuntuNpmCatalog.entries.size, UbuntuNpmCatalog.entries.map { it.familyId }.distinct().size)
        val entry = UbuntuNpmCatalog.find("quality.prettier")!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "3.7.0", entry.packageName, "3.7.0", entry.command, 22, entry.provenance)
        assertTrue(UbuntuNpmCatalog.supports(recipe))
        assertFalse(UbuntuNpmCatalog.supports(recipe.copy(packageName = "unexpected")))
        assertFalse(UbuntuNpmCatalog.supports(recipe.copy(allowLifecycleScripts = true)))
        assertFalse(UbuntuNpmCatalog.supports(recipe.copy(provenanceUrl = "https://example.com/unreviewed")))
    }
    @Test fun officialNodeRecipeIsExactAndUsesPublisherArchiveBounds() {
        val line = "a".repeat(64) + "  node-v22.23.3-linux-arm64.tar.gz"
        val recipe = LocalUbuntuNodeDistribution.resolve(line)
        assertEquals("22.23.3", recipe.version)
        assertEquals(8192, recipe.treeMaxFiles)
        assertEquals(emptyList<String>(), recipe.treeExecutablePaths)
        recipe.validate()
        val urlRecipe = recipe.copy(treeMaxFiles = 4096)
        rejects { urlRecipe.copy(assetUrl = "http://nodejs.org/archive.tar.gz").validate() }
        rejects { urlRecipe.copy(assetUrl = "https://127.0.0.1/archive.tar.gz").validate() }
        rejects { urlRecipe.copy(assetUrl = "https://user:secret@nodejs.org/archive.tar.gz").validate() }
        rejects { LocalUbuntuNodeDistribution.resolve(line + "\n" + line) }
        assertEquals("24.1.0", LocalUbuntuNodeDistribution.resolve(line.replace("22.23.3", "24.1.0")).version)
        rejects { recipe.copy(familyId = "unexpected").validate() }
    }
    @Test fun onlyPinnedNodeNpmAndNpxScriptsBypassElfAdmission() {
        val file = Files.createTempFile("droide-node-cli", ".js").toFile()
        try {
            file.writeText("#!/usr/bin/env node\nconsole.log('version')\n")
            assertTrue(LocalUbuntuNodeDistribution.isAuxScript("runtime.node", "22.23.3", "npm", "node-v22.23.3-linux-arm64/bin/npm", file))
            assertFalse(LocalUbuntuNodeDistribution.isAuxScript("runtime.python", "22.23.3", "npm", "node-v22.23.3-linux-arm64/bin/npm", file))
            assertFalse(LocalUbuntuNodeDistribution.isAuxScript("runtime.node", "22.23.3", "node", "node-v22.23.3-linux-arm64/bin/node", file))
            file.writeText("#!/bin/sh\nexit 0\n")
            assertFalse(LocalUbuntuNodeDistribution.isAuxScript("runtime.node", "22.23.3", "npm", "node-v22.23.3-linux-arm64/bin/npm", file))
        } finally { file.delete() }
    }
    @Test fun progressTailIsBoundedAndSplitCredentialsAreMasked() {
        val log = InstallerProgressLog(256)
        log.append("Authorization: Be")
        val tail = log.append("arer sensitive-secret\nhttps://user:password@example.com/a\n")
        assertFalse(tail.contains("sensitive-secret"))
        assertFalse(tail.contains("user:password"))
        log.append("token: sk-abc")
        assertFalse(log.append("defghijklmnop\n").contains("sk-abcdefghijklmnop"))
        assertTrue(log.append("x".repeat(20000)).length <= 256)
        log.clear(); assertEquals("", log.append(""))
    }

    @Test fun authorizationSchemesAndSplitBasicCredentialsAreMasked() {
        val log = InstallerProgressLog()
        log.append("Authorization: Basic dXNlcj")
        val tail = log.append("pwYXNzd29yZA==\nProxy-Authorization: Digest username=fixture, response=private-digest\nnext stage\n")
        assertFalse(tail.contains("dXNlcj"))
        assertFalse(tail.contains("pwYXNzd29yZA=="))
        assertFalse(tail.contains("private-digest"))
        assertTrue(tail.contains("next stage"))
    }
}

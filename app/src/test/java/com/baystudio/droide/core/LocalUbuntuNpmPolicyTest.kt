package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuNpmPolicyTest {
    @Test fun sharedDigestBufferProducesCanonicalHashesForEveryFile() = runBlocking {
        val root = Files.createTempDirectory("droide-npm-hashes").toFile()
        try {
            File(root, "empty").writeBytes(byteArrayOf())
            File(root, "abc").writeText("abc")
            File(root, "unsigned").writeBytes(ByteArray(256) { it.toByte() })
            val files = LocalUbuntuNpmPolicy.tree(root)["files"]!!.jsonObject
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", files["empty"]!!.jsonPrimitive.content)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", files["abc"]!!.jsonPrimitive.content)
            assertEquals("40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880", files["unsigned"]!!.jsonPrimitive.content)
            for (name in listOf("empty", "abc", "unsigned")) assertEquals(files[name]!!.jsonPrimitive.content, LocalUbuntuNpmPolicy.sha256(File(root, name)))
        } finally { root.deleteRecursively() }
    }
    @Test fun npmUsesItsOwnRecoverableLocalTransactionPaths() {
        val paths = LocalPackageInstallJournal.paths("/app", "plugin.codex", "2.1.0", LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        assertEquals("/app/packages/.staging", paths.stage.parent)
        assertTrue(paths.stage.name.matches(Regex("plugin\\.codex-2\\.1\\.0-local-ubuntu-npm-[0-9a-f]{24}")))
        assertEquals("/app/packages/plugin.codex/2.1.0/arm64-v8a", paths.final.path)
        assertEquals("/app/packages/.previous", paths.previous.parent)
        assertEquals(paths.stage.name, paths.previous.name)
        assertEquals("/app/packages/.staging/plugin.codex-2.1.0-local-ubuntu-npm", paths.legacyStage?.path)
        assertEquals("/app/packages/.previous/plugin.codex-2.1.0-local-ubuntu-npm", paths.legacyPrevious?.path)
    }

    private val sri = "sha512-" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
    private val recipe = WorkstationInstallRecipeCatalog.find("plugin.opencode", "1.18.34")!!.copy(registryIntegrity = sri)
    private fun lock(rootVersion: String = recipe.version, integrity: String = sri, url: String = "https://registry.npmjs.org/opencode-ai/-/opencode-ai-1.18.34.tgz"): String = buildJsonObject {
        put("lockfileVersion", 3)
        put("packages", buildJsonObject {
            put("", buildJsonObject { put("name", "droide-managed-agent") })
            put("node_modules/opencode-ai", buildJsonObject { put("version", rootVersion); put("integrity", integrity); put("resolved", url) })
        })
    }.toString()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe artifact was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun exactRegistryLockAndOriginalPackageAreRequired() {
        assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
        LocalUbuntuNpmPolicy.validateLock(lock(), recipe)
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(packageName = "@opencode/cli")))
        rejects { LocalUbuntuNpmPolicy.validateLock(lock(rootVersion = "1.0.0"), recipe) }
        rejects { LocalUbuntuNpmPolicy.validateLock(lock(integrity = "sha512-"+java.util.Base64.getEncoder().encodeToString(ByteArray(64))), recipe) }
    }

    @Test fun registryRedirectsCredentialsAndWeakIntegrityAreRejected() {
        for (url in listOf("http://registry.npmjs.org/pkg.tgz", "https://evil.example/pkg.tgz", "https://user@registry.npmjs.org/pkg.tgz", "https://registry.npmjs.org:8443/pkg.tgz")) {
            rejects { LocalUbuntuNpmPolicy.validateLock(lock(url = url), recipe) }
        }
        rejects { LocalUbuntuNpmPolicy.requireIntegrity("sha1-abc") }
    }

    @Test fun cliAndAcpEntriesRemainDistinct() {
        val codex = WorkstationInstallRecipeCatalog.find("plugin.codex", "2.1.0")!!
        assertEquals(listOf("codex"), LocalUbuntuNpmPolicy.commands(codex)["codex"])
        val claude = WorkstationInstallRecipeCatalog.find("plugin.claude-code", "0.84.0")!!
        assertEquals(listOf("claude-agent-acp", "--cli"), LocalUbuntuNpmPolicy.commands(claude)["claude"])
        assertEquals(WorkstationInstallRecipeCatalog.entries.size, WorkstationInstallRecipeCatalog.entries.count(LocalUbuntuNpmPolicy::supports))
    }

    @Test fun sealedFilesAndAdditionalMembersDetectTampering() = runBlocking {
        val root = Files.createTempDirectory("droide-npm").toFile()
        try {
            val data = File(root, "payload/native").apply { parentFile.mkdirs(); writeText("original") }
            val seal = File(root, LocalUbuntuNpmPolicy.SEAL)
            seal.writeText(LocalUbuntuNpmPolicy.tree(root).toString())
            val hash = LocalUbuntuNpmPolicy.sha256(seal)
            assertTrue(LocalUbuntuNpmPolicy.verifyTree(root, hash))
            data.writeText("modified")
            assertFalse(LocalUbuntuNpmPolicy.verifyTree(root, hash))
            data.writeText("original")
            File(root, "extra").writeText("injected")
            assertFalse(LocalUbuntuNpmPolicy.verifyTree(root, hash))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun npmBinSymlinkIsSealedAndCannotEscapePackage() = runBlocking {
        val root = Files.createTempDirectory("droide-npm").toFile()
        val outside = Files.createTempDirectory("droide-external").toFile()
        try {
            File(root, "cli.js").writeText("cli")
            Files.createSymbolicLink(File(root, "cli").toPath(), java.nio.file.Path.of("cli.js"))
            LocalUbuntuNpmPolicy.tree(root)
            Files.delete(File(root, "cli").toPath())
            Files.createSymbolicLink(File(root, "cli").toPath(), outside.toPath())
            try { LocalUbuntuNpmPolicy.tree(root); fail("Escaping symlink accepted") } catch (_: IllegalArgumentException) { }
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(outside) }
    }
    @Test fun typescriptServerPinsCompilerAndUsesFiniteHealthProbe() {
        val entry = UbuntuNpmCatalog.find("lsp.typescript")!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "5.0.0", entry.packageName, "5.0.0", entry.command,
            22, entry.provenance, registryIntegrity = sri, additionalDependencies = mapOf("typescript" to "5.9.3"))
        assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
        assertEquals(listOf("--droide-health"), LocalUbuntuNpmPolicy.healthArgs(recipe))
        assertEquals(listOf("--stdio"), LocalUbuntuNpmPolicy.languageServerArgs(recipe))
        val root = buildJsonObject { put("version", recipe.version); put("resolved", "https://registry.npmjs.org/a.tgz"); put("integrity", sri) }
        val text = buildJsonObject {
            put("lockfileVersion", 3)
            put("packages", buildJsonObject {
                put("", buildJsonObject {})
                put("node_modules/typescript-language-server", root)
                put("node_modules/typescript", buildJsonObject { put("version", "5.9.3"); put("resolved", "https://registry.npmjs.org/b.tgz"); put("integrity", sri) })
            })
        }.toString()
        LocalUbuntuNpmPolicy.validateLock(text, recipe)
        rejects { LocalUbuntuNpmPolicy.validateLock(text.replace("5.9.3", "5.8.0"), recipe) }
    }

    @Test fun pyrightCliAndLanguageServerUseDifferentHealthChecks() {
        val entry = UbuntuNpmCatalog.find("lsp.python-pyright")!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "1.1.407", entry.packageName, "1.1.407", entry.command,
            22, entry.provenance)
        assertEquals(listOf("--droide-health"), LocalUbuntuNpmPolicy.healthArgs(recipe, "pyright-langserver"))
        assertEquals(listOf("--version"), LocalUbuntuNpmPolicy.healthArgs(recipe, "pyright"))
        assertEquals(listOf("--stdio"), LocalUbuntuNpmPolicy.languageServerArgs(recipe))
    }

    @Test fun legacySyntaxOnlyOrReplacedLspHealthProbeCannotVerifyReady() {
        val entry = UbuntuNpmCatalog.find("lsp.python-pyright")!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "1.1.407", entry.packageName, "1.1.407", entry.command,
            22, entry.provenance)
        val root = Files.createTempDirectory("droide-lsp-probe-receipt").toFile()
        try {
            assertFalse(LocalUbuntuNpmPolicy.verifyLanguageServerProbe(root, recipe))
            val helper = File(root, "payload/${LocalUbuntuNpmLspHealth.FILE_NAME}")
            helper.parentFile.mkdirs()
            helper.writeText(LocalUbuntuNpmLspHealth.script)
            assertTrue(LocalUbuntuNpmPolicy.verifyLanguageServerProbe(root, recipe))
            helper.writeText("process.exit(0);\n")
            assertFalse(LocalUbuntuNpmPolicy.verifyLanguageServerProbe(root, recipe))
        } finally { root.deleteRecursively() }
    }

    @Test fun reviewedNpmLanguageServersKeepTheirSupportedTransportRoutes() {
        val entries = UbuntuNpmCatalog.entries.filter { it.familyId.startsWith("lsp.") && it.familyId != "lsp.typescript" }
        assertTrue(entries.any { it.familyId == "lsp.bash-language-server" })
        for (entry in entries) {
            val version = entry.reviewedVersion ?: "1.0.0"
            val recipe = when (entry.familyId) {
                LocalUbuntuGraphqlPolicy.FAMILY -> LocalUbuntuGraphqlPolicy.recipe()
                LocalUbuntuTomlPolicy.FAMILY -> LocalUbuntuTomlPolicy.recipe()
                LocalUbuntuVuePolicy.FAMILY -> LocalUbuntuVuePolicy.recipe()
                else -> WorkstationInstallRecipe(
                    entry.familyId, version, entry.packageName, version, entry.command,
                    22, entry.provenance, registryIntegrity = entry.reviewedIntegrity,
                    additionalDependencies = when (entry.familyId) {
                        LspMdxProfile.FAMILY -> mapOf("typescript" to LocalUbuntuTypeScriptServerPolicy.VERSION)
                        LocalUbuntuPerlNavigatorPolicy.FAMILY_ID -> LocalUbuntuPerlNavigatorPolicy.dependencies
                        LocalUbuntuPrismaPolicy.FAMILY_ID -> LocalUbuntuPrismaPolicy.dependencies
                        LocalUbuntuSveltePolicy.FAMILY_ID -> LocalUbuntuSveltePolicy.dependencies
                        else -> emptyMap()
                    },
                )
            }
            assertTrue("Reviewed recipe rejected: ${entry.familyId}", LocalUbuntuNpmPolicy.supports(recipe))
            if (entry.familyId in setOf("lsp.graphql-library", "lsp.taplo-wasm")) {
                assertThrows(IllegalArgumentException::class.java) {
                    LocalUbuntuNpmPolicy.languageServerArgs(recipe)
                }
            } else {
                val expected = if (entry.familyId == "lsp.bash-language-server") listOf("start") else listOf("--stdio")
                assertEquals(entry.familyId, expected, LocalUbuntuNpmPolicy.languageServerArgs(recipe))
            }
            assertEquals(listOf("--droide-health"), LocalUbuntuNpmPolicy.healthArgs(recipe, entry.command))
        }
    }
    @Test fun genericNpmCannotEnableScriptsOrUnreviewedNames() {
        val entry = UbuntuNpmCatalog.find("quality.prettier")!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "3.7.0", entry.packageName, "3.7.0", entry.command, 22, entry.provenance)
        assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(packageName = "unreviewed")))
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(allowLifecycleScripts = true, lifecycleScriptReview = "invented")))
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(additionalDependencies = mapOf("unreviewed" to "1.0.0"))))
    }

    @Test fun binaryCopyCompatibilityIsLimitedToTheReviewedOpenCodeSelector() {
        assertTrue(LocalUbuntuNpmPolicy.reviewedSelectorPreload(recipe).contains("failure.code = 'EXDEV'"))
        rejects { LocalUbuntuNpmPolicy.reviewedSelectorPreload(recipe.copy(version = "unreviewed")) }
        rejects { LocalUbuntuNpmPolicy.reviewedSelectorPreload(recipe.copy(packageName = "unreviewed")) }
        rejects { LocalUbuntuNpmPolicy.reviewedSelectorPreload(WorkstationInstallRecipeCatalog.find("plugin.codex", "2.1.0")!!) }
    }
}

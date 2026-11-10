package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Publisher fixtures and local admission only; no ARM64 execution or network in unit tests. */
class ExtensionPublisherUpgradeTest {
    private val sha = "a".repeat(64)
    private fun release(family: String, version: String, digest: String = "sha256:$sha"): String {
        val definition = requireNotNull(LocalUbuntuVendorCatalog.find(family))
        val tag = definition.tagPrefix + version
        val asset = definition.asset(version)
        return """{"draft":false,"prerelease":false,"tag_name":"$tag","assets":[{"name":"$asset","browser_download_url":"https://github.com/${definition.repository}/releases/download/$tag/$asset","digest":"$digest","size":40000000}]}"""
    }

    @Test fun newerNativeReleasesResolveTheirOwnPublisherIdentityAndHealthVersion() = runBlocking<Unit> {
        for ((family, version) in mapOf("lsp.buf" to "1.75.0", "lsp.texlab" to "5.27.0", "lsp.marksman" to "2026-11-10")) {
            val recipe = LocalUbuntuVendorResolver { release(family, version) }.resolve(family)
            assertEquals(version, recipe.version)
            assertEquals(sha, recipe.assetSha256)
            assertTrue(recipe.assetUrl.contains("/$version/") || recipe.assetUrl.contains("/v$version/"))
            recipe.validate()
            if (family == "lsp.buf") {
                val helper = LocalUbuntuVendorPolicy.lspHealthScript(recipe)
                assertTrue(helper.contains("record.buf_version !== '$version'"))
                assertFalse(helper.contains("'1.74.0'"))
            }
            assertTrue(runCatching { LocalUbuntuVendorResolver { release(family, version, "") }.resolve(family) }.isFailure)
            assertTrue(runCatching { LocalUbuntuVendorResolver { release(family, version) }.resolve(family, "99.1.2") }.isFailure)
        }
    }

    @Test fun historicalHealthScriptsRemainIdenticalAndCannotReceiveExecutableVersionText() {
        assertEquals(LocalUbuntuBufLspHealth.script, LocalUbuntuBufLspHealth.scriptForVersion(LocalUbuntuBufPolicy.VERSION))
        assertEquals(LocalUbuntuZlsLspHealth.script, LocalUbuntuZlsLspHealth.scriptForVersion(LocalUbuntuZlsPolicy.VERSION))
        for (bad in listOf("'; process.exit(0); //", "1.0.0\n", "1.0.0\\x27")) {
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuBufLspHealth.scriptForVersion(bad) }
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuZlsLspHealth.scriptForVersion(bad) }
        }
    }

    @Test fun terraformSelectsHighestStableNumericallyAndRequiresThatArtifactsChecksum() = runBlocking<Unit> {
        val visited = mutableListOf<String>()
        val recipe = LocalUbuntuVendorResolver { url ->
            visited += url
            if (url.endsWith("index.json")) """{"versions":{"0.9.0":{},"0.40.0":{},"0.41.0-beta.1":{}}}"""
            else "$sha  terraform-ls_0.40.0_linux_arm64.zip\n"
        }.resolve("lsp.terraform-ls")
        assertEquals("0.40.0", recipe.version)
        assertEquals(2, visited.size)
        assertEquals("https://releases.hashicorp.com/terraform-ls/0.40.0/terraform-ls_0.40.0_SHA256SUMS", visited.last())
        assertNull(recipe.assetSize)
        assertTrue(runCatching { recipe.copy(provenanceUrl = "https://example.com/sums").validate() }.isFailure)
    }

    @Test fun zlsUpdatesPatchesWithinTheCompatibleCompilerLine() = runBlocking<Unit> {
        fun target(version: String) = """{"aarch64-linux":{"tarball":"https://builds.zigtools.org/zls-aarch64-linux-$version.tar.xz","shasum":"$sha","size":"4000000"}}"""
        val index = """{"master":${target("0.18.0")},"0.16.1":${target("0.16.1")},"0.17.0":${target("0.17.0")}}"""
        val recipe = LocalUbuntuVendorResolver { index }.resolve("lsp.zls")
        assertEquals("0.16.1", recipe.version)
        assertTrue(LocalUbuntuVendorPolicy.lspHealthScript(recipe).contains("version !== '0.16.1'"))
        assertTrue(runCatching { LocalUbuntuVendorResolver { index }.resolve("lsp.zls", "0.17.0") }.isFailure)
    }

    @Test fun newerNoticesArePublisherOwnedWhileHistoricalNoticePinsStillProtectOldReceipts() {
        val directory = Files.createTempDirectory("extension-notices").toFile()
        try {
            val entry = File(directory, "terraform-ls").apply { writeText("inert fixture") }
            File(directory, "LICENSE.txt").writeText("Publisher license for a new release")
            LocalUbuntuTerraformPolicy.requireTree(entry, "0.40.0")
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuTerraformPolicy.requireTree(entry) }
            File(directory, "unexpected").writeText("inert fixture")
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuTerraformPolicy.requireTree(entry, "0.40.0") }
        } finally { directory.deleteRecursively() }
    }

    @Test fun npmUpgradesUseNewIntegrityLocksWithoutBorrowingHistoricalDependencyPins() {
        val integrity = "sha512-" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
        for (family in listOf("lsp.vim-language-server", "lsp.perlnavigator")) {
            val entry = requireNotNull(UbuntuNpmCatalog.find(family))
            val recipe = WorkstationInstallRecipe(family, "99.1.2", entry.packageName, "99.1.2", entry.command,
                entry.minimumNodeMajor, entry.provenance, registryIntegrity = integrity)
            recipe.validate()
            assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
            assertTrue(recipe.additionalDependencies.isEmpty())
            val lock = buildJsonObject {
                put("lockfileVersion", 3)
                put("packages", buildJsonObject {
                    put("", buildJsonObject { put("dependencies", buildJsonObject { put(recipe.packageName, recipe.packageVersion) }) })
                    put("node_modules/${recipe.packageName}", buildJsonObject {
                        put("version", recipe.packageVersion)
                        put("resolved", "https://registry.npmjs.org/${recipe.packageName}/-/package-99.1.2.tgz")
                        put("integrity", integrity)
                    })
                })
            }.toString()
            LocalUbuntuNpmPolicy.validateLock(lock, recipe)
            assertTrue(runCatching { LocalUbuntuNpmPolicy.validateLock(lock.replace(integrity, "invalid"), recipe) }.isFailure)
        }
    }
}

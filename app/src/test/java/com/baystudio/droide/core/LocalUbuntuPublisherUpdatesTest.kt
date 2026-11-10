package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuPublisherUpdatesTest {
    @Test fun kotlinCanResolveANewerReleaseAndPreservesTheWholeJvmDistribution() = runBlocking {
        val recipe = LocalUbuntuVendorResolver {
            release("fwcd/kotlin-language-server", "1.4.0", "server.zip", SHA)
        }.resolve("lsp.kotlin")
        assertEquals("1.4.0", recipe.version)
        val root = Files.createTempDirectory("droide-kotlin-update").toFile()
        try {
            val archive = File(root, "server.zip")
            val files = mapOf("server/bin/kotlin-language-server" to "#!/bin/bash\nexit 0\n",
                "server/lib/server.jar" to "original-jvm-distribution", "server/lib/dependency.jar" to "required-dependency")
            ZipOutputStream(archive.outputStream()).use { output -> files.forEach { (path, data) ->
                output.putNextEntry(ZipEntry(path)); output.write(data.toByteArray()); output.closeEntry()
            } }
            LocalUbuntuVendorArchive.project(recipe.copy(assetSize = archive.length()), archive, "/unused/launcher").use { projection ->
                assertEquals(files.keys, projection.files.map { it.relativePath }.toSet())
                files.forEach { (path, expected) -> assertEquals(expected, File(projection.root, path).readText()) }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun knownKotlinChecksumCannotBeBorrowedForANewerUnsignedRelease() = runBlocking {
        val old = LocalUbuntuVendorResolver { release("fwcd/kotlin-language-server", "1.3.13", "server.zip", null) }
            .resolve("lsp.kotlin", "1.3.13")
        assertEquals(LocalUbuntuVendorCatalog.find("lsp.kotlin")!!.pinnedSha256, old.assetSha256)
        try {
            LocalUbuntuVendorResolver { release("fwcd/kotlin-language-server", "1.4.0", "server.zip", null) }.resolve("lsp.kotlin")
            fail("The old checksum must not attest new publisher bytes")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun explicitPrereleasesAreSelectableWhileLatestAndDraftsRemainExcluded() = runBlocking {
        val def = LocalUbuntuVendorCatalog.find("plugin.opencode")!!
        val body = release(def.repository!!, "v99.0.0-beta.1", def.asset("99.0.0-beta.1"), SHA, prerelease = true)
        val explicit = LocalUbuntuVendorResolver { body }.resolve(def, "99.0.0-beta.1")
        assertEquals("99.0.0-beta.1", explicit.version)
        try { LocalUbuntuVendorResolver { body }.resolve(def); fail() } catch (_: IllegalArgumentException) {}
        try { LocalUbuntuVendorResolver { body.replace("\"draft\":false", "\"draft\":true") }.resolve(def, explicit.version); fail() }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun newJsDebugUsesPublisherDigestAndUserNodeInsteadOfHistoricalRuntimePin() = runBlocking {
        val recipe = LocalUbuntuVendorResolver {
            release("microsoft/vscode-js-debug", "v1.150.0", "js-debug-dap-v1.150.0.tar.gz", SHA)
        }.resolve(LocalUbuntuJsDebugPolicy.FAMILY)
        assertFalse(LocalUbuntuJsDebugPolicy.requiresLegacyNode(recipe))
        assertEquals(listOf("ca-certificates", "nodejs"), recipe.requiredPackages)
        assertEquals(listOf("/usr/bin/env", "node", "/tree/js-debug/src/dapDebugServer.js"),
            LocalUbuntuVendorPolicy.argv(recipe, "/tree/js-debug/src/dapDebugServer.js"))
        val entry = Files.createTempFile("droide-js-update", ".js").toFile()
        try { entry.writeText("console.log('new publisher bundle');"); LocalUbuntuJsDebugPolicy.requireEntry(recipe, entry) }
        finally { entry.delete() }
    }

    @Test fun historicalJsReceiptKeepsItsExactWrapperAndEntryAttestation() {
        val def = LocalUbuntuVendorCatalog.find(LocalUbuntuJsDebugPolicy.FAMILY)!!
        val version = LocalUbuntuJsDebugPolicy.VERSION
        val recipe = LocalUbuntuVendorRecipe(def, version, "v$version", def.asset(version),
            "https://github.com/microsoft/vscode-js-debug/releases/download/v$version/${def.asset(version)}",
            LocalUbuntuJsDebugPolicy.ARTIFACT_SHA256, LocalUbuntuJsDebugPolicy.ARTIFACT_BYTES,
            "https://github.com/microsoft/vscode-js-debug/releases/tag/v$version")
        recipe.validate()
        assertTrue(LocalUbuntuJsDebugPolicy.requiresLegacyNode(recipe))
        assertEquals(listOf("ca-certificates"), recipe.requiredPackages)
        assertEquals("#!/bin/sh\nset -eu\nexec '${LocalUbuntuJsDebugPolicy.NODE_GUEST}' '/tree/js-debug/src/dapDebugServer.js' \"${'$'}@\"\n",
            LocalUbuntuVendorPolicy.guestWrapper(recipe, "/tree/js-debug/src/dapDebugServer.js", "/tree"))
        val entry = Files.createTempFile("droide-js-old", ".js").toFile()
        try {
            entry.writeText("tampered old publisher entry")
            try { LocalUbuntuJsDebugPolicy.requireEntry(recipe, entry); fail() } catch (_: IllegalArgumentException) {}
        } finally { entry.delete() }
    }

    @Test fun jdtlsUsesNumericLatestMilestoneAndThatReleasesActualArchiveChecksum() = runBlocking {
        val visited = mutableListOf<String>()
        val recipe = LocalUbuntuVendorResolver { url ->
            visited += url
            when {
                url.endsWith("milestones/") -> "<a href='/jdtls/milestones/1.9.0'>old</a><a href='/jdtls/milestones/1.62.0'>new</a>"
                url.endsWith("/1.62.0/") -> "<a href='jdt-language-server-1.62.0-202610131315.tar.gz'>archive</a>"
                url.endsWith(".sha256") -> SHA
                else -> error("Unexpected publisher lookup: $url")
            }
        }.resolve("lsp.jdtls")
        assertEquals("1.62.0", recipe.version)
        assertEquals("jdt-language-server-1.62.0-202610131315.tar.gz", recipe.assetName)
        assertTrue(visited.last().endsWith("${recipe.assetName}.sha256"))
        assertFalse(LocalUbuntuVendorPolicy.argv(recipe, "/tree/bin/jdtls").contains("--java-executable"))
        assertNull(recipe.assetSize)
    }

    @Test fun soliditySelectsTheActualReleaseCommitRatherThanAnOlderCommitTemplate() = runBlocking {
        val body = """{"latestRelease":"0.8.38","releases":{"0.8.38":"solc-linux-arm64-v0.8.38+commit.abcdef01"},"builds":[{"version":"0.8.38","path":"solc-linux-arm64-v0.8.38+commit.abcdef01","sha256":"0x$SHA"}]}"""
        val recipe = LocalUbuntuVendorResolver { body }.resolve("toolchain.solidity")
        assertEquals("0.8.38", recipe.version)
        assertEquals("solc-linux-arm64-v0.8.38+commit.abcdef01", recipe.assetName)
        assertEquals(SHA, recipe.assetSha256)
        try { recipe.copy(assetName = "../solc").validate(); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun miseDeclaresTheXzExtractorNeededBeforeFreshArchiveProjection() {
        assertTrue("xz-utils" in LocalUbuntuVendorCatalog.find("cli.mise")!!.packages)
    }

    private fun release(repo: String, tag: String, asset: String, sha: String?, prerelease: Boolean = false) =
        """{"draft":false,"prerelease":$prerelease,"tag_name":"$tag","assets":[{"name":"$asset","browser_download_url":"https://github.com/$repo/releases/download/$tag/$asset","digest":${sha?.let { "\"sha256:$it\"" } ?: "null"},"size":12345}]}"""
    private companion object { val SHA = "b".repeat(64) }
}

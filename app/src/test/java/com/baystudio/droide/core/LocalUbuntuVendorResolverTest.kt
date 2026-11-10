package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuVendorResolverTest {
    @Test fun exactPublisherIdentityAndBytesAreResolvedBeforeInstallation() = runBlocking {
        val recipe = resolver().resolve("package.bun")
        assertEquals("1.4.2", recipe.version)
        assertEquals(12345L, recipe.assetSize)
        assertEquals("a".repeat(64), recipe.assetSha256)
        recipe.validate()
    }
    @Test fun absentDigestDraftAndEscapedPublisherUrlsAreRejected() = runBlocking {
        for (body in listOf(RELEASE.replace("sha256:" + "a".repeat(64), ""),
            RELEASE.replace("\"draft\":false", "\"draft\":true"),
            RELEASE.replace("https://github.com/oven-sh/bun/releases/download/", "https://example.com/"))) {
            try { resolver(body).resolve("package.bun"); fail() } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun resolverCannotSubstituteAnotherRequestedVersion() = runBlocking {
        try { resolver().resolve("package.bun", "1.4.1"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun forgedRecipeCannotEscapeReviewedSourceOrDefinition() {
        val def = LocalUbuntuVendorCatalog.find("package.bun")!!
        val valid = LocalUbuntuVendorRecipe(def, "1.4.2", "bun-v1.4.2", def.asset("1.4.2"),
            "https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-aarch64.zip", "a".repeat(64), 12345,
            "https://github.com/oven-sh/bun/releases/tag/bun-v1.4.2")
        for (bad in listOf(valid.copy(assetUrl = "https://example.com/bun.zip"), valid.copy(assetSize = 0),
            valid.copy(assetSha256 = "broken"), valid.copy(definition = def.copy(entryTemplate = "../user-data")))) {
            try { bad.validate(); fail() } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun allVendorFamiliesHaveBoundedAndUniqueReviewedDefinitions() {
        val definitions = LocalUbuntuVendorCatalog.all
        assertEquals(definitions.size, definitions.map { it.familyId }.toSet().size)
        for (def in definitions) {
            assertNotNull(DevelopmentExtensionCatalog.byId(def.familyId))
            assertTrue(def.maxFiles in 1..50_000)
            assertTrue(def.maxUnpackedBytes in 1..1_073_741_824L)
            assertTrue(def.maxAssetBytes in 1..268_435_456L)
        }
    }
    @Test fun hexRequiresExactCompatiblePublisherChecksumRow() = runBlocking {
        val row = "2.5.1,7cc7ef419c87a90b2cc86813501dcf8a46a24e9765940574fbd3d25571c388cb41134537154bffa1f8e4d388fb7231716f1f890478ed6aae9b5c4468afddf010,1.14.0"
        val recipe = LocalUbuntuVendorResolver { row }.resolve("package.hex")
        assertEquals(971_966L, recipe.assetSize)
        assertEquals("https://repo.hex.pm/installs/1.14.0/hex-2.5.1.ez", recipe.assetUrl)
        for (bad in listOf(row.replace("1.14.0", "1.15.0"), row.replace("7cc7", "ffff"), "$row\n$row")) {
            try { LocalUbuntuVendorResolver { bad }.resolve("package.hex"); fail() } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun opencodeStandaloneCanResolveAFuturePublisherVersionWithItsActualDigest() = runBlocking {
        val body = """{"draft":false,"prerelease":false,"tag_name":"v99.2.1","assets":[{"name":"opencode-linux-arm64.tar.gz","browser_download_url":"https://github.com/anomalyco/opencode/releases/download/v99.2.1/opencode-linux-arm64.tar.gz","digest":"sha256:${"b".repeat(64)}","size":60493623}]}"""
        val recipe = LocalUbuntuVendorResolver { body }.resolve("plugin.opencode")
        assertEquals("99.2.1", recipe.version)
        assertEquals("b".repeat(64), recipe.assetSha256)
        assertFalse(recipe.definition.packages.contains("nodejs"))
        recipe.validate()
    }

    private fun resolver(body: String = RELEASE) = LocalUbuntuVendorResolver { body }
    private companion object {
        val RELEASE = """{"draft":false,"prerelease":false,"tag_name":"bun-v1.4.2","assets":[{"name":"bun-linux-aarch64.zip","browser_download_url":"https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-aarch64.zip","digest":"sha256:${"a".repeat(64)}","size":12345}]}"""
    }
}

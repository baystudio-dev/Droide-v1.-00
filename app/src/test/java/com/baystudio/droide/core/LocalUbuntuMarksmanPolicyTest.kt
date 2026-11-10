package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Metadata/recipe-only fixtures. No upstream artifact download or execution. */
class LocalUbuntuMarksmanPolicyTest {
    private val family = "lsp.marksman"
    private val version = "2026-02-08"
    private val sha = "db8e124527f7f8048e3e6c91821b9c52ef173d92c01e47d221bf1337afd962fb"
    private fun release(digest: String = "\"sha256:$sha\"", size: String = "21851058") =
        """{"draft":false,"prerelease":false,"tag_name":"$version","assets":[{"name":"marksman-linux-arm64","browser_download_url":"https://github.com/artempyanykh/marksman/releases/download/$version/marksman-linux-arm64","digest":$digest,"size":$size}]}"""
    private suspend fun recipe() = LocalUbuntuVendorResolver { release() }.resolve(family,version)
    @Test fun exactPublishedIdentityHasRawGlibcAdmissionAndNoInventedDotnetRuntime() = runBlocking<Unit> {
        val r=recipe();r.validate()
        assertEquals(sha,r.assetSha256);assertEquals(21851058L,r.assetSize)
        assertEquals(LocalUbuntuVendorFormat.RAW,r.definition.format)
        assertEquals(LocalUbuntuVendorAdmission.GLIBC_ARM64,r.definition.admission)
        assertTrue(r.definition.lsp);assertTrue(r.healthArgs.isEmpty());assertEquals("marksman",r.entryPoint)
        assertEquals(1,r.definition.maxFiles)
        assertFalse(r.definition.packages.any { it.startsWith("dotnet") })
        assertTrue(r.definition.packages.containsAll(listOf("libicu74","libssl3t64","libgssapi-krb5-2","nodejs")))
        assertTrue(r.definition.supportsPublisherVersionSelection)
    }
    @Test fun missingNullMalformedAndChangedPublisherDigestsFailBeforeInstallation() = runBlocking<Unit> {
        val bodies=listOf(release("null"),release("\"\""),release("\"sha256:${"a".repeat(64)}\""),
            release().replace("\"digest\":\"sha256:$sha\",",""),release("{}"))
        for (body in bodies) assertTrue(runCatching {LocalUbuntuVendorResolver {body}.resolve(family,version)}.isFailure)
    }
    @Test fun sizeTagUrlAndDraftCannotDrift() = runBlocking<Unit> {
        for (body in listOf(release(size="null"),release(size="21851057"),release(size="21851059"),
            release().replace("\"tag_name\":\"$version\"","\"tag_name\":\"2026-02-09\""),
            release().replace("https://github.com/artempyanykh/marksman/releases/download/","https://example.com/"),
            release().replace("\"draft\":false","\"draft\":true"))) {
            assertTrue(runCatching {LocalUbuntuVendorResolver {body}.resolve(family,version)}.isFailure)
        }
    }
    @Test fun latestResolvesPublisherButAnExplicitDifferentVersionIsNeverSubstituted() = runBlocking<Unit> {
        var calls = 0
        val resolver = LocalUbuntuVendorResolver { calls++; release() }
        assertEquals(version, resolver.resolve(family, "latest").version)
        assertTrue(runCatching { resolver.resolve(family, "999.0.0") }.isFailure)
        assertEquals(2, calls)
    }
    @Test fun reconstructedOrForgedRecipesMustRetainExactPinAndDefinition() = runBlocking<Unit> {
        val valid=recipe()
        for (bad in listOf(valid.copy(version="2026-02-09"),valid.copy(releaseTag="v$version"),
            valid.copy(assetSha256="a".repeat(64)),valid.copy(assetSize=null),valid.copy(assetSize=1),
            valid.copy(definition=valid.definition.copy(packages=emptyList())),
            valid.copy(definition=valid.definition.copy(familyId="lsp.marksman-lookalike")))) {
            assertTrue(runCatching {bad.validate()}.isFailure)
        }
    }
    @Test fun genericUpdaterCannotBypassExactManagedRecipeForAnyVersion() {
        val f=DevelopmentExtensionCatalog.byId(family)!!
        for (v in listOf(version,"latest","99.0.0")) assertTrue(runCatching {ToolUpdatePlanner.recipe(f,v,null)}.isFailure)
        // Old non-pinned vendor families retain their mutable publisher route.
        val old=DevelopmentExtensionCatalog.byId("package.bun")!!
        assertTrue(ToolUpdatePlanner.recipe(old,"99.0.0",null).command.contains("99.0.0"))
    }
}

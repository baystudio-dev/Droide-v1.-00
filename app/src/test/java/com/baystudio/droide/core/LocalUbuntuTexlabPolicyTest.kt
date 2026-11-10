package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Metadata/recipe-only fixtures. No upstream artifact download or execution. */
class LocalUbuntuTexlabPolicyTest {
    private val family = "lsp.texlab"
    private val version = "5.26.0"
    private val sha = "a85cdfcd22454b8d8550f4b0f0620c45ab51760f302fac7a12bc18a890f70f8c"
    private fun release(digest: String = "\"sha256:$sha\"", size: String = "10383190") =
        """{"draft":false,"prerelease":false,"tag_name":"v$version","assets":[{"name":"texlab-aarch64-linux.tar.gz","browser_download_url":"https://github.com/latex-lsp/texlab/releases/download/v$version/texlab-aarch64-linux.tar.gz","digest":$digest,"size":$size}]}"""
    private suspend fun recipe() = LocalUbuntuVendorResolver { release() }.resolve(family,version)
    @Test fun exactPublishedIdentityHasGlibcTarAdmissionAndNoInventedTexRuntime() = runBlocking<Unit> {
        val r=recipe();r.validate()
        assertEquals(sha,r.assetSha256);assertEquals(10383190L,r.assetSize)
        assertEquals(LocalUbuntuVendorFormat.TAR_GZ,r.definition.format)
        assertEquals(LocalUbuntuVendorAdmission.GLIBC_ARM64,r.definition.admission)
        assertTrue(r.definition.lsp);assertTrue(r.healthArgs.isEmpty());assertEquals("texlab",r.entryPoint)
        assertEquals(1,r.definition.maxFiles)
        assertFalse(r.definition.packages.any { it.startsWith("texlive") })
        assertTrue(r.definition.packages.containsAll(listOf("libc6","libgcc-s1","nodejs")))
        assertTrue(r.definition.supportsPublisherVersionSelection)
    }
    @Test fun missingNullMalformedAndChangedPublisherDigestsFailBeforeInstallation() = runBlocking<Unit> {
        val bodies=listOf(release("null"),release("\"\""),release("\"sha256:${"a".repeat(64)}\""),
            release().replace("\"digest\":\"sha256:$sha\",",""),release("{}"))
        for (body in bodies) assertTrue(runCatching {LocalUbuntuVendorResolver {body}.resolve(family,version)}.isFailure)
    }
    @Test fun sizeTagUrlAndDraftCannotDrift() = runBlocking<Unit> {
        for (body in listOf(release(size="null"),release(size="10383189"),release(size="10383191"),
            release().replace("\"tag_name\":\"v$version\"","\"tag_name\":\"v5.27.0\""),
            release().replace("https://github.com/latex-lsp/texlab/releases/download/","https://example.com/"),
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
        for (bad in listOf(valid.copy(version="5.27.0"),valid.copy(releaseTag="wrong-$version"),
            valid.copy(assetSha256="a".repeat(64)),valid.copy(assetSize=null),valid.copy(assetSize=1),
            valid.copy(definition=valid.definition.copy(packages=emptyList())),
            valid.copy(definition=valid.definition.copy(familyId="lsp.texlab-lookalike")))) {
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

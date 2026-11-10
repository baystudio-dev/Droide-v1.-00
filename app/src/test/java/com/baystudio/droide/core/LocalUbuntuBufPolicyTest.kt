package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Metadata/recipe-only fixtures. No upstream artifact download or execution. */
class LocalUbuntuBufPolicyTest {
    private val family = "lsp.buf"
    private val version = "1.74.0"
    private val sha = "54a5d5500992deb3239bfee61c9f0ef7646b6f6b231d2c255fdfc16eeb50564c"
    private fun release(digest: String = "\"sha256:$sha\"", size: String = "29377099") =
        """{"draft":false,"prerelease":false,"tag_name":"v$version","assets":[{"name":"buf-Linux-aarch64.tar.gz","browser_download_url":"https://github.com/bufbuild/buf/releases/download/v$version/buf-Linux-aarch64.tar.gz","digest":$digest,"size":$size}]}"""
    private suspend fun recipe() = LocalUbuntuVendorResolver { release() }.resolve(family,version)
    @Test fun exactPublishedArchiveRetainsStaticAdmissionAndOnlyBufCommand() = runBlocking<Unit> {
        val r=recipe();r.validate()
        assertEquals(sha,r.assetSha256);assertEquals(29377099L,r.assetSize)
        assertEquals(LocalUbuntuVendorFormat.TAR_GZ,r.definition.format)
        assertEquals(LocalUbuntuVendorAdmission.STATIC_ARM64,r.definition.admission)
        assertTrue(r.definition.lsp);assertTrue(r.healthArgs.isEmpty());assertEquals("buf/bin/buf",r.entryPoint)
        assertEquals(512,r.definition.maxFiles)
        assertEquals(listOf("ca-certificates","nodejs"),r.definition.packages)

        assertTrue(r.definition.supportsPublisherVersionSelection)
    }
    @Test fun missingNullMalformedAndChangedPublisherDigestsFailBeforeInstallation() = runBlocking<Unit> {
        val bodies=listOf(release("null"),release("\"\""),release("\"sha256:${"a".repeat(64)}\""),
            release().replace("\"digest\":\"sha256:$sha\",",""),release("{}"))
        for (body in bodies) assertTrue(runCatching {LocalUbuntuVendorResolver {body}.resolve(family,version)}.isFailure)
    }
    @Test fun sizeTagUrlAndDraftCannotDrift() = runBlocking<Unit> {
        for (body in listOf(release(size="null"),release(size="29377098"),release(size="29377100"),
            release().replace("\"tag_name\":\"v$version\"","\"tag_name\":\"v1.75.0\""),
            release().replace("https://github.com/bufbuild/buf/releases/download/","https://example.com/"),
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
        for (bad in listOf(valid.copy(version="1.75.0"),valid.copy(releaseTag="wrong-$version"),
            valid.copy(assetSha256="a".repeat(64)),valid.copy(assetSize=null),valid.copy(assetSize=1),
            valid.copy(definition=valid.definition.copy(packages=emptyList())),
            valid.copy(definition=valid.definition.copy(familyId="lsp.buf-lookalike")))) {
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
    @Test fun missingFabricatedLicenseAndWrongEntryLayoutFailClosed() {
        val root=Files.createTempDirectory("buf-layout-").toFile()
        try {
            val entry=File(root,"buf/bin/buf").apply {parentFile.mkdirs();writeText("inert fake")}
            assertTrue(runCatching {LocalUbuntuBufPolicy.requireTree(entry)}.isFailure)
            File(root,"buf/LICENSE").writeText(" ".repeat(11357))
            assertTrue(runCatching {LocalUbuntuBufPolicy.requireTree(entry)}.isFailure)
            assertTrue(runCatching {LocalUbuntuBufPolicy.requireTree(File(root,"buf/bin/other"))}.isFailure)
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
}

package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Publisher metadata and synthetic files only; never downloads or runs upstream code. */
class LocalUbuntuTerraformPolicyTest {
    private val family = "lsp.terraform-ls"
    private val version = "0.39.0"
    private val sha = "62f32ea22cb78e5e5667ed638ad6e0fbde30ab59228d073c3c9bb249f89c7f5a"
    private val asset = "terraform-ls_0.39.0_linux_arm64.zip"
    private val body get() = "$sha  $asset\n${"a".repeat(64)}  terraform-ls_0.39.0_linux_amd64.zip\n"
    private suspend fun recipe() = LocalUbuntuVendorResolver { url ->
        assertEquals("https://releases.hashicorp.com/terraform-ls/0.39.0/terraform-ls_0.39.0_SHA256SUMS", url)
        body
    }.resolve(family, version)

    @Test fun originalVendorZipHasStaticAdmissionAndOptionalCli() = runBlocking<Unit> {
        val r=recipe();r.validate()
        assertEquals(sha,r.assetSha256);assertEquals(30305656L,r.assetSize)
        assertEquals(LocalUbuntuVendorFormat.ZIP,r.definition.format)
        assertEquals(LocalUbuntuVendorAdmission.STATIC_ARM64,r.definition.admission)
        assertEquals("terraform-ls",r.entryPoint);assertEquals(2,r.definition.maxFiles)
        assertEquals(listOf("ca-certificates","nodejs"),r.definition.packages)
        assertTrue(r.definition.lsp);assertTrue(r.healthArgs.isEmpty())
        assertTrue(r.definition.supportsPublisherVersionSelection)
    }
    @Test fun malformedMissingDuplicateAndChangedChecksumCannotResolve() = runBlocking<Unit> {
        for(b in listOf("",body.replace(asset,"other.zip"),body+"$sha  $asset\n",body.replace(sha,"b".repeat(64)),
            body.replace("  "," "),body.replace(sha,"SHA256:$sha"),body+"malformed\n"," ".repeat(65537))) {
            assertTrue(runCatching {LocalUbuntuVendorResolver {b}.resolve(family,version)}.isFailure)
        }
        assertEquals(sha,LocalUbuntuTerraformPolicy.publisherChecksum(body.replace("\n","\r\n")))
    }
    @Test fun newerVersionsRequireTheirOwnPublisherChecksum() = runBlocking<Unit> {
        val next = body.replace("0.39.0", "0.40.0").replace(sha, "b".repeat(64))
        val resolved = LocalUbuntuVendorResolver { next }.resolve(family, "0.40.0")
        assertEquals("0.40.0", resolved.version)
        assertEquals("b".repeat(64), resolved.assetSha256)
        assertTrue(runCatching { LocalUbuntuVendorResolver { body }.resolve(family, "0.40.0") }.isFailure)
    }
    @Test fun receiptRecipeMustKeepExactIdentityAndDefinition() = runBlocking<Unit> {
        val r=recipe()
        for(bad in listOf(r.copy(version="0.40.0"),r.copy(releaseTag="0.39.0"),r.copy(assetSha256="a".repeat(64)),
            r.copy(assetSize=null),r.copy(assetSize=1),r.copy(assetUrl="https://example.com/$asset"),
            r.copy(provenanceUrl="https://example.com/checksums"),r.copy(definition=r.definition.copy(packages=emptyList()))))
            assertTrue(runCatching {bad.validate()}.isFailure)
    }
    @Test fun genericUpdaterCannotBypassExactRecipe() {
        val family=DevelopmentExtensionCatalog.byId(family)!!
        for(v in listOf(version,"latest","99.0.0")) assertTrue(runCatching {ToolUpdatePlanner.recipe(family,v,null)}.isFailure)
        assertTrue(ToolUpdatePlanner.recipe(DevelopmentExtensionCatalog.byId("package.bun")!!,"99.0.0",null).command.contains("99.0.0"))
    }
    @Test fun missingOrFabricatedLicenseAndExtraFilesFailClosed() {
        val root=Files.createTempDirectory("terraform-layout-").toFile()
        try {
            val entry=File(root,"terraform-ls").apply {writeText("inert fake")}
            assertTrue(runCatching {LocalUbuntuTerraformPolicy.requireTree(entry)}.isFailure)
            File(root,"LICENSE.txt").writeText(" ".repeat(16009))
            assertTrue(runCatching {LocalUbuntuTerraformPolicy.requireTree(entry)}.isFailure)
            File(root,"unexpected").writeText("inert")
            assertTrue(runCatching {LocalUbuntuTerraformPolicy.requireTree(entry)}.isFailure)
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
}

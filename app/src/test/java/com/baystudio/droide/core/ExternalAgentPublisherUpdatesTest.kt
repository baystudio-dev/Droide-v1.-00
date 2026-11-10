package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentPublisherUpdatesTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/agent-publishers/$name.json"))
        .bufferedReader().use { it.readText() }

    @Test fun latestMuseResolvesANewerNativeRuntimeAndUsesPublisherIdentity() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val recipe = LocalUbuntuVendorResolver { url ->
            urls += url
            when (url) {
                LocalUbuntuAgentDistributions.MUSE_CHANNEL -> fixture("muse-channel")
                LocalUbuntuAgentDistributions.museUrl("1.4.14-R7309.1", "manifest.json") -> fixture("muse-manifest")
                else -> error("Unexpected URL: $url")
            }
        }.resolve(LocalUbuntuAgentDistributions.MUSE_RUNTIME)
        assertEquals("1.4.14-R7309.1", recipe.version)
        assertEquals(371529960L, recipe.assetSize)
        assertEquals("3dc39b8fa26f9963cc2038f2966c0932ed5a39f0f9efb39cc0266a261cbbcf76", recipe.assetSha256)
        assertEquals(2, urls.size)
        val appRoot = "/data/user/0/com.baystudio.droide/files"
        val path = LocalPackageInstallJournal.paths(appRoot, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final.path
        val record = ManagedPackageRecord(recipe.familyId, recipe.version, ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
            artifactSha256 = recipe.assetSha256, abi = "arm64-v8a")
        assertTrue(LocalUbuntuMusePolicy.requireRuntime(record, appRoot).endsWith("/payload/tree/muse"))
        assertTrue(LocalUbuntuMusePolicy.isRuntimeKey("${recipe.familyId}@${recipe.version}"))
        assertThrows(IllegalArgumentException::class.java) {
            LocalUbuntuMusePolicy.requireRuntime(record.copy(installRoot = "/tmp/foreign"), appRoot)
        }
    }

    @Test fun futureAntigravityAuthenticatesSha512BeforeMaterializingReceiptSha256() = runBlocking<Unit> {
        val manifest = fixture("agy").replace("1.5.3", "1.6.0").replace(
            Json.parseToJsonElement(fixture("agy")).jsonObject.getValue("sha512").jsonPrimitive.content, "a".repeat(128))
        val recipe = LocalUbuntuVendorResolver { manifest }.resolve(LocalUbuntuAgentDistributions.ANTIGRAVITY)
        assertEquals("1.6.0", recipe.version)
        assertEquals("", recipe.assetSha256)
        val artifact = recipe.artifactDescriptor("agent-test")
        artifact.validateStructure()
        assertEquals("SHA-512", artifact.digestAlgorithm)
        assertEquals("a".repeat(128), artifact.digestHex)
        val receiptRecipe = recipe.withVerifiedSha256("b".repeat(64))
        assertEquals("b".repeat(64), receiptRecipe.assetSha256)
        assertEquals(recipe.assetSha512, receiptRecipe.assetSha512)
        assertThrows(IllegalArgumentException::class.java) { receiptRecipe.withVerifiedSha256("c".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { recipe.copy(assetSha512 = null).validate() }
        assertThrows(IllegalArgumentException::class.java) { recipe.copy(assetUrl = recipe.assetUrl + "?redirect=evil").validate() }
    }

    @Test fun latestMetadataCannotRedirectOrSubstituteRequestedPublisherVersions() = runBlocking<Unit> {
        suspend fun rejected(family: String, version: String, fetch: suspend (String) -> String) {
            try { LocalUbuntuVendorResolver(fetch).resolve(family, version); fail("Untrusted manifest accepted") }
            catch (_: IllegalArgumentException) { }
        }
        rejected(LocalUbuntuAgentDistributions.ANTIGRAVITY, "latest") { fixture("agy").replace("storage.googleapis.com", "evil.example") }
        rejected(LocalUbuntuAgentDistributions.ANTIGRAVITY, "9.0.0") { fixture("agy") }
        rejected(LocalUbuntuAgentDistributions.MUSE_RUNTIME, "latest") { fixture("muse-channel").replace("lookaside.facebook.com", "evil.example") }
        rejected(LocalUbuntuAgentDistributions.MUSE_RUNTIME, "1.4.14-R7309.1") { fixture("muse-manifest").replace("1.4.14-R7309.1", "1.4.15-R7400.1") }
    }

    @Test fun everyNpmAgentAcceptsFutureVersionsButKeepsPackageAndRuntimeAdmission() {
        for (spec in WorkspaceAgentPluginCatalog.entries) {
            val entry = UbuntuNpmCatalog.find(spec.familyId) ?: continue
            val recipe = WorkstationInstallRecipe(entry.familyId, "99.0.0", entry.packageName, "99.0.0", entry.command,
                entry.minimumNodeMajor, entry.provenance, registryIntegrity = "sha512-" + "A".repeat(86) + "==",
                allowLifecycleScripts = entry.allowLifecycleScripts, lifecycleScriptReview = entry.lifecycleScriptReview)
            recipe.validate()
            assertTrue(spec.id, LocalUbuntuNpmPolicy.supports(recipe))
            assertFalse(spec.id, LocalUbuntuNpmPolicy.supports(recipe.copy(packageName = "untrusted-cli")))
            assertFalse(spec.id, LocalUbuntuNpmPolicy.supports(recipe.copy(minimumNodeMajor = 18)))
            assertFalse(spec.id, LocalUbuntuNpmPolicy.supports(recipe.copy(allowLifecycleScripts = !entry.allowLifecycleScripts)))
        }
    }

    @Test fun deepseekUsesDocumentedProfileAndDoesNotInventPlanMode() {
        val spec = WorkspaceAgentPluginCatalog.entries.single { it.id == "deepseek" }
        assertEquals("dsh", spec.command)
        assertEquals(listOf("--profile", "acp"), spec.args)
        val selector = WorkspaceAgentModeSelection(spec.planModeCandidates, spec.editModeCandidates)
        assertTrue(selector.resolve(WorkspaceAgentMode.EDIT, WorkspaceAgentSessionConfiguration()).nativeDefault)
        assertThrows(IllegalStateException::class.java) { selector.resolve(WorkspaceAgentMode.PLAN, WorkspaceAgentSessionConfiguration()) }
        assertEquals(24, UbuntuNpmCatalog.find(spec.familyId)!!.minimumNodeMajor)
    }
}

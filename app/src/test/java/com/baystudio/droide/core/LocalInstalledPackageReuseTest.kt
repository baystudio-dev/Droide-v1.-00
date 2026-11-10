package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalInstalledPackageReuseTest {
    private val hash = "a".repeat(64)
    private val record = ManagedPackageRecord("runtime.node", "22.23.3", ExecutionScope.LOCAL_LINUX_ARM64.name,
        "/fixture/packages/runtime.node/22.23.3/arm64-v8a", 1, artifactSha256 = hash)
    private val sri = "sha512-" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
    private val recipe = WorkstationInstallRecipeCatalog.find("plugin.opencode", "1.18.34")!!.copy(registryIntegrity = sri)
    private val npm = record.copy(familyId = recipe.familyId, version = recipe.version,
        metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_NPM))
    private fun review(request: WorkstationInstallRecipe = recipe) = buildJsonObject {
        put("schema", 1); put("familyId", request.familyId); put("version", request.version)
        put("packageName", request.packageName); put("packageVersion", request.packageVersion)
        put("registryIntegrity", request.registryIntegrity!!); put("nodeCommand", "/usr/bin/node"); put("nodeMajor", 22)
        put("additionalDependencies", buildJsonObject { request.additionalDependencies.forEach { (name, version) -> put(name, version) } })
        put("allowLifecycleScripts", request.allowLifecycleScripts)
        request.lifecycleScriptReview?.let { put("lifecycleScriptReview", it) }
    }
    private fun matches(current: ManagedPackageRecord = npm, request: WorkstationInstallRecipe = recipe,
        lock: JsonObject = review(), node: ManagedPackageRecord? = null, command: String = "/usr/bin/node", major: Int = 22) =
        LocalInstalledPackageReuse.npmIdentityMatches(current, request, lock, node, command, major)

    @Test fun museReuseRequiresTheSameRuntimeDependencyAndReview() {
        val request = requireNotNull(WorkstationInstallRecipeCatalog.find(LocalUbuntuMusePolicy.FAMILY, LocalUbuntuMusePolicy.VERSION))
        val runtime = listOf(LocalUbuntuAgentDistributions.MUSE_KEY)
        val current = npm.copy(familyId = request.familyId, version = request.version, dependencies = runtime)
        val lock = JsonObject(review(request) + ("museRuntime" to JsonPrimitive(runtime.single())))
        fun matches(extra: List<String>, saved: ManagedPackageRecord = current, savedReview: JsonObject = lock) =
            LocalInstalledPackageReuse.npmIdentityMatches(saved, request, savedReview, null, "/usr/bin/node", 22,
                extraDependencies = extra)
        assertTrue(matches(runtime))
        assertFalse(matches(emptyList()))
        assertFalse(matches(listOf("runtime.muse-code@other")))
        assertFalse(matches(runtime, current.copy(dependencies = emptyList())))
        assertFalse(matches(runtime, savedReview = review(request)))
    }

    @Test fun exactOwnedArtifactRequiresFreshFullVerification() = runBlocking {
        var checks = 0
        assertSame(record, LocalInstalledPackageReuse.artifact(record, record.familyId, record.version, hash,
            { true }, { checks++; true }))
        assertEquals(1, checks)
        assertNull(LocalInstalledPackageReuse.artifact(record, record.familyId, record.version, hash, { true }, { false }))
    }
    @Test fun foreignIdentityOrChangedPublisherPinCannotReuse() = runBlocking {
        var checks = 0
        for (candidate in listOf(null, record.copy(familyId = "other"), record.copy(version = "22.0.0"),
            record.copy(artifactSha256 = "b".repeat(64)))) {
            assertNull(LocalInstalledPackageReuse.artifact(candidate, record.familyId, record.version, hash,
                { true }, { checks++; true }))
        }
        assertNull(LocalInstalledPackageReuse.artifact(record, record.familyId, record.version, hash, { false }, { checks++; true }))
        assertNull(LocalInstalledPackageReuse.artifact(record, record.familyId, record.version, "bad", { true }, { checks++; true }))
        assertEquals(0, checks)
    }
    @Test fun cancellationDuringVerificationNeverBecomesAReuseMiss() = runBlocking {
        try {
            LocalInstalledPackageReuse.artifact(record, record.familyId, record.version, hash, { true },
                { throw CancellationException("cancel verification") })
            fail("Cancellation swallowed")
        } catch (expected: CancellationException) { assertEquals("cancel verification", expected.message) }
    }
    @Test fun changedReviewedCommandsOrRuntimeContractCannotReuseAnArtifact() = runBlocking {
        var verified = false
        assertNull(LocalInstalledPackageReuse.artifact(record, record.familyId, record.version, hash,
            { true }, { verified = true; true }, identity = { false }))
        assertFalse(verified)
    }
    @Test fun unpinnedOrChangedNpmIntegrityCannotReuse() {
        assertTrue(matches())
        assertFalse(matches(request = recipe.copy(registryIntegrity = null)))
        assertFalse(matches(request = recipe.copy(registryIntegrity = sri.replace('H', 'G') + "changed")))
        assertFalse(matches(current = npm.copy(familyId = "other")))
        assertFalse(matches(current = npm.copy(metadata = emptyMap())))
    }
    @Test fun nodeSelectionAndDependencyEdgeMustStillMatch() {
        assertFalse(matches(command = "/other/node")); assertFalse(matches(major = 24))
        assertFalse(matches(node = record)); assertFalse(matches(current = npm.copy(dependencies = listOf("runtime.node@22.23.3"))))
        val bound = npm.copy(dependencies = listOf("runtime.node@22.23.3"))
        assertTrue(matches(current = bound, node = record))
        assertFalse(matches(current = bound, node = record.copy(version = "22.24.0")))
    }
    @Test fun companionPinsAndLifecycleReviewCannotDrift() {
        assertFalse(matches(request = recipe.copy(additionalDependencies = mapOf("typescript" to "5.9.3"))))
        assertFalse(matches(request = recipe.copy(allowLifecycleScripts = false, lifecycleScriptReview = null)))
        assertFalse(matches(request = recipe.copy(lifecycleScriptReview = "different reviewed selector")))
        assertFalse(matches(request = recipe.copy(packageName = "other")))
        assertFalse(matches(request = recipe.copy(packageVersion = "1.0.0")))
    }
    @Test fun malformedOrIncompleteReviewIsNotAccepted() {
        for (key in listOf("schema", "packageName", "nodeCommand", "nodeMajor", "additionalDependencies", "allowLifecycleScripts", "lifecycleScriptReview")) {
            assertFalse(key, matches(lock = JsonObject(review() - key)))
        }
        assertFalse(matches(lock = JsonObject(review() + ("packageName" to JsonPrimitive(7)))))
        assertFalse(matches(lock = JsonObject(review() + ("schema" to JsonPrimitive(2)))))
    }
}

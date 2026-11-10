package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UbuntuJavascriptDebugCatalogTest {
    @Test fun officialAdapterResolvesTheExactReviewedArchitectureNeutralRelease() = runBlocking {
        var lookup = ""
        val recipe = LocalUbuntuVendorResolver { url -> lookup = url; RELEASE }.resolve(LocalUbuntuJsDebugPolicy.FAMILY, LocalUbuntuJsDebugPolicy.VERSION)
        assertEquals("https://api.github.com/repos/microsoft/vscode-js-debug/releases/tags/v1.140.0", lookup)
        assertEquals(LocalUbuntuJsDebugPolicy.ARTIFACT_SHA256, recipe.assetSha256)
        assertEquals(LocalUbuntuJsDebugPolicy.ARTIFACT_BYTES, recipe.assetSize)
        assertEquals(listOf(LocalUbuntuJsDebugPolicy.NODE_GUEST), recipe.definition.interpreter)
        assertEquals(listOf("--help"), recipe.healthArgs)
        assertEquals(LocalUbuntuVendorAdmission.INTERPRETED, recipe.definition.admission)
        recipe.validate()
    }

    @Test fun changedPublisherDigestSizeVersionOrInterpreterCannotActivate() = runBlocking {
        val recipe = LocalUbuntuVendorResolver { RELEASE }.resolve(LocalUbuntuJsDebugPolicy.FAMILY)
        for (bad in listOf(recipe.copy(assetSha256 = "b".repeat(64)), recipe.copy(assetSize = recipe.assetSize!! + 1),
            recipe.copy(version = "1.140.1"), recipe.copy(definition = recipe.definition.copy(interpreter = listOf("/usr/bin/node"))))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        }
    }

    @Test fun nodeDependencyMustBelongToTheSameUbuntuAbiAndOwnedDestination() {
        val node = nodeReceipt()
        LocalUbuntuJsDebugPolicy.requireNode(node, APP_ROOT)
        for (bad in listOf(node.copy(abi = "x86_64"), node.copy(version = "20.0.0"),
            node.copy(commands = mapOf("node" to "/usr/bin/node")), node.copy(installRoot = "/external/node"),
            node.copy(metadata = node.metadata + (PackageBackendContract.METADATA_KEY to PackageBackendId.DEVICE_ADB.name)))) {
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuJsDebugPolicy.requireNode(bad, APP_ROOT) }
        }
    }

    @Test fun wrapperForwardsRuntimeArgumentsAndHealthDoesNotBindADebugServer() = runBlocking {
        val recipe = LocalUbuntuVendorResolver { RELEASE }.resolve(LocalUbuntuJsDebugPolicy.FAMILY)
        val target = "/opt/droide/packages/debug.js-debug/1.140.0/arm64-v8a/payload/js-debug/src/dapDebugServer.js"
        assertEquals(listOf(LocalUbuntuJsDebugPolicy.NODE_GUEST, target, "--help"), LocalUbuntuVendorPolicy.healthCommand(recipe, target))
        val wrapper = LocalUbuntuVendorPolicy.guestWrapper(recipe, target, "/opt/droide/packages/debug.js-debug")
        assertTrue(wrapper.contains("\"${'$'}@\""))
        assertFalse(wrapper.contains("npm install"))
        assertFalse(wrapper.contains("--version"))
        assertNotNull(DevelopmentExtensionCatalog.byId(LocalUbuntuJsDebugPolicy.FAMILY))
    }

    private fun nodeReceipt(): ManagedPackageRecord {
        val path = "$APP_ROOT/packages/runtime.node/${LocalUbuntuJsDebugPolicy.NODE_VERSION}/arm64-v8a"
        return ManagedPackageRecord("runtime.node", LocalUbuntuJsDebugPolicy.NODE_VERSION,
            ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
            commands = mapOf("node" to "$path/payload/host-bin/node"), artifactSha256 = "a".repeat(64),
            abi = "arm64-v8a", metadata = mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name))
    }
    private companion object {
        const val APP_ROOT = "/app/files"
        val RELEASE = """{"draft":false,"prerelease":false,"tag_name":"v1.140.0","assets":[{"name":"js-debug-dap-v1.140.0.tar.gz","browser_download_url":"https://github.com/microsoft/vscode-js-debug/releases/download/v1.140.0/js-debug-dap-v1.140.0.tar.gz","digest":"sha256:${LocalUbuntuJsDebugPolicy.ARTIFACT_SHA256}","size":${LocalUbuntuJsDebugPolicy.ARTIFACT_BYTES}}]}"""
    }
}

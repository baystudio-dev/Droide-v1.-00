package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class UbuntuSdkLanguageServerCatalogTest {
    @Test fun dockerfileLanguageServerUsesItsActualNpmBinWithLifecycleScriptsBlocked() {
        val entry = requireNotNull(UbuntuNpmCatalog.find("lsp.dockerfile-language-server"))
        assertEquals("dockerfile-language-server-nodejs", entry.packageName)
        assertEquals("docker-langserver", entry.command)
        val recipe = WorkstationInstallRecipe(entry.familyId, "0.15.0", entry.packageName,
            "0.15.0", entry.command, 22, entry.provenance)
        assertTrue(UbuntuNpmCatalog.supports(recipe))
        assertFalse(UbuntuNpmCatalog.supports(recipe.copy(command = "dockerfile-language-server")))
        assertFalse(UbuntuNpmCatalog.supports(recipe.copy(allowLifecycleScripts = true, lifecycleScriptReview = "unreviewed")))
    }

    @Test fun dartLspIntegrationPreservesTheNormalDartCliAndUsesTheOwnedSdk() {
        val primary = sdk("runtime.dart", "3.13.4", setOf("dart"))
        val plan = LocalUbuntuBundleAliasPolicy.plan("lsp.dart", primary, APP_ROOT)
        val command = plan.recipe.commands.getValue("dart")
        assertEquals(emptyList<String>(), command.fixedArgs)
        assertEquals(listOf("--version"), command.healthArgs)
        assertEquals("runtime.dart@3.13.4", plan.primaryKey)
        val wrapper = LocalUbuntuBundleAliasPolicy.files(plan,
            "$APP_ROOT/packages/lsp.dart/${plan.version}/arm64-v8a", APP_ROOT).getValue("payload/bin/dart")
        assertFalse("A Dart integration cannot hijack all dart commands", wrapper.contains("language-server"))
        assertTrue(wrapper.contains("\"${'$'}@\""))
    }

    @Test fun swiftLspCannotActivateWithoutTheRealSourceKitAuxiliaryCommand() {
        val primary = sdk("toolchain.swift", "6.4.0", setOf("swift", "swiftc", "sourcekit-lsp"))
        val plan = LocalUbuntuBundleAliasPolicy.plan("lsp.sourcekit-lsp", primary, APP_ROOT)
        assertEquals(setOf("sourcekit-lsp"), plan.recipe.commands.keys)
        assertEquals("toolchain.swift@6.4.0", plan.primaryKey)
        assertEquals(listOf("--help"), plan.healthChecks().single().args)
        assertThrows(IllegalArgumentException::class.java) {
            LocalUbuntuBundleAliasPolicy.plan("lsp.sourcekit-lsp", primary.copy(commands = primary.commands - "sourcekit-lsp"), APP_ROOT)
        }
    }

    @Test fun sdkLanguageServersRejectForeignAbiAndDeviceReceipts() {
        val dart = sdk("runtime.dart", "3.13.4", setOf("dart"))
        val swift = sdk("toolchain.swift", "6.4.0", setOf("swift", "sourcekit-lsp"))
        for ((family, primary) in listOf("lsp.dart" to dart, "lsp.sourcekit-lsp" to swift)) {
            for (bad in listOf(primary.copy(abi = "x86_64"), primary.copy(metadata = primary.metadata +
                (PackageBackendContract.METADATA_KEY to PackageBackendId.DEVICE_ADB.name)),
                primary.copy(installRoot = "/root/unverified-sdk"))) {
                assertThrows(IllegalArgumentException::class.java) { LocalUbuntuBundleAliasPolicy.plan(family, bad, APP_ROOT) }
            }
        }
    }

    private fun sdk(family: String, version: String, commands: Set<String>): ManagedPackageRecord {
        val path = "$APP_ROOT/packages/$family/$version/arm64-v8a"
        return ManagedPackageRecord(family, version, ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
            commands = commands.associateWith { "$path/payload/host-bin/$it" },
            artifactSha256 = "a".repeat(64), abi = "arm64-v8a", metadata = mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name))
    }
    private companion object { const val APP_ROOT = "/app/files" }
}

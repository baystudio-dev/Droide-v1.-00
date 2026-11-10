package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuBundleAliasPolicyTest {
    @Test fun aliasSealBindsExactSdkAndWrapperBytes() = withRoot { root ->
        val primary = sdk(root, "22.23.3")
        val alias = alias(root, primary)
        assertTrue(LocalUbuntuBundleAliasPolicy.verifyReceipt(alias, primary, root.path))
        assertFalse(LocalUbuntuBundleAliasPolicy.verifyReceipt(alias.copy(dependencies = emptyList()), primary, root.path))
        assertFalse(LocalUbuntuBundleAliasPolicy.verifyReceipt(alias, primary.copy(version = "22.23.2"), root.path))
        File(alias.installRoot, "payload/bin/npm").appendText("echo changed\n")
        assertFalse(LocalUbuntuBundleAliasPolicy.verifyReceipt(alias, primary, root.path))
    }
    @Test fun unexpectedFilesAndLinksAreRejected() = withRoot { root ->
        val primary = sdk(root, "22.23.3"); val alias = alias(root, primary)
        val added = File(alias.installRoot, "unexpected").apply { writeText("x") }
        assertFalse(LocalUbuntuBundleAliasPolicy.verifyReceipt(alias, primary, root.path)); added.delete()
        Files.createSymbolicLink(File(alias.installRoot, "link").toPath(), File(root, "user-file").toPath())
        assertFalse(LocalUbuntuBundleAliasPolicy.verifyReceipt(alias, primary, root.path))
    }
    @Test fun aliasCommandsOverrideOnlyTheirSdkAndUninstallRestoresProjection() = withRoot { root ->
        val primary = sdk(root, "22.23.3"); val alias = alias(root, primary)
        val projected = LocalUbuntuBundleAliasPolicy.projectCommands(listOf(primary, alias), root.path)
        assertEquals(setOf("node"), projected.first { it.familyId == primary.familyId }.commands.keys)
        assertEquals(setOf("npm", "npx"), projected.first { it.familyId == alias.familyId }.commands.keys)
        assertEquals(primary.commands, LocalUbuntuBundleAliasPolicy.projectCommands(listOf(primary), root.path).single().commands)
        try { LocalUbuntuBundleAliasPolicy.projectCommands(listOf(primary, alias.copy(dependencies = emptyList())), root.path); fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun userManagedOrWrongAbiSdkCannotAcquireAliases() = withRoot { root ->
        val primary = sdk(root, "22.23.3")
        for (bad in listOf(primary.copy(abi = "x86_64"), primary.copy(installRoot = "/tmp/user-node"), primary.copy(metadata = emptyMap()))) {
            try { LocalUbuntuBundleAliasPolicy.plan("package.npm", bad, root.path); fail() }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(setOf("dart-test"), LocalUbuntuBundleAliasPolicy.find("test.dart")!!.commands.keys)
    }
    @Test fun dartTestHealthDoesNotRequireAnExistingUserProject() = withRoot { root ->
        val node = sdk(root, "3.13.4")
        val path = File(root, "packages/runtime.dart/3.13.4/arm64-v8a").path
        val dart = node.copy(familyId = "runtime.dart", installRoot = path, commands = mapOf("dart" to "$path/payload/host-bin/dart"))
        val plan = LocalUbuntuBundleAliasPolicy.plan("test.dart", dart, root.path)
        val command = plan.recipe.commands.getValue("dart-test")
        assertEquals(listOf("test"), command.fixedArgs)
        assertEquals(emptyList<String>(), command.healthFixedArgs)
        assertEquals(listOf("help", "test"), command.healthArgs)
        assertEquals(listOf("--droide-health"), plan.healthChecks().single().args)
        assertTrue(LocalUbuntuBundleAliasPolicy.files(plan, "$root/packages/test.dart/${plan.version}/arm64-v8a", root.path)
            .getValue("payload/bin/dart-test").contains("--droide-health"))
    }
    private fun sdk(root: File, version: String): ManagedPackageRecord {
        val path = File(root, "packages/runtime.node/$version/arm64-v8a").path
        return ManagedPackageRecord("runtime.node", version, ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
            commands = setOf("node", "npm", "npx").associateWith { "$path/payload/host-bin/$it" },
            artifactSha256 = "a".repeat(64), abi = "arm64-v8a", metadata = mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name))
    }
    private fun alias(root: File, primary: ManagedPackageRecord): ManagedPackageRecord {
        val plan = LocalUbuntuBundleAliasPolicy.plan("package.npm", primary, root.path)
        val path = File(root, "packages/package.npm/${plan.version}/arm64-v8a").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val files = LocalUbuntuBundleAliasPolicy.files(plan, path.path, root.path) + ("DROIDE_ACTIVATION_ID" to id)
        files.forEach { (relative, text) -> File(path, relative).apply { parentFile!!.mkdirs(); writeText(text); if (relative.startsWith("payload/")) setExecutable(true, true) } }
        val sealFile = File(path, "SHA256SUMS").apply { writeText(LocalUbuntuBundleAliasPolicy.manifest(path, files.keys)) }
        val seal = LocalUbuntuBundleAliasPolicy.sha256(sealFile)
        return ManagedPackageRecord("package.npm", plan.version, primary.scope, path.path, 1,
            pathEntries = listOf("$path/payload/host-bin"), commands = plan.recipe.commands.keys.associateWith { "$path/payload/host-bin/$it" },
            dependencies = listOf(plan.primaryKey), healthChecks = plan.healthChecks(), artifactSha256 = seal, abi = "arm64-v8a",
            metadata = LocalUbuntuBundleAliasPolicy.metadata(plan, id, seal))
    }
    private fun withRoot(action: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-alias-test-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}

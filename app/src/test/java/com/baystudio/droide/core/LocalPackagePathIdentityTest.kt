package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalPackagePathIdentityTest {
    @Test fun uncreatedAnchorAddressIsCanonicalBeforePayloadProvisioning() = fixture { root, alias ->
        val identity = LocalPackagePathIdentity(alias)
        val relative = "/packages/runtime.ubuntu-deps.0123456789/r2"
        assertFalse(File(root.path + relative).exists())
        assertEquals(root.path + relative, identity.canonicalPackagePath(alias.path + relative))
        assertEquals(root.path + relative, identity.canonicalPackagePath(root.path + relative))
        assertEquals(root.path + relative, identity.normalizeLayerReceipt(layer(alias.path + relative)).installRoot)
    }

    @Test fun aliasedUbuntuAnchorIsRebasedWithoutChangingItsSealedPayload() = fixture { root, alias ->
        val install = File(root, "packages/runtime.ubuntu-deps.0123456789/r2").apply { mkdirs() }
        val launcher = "${alias.path}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
        val sealed = File(install, "payload/verify-layer").apply {
            parentFile!!.mkdirs(); writeText("#!/system/bin/sh\nexec /system/bin/sh '$launcher'\n")
        }
        val originalBytes = sealed.readBytes()
        val legacy = layer("${alias.path}/packages/runtime.ubuntu-deps.0123456789/r2")
        assertNull(PackageBackendContract.recordOwner(legacy.installRoot, legacy.metadata, root.path))
        val normalized = LocalPackagePathIdentity(alias).normalizeLayerReceipt(legacy)
        assertEquals(install.path, normalized.installRoot)
        PackageBackendContract.requireOwner(PackageBackendId.LOCAL_APP, normalized.installRoot, normalized.metadata, root.path)
        assertEquals(legacy.copy(installRoot = install.path), normalized)
        assertArrayEquals(originalBytes, sealed.readBytes())
        assertEquals(normalized, LocalPackagePathIdentity(alias).normalizeLayerReceipt(normalized))
    }

    @Test fun declarationAndBackendAreStillAuthenticatedByStorage() = fixture { root, alias ->
        val identity = LocalPackagePathIdentity(alias)
        val path = "${alias.path}/packages/runtime.ubuntu-deps.0123456789/r2"
        for (bad in listOf(
            layer(path).copy(metadata = layer(path).metadata + (PackageBackendContract.METADATA_KEY to "DEVICE_ADB")),
            layer("/data/local/tmp/droide/packages/runtime.ubuntu-deps.0123456789/r2"),
            layer("/data/user/10/com.baystudio.droide/files/packages/runtime.ubuntu-deps.0123456789/r2"),
            layer("${root.path}-other/packages/runtime.ubuntu-deps.0123456789/r2"),
        )) {
            assertEquals(bad, identity.normalizeLayerReceipt(bad))
            assertNotEquals(PackageBackendId.LOCAL_APP,
                PackageBackendContract.recordOwner(bad.installRoot, bad.metadata, root.path))
        }
    }

    @Test fun packageStorageLinksAndTraversalsCannotBecomeTrustedAliases() = fixture { root, alias ->
        val identity = LocalPackagePathIdentity(alias)
        val outside = File(root.parentFile, "outside").apply { mkdirs() }
        val inside = File(root, "inside").apply { mkdirs() }
        val packages = File(root, "packages").apply { mkdirs() }
        Files.createSymbolicLink(File(packages, "outside").toPath(), outside.toPath())
        Files.createSymbolicLink(File(packages, "inside").toPath(), inside.toPath())
        for (relative in listOf("packages/outside/r2", "packages/inside/r2", "packages/../outside/r2", "packages//r2", "packages/a/./r2")) {
            assertNull(relative, identity.canonicalPackagePath("${alias.path}/$relative"))
        }
        assertNull(identity.canonicalPackagePath("${alias.path}/packages/a\nb/r2"))
        assertNull(identity.canonicalPackagePath("${alias.path}/packages/a\\b/r2"))
    }

    @Test fun retargetedAppAliasCannotMigrateAnotherDirectory() = fixture { root, alias ->
        val identity = LocalPackagePathIdentity(alias)
        assertTrue(alias.delete())
        val foreign = File(root.parentFile, "foreign").apply { mkdirs() }
        Files.createSymbolicLink(alias.toPath(), foreign.toPath())
        assertNull(identity.canonicalPackagePath("${alias.path}/packages/runtime.ubuntu-deps.0123456789/r2"))
    }

    @Test fun sdkWrappersAndUnexpectedLayerCommandsAreNeverResealedOrRewritten() = fixture { _, alias ->
        val identity = LocalPackagePathIdentity(alias)
        val path = "${alias.path}/packages/runtime.node/22.23.3/arm64-v8a"
        val sdk = layer(path).copy(familyId = "runtime.node", metadata = mapOf(
            LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
            PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
        ))
        assertEquals(sdk, identity.normalizeLayerReceipt(sdk))
        val badLayer = layer(path).copy(commands = mapOf("node" to "$path/payload/host-bin/node"))
        assertEquals(badLayer, identity.normalizeLayerReceipt(badLayer))
    }

    private fun layer(path: String) = ManagedPackageRecord(
        "runtime.ubuntu-deps.0123456789", "r2", ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
        healthChecks = listOf(ManagedPackageHealthCheck("verify-layer", emptyList())),
        metadata = mapOf(
            FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
            FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to "libstdc++6",
        ),
    )

    private inline fun fixture(block: (File, File) -> Unit) {
        val base = Files.createTempDirectory("droide-package-root-").toFile()
        val root = File(base, "data-user-0-files").apply { mkdirs() }
        val alias = File(base, "data-data-files")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        try { block(root, alias) } finally { PathSecurity.deleteTreeNoFollow(base) }
    }
}

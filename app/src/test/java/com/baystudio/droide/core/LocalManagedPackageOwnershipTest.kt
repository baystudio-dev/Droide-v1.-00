package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalManagedPackageOwnershipTest {
    @Test fun bothGuestManagersUseAuthoritysCanonicalApplicationRoot() = fixture { context, authority, _, _ ->
        val expected = authority.appRoot + "/guest/"
        assertEquals(expected + FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR + "/launch",
            FoundryUbuntuGuestEnvironmentManager(context).launcherPath())
        assertEquals(expected + WorkstationGuestEnvironmentSpec.ROOT_DIR + "/launch",
            WorkstationGuestEnvironmentManager(context).launcherPath())
    }

    @Test fun persistedLegacyLayerIsOwnedAndReadableBeforeAnyRuntimeOrDownload() = fixture { _, authority, registry, alias ->
        val legacy = layer("$alias/packages/runtime.ubuntu-deps.0123456789/r2")
        registry.put(legacy)
        assertTrue(authority.owns(legacy))
        assertTrue(authority.hasReceipt(legacy.familyId, legacy.version))
        val canonical = requireNotNull(authority.ownedReceipt(legacy.familyId, legacy.version))
        assertEquals(authority.appRoot + "/packages/runtime.ubuntu-deps.0123456789/r2", canonical.installRoot)
        assertEquals(legacy, registry.find(legacy.familyId, legacy.version))
        assertEquals(canonical, authority.normalizeReceipt(canonical))
    }

    @Test fun deviceReceiptAndOtherAppCannotUseTheLocalRecoveryRoute() = fixture { _, authority, registry, alias ->
        for (foreign in listOf(
            layer("$alias/packages/runtime.ubuntu-deps.0123456789/r2").let {
                it.copy(metadata = it.metadata + (PackageBackendContract.METADATA_KEY to "DEVICE_ADB"))
            },
            layer("/data/local/tmp/droide/packages/runtime.ubuntu-deps.0123456789/r2"),
            layer(authority.appRoot + "-other/packages/runtime.ubuntu-deps.0123456789/r2"),
        )) {
            registry.put(foreign)
            assertFalse(authority.owns(foreign))
            assertFalse(authority.hasReceipt(foreign.familyId, foreign.version))
            assertNull(authority.ownedReceipt(foreign.familyId, foreign.version))
        }
    }

    @Test fun localLookingReceiptCannotClaimAnExternalPackageStorageLink() = fixture { _, authority, registry, _ ->
        val outside = Files.createTempDirectory("droide-other-app-packages-").toFile()
        try {
            val packages = File(authority.appRoot, "packages").apply { mkdirs() }
            Files.createSymbolicLink(File(packages, "runtime.ubuntu-deps.0123456789").toPath(), outside.toPath())
            val forged = layer(authority.appRoot + "/packages/runtime.ubuntu-deps.0123456789/r2")
            registry.put(forged)
            assertFalse(authority.owns(forged))
            assertNull(authority.ownedReceipt(forged.familyId, forged.version))
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    private fun layer(path: String) = ManagedPackageRecord(
        "runtime.ubuntu-deps.0123456789", "r2", ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
        healthChecks = listOf(ManagedPackageHealthCheck("verify-layer", emptyList())),
        metadata = mapOf(
            FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
            FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to "libstdc++6",
        ),
    )

    private inline fun fixture(block: (Context, LocalManagedPackageAuthority, ManagedPackageRegistry, String) -> Unit) {
        val application = RuntimeEnvironment.getApplication()
        val base = Files.createTempDirectory("droide-local-owner-").toFile()
        val canonical = File(base, "canonical-files").apply { mkdirs() }
        val alias = File(base, "alias-files")
        Files.createSymbolicLink(alias.toPath(), canonical.toPath())
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = alias
        }
        val prefs = context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        try { block(context, LocalManagedPackageAuthority(context, ManagedPackageRegistry(context)), ManagedPackageRegistry(context), alias.path) }
        finally { prefs.edit().clear().commit(); PathSecurity.deleteTreeNoFollow(base) }
    }
}

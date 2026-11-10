package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Authored local transaction/plan controls. These tests do not run a downloaded installer or runtime. */
class LocalUbuntuCMakeInstallerTest {
    @Test fun everyPriorKindRetainsItsExactStageIdentity() {
        val suffixes=mapOf(
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW to "local-ubuntu-raw",
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE to "local-ubuntu-tree",
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE to "local-ubuntu-vendor",
            LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS to "local-ubuntu-alias",
            LocalManagedPackageMetadata.KIND_UBUNTU_APT to "local-ubuntu-apt",
            LocalManagedPackageMetadata.KIND_UBUNTU_NPM to "local-ubuntu-npm",
            LocalManagedPackageMetadata.KIND_UBUNTU_RUBY to "local-ubuntu-ruby",
            LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION to "local-android-workstation",
            LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT to "local-android-sdk-component",
            LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE to "local-ubuntu-cmake")
        val names=mutableSetOf<String>()
        suffixes.forEach { (kind,suffix) ->
            val p=LocalPackageInstallJournal.paths("/authored","lsp.fixture","1.0",kind)
            val hash=MessageDigest.getInstance("SHA-256").digest("lsp.fixture@1.0:$suffix".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)
            val name="lsp.fixture-1.0-$suffix-$hash"
            assertEquals(File("/authored/packages/.staging/$name"),p.stage)
            assertEquals(File("/authored/packages/.previous/$name"),p.previous)
            assertEquals(File("/authored/packages/lsp.fixture/1.0/arm64-v8a"),p.final)
            assertEquals(File("/authored/packages/.staging/lsp.fixture-1.0-$suffix"),p.legacyStage)
            assertTrue(names.add(p.stage.name))
        }
        assertTrue(runCatching {LocalPackageInstallJournal.paths("/authored","lsp.fixture","1.0","unreviewed")}.isFailure)
    }
    @Test fun failedCMakeAdoptionRestoresExactPriorGeneration() {
        val root=Files.createTempDirectory("cmake-transaction-model").toFile()
        try {
            val p=LocalPackageInstallJournal.paths(root.path,LocalUbuntuCMakePolicy.FAMILY,LocalUbuntuCMakePolicy.VERSION,LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE)
            p.final.mkdirs();File(p.final,"authored").writeText("old");val oldId=LocalPackageInstallJournal.prepare(p.final)
            p.stage.mkdirs();File(p.stage,"authored").writeText("new");val newId=LocalPackageInstallJournal.prepare(p.stage)
            val activation=LocalPackageInstallJournal.activate(p.stage,p.final,p.previous)
            assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,newId))
            LocalPackageInstallJournal.rollback(p.final,p.previous,activation)
            assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,oldId))
            assertEquals("old",File(p.final,"authored").readText())
        } finally {root.deleteRecursively()}
    }
    @Test fun onlyExactCMakePlanIsAdmitted() {
        val plan=ExtensionInstallPlan.LocalCMake(LocalUbuntuCMakePolicy.FAMILY,"CMake",LocalUbuntuCMakePolicy.VERSION,LocalUbuntuCMakeCatalog.recipe,emptyList())
        for(reviewed in listOf(false,true)) {
            UbuntuExtensionPlanPolicy.requireSupported(plan,reviewed)
            for(stale in listOf(plan.copy(familyId="lsp.other"),plan.copy(requestedVersion="latest"),plan.copy(recipe=plan.recipe.copy(wheels=plan.recipe.wheels.dropLast(1))))) {
                assertTrue(runCatching {UbuntuExtensionPlanPolicy.requireSupported(stale,reviewed)}.isFailure)
            }
            assertTrue(runCatching {UbuntuExtensionPlanPolicy.requireSupported(ExtensionInstallPlan.Package("old.package","Old","1",emptyList(),emptyList()),reviewed)}.isFailure)
        }
    }
}

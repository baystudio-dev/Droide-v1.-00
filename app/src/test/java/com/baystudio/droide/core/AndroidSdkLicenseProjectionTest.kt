package com.baystudio.droide.core

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidSdkLicenseProjectionTest {
    @Test fun managedSdkLaunchBindsLiveLicenseDirectoryAlongsideComponents() {
        val context = RuntimeEnvironment.getApplication()
        LocalExecutionSubstrate.init(context)
        val root = Files.createTempDirectory(context.filesDir.toPath(), "sdk-bind-").toFile()
        val guest = Files.createTempDirectory("sdk-guest-").toFile()
        try {
            val install = root.resolve("packages/sdk.android/36/arm64-v8a")
            install.resolve("payload/component").mkdirs()
            val record = ManagedPackageRecord(familyId = "sdk.android", version = "36", active = true,
                scope = ExecutionScope.LOCAL_LINUX_ARM64.name, installRoot = install.path, installedAtEpochMs = 1,
                artifactSha256 = "a".repeat(64), abi = "arm64-v8a", metadata = mapOf(
                    LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT,
                    LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY to "platforms/android-36"))
            val bindings = LocalAndroidSdkComponentEnvironment.bindings(listOf(record), emptyMap(), root.path)
            assertEquals(setOf("/opt/droide/android-sdk/platforms/android-36", "/opt/droide/android-sdk/licenses"), bindings.map { it.guestPath }.toSet())
            LocalAndroidSdkComponentEnvironment.prepareGuestTargets(guest, bindings)
            assertTrue(guest.resolve("opt/droide/android-sdk/licenses").isDirectory)
            val mounted = java.io.File(bindings.single { it.guestPath.endsWith("/licenses") }.hostPath)
            assertFalse(mounted.resolve("android-sdk-license").exists())
            AndroidSdkLicenseProjection.writeAccepted(root, licenseFixture())
            assertTrue(mounted.resolve("android-sdk-license").isFile)
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(guest) }
    }

    @Test fun onlyAcceptedOriginalRepositoryValueProducesSdkReceiptAndRevokeRemovesIt() {
        val root = Files.createTempDirectory("sdk-consent-").toFile()
        try {
            val directory = AndroidSdkLicenseProjection.directory(root)
            assertFalse(directory.resolve("android-sdk-license").exists())
            val license = licenseFixture("Terms with Unicode: izin ✓").also { it.repositoryText = it.text + "\n" }
            AndroidSdkLicenseProjection.writeAccepted(root, license)
            val expected = MessageDigest.getInstance("SHA-1").digest(license.repositoryText.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(expected + "\n", directory.resolve("android-sdk-license").readText())
            AndroidSdkLicenseProjection.writeAccepted(root, license)
            assertEquals(listOf(expected), directory.resolve("android-sdk-license").readLines())
            AndroidSdkLicenseProjection.revoke(root)
            assertFalse(directory.resolve("android-sdk-license").exists()); assertTrue(directory.isDirectory)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun mismatchingRepositoryTextCannotGrantUnreviewedTerms() {
        val root = Files.createTempDirectory("sdk-consent-").toFile()
        try {
            val license = licenseFixture().also { it.repositoryText = "Unreviewed license" }
            assertTrue(runCatching { AndroidSdkLicenseProjection.writeAccepted(root, license) }.isFailure)
            assertFalse(root.resolve("android-sdk-consent/licenses/android-sdk-license").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun symlinkDirectoryOrReceiptCannotWriteOutsideOwnedStorage() {
        val root = Files.createTempDirectory("sdk-consent-").toFile()
        val outside = Files.createTempDirectory("sdk-outside-").toFile()
        try {
            Files.createSymbolicLink(root.resolve("android-sdk-consent").toPath(), outside.toPath())
            assertTrue(runCatching { AndroidSdkLicenseProjection.writeAccepted(root, licenseFixture()) }.isFailure)
            assertTrue(outside.listFiles()!!.isEmpty())
            Files.delete(root.resolve("android-sdk-consent").toPath())
            val directory = AndroidSdkLicenseProjection.directory(root)
            val victim = outside.resolve("keep").apply { writeText("untouched") }
            Files.createSymbolicLink(directory.resolve("android-sdk-license").toPath(), victim.toPath())
            assertTrue(runCatching { AndroidSdkLicenseProjection.writeAccepted(root, licenseFixture()) }.isFailure)
            assertEquals("untouched", victim.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun noManagedSdkDoesNotClaimAndroidEnvironmentFromLicenseAlone() {
        val root = Files.createTempDirectory("sdk-consent-").toFile()
        try {
            AndroidSdkLicenseProjection.writeAccepted(root, licenseFixture())
            assertTrue(LocalAndroidSdkComponentEnvironment.bindings(emptyList(), emptyMap(), root.path).isEmpty())
            assertTrue(LocalAndroidSdkComponentEnvironment.environment(emptyList()).isEmpty())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

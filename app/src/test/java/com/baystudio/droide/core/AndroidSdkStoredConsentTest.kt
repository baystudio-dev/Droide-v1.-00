package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidSdkStoredConsentTest {
    @Test fun invalidRepositoryValueCannotReplacePreviousConsent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = AndroidSdkLicenseManager(context)
        val previous = licenseFixture("Previously reviewed test terms")
        manager.revoke()
        try {
            manager.accept(previous)
            val invalid = licenseFixture("Different displayed terms").also { it.repositoryText = "Unreviewed text" }
            assertTrue(runCatching { manager.accept(invalid) }.isFailure)
            assertTrue(manager.isAccepted(previous))
        } finally { manager.revoke() }
    }

    @Test fun persistedExactConsentRepairsMissingSdkProjectionWithoutAskingAgain() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = AndroidSdkLicenseManager(context)
        val license = licenseFixture("Test terms, not a real SDK license").also { it.repositoryText += "\n" }
        val receipt = AndroidSdkLicenseProjection.directory(context.filesDir).resolve("android-sdk-license")
        manager.revoke()
        try {
            assertFalse(manager.isAccepted(license)); assertFalse(receipt.exists())
            manager.accept(license)
            val bytes = receipt.readBytes()
            assertTrue(receipt.delete())
            assertTrue(AndroidSdkLicenseManager(context).isAccepted(license))
            assertArrayEquals(bytes, receipt.readBytes())
            assertFalse(manager.isAccepted(licenseFixture("A different revision")))
        } finally { manager.revoke() }
        assertFalse(manager.isAccepted(license)); assertFalse(receipt.exists())
    }

    @Test fun concurrentReceiptChecksCannotResurrectRevokedSdkConsent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = AndroidSdkLicenseManager(context)
        val license = licenseFixture("Concurrent test consent")
        manager.revoke()
        try {
            manager.accept(license)
            val checks = (1..40).map { async(Dispatchers.Default) { AndroidSdkLicenseManager(context).isAccepted(license) } }
            val revoke = async(Dispatchers.Default) { manager.revoke() }
            checks.awaitAll(); revoke.await()
            assertFalse(manager.isAccepted(license))
            assertFalse(AndroidSdkLicenseProjection.directory(context.filesDir).resolve("android-sdk-license").exists())
        } finally { manager.revoke() }
    }
}

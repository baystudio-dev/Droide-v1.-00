package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

internal fun licenseFixture(text: String = "Reviewed terms\n".repeat(900) + "END OF EXACT LICENSE") =
    AndroidSdkLicense(text, MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) })

class ToolchainInstallConsentTest {
    private class Fixture(effect: PermEffect = PermEffect.ALLOW) {
        val permissions = PermissionEngine(listOf(PermRule("*", "*", effect)))
        val approvals = ApprovalManager()
        val license = licenseFixture()
        val events = mutableListOf<String>()
        var owner = true
        var acceptFailure = false
        suspend fun install(accepted: Boolean = false, terms: AndroidSdkLicense? = license) =
            ToolchainInstallConsent.install(permissions, approvals, "install:sdk@36:${terms?.sha256}", "install SDK 36",
                "Google SDK Platform 36; exact artifact fixture", terms, accepted, "build", AgentRequestProvenance.primary("build-turn"),
                requireCurrent = { check(owner) { "Owner retired" } }, acceptLicense = {
                    check(!acceptFailure) { "Receipt could not be saved" }; events += "accept:${terms?.sha256}"
                }, install = { events += "install"; "TOOLCHAIN_INSTALL_RESULT\nsuccess=true" })
    }

    @Test(timeout = 5000) fun fullLicenseWaitsForExplicitConsentEvenWithAllowAllAndThenResumesOnce() = runBlocking {
        val f = Fixture()
        val work = async { f.install() }
        val request = requireNotNull(f.approvals.pending.first { it != null })
        assertEquals(ToolchainInstallConsent.KIND, request.kind)
        assertTrue(request.detail.length > 8000)
        assertTrue(request.detail.endsWith("END OF EXACT LICENSE"))
        assertFalse(request.canAllowForSession)
        assertTrue(f.events.isEmpty())
        assertFalse(f.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        assertTrue(work.isActive)
        assertTrue(f.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        assertTrue(work.await().contains("success=true"))
        assertEquals(listOf("accept:${f.license.sha256}", "install"), f.events)
        assertNull(f.approvals.pending.value)
        assertFalse(f.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
    }

    @Test(timeout = 5000) fun denialDoesNotAcceptOrInstall() = runBlocking {
        val f = Fixture(); val work = async { f.install() }
        val request = requireNotNull(f.approvals.pending.first { it != null })
        f.approvals.resolve(request.id, ApprovalResolution.DENY)
        assertTrue(work.await().startsWith("DENIED")); assertTrue(f.events.isEmpty())
    }

    @Test(timeout = 5000) fun cancellationWithdrawsPromptAndCannotCommitALateApproval() = runBlocking {
        val f = Fixture(); val work = async { f.install() }
        val request = requireNotNull(f.approvals.pending.first { it != null })
        work.cancelAndJoin()
        assertNull(f.approvals.pending.value)
        assertFalse(f.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE)); assertTrue(f.events.isEmpty())
    }

    @Test fun policyDenialStopsBeforeShowingTerms() = runBlocking {
        val f = Fixture(PermEffect.DENY)
        assertTrue(f.install().startsWith("DENIED")); assertNull(f.approvals.pending.value); assertTrue(f.events.isEmpty())
    }

    @Test(timeout = 5000) fun policyChangedWhileReviewingPreventsReceiptAndInstallation() = runBlocking {
        val f = Fixture(); val work = async { f.install() }
        val request = requireNotNull(f.approvals.pending.first { it != null })
        f.permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("toolchain_install", "*", PermEffect.DENY))))
        f.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE)
        assertTrue(work.await().startsWith("DENIED")); assertTrue(f.events.isEmpty())
    }

    @Test(timeout = 5000) fun ownerRetiredAfterApprovalCannotCommit() = runBlocking {
        val f = Fixture(); val work = async { runCatching { f.install() } }
        val request = requireNotNull(f.approvals.pending.first { it != null })
        f.owner = false; f.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE)
        assertTrue(work.await().isFailure); assertTrue(f.events.isEmpty())
    }

    @Test(timeout = 5000) fun receiptFailureDoesNotStartInstallation() = runBlocking {
        val f = Fixture(); f.acceptFailure = true
        val work = async { runCatching { f.install() } }
        val request = requireNotNull(f.approvals.pending.first { it != null })
        f.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE)
        assertTrue(work.await().isFailure); assertTrue(f.events.isEmpty())
    }

    @Test fun existingExactReceiptDoesNotAskAgainOrWriteAnotherAcceptance() = runBlocking {
        val f = Fixture(); assertTrue(f.install(accepted = true).contains("success=true"))
        assertEquals(listOf("install"), f.events); assertNull(f.approvals.pending.value)
    }

    @Test(timeout = 5000) fun receiptDoesNotBypassSeparateInstallationPolicy() = runBlocking {
        val f = Fixture(PermEffect.ASK); val work = async { f.install(accepted = true) }
        val request = requireNotNull(f.approvals.pending.first { it != null })
        assertEquals("toolchain_install", request.kind)
        assertFalse(request.detail.contains(f.license.text)); assertTrue(f.events.isEmpty())
        f.approvals.resolve(request.id, ApprovalResolution.ALLOW_ONCE)
        assertTrue(work.await().contains("success=true")); assertEquals(listOf("install"), f.events)
    }

    @Test fun nonAndroidDependencyUsesNormalInstallationPolicy() = runBlocking {
        val f = Fixture(); assertTrue(f.install(terms = null).contains("success=true"))
        assertEquals(listOf("install"), f.events); assertNull(f.approvals.pending.value)
    }

    @Test fun changedTextCannotBePresentedUnderAnOldHash() = runBlocking {
        val f = Fixture(); val wrong = f.license.copy(text = "Different terms")
        assertTrue(runCatching { f.install(terms = wrong) }.isFailure)
        assertTrue(f.events.isEmpty()); assertNull(f.approvals.pending.value)
    }

    @Test(timeout = 5000) fun generalSessionGrantCannotAcceptNewLicenseRevision() = runBlocking {
        val f = Fixture(PermEffect.ASK)
        val install = async { f.install(terms = null) }
        val first = requireNotNull(f.approvals.pending.first { it != null })
        f.approvals.resolve(first.id, ApprovalResolution.ALLOW_SESSION); install.await()
        val next = async { f.install() }
        val second = requireNotNull(f.approvals.pending.first { it != null })
        assertEquals(ToolchainInstallConsent.KIND, second.kind)
        f.approvals.resolve(second.id, ApprovalResolution.DENY)
        assertTrue(next.await().startsWith("DENIED")); assertEquals(listOf("install"), f.events)
    }
}

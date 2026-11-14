package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalToolDownloadConsentTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences("consent-test", Context.MODE_PRIVATE)
    private fun plan(packages: List<String> = listOf("git")) = ExtensionInstallPlan.UbuntuApt(
        "tool.git", "Git", "latest", UbuntuAptRecipe("tool.git", packages, mapOf("git" to "/usr/bin/git")), listOf("Git"))

    @Test fun approvedPlanPersistsAcrossOwnersAndDoesNotAuthorizeChangedDependencies() = runBlocking {
        val preferences = preferences(); val consent = ExternalToolDownloadConsent(preferences, true)
        val plan = plan(); assertNotNull(consent.notice(plan))
        assertThrows(IllegalStateException::class.java) { consent.requireAccepted(plan) }
        consent.acceptFromUser(plan)
        repeat(5) { ExternalToolDownloadConsent(preferences, true).requireAccepted(plan) }
        assertNotNull(consent.notice(plan(listOf("git", "openssh-client"))))
        assertNotNull(consent.notice(plan.copy(familyId = "other.tool")))
    }

    @Test fun regularDistributionDoesNotRequireOrPersistAdditionalConsent() = runBlocking {
        val preferences = preferences(); val consent = ExternalToolDownloadConsent(preferences, false)
        assertNull(consent.notice(plan())); consent.requireAccepted(plan()); consent.acceptFromUser(plan())
        assertTrue(preferences.all.isEmpty())
    }

    @Test(timeout = 5000) fun allowAllPolicyStillNeedsOneExplicitDownloadDecisionAndDenialDoesNothing() = runBlocking {
        for (allow in listOf(false, true)) {
            val approvals = ApprovalManager(); val events = mutableListOf<String>()
            val work = async {
                ToolchainInstallConsent.install(PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
                    approvals, "install:tool.git", "install Git", "Git package", null, true, "build",
                    AgentRequestProvenance.primary("turn"), acceptLicense = { error("No SDK terms") },
                    install = { events += "install"; "success" }, externalDownloadNotice = ExternalToolDownloadConsent.NOTICE,
                    acceptExternalDownload = { events += "consent" })
            }
            val request = requireNotNull(approvals.pending.first { it != null })
            assertEquals("external_code_download", request.kind)
            assertTrue(request.detail.contains("outside F-Droid")); assertFalse(request.canAllowForSession)
            assertFalse(approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION)); assertTrue(events.isEmpty())
            approvals.resolve(request.id, if (allow) ApprovalResolution.ALLOW_ONCE else ApprovalResolution.DENY)
            val result = work.await()
            if (allow) { assertEquals("success", result); assertEquals(listOf("consent", "install"), events) }
            else { assertTrue(result.startsWith("DENIED")); assertTrue(events.isEmpty()) }
        }
    }
}

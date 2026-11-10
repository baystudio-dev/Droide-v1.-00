package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspProjectAnalysisConsentTest {
    private fun identity(project: String = "/workspace/app", configuration: String = "reviewed") =
        LspProjectAnalysisIdentity("/workspace", "perlnavigator", project, configuration)

    @Test fun observingDoesNotGrantAndExplicitApprovalBindsExactIdentity() {
        val authority = LspProjectAnalysisConsent(); val key = identity()
        assertNull(authority.observe(key))
        val permit = authority.approve(authority.begin(key)!!)!!
        assertSame(permit, authority.observe(key))
        assertTrue(authority.isCurrent(permit))
        assertNull(authority.observe(identity(project = "/workspace/other")))
        assertTrue(authority.isCurrent(permit))
    }

    @Test fun configurationChangeInvalidatesGrantedAndPendingIdentityEvenIfRestored() {
        val authority = LspProjectAnalysisConsent(); val key = identity()
        val pending = authority.begin(key)!!
        val permit = authority.approve(pending)!!
        assertNull(authority.observe(identity(configuration = "changed-command")))
        assertFalse(authority.isCurrent(pending)); assertFalse(authority.isCurrent(permit))
        assertNull(authority.approve(pending))
        assertNull(authority.observe(key))
        assertNull(authority.approve(pending))
    }

    @Test fun revocationCannotBeUndoneByOldApprovalOrOldPermit() {
        val authority = LspProjectAnalysisConsent(); val key = identity()
        val oldTicket = authority.begin(key)!!; val oldPermit = authority.approve(oldTicket)!!
        authority.revoke(key)
        assertNull(authority.approve(oldTicket)); assertFalse(authority.isCurrent(oldPermit))
        val freshPermit = authority.approve(authority.begin(key)!!)!!
        assertNotSame(oldPermit, freshPermit)
        assertFalse(authority.isCurrent(oldPermit)); assertTrue(authority.isCurrent(freshPermit))
    }

    @Test fun revocationDoesNotAffectAnUnrelatedServer() {
        val authority = LspProjectAnalysisConsent()
        val perl = identity(); val other = perl.copy(serverId = "other")
        val perlPermit = authority.approve(authority.begin(perl)!!)!!
        val otherPermit = authority.approve(authority.begin(other)!!)!!
        authority.revoke(perl)
        assertFalse(authority.isCurrent(perlPermit)); assertTrue(authority.isCurrent(otherPermit))
    }

    @Test fun revocationPreservesAnotherRootUsingTheSameServer() {
        val authority = LspProjectAnalysisConsent(); val first = identity(); val second = identity(project = "/workspace/other")
        val firstPermit = authority.approve(authority.begin(first)!!)!!
        val secondPermit = authority.approve(authority.begin(second)!!)!!
        authority.revoke(first)
        assertFalse(authority.isCurrent(firstPermit)); assertTrue(authority.isCurrent(secondPermit))
    }

    @Test fun closeRetiresTicketsAndGrantsAndNewRuntimeStartsEmpty() {
        val authority = LspProjectAnalysisConsent(); val key = identity()
        val ticket = authority.begin(key)!!; val permit = authority.approve(ticket)!!
        authority.close()
        assertFalse(authority.isCurrent(ticket)); assertFalse(authority.isCurrent(permit))
        assertNull(authority.begin(key)); assertNull(authority.approve(ticket)); assertNull(authority.observe(key))
        val nextRuntime = LspProjectAnalysisConsent()
        assertNull(nextRuntime.observe(key)); assertFalse(nextRuntime.isCurrent(permit))
    }

    @Test fun ticketFromDifferentRuntimeCannotGrantEvenWhenPathsMatch() {
        val prior = LspProjectAnalysisConsent(); val next = LspProjectAnalysisConsent(); val key = identity()
        val ticket = prior.begin(key)!!
        next.observe(key)
        assertNull(next.approve(ticket))
    }
}

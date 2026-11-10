package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentToolchainInstallResultPolicyTest {
    @Test fun successfulUpstreamUpdateDoesNotRequireAFabricatedManagedReceipt() {
        assertEquals("external", AgentToolchainInstallResultPolicy.state(true, null))
    }

    @Test fun managedActivationAndInactiveVersionKeepTheirReceiptState() {
        assertEquals("active", AgentToolchainInstallResultPolicy.state(false, "active"))
        assertEquals("inactive", AgentToolchainInstallResultPolicy.state(false, "inactive"))
    }

    @Test fun failedOrMissingManagedVerificationCannotBeReportedAsInstalled() {
        for (state in listOf(null, "external", "available", "unavailable")) {
            assertThrows(IllegalArgumentException::class.java) { AgentToolchainInstallResultPolicy.state(false, state) }
        }
    }
}

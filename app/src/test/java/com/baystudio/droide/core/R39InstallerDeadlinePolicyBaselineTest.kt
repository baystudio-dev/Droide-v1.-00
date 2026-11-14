package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Policy-only candidate. Does not certify the ManagedExtensionInstaller entry/wrapper. */
class R39InstallerDeadlinePolicyBaselineTest {
    @Test fun installAndRepairPoliciesDoNotImplicitlyLimitFiniteWork() {
        // Expected baseline red. A separate wrapper test is required before integration.
        assertEquals(0L, InstallerExecutionPolicy.operationDeadlineMs(ManagedExtensionInstaller.Phase.INSTALLING))
        assertEquals(0L, InstallerExecutionPolicy.operationDeadlineMs(ManagedExtensionInstaller.Phase.REPAIRING))
    }

    @Test fun controlPhasesHeartbeatAndRemoteRequestBudgetsStayFinite() {
        for (phase in listOf(ManagedExtensionInstaller.Phase.RESOLVING,
            ManagedExtensionInstaller.Phase.ACTIVATING, ManagedExtensionInstaller.Phase.REMOVING)) {
            assertEquals(1_800_000L, InstallerExecutionPolicy.operationDeadlineMs(phase))
        }
        assertEquals(20_000L, InstallerExecutionPolicy.HEARTBEAT_INTERVAL_MS)
        assertEquals(30_000L, AgentTimeoutPolicy.CONNECT_MS)
        assertEquals(90_000L, AgentTimeoutPolicy.ACP_STARTUP_MS)
        assertEquals(30_000L, AgentTimeoutPolicy.ACP_CONFIGURATION_MS)
        assertEquals(1_800_000L, AgentTimeoutPolicy.MCP_TOOL_MS)
    }
}

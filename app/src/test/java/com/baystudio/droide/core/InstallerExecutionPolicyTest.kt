package com.baystudio.droide.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class InstallerExecutionPolicyTest {
    @Test fun installationLifetimeIsSeparateFromProgressHeartbeat() {
        assertEquals(0L, InstallerExecutionPolicy.INSTALL_OPERATION_DEADLINE_MS)
        assertEquals(0L, InstallerExecutionPolicy.NPM_STEP_DEADLINE_MS)
        assertEquals(20_000L, InstallerExecutionPolicy.HEARTBEAT_INTERVAL_MS)
        // DefaultUnifiedPackageAuthority owns an INSTALLING transaction around prepare + install.
        assertEquals(InstallerExecutionPolicy.INSTALL_OPERATION_DEADLINE_MS,
            InstallerExecutionPolicy.operationDeadlineMs(ManagedExtensionInstaller.Phase.INSTALLING))
        assertEquals(0L, InstallerExecutionPolicy.operationDeadlineMs(ManagedExtensionInstaller.Phase.REPAIRING))
        for (phase in listOf(ManagedExtensionInstaller.Phase.ACTIVATING, ManagedExtensionInstaller.Phase.REMOVING)) {
            assertEquals(1_800_000L, InstallerExecutionPolicy.operationDeadlineMs(phase))
        }
    }

    @Test fun opencodeInstallationDoesNotForceProviderLogin() {
        val message = InstallerExecutionPolicy.successMessage("plugin.opencode")
        assertTrue(message.contains("installed and ready"))
        assertTrue(message.contains("Sign-in is optional"))
        assertTrue(message.contains("provider requires credentials"))
        assertFalse(message.contains("Complete upstream login"))
        assertTrue(InstallerExecutionPolicy.successMessage("plugin.gemini-cli").contains("only if required"))
    }

    @Test fun finalOperationStatePreservesVerifiedOpenCodeReadyMessage() {
        val ready = InstallerExecutionPolicy.successMessage("plugin.opencode")
        assertEquals(ready, InstallerExecutionPolicy.finalMessage(true, ready, "Installation completed."))
        assertEquals("Installation completed.",
            InstallerExecutionPolicy.finalMessage(false, "Installing…", "Installation completed."))
    }

    @Test fun quietDetectionResetsOnActualOutputWithoutFakePercentages() {
        var now = 1_000_000_000L
        val heartbeat = InstallerOutputHeartbeat(intervalMs = 100, clockNanos = { now })
        assertFalse(heartbeat.isQuiet())
        now += 110_000_000L
        assertTrue(heartbeat.isQuiet())
        heartbeat.observedOutput()
        assertFalse(heartbeat.isQuiet())
        now += 99_000_000L
        assertFalse(heartbeat.isQuiet())
        now += 1_000_000L
        assertTrue(heartbeat.isQuiet())
    }

    @Test fun heartbeatIsChildJobAndCancelStopsFutureUpdates() = runBlocking {
        val output = mutableListOf<String>()
        val beat = InstallerOutputHeartbeat(intervalMs = 20, clockNanos = System::nanoTime)
        val job = beat.start(this) { output += it }
        delay(70)
        job.cancelAndJoin()
        val size = output.size
        delay(50)
        assertEquals(size, output.size)
        assertTrue(output.all { it.contains("Still working") && !it.contains("%") })
    }
}

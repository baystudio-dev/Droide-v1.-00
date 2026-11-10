package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class NativePtyCleanupReceiptTest {
    @Test fun relayFailureCombinesWithoutErasingNativeFailureOrObservedExit() {
        val clean = NativePtyCleanupReceipt(0, 0, 0)
        assertEquals(0, clean.withRelayInputFailure(0).exitCode)
        val pending = clean.withRelayInputFailure(5)
        assertEquals(-1, pending.exitCode)
        assertEquals(5, pending.inputFailureReason)
        val nativeFailure = NativePtyCleanupReceipt(7, 4, 2).withRelayInputFailure(5)
        assertEquals(7, nativeFailure.observedExitStatus)
        assertEquals(4, nativeFailure.processFailureReason)
        assertEquals(2, nativeFailure.inputFailureReason)
        assertEquals(-1, nativeFailure.exitCode)
    }

    @Test fun correlationTokenIsDistinctFromGenericNumericLeaseEnvironment() {
        val token = NativePtyToken.create()
        assertEquals(setOf("DROIDE_NATIVE_PTY_TOKEN"), token.environment.keys)
        assertEquals(token.value, token.environment["DROIDE_NATIVE_PTY_TOKEN"])
        assertTrue(token.value.matches(Regex("[0-9a-f]{32}")))
        assertFalse(token.environment.containsKey("DROIDE_PROCESS_LEASE"))
    }

    @Test fun cleanExitRetainsDetachedProcessLimitation() {
        val receipt = NativePtyCleanupReceipt(0, 0, 0)
        assertEquals(0, receipt.exitCode)
        assertTrue(receipt.description.contains("process exited: 0"))
        assertTrue(receipt.description.contains("may still be running"))
    }

    @Test fun unknownOwnershipNeverInventsAnExitStatus() {
        for (reason in listOf(1, 2, 3, 99)) {
            val receipt = NativePtyCleanupReceipt(127, reason, 0)
            assertEquals(-1, receipt.exitCode)
            assertFalse(receipt.description.contains("process exited: 127"))
        }
    }

    @Test fun groupCleanupAndInputFailuresCannotLookLikeCleanSuccess() {
        val groupFailure = NativePtyCleanupReceipt(0, 4, 0)
        assertEquals(-1, groupFailure.exitCode)
        assertTrue(groupFailure.description.contains("process-group cleanup is unconfirmed"))
        for (reason in 1..5) {
            val inputFailure = NativePtyCleanupReceipt(0, 0, reason)
            assertEquals(-1, inputFailure.exitCode)
            assertTrue(inputFailure.description.contains("input delivery could not be confirmed"))
            assertTrue(inputFailure.description.contains("Nothing will be replayed"))
        }
    }

    @Test fun historicalNativeNamesAreExcludedNarrowly() {
        assertTrue(NativePtyCleanupReceipt.isLegacyLeaseName("native-terminal-old.pid"))
        assertTrue(NativePtyCleanupReceipt.isLegacyLeaseName("agent-native-pty-old.pid"))
        for (name in listOf("bootstrap-old.pid", "terminal-old.pid", "agent-command-old.pid", "native-terminal-old.log", "prefix-native-terminal-old.pid")) {
            assertFalse(name, NativePtyCleanupReceipt.isLegacyLeaseName(name))
        }
    }

    @Test fun localScannerSkipsLegacyNativeNamesBeforeEveryContentOrDeletionBranch() {
        val script = RemoteProcessLease.reapAllInDirectory("/unused-native-lease-fixture", excludeNativePtyLeases = true)
        val loop = script.substringAfter("for f in ")
        val skip = loop.indexOf("native-terminal-*.pid|agent-native-pty-*.pid")
        assertTrue(skip >= 0)
        assertTrue(skip < loop.indexOf("[ -L \"\$f\" ] &&"))
        assertTrue(skip < loop.indexOf("line=\$(cat"))
        assertTrue(skip < loop.indexOf("kill_token_processes"))
        assertTrue(loop.contains("unresolved_native=\$((unresolved_native + 1)); continue"))
        assertTrue(loop.contains(NativePtyCleanupReceipt.LEGACY_UNRESOLVED_PREFIX))
        assertTrue(loop.contains("cleanup_status=\$?"))
        assertTrue(loop.contains("exit \"\$cleanup_status\""))
        val unchangedRemotePolicy = RemoteProcessLease.reapAllInDirectory("/unused-remote-fixture")
        assertFalse(unchangedRemotePolicy.contains("native-terminal-*.pid"))
        assertFalse(unchangedRemotePolicy.contains(NativePtyCleanupReceipt.LEGACY_UNRESOLVED_PREFIX))
    }
}

package com.baystudio.droide.core

import android.app.ApplicationExitInfo
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class StartupRecoveryPolicyTest {
    @Test fun unreadableRecoveryMarkerDoesNotTurnAbnormalRestartIntoACrashLoop() {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            check(method.name == "getString")
            throw ClassCastException("Previous marker has a different preference type")
        } as SharedPreferences
        val consumed = StartupRecoveryPolicy.readConsumedExitIdentity(preferences)
        assertNull(consumed)
        val previous = PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_LOW_MEMORY)
        val policy = StartupRecoveryPolicy.decide(previous, consumed)
        assertTrue(policy.deferEditorReopen)
        assertFalse(policy.allowAutomaticTerminalStart)
        assertFalse(policy.clearTerminalRevive)
        assertNull(StartupRecoveryPolicy.readConsumedExitIdentity(null))
    }

    @Test fun everyReportedAbnormalExitDefersEditorAndAutomaticTerminalStartWithoutDeletingRecovery() {
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)) {
            val policy = StartupRecoveryPolicy.decide(PreviousProcessExit(1_000, 101, reason), null)
            assertTrue("reason=$reason", policy.deferEditorReopen)
            assertFalse(policy.allowAutomaticTerminalStart)
            assertFalse(policy.allowTerminalRevive)
            assertFalse(policy.clearTerminalRevive)
            assertEquals("1000:101:$reason", policy.recoveryGeneration)
            assertEquals("", policy.recoveredEditorPath("unsaved.kt", listOf("README.md", "unsaved.kt")))
        }
    }

    @Test fun consumedCrashDoesNotPermanentlyDisableNormalRestorationButANewCrashGuardsAgain() {
        val previous = PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_CRASH_NATIVE)
        assertTrue(StartupRecoveryPolicy.decide(previous, null).deferEditorReopen)
        assertEquals(StartupRecoveryPolicy.NORMAL, StartupRecoveryPolicy.decide(previous, previous.identity))
        val later = PreviousProcessExit(2_000, 202, ApplicationExitInfo.REASON_CRASH_NATIVE)
        assertTrue(StartupRecoveryPolicy.decide(later, previous.identity).deferEditorReopen)
        val healthyExit = PreviousProcessExit(3_000, 303, ApplicationExitInfo.REASON_EXIT_SELF)
        assertEquals(StartupRecoveryPolicy.NORMAL, StartupRecoveryPolicy.decide(healthyExit, later.identity))
    }

    @Test fun distinctProcessAtSameTimestampIsNotMistakenForTheConsumedExit() {
        val previous = PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_CRASH)
        val next = PreviousProcessExit(1_000, 202, ApplicationExitInfo.REASON_CRASH)
        assertTrue(StartupRecoveryPolicy.decide(next, previous.identity).deferEditorReopen)
    }

    @Test fun consecutiveAbnormalRestartsHaveDistinctSavedStateKeysEvenWhenBothDeferEditor() {
        val firstExit = PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_CRASH_NATIVE)
        val secondExit = PreviousProcessExit(2_000, 202, ApplicationExitInfo.REASON_CRASH_NATIVE)
        val first = StartupRecoveryPolicy.decide(firstExit, null)
        val second = StartupRecoveryPolicy.decide(secondExit, firstExit.identity)
        assertEquals(first.deferEditorReopen, second.deferEditorReopen)
        assertNotNull(first.recoveryGeneration)
        assertNotNull(second.recoveryGeneration)
        assertNotEquals(first.recoveryGeneration, second.recoveryGeneration)
        // Android timestamps need not be unique across distinct exited processes.
        val sameTime = StartupRecoveryPolicy.decide(secondExit.copy(timestamp = firstExit.timestamp), firstExit.identity)
        assertNotEquals(first.recoveryGeneration, sameTime.recoveryGeneration)
    }

    @Test fun sameProcessDecisionKeepsItsGenerationAndConsumedOrNormalHistoryHasNone() {
        val exit = PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_CRASH_NATIVE)
        val processDecision = StartupRecoveryPolicy.decide(exit, null)

        assertEquals(processDecision.recoveryGeneration, StartupRecoveryPolicy.decide(exit, null).recoveryGeneration)
        assertNull(StartupRecoveryPolicy.decide(exit, exit.identity).recoveryGeneration)
        assertNull(StartupRecoveryPolicy.decide(null, null).recoveryGeneration)
        assertNull(StartupRecoveryPolicy.decide(exit.copy(reason = ApplicationExitInfo.REASON_EXIT_SELF), null).recoveryGeneration)
        assertNull(StartupRecoveryPolicy.decide(exit.copy(reason = ApplicationExitInfo.REASON_USER_REQUESTED), null).recoveryGeneration)
    }

    @Test fun deliberateForceStopRetainsItsExistingReviveCleanupWithoutInventingANativeCrash() {
        val policy = StartupRecoveryPolicy.decide(PreviousProcessExit(1_000, 101, ApplicationExitInfo.REASON_USER_REQUESTED), null)
        assertFalse(policy.deferEditorReopen)
        assertTrue(policy.allowAutomaticTerminalStart)
        assertFalse(policy.allowTerminalRevive)
        assertTrue(policy.clearTerminalRevive)
    }

    @Test fun unavailableUnknownOrInvalidExitHistoryIsNotProofOfACrash() {
        assertEquals(StartupRecoveryPolicy.NORMAL, StartupRecoveryPolicy.decide(null, null))
        assertEquals(StartupRecoveryPolicy.NORMAL, StartupRecoveryPolicy.decide(
            PreviousProcessExit(0, 101, ApplicationExitInfo.REASON_CRASH), null))
        for (reason in listOf(ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED)) {
            assertEquals(StartupRecoveryPolicy.NORMAL, StartupRecoveryPolicy.decide(PreviousProcessExit(1_000, 101, reason), null))
        }
    }

    @Test fun normalRestorationKeepsTheActiveFileAndAnExplicitlyBlankEditorStaysDeferred() {
        val policy = StartupRecoveryPolicy.NORMAL
        assertEquals("second.kt", policy.recoveredEditorPath("second.kt", listOf("first.kt", "second.kt")))
        assertEquals("first.kt", policy.recoveredEditorPath("deleted.kt", listOf("first.kt", "second.kt")))
        assertEquals("", policy.recoveredEditorPath("", listOf("unsaved.kt")))
        assertEquals("", policy.recoveredEditorPath("deleted.kt", emptyList()))
    }
}

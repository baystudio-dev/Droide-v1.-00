package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class AccessoryModifierPolicyR73Test {
    private val ctrl = AccessoryModifier.CTRL
    private val alt = AccessoryModifier.ALT
    private val shift = AccessoryModifier.SHIFT
    private val off = AccessoryModifierMode.OFF
    private val armed = AccessoryModifierMode.ARMED
    private val locked = AccessoryModifierMode.LOCKED
    private fun AccessoryModifierPolicy.click(key: AccessoryModifier, at: Long) = tapAt(key, at, 40, 300)

    @Test fun firstReleaseArmsImmediatelyForTheNextKeyWithoutAClockAdvance() {
        for (key in AccessoryModifier.entries) {
            val policy = AccessoryModifierPolicy()
            policy.click(key, 1_000)
            val delivered = policy.snapshot.mode(key)
            policy.consumeArmed()
            assertEquals(armed, delivered)
            assertEquals(off, policy.snapshot.mode(key))
        }
    }

    @Test fun rapidModifierKeyModifierKeyKeepsBothChordsIndependent() {
        val policy = AccessoryModifierPolicy()
        repeat(50) { index ->
            policy.click(ctrl, 1_000 + index * 60L)
            assertEquals(armed, policy.snapshot.ctrl)
            policy.consumeArmed()
            assertEquals(off, policy.snapshot.ctrl)
        }
    }

    @Test fun secondReleaseWithinThePlatformWindowLocksAndSurvivesConsumption() {
        for (delta in listOf(40L, 150L, 300L)) {
            val policy = AccessoryModifierPolicy()
            policy.click(ctrl, 1_000)
            policy.click(ctrl, 1_000 + delta)
            assertEquals(locked, policy.snapshot.ctrl)
            repeat(20) { policy.consumeArmed(); assertEquals(locked, policy.snapshot.ctrl) }
        }
    }

    @Test fun lockedFirstTapUnlocksImmediatelyAndNextTapArmsAgain() {
        val policy = AccessoryModifierPolicy()
        policy.click(ctrl, 1_000); policy.click(ctrl, 1_100)
        policy.click(ctrl, 1_200)
        assertEquals(off, policy.snapshot.ctrl)
        policy.click(ctrl, 1_300)
        assertEquals(armed, policy.snapshot.ctrl)
        policy.click(ctrl, 1_400)
        assertEquals(locked, policy.snapshot.ctrl)
    }

    @Test fun otherModifierInterventionDoesNotLockTheFirstModifier() {
        val policy = AccessoryModifierPolicy()
        policy.click(ctrl, 1_000); policy.click(alt, 1_050); policy.click(ctrl, 1_100)
        assertEquals(off, policy.snapshot.ctrl)
        assertEquals(armed, policy.snapshot.alt)
    }

    @Test fun clearInvalidatesAllModifiersAndPendingDoubleTapHistory() {
        val policy = AccessoryModifierPolicy()
        policy.click(ctrl, 1_000); policy.click(ctrl, 1_100)
        policy.click(alt, 1_150); policy.tap(shift)
        policy.clear()
        assertEquals(AccessoryModifierSnapshot(), policy.snapshot)
        policy.click(ctrl, 1_200)
        assertEquals(armed, policy.snapshot.ctrl)
    }

    @Test fun clearWhenAlreadyOffAlsoCannotReviveAnOldSequence() {
        val policy = AccessoryModifierPolicy()
        policy.click(ctrl, 1_000); policy.consumeArmed(); policy.clear()
        policy.click(ctrl, 1_100)
        assertEquals(armed, policy.snapshot.ctrl)
    }

    @Test fun tooFastLateOrClockRollbackSecondTapNeverLocks() {
        for (delta in listOf(-100L, 0L, 39L, 301L, 600L)) {
            val policy = AccessoryModifierPolicy()
            policy.click(ctrl, 1_000); policy.click(ctrl, 1_000 + delta)
            assertEquals("delta=$delta", off, policy.snapshot.ctrl)
        }
    }

    @Test fun longHeldSecondReleaseOutsideTheWindowDoesNotLockOrScheduleWork() {
        val policy = AccessoryModifierPolicy()
        policy.click(ctrl, 1_000); policy.click(ctrl, 1_700)
        assertEquals(off, policy.snapshot.ctrl)
        policy.click(ctrl, 1_800)
        assertEquals(armed, policy.snapshot.ctrl)
    }

    @Test fun consumingAChordClearsArmedModifiersTogetherButKeepsLockedOnes() {
        val policy = AccessoryModifierPolicy()
        policy.lock(ctrl); policy.tap(alt); policy.tap(shift)
        policy.consumeArmed()
        assertEquals(AccessoryModifierSnapshot(ctrl = locked), policy.snapshot)
    }

    @Test fun snapshotsRemainImmutableAcrossLaterTransitions() {
        val policy = AccessoryModifierPolicy()
        policy.click(ctrl, 1_000)
        val first = policy.snapshot
        policy.click(ctrl, 1_100); policy.consumeArmed(); policy.clear()
        assertEquals(armed, first.ctrl)
        assertEquals(off, policy.snapshot.ctrl)
    }

    @Test fun explicitToggleAndLockOperationsKeepTheirExistingContract() {
        val policy = AccessoryModifierPolicy()
        for (key in AccessoryModifier.entries) {
            policy.tap(key); assertEquals(armed, policy.snapshot.mode(key))
            policy.tap(key); assertEquals(off, policy.snapshot.mode(key))
            policy.lock(key); assertEquals(locked, policy.snapshot.mode(key))
            policy.lock(key); assertEquals(off, policy.snapshot.mode(key))
        }
    }
}

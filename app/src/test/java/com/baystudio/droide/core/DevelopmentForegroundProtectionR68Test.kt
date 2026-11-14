package com.baystudio.droide.core

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE, application = Application::class)
class DevelopmentForegroundProtectionR68Test {
    @Before fun setup() { DevelopmentForegroundSessionRegistry.closeForExit(); DevelopmentForegroundSessionRegistry.openForEpoch(UUID.randomUUID().toString()) }
    @After fun cleanup() { DevelopmentForegroundSessionRegistry.closeForExit(); DevelopmentForegroundSessionRegistry.openForEpoch(UUID.randomUUID().toString()) }
    private val token = "human-command-r68-0123456789"
    @Test fun admissionIsPendingUntilAcquiredAndAckIsOnce() {
        var acquired = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, true, {}, { acquired++ }, {})
        assertEquals(0, acquired)
        DevelopmentForegroundSessionRegistry.acquired(token)
        DevelopmentForegroundSessionRegistry.acquired(token)
        assertEquals(1, acquired)
    }
    @Test fun retiredTokenCannotReceiveAcquired() {
        var acquired = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, true, {}, { acquired++ }, {})
        DevelopmentForegroundSessionRegistry.remove(token)
        DevelopmentForegroundSessionRegistry.acquired(token)
        assertEquals(0, acquired)
    }
    @Test fun humanProtectionLossDoesNotCancelCommand() {
        var canceled = 0; var lost = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, true, { canceled++ }, {}, { lost++ })
        assertTrue(DevelopmentForegroundSessionRegistry.protectionLost(token, "Unavailable"))
        assertFalse(DevelopmentForegroundSessionRegistry.protectionLost(token, "Again"))
        assertEquals(1, lost); assertEquals(0, canceled)
        assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(token))
    }
    @Test fun explicitStopStillCancelsHumanCommand() {
        var canceled = 0; var lost = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, true, { canceled++ }, {}, { lost++ })
        assertTrue(DevelopmentForegroundSessionRegistry.cancel(token))
        assertEquals(1, canceled); assertEquals(0, lost)
    }
    @Test fun exitStillCancelsPendingAndAcquiredHumans() {
        var canceled = 0; var lost = 0
        for (n in 0..1) DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token+n, true, { canceled++ }, {}, { lost++ })
        DevelopmentForegroundSessionRegistry.acquired(token+1)
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        assertEquals(2, canceled); assertEquals(0, lost)
    }
    @Test fun managedWorkRetainsFailClosedCancellation() {
        var canceled = 0
        DevelopmentForegroundSessionRegistry.register(token) { canceled++ }
        DevelopmentForegroundSessionRegistry.protectionLost(token, "Unavailable")
        assertEquals(1, canceled)
    }
    @Test fun mixedOwnerLossDoesNotApplyHumanPolicyToManagedWork() {
        var humanCanceled = 0; var humanLost = 0; var managedCanceled = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, true, { humanCanceled++ }, {}, { humanLost++ })
        val managed = "managed-command-r68-0123456789"
        DevelopmentForegroundSessionRegistry.register(managed) { managedCanceled++ }
        DevelopmentForegroundSessionRegistry.protectionLostAll(listOf(token, managed, token), "Unavailable")
        assertEquals(0, humanCanceled); assertEquals(1, humanLost); assertEquals(1, managedCanceled)
    }
    @Test fun observerCanRetireItselfWithoutReactivation() {
        var acquired = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, true, {}, {
            acquired++; DevelopmentForegroundSessionRegistry.remove(token)
        }, {})
        DevelopmentForegroundSessionRegistry.acquired(token)
        assertEquals(1, acquired); assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(token))
    }
    @Test fun observerFailureCannotCancelHumanWorkOrBlockAnotherOwner() {
        var canceled = 0; var second = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, true, { canceled++ }, {}, { error("observer failure") })
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token+"2", true, {}, {}, { second++ })
        DevelopmentForegroundSessionRegistry.protectionLostAll(listOf(token, token+"2"), "Unavailable")
        assertEquals(0, canceled); assertEquals(1, second)
    }
}

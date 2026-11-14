package com.baystudio.droide.core

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DevelopmentForegroundRegistryExitR64Test {
    @Before fun prepare() {
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.openForEpoch(UUID.randomUUID().toString())
    }
    @After fun restore() {
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.openForEpoch(UUID.randomUUID().toString())
    }
    @Test fun closesRegisteredButNotDeliveredOperationExactlyOnce() {
        var canceled = 0
        DevelopmentForegroundSessionRegistry.register("not-delivered-operation-01234") { canceled++ }
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        assertEquals(1, canceled)
        assertFalse(DevelopmentForegroundSessionRegistry.isRegistered("not-delivered-operation-01234"))
    }
    @Test fun callbackFailureDoesNotHideFailureOrPreventOtherCancellation() {
        var canceled = 0
        DevelopmentForegroundSessionRegistry.register("first-operation-0123456789") { error("first failure") }
        DevelopmentForegroundSessionRegistry.register("second-operation-0123456789") { canceled++; error("second failure") }
        val failure = DevelopmentForegroundSessionRegistry.closeForExit().exceptionOrNull()
        assertEquals("first failure", failure?.message)
        assertEquals("second failure", failure?.suppressed?.single()?.message)
        assertEquals(1, canceled)
    }
    @Test fun closedRegistryRejectsLateRegistrationUntilExplicitNewEpoch() {
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        assertThrows(IllegalStateException::class.java) { DevelopmentForegroundSessionRegistry.register("late-operation-0123456789") {} }
        DevelopmentForegroundSessionRegistry.openForEpoch("next")
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy("new-operation-0123456789", false) {}
        assertFalse(DevelopmentForegroundSessionRegistry.cpuWork("new-operation-0123456789"))
    }
    @Test fun newEpochCannotSilentlyAbandonExistingOwner() {
        DevelopmentForegroundSessionRegistry.register("active-operation-0123456789") {}
        assertThrows(IllegalStateException::class.java) { DevelopmentForegroundSessionRegistry.openForEpoch("replacement") }
        assertTrue(DevelopmentForegroundSessionRegistry.isRegistered("active-operation-0123456789"))
    }
}

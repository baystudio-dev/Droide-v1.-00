package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class ApplicationWorkAdmissionFixtureTest {
    @Test fun restoresPriorClosedStateAfterSuccessfulBody() = verifyRestoration(failBody = false)

    @Test fun restoresPriorClosedStateAfterFailingBody() = verifyRestoration(failBody = true)

    private fun verifyRestoration(failBody: Boolean) {
        val ownerField = ApplicationWorkAdmission::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val stateField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
        val priorOwner = Any()
        val priorEpoch = "b".repeat(32)
        val priorState = ApplicationWorkAdmissionState(
            read = { ApplicationWorkAdmissionState.Snapshot(priorEpoch, false) },
            write = { error("The fixture must not write through the prior state") },
        ).apply { initialize() }
        val previous = synchronized(ApplicationWorkAdmission) {
            val saved = ownerField.get(null) to stateField.get(null)
            ownerField.set(null, priorOwner)
            stateField.set(null, priorState)
            saved
        }
        try {
            val expectedFailure = AssertionError("Synthetic test body failure")
            val body = object : Statement() {
                override fun evaluate() {
                    assertNotSame(priorOwner, ownerField.get(null))
                    assertNotSame(priorState, stateField.get(null))
                    assertTrue(ApplicationWorkAdmission.isOpen)
                    assertEquals("a".repeat(32), ApplicationWorkAdmission.requireOpen())
                    ApplicationWorkAdmission.closeForExit()
                    assertFalse(ApplicationWorkAdmission.isOpen)
                    if (failBody) throw expectedFailure
                }
            }
            val statement = ApplicationWorkAdmissionFixture().apply(body,
                Description.createTestDescription(javaClass, "fixture restoration"))
            if (failBody) {
                assertSame(expectedFailure, assertThrows(AssertionError::class.java) { statement.evaluate() })
            } else {
                statement.evaluate()
            }
            synchronized(ApplicationWorkAdmission) {
                assertSame(priorOwner, ownerField.get(null))
                assertSame(priorState, stateField.get(null))
                assertEquals(priorEpoch, ApplicationWorkAdmission.currentEpoch)
                assertFalse(ApplicationWorkAdmission.isOpen)
                assertThrows(IllegalStateException::class.java) { ApplicationWorkAdmission.requireOpen() }
            }
        } finally {
            synchronized(ApplicationWorkAdmission) {
                ownerField.set(null, previous.first)
                stateField.set(null, previous.second)
            }
        }
    }
}

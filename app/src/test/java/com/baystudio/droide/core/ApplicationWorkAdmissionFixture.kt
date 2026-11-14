package com.baystudio.droide.core

import org.junit.rules.ExternalResource

/** Isolated open state for workflow tests; production admission checks remain active. */
internal class ApplicationWorkAdmissionFixture : ExternalResource() {
    private val ownerField = ApplicationWorkAdmission::class.java.getDeclaredField("owner").apply { isAccessible = true }
    private val stateField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
    private var previousOwner: Any? = null
    private var previousState: Any? = null
    private var captured = false

    override fun before() {
        synchronized(ApplicationWorkAdmission) {
            previousOwner = ownerField.get(null)
            previousState = stateField.get(null)
            captured = true
            try {
                val state = ApplicationWorkAdmissionState(
                    read = { ApplicationWorkAdmissionState.Snapshot("a".repeat(32), true) },
                    write = {},
                )
                state.initialize()
                ownerField.set(null, Any())
                stateField.set(null, state)
            } catch (failure: Throwable) {
                restore()
                throw failure
            }
        }
    }

    override fun after() = restore()

    private fun restore() {
        synchronized(ApplicationWorkAdmission) {
            if (!captured) return
            ownerField.set(null, previousOwner)
            stateField.set(null, previousState)
            captured = false
        }
    }
}

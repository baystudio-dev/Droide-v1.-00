package com.baystudio.droide.core

import java.util.UUID

/** Pure persistence contract, independently testable without an Android process. */
internal class ApplicationWorkAdmissionState(
    private val read: () -> Snapshot?,
    private val write: (Snapshot) -> Unit,
    private val newEpoch: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    data class Snapshot(val epoch: String, val open: Boolean)
    private var value = Snapshot(newEpoch(), false)
    private var initialized = false
    private var closePersisted = false

    val isOpen: Boolean @Synchronized get() = initialized && value.open
    val currentEpoch: String @Synchronized get() = value.epoch

    @Synchronized fun initialize() {
        if (initialized) return
        try {
            val saved = read()
            if (saved != null) {
                require(saved.epoch.matches(Regex("[0-9a-f]{32}"))) { "Invalid persisted application epoch" }
                value = saved
                closePersisted = !saved.open
            }

            initialized = true
        } catch (failure: Exception) {
            value = value.copy(open = false)
            initialized = true
            throw IllegalStateException("Application work state could not be read; work remains stopped", failure)
        }
    }

    @Synchronized fun requireOpen(): String {
        check(isOpen) { "Droide is stopped; open the app to start a new session" }
        return value.epoch
    }

    @Synchronized fun isCurrentOpen(epoch: String): Boolean = isOpen && value.epoch == epoch

    @Synchronized fun closeForExit(): String {
        check(initialized) { "Application work admission has not been initialized" }
        value = value.copy(open = false)
        if (!closePersisted) {
            write(value)
            closePersisted = true
        }
        return value.epoch
    }

    @Synchronized fun reopenFromUser(): String {
        check(initialized) { "Application work admission has not been initialized" }
        // Keep closed in memory if persistence fails; never resurrect the previous open value.
        value = value.copy(open = false)
        closePersisted = false
        val next = Snapshot(newEpoch(), true)
        require(next.epoch.matches(Regex("[0-9a-f]{32}")) && next.epoch != value.epoch) { "Invalid new application epoch" }
        write(next)
        value = next
        return next.epoch
    }
}

package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Admission closes independently of history publication, which must survive final cancellation. */
internal class ExitOperationFence(
    private val lock: Any = Any(),
    private val applicationOpen: () -> Boolean = { ApplicationWorkAdmission.isOpen },
) {
    @Volatile private var exiting = false
    private val operations = LinkedHashMap<Job, Int>()
    val isOpen: Boolean get() = !exiting && applicationOpen()
    val isExiting: Boolean get() = exiting

    fun requireOpen() {
        if (!isOpen) throw CancellationException("The application is exiting; new Agent work is paused")
    }

    fun track(job: Job): AutoCloseable = synchronized(lock) {
        requireOpen()
        operations[job] = (operations[job] ?: 0) + 1
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        AutoCloseable { if (released.compareAndSet(false, true)) synchronized(lock) {
            val count = operations[job] ?: return@synchronized
            if (count == 1) operations.remove(job) else operations[job] = count - 1
        } }
    }

    /** Retain jobs until their own finally blocks retire them; retries still await cleanup. */
    fun beginExit(): List<Job> = synchronized(lock) {
        exiting = true
        operations.keys.toList()
    }
}

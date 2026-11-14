package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Exact screen/action jobs, retained through disposal until their cleanup actually completes. */
internal class ExitUiWorkOwner {
    private val lock = Any()
    private val jobs = linkedSetOf<Job>()
    private var closed = false
    fun register(job: Job) {
        val cancel = synchronized(lock) { jobs.add(job); closed }
        job.invokeOnCompletion { synchronized(lock) { jobs.remove(job) } }
        if (cancel) job.cancel(CancellationException("Application Exit"))
    }
    suspend fun stopAndAwait() {
        synchronized(lock) { closed = true; jobs.toList() }.forEach { it.cancel(CancellationException("Application Exit")) }
        while (true) {
            val pending = synchronized(lock) { jobs.toList() }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }
}

package com.baystudio.droide.core

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks the owned reaper helper; this does not certify every descendant or procfs lookup. */
internal object LocalLeaseCleanup {
    const val TIMEOUT_MS = 3_000L

    fun await(process: Process, timeoutMs: Long = TIMEOUT_MS) {
        var interrupted = false
        var primaryFailure: Throwable? = null
        try {
            require(timeoutMs in 1..TIMEOUT_MS) { "Invalid local lease cleanup timeout" }
            val finished = try {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                interrupted = true
                throw IOException("Local process lease cleanup was interrupted; completion could not be verified", failure)
            }
            if (!finished) {
                throw IOException("Local process lease cleanup timed out after ${timeoutMs}ms; completion could not be verified")
            }
            val exit = process.exitValue()
            if (exit != 0) {
                throw IOException("Local process lease cleanup failed (exit $exit); completion could not be verified")
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // A timed-out helper must not outlive its owner. Keep the existing bounded
            // termination/reaping policy, without repeating a lease scan or deleting it.
            try { LocalProcessSupervisor.terminate(process) }
            catch (cleanup: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanup
                if (original !== cleanup) original.addSuppressed(cleanup)
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
}

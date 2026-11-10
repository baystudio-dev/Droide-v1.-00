package com.baystudio.droide.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One callback invocation owned by its capture/host. */

internal class OwnedLeaseCleanupAttempt(private val cleanup: (() -> Unit)?) {
    companion object { internal const val OBSERVATION_MS = 6_000L }
    private val done = CountDownLatch(1)
    private var started = false
    private var startNanos = 0L
    @Volatile private var failure: Throwable? = null

    fun observe() {
        val start = synchronized(this) {
            if (!started) {
                started = true
                startNanos = System.nanoTime()
                if (cleanup == null) done.countDown()
                else try {
                    // A running thread retains this attempt and callback even if the caller has already reported
                    // uncertainty to its custody owner.
                    Thread({
                        try { cleanup.invoke() }
                        catch (error: Throwable) { failure = error }
                        finally { done.countDown() }
                    }, "droide-owned-lease-cleanup").apply { isDaemon = true }.start()
                } catch (error: Throwable) { failure = error; done.countDown() }
            }
            startNanos
        }
        val remaining = TimeUnit.MILLISECONDS.toNanos(OBSERVATION_MS) - (System.nanoTime() - start)
        val finished = try { done.await(maxOf(0L, remaining), TimeUnit.NANOSECONDS) }
        catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Lease cleanup observation was interrupted; its single attempt remains retained", error)
        }
        check(finished) { "Lease cleanup observation timed out; its single attempt remains retained" }
        failure?.let { throw it }
    }
}

package com.baystudio.droide.core

import java.io.IOException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Strict direct-child/pipe teardown only, never a descendant-quiescence receipt.
 * A blocking Process method or pipe close runs in a retained worker. Observation
 * timeout/interruption never abandons or duplicates that exact handle's attempt.
 */
internal object OwnedProcessTermination {
    internal const val OBSERVATION_MS = 2_000L
    private val collected = ReferenceQueue<Process>()
    private val attempts = HashMap<IdentityKey, Attempt>()

    private class IdentityKey(process: Process, queue: ReferenceQueue<Process>? = null) :
        WeakReference<Process>(process, queue) {
        private val identityHash = System.identityHashCode(process)
        override fun hashCode() = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is IdentityKey && get()?.let { it === other.get() } == true
    }

    /** Dispatch exact-handle teardown without observing or running Process methods on the caller. */
    fun request(process: Process) { attemptFor(process) }

    fun terminate(process: Process) { attemptFor(process).observe() }

    private fun attemptFor(process: Process): Attempt {
        var launch: Attempt? = null
        val attempt = synchronized(attempts) {
            while (true) {
                val dead = collected.poll() ?: break
                attempts.remove(dead)
            }
            attempts[IdentityKey(process)] ?: Attempt().also {
                attempts[IdentityKey(process, collected)] = it
                launch = it
            }
        }
        launch?.start(process)
        // Keep successful completion for this exact live handle, so late observers
        // never close its pipes twice. Weak keys release successful handles when
        // their owners disappear; failed attempts explicitly retain their handle.
        return attempt
    }

    private class Attempt {
        private val started = System.nanoTime()
        private val done = CountDownLatch(1)
        @Volatile private var failure: Throwable? = null
        @Suppress("unused") private var failedProcess: Process? = null

        fun start(process: Process) {
            try {
                Thread({
                    try { reapAndClose(process) }
                    catch (error: Throwable) { failedProcess = process; failure = error }
                    finally { done.countDown() }
                }, "droide-owned-process-cleanup").apply { isDaemon = true }.start()
            } catch (error: Throwable) {
                failedProcess = process
                failure = error
                done.countDown()
            }
        }

        fun observe() {
            val remaining = TimeUnit.MILLISECONDS.toNanos(OBSERVATION_MS) - (System.nanoTime() - started)
            val finished = try { done.await(maxOf(0L, remaining), TimeUnit.NANOSECONDS) }
            catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Exact process cleanup observation was interrupted; its attempt remains retained", error)
            }
            check(finished) { "Exact process cleanup observation timed out; its attempt remains retained" }
            failure?.let { throw it }
        }
    }

    private fun reapAndClose(process: Process) {
        var failure: Throwable? = null
        fun retain(error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) failure!!.addSuppressed(error)
        }
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { retain(error) } }
        fun alive(): Boolean = try { process.isAlive } catch (error: Throwable) { retain(error); true }

        if (alive()) attempt { process.destroy() }
        var exited = false
        attempt { exited = process.waitFor(200, TimeUnit.MILLISECONDS) }
        if (!exited && alive()) attempt { process.destroyForcibly() }
        if (!exited) attempt { exited = process.waitFor(800, TimeUnit.MILLISECONDS) }
        if (!exited) retain(IOException("Exact process did not confirm exit after termination"))

        var reaped = false
        if (exited) attempt {
            process.waitFor()
            check(!process.isAlive) { "Exact process still reports live after wait" }
            reaped = true
        }
        // Never touch potentially blocked pipes before exact child exit is known.
        // A blocked close keeps this worker retained; the observer stays bounded.
        if (reaped) {
            attempt { process.outputStream.close() }
            attempt { process.inputStream.close() }
            attempt { process.errorStream.close() }
        }
        failure?.let { throw it }
    }
}

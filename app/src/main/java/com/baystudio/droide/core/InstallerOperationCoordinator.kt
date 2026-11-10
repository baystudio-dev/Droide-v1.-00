package com.baystudio.droide.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One owner for mutations, their cleanup, cancellation and coherent catalog reads. */
internal class InstallerOperationCoordinator {
    private val mutex = Mutex()
    private val lifecycleLock = Any()
    @Volatile private var activeJob: Job? = null
    @Volatile private var snapshotJob: Job? = null
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    @Volatile var active: Boolean = false
        private set

    private class Owner(val coordinator: InstallerOperationCoordinator) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Owner>
    }

    suspend fun ownsCurrentOperation(): Boolean = currentCoroutineContext()[Owner]?.coordinator === this
    fun requireOpen() { check(!closed) { "Extension manager is closed" } }
    fun publish(allowWhenClosed: Boolean = false, block: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (closed && !allowWhenClosed) false else { block(); true }
    }

    suspend fun <T> operation(
        onStart: () -> Unit = {},
        onFinish: (Throwable?) -> Unit = {},
        block: suspend () -> T,
    ): T {
        if (ownsCurrentOperation()) {
            currentCoroutineContext().ensureActive()
            return block()
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val job = currentCoroutineContext()[Job]
                synchronized(lifecycleLock) {
                    requireOpen()
                    activeJob = job
                    active = true
                }
                var failure: Throwable? = null
                try {
                    onStart()
                    withContext(Owner(this@InstallerOperationCoordinator)) { block() }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    synchronized(lifecycleLock) {
                        activeJob = null
                        active = false
                    }
                    // Publish the terminal state after all nested transaction cleanup, before the next owner enters.
                    try { onFinish(failure) } catch (publicationFailure: Throwable) {
                        val original = failure
                        if (original != null) {
                            // Coroutine debug recovery unwraps exception copies at a later dispatcher boundary. Retain the
                            // publication error on both the copy and its matching original.
                            val seen = mutableSetOf<Throwable>()
                            var retained: Throwable? = original
                            while (retained != null && seen.size < 16 && seen.add(retained) &&
                                retained.javaClass == original.javaClass && retained.message == original.message) {
                                if (retained !== publicationFailure) retained.addSuppressed(publicationFailure)
                                retained = retained.cause
                            }
                        } else throw publicationFailure
                    }
                }
            }
        }
    }

    suspend fun <T> snapshot(block: suspend () -> T): T {
        if (ownsCurrentOperation()) return block()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val job = currentCoroutineContext()[Job]
                synchronized(lifecycleLock) { requireOpen(); snapshotJob = job }
                try { block() } finally { synchronized(lifecycleLock) { snapshotJob = null } }
            }
        }
    }

    fun cancel() { activeJob?.cancel(CancellationException("Canceled by user")) }

    suspend fun closeAndJoin() {
        close()

        mutex.withLock { }
    }

    fun close() {
        val jobs = synchronized(lifecycleLock) {
            closed = true
            listOfNotNull(activeJob, snapshotJob)
        }
        jobs.forEach { it.cancel(CancellationException("Extension manager closed")) }
    }
}

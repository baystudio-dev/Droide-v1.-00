package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Shared by every research service, including short-lived unbound tool services. */
internal object AgentWebWork {
    const val MAX_TRANSFERS = 3
    private val transfers = Semaphore(MAX_TRANSFERS)
    private val parser = Mutex()
    private val binary = Mutex()

    // Keep the slot through extraction: waiting parsers must not accumulate downloaded bodies.
    suspend fun <T> transfer(block: suspend () -> T): T = transfers.withPermit {
        currentCoroutineContext().ensureActive()
        block().also { currentCoroutineContext().ensureActive() }
    }

    suspend fun <T> parse(block: () -> T): T = parser.withLock {
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            block().also { currentCoroutineContext().ensureActive() }
        }
    }

    /** Existing single-source image/PDF work uses one lane, including across service instances. */
    suspend fun <T> single(block: suspend () -> T): T = binary.withLock {
        currentCoroutineContext().ensureActive()
        block().also { currentCoroutineContext().ensureActive() }
    }
}

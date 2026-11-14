package com.baystudio.droide.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent of Main, coroutine cancellation and owner locks. Retries cannot extend this budget. */
internal class ApplicationExitDeadline(
    private val prepareClose: () -> Unit,
    private val terminate: () -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit) = ::scheduleExitDeadline,
) {
    private val finished = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var cancelPreparation: (() -> Unit)? = null
    private var cancelTermination: (() -> Unit)? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        cancelTermination = schedule(TIMEOUT_MS) { complete() }
        cancelPreparation = schedule(TIMEOUT_MS - 500) { if (!finished.get()) prepareClose() }
    }

    fun complete() {
        if (finished.compareAndSet(false, true)) {
            cancelPreparation?.invoke()
            cancelTermination?.invoke()
            terminate()
        }
    }

    fun cancel() {
        finished.set(true)
        cancelPreparation?.invoke()
        cancelTermination?.invoke()
    }

    companion object { const val TIMEOUT_MS = 15_000L }
}

private val exitDeadlineExecutor = Executors.newScheduledThreadPool(2) { task ->
    Thread(task, "droide-exit-deadline").apply { isDaemon = true }
}

private fun scheduleExitDeadline(delayMs: Long, action: () -> Unit): () -> Unit {
    val future = exitDeadlineExecutor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
    return { future.cancel(false); Unit }
}

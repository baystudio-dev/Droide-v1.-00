package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Retains bounded PTY input through slow startup, with one writer and no automatic replay. */
internal class TerminalInputRelay<T : Any>(
    scope: CoroutineScope,
    connection: Deferred<T>,
    private val write: (T, ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val input = Channel<ByteArray>(1024)
    private var pending = 0
    private var pendingBytes = 0
    private var closed = false
    private var failureReported = false

    private var deliveryUnconfirmed = false
    private var finalInputFailureReason = -1
    @Volatile private var reportDropped = false
    private val worker = scope.launch(ioDispatcher) {
        try {
            val session = connection.await()
            for (bytes in input) {
                // Close cannot revoke an already admitted write, but denies later ones.
                if (synchronized(this@TerminalInputRelay) { closed }) break
                write(session, bytes)
                synchronized(this@TerminalInputRelay) { pending--; pendingBytes -= bytes.size }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // This worker is already exiting. Do not reference the worker field here: an immediate
            // dispatcher can enter this catch before its assignment.
            reportFailure("Terminal input delivery could not be confirmed: ${error.message?.take(160) ?: error.javaClass.simpleName}. Check the terminal before retrying.", cancelWorker = false)
        } finally { discardQueuedInput() }
    }.also { job ->
        // A cancelled scope can prevent the body (and its finally) from starting at all.
        job.invokeOnCompletion { discardQueuedInput() }
    }

    private fun discardQueuedInput() {
        val dropped = synchronized(this) {
            closed = true
            input.close()
            while (input.tryReceive().isSuccess) { /* discard without replay */ }
            if (pending > 0) deliveryUnconfirmed = true
            // Finalization owns its receipt/notice; cancellation must not call an observer inline before the
            // caller can publish that stable receipt.
            val unconfirmed = if (failureReported || finalInputFailureReason >= 0) 0 else pending
            pending = 0
            pendingBytes = 0
            unconfirmed
        }
        if (reportDropped && dropped > 0) runCatching {
            onError("Terminal closed with $dropped pending or unconfirmed input chunk(s). Check the terminal before retrying; nothing will be replayed.")
        }
    }

    fun send(bytes: ByteArray): Boolean = send(bytes, 0, bytes.size)

    fun send(bytes: ByteArray, offset: Int, count: Int): Boolean {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count) { "Invalid terminal input range" }
        if (count == 0) return true
        val rejection = synchronized(this) {
            // Cancellation rejects input before the worker's finally is scheduled.
            if (!worker.isActive) closed = true
            val rejected = when {
                closed -> "Terminal is closed; input was not sent"
                count > 65_536 -> "Terminal input exceeds 64 KiB; input was not sent"
                pendingBytes + count > 262_144 -> "Terminal input queue exceeds 256 KiB; input was not sent"
                else -> {
                    val copy = bytes.copyOfRange(offset, offset + count)
                    pending++
                    pendingBytes += count
                    if (input.trySend(copy).isSuccess) return true
                    pending--
                    pendingBytes -= count
                    "Terminal input queue is full or closed; input was not sent"
                }
            }
            deliveryUnconfirmed = true
            rejected
        }
        // Observer callbacks never run while holding the admission monitor.
        onError(rejection)
        return false
    }

    /** Linearizes final input admission and freezes its receipt without joining or invoking
     * observers. pending includes the current write until it returns. */
    fun finishInput(): Int {
        val receipt = synchronized(this) {
            closed = true
            if (finalInputFailureReason < 0) {
                finalInputFailureReason = if (deliveryUnconfirmed || failureReported || pending > 0) 5 else 0
            }

            input.close()
            finalInputFailureReason
        }
        // Queue state/receipt is already committed. No observer/cleanup exception is allowed to prevent
        // the completion owner from obtaining that receipt.
        runCatching { worker.cancel(CancellationException("Terminal input finalized")) }
        return receipt
    }

    /** Called after native writer cleanup. Closes admission without waiting or replaying. */
    fun fail(detail: String) = reportFailure(detail, cancelWorker = true)

    private fun reportFailure(detail: String, cancelWorker: Boolean) {
        val report = synchronized(this) {
            deliveryUnconfirmed = true
            closed = true
            val notify = !failureReported
            failureReported = true
            input.close()
            notify
        }
        if (cancelWorker) worker.cancel(CancellationException("Terminal input delivery failed"))
        // Cancellation/closed state is committed before any observer can retry or throw.
        if (report) runCatching { onError(detail) }
    }

    fun close(reportUnsent: Boolean = false) {
        synchronized(this) {
            if (pending > 0) deliveryUnconfirmed = true
            reportDropped = reportDropped || reportUnsent
            closed = true
            input.close()
        }
        worker.cancel(CancellationException("Terminal input relay closed"))
    }
}

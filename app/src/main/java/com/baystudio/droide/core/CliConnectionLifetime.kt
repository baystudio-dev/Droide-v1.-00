package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

/** Unblock a blocking peer read before coroutineScope joins its canceled children. */
internal object CliConnectionLifetime {
    suspend fun serve(
        operation: suspend () -> DroideCliExecutionResult,
        readPeer: () -> Int,
        closePeer: () -> Unit,
        writeResponse: (DroideCliExecutionResult) -> Unit,
    ) = coroutineScope {
        val closed = AtomicBoolean(false)
        fun close() {
            if (closed.compareAndSet(false, true)) runCatching(closePeer)
        }
        // Coroutine cancellation alone cannot interrupt java/native socket I/O.
        // Use a separate IO child so cancellation can also unblock a response write
        // on this coroutine's dispatcher. UNDISPATCHED installs it before any work.
        val cancellationCloser = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        val work = async { operation() }
        val peer = async(Dispatchers.IO) { runCatching { readPeer() }.getOrDefault(-1) }
        try {
            val completed = select<DroideCliExecutionResult?> {
                work.onAwait { it }
                peer.onAwait { null }
            }
            if (completed == null) work.cancel()
            else writeResponse(completed)
        } finally {
            // Socket read/write ignores coroutine cancellation. Close inside this scope,
            // including failed/canceled operations, before either structured child is joined.
            close()
            cancellationCloser.cancel()
            peer.cancel()
            work.cancel()
        }
    }
}

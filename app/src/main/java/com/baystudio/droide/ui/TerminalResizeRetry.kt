package com.baystudio.droide.ui

import com.baystudio.droide.core.TerminalResizeReceipt

/** One next-frame retry per failed request in one view/session lifecycle. No output-triggered retries. */
internal class TerminalResizeRetry(
    private val schedule: (() -> Unit) -> (() -> Unit),
) {
    private data class Key(val generation: Long, val request: Long)
    private var owner: Any? = null
    private var epoch = 0L
    private var used: Key? = null
    private var cancel: (() -> Unit)? = null

    fun lifecycle(newOwner: Any?) {
        cancel?.invoke(); cancel = null
        epoch++
        owner = newOwner
        used = null
    }

    fun suspendPresentation() {
        cancel?.invoke(); cancel = null
        epoch++ // Retain used budget; hidden callbacks can never revive.
    }

    fun offer(currentOwner: Any, receipt: TerminalResizeReceipt?, eligible: () -> Boolean,
              stillLatest: () -> Boolean, retry: () -> Unit) {
        if (owner !== currentOwner || receipt?.status != TerminalResizeReceipt.FAILED || !eligible()) return
        val key = Key(receipt.generation, receipt.requestId)
        val prior = used
        // Delayed observations must not revive an older request within this lifecycle.
        if (prior != null && (key.generation < prior.generation ||
            key.generation == prior.generation && key.request <= prior.request)) return
        cancel?.invoke(); cancel = null
        used = key
        val capturedEpoch = epoch
        var ran = false
        val cancellation = schedule {
            ran = true
            if (epoch == capturedEpoch && owner === currentOwner && used == key) {
                cancel = null
                if (eligible() && stillLatest()) retry()
            }
        }
        if (!ran && epoch == capturedEpoch && owner === currentOwner && used == key) cancel = cancellation
        else cancellation()
    }
}

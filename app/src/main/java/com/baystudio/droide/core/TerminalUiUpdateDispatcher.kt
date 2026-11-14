package com.baystudio.droide.core

/** One pending render per attached view; detached generations cannot update a replacement. */
internal class TerminalUiUpdateDispatcher(
    private val schedule: (() -> Unit) -> (() -> Unit),
    private val update: () -> Unit,
) {
    private class Ticket { var cancel: (() -> Unit)? = null }
    private var attached = false
    private var pending: Ticket? = null

    fun attach() {
        synchronized(this) { attached = true }
        request()
    }

    fun request() {
        val ticket = synchronized(this) {
            if (!attached || pending != null) return
            Ticket().also { pending = it }
        }
        val cancel = try {
            schedule {
                val accepted = synchronized(this) {
                    if (attached && pending === ticket) {
                        pending = null
                        true
                    } else false
                }
                // Emulator callbacks may request a frame while holding their own lock.
                // Never hold this dispatcher lock while acquiring the emulator lock.
                if (accepted) update()
            }
        } catch (error: Exception) {
            synchronized(this) { if (pending === ticket) pending = null }
            throw error
        }
        val stale = synchronized(this) {
            if (pending === ticket) { ticket.cancel = cancel; false } else true
        }
        if (stale) cancel()
    }

    fun detach() {
        val cancel = synchronized(this) {
            attached = false
            val prior = pending?.cancel
            pending = null
            prior
        }
        cancel?.invoke()
    }
}

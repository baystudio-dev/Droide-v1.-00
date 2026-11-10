package com.baystudio.droide.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.IdentityHashMap

/** One editor owns its pending callbacks. Cancellation uses the caller's Runnable identity. */
internal class EditorCallbackQueue {
    private val handler = Handler(Looper.getMainLooper())
    private val token = Any()
    private val pending = IdentityHashMap<Runnable, MutableSet<Runnable>>()
    private var closed = false
    internal val pendingCount: Int get() = synchronized(this) { pending.values.sumOf { it.size } }

    @Synchronized
    fun post(action: Runnable, delayMillis: Long = 0L, run: (Runnable) -> Unit): Boolean {
        if (closed) return false
        lateinit var delivery: Runnable
        delivery = Runnable {
            val admitted = synchronized(this) {
                val callbacks = pending[action]
                val found = callbacks?.remove(delivery) == true
                if (callbacks?.isEmpty() == true) pending.remove(action)
                found && !closed
            }
            if (admitted) run(action)
        }
        pending.getOrPut(action) { mutableSetOf() }.add(delivery)
        val now = SystemClock.uptimeMillis()
        val whenMillis = now + delayMillis.coerceIn(0L, Long.MAX_VALUE - now)
        if (handler.postAtTime(delivery, token, whenMillis)) return true
        pending[action]?.let { it.remove(delivery); if (it.isEmpty()) pending.remove(action) }
        return false
    }

    @Synchronized
    fun remove(action: Runnable) {
        pending.remove(action)?.forEach(handler::removeCallbacks)
    }

    @Synchronized
    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(token)
        pending.clear()
    }
}

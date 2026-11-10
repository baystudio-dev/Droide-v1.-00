package com.baystudio.droide.core

import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Only a promoted, active developer foreground service owns this short, renewable CPU lease. */
internal class DevelopmentCpuWakeLease(private val manager: PowerManager,
    private val handler: Handler = Handler(Looper.getMainLooper()), private val onFailure: () -> Unit = {}, private val tag: String = "Droide:active-developer-operation",
    private val acquireAdmission: (() -> Unit) -> Unit = { it() }) {
    private var lock: PowerManager.WakeLock? = null
    private var active = false
    private val renew = object : Runnable {
        override fun run() {
            if (!active) return
            try { acquire() } catch (failure: RuntimeException) {
                runCatching { release() }.onFailure { failure.addSuppressed(it) }
                runCatching(onFailure)
                android.util.Log.e("DroideLifecycle", "CPU wake lease renewal failed", failure)
                return
            }
            handler.postDelayed(this, RENEW_MS)
        }
    }
    fun hold() {
        if (active) return
        active = true
        try { acquire(); handler.postDelayed(renew, RENEW_MS) }
        catch (failure: RuntimeException) {
            runCatching { release() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }
    private fun acquire() = acquireAdmission {
        val lease = lock ?: manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false) }.also { lock = it }
        lease.acquire(LEASE_MS)
    }
    fun release() {
        active = false
        handler.removeCallbacks(renew)
        val previous = lock.also { lock = null }
        previous?.let { if (it.isHeld) it.release() }
    }
    companion object {
        const val LEASE_MS = 120_000L
        const val RENEW_MS = 60_000L
    }
}

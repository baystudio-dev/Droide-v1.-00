package com.baystudio.droide.core

import android.util.Log

internal object DevelopmentForegroundSessionRegistry {
    private data class Entry(val cancel: () -> Unit, val cpuWork: Boolean,
        val onAcquired: (() -> Unit)? = null, val onProtectionLost: ((String) -> Unit)? = null,
        val acquired: Boolean = false)
    private val callbacks = linkedMapOf<String, Entry>()
    private var accepting = true
    private var epoch: String? = null

    @Synchronized fun openForEpoch(value: String) {
        if (epoch == value && accepting) return
        check(callbacks.isEmpty()) { "Previous developer operations have not been retired" }
        epoch = value
        accepting = true
    }
    fun register(token: String, cancel: () -> Unit) = registerWithCpuPolicy(token, true, cancel)
    fun registerWithCpuPolicy(token: String, cpuWork: Boolean, cancel: () -> Unit) =
        registerWithCpuPolicy(token, cpuWork, cancel, null, null)
    @Synchronized fun registerWithCpuPolicy(token: String, cpuWork: Boolean, cancel: () -> Unit,
        onAcquired: (() -> Unit)?, onProtectionLost: ((String) -> Unit)?) {
        require(token.matches(Regex("[A-Za-z0-9_-]{12,100}"))) { "Invalid foreground-session token" }
        check(accepting) { "Developer session admission is closed" }
        check(!callbacks.containsKey(token)) { "Duplicate foreground-session token" }
        callbacks[token] = Entry(cancel, cpuWork, onAcquired, onProtectionLost)
    }
    @Synchronized fun cpuWork(token: String): Boolean = callbacks[token]?.cpuWork == true
    @Synchronized fun setCpuWork(token: String, required: Boolean) {
        callbacks[token]?.let { callbacks[token] = it.copy(cpuWork = required) }
    }
    @Synchronized fun remove(token: String) { callbacks.remove(token) }
    @Synchronized fun isRegistered(token: String): Boolean = callbacks.containsKey(token)
    fun cancel(token: String): Boolean {
        val callback = synchronized(this) { callbacks.remove(token) } ?: return false
        runCatching { callback.cancel() }.onFailure { Log.e("DroideLifecycle", "Developer operation cancellation request failed", it) }
        return true
    }
    fun cancelAll(tokens: Collection<String>) {
        tokens.distinct().forEach(::cancel)
    }
    /** Observational acknowledgement only after promotion and CPU acquisition. */
    fun acquired(token: String) {
        val callback = synchronized(this) {
            val entry = callbacks[token] ?: return
            if (entry.acquired) return
            callbacks[token] = entry.copy(acquired = true)
            entry.onAcquired
        }
        runCatching { callback?.invoke() }.onFailure { Log.e("DroideLifecycle", "CPU protection observer failed", it) }
    }
    /** Human work is warned; managed operations retain their existing fail-closed cancellation. */
    fun protectionLost(token: String, reason: String): Boolean {
        val entry = synchronized(this) { callbacks.remove(token) } ?: return false
        runCatching { entry.onProtectionLost?.invoke(reason) ?: entry.cancel() }
            .onFailure { Log.e("DroideLifecycle", "CPU protection loss observer failed", it) }
        return true
    }
    fun protectionLostAll(tokens: Collection<String>, reason: String) = tokens.distinct().forEach { protectionLost(it, reason) }
    /** Includes registrations Android has not yet delivered to the service. Not a job-join receipt. */
    fun closeForExit(): Result<Unit> {
        val owned = synchronized(this) {
            accepting = false
            callbacks.values.toList().also { callbacks.clear() }
        }
        return OwnedResourceCleanup.run(*owned.map { entry -> { entry.cancel() } }.toTypedArray())
    }
}

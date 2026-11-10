package com.baystudio.droide.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A replacement runtime cannot abandon an earlier host's failed cleanup for this workspace. Gate
 * entries are stable; removing a gate while a claimant waits could split ownership. */
internal object WorkspaceAgentHostOwnership {
    private class Entry {
        val gate = Mutex()
        val owner = AtomicReference<WorkspaceAgentPluginHost?>()
    }
    private val entries = ConcurrentHashMap<String, Entry>()

    // Call before acquiring the claimant's sessionLifecycle lock: retiring the previous host must be
    // able to acquire that host's lock without a claim/cleanup lock cycle.
    suspend fun claim(key: String, host: WorkspaceAgentPluginHost, requireCurrent: () -> Unit) {
        val entry = entries.computeIfAbsent(key) { Entry() }
        entry.gate.withLock {
            requireCurrent()
            val previous = entry.owner.get()
            if (previous === host) return@withLock
            previous?.closeAndAwait()
            requireCurrent()
            entry.owner.set(host)
        }
    }

    fun release(key: String, host: WorkspaceAgentPluginHost) {
        entries[key]?.owner?.compareAndSet(host, null)
    }
}

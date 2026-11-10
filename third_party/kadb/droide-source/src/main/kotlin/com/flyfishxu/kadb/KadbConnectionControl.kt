// Droide Android-only derivative changed 2026-10-15: cancellation, TLS/GUID and source integration.
package com.flyfishxu.kadb

import java.io.Closeable
import java.io.IOException

/** Droide derivative: closes raw I/O without waiting for a protocol writer or connection lock. */
class KadbConnectionControl {
    private val lock = Any()
    private var cancelled = false
    private val owned = mutableSetOf<Closeable>()

    fun checkActive() = synchronized(lock) {
        if (cancelled) throw IOException("ADB connection cancelled")
    }

    fun own(resource: Closeable): Closeable {
        val accepted = synchronized(lock) {
            if (cancelled) false else { owned.add(resource); true }
        }
        if (!accepted) {
            runCatching { resource.close() }
            throw IOException("ADB connection cancelled")
        }
        return Closeable { synchronized(lock) { owned.remove(resource) }; Unit }
    }

    fun cancel() {
        val resources = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            owned.toList().also { owned.clear() }
        }
        resources.forEach { runCatching { it.close() } }
    }
}

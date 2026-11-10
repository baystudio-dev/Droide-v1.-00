package com.baystudio.droide.core

import java.io.Closeable
import kotlinx.coroutines.CancellationException

/** Single publication/cancellation winner. Published sessions never enter this ownership set. */
internal class DeviceBridgeConnectionAttempts {
    class Token internal constructor(val id: Long, internal val cancelCaller: () -> Unit) {
        internal val owned = linkedSetOf<Closeable>()
    }
    private val lock = Any()
    private var sequence = 0L
    private var current: Token? = null
    private var closed = false

    fun begin(cancelCaller: () -> Unit = {}, onBegin: (Token) -> Unit): Token = synchronized(lock) {
        check(!closed) { "Device bridge is closed" }
        check(current == null) { "A device connection operation is already running" }
        Token(++sequence, cancelCaller).also {
            current = it
            try { onBegin(it) } catch (error: Throwable) { current = null; throw error }
        }
    }

    fun checkCurrent(token: Token) = synchronized(lock) {
        if (closed || current !== token) throw CancellationException("Device connection cancelled")
    }

    fun own(token: Token, resource: Closeable) {
        val accepted = synchronized(lock) {
            if (!closed && current === token) { token.owned.add(resource); true } else false
        }
        if (!accepted) {
            runCatching { resource.close() }
            throw CancellationException("Device connection cancelled")
        }
    }

    fun commit(token: Token, retained: Closeable? = null, publish: () -> Unit): Boolean {
        val cleanup = synchronized(lock) {
            if (closed || current !== token) return false
            publish()
            current = null
            token.owned.filter { it !== retained }.also { token.owned.clear() }
        }
        cleanup.forEach { runCatching { it.close() } }
        return true
    }

    fun cancel(token: Token, publish: () -> Unit = {}): Boolean {
        var cleanup = emptyList<Closeable>()
        try {
            synchronized(lock) {
                if (current !== token) return false
                current = null
                cleanup = token.owned.toList()
                token.owned.clear()
                publish()
            }
        } finally {
            cleanup.forEach { runCatching { it.close() } }
        }
        return true
    }

    /** Explicit Cancel may cancel its caller only if it beats publication under the same lock. */
    fun requestCancel(token: Token, publish: () -> Unit = {}): Boolean {
        if (!cancel(token, publish)) return false
        token.cancelCaller()
        return true
    }

    fun active(): Token? = synchronized(lock) { current }

    fun invalidate(publish: () -> Unit) {
        var detached: Token? = null
        var cleanup = emptyList<Closeable>()
        try {
            synchronized(lock) {
                detached = current
                cleanup = current?.owned?.toList().orEmpty()
                current?.owned?.clear()
                current = null
                publish()
            }
        } finally {
            cleanup.forEach { runCatching { it.close() } }
            detached?.cancelCaller?.invoke()
        }
    }

    fun close(publish: () -> Unit = {}) {
        var detached: Token? = null
        var cleanup = emptyList<Closeable>()
        try {
            synchronized(lock) {
                closed = true
                detached = current
                cleanup = current?.owned?.toList().orEmpty()
                current?.owned?.clear()
                current = null
                publish()
            }
        } finally {
            cleanup.forEach { runCatching { it.close() } }
            detached?.cancelCaller?.invoke()
        }
    }
}

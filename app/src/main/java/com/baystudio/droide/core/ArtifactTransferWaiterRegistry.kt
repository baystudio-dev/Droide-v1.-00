package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException

/** Shares a worker between callers without giving one caller ownership of everybody's transfer. */
internal class ArtifactTransferWaiterRegistry {
    private val lock = Any()
    private val groups = mutableMapOf<String, Group>()

    internal class Group {
        val waiters = mutableSetOf<Waiter>()
        @Volatile var cancellationReason: String? = null
    }

    internal class Waiter internal constructor(val transferId: String, internal val group: Group) {
        fun ensureActive() {
            group.cancellationReason?.let { throw CancellationException(it) }
        }
    }

    /** Registration and scheduling share the lock with last-caller cancellation. */
    fun join(transferId: String, start: () -> Unit): Waiter = synchronized(lock) {
        val group = groups[transferId]?.takeIf { it.cancellationReason == null }
            ?: Group().also { groups[transferId] = it }
        val first = group.waiters.isEmpty()
        val waiter = Waiter(transferId, group)
        group.waiters.add(waiter)
        try {
            if (first) start()
            waiter
        } catch (failure: Throwable) {
            group.waiters.remove(waiter)
            if (group.waiters.isEmpty() && groups[transferId] === group) groups.remove(transferId)
            throw failure
        }
    }

    fun leave(waiter: Waiter, canceled: Boolean, cancelLast: () -> Unit) = synchronized(lock) {
        val group = waiter.group
        if (!group.waiters.remove(waiter)) return@synchronized
        if (group.waiters.isNotEmpty()) return@synchronized
        if (groups[waiter.transferId] !== group) return@synchronized
        groups.remove(waiter.transferId)
        if (canceled && group.cancellationReason == null) cancelLast()
    }

    /** User and Android stop actions still stop the worker for all current callers. */
    fun cancelAll(transferId: String, reason: String, stop: () -> Unit) = synchronized(lock) {
        groups[transferId]?.let { group ->
            if (group.cancellationReason == null) group.cancellationReason = reason
        }
        stop()
    }

    fun cancelAllIf(transferId: String, reason: String, stop: () -> Boolean): Boolean = synchronized(lock) {
        if (!stop()) return@synchronized false
        groups[transferId]?.let { group ->
            if (group.cancellationReason == null) group.cancellationReason = reason
        }
        true
    }

    internal fun waiterCount(): Int = synchronized(lock) { groups.values.sumOf { it.waiters.size } }
}

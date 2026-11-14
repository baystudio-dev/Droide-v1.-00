package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred

internal class ExportCaptureBusyException(message: String) : IllegalStateException(message)

/** Owner-controlled quiescence. The owner holds its lock until the nested capture finishes. */
internal interface ExportCaptureParticipant {
    suspend fun <T> withQuiescence(action: suspend () -> T): T
}

/** Small, non-waiting fence for mapped application writers not covered by the repository mutex.
 * No callback runs under this monitor. Nested writers are counted, not locked; SAF/repository
 * publication keeps its existing receipts and lock order. This is NOT an external-process lock.
 */
internal object ExportWriterAdmission {
    private class ManagedWriter(val label: String, val participant: ExportCaptureParticipant)
    private data class State(var capturing: Boolean = false, val writers: MutableMap<String, Int> = mutableMapOf(),
        val managed: MutableSet<ManagedWriter> = linkedSetOf(), var captureDone: CompletableDeferred<Unit>? = null,
        var captureLabel: String = "Export", var allowsConcurrentFileWriters: Boolean = false)
    private val states = mutableMapOf<String, State>()
    private var observationVersion: Any = Any() // guarded by states; opaque ABA-safe token
    data class RegisteredWriter(val root: String, val label: String, val count: Int)
    class Observation internal constructor(writers: List<RegisteredWriter>, managed: List<RegisteredWriter>,
                                          captures: List<RegisteredWriter>, private val validateState: () -> Unit) {
        val writers: List<RegisteredWriter> = java.util.Collections.unmodifiableList(writers.toList())
        val managed: List<RegisteredWriter> = java.util.Collections.unmodifiableList(managed.toList())
        val captures: List<RegisteredWriter> = java.util.Collections.unmodifiableList(captures.toList())
        fun validate() = validateState()
    }

    /** Participating registrations only; never a reservation or external writer census. */
    fun observe(root: File): Observation {
        val rootKey = key(root)
        return synchronized(states) {
            val version = observationVersion
            val matching = states.filterKeys { overlaps(it, rootKey) }
            Observation(
                matching.flatMap { (path, state) -> state.writers.map { RegisteredWriter(path, it.key, it.value) } },
                matching.flatMap { (path, state) -> state.managed.groupingBy { it.label }.eachCount().map { RegisteredWriter(path, it.key, it.value) } },
                matching.filterValues { it.capturing }.map { RegisteredWriter(it.key, it.value.captureLabel, 1) },
            ) { synchronized(states) { check(observationVersion === version) { "Participating writer registrations changed during review" } } }
        }
    }
    private fun key(root: File) = root.canonicalPath
    private fun overlaps(a: String, b: String) = a == b || a.startsWith("$b/") || b.startsWith("$a/")

    private fun enterWriter(root: File, label: String): String {
        val key = key(root)
        synchronized(states) {
            states.entries.firstOrNull { (path, value) -> value.capturing && !value.allowsConcurrentFileWriters && overlaps(path, key) }?.let {
                throw ExportCaptureBusyException("${it.value.captureLabel} is capturing this workspace; retry $label after capture finishes")
            }
            addWriterLocked(key, label)
        }
        return key
    }
    private fun addWriterLocked(key: String, label: String) {
        observationVersion = Any()
        val state = states.getOrPut(key) { State() }
        state.writers[label] = (state.writers[label] ?: 0) + 1
    }
    private fun leaveWriter(key: String, label: String) = synchronized(states) {
        val state = checkNotNull(states[key])
        val count = checkNotNull(state.writers[label])
        observationVersion = Any()
        if (count == 1) state.writers.remove(label) else state.writers[label] = count - 1
        if (!state.capturing && state.writers.isEmpty() && state.managed.isEmpty()) states.remove(key)
    }
    /** Retained asynchronous owners close this ticket on every completion/cancel-before-start path. */
    fun beginWriter(root: File, label: String): AutoCloseable {
        val key = enterWriter(root, label)
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) leaveWriter(key, label) }
    }

    /** A live connection still blocks raw capture. Coordinated export may prepare its owner. */
    fun beginManagedWriter(root: File, label: String, participant: ExportCaptureParticipant): AutoCloseable {
        val key = key(root)
        val writer = ManagedWriter(label, participant)
        synchronized(states) {
            states.entries.firstOrNull { (path, value) -> value.capturing && overlaps(path, key) }?.let {
                throw ExportCaptureBusyException("${it.value.captureLabel} is capturing this workspace; retry $label after capture finishes")
            }
            observationVersion = Any()
            states.getOrPut(key) { State() }.managed.add(writer)
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(states) {
                val state = checkNotNull(states[key])
                observationVersion = Any()
                state.managed.remove(writer)
                if (!state.capturing && state.writers.isEmpty() && state.managed.isEmpty()) states.remove(key)
            }
        }
    }
    fun <T> withWriter(root: File, label: String, action: () -> T): T {
        val key = enterWriter(root, label)
        return try { action() } finally { leaveWriter(key, label) }
    }
    suspend fun <T> withSuspendingWriter(root: File, label: String, action: suspend () -> T): T {
        val key = enterWriter(root, label)
        return try { action() } finally { leaveWriter(key, label) }
    }

    /** Resource disposal waits outside the owner's mutex. It must not lose a close
     * request in the interval between releasing owner locks and releasing capture. */
    internal suspend fun <T> withWriterAfterCapture(root: File, label: String, action: suspend () -> T): T {
        val key = key(root)
        while (true) {
            currentCoroutineContext().ensureActive()
            val waits = synchronized(states) {
                val pending = states.filterKeys { overlaps(it, key) }.values.mapNotNull { it.captureDone }
                if (pending.isEmpty()) addWriterLocked(key, label)
                pending
            }
            if (waits.isEmpty()) return try { action() } finally { leaveWriter(key, label) }
            waits.forEach { it.await() }
        }
    }
    /** Advisory, read-only snapshot. It neither creates a state nor reserves a future capture. */
    fun snapshotBlockers(root: File, prepareManagedWriters: Boolean = false): List<String> {
        val key = key(root)
        return synchronized(states) {
            buildList {
                states.forEach { (path, state) ->
                    if (overlaps(path, key)) {
                        if (state.capturing) add("Another ${state.captureLabel.lowercase()} is capturing this workspace")
                        addAll(state.writers.keys)
                        if (!prepareManagedWriters) addAll(state.managed.map { it.label })
                    }
                }
            }.distinct().sorted()
        }
    }
    /** A single regular file is content-validated instead of treating every live process as
     * a conflicting writer. Recovery/catalog captures remain exclusive. No process is stopped
     * or removed from its lifetime/Exit ledger by this export-only observation. */
    internal fun snapshotVerifiedFileBlockers(file: File): List<String> {
        val key = key(file)
        return synchronized(states) {
            states.filter { (path, state) -> state.capturing && overlaps(path, key) }
                .map { "Another ${it.value.captureLabel.lowercase()} is capturing this workspace" }.distinct().sorted()
        }
    }

    /** Only used with a regular-file permit that ExportCaptureStore verifies before sealing.
     * Existing managed databases retain their checkpoint/mutex handoff. New managed owners
     * are still refused until capture ends. App-owned metadata (including cold SQLite opens)
     * is excluded by source admission and never uses this concurrent-file path. */
    internal suspend fun <T> withVerifiedFileCapture(file: File, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val key = key(file)
        val participants = synchronized(states) {
            check(states.none { (path, state) -> state.capturing && overlaps(path, key) }) { "Another export is capturing this workspace" }
            val owned = states.filterKeys { overlaps(it, key) }.values.flatMap { it.managed }.map { it.participant }
            reserveCaptureLocked(key)
            states.getValue(key).allowsConcurrentFileWriters = true
            owned
        }
        suspend fun capture(index: Int): T {
            currentCoroutineContext().ensureActive()
            return if (index == participants.size) action()
            else participants[index].withQuiescence { capture(index + 1) }
        }
        return try { capture(0) } finally { endCapture(key) }
    }

    internal fun beginCapture(root: File): AutoCloseable = beginCapture(root, "Export")

    internal fun beginCapture(root: File, label: String): AutoCloseable {
        val key = key(root)
        synchronized(states) {
            check(states.none { (path, value) -> value.capturing && overlaps(path, key) }) { "Another export is capturing this workspace" }
            val active = states.filterKeys { overlaps(it, key) }.values.flatMap { it.writers.keys + it.managed.map { writer -> writer.label } }
            check(active.isEmpty()) { "Source is not quiescent: ${active.distinct().sorted().joinToString()}" }
            reserveCaptureLocked(key, label)
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) endCapture(key) }
    }

    /** Reserve before checkpointing: no new mapped writer can race the owner handoff.
     * No owner callback, continuation or disk IO runs under the registry monitor. */
    internal suspend fun <T> withQuiescentCapture(root: File, label: String = "Export", action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val key = key(root)
        val participants = synchronized(states) {
            check(states.none { (path, value) -> value.capturing && overlaps(path, key) }) { "Another export is capturing this workspace" }
            val matching = states.filterKeys { overlaps(it, key) }.values
            val active = matching.flatMap { it.writers.keys }.distinct().sorted()
            check(active.isEmpty()) { "Source is not quiescent: ${active.joinToString()}" }
            val owned = matching.flatMap { it.managed }.map { it.participant }
            reserveCaptureLocked(key, label)
            owned
        }
        suspend fun capture(index: Int): T {
            currentCoroutineContext().ensureActive()
            return if (index == participants.size) action()
            else participants[index].withQuiescence { capture(index + 1) }
        }
        return try { capture(0) } finally { endCapture(key) }
    }

    private fun reserveCaptureLocked(key: String, label: String = "Export") {
        observationVersion = Any()
        states.getOrPut(key) { State() }.apply {
            capturing = true
            captureLabel = label
            captureDone = CompletableDeferred()
        }
    }

    private fun endCapture(key: String) {
        val completion = synchronized(states) {
            val state = checkNotNull(states[key])
            val done = checkNotNull(state.captureDone)
            observationVersion = Any()
            state.capturing = false
            state.allowsConcurrentFileWriters = false
            state.captureDone = null
            if (state.writers.isEmpty() && state.managed.isEmpty()) states.remove(key)
            done
        }
        completion.complete(Unit)
    }
}

package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Raw output is copied into a bounded queue; disk latency never blocks a terminal reader or the UI. */
class TerminalOutputRecording(private val workspace: File, private val limitBytes: Long = 64L * 1024 * 1024) {
    data class State(val active: Boolean = false, val saving: Boolean = false, val path: String? = null,
        val bytes: Long = 0, val problem: String? = null)
    private data class Writer(val queue: Channel<ByteArray>, val done: CompletableDeferred<Unit>, var accepted: Long = 0)
    private val lock = Any()
    private var writer: Writer? = null
    private var closed = false
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    suspend fun start() = withContext(Dispatchers.IO) {
        synchronized(lock) { check(!closed && writer == null) { "Recording is unavailable or still saving" } }
        val name = "terminal-${System.currentTimeMillis()}-${UUID.randomUUID()}.log"
        val opened = AnchoredWorkspaceFiles(workspace).use { root ->
            if (root.kind("terminal-logs") == AnchoredWorkspaceFiles.Kind.ABSENT) {
                val receipt = root.createDirectory("terminal-logs")
                check(receipt.durable || receipt.error == 17) { "Cannot create terminal log directory" }
            }
            root.openDirectory("terminal-logs").use { it.createNew(name) }
        }
        val candidate = Writer(Channel(64), CompletableDeferred())
        try {
            synchronized(lock) {
                check(!closed && writer == null) { "Recording is unavailable or still saving" }
                writer = candidate
                mutable.value = State(active = true, path = "terminal-logs/$name")
            }
        } catch (failure: Throwable) { opened.close(); throw failure }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        owner.launch {
            try {
                opened.use { file ->
                    var written = 0L
                    for (chunk in candidate.queue) {
                        file.write(chunk); written += chunk.size
                        synchronized(lock) { mutable.value = mutable.value.copy(bytes = written) }
                    }
                    file.forceContents()
                }
            } catch (_: Exception) {
                synchronized(lock) { mutable.value = mutable.value.copy(problem = "Recording incomplete: storage write failed") }
            } finally {
                candidate.queue.cancel()
                synchronized(lock) {
                    if (writer === candidate) { writer = null; mutable.value = mutable.value.copy(active = false, saving = false) }
                }
                candidate.done.complete(Unit)
                owner.cancel()
            }
        }
    }

    fun append(bytes: ByteArray, count: Int = bytes.size): Unit = synchronized(lock) {
        val current = writer ?: return@synchronized
        if (!mutable.value.active) return@synchronized
        require(count in 0..bytes.size)
        if (count == 0) return@synchronized
        val problem = when {
            count > 65536 || current.accepted + count > limitBytes -> "Recording stopped at its storage limit; later output was not recorded"
            !current.queue.trySend(bytes.copyOf(count)).isSuccess -> "Recording incomplete: output exceeded the disk writer's queue"
            else -> null
        }
        if (problem == null) current.accepted += count else {
            mutable.value = mutable.value.copy(active = false, saving = true, problem = problem)
            current.queue.close()
        }
    }

    fun stop() = synchronized(lock) {
        writer?.let { mutable.value = mutable.value.copy(active = false, saving = true); it.queue.close() }
    }

    fun close() = synchronized(lock) { closed = true; stop() }

    suspend fun awaitClosed() {
        val completion = synchronized(lock) { closed = true; stop(); writer?.done }
        completion?.await()
    }
}

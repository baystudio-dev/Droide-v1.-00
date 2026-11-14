package com.baystudio.droide.core

import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*

/** Owns one pipe shell generation, including startup before a Process exists. */
internal class InteractiveTerminalProcess(
    private val scope: CoroutineScope,
    private val launchProcess: (Long) -> Process,
    private val cleanup: (Process) -> Unit,
    private val output: (String) -> Unit,
    private val onFinished: (Int) -> Unit = {},
    private val onFailure: () -> Unit = {},
    private val maxCommandChars: Int = 64_000,
    private val maxQueuedChars: Int = 256_000,
    private val initialCommand: String? = null,
) {
    internal fun interface LaunchAdmission {
        suspend fun create(factory: () -> Process): Process
    }

    private var prepareLaunch: () -> LaunchAdmission = { LaunchAdmission { it() } }

    constructor(
        scope: CoroutineScope,
        launchProcess: (Long) -> Process,
        cleanup: (Process) -> Unit,
        output: (String) -> Unit,
        onFinished: (Int) -> Unit,
        onFailure: () -> Unit,
        maxCommandChars: Int,
        maxQueuedChars: Int,
        initialCommand: String?,
        prepareLaunch: () -> LaunchAdmission,
    ) : this(scope, launchProcess, cleanup, output, onFinished, onFailure, maxCommandChars, maxQueuedChars, initialCommand) {
        this.prepareLaunch = prepareLaunch
    }

    private val lock = java.lang.Object()
    private var observationVersion: Any = Any() // guarded by lock
    private var worker: Job? = null
    private var process: Process? = null
    private var generation = 0L
    private var destroyed = false
    // Restart/destroy may detach the display owner before its non-cancellable cleanup ends.
    private val retiringWorkers = mutableSetOf<Job>()
    private inner class CleanupOwner(val child: Process) {
        val lease = OwnedLeaseCleanupAttempt { cleanup(child) }
        var stopConfirmed = false
        @Volatile var reader: Thread? = null
        @Volatile var writer: Thread? = null
    }
    private val cleanupOwners = mutableSetOf<CleanupOwner>()
    private val pendingStops = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Process, Boolean>())
    private var exited = false
    private val pending = ArrayDeque<String>()
    private var pendingChars = 0

    /** Only owner metadata. No process/thread getter or cleanup attempt is invoked. */
    internal fun observeOwnership(): TerminalOwnershipObservation = synchronized(lock) {
        val version = observationVersion
        val diagnostics = buildList {
            if (worker != null) add("Pipe terminal worker registration: 1")
            if (process != null) add("Pipe terminal process registration: 1")
            if (retiringWorkers.isNotEmpty()) add("Pipe terminal retained workers: ${retiringWorkers.size}")
            if (cleanupOwners.isNotEmpty()) add("Pipe terminal retained cleanup owners: ${cleanupOwners.size}")
            val readers = cleanupOwners.count { it.reader != null }
            val writers = cleanupOwners.count { it.writer != null }
            if (readers > 0) add("Pipe terminal retained reader references: $readers")
            if (writers > 0) add("Pipe terminal retained writer references: $writers")
            if (pendingStops.isNotEmpty()) add("Pipe terminal unconfirmed stops: ${pendingStops.size}")
            if (pending.isNotEmpty()) add("Pipe terminal queued input frames: ${pending.size}")
        }
        TerminalOwnershipObservation(emptyList(), destroyed, diagnostics) {
            synchronized(lock) {
                check(observationVersion === version) { "Pipe terminal ownership changed during review" }
            }
        }
    }

    fun start() = start(expectedGeneration = null)

    private fun start(expectedGeneration: Long?) {
        synchronized(lock) {
            if (expectedGeneration != null && (destroyed || generation != expectedGeneration || worker != null)) return
            check(!destroyed) { "Terminal is closed" }
            if (worker != null && !exited) return
        }
        // A timed-out prior stop stays a barrier: explicit start/restart cannot
        // turn an unconfirmed known child into an overlapping replacement.
        observePendingStops()
        // Capture admission before taking the process lock to avoid reversing the caller's lock order.
        val admission = prepareLaunch()
        val job = synchronized(lock) {
            if (expectedGeneration != null && (destroyed || generation != expectedGeneration || worker != null)) return
            check(!destroyed) { "Terminal is closed" }
            scope.coroutineContext.ensureActive()
            if (worker != null && !exited) return
            check(pendingStops.isEmpty()) { "Prior terminal stop is still pending; retry after cleanup" }
            observationVersion = Any()
            exited = false
            val currentGeneration = ++generation
            initialCommand?.let {
                require(it.length <= maxCommandChars && '\u0000' !in it) { "Terminal initialization is invalid" }
                pending.addFirst(it)
                pendingChars += it.length
            }
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                runGeneration(currentGeneration, admission)
            }.also { created ->
                worker = created
                retiringWorkers += created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        observationVersion = Any()
                        retiringWorkers -= created
                        if (worker === created) {
                            worker = null
                            exited = true
                            pending.clear()
                            pendingChars = 0
                            lock.notifyAll()
                        }
                    }
                }
            }
        }
        job.start()
    }

    fun send(command: String) {
        require(command.length <= maxCommandChars && '\u0000' !in command) { "Terminal command is too large or invalid" }
        synchronized(lock) {
            check(!destroyed && !exited) { "Terminal is closed or its process exited" }
            check(pending.size < 32 && pendingChars + command.length <= maxQueuedChars) {
                "Terminal startup command queue is full"
            }
            observationVersion = Any()
            pending.addLast(command)
            pendingChars += command.length
            lock.notifyAll()
        }
    }

    fun restart() {
        stop(permanently = false)
        start()
    }

    internal class RestartClaim(private val completeAction: ((() -> Unit) -> Unit) -> Unit) {
        private val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun complete(restartAdmission: (() -> Unit) -> Unit = { it() }) {
            if (completed.compareAndSet(false, true)) completeAction(restartAdmission)
        }
    }

    /** Lock order: command tracker, then process owner. Only detachment occurs here. */
    fun claimRestartIfCurrent(expectedGeneration: Long, stillOwner: () -> Boolean): RestartClaim? {
        val detached = synchronized(lock) {
            if (destroyed || generation != expectedGeneration || !stillOwner()) return null
            detachLocked(permanently = false)
        }
        return RestartClaim { admitRestart ->
            terminateDetached(detached, observe = true)
            // Cleanup can take time. Admission and exact detached epoch are rechecked before start.
            admitRestart { start(detached.retiredGeneration) }
        }
    }

    fun restartIfCurrent(expectedGeneration: Long, stillOwner: () -> Boolean): Boolean {
        val claim = claimRestartIfCurrent(expectedGeneration, stillOwner) ?: return false
        claim.complete()
        return true
    }

    fun destroy() { stop(permanently = true) }

    suspend fun destroyAndJoinForExit() {
        val stopFailure = runCatching { destroy() }.exceptionOrNull()
        // destroy seals admission before this snapshot; every older generation is retained.
        val owned = synchronized(lock) { retiringWorkers.toList() }
        owned.forEach { it.join() }
        val retained = synchronized(lock) { cleanupOwners.toList() }
        retained.forEach { OwnedProcessTermination.request(it.child) }
        val result = OwnedResourceCleanup.run(*retained.map { owner -> { observeCleanup(owner) } }.toTypedArray())
        OwnedResourceCleanup.run({ stopFailure?.let { throw it } }, { result.getOrThrow() }).getOrThrow()
    }

    private data class Detached(val worker: Job?, val process: Process?, val retiredGeneration: Long)
    private fun detachLocked(permanently: Boolean): Detached {
        observationVersion = Any()
        destroyed = permanently
        exited = true
        ++generation
        val owned = Detached(worker, process, generation)
        worker = null
        process = null
        pending.clear()
        pendingChars = 0
        lock.notifyAll()
        return owned
    }
    private fun terminateDetached(owned: Detached, observe: Boolean) {
        owned.process?.let(::requestStop)
        owned.worker?.cancel()
        if (observe) observePendingStops()
    }

    private fun requestStop(child: Process) {
        synchronized(lock) {
            if (cleanupOwners.firstOrNull { it.child === child }?.stopConfirmed != true) {
                observationVersion = Any()
                pendingStops += child
            }
        }
        OwnedProcessTermination.request(child)
    }

    private fun confirmStop(child: Process) = synchronized(lock) {
        observationVersion = Any()
        cleanupOwners.firstOrNull { it.child === child }?.stopConfirmed = true
        pendingStops -= child
    }

    private fun observePendingStops() {
        val pending = synchronized(lock) { pendingStops.toList() }
        pending.forEach { child ->
            try { OwnedProcessTermination.terminate(child) }
            catch (failure: Throwable) {
                throw IllegalStateException("Prior terminal cleanup is unconfirmed; replacement was not started", failure)
            }
            confirmStop(child)
        }
    }
    private fun stop(permanently: Boolean) {
        val detached = synchronized(lock) {
            if (destroyed) return
            detachLocked(permanently).also { detached ->
                // Publish the ordinary restart barrier atomically with detachment;
                // a concurrent explicit start cannot slip into the dispatch gap.
                if (!permanently) detached.process?.let { pendingStops += it }
            }
        }
        terminateDetached(detached, observe = !permanently)
    }

    private suspend fun runGeneration(ownedGeneration: Long, admission: LaunchAdmission) {
        var owned: Process? = null
        var cleanupOwner: CleanupOwner? = null
        var reader: Thread? = null
        var writer: Thread? = null
        val failure = AtomicReference<Throwable?>()
        try {
            val context = currentCoroutineContext()
            context.ensureActive()
            var creationInvoked = false
            val child = admission.create {
                context.ensureActive()
                check(!creationInvoked) { "Terminal creation action was invoked twice" }
                creationInvoked = true
                launchProcess(ownedGeneration).also { child ->
                    owned = child
                    cleanupOwner = CleanupOwner(child).also { owner -> synchronized(lock) { observationVersion = Any(); cleanupOwners += owner } }
                }
            }
            check(child === owned) { "Terminal startup admission did not return its created process" }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                check(!destroyed && generation == ownedGeneration) { "Terminal startup was cancelled" }
                observationVersion = Any()
                process = child
            }
            reader = thread(name = "droide-terminal-output", isDaemon = true) {
                try {
                    // Raw process streams have one termination owner. Reader/writer
                    // workers must not race it with independent close/flush calls.
                    val input = InputStreamReader(child.inputStream, Charsets.UTF_8)
                    run {
                        val chars = CharArray(4096)
                        while (true) {
                            val count = input.read(chars)
                            if (count < 0) break
                            if (isCurrent(ownedGeneration, child)) output(String(chars, 0, count))
                        }
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
            synchronized(lock) { observationVersion = Any(); cleanupOwner?.reader = reader }
            writer = thread(name = "droide-terminal-input", isDaemon = true) {
                try {
                    val input = child.outputStream.bufferedWriter()
                    run {
                        while (true) {
                            val command = synchronized(lock) {
                                while (pending.isEmpty() && isCurrentLocked(ownedGeneration, child)) lock.wait()
                                if (!isCurrentLocked(ownedGeneration, child)) return@thread
                                observationVersion = Any()
                                pending.removeFirst().also { pendingChars -= it.length }
                            }
                            input.write(command + "\n")
                            input.flush()
                        }
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
            synchronized(lock) { observationVersion = Any(); cleanupOwner?.writer = writer }
            while (child.isAlive) {
                currentCoroutineContext().ensureActive()
                failure.get()?.let { throw it }
                delay(25)
            }
            reader.join(500)
            if (isCurrent(ownedGeneration, child)) {
                onFinished(child.exitValue())
                output("\n[process exited: ${child.exitValue()}]\n")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (synchronized(lock) { !destroyed && generation == ownedGeneration }) {
                runCatching {
                    onFailure()
                    output("[terminal error] ${error.message ?: error.javaClass.simpleName}\n")
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(lock) {
                    observationVersion = Any()
                    cleanupOwner?.let { owner ->
                        if (!owner.stopConfirmed) pendingStops += owner.child
                    }
                    if (generation == ownedGeneration) {
                        process = null
                        exited = true
                        pending.clear()
                        pendingChars = 0
                        lock.notifyAll()
                    }
                }
                cleanupOwner?.let { owner ->
                    // Preserve the lease's process-group authority while the root
                    // is alive, but bound its observation before direct termination.
                    val error = runCatching { observeCleanup(owner) }.exceptionOrNull()
                    if (error != null && synchronized(lock) { !destroyed && generation == ownedGeneration }) {
                        runCatching {
                            onFailure()
                            output("[terminal cleanup error] ${error.message ?: error.javaClass.simpleName}\n")
                        }
                    }
                }
            }
        }
    }

    /** Reobserves the same retained attempts; a timeout is not a completed receipt. */
    private fun observeCleanup(owner: CleanupOwner) {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        attempt { owner.lease.observe() }
        requestStop(owner.child)
        attempt {
            OwnedProcessTermination.terminate(owner.child)
            confirmStop(owner.child)
        }
        attempt { owner.reader?.join(500) }
        attempt { owner.writer?.join(500) }
        if (owner.reader?.isAlive == true || owner.writer?.isAlive == true)
            attempt { error("Terminal I/O worker did not stop; cleanup remains retained") }
        failure?.let { throw IllegalStateException("Terminal process cleanup is incomplete", it) }
        synchronized(lock) { observationVersion = Any(); cleanupOwners -= owner }
    }

    private fun isCurrent(ownedGeneration: Long, child: Process) = synchronized(lock) {
        isCurrentLocked(ownedGeneration, child)
    }

    private fun isCurrentLocked(ownedGeneration: Long, child: Process) =
        !destroyed && generation == ownedGeneration && process === child
}

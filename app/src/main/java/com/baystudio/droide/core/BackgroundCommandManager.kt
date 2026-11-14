package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext


class BackgroundCommandManager private constructor(
    private val terminal: ITerminalSession,
    private val heartbeatIntervalMs: Long,
    private val onCommandFinished: suspend () -> Unit,
) {
    constructor(terminal: ITerminalSession, heartbeatIntervalMs: Long = 20_000) :
        this(terminal, heartbeatIntervalMs, {})

    internal constructor(terminal: ITerminalSession, onCommandFinished: suspend () -> Unit) :
        this(terminal, 20_000, onCommandFinished)

    init { require(heartbeatIntervalMs in 20..30_000) }
    enum class State { RUNNING, EXITED, FAILED, TIMED_OUT, KILLED }

    data class Snapshot(
        val id: String,
        val state: State,
        val command: String,
        val output: String,
        val exitCode: Int?,
        val timeoutMs: Long?,
        val createdAtMs: Long,
        val finishedAtMs: Long?,
        val launchDiskState: AgentDiskExecutionState = AgentDiskExecutionState.CLEAN,
        val outputEndCursor: Long = output.length.toLong(),
        val stdinAvailable: Boolean = false,
        val interactivePty: Boolean = false,
        val outputIncomplete: Boolean = false,
    )

    private data class Entry(
        val id: String,
        val command: String,
        val executionSession: ITerminalSession,
        val timeoutMs: Long?,
        val createdAtMs: Long,
        var state: State = State.RUNNING,
        var output: String = "",
        var exitCode: Int? = null,
        var finishedAtMs: Long? = null,
        val launchDiskState: AgentDiskExecutionState = AgentDiskExecutionState.CLEAN,
        var job: Job? = null,
        var inertReservation: Boolean = false,
        var outputEndCursor: Long = 0,
        var outputIncomplete: Boolean = false,
        val input: ProcessInputBuffer? = null,
        val stopWithAgent: Boolean = false,
        val requiresTty: Boolean = false,
        val changes: MutableStateFlow<Long> = MutableStateFlow(0),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val counter = AtomicLong(0)
    private val identity = UUID.randomUUID().toString().substring(0, 12)
    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false

    fun start(
        command: String,
        timeoutMs: Long? = null,
        launchDiskState: AgentDiskExecutionState = AgentDiskExecutionState.CLEAN,
        keepStdinOpen: Boolean? = null,
        stopWithAgent: Boolean = false,
        requiresTty: Boolean = false,
    ): Snapshot {
        val (entry, launched) = prepareLaunch(command, timeoutMs, launchDiskState,
            AgentTtyPolicy.keepStdinOpen(requiresTty, keepStdinOpen), stopWithAgent, requiresTty)
        launched.start()
        return snapshot(entry.id) ?: error("Background job disappeared during launch")
    }

    /** Durable association is informational, never a reconstructible activation capability. */
    internal data class SourceLaunchIntent(
        val jobId: String,
        val sourceOwner: OwnedResponseContentStore.Owner,
        val argumentsSha256: String,
        val commandSha256: String,
        val timeoutMs: Long?,
        val keepStdinOpen: Boolean,
        val stopWithAgent: Boolean,
        val requiresTty: Boolean,
    )
    internal enum class SourceActivation { NO_COMMAND_ACTIVATION, ACTIVATED, ACTIVATION_UNCERTAIN }
    /** Only receipts registered by this live manager can be verified. Not native execution proof. */
    internal class SourceLaunchReceipt internal constructor(
        val intent: SourceLaunchIntent,
        val activation: SourceActivation,
    ) {
        override fun toString() = "SourceLaunchReceipt(activation=$activation)"
    }
    internal class SourceLaunchFailure internal constructor(
        val receipt: SourceLaunchReceipt, cause: Throwable,
    ) : IllegalStateException("Source command activation did not return normally", cause)
    internal class SourceLaunchCancelled internal constructor(
        val receipt: SourceLaunchReceipt, cause: Throwable,
    ) : CancellationException("Source command activation cancelled") {
        init { initCause(cause) }
    }
    private data class SourceLaunchRecord(val intent: SourceLaunchIntent, val entry: Entry)
    private val sourceLaunchReceipts = java.util.WeakHashMap<SourceLaunchReceipt, SourceLaunchRecord>()
    internal data class SourceLaunchObservation(
        val intent: SourceLaunchIntent,
        val activation: SourceActivation,
        val snapshot: Snapshot,
    )
    /** Exact original entry even after history pruning. Observation is not native completion proof. */
    internal fun inspectSourceLaunch(receipt: SourceLaunchReceipt): SourceLaunchObservation = synchronized(lock) {
        val record = sourceLaunchReceipts[receipt]
        check(record != null && record.intent === receipt.intent) { "Foreign source launch receipt" }
        SourceLaunchObservation(record.intent, receipt.activation, record.entry.toSnapshot())
    }

    internal fun verifySourceLaunch(receipt: SourceLaunchReceipt): SourceLaunchIntent = synchronized(lock) {
        check(sourceLaunchReceipts[receipt]?.intent === receipt.intent) { "Foreign source launch receipt" }
        receipt.intent
    }

    /**
     * Caller has already completed permission and exact-input review. Persistence runs outside
     * the manager monitor, before this invocation's private LAZY job can ever be activated.
     * ACTIVATED means Job.start accepted, not that an OS child exists or completed successfully.
     * Terminal failures/cancellation after activation remain custody-uncertain; never retry from
     * a FAILED snapshot or an error string. No-command-activation says nothing about prior
     * workspace preparation effects. This API deliberately has no managerless fallback.
     */
    internal fun startSource(
        command: String,
        sourceOwner: OwnedResponseContentStore.Owner,
        argumentsSha256: String,
        timeoutMs: Long? = null,
        launchDiskState: AgentDiskExecutionState = AgentDiskExecutionState.CLEAN,
        keepStdinOpen: Boolean? = null,
        stopWithAgent: Boolean = false,
        requiresTty: Boolean = false,
        requireCurrent: () -> Unit,
        persistIntent: (SourceLaunchIntent) -> Unit,
    ): SourceLaunchReceipt {
        require(argumentsSha256.matches(Regex("[0-9a-f]{64}")))
        requireCurrent()
        val inputOpen = AgentTtyPolicy.keepStdinOpen(requiresTty, keepStdinOpen)
        val (entry, launched) = prepareLaunch(command, timeoutMs, launchDiskState,
            inputOpen, stopWithAgent, requiresTty, preserveHistory = true)
        val intent = SourceLaunchIntent(entry.id, sourceOwner, argumentsSha256,
            java.security.MessageDigest.getInstance("SHA-256").digest(command.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            timeoutMs, inputOpen, stopWithAgent, requiresTty)
        fun receipt(state: SourceActivation) = SourceLaunchReceipt(intent, state).also {
            synchronized(lock) { sourceLaunchReceipts[it] = SourceLaunchRecord(intent, entry) }
        }
        val inactive = receipt(SourceActivation.NO_COMMAND_ACTIVATION)
        val active = receipt(SourceActivation.ACTIVATED)
        val uncertain = receipt(SourceActivation.ACTIVATION_UNCERTAIN)
        var activationAttempted = false
        try {
            persistIntent(intent)
            synchronized(lock) {
                requireCurrent()
                ApplicationWorkAdmission.requireOpen()
                check(!closed && entries[entry.id] === entry && entry.state == State.RUNNING) {
                    "Source command reservation is no longer current"
                }
                // Job is private to this invocation: only kill/close can have cancelled it.
                // No public method can start it; false therefore proves no activation here.
                activationAttempted = true
                if (!launched.start()) {
                    activationAttempted = false
                    entries.remove(entry.id)
                    entry.input?.abort()
                    return inactive
                }
                entry.inertReservation = false
                // Admission is now accepted. Retention policy may finally retire old completed
                // entries; inert failed reservations never consume prior history.
                pruneSourceAfterActivationLocked(entry)
                return active
            }
        } catch (failure: Throwable) {
            // Unexpected failure from Job.start is not proof that its body never began.
            // Retain the entry/custody in that case; never remove an ambiguous active job.
            launched.cancel()
            if (activationAttempted) synchronized(lock) { entry.inertReservation = false }
            if (!activationAttempted) synchronized(lock) {
                if (entries[entry.id] === entry) entries.remove(entry.id)
                entry.input?.abort()
            }
            val evidence = if (activationAttempted) uncertain else inactive
            if (failure is CancellationException) throw SourceLaunchCancelled(evidence, failure)
            throw SourceLaunchFailure(evidence, failure)
        }
    }

    private fun prepareLaunch(
        command: String, timeoutMs: Long?, launchDiskState: AgentDiskExecutionState,
        keepStdinOpen: Boolean, stopWithAgent: Boolean, requiresTty: Boolean,
        preserveHistory: Boolean = false,
    ): Pair<Entry, Job> {
        require(command.isNotBlank()) { "Background command cannot be blank" }
        require(command.length <= MAX_COMMAND_CHARS) { "Background command is too long" }
        require(timeoutMs == null || timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "Invalid background timeout" }
        // Bind before the lazy launch: switching user tabs must never redirect
        // an existing Agent job into a different environment or PTY.
        val executionSession = (terminal as? ExecutionTerminalRouter)?.bindCommandSession() ?: terminal
        AgentTtyPolicy.unsupportedReason(requiresTty, (executionSession as? InteractivePtyCommandSession)?.supportsInteractivePty == true)?.let {
            throw IllegalArgumentException(it)
        }
        require(!requiresTty || command.length <= 32_000) { "Interactive command is too long" }
        require(!keepStdinOpen || requiresTty || executionSession.supportsCommandInput) {
            "Command stdin is unavailable in the selected execution backend"
        }

        val id = "bg-$identity-${counter.incrementAndGet()}"
        val entry = Entry(
            id = id,
            command = command,
            executionSession = executionSession,
            timeoutMs = timeoutMs,
            createdAtMs = System.currentTimeMillis(),
            launchDiskState = launchDiskState,
            input = if (keepStdinOpen) ProcessInputBuffer() else null,
            stopWithAgent = stopWithAgent,
            requiresTty = requiresTty,
            inertReservation = preserveHistory,
        )
        synchronized(lock) {
            ApplicationWorkAdmission.requireOpen()
            check(!closed) { "Background command manager is closed" }
            // A source reservation has not durably admitted execution yet. Never evict
            // unrelated output if persistence or ownership validation subsequently fails.
            // Source may transiently exceed history capacity by bounded live reservations.
            // Prune only after accepted activation; legacy ordering remains unchanged.
            if (!preserveHistory) pruneLocked()
            check(entries.values.count { it.state == State.RUNNING } < MAX_RUNNING_JOBS) {
                "Too many concurrent background jobs (max $MAX_RUNNING_JOBS)"
            }
            check(entries.size < MAX_JOBS + MAX_RUNNING_JOBS &&
                (preserveHistory || entries.values.count { !it.inertReservation } < MAX_JOBS)) {
                "Background job history is full"
            }
            entries[id] = entry
        }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            var executionStarted = false
            var completionNotified = false
            suspend fun notifyWorkspace() {
                if (!executionStarted || completionNotified) return
                completionNotified = true
                try {
                    withContext(NonCancellable) { onCommandFinished() }
                } catch (failure: Exception) {
                    appendOutput(id, "\n[workspace refresh pending] ${failure.message?.take(300) ?: failure.javaClass.simpleName}\n")
                }
            }
            try {
                ApplicationWorkAdmission.requireOpen()
                val onOutput: (String) -> Unit = { chunk -> appendOutput(id, chunk) }
                suspend fun execute(): ExecResult {
                    executionStarted = true
                    return if (entry.requiresTty)
                        (entry.executionSession as InteractivePtyCommandSession).execInteractivePty(command, timeoutMs ?: NO_TIMEOUT, entry.input, onOutput)
                        else if (entry.input != null)
                        entry.executionSession.execStreamingWithInput(command, timeoutMs ?: NO_TIMEOUT, entry.input, onOutput)
                        else entry.executionSession.execStreaming(command, timeoutMs ?: NO_TIMEOUT, onOutput)
                }
                val protection = entry.executionSession as? DevelopmentOperationSession
                val result = if (protection != null) protection.protectCommand { execute() } else execute()
                synchronized(lock) {
                    val current = entries[id] ?: return@synchronized
                    if (current.state == State.KILLED) return@synchronized
                    if (result.output.isNotBlank() && current.output.isBlank()) {
                        current.output = boundedOutput(result.output)
                        current.outputEndCursor = result.output.length.toLong()
                    }
                }
                notifyWorkspace()
                synchronized(lock) {
                    val current = entries[id] ?: return@synchronized
                    if (current.state == State.KILLED) return@synchronized
                    current.exitCode = result.exitCode
                    current.outputIncomplete = result.outputIncomplete
                    current.state = when {
                        result.timedOut -> State.TIMED_OUT
                        result.exitCode == 0 -> State.EXITED
                        else -> State.FAILED
                    }
                    current.finishedAtMs = System.currentTimeMillis()
                    current.changed()
                }
            } catch (cancelled: CancellationException) {
                notifyWorkspace()
                synchronized(lock) {
                    entries[id]?.let { current ->
                        current.state = State.KILLED
                        current.finishedAtMs = current.finishedAtMs ?: System.currentTimeMillis()
                        current.changed()
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                appendOutput(id, "\n[background error] ${error.message ?: error.javaClass.simpleName}\n")
                notifyWorkspace()
                synchronized(lock) {
                    entries[id]?.let { current ->
                        if (current.state == State.KILLED) return@let
                        current.state = State.FAILED
                        current.exitCode = -1
                        current.finishedAtMs = System.currentTimeMillis()
                        current.changed()
                    }
                }
            } finally {
                // The process/PTY has already exited or was cancelled. A graceful EOF
                // here targets a closed relay and may throw outside the job's catch
                // block (or send input to a shell that has regained the terminal).
                // Explicit close_stdin remains the only graceful-EOF operation.
                entry.input?.abort()
            }
        }
        synchronized(lock) {
            entry.job = launched
            // list()/kill()/close() may observe the entry before its lazy job is attached.
            if (closed || entry.state != State.RUNNING) launched.cancel()
        }
        return entry to launched
    }

    fun snapshot(id: String): Snapshot? = synchronized(lock) { entries[id]?.toSnapshot() }

    /** Runtime identity only, never permission or proof that the child consumed input. */
    internal class SourceStdinBinding internal constructor(
        val sessionId: String,
        val sourceOwner: OwnedResponseContentStore.Owner,
        val argumentsSha256: String,
    )
    /** Informational snapshot only. Callers must obtain it from the manager, not deserialize it as authority. */
    internal data class SourceStdinEvidence internal constructor(
        val sessionId: String,
        val sourceOwner: OwnedResponseContentStore.Owner,
        val argumentsSha256: String,
        val observationStarted: Boolean,
        val offerAttempted: Boolean,
        val offerAccepted: Boolean,
        val eofAttempted: Boolean,
        val eofAccepted: Boolean,
        val interruptAttempted: Boolean,
        val interruptAccepted: Boolean,
        val offerKnownRejected: Boolean,
        val offerRejection: ProcessInputBuffer.OfferRejection?,
    )
    private class StdinAdmission(val entry: Entry, val charsSha256: String,
        val closeInput: Boolean, val interrupt: Boolean, val requireCurrent: () -> Unit, var consumed: Boolean = false) {
        var observationStarted = false
        var offerAttempted = false
        var offerAccepted = false
        var offerRejection: ProcessInputBuffer.OfferRejection? = null
        var eofAttempted = false
        var eofAccepted = false
        var interruptAttempted = false
        var interruptAccepted = false
    }
    // A constructed lookalike token is not registered and cannot authorize a manager operation.
    private val sourceStdinBindings = java.util.WeakHashMap<SourceStdinBinding, StdinAdmission>()

    /** Historical evidence remains readable after cancellation, completion and manager close.
     * No generation callback or lifecycle check: neither can undo already admitted input.
     * Registry identity still rejects constructed, foreign and restarted-manager tokens.
     */
    internal fun inspectSourceStdin(binding: SourceStdinBinding): SourceStdinEvidence = synchronized(lock) {
        val a = sourceStdinBindings[binding] ?: error("Foreign or expired stdin binding")
        SourceStdinEvidence(binding.sessionId, binding.sourceOwner, binding.argumentsSha256,
            a.observationStarted, a.offerAttempted, a.offerAccepted, a.eofAttempted,
            a.eofAccepted, a.interruptAttempted, a.interruptAccepted,
            a.offerRejection != null, a.offerRejection)
    }

    internal fun bindSourceStdin(id: String, sourceOwner: OwnedResponseContentStore.Owner,
        argumentsSha256: String, text: String, closeInput: Boolean, interrupt: Boolean,
        requireCurrent: () -> Unit = {}): SourceStdinBinding = synchronized(lock) {
        check(!closed) { "Command manager is closed" }
        require(argumentsSha256.matches(Regex("[0-9a-f]{64}")))
        require(!interrupt || (text.isEmpty() && !closeInput))
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_536)
        val entry = entries[id] ?: error("Command session not found: $id")
        SourceStdinBinding(id, sourceOwner, argumentsSha256).also {
            sourceStdinBindings[it] = StdinAdmission(entry, stdinDigest(text), closeInput, interrupt, requireCurrent)
        }
    }
    private fun stdinDigest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun requireSourceStdin(binding: SourceStdinBinding, id: String): StdinAdmission {
        check(!closed) { "Command manager is closed" }
        val admitted = sourceStdinBindings[binding] ?: error("Foreign or expired stdin binding")
        check(binding.sessionId == id && entries[id] === admitted.entry) { "Stale command session binding" }
        admitted.requireCurrent() // non-suspending runtime generation/cancellation check, no filesystem IO
        return admitted
    }
    internal fun beginSourceStdin(binding: SourceStdinBinding, id: String, text: String,
        closeInput: Boolean, interrupt: Boolean): Snapshot = synchronized(lock) {
        val admission = requireSourceStdin(binding, id)
        check(!admission.consumed && stdinDigest(text) == admission.charsSha256 &&
            closeInput == admission.closeInput && interrupt == admission.interrupt) { "Stdin invocation differs from admission" }
        check(!admission.observationStarted)
        admission.observationStarted = true // one tool invocation, including read-only observations
        admission.entry.toSnapshot()
    }
    internal fun sendSourceInput(binding: SourceStdinBinding, id: String, text: String,
        closeInput: Boolean) = synchronized(lock) {
        val admission = requireSourceStdin(binding, id)
        check(admission.observationStarted && !admission.consumed && !admission.interrupt &&
            admission.closeInput == closeInput && stdinDigest(text) == admission.charsSha256)
        val entry = admission.entry
        check(entry.state == State.RUNNING) { "Command session has already finished: $id" }
        val input = entry.input ?: error("This command session has no open stdin")
        admission.consumed = true
        // No callback, IO or suspension between effect and in-memory evidence.
        if (text.isNotEmpty()) admission.offerAttempted = true
        val offer = input.offerWithEvidence(text)
        if (text.isNotEmpty() && offer is ProcessInputBuffer.OfferAdmission.Rejected) admission.offerRejection = offer.reason
        offer.requireAccepted() // known rejection still preserves the legacy returned error
        if (text.isNotEmpty()) admission.offerAccepted = true
        if (closeInput) {
            admission.eofAttempted = true
            if (entry.requiresTty) input.signalTerminalEof() else input.close()
            admission.eofAccepted = true
        }
    }
    internal suspend fun interruptSource(binding: SourceStdinBinding, id: String): Snapshot? {
        val job = synchronized(lock) {
            val admission = requireSourceStdin(binding, id)
            check(admission.observationStarted && !admission.consumed && admission.interrupt)
            admission.consumed = true
            val entry = admission.entry
            admission.interruptAttempted = true
            if (entry.state == State.RUNNING) {
                entry.state = State.KILLED
                entry.input?.abort()
                entry.finishedAtMs = System.currentTimeMillis()
                entry.changed()
            }
            admission.interruptAccepted = true // request recorded, not process-exit confirmation
            entry.job
        }
        job?.cancelAndJoin()
        return synchronized(lock) { requireSourceStdin(binding, id).entry.toSnapshot() }
    }

    fun sendInput(id: String, text: String, closeInput: Boolean = false) = synchronized(lock) {
        val entry = entries[id] ?: error("Command session not found: $id")
        check(entry.state == State.RUNNING) { "Command session has already finished: $id" }
        val input = entry.input ?: error("This command session has no open stdin")
        input.offer(text)
        if (closeInput) {
            if (entry.requiresTty) input.signalTerminalEof() else input.close()
        }
    }

    suspend fun awaitOutput(id: String, cursor: Long, yieldMs: Long): Snapshot {
        require(cursor >= 0 && yieldMs in 0..30_000) { "Invalid output cursor or yield_time_ms" }
        val entry = synchronized(lock) { entries[id] } ?: error("Command session not found: $id")
        fun current(): Snapshot = snapshot(id) ?: error("Command session not found: $id")
        fun ready(value: Snapshot): Boolean {
            require(cursor <= value.outputEndCursor) { "Output cursor is ahead of this session" }
            return value.state != State.RUNNING || value.outputEndCursor > cursor
        }
        val initial = current()
        if (ready(initial) || yieldMs == 0L) return initial
        // StateFlow rechecks its current value on subscription. Output or exit
        // between the snapshot above and subscription cannot be missed. Quiet
        // builds need only their caller deadline, never a 40 Hz polling loop.
        withTimeoutOrNull(yieldMs) { entry.changes.first { ready(current()) } }
        return current()
    }

    fun list(): List<Snapshot> = synchronized(lock) {
        entries.values.map { it.toSnapshot() }.sortedByDescending { it.createdAtMs }
    }

    /** Bounded caller wait, independent of the process deadline and output activity. */
    suspend fun awaitCompletion(id: String, yieldMs: Long, onOutput: (String) -> Unit): Snapshot {
        require(yieldMs in 0..30_000) { "Invalid yield_time_ms" }
        val deadline = System.nanoTime() + yieldMs * 1_000_000
        var cursor = 0L
        while (true) {
            val value = snapshot(id) ?: error("Command session not found: $id")
            if (value.outputEndCursor > cursor) {
                val first = value.outputEndCursor - value.output.length
                onOutput(value.output.substring((maxOf(cursor, first) - first).toInt()))
                cursor = value.outputEndCursor
            }
            if (value.state != State.RUNNING || System.nanoTime() >= deadline) return value
            val remainingMs = ((deadline - System.nanoTime()).coerceAtLeast(0L) / 1_000_000 + 1)
                .coerceAtMost(yieldMs)
            awaitOutput(id, cursor, remainingMs)
        }
    }

    /** Keep one tool call waiting for final evidence; silence does not kill or restart a build. */
    suspend fun awaitExit(id: String, cursor: Long, onActivity: (String) -> Unit = {}, onOutput: (String) -> Unit): Snapshot {
        var next = cursor
        var nextHeartbeat = System.nanoTime() + heartbeatIntervalMs * 1_000_000
        while (true) {
            val untilHeartbeat = ((nextHeartbeat - System.nanoTime()).coerceAtLeast(0) / 1_000_000 + 1)
                .coerceAtMost(heartbeatIntervalMs)
            val value = awaitOutput(id, next, untilHeartbeat)
            if (value.outputEndCursor > next) {
                val first = value.outputEndCursor - value.output.length
                onOutput(value.output.substring((maxOf(next, first) - first).toInt()))
                next = value.outputEndCursor
            }
            if (value.state != State.RUNNING) return value
            if (System.nanoTime() >= nextHeartbeat) {
                val elapsed = ((System.currentTimeMillis() - value.createdAtMs).coerceAtLeast(0L) / 1_000)
                onActivity("Command still running · ${elapsed}s elapsed · session ${value.id}; waiting for final exit status")
                nextHeartbeat = System.nanoTime() + heartbeatIntervalMs * 1_000_000
            }
        }
    }

    /** Stop only Agent foreground/resumable jobs; explicitly detached jobs retain their lifecycle. */
    fun cancelForegroundCommands() {
        val jobs = synchronized(lock) {
            entries.values.filter { it.stopWithAgent && it.state == State.RUNNING }.map { entry ->
                entry.state = State.KILLED
                entry.input?.abort()
                entry.finishedAtMs = System.currentTimeMillis()
                entry.changed()
                entry.job
            }
        }
        jobs.forEach { it?.cancel(CancellationException("Stopped by Agent user")) }
    }

    suspend fun kill(id: String): Snapshot? {
        val runningJob = synchronized(lock) {
            val entry = entries[id] ?: return null
            if (entry.state == State.RUNNING) {
                entry.state = State.KILLED
                entry.input?.abort()
                entry.finishedAtMs = System.currentTimeMillis()
                entry.changed()
            }
            entry.job
        }
        runningJob?.cancelAndJoin()
        return snapshot(id)
    }

    suspend fun closeAndJoin() {
        close()
        scope.coroutineContext.job.join()
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.filter { it.state == State.RUNNING }.forEach {
                it.state = State.KILLED
                it.input?.abort()
                it.finishedAtMs = System.currentTimeMillis()
                it.changed()
            }
        }
        scope.cancel("Workspace closed")
    }

    private fun appendOutput(id: String, chunk: String) {
        if (chunk.isEmpty()) return
        val clean = Ansi.strip(chunk)
        synchronized(lock) {
            entries[id]?.let { entry ->
                // A late callback from a cancelled backend must not mutate a
                // terminal state or revive its output cursor after Kill/Close.
                if (entry.state != State.RUNNING) return@let
                entry.output = boundedOutput(entry.output + clean)
                entry.outputEndCursor += clean.length
                entry.changed()
            }
        }
    }

    private fun Entry.toSnapshot() = Snapshot(
        id = id,
        state = state,
        command = command,
        output = output,
        exitCode = exitCode,
        timeoutMs = timeoutMs,
        createdAtMs = createdAtMs,
        finishedAtMs = finishedAtMs,
        launchDiskState = launchDiskState,
        outputEndCursor = outputEndCursor,
        stdinAvailable = input?.isClosed == false && state == State.RUNNING,
        interactivePty = requiresTty,
        outputIncomplete = outputIncomplete,
    )

    private fun Entry.changed() { changes.value += 1 }

    private fun boundedOutput(text: String): String {
        val tail = text.takeLast(MAX_LIVE_OUTPUT_CHARS)
        // Cursors count UTF-16 code units. Trimming must discard a whole emoji,
        // rather than leaving its low surrogate at the start of retained output.
        return if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail
    }

    private fun pruneSourceAfterActivationLocked(activated: Entry) {
        while (entries.values.count { !it.inertReservation } > MAX_JOBS) {
            val removable = entries.values.firstOrNull { !it.inertReservation && it !== activated && it.state != State.RUNNING } ?: break
            entries.remove(removable.id)
        }
    }

    private fun pruneLocked() {
        while (entries.values.count { !it.inertReservation } >= MAX_JOBS) {
            val removable = entries.values.firstOrNull { !it.inertReservation && it.state != State.RUNNING } ?: break
            entries.remove(removable.id)
        }
    }

    companion object {
        const val NO_TIMEOUT = 0L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 86_400_000L
        const val MAX_COMMAND_CHARS = 128_000
        const val MAX_LIVE_OUTPUT_CHARS = 20_000
        const val MAX_JOBS = 32
        const val MAX_RUNNING_JOBS = 8
    }
}

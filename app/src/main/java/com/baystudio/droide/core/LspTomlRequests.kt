package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Finite requests belonging to one exact managed TOML owner, never a generic scheduler. */
internal class LspTomlRequests(
    managerScope: CoroutineScope,
    private val onDeadline: (methodKey: String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    companion object {
        const val FEATURE_MS = 30_000L
        const val MAX_RECORDS = 16
        private const val NANOS_PER_MS = 1_000_000L
        private val latestFamilies = setOf("textDocument/completion", "textDocument/hover")
        private val methods = latestFamilies + setOf("textDocument/documentSymbol", "textDocument/rename",
            "textDocument/formatting", "textDocument/semanticTokens/full", "taplo/listSchemas")
    }

    private val ownerJob = SupervisorJob(managerScope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(managerScope.coroutineContext + ownerJob)
    private val lock = Any()
    private val records = LinkedHashSet<Record>()
    private val active = HashMap<String, Record>()
    private val queued = HashMap<String, Record>()
    private var closed = false
    private var deadlinePublicationPending = false // close defers cancellation until the deadline fence publishes

    internal inner class Record(
        val method: String,
        val deadlineNanos: Long,
        val isCurrent: () -> Boolean,
        val propagateFailure: Boolean,
        val block: suspend (Operation) -> Any?,
    ) {
        val waiter = CompletableDeferred<Any?>(ownerJob)
        var worker: Job? = null
        var started = false
        var attached = true
        var latest = true
        var dispatched = false
    }

    class NotAdmitted : Exception("TOML request no longer admitted before the next wire phase")

    /** One absolute budget owns preparation, mutex wait, ordinary native sync and feature drain. */
    inner class Operation internal constructor(private val record: Record) {
        val hasDispatched: Boolean get() = synchronized(lock) { record.dispatched }
        fun isDemandCurrent(): Boolean = record.isCurrent() && synchronized(lock) {
            !closed && record in records && record.attached && record.latest && ownerJob.isActive && nanoTime() < record.deadlineNanos
        }
        fun remainingMs(): Long {
            val remaining = record.deadlineNanos - nanoTime()
            if (remaining <= 0) throw NotAdmitted()
            return ceilMillis(remaining)
        }
        /** Called at the existing serialized writer admission, never at mutex acquisition. */

        fun markDispatched() {
            check(record.isCurrent()) { "TOML demand lost its current capture at serialized admission" }
            synchronized(lock) {
                check(!closed && record in records && record.attached && record.latest && ownerJob.isActive &&
                    nanoTime() < record.deadlineNanos) { "TOML demand detached, superseded or expired at serialized admission" }
                record.dispatched = true
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> owned(methodKey: String, isCurrent: () -> Boolean, propagateFailure: Boolean = true, block: suspend (Operation) -> T): T? {
        require(methodKey in methods) { "Unsupported fixed TOML request: $methodKey" }
        currentCoroutineContext().ensureActive()
        val registeredAt = nanoTime()
        var replaced: Record? = null
        val record = synchronized(lock) {
            if (closed || !ownerJob.isActive) return@synchronized null
            val latestFamily = methodKey in latestFamilies
            if (latestFamily) {
                replaced = queued.remove(methodKey)
                replaced?.let { records.remove(it) }
            }
            if (records.size >= MAX_RECORDS) return@synchronized null
            Record(methodKey, registeredAt + FEATURE_MS * NANOS_PER_MS, isCurrent, propagateFailure, { operation -> block(operation) }).also { item ->
                records.add(item)
                if (latestFamily && active.containsKey(methodKey)) {
                    active[methodKey]?.latest = false
                    queued[methodKey] = item
                } else {
                    if (latestFamily) active[methodKey] = item
                    item.worker = newWorker(item)
                }
            }
        }
        replaced?.waiter?.complete(null)
        if (record == null) return null
        start(record)
        try {
            val result = record.waiter.await()
            currentCoroutineContext().ensureActive()
            return result?.takeIf {
                record.isCurrent() && synchronized(lock) { !closed && record.attached && record.latest && ownerJob.isActive }
            } as T?
        } catch (cancel: CancellationException) {
            detach(record)
            throw cancel
        } catch (interrupted: InterruptedException) {
            detach(record)
            throw interrupted
        }
    }

    fun isIdle(): Boolean = synchronized(lock) { records.isEmpty() }

    private fun newWorker(record: Record): Job = ownerScope.launch(start = CoroutineStart.LAZY) { runRecord(record) }

    private fun start(record: Record) {
        val worker = synchronized(lock) {
            if (record.started || record.worker == null) null else {
                record.started = true
                record.worker
            }
        } ?: return
        // Lazy cancellation must release exactly this record even if its body never ran.
        worker.invokeOnCompletion { finish(record, null) }
        worker.start()
    }

    private sealed interface Outcome {
        data class Result(val value: Any?) : Outcome
        data object Unavailable : Outcome
    }

    private suspend fun runRecord(record: Record) {
        var result: Any? = null
        try {
            val remaining = record.deadlineNanos - nanoTime()
            if (remaining <= 0L) { expireBeforeDispatch(record); return }
            val outcome = withTimeoutOrNull(ceilMillis(remaining)) {
                if (!synchronized(lock) { !closed && record in records && record.attached && record.latest } ||
                    !record.isCurrent()) Outcome.Unavailable
                else if (expireBeforeDispatch(record)) Outcome.Unavailable
                else {
                    currentCoroutineContext().ensureActive()
                    Outcome.Result(record.block(Operation(record)))
                }
            }
            when (outcome) {
                is Outcome.Result -> {
                    // Even a non-suspending completion cannot publish beyond its original budget.
                    if (nanoTime() - record.deadlineNanos >= 0L) { if (record.dispatched) failOwner(record.method) }
                    else if (record.isCurrent()) result = outcome.value
                }
                Outcome.Unavailable -> Unit
                null -> if (record.dispatched) failOwner(record.method)
            }
        } catch (timeout: TimeoutCancellationException) {
            // A nested transport timeout is an owner failure only at our own deadline.
            if (record.dispatched && nanoTime() - record.deadlineNanos >= 0L) failOwner(record.method)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (record.propagateFailure && failure !is NotAdmitted) record.waiter.completeExceptionally(failure)
            // Completed native errors are unavailable; guarded transaction validation can still report a
            // rejection.
        } finally {
            finish(record, result)
        }
    }

    private fun detach(record: Record) {
        synchronized(lock) {
            record.attached = false
            if (queued[record.method] === record) {
                queued.remove(record.method)
                records.remove(record)
            }
        }
        record.waiter.cancel()
        // The active native attempt is independently owned and still consumes its slot.
    }

    private fun finish(record: Record, result: Any?) {
        var next: Record? = null
        val publish = synchronized(lock) {
            if (!records.remove(record)) return
            if (queued[record.method] === record) queued.remove(record.method)
            if (active[record.method] === record) {
                active.remove(record.method)
                if (!closed && ownerJob.isActive) {
                    next = queued.remove(record.method)
                    next?.let { active[record.method] = it; it.worker = newWorker(it) }
                }
            }
            !closed && record.attached && record.latest && ownerJob.isActive
        }
        record.waiter.complete(if (publish) result else null)
        next?.let(::start)
    }

    /** Expired live demand owns the same failure boundary even before its RPC is dispatched. */

    private fun expireBeforeDispatch(record: Record): Boolean {
        if (record.deadlineNanos - nanoTime() > 0L) return false

        return true
    }

    private fun failOwner(methodKey: String, undispatched: Record? = null) {
        val pending = synchronized(lock) {
            if (closed || !ownerJob.isActive || (undispatched != null &&
                (undispatched !in records || !undispatched.attached || !undispatched.latest))) return
            closed = true
            deadlinePublicationPending = true
            records.toList().also { records.clear(); active.clear(); queued.clear() }
        }
        // The callback may acquire its lifetime lock and close this coordinator, so never invoke it
        // under our lock.
        try { onDeadline(methodKey) }
        finally {
            pending.forEach { it.waiter.complete(null) }
            synchronized(lock) { deadlinePublicationPending = false }
            ownerJob.cancel(CancellationException("TOML owned request deadline"))
        }
    }

    fun close() {
        val (pending, cancelOwner) = synchronized(lock) {
            closed = true
            records.toList().also { records.clear(); active.clear(); queued.clear() } to !deadlinePublicationPending
        }
        pending.forEach { it.waiter.complete(null) }
        // A deadline callback may reenter close after fencing the manager. Its finally still owns
        // pending unavailable publication before cancellation of their parent.
        if (cancelOwner) ownerJob.cancel(CancellationException("TOML request owner closed"))
    }

    private fun ceilMillis(nanos: Long): Long = (nanos - 1L) / NANOS_PER_MS + 1L
}

/** Exact managed TOML route. The all-editor ledger observes companions without launching their servers. */
internal class TomlLspManager(
    scope: CoroutineScope,
    private val workspace: File,
    private val host: StdioProcessHost?,
    private val routeCurrent: () -> Boolean,
    private val approve: suspend (LspProjectAnalysisRequest, () -> Boolean) -> Boolean,
    private val onDiagnostics: (Map<String, List<LspDiagnostic>>) -> Unit = {},
    private val routeForPath: (String) -> Boolean = { routeCurrent() },
) : LspManager {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownedScope = CoroutineScope(scope.coroutineContext + job)
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val launchMutex = Mutex()
    private val consent = LspProjectAnalysisConsent()
    private val buffers = LinkedHashMap<String, LspTomlSnapshotAuthority.Overlay>()
    private var revision = 0L
    private var lifetime = 0L
    private var ownerGeneration = 0L
    private var batch = 0L
    private var closed = false
    private var permit: LspProjectAnalysisConsent.Permit? = null
    private var identity: TomlManagedLaunchIdentity? = null
    private var grantRootIdentity: String? = null
    private var session: Session? = null
    private var cleanup: Job? = null
    private var cleanupUnknown = false
    private val knownDependencies = linkedSetOf<String>()
    private var phase = LspProjectAnalysisReadiness.ON_DEMAND
    private var detail = "Enable native read-only TOML analysis for this workspace."
    private val _revision = MutableStateFlow(0L)
    override val statusRevision: StateFlow<Long> = _revision.asStateFlow()
    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()
    private val diagnosticUpdates = kotlinx.coroutines.channels.Channel<Map<String, List<LspDiagnostic>>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val held = hashMapOf<String, LspHeldResultGuard>()
    private var requests = newRequests()
    private fun newRequests(): LspTomlRequests = LspTomlRequests(ownedScope, onDeadline = { synchronized(lock) {
        retireLocked("TOML logical request deadline reached; exact cleanup is required.")
        if (!closed) requests = newRequests()
    } })
    private class Capture(val path: String, val buffers: Map<String, LspTomlSnapshotAuthority.Overlay>, val revision: Long, val lifetime: Long, val tomlPaths: Set<String>) {
        val text: String get() = buffers.getValue(path).text
    }
    private class Session(val identity: TomlManagedLaunchIdentity, val permit: LspProjectAnalysisConsent.Permit,
        val snapshot: LspTomlSnapshotAuthority.Snapshot, val owner: TomlPreparedOwner,
        val initialCapture: Capture, val initialOperation: LspTomlRequests.Operation) {
        var active = true
        var ready = false
        var retiring = false
        var configurationObserved = false
        var observedAtNanos = snapshot.observedAtNanos
        var warning: String? = null
        lateinit var rpc: JsonRpcProcess
        lateinit var sync: LspDocumentSyncAuthority
        lateinit var caps: LspNegotiatedCapabilities
        var process: TomlHostedProcess? = null
        var notifications: Job? = null
        var poller: Job? = null
        val wire = java.util.IdentityHashMap<JsonElement, Ticket>()
        var synchronization: Ticket? = null
        val documentMutex = Mutex()
    }
    private class SessionFailure(val owner: Session, cause: Throwable) : Exception(cause.message, cause)
    private data class Ticket(val capture: Capture, val operation: LspTomlRequests.Operation, val method: String? = null, val line: Int? = null, val column: Int? = null)
    init {
        ownedScope.launch(Dispatchers.Default) { for (value in diagnosticUpdates) onDiagnostics(value) }
    }
    private fun changed() { _revision.value++ }
    /** Nonblocking publication; the external callback always runs outside our admission lock. */
    private fun diagnosticsLocked(value: Map<String, List<LspDiagnostic>>) { _diagnostics.value = value; diagnosticUpdates.trySend(value) }
    private fun absolute(path: String): String = LspTomlSnapshotAuthority.path(workspace.absolutePath, path)
    private fun relative(path: String): String = File(path).relativeTo(workspace).invariantSeparatorsPath
    private fun uri(path: String): String = java.net.URI("file", "", path, null).toASCIIString()
    private fun routeLocked(): Boolean {
        if (routeCurrent()) return true
        if (permit != null || session != null) revokeLocked("TOML route changed; enable its new exact identity separately.")
        return false
    }
    private fun currentCapture(capture: Capture): Boolean = !closed && job.isActive && capture.lifetime == lifetime &&
        capture.revision == revision && capture.buffers == buffers && capture.tomlPaths.all { routeForPath(relative(it)) } && permit?.let(consent::isCurrent) == true && routeLocked()
    private fun current(s: Session): Boolean = !closed && job.isActive && session === s && s.active && permit === s.permit &&
        consent.isCurrent(s.permit) && routeLocked() && s.owner.isCurrent && s.process?.fatalControl == null &&
        s.snapshot.overlaysCurrent(buffers) && (!s.ready || System.nanoTime() - s.observedAtNanos <= LspTomlSnapshotAuthority.OBSERVATION_MAX_AGE_NANOS)
    private fun captureCurrent(s: Session, capture: Capture): Boolean = currentCapture(capture) && current(s)
    private fun guard(s: Session, capture: Capture): LspHeldResultGuard = LspHeldResultGuard { synchronized(lock) { captureCurrent(s, capture) } }
    override fun heldResultGuard(path: String): LspHeldResultGuard? = synchronized(lock) { held[absolute(path)] }
    override fun completionPolicy(path: String): LspCompletionPolicy = LspCompletionPolicy.TOML_NATIVE
    override fun projectAnalysisStatus(path: String): LspProjectAnalysisStatus = synchronized(lock) {
        routeLocked()
        LspProjectAnalysisStatus(LspTomlProfile.ID, permit?.let(consent::isCurrent) == true, workspace.absolutePath,
            LspTomlProfile.FEATURE_NOTE, canRevoke = permit != null || session != null || cleanup?.isActive == true,
            readiness = phase, readinessDetail = detail)
    }
    override fun serverStatus(path: String): String = synchronized(lock) { routeLocked(); "TOML · $detail" }
    fun observeEditorDocument(path: String, text: String) {
        if (SensitivePathPolicy.isSensitive(path)) return
        val full = absolute(path)
        synchronized(lock) {
            if (closed || buffers[full]?.text == text) return
            buffers[full] = LspTomlSnapshotAuthority.Overlay(text, ++revision)
            held.clear(); ++batch; diagnosticsLocked(emptyMap())
            if (session?.snapshot?.entries?.containsKey(full) == true) retireLocked("TOML local dependency changed; the next explicit request uses a fresh snapshot.")
            changed()
        }
    }
    fun closeEditorDocument(path: String) {
        if (SensitivePathPolicy.isSensitive(path)) return
        val full = absolute(path)
        synchronized(lock) {
            if (buffers.remove(full) == null) return
            ++revision; held.clear(); ++batch; diagnosticsLocked(emptyMap())
            if (LspTomlProfile.supportsPath(full) || session?.snapshot?.entries?.containsKey(full) == true)
                retireLocked("TOML editor lifetime closed; the next explicit request replays remaining open buffers.")
            changed()
        }
    }
    override suspend fun didOpen(path: String, text: String) { observeEditorDocument(path, text) }
    override suspend fun didChange(path: String, text: String) { observeEditorDocument(path, text) }
    override suspend fun didClose(path: String) { closeEditorDocument(path) }
    /** A Save is not an authoritative editor publication and cannot overwrite newer typing. */
    fun observeEditorSave(path: String) {
        if (SensitivePathPolicy.isSensitive(path)) return
        val full = absolute(path)
        synchronized(lock) {
            if (closed || full !in buffers) return
            if (session?.snapshot?.entries?.containsKey(full) == true)
                retireLocked("TOML dependency saved; fresh exact snapshot required.")
        }
    }
    override suspend fun didSave(path: String, text: String?) { observeEditorSave(path) }
    override suspend fun didSaveSnapshot(path: String, savedText: String, currentText: String) { observeEditorSave(path) }
    override fun workspaceFilesChanged() { synchronized(lock) { retireLocked("Workspace files changed; fresh exact TOML dependency observation required."); ++lifetime } }
    override suspend fun enableProjectAnalysis(path: String): String {
        val full = absolute(path)
        val overlays = synchronized(lock) { check(!closed && routeLocked()); require(full in buffers); buffers.toMap() }
        val replayPaths = overlays.keys.filter { LspTomlProfile.supportsPath(it) && routeForPath(relative(it)) }.toSet()
        val snapshot = withContext(Dispatchers.IO) { LspTomlSnapshotAuthority.capture(workspace, emptySet(), overlays, replayPaths) }
        val native = withContext(Dispatchers.IO) { requireNotNull(host?.reviewedTomlLaunchIdentity(LspTomlProfile.COMMAND, workspace.absolutePath)) { "Install the exact reviewed Taplo and private Node prerequisites first" } }
        val consentIdentity = LspProjectAnalysisIdentity(workspace.absolutePath, LspTomlProfile.ID, snapshot.rootPath,
            LspTomlSnapshotAuthority.sha(snapshot.rootIdentity + "\u0000" + native.consentIdentity + "\u0000" + LspTomlProfile.settings()))
        val ticket = synchronized(lock) { check(overlays == buffers && routeLocked()); consent.begin(consentIdentity) ?: error("TOML workspace closed") }
        val allowed = approve(LspProjectAnalysisRequest(LspTomlProfile.ID, workspace.absolutePath, snapshot.rootPath,
            listOf(listOf(native.launchExecutable)), LspTomlProfile.APPROVAL_EXPLANATION, LspTomlProfile.INSTALLATION_DISCLOSURE,
            executionDisclosure = "This fixed route reads only admitted local snapshots and denies remote schema fetches. It does not load project modules, compilers or plugins. The reviewed runtime is not an OS sandbox. The grant lasts until Revoke or workspace close.")) {
            synchronized(lock) { !closed && overlays == buffers && routeLocked() && consent.isCurrent(ticket) }
        }
        if (!allowed) return "TOML analysis remains disabled."
        val fresh = withContext(Dispatchers.IO) {
            check(host?.reviewedTomlLaunchIdentity(LspTomlProfile.COMMAND, workspace.absolutePath) == native)
            LspTomlSnapshotAuthority.capture(workspace, emptySet(), overlays, replayPaths)
        }
        synchronized(lock) {
            check(overlays == buffers && routeLocked() && fresh.generation == snapshot.generation)
            retireLocked("TOML analysis enabled; native readiness is established by the next explicit request.")
            permit = consent.approve(ticket) ?: error("TOML approval expired")
            identity = native; grantRootIdentity = fresh.rootIdentity; knownDependencies.clear(); ++lifetime
            phase = LspProjectAnalysisReadiness.ON_DEMAND; changed()
        }
        return retryProjectAnalysis(path)
    }
    override suspend fun retryProjectAnalysis(path: String): String {
        feature(path, "taplo/listSchemas") { _, _, _ -> Unit }
        return synchronized(lock) { detail }
    }
    private fun retireLocked(reason: String) {
        held.clear(); ++batch; diagnosticsLocked(emptyMap())
        val old = session
        detail = reason; phase = LspProjectAnalysisReadiness.FAILED
        if (old == null || !old.active) { changed(); return }
        old.active = false
        old.notifications?.cancel(); old.poller?.cancel()
        val previous = cleanup
        cleanup = retirementScope.launch {
            previous?.join()
            synchronized(lock) { old.retiring = true }
            if (old.process?.fatalControl == null) withTimeoutOrNull(1500) {
                val stopped = runCatching { old.rpc.request("shutdown", timeoutMs = 1000) }.getOrNull()
                if (stopped == JsonNull) runCatching { old.rpc.notify("exit") }
            }
            runCatching { old.rpc.close() }
            val receipt = runCatching { old.owner.retire() }.getOrNull()
            synchronized(lock) {
                if (receipt?.complete == true) { if (session === old) session = null }
                else { cleanupUnknown = true; detail = "TOML owned cleanup is unconfirmed; replacement remains blocked. Revoke remains available." }
                changed()
            }
        }
        changed()
    }
    private fun revokeLocked(reason: String) {
        permit?.let { consent.revoke(it.identity) }; permit = null; identity = null; grantRootIdentity = null; knownDependencies.clear(); ++lifetime
        retireLocked(reason); phase = LspProjectAnalysisReadiness.ON_DEMAND; changed()
    }
    override fun revokeProjectAnalysis(path: String): String = synchronized(lock) { revokeLocked("TOML analysis revoked; exact existing owner cleanup remains owned."); detail }
    override fun retireProjectAnalysisAuthority(): () -> Unit {
        synchronized(lock) { revokeLocked("TOML workspace closed."); closed = true; consent.close(); requests.close() }
        return { diagnosticUpdates.close(); job.cancel() }
    }
    override fun close() { retireProjectAnalysisAuthority().invoke() }
    override suspend fun shutdown() { close(); cleanup?.join() }
    private suspend fun snapshot(capture: Capture, dependencies: Set<String>): LspTomlSnapshotAuthority.Snapshot {
        check(synchronized(lock) { currentCapture(capture) })
        val value = withContext(Dispatchers.IO) { LspTomlSnapshotAuthority.capture(workspace, dependencies, capture.buffers, capture.tomlPaths) }
        synchronized(lock) {
            check(currentCapture(capture)) { "TOML editor capture changed during filesystem observation" }
            check(value.rootIdentity == grantRootIdentity) { "TOML workspace or parent identity changed; revoke and enable a fresh root generation" }
        }
        return value
    }
    private suspend fun <T> feature(path: String, method: String, suppliedText: String? = null, line: Int? = null, column: Int? = null,
        block: suspend (Session, Capture, LspTomlRequests.Operation) -> T): T? {
        val full = absolute(path)
        val capture = synchronized(lock) {
            check(!closed && routeLocked() && permit?.let(consent::isCurrent) == true) { "Enable native TOML analysis first" }
            require(LspTomlProfile.supportsPath(full) && routeForPath(relative(full)) && full in buffers && (suppliedText == null || buffers[full]?.text == suppliedText)) { "TOML requires the exact open editor capture" }
            if (method in LspTomlPositionAdmission.cursorMethods) require(LspTomlPositionAdmission.admits(method, buffers.getValue(full).text,
                requireNotNull(line) - 1, requireNotNull(column) - 1)) { "Native TOML cursor requires an ASCII-next or logical line-end boundary" }
            Capture(full, buffers.toMap(), revision, lifetime, buffers.keys.filter { LspTomlProfile.supportsPath(it) && routeForPath(relative(it)) }.toSet())
        }
        LspTomlSnapshotAuthority.validateReplay(capture.buffers.filterKeys { it in capture.tomlPaths }, workspace.absolutePath)
        return requests.owned(method, { synchronized(lock) { currentCapture(capture) } }) { operation ->
            val paths = synchronized(lock) { knownDependencies.toMutableSet() }
            var rounds = 0
            var value = snapshot(capture, paths)
            while (true) {
                check(operation.isDemandCurrent())
                var own: Session? = null
                try {
                    val s = ensureSession(capture, operation, value); own = s
                    synchronize(s, capture, operation)
                    check(operation.isDemandCurrent())
                    val result = block(s, capture, operation)
                    inspectNativeLog(s)
                    val fresh = snapshot(capture, paths)
                    require(fresh.generation == value.generation) { "TOML dependency changed while the native result was pending" }
                    synchronized(lock) { check(captureCurrent(s, capture)); s.observedAtNanos = fresh.observedAtNanos }
                    return@owned result
                } catch (failure: Exception) {
                    // A poisoned transport can cancel its independent writer while this logical operation remains
                    // live.
                    currentCoroutineContext().ensureActive()
                    val failed = (failure as? SessionFailure)?.owner ?: own ?: synchronized(lock) { session }
                    val control = failed?.process?.fatalControl
                    synchronized(lock) { if (failed != null && session === failed) retireLocked("Native TOML operation unavailable: ${failure.message?.take(160).orEmpty()}") }
                    if (!operation.isDemandCurrent() || control?.kind != "read_miss" || control.reason != "unknown_snapshot_path" || control.path == null) throw failure
                    val missing = LspTomlSnapshotAuthority.path(value.rootPath, control.path)
                    require(rounds < LspTomlSnapshotAuthority.MAX_GROWTH_ROUNDS && missing !in value.entries && paths.add(missing)) { "TOML dependency discovery made no progress or exceeded eight growing rounds" }
                    val unchanged = snapshot(capture, value.dependencyPaths)
                    require(unchanged.generation == value.generation) { "TOML dependency changed during read-miss restart" }
                    value = snapshot(capture, paths); rounds++
                    synchronized(lock) { check(currentCapture(capture)); knownDependencies.add(missing) }
                }
            }
            @Suppress("UNREACHABLE_CODE") error("TOML logical operation ended")
        }
    }
    private suspend fun ensureSession(capture: Capture, operation: LspTomlRequests.Operation,
        snapshot: LspTomlSnapshotAuthority.Snapshot): Session = launchMutex.withLock {
        check(operation.isDemandCurrent())
        synchronized(lock) {
            session?.takeIf { it.ready && current(it) && it.snapshot.generation == snapshot.generation }?.let { it.observedAtNanos = snapshot.observedAtNanos; return@withLock it }
            if (session?.active == true) retireLocked("Preparing a fresh exact TOML dependency owner.")
        }
        cleanup?.join()
        val pair = synchronized(lock) {
            check(!cleanupUnknown && currentCapture(capture) && operation.isDemandCurrent()) { "TOML previous cleanup or current capture unavailable" }
            phase = LspProjectAnalysisReadiness.PREPARING; detail = "Preparing native TOML transport and configuration lock barrier."; changed()
            requireNotNull(identity) to requireNotNull(permit)
        }
        val remote = requireNotNull(host)
        check(withContext(Dispatchers.IO) { remote.reviewedTomlLaunchIdentity(LspTomlProfile.COMMAND, workspace.absolutePath) } == pair.first)
        val owner = remote.prepareReviewedTomlOwner(pair.first, TomlOwnerRequest(workspace.absolutePath, snapshot.rootPath,
            synchronized(lock) { ++ownerGeneration }, LspTomlSnapshotAuthority.sha(pair.second.identity.configuration), snapshot.descriptor()))
        val s = Session(pair.first, pair.second, snapshot, owner, capture, operation)
        try {
            val adapter = object : StdioProcessHost by remote {
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                    require(argv == listOf(s.identity.launchExecutable) && environment.isEmpty() && resourceLimits == null)
                    return remote.startReviewedTomlInWorkspace(s.identity, owner) { create -> synchronized(lock) {
                        check(currentCapture(capture) && session === s && s.active && operation.isDemandCurrent())
                        create().also { s.process = it }
                    } }.also { process ->
                        process.onControl { event ->
                            synchronized(lock) {
                                if (session === s && event.kind == "offline_default_catalog_disabled") {
                                    s.warning = "Default online catalog disabled; local schema readiness is unverified."; changed()
                                } else if (session === s && process.fatalControl != null) {
                                    held.clear(); ++batch; diagnosticsLocked(emptyMap())
                                    detail = "TOML policy/read failure fenced this owner; current logical demand decides bounded retry."; changed()
                                }
                            }
                        }
                    }
                }
            }
            s.rpc = JsonRpcProcess(ownedScope, File(snapshot.rootPath), listOf(s.identity.launchExecutable), processHost = adapter,
                sessionAdmission = { frame -> synchronized(lock) {
                    val method = (frame?.get("method") as? JsonPrimitive)?.content
                    if (s.retiring && method in setOf("shutdown", "exit")) check(session === s && !s.active)
                    else {
                        check(current(s)) { "TOML queued frame lost owner/dependency authority" }
                        val ticket = if (!s.ready) Ticket(capture, operation) else frame?.get("params")?.let { s.wire[it] } ?: s.synchronization
                        if (method != "$/cancelRequest") {
                            requireNotNull(ticket) { "TOML queued frame has no captured operation" }
                            check(captureCurrent(s, ticket.capture))
                            if (ticket.method in LspTomlPositionAdmission.cursorMethods) require(LspTomlPositionAdmission.admits(ticket.method!!,
                                ticket.capture.text, requireNotNull(ticket.line) - 1, requireNotNull(ticket.column) - 1))
                            ticket.operation.markDispatched()
                        }
                    }
                } },
                inboundRequestHandler = { method, params ->
                    try {
                        check(synchronized(lock) { current(s) && !s.ready && currentCapture(capture) && operation.isDemandCurrent() })
                        when (method) {
                            "workspace/configuration" -> {
                                val response = LspTomlProfile.configuration(params, snapshot.rootUri)
                                synchronized(lock) { check(!s.configurationObserved && current(s)); s.configurationObserved = true }
                                response
                            }
                            else -> error("Unexpected native TOML client callback")
                        }
                    } catch (failure: Throwable) {
                        synchronized(lock) { if (session === s && s.active) retireLocked("Native TOML configuration callback was not admitted.") }
                        throw failure
                    }
                })
            synchronized(lock) { check(currentCapture(capture) && operation.isDemandCurrent()); session = s }
            startObservation(s)
            s.notifications = ownedScope.launch { s.rpc.notifications.collect { notice -> notification(s, notice.method, notice.params) } }
            s.rpc.start()
            val initialized = s.rpc.request("initialize", buildJsonObject {
                put("processId", JsonNull); put("rootUri", snapshot.rootUri)
                put("workspaceFolders", buildJsonArray { add(buildJsonObject { put("uri", snapshot.rootUri); put("name", workspace.name) }) })
                put("initializationOptions", LspTomlProfile.initializationOptions()); put("capabilities", LspTomlProfile.clientCapabilities())
            }, minOf(operation.remainingMs(), LspTomlProfile.INITIALIZE_MS), "$/cancelRequest")
            s.caps = LspTomlProfile.capabilities(initialized); s.sync = LspDocumentSyncAuthority(s.rpc, s.caps.sync)
            s.rpc.notify("initialized", buildJsonObject {})
            withTimeout(operation.remainingMs()) { requireNotNull(s.process).awaitConfigurationResponseAccepted() }
            check(synchronized(lock) { current(s) && s.configurationObserved && currentCapture(capture) && operation.isDemandCurrent() })
            s.rpc.request("taplo/listSchemas", buildJsonObject { put("documentUri", uri(capture.path)) }, operation.remainingMs(), "$/cancelRequest")
            inspectNativeLog(s)
            synchronized(lock) {
                check(current(s) && currentCapture(capture) && operation.isDemandCurrent())
                s.ready = true; phase = LspProjectAnalysisReadiness.READY
                detail = "Native transport and configuration lock barrier ready; schema validity and dependency completeness remain unverified." + s.warning?.let { " $it" }.orEmpty(); changed()
            }

            s
        } catch (failure: Throwable) {
            val published = synchronized(lock) { session === s }
            if (published) synchronized(lock) { retireLocked("Native TOML preparation failed; exact cleanup remains owned.") }
            else withContext(NonCancellable) {
                val receipt = runCatching { owner.retire() }.getOrNull()
                if (receipt?.complete != true) synchronized(lock) { cleanupUnknown = true }
            }
            currentCoroutineContext().ensureActive()
            throw SessionFailure(s, failure)
        }
    }
    private fun startObservation(s: Session) {
        s.poller = ownedScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(500)
                    if (!runCatching { inspectNativeLog(s) }.isSuccess) return@launch
                    val overlays = synchronized(lock) { if (!s.active || session !== s) return@launch; buffers.toMap() }
                    val observed = runCatching { LspTomlSnapshotAuthority.capture(workspace, s.snapshot.dependencyPaths, overlays, s.snapshot.documentPaths) }.getOrNull()
                    synchronized(lock) {
                        if (session !== s || !s.active) return@launch
                        if (observed?.generation != s.snapshot.generation || !s.snapshot.overlaysCurrent(buffers)) {
                            retireLocked("TOML exact dependency/root observation changed; held results were withdrawn."); return@launch
                        }
                        s.observedAtNanos = observed.observedAtNanos
                    }
                }
            }
    }
    private suspend fun synchronize(s: Session, capture: Capture, operation: LspTomlRequests.Operation) {
        s.documentMutex.withLock {
            val ticket = Ticket(capture, operation)
            synchronized(lock) { check(captureCurrent(s, capture)); s.synchronization = ticket }
            try {
                LspTomlSnapshotAuthority.validateReplay(capture.buffers.filterKeys { it in capture.tomlPaths }, s.snapshot.rootPath).forEach { (path, overlay) ->
                    check(operation.isDemandCurrent()); s.sync.synchronize(uri(path), "toml", overlay.text)
                }
            } finally { synchronized(lock) { if (s.synchronization === ticket) s.synchronization = null } }
        }
    }
    private suspend fun request(s: Session, capture: Capture, operation: LspTomlRequests.Operation,
        method: String, params: JsonObject, line: Int? = null, column: Int? = null): JsonElement {
        if (method in LspTomlPositionAdmission.cursorMethods) require(LspTomlPositionAdmission.admits(method, capture.text,
            requireNotNull(line) - 1, requireNotNull(column) - 1)) { "Native TOML cursor is unavailable before a non-ASCII scalar; use an ASCII boundary or line end" }
        synchronized(lock) { check(captureCurrent(s, capture)); require(s.wire.size < 16); s.wire[params] = Ticket(capture, operation, method, line, column) }
        try {
            val result = s.rpc.request(method, params, operation.remainingMs(), "$/cancelRequest")
            check(operation.isDemandCurrent() && synchronized(lock) { captureCurrent(s, capture) })
            return result
        } finally { synchronized(lock) { s.wire.remove(params) } }
    }
    private fun params(capture: Capture, line: Int? = null, column: Int? = null, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("textDocument", buildJsonObject { put("uri", uri(capture.path)) })
        if (line != null && column != null) put("position", buildJsonObject { put("line", line - 1); put("character", column - 1) })
        extra()
    }
    override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> = completionItems(path, line, col, null, null)
    override suspend fun completionItems(path: String, line: Int, col: Int, triggerCharacter: String?, currentText: String?): List<LspCompletionItem> =
        feature(path, "textDocument/completion", currentText, line, col) { s, capture, operation ->
            check(s.caps.completion)
            val context = LspTomlCompletionContext.capture(capture.text, line, col) ?: error("TOML cursor requires an ASCII-next or logical EOL boundary")
            val serial = synchronized(lock) { ++batch }
            val raw = request(s, capture, operation, "textDocument/completion", params(capture, line, col) {
                val nativeTrigger = triggerCharacter?.takeIf { it in s.caps.completionTriggerCharacters }
                put("context", buildJsonObject { put("triggerKind", if (nativeTrigger == null) 1 else 2); nativeTrigger?.let { put("triggerCharacter", it) } })
            }, line, col)
            val application = LspCompletionApplicationGuard(capture.text, context.caret) { synchronized(lock) { captureCurrent(s, capture) && batch == serial } }
            LspTomlCompletionContext.parse(raw, context, application, serial).items
        }.orEmpty()
    override suspend fun heldTextResult(op: String, path: String, line: Int, col: Int): LspHeldTextResult {
        if (op == "documentSymbol") return feature(path, "textDocument/documentSymbol") { s, capture, operation ->
            check(s.caps.documentSymbol)
            val heldGuard = guard(s, capture)
            val symbols = LspTomlResultAuthority.symbols(request(s, capture, operation, "textDocument/documentSymbol", params(capture)),
                capture.text, uri(capture.path), relative(capture.path), heldGuard)
            synchronized(lock) { held[capture.path] = heldGuard }
            LspHeldTextResult(if (symbols.isEmpty()) "No native document symbols." else symbols.joinToString("\n") { it.name }, heldGuard)
        } ?: LspHeldTextResult("Native TOML symbols became stale.")
        require(op == "hover")
        return feature(path, "textDocument/hover", line = line, column = col) { s, capture, operation ->
            check(s.caps.hover)
            val raw = request(s, capture, operation, "textDocument/hover", params(capture, line, col), line, col)
            val heldGuard = guard(s, capture)
            synchronized(lock) { held[capture.path] = heldGuard }
            LspHeldTextResult(LspTomlResultAuthority.hover(raw, capture.text), heldGuard)
        } ?: LspHeldTextResult("Native TOML hover became stale.")
    }
    override suspend fun symbolItems(path: String): List<LspSymbol> = feature(path, "textDocument/documentSymbol") { s, capture, operation ->
        check(s.caps.documentSymbol)
        LspTomlResultAuthority.symbols(request(s, capture, operation, "textDocument/documentSymbol", params(capture)), capture.text,
            uri(capture.path), relative(capture.path), guard(s, capture))
    }.orEmpty()
    override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> = feature(path, "textDocument/semanticTokens/full", currentText) { s, capture, operation ->
        check(s.caps.semanticTokens)
        LspTomlResultAuthority.semantic(request(s, capture, operation, "textDocument/semanticTokens/full", params(capture)), capture.text, s.caps.semanticTokenLegend)
    }.orEmpty()
    override suspend fun formatDocument(path: String): LspWorkspaceEdit? =
        formatDocumentWithOptions(path, LspFormattingOptions.DEFAULT)

    override suspend fun formatDocument(path: String, options: LspFormattingOptions): LspWorkspaceEdit? =
        formatDocumentWithOptions(path, options)

    private suspend fun formatDocumentWithOptions(path: String, options: LspFormattingOptions): LspWorkspaceEdit? = feature(path, "textDocument/formatting") { s, capture, operation ->
        check(s.caps.formatting)
        val raw = request(s, capture, operation, "textDocument/formatting", params(capture) { put("options", options.toJson()) })
        LspTomlResultAuthority.edits(raw, true, uri(capture.path), relative(capture.path), capture.text, requireNotNull(s.sync.version(uri(capture.path)))) {
            synchronized(lock) { captureCurrent(s, capture) }
        }
    }
    override suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? {
        require(LspTomlResultAuthority.renameSpelling(newName)) { LspTomlProfile.RENAME_GUIDANCE }
        return feature(path, "textDocument/rename", line = line, column = col) { s, capture, operation ->
            check(s.caps.rename)
            LspTomlResultAuthority.prepareRename(request(s, capture, operation, "textDocument/prepareRename", params(capture, line, col), line, col), capture.text)
            val raw = request(s, capture, operation, "textDocument/rename", params(capture, line, col) { put("newName", newName) }, line, col)
            LspTomlResultAuthority.edits(raw, false, uri(capture.path), relative(capture.path), capture.text, requireNotNull(s.sync.version(uri(capture.path)))) {
                synchronized(lock) { captureCurrent(s, capture) }
            }
        }
    }
    /** Stderr is a bounded availability hint only, never ordered provenance or schema validity. */
    private fun inspectNativeLog(s: Session) {
        val log = s.rpc.stderr
        val failure = listOf("failed to collect schemas", "failed to resolve schema", "schema resolution failed", "schema validation failed",
            "failed to fetch schema", "invalid configuration", "failed to update workspace", "failed to fetch configuration")
            .firstOrNull { log.contains(it, ignoreCase = true) } ?: return
        synchronized(lock) { if (session === s && s.active) retireLocked("Native TOML reported $failure; configured assistance is unavailable. No schema-disabled fallback was used.") }
        error("Native TOML configuration/schema failure: $failure")
    }
    private fun notification(s: Session, method: String, params: JsonElement?) {
        if (method == "textDocument/publishDiagnostics") {
            val target = ((params as? JsonObject)?.get("uri") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            val captured = synchronized(lock) {
                if (!s.ready || !current(s)) return
                val path = runCatching { LspTomlProfile.requireOwnedUri(target, s.snapshot.rootPath) }.getOrNull() ?: return
                if (!LspTomlProfile.supportsPath(path) || !routeForPath(relative(path))) return
                path to (buffers[path] ?: return)
            }
            val diagnostics = runCatching { LspTomlResultAuthority.diagnostics(params, target, captured.second.text) }.getOrNull() ?: return
            synchronized(lock) {
                if (current(s) && buffers[captured.first] === captured.second && diagnostics.isNotEmpty())
                    diagnosticsLocked(_diagnostics.value + (target to diagnostics))
                // An empty unversioned push does not certify cleanliness or erase a known failure.
            }
        } else if (method in setOf("window/logMessage", "window/showMessage")) {
            val obj = params as? JsonObject ?: return
            val level = (obj["type"] as? JsonPrimitive)?.intOrNull
            val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            if (level in 1..3) synchronized(lock) {
                if (current(s)) { s.warning = message.filter { !it.isISOControl() || it == '\n' }.take(500)
                    detail = "Native TOML warning: ${s.warning}. Configuration/schema validity remains unverified."; changed() }
            }
        }
    }
    override suspend fun diagnose(relPath: String): String {
        feature(relPath, "taplo/listSchemas") { _, _, _ -> Unit }
        val reports = synchronized(lock) { _diagnostics.value[uri(absolute(relPath))].orEmpty() }
        return LspTomlProfile.DIAGNOSTIC_QUALIFICATION + ". " + if (reports.isEmpty()) "No current advisory report; a clean/schema-valid document was not certified."
            else reports.joinToString("\n") { "${it.line}:${it.column}: ${it.message}" }
    }
    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = when (op) {
        "hover" -> heldTextResult(op, path, line, col).text
        "completion" -> completionItems(path, line, col).joinToString("\n") { it.label }
        "documentSymbol" -> symbolItems(path).joinToString("\n") { it.name }
        else -> "This native TOML feature is unavailable; no command or code action was executed."
    }
}

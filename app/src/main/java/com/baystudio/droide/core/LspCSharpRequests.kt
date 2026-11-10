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

/** Finite requests belonging to one exact managed C# owner, never a generic scheduler. */
internal class LspCSharpRequests(
    managerScope: CoroutineScope,
    private val onDeadline: (methodKey: String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    companion object {
        const val FEATURE_MS = 15_000L
        const val MAX_RECORDS = 16
        private const val NANOS_PER_MS = 1_000_000L
        private val latestFamilies = setOf("textDocument/completion", "textDocument/signatureHelp", "textDocument/hover")
        private val methods = latestFamilies + setOf(
            "textDocument/definition", "textDocument/declaration", "textDocument/typeDefinition", "textDocument/references",
            "textDocument/documentSymbol", "workspace/symbol", "textDocument/rename",
            "textDocument/formatting", "textDocument/diagnostic", "textDocument/implementation",
        )
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

    class NotAdmitted : Exception("C# request no longer admitted before the next wire phase")

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
            check(record.isCurrent()) { "C# demand lost its current capture at serialized admission" }
            synchronized(lock) {
                check(!closed && record in records && record.attached && record.latest && ownerJob.isActive &&
                    nanoTime() < record.deadlineNanos) { "C# demand detached, superseded or expired at serialized admission" }
                record.dispatched = true
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> owned(methodKey: String, isCurrent: () -> Boolean, propagateFailure: Boolean = true, block: suspend (Operation) -> T): T? {
        require(methodKey in methods) { "Unsupported fixed C# request: $methodKey" }
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
            ownerJob.cancel(CancellationException("C# owned request deadline"))
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
        if (cancelOwner) ownerJob.cancel(CancellationException("C# request owner closed"))
    }

    private fun ceilMillis(nanos: Long): Long = (nanos - 1L) / NANOS_PER_MS + 1L
}

internal class CSharpLspManager(
    scope: CoroutineScope,
    private val workspace: File,
    private val host: StdioProcessHost?,
    private val routeCurrent: () -> Boolean,
    private val approve: suspend (LspProjectAnalysisRequest, () -> Boolean) -> Boolean,
    private val onDiagnostics: (Map<String, List<LspDiagnostic>>) -> Unit = {},
) : LspManager {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownedScope = CoroutineScope(scope.coroutineContext + job)
    private val lock = Any()
    private val launchMutex = Mutex()
    private val consent = LspProjectAnalysisConsent()
    private val selection = CSharpAnalysisSelections(workspace) { root, solution ->
        // Metadata only; Save invokes this on IO, never from a callback or admission lock.
        runBlocking { requireNotNull(host?.inspectCSharpSelection(workspace.path, root, solution)) { "This host cannot inspect the managed C# selection" }.identity }
    }
    private class Buffer(val text: String, val revision: Long)
    private val buffers = LinkedHashMap<String, Buffer>()
    private var revision = 0L
    private var generation = 0L
    private var batch = 0L
    private var closed = false
    private var permit: LspProjectAnalysisConsent.Permit? = null
    private var grantIdentity: CSharpManagedLaunchIdentity? = null
    private var session: Session? = null
    private var startup: Job? = null
    private var cleanup: Job? = null
    private var cleanupUnknown = false
    private var phase = LspProjectAnalysisReadiness.ON_DEMAND
    private var detail = "Save a selection, then enable native project analysis."
    private val _revision = MutableStateFlow(0L)
    override val statusRevision: StateFlow<Long> = _revision.asStateFlow()
    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()
    private fun publishDiagnostics(value: Map<String, List<LspDiagnostic>>) { _diagnostics.value = value; onDiagnostics(value) }
    private val held = HashMap<String, LspHeldResultGuard>()
    private data class Diagnostic(val buffer: Buffer, val report: LspCSharpDiagnostics.Report)
    private val reports = HashMap<String, Diagnostic>()
    private data class Completion(val context: LspCSharpCompletionContext.Context, val incomplete: Boolean, val session: Session)
    private val completions = HashMap<String, Completion>()
    private class Session(val selection: CSharpAnalysisSelections.Selection, val inspection: CSharpSelectionInspection,
        val identity: CSharpManagedLaunchIdentity, val permit: LspProjectAnalysisConsent.Permit,
        val generation: Long, val owner: CSharpPreparedOwner, val preparationDeadline: Long) {
        var active = true
        var ready = false
        var retiring = false
        lateinit var rpc: JsonRpcProcess
        lateinit var requests: LspCSharpRequests
        lateinit var caps: LspNegotiatedCapabilities
        lateinit var sync: LspDocumentSyncAuthority
        var process: CSharpHostedProcess? = null
        var notifications: Job? = null
        var warning: String? = null
        val wire = java.util.IdentityHashMap<JsonElement, Ticket>()
        var synchronization: Ticket? = null
        val mutex = Mutex()
    }
    private data class Capture(val path: String, val buffers: Map<String, Buffer>) {
        val text: String get() = buffers.getValue(path).text
        val texts: Map<String, String> get() = buffers.mapValues { it.value.text }
    }
    private data class Ticket(val capture: Capture, val operation: LspCSharpRequests.Operation)
    private fun changed() { _revision.value++ }
    private fun absolute(path: String): String = PathSecurity.resolveWithin(workspace, path).absoluteFile.normalize().path
    private fun relative(path: String): String = File(path).relativeTo(workspace).invariantSeparatorsPath
    private fun uri(path: String): String = File(path).toURI().toString()
    private fun current(s: Session): Boolean = !closed && job.isActive && session === s && s.active && s.generation == generation &&
        (s.ready || System.nanoTime() < s.preparationDeadline) && selection.isCurrent(s.selection) && permit === s.permit && consent.isCurrent(s.permit) && observeRoute() && s.owner.isCurrent

    private fun observeRoute(): Boolean {
        if (routeCurrent()) return true
        if (permit != null || session?.active == true || startup?.isActive == true)
            revokeLocked("C# route changed; the old grant and owner are retired. Enable the selected route separately.")
        return false
    }
    private fun captureCurrent(s: Session, capture: Capture): Boolean = current(s) && capture.buffers ==
        buffers.filterKeys { CSharpAnalysisSelections.contains(s.selection.rootPath, it) }
    private fun heldGuard(s: Session, capture: Capture): LspHeldResultGuard = LspHeldResultGuard { synchronized(lock) { captureCurrent(s, capture) } }
    override fun heldResultGuard(path: String): LspHeldResultGuard? = synchronized(lock) { held[absolute(path)] }
    override fun completionPolicy(path: String): LspCompletionPolicy = LspCompletionPolicy.CSHARP_NATIVE
    override fun csharpSelectionStatus(path: String): CSharpAnalysisSelections.Status = selection.status(File(absolute(path)))
    override suspend fun saveCsharpSelection(path: String, rootPath: String, mode: CSharpAnalysisSelections.Mode, solutionPath: String?): String {
        val prior = selection.current()
        val candidate = withContext(Dispatchers.IO) { selection.validate(File(absolute(path)), rootPath, mode, solutionPath) }
        synchronized(lock) {
            check(!closed && routeCurrent())
            check(selection.current() === prior) { "C# selection changed during validation" }
            // Fence before publishing replacement; cleanup still owns the exact old session.
            revokeLocked("C# selection saved. Enable analysis separately.")
            selection.commit(candidate, prior)
            changed()
        }
        return "C# selection saved after metadata-only checks. Enable project analysis separately."
    }
    override fun projectAnalysisStatus(path: String): LspProjectAnalysisStatus = synchronized(lock) {
        observeRoute()
        val status = selection.status(File(absolute(path)))
        val granted = permit?.let(consent::isCurrent) == true
        LspProjectAnalysisStatus(LspCSharpProfile.ID, granted && status.available && routeCurrent(), status.selection?.rootPath.orEmpty(),
            LspCSharpProfile.FEATURE_NOTE, canRevoke = permit != null || session != null || startup?.isActive == true,
            readiness = phase, readinessDetail = if (!status.available) status.message else detail)
    }
    override fun serverStatus(path: String): String = synchronized(lock) { "C# · $detail" }
    override suspend fun enableProjectAnalysis(path: String): String {
        val selected = requireNotNull(selection.current()) { "Save a C# analysis selection first" }
        require(selection.status(File(absolute(path))).available) { "C# selection is unavailable for this document" }
        val native = withContext(Dispatchers.IO) {
            check(selection.revalidate(selected))
            requireNotNull(host?.reviewedCSharpLaunchIdentity(LspCSharpProfile.COMMAND, workspace.path)) { "Install all three reviewed C# prerequisites before enabling" }
        }
        val identity = LspProjectAnalysisIdentity(workspace.path, LspCSharpProfile.ID, selected.rootPath,
            sha(selected.stableIdentity + "\u0000" + native.consentIdentity + "\u0000" + LspCSharpProfile.section(selected.solutionPath)))
        val ticket = synchronized(lock) { check(!closed && selection.isCurrent(selected) && routeCurrent()); consent.begin(identity) ?: error("C# workspace closed") }
        val allowed = approve(LspProjectAnalysisRequest(LspCSharpProfile.ID, workspace.path, selected.rootPath,
            listOf(listOf(native.launchExecutable)), LspCSharpProfile.APPROVAL_EXPLANATION + "\nSelection: " + selected.mode + " " + selected.solutionPath.orEmpty() +
                if (selected.mode == CSharpAnalysisSelections.Mode.NATIVE_DIRECTORY_DISCOVERY) "\nNative recursive discovery prefers discovered .sln/.slnx by declared project count, otherwise all discovered .csproj/.fsproj outside node_modules. It is not selected-file-only or offline analysis." else "",
            LspCSharpProfile.INSTALLATION_DISCLOSURE)) { synchronized(lock) { !closed && selection.isCurrent(selected) && routeCurrent() && consent.isCurrent(ticket) } }
        if (!allowed) return "C# project analysis remains disabled."

        withContext(Dispatchers.IO) { check(selection.revalidate(selected)); check(host?.reviewedCSharpLaunchIdentity(LspCSharpProfile.COMMAND, workspace.path) == native) }
        synchronized(lock) {
            check(selection.isCurrent(selected) && routeCurrent())
            permit = consent.approve(ticket) ?: error("C# approval expired")
            grantIdentity = native
        }
        return retryProjectAnalysis(path)
    }
    override suspend fun retryProjectAnalysis(path: String): String {
        val selected = requireNotNull(selection.current()) { "Save a C# selection first" }
        check(selection.status(File(absolute(path))).available) { "C# selection unavailable; Revoke or save a new selection" }
        val granted = synchronized(lock) { requireNotNull(permit?.takeIf(consent::isCurrent)) { "Enable C# project analysis first" } }
        synchronized(lock) {
            check(!closed && routeCurrent() && selection.isCurrent(selected) && permit === granted)
            fenceLocked("Preparing native C# model; completeness remains unverified.")
            phase = LspProjectAnalysisReadiness.PREPARING
            val attempt = ++generation
            val preparationDeadline = System.nanoTime() + LspCSharpProfile.PREPARATION_MS * 1_000_000L
            startup = ownedScope.launch { start(selected, requireNotNull(grantIdentity), granted, attempt, preparationDeadline) }
            changed()
        }
        return "Preparing native C# analysis. MSBuild project loading may execute trusted project code; Revoke stops the owned attempt."
    }
    private suspend fun start(selected: CSharpAnalysisSelections.Selection, native: CSharpManagedLaunchIdentity,
        granted: LspProjectAnalysisConsent.Permit, attempt: Long, deadline: Long) {
        try {
            withTimeout(((deadline - System.nanoTime() + 999_999L) / 1_000_000L).coerceAtLeast(0L)) {
                launchMutex.withLock { startLocked(selected, native, granted, attempt, deadline) }
            }
        } catch (timeout: TimeoutCancellationException) {
            synchronized(lock) { if (!closed && generation == attempt) {
                session?.takeIf { it.generation == attempt }?.let { fail(it, "C# total preparation deadline reached; replacement awaits exact cleanup.") }
                phase = LspProjectAnalysisReadiness.FAILED
                detail = "C# total preparation deadline reached, including queued cleanup. Retry only after confirmed retirement."
                changed()
            } }
        }
    }
    private suspend fun startLocked(selected: CSharpAnalysisSelections.Selection, native: CSharpManagedLaunchIdentity,
        granted: LspProjectAnalysisConsent.Permit, attempt: Long, deadline: Long) {
        var prepared: CSharpPreparedOwner? = null
        var own: Session? = null
        try {
            withTimeout(((deadline - System.nanoTime() + 999_999L) / 1_000_000L).coerceAtLeast(0L)) {
                run {
                    cleanup?.join()
                    synchronized(lock) { check(!cleanupUnknown && !closed && attempt == generation && permit === granted && consent.isCurrent(granted)) { "C# previous cleanup remains unconfirmed" } }
                    val inspection = withContext(Dispatchers.IO) {
                        check(selection.revalidate(selected))
                        requireNotNull(host?.inspectCSharpSelection(workspace.path, selected.rootPath, selected.solutionPath))
                    }
                    require(inspection.rootGuestAbsolute == selected.rootPath && inspection.solutionGuestAbsolute == selected.solutionPath) {
                        "This managed C# route requires its exact local Ubuntu identity mapping"
                    }
                    val remote = requireNotNull(host)
                    check(remote.reviewedCSharpLaunchIdentity(LspCSharpProfile.COMMAND, workspace.path) == native)
                    val owner = remote.prepareReviewedCSharpOwner(native, CSharpOwnerRequest(workspace.path, selected.rootPath, attempt,
                        sha(granted.identity.configuration), solutionPath = selected.solutionPath))
                    prepared = owner
                    val s = Session(selected, inspection, native, granted, attempt, owner, deadline); own = s
                    val section = LspCSharpProfile.section(inspection.solutionGuestAbsolute)
                    val rootUri = uri(inspection.rootGuestAbsolute)
                    val adapter = object : StdioProcessHost by remote {
                        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                            require(argv == listOf(native.launchExecutable) && environment.isEmpty() && resourceLimits == null)
                            return remote.startReviewedCSharpInWorkspace(native, owner) { create ->
                                synchronized(lock) { check(current(s)); create().also { s.process = it } }
                            }
                        }
                    }
                    s.requests = LspCSharpRequests(ownedScope, onDeadline = { fail(s, "C# native feature deadline reached; Retry requires confirmed cleanup.") })
                    s.rpc = JsonRpcProcess(ownedScope, File(selected.rootPath), listOf(native.launchExecutable), processHost = adapter,
                        sessionAdmission = { frame -> synchronized(lock) {
                            val method = (frame?.get("method") as? JsonPrimitive)?.content
                            val cleanupOnly = s.retiring && method in setOf("shutdown", "exit")
                            if (cleanupOnly) check(!s.active && session === s) { "C# cleanup lost its exact retired owner" }
                            else check(current(s)) { "C# owner or stable grant retired" }
                            if (!cleanupOnly && s.ready && method != null && method !in setOf("$/cancelRequest", "shutdown", "exit")) {
                                val params = frame["params"]
                                val ticket = params?.let { s.wire[it] } ?: s.synchronization
                                check(ticket != null && captureCurrent(s, ticket.capture)) { "C# queued frame lost its capture" }
                                ticket.operation.markDispatched()
                            }
                        } },
                        inboundRequestHandler = { method, params ->
                            synchronized(lock) { check(current(s)) }
                            when (method) {
                                "workspace/configuration" -> LspCSharpProfile.configuration(params, rootUri, section)
                                "workspace/workspaceFolders" -> buildJsonArray { add(buildJsonObject { put("uri", rootUri); put("name", File(selected.rootPath).name) }) }
                                "workspace/applyEdit" -> buildJsonObject { put("applied", false); put("failureReason", "C# server-driven edits require explicit review") }
                                "window/showMessageRequest" -> { synchronized(lock) { detail = "Native C# requested a user action; automatic actions are unavailable. Project completeness remains unverified."; changed() }; JsonNull }
                                else -> error("Unsupported C# client callback")
                            }
                        })
                    synchronized(lock) {
                        check(!closed && generation == attempt && permit === granted && consent.isCurrent(granted) && selection.isCurrent(selected) && routeCurrent())
                        session = s
                    }
                    s.notifications = ownedScope.launch {
                        s.rpc.notifications.collect { notification ->
                            if (notification.method in setOf("window/logMessage", "window/showMessage")) {
                                val p = notification.params as? JsonObject
                                val message = (p?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
                                val level = (p?.get("type") as? JsonPrimitive)?.intOrNull
                                if (message != null && level in 1..3) synchronized(lock) {
                                    if (current(s)) {
                                        s.warning = message.filter { !it.isISOControl() || it == '\n' }.take(500)
                                        detail = "Native C# warning: " + s.warning + " Project completeness remains unverified."
                                        changed()
                                    }
                                }
                            }
                        }
                    }
                    s.rpc.start()
                    val initialized = s.rpc.request("initialize", buildJsonObject {
                        put("processId", JsonNull); put("rootUri", rootUri)
                        put("workspaceFolders", buildJsonArray { add(buildJsonObject { put("uri", rootUri); put("name", File(selected.rootPath).name) }) })
                        put("capabilities", LspCSharpProfile.clientCapabilities())
                    }, LspCSharpProfile.INITIALIZE_MS, "$/cancelRequest")
                    s.caps = LspCSharpProfile.capabilities(initialized)
                    s.sync = LspDocumentSyncAuthority(s.rpc, s.caps.sync)
                    s.rpc.notify("initialized", buildJsonObject {})
                    s.rpc.notify("workspace/didChangeConfiguration", LspCSharpProfile.settings(section))
                    synchronized(lock) {
                        check(current(s)); s.ready = true
                        phase = LspProjectAnalysisReadiness.READY
                        detail = "Transport initialized; preparing native model. No current-document semantic result yet; project completeness unverified." +
                            s.warning?.let { " Native warning: $it" }.orEmpty()
                        changed()
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (generation == attempt && !closed) {
                    phase = LspProjectAnalysisReadiness.FAILED
                    detail = "Native C# preparation unavailable. Review selection, prerequisites and owned cleanup, then Retry."
                    changed()
                }
            }
            own?.let { fail(it, "Native C# preparation failed; project completeness remains unverified.") }
            if (own == null || synchronized(lock) { session !== own }) prepared?.let { owner ->
                withContext(NonCancellable) { val receipt = owner.retire(); synchronized(lock) { if (!receipt.complete) cleanupUnknown = true } }
            }
            if (failure is CancellationException) throw failure
        }
    }
    private fun fenceLocked(reason: String) {
        generation++
        startup?.cancel(); startup = null
        held.clear(); completions.clear(); reports.clear(); publishDiagnostics(emptyMap())
        val old = session
        old?.active = false
        old?.requests?.close()
        old?.notifications?.cancel()
        detail = reason
        if (old != null) {
            val previous = cleanup
            cleanup = retirementScope.launch {
                previous?.join()
                // Feature/callback authority was already fenced. Only this exact old transport gets a bounded
                // shutdown/exit grace; no replacement can exist before receipt.
                synchronized(lock) { old.retiring = true }
                withTimeoutOrNull(2_000L) {
                    runCatching { old.rpc.request("shutdown", timeoutMs = 1_500L) }
                    runCatching { old.rpc.notify("exit") }
                }
                runCatching { old.rpc.close() }
                val receipt = runCatching { old.owner.retire() }.getOrNull()
                synchronized(lock) {
                    if (receipt?.complete == true) { if (session === old) session = null }
                    else { cleanupUnknown = true; detail = "C# cleanup is unconfirmed; replacement is blocked. Revoke remains available." }
                    changed()
                }
            }
        }
    }
    private fun fail(s: Session, reason: String) = synchronized(lock) {
        if (session === s && s.active) { fenceLocked(reason); phase = LspProjectAnalysisReadiness.FAILED; changed() }
    }
    private fun revokeLocked(reason: String) {
        permit?.let { consent.revoke(it.identity) }; permit = null; grantIdentity = null
        fenceLocked(reason); phase = LspProjectAnalysisReadiness.ON_DEMAND; changed()
    }
    override fun revokeProjectAnalysis(path: String): String = synchronized(lock) {
        revokeLocked("C# project analysis revoked; exact owned cleanup is pending if a process was started."); detail
    }
    override fun retireProjectAnalysisAuthority(): () -> Unit {
        synchronized(lock) { revokeLocked("C# workspace authority closed."); closed = true; selection.close(); consent.close() }
        return { job.cancel() /* independent exact retirement remains owned until confirmed */ }
    }
    override fun close() { retireProjectAnalysisAuthority().invoke(); job.cancel() }
    override suspend fun shutdown() { close(); cleanup?.join(); job.cancel() }
    override fun workspaceFilesChanged() { synchronized(lock) { revokeLocked("Workspace files changed; review and enable C# analysis again.") } }
    override suspend fun didOpen(path: String, text: String) { update(path, text) }
    override suspend fun didChange(path: String, text: String) { update(path, text) }
    private fun update(path: String, text: String) {
        if (!LspCSharpProfile.supportsPath(path)) return
        val full = absolute(path)
        synchronized(lock) {
            if (closed || buffers[full]?.text == text) return
            require(buffers.size < 128 || full in buffers) { "C# captured-buffer limit exceeded" }
            buffers[full] = Buffer(text, ++revision)
            held.clear()
            reports.remove(full); publishDiagnostics(_diagnostics.value - uri(full))
            // Preserve only the incomplete-session lexical basis, never old completion items or authority.
        }
    }
    override suspend fun didSave(path: String, text: String?) {
        text?.let { update(path, it) }
        synchronized(lock) { fenceLocked("C# source saved; Retry native analysis with current buffers."); phase = LspProjectAnalysisReadiness.FAILED; changed() }
    }
    override suspend fun didSaveSnapshot(path: String, savedText: String, currentText: String) { didSave(path, currentText) }
    override suspend fun didClose(path: String) {
        synchronized(lock) {
            val full = absolute(path); buffers.remove(full); completions.remove(full); reports.remove(full); held.clear()
            publishDiagnostics(_diagnostics.value - uri(full))
            // Native didClose is an ordinary owned write; retire rather than leave an unowned queued close.
            fenceLocked("C# document closed; Retry to synchronize the remaining captured documents."); phase = LspProjectAnalysisReadiness.FAILED; changed()
        }
    }
    private suspend fun <T> feature(path: String, method: String, suppliedText: String? = null,
        block: suspend (Session, Capture, LspCSharpRequests.Operation) -> T): T? {
        val full = absolute(path)
        val pair = synchronized(lock) {
            val s = session?.takeIf { it.ready && current(it) } ?: throw LspProjectAnalysisNotReadyException(
                if (phase == LspProjectAnalysisReadiness.PREPARING) phase else LspProjectAnalysisReadiness.FAILED, detail)
            val capture = Capture(full, buffers.filterKeys { CSharpAnalysisSelections.contains(s.selection.rootPath, it) })
            check(full in capture.buffers && (suppliedText == null || suppliedText == capture.text)) { "C# requires the exact current open document" }
            check(capture.buffers.values.all { LspCSharpResultAuthority.validText(it.text) && it.text.length <= 1_048_576 })
            s to capture
        }
        val (s, capture) = pair
        return s.requests.owned(method, { synchronized(lock) { captureCurrent(s, capture) } }) { operation ->
            try { withContext(Dispatchers.IO) { check(selection.revalidate(s.selection)) } }
            catch (failure: Exception) { fail(s, "C# selection or SDK policy changed; save and enable again."); throw failure }
            check(operation.isDemandCurrent())
            s.mutex.withLock {
                val ticket = Ticket(capture, operation)
                synchronized(lock) { check(captureCurrent(s, capture)); s.synchronization = ticket }
                try {
                    capture.buffers.forEach { (document, buffer) ->
                        check(operation.isDemandCurrent())
                        s.sync.synchronize(uri(document), "csharp", buffer.text)
                    }
                } finally { synchronized(lock) { if (s.synchronization === ticket) s.synchronization = null } }
            }
            check(operation.isDemandCurrent())
            block(s, capture, operation)
        }
    }
    private suspend fun request(s: Session, capture: Capture, operation: LspCSharpRequests.Operation,
        method: String, params: JsonObject): JsonElement {
        synchronized(lock) { check(captureCurrent(s, capture)); check(s.wire.size < 16); s.wire[params] = Ticket(capture, operation) }
        try {
            val result = s.rpc.request(method, params, operation.remainingMs(), "$/cancelRequest")
            check(operation.isDemandCurrent() && synchronized(lock) { captureCurrent(s, capture) })
            return result
        } finally { synchronized(lock) { s.wire.remove(params) } }
    }
    private fun params(capture: Capture, line: Int? = null, col: Int? = null, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("textDocument", buildJsonObject { put("uri", uri(capture.path)) })
        if (line != null && col != null) {
            require(LspCSharpResultAuthority.validPosition(capture.text, line - 1, col - 1))
            put("position", buildJsonObject { put("line", line - 1); put("character", col - 1) })
        }; extra()
    }
    override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> = completionItems(path, line, col, null, null)
    override suspend fun completionItems(path: String, line: Int, col: Int, triggerCharacter: String?, currentText: String?): List<LspCompletionItem> =
        feature(path, "textDocument/completion", currentText) { s, capture, operation ->
            check(s.caps.completion)
            val context = LspCSharpCompletionContext.capture(capture.text, line, col) ?: error("C# completion lexical context is unavailable")
            val serial = synchronized(lock) { ++batch }
            val prior = synchronized(lock) { completions[capture.path] }
            val kind = if (prior?.session === s && prior.incomplete && context.compatible(prior.context)) 3
                else if (triggerCharacter != null && triggerCharacter in s.caps.completionTriggerCharacters) 2 else 1
            val raw = request(s, capture, operation, "textDocument/completion", params(capture, line, col) {
                put("context", buildJsonObject { put("triggerKind", kind); if (kind == 2) put("triggerCharacter", triggerCharacter!!) })
            })
            val guard = LspCompletionApplicationGuard(capture.text, context.caret) { synchronized(lock) { captureCurrent(s, capture) && batch == serial } }
            val result = LspCSharpCompletionContext.parse(raw, context, guard, serial)
            synchronized(lock) { check(captureCurrent(s, capture)); completions[capture.path] = Completion(context, result.incomplete, s) }
            result.items
        }.orEmpty()
    override fun signatureHelpTriggers(path: String): LspSignatureTriggers = synchronized(lock) {
        session?.takeIf { it.ready && current(it) }?.let { LspSignatureTriggers(it.caps.signatureHelpTriggerCharacters, it.caps.signatureHelpRetriggerCharacters) } ?: LspSignatureTriggers()
    }
    override suspend fun signatureHelp(path: String, line: Int, col: Int, currentText: String?, triggerKind: LspSignatureTriggerKind,
        triggerCharacter: String?, isRetrigger: Boolean, previous: LspSignatureHelp?): LspSignatureHelp? =
        feature(path, "textDocument/signatureHelp", currentText) { s, capture, operation ->
            check(s.caps.signatureHelp)
            val acceptedTrigger = triggerCharacter?.takeIf { it in s.caps.signatureHelpTriggerCharacters || isRetrigger && it in s.caps.signatureHelpRetriggerCharacters }
            val acceptedKind = if (triggerKind == LspSignatureTriggerKind.TRIGGER_CHARACTER && acceptedTrigger == null) LspSignatureTriggerKind.INVOKED else triggerKind
            val prior = previous?.takeIf { it.applicationGuard?.accepts(capture.text, LspCompletionCaret(line, col), false) == true }
            val raw = request(s, capture, operation, "textDocument/signatureHelp", params(capture, line, col) {
                put("context", LspSignatureHelpProtocol.context(acceptedKind, acceptedTrigger, isRetrigger && prior != null, prior))
            })
            LspSignatureHelpProtocol.parse(LspCSharpResultAuthority.signatureHelp(raw))?.copy(
                applicationGuard = LspCompletionApplicationGuard(capture.text, LspCompletionCaret(line, col)) { synchronized(lock) { captureCurrent(s, capture) } })
        }
    override suspend fun heldTextResult(op: String, path: String, line: Int, col: Int): LspHeldTextResult {
        require(op == "hover") { "Unsupported C# text operation" }
        return feature(path, "textDocument/hover") { s, capture, operation ->
            check(s.caps.hover)
            val raw = request(s, capture, operation, "textDocument/hover", params(capture, line, col))
            val obj = raw as? JsonObject
            obj?.get("range")?.let { require(LspCSharpResultAuthority.validRange(capture.text, it)) }
            fun text(value: JsonElement?): String = when (value) {
                null, JsonNull -> ""
                is JsonPrimitive -> if (value.isString) value.content else error("Malformed C# hover")
                is JsonArray -> value.joinToString("\n") { text(it) }
                is JsonObject -> text(value["value"])
            }
            val content = text(obj?.get("contents"))
            require(content.length <= 100_000 && LspCSharpResultAuthority.validText(content))
            val guard = heldGuard(s, capture)
            synchronized(lock) { held[capture.path] = guard }
            LspHeldTextResult(content.ifBlank { "No native hover information." }, guard)
        } ?: LspHeldTextResult("C# hover unavailable.")
    }
    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = when (op) {
        "hover" -> heldTextResult(op, path, line, col).text
        "completion" -> completionItems(path, line, col).joinToString("\n") { it.label }
        "documentSymbol" -> symbolItems(path).joinToString("\n") { it.name }
        "goToDefinition", "goToTypeDefinition", "goToImplementation", "findReferences" -> locationItems(op, path, line, col).joinToString("\n") { "${it.path}:${it.line}:${it.column}" }
        else -> "Unsupported C# operation; no command or code action was executed."
    }
    override suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> {
        val method = when (op) { "goToDefinition" -> "textDocument/definition"; "goToTypeDefinition" -> "textDocument/typeDefinition"
            "goToImplementation" -> "textDocument/implementation"; "findReferences" -> "textDocument/references"; else -> error("Unsupported C# navigation") }
        return feature(path, method) { s, capture, operation ->
            check(s.caps.supportsOperation(op))
            val raw = request(s, capture, operation, method, params(capture, line, col) { if (op == "findReferences") put("context", buildJsonObject { put("includeDeclaration", true) }) })
            LspCSharpResultAuthority.locations(raw, s.selection.rootPath, capture.texts, ::relative, heldGuard(s, capture))
        }.orEmpty()
    }
    private fun symbols(value: JsonElement, s: Session, capture: Capture): List<LspSymbol> {
        val result = mutableListOf<LspSymbol>(); val guard = heldGuard(s, capture)
        fun add(raw: JsonElement, parentPath: String, depth: Int) {
            require(depth <= 32 && result.size < 1000)
            val o = raw as? JsonObject ?: error("Malformed C# symbol")
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 1000 }?.content ?: error("C# symbol name missing")
            val location = o["location"] as? JsonObject
            val path = if (location != null) LspCSharpResultAuthority.uriPath((location["uri"] as? JsonPrimitive)?.content ?: error("C# symbol URI missing"), s.selection.rootPath, capture.texts) else parentPath
            val range = LspCSharpResultAuthority.range(location?.get("range") ?: o["selectionRange"] ?: o["range"], capture.texts.getValue(path))
            result += LspSymbol(name, (o["kind"] as? JsonPrimitive)?.intOrNull, relative(path), range.startLine, range.startColumn, depth, guard)
            o["children"]?.let { (it as? JsonArray ?: error("Malformed C# child symbols")).forEach { child -> add(child, path, depth + 1) } }
        }
        (value as? JsonArray ?: error("C# symbol result unavailable")).forEach { add(it, capture.path, 0) }
        return result
    }
    override suspend fun symbolItems(path: String): List<LspSymbol> = feature(path, "textDocument/documentSymbol") { s, capture, operation ->
        check(s.caps.documentSymbol); symbols(request(s, capture, operation, "textDocument/documentSymbol", params(capture)), s, capture)
    }.orEmpty()
    override suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> = feature(pathHint, "workspace/symbol") { s, capture, operation ->
        check(s.caps.workspaceSymbol); require(query.length <= 1000)
        symbols(request(s, capture, operation, "workspace/symbol", buildJsonObject { put("query", query) }), s, capture)
    }.orEmpty()
    override suspend fun formatDocument(path: String): LspWorkspaceEdit? =
        formatDocumentWithOptions(path, LspFormattingOptions.DEFAULT)

    override suspend fun formatDocument(path: String, options: LspFormattingOptions): LspWorkspaceEdit? =
        formatDocumentWithOptions(path, options)

    private suspend fun formatDocumentWithOptions(path: String, options: LspFormattingOptions): LspWorkspaceEdit? = feature(path, "textDocument/formatting") { s, capture, operation ->
        check(s.caps.formatting)
        val raw = request(s, capture, operation, "textDocument/formatting", params(capture) { put("options", options.toJson()) })
        LspCSharpResultAuthority.edits(raw, capture.path, s.selection.rootPath, capture.texts,
            capture.buffers.keys.associateWith { requireNotNull(s.sync.version(uri(it))) }, ::relative) { synchronized(lock) { captureCurrent(s, capture) } }
    }
    override suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? = feature(path, "textDocument/rename") { s, capture, operation ->
        check(s.caps.rename); require(newName.isNotBlank() && newName.length <= 1000 && LspCSharpResultAuthority.validText(newName))
        val raw = request(s, capture, operation, "textDocument/rename", params(capture, line, col) { put("newName", newName) })
        LspCSharpResultAuthority.edits(raw, null, s.selection.rootPath, capture.texts,
            capture.buffers.keys.associateWith { requireNotNull(s.sync.version(uri(it))) }, ::relative) { synchronized(lock) { captureCurrent(s, capture) } }
    }
    override suspend fun diagnose(relPath: String): String = feature(relPath, "textDocument/diagnostic") { s, capture, operation ->
        check(s.caps.pullDiagnostics)
        val prior = synchronized(lock) { reports[capture.path]?.takeIf { it.buffer === capture.buffers[capture.path] }?.report }
        val raw = request(s, capture, operation, "textDocument/diagnostic", params(capture) {
            s.caps.diagnosticIdentifier?.let { put("identifier", it) }; prior?.resultId?.let { put("previousResultId", it) }
        })
        val report = LspCSharpDiagnostics.parse(raw, capture.text, prior)
        synchronized(lock) {
            check(captureCurrent(s, capture))
            if (report == null) "C# diagnostics pending or unavailable; prior meaningful report retained. Project completeness unverified."
            else {
                reports[capture.path] = Diagnostic(capture.buffers.getValue(capture.path), report)
                publishDiagnostics(_diagnostics.value + (uri(capture.path) to report.items))
                detail = if (report.semanticResult) "Current-document native semantic result available; project completeness, restore and build readiness unverified."
                    else "Current-document native diagnostics available without semantic-result identity; project completeness unverified."
                detail += s.warning?.let { " Native warning: $it" }.orEmpty()
                changed()
                detail + "\n" + if (report.items.isEmpty()) "No diagnostics in this identified current-document report." else report.items.joinToString("\n") { "${it.line}:${it.column}: ${it.message}" }
            }
        }
    } ?: "C# diagnostic demand became stale; no clean result was certified."
    private fun sha(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

package com.baystudio.droide.core

import java.io.File
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Native ELP sessions are owned by resolved selection, never by whichever tab opened first. */
internal class ElpLspManager(scope: CoroutineScope, private val workspace: File,
    private val host: StdioProcessHost?, private val routeCurrent: () -> Boolean,
    private val approve: suspend (LspProjectAnalysisRequest, () -> Boolean) -> Boolean,
    private val onDiagnostics: (ElpDiagnosticPublication) -> Unit = {},
    private val routeForPath: (String) -> Boolean = { routeCurrent() }) : LspManager {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownedScope = CoroutineScope(scope.coroutineContext + job)
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val consent = LspProjectAnalysisConsent()
    private val lifetime = UUID.randomUUID().toString()
    private var closed = false
    private var cleanupTail: Job? = null
    private var cleanupUnknown = false
    private var epoch = 0L
    private var revision = 0L
    private var invalidationGeneration = 0L
    private var opens = 0L
    private var ownerSequence = 0L
    private var requestSequence = 0L
    private var batch = 0L
    private var publicationSequence = 0L
    private val publicationOwner = Any()
    private val buffers = linkedMapOf<String, Overlay>()
    private val unconfirmedEditorPaths = hashSetOf<String>()
    private val projects = linkedMapOf<String, Project>()
    private val pathProjects = hashMapOf<String, String>()
    private val pending = hashMapOf<String, LspProjectAnalysisConsent.Ticket>()
    private val metadata = hashMapOf<String, ElpMetadataInspectionPermit>()
    private val held = hashMapOf<String, LspHeldResultGuard>()
    private val actionReceipts = IdentityHashMap<LspCodeAction, ActionReceipt>()
    private val hierarchyReceipts = IdentityHashMap<LspCallHierarchyItem, HierarchyReceipt>()
    private val diagnosticReceipts = IdentityHashMap<LspDiagnostic, DiagnosticReceipt>()
    private val reportSerials = hashMapOf<String, Long>()
    private val publications = Channel<ElpDiagnosticPublication>(Channel.CONFLATED)
    @Volatile private var invalidation: ElpDiagnosticPublication? = null
    // The parent holds its outer lifetime lock; never call it while holding our inner monitor.
    private fun publishInvalidation() { if (!Thread.holdsLock(lock)) invalidation?.let(onDiagnostics) }
    private inline fun <T> mutate(block: () -> T): T = try { synchronized(lock, block) } finally { publishInvalidation() }
    private var detail = "Enable native ELP analysis for this trusted project and selected tools."
    private val _status = MutableStateFlow(0L)
    override val statusRevision: StateFlow<Long> = _status.asStateFlow()
    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()
    private data class Overlay(val text: String, val generation: Long, val revision: Long)
    private data class EditorCapture(val ledger: Map<String, Overlay>, val revision: Long, val epoch: Long, val invalidationGeneration: Long?)
    private fun editorCaptureLocked(bindResults: Boolean = false) = EditorCapture(buffers.filterKeys { LspElpProfile.supportsPath(it) && routeForPath(relative(it)) }, revision, epoch, invalidationGeneration.takeIf { bindResults })
    private fun current(editors: EditorCapture) = editors.revision == revision && editors.epoch == epoch && (editors.invalidationGeneration == null || editors.invalidationGeneration == invalidationGeneration) && editors.ledger.all { (p, o) -> buffers[p] === o }
    private class Project(val observation: ElpProjectObservation, val parsedToml: JsonObject?, val native: ElpManagedLaunchIdentity,
        val permit: LspProjectAnalysisConsent.Permit, val epoch: Long, val initiating: String) {
        var session: Session? = null; var starting: Deferred<Unit>? = null; var cleanup: Job? = null
        var preparationGeneration = 0L; var preparationDeadline: ElpDeadline? = null
        var cleanupUnknown = false; var phase = LspProjectAnalysisReadiness.PREPARING; var detail = "ELP preparing."
        val demands = linkedSetOf<String>()
    }
    private class Session(val project: Project, val owner: ElpPreparedOwner, val generation: Long, val startDeadline: ElpDeadline) {
        var active = true; var ready = false; var nativeStatus: String? = null; var replayCommitted = false
        var setupStarted = false; var setupReturned = false; var startupFinished = false; var degraded = false
        var phaseDeadline = startDeadline
        lateinit var rpc: JsonRpcProcess; lateinit var caps: LspNegotiatedCapabilities; lateinit var sync: LspDocumentSyncAuthority
        var process: ElpHostedProcess? = null
        var observer: Job? = null; var notifications: Job? = null
        val wire = IdentityHashMap<JsonElement, WireTicket>()
        val callbackResults = IdentityHashMap<JsonElement, Long>()
        val outboundIds = linkedSetOf<JsonPrimitive>()
        val progress = linkedMapOf<JsonPrimitive, String?>()
        val delivered = linkedMapOf<String, Overlay>()
        val syncMutex = Mutex(); var replayCapture: Capture? = null
        lateinit var requests: LspElpRequests
    }
    private class Capture(val session: Session, val path: String, val ledger: Map<String, Overlay>, val revision: Long,
        val epoch: Long, val sequence: Long, val deadline: ElpDeadline, val retirementDeadline: ElpDeadline = deadline, val invalidationGeneration: Long? = null) {
        val text get() = requireNotNull(ledger[path]).text
        val guestPath get() = session.project.observation.toGuest(path)
        val guestRoot get() = session.project.observation.inspection.guestWorkspaceRoot
        val uri get() = LspElpProfile.uri(guestPath)
        var versions: Map<String, Int> = emptyMap()
        fun sources(): Map<String, String> = ledger.mapKeys { session.project.observation.toGuest(it.key) }.mapValues { it.value.text }
    }
    private class WireTicket(val capture: Capture?, val operation: LspElpRequests.Operation?, val deadline: ElpDeadline,
        val epoch: Long, val method: String) { var requestId: JsonPrimitive? = null }
    private class ActionReceipt(val capture: Capture, val raw: JsonObject)
    private class HierarchyReceipt(val capture: Capture, val raw: JsonObject)
    private class DiagnosticReceipt(val capture: Capture, val serial: Long)
    init { ownedScope.launch { for (publication in publications) onDiagnostics(publication) } }
    private fun changed() { _status.value++ }
    private fun full(path: String): String {
        val file = if (path.startsWith('/')) File(path) else File(workspace, path)
        val value = file.toPath().normalize().toString()
        require(value.startsWith(workspace.absolutePath.trimEnd('/') + "/") && !SensitivePathPolicy.isSensitive(value))
        return value
    }
    private fun relative(path: String) = path.removePrefix(workspace.absolutePath.trimEnd('/') + "/")
    private fun live(p: Project): Boolean = !closed && job.isActive && routeCurrent() && p.epoch == epoch &&
        projects[p.observation.configurationIdentity] === p && consent.isCurrent(p.permit)
    private fun live(s: Session): Boolean = live(s.project) && s.project.preparationGeneration == s.generation && s.project.session === s && s.active && s.owner.isCurrent
    private fun preparing(p: Project, generation: Long) = live(p) && p.preparationGeneration == generation
    private fun current(c: Capture): Boolean = live(c.session) && c.epoch == epoch && c.revision == revision &&
        (c.invalidationGeneration == null || c.invalidationGeneration == invalidationGeneration) && c.ledger.all { (path, overlay) -> buffers[path] === overlay } && routeForPath(relative(c.path))
    private fun guard(c: Capture) = LspHeldResultGuard { synchronized(lock) { current(c) && c.session.ready && c.session.replayCommitted && c.session.nativeStatus == "Running" } }
    private fun invalidateLocked() {
        ++invalidationGeneration
        held.clear(); actionReceipts.clear(); hierarchyReceipts.clear(); diagnosticReceipts.clear(); reportSerials.clear(); ++batch
        _diagnostics.value = emptyMap(); val at = ++publicationSequence
        val clear = ElpDiagnosticPublication(publicationOwner, at) { publish -> synchronized(lock) {
            if (publicationSequence != at) false else { publish(emptyMap()); true }
        } }; invalidation = clear; publications.trySend(clear)
    }
    private fun publishDiagnosticsLocked(c: Capture, uri: String, items: List<LspDiagnostic>, report: Long) {
        check(current(c)); reportSerials[uri] = report; val at = ++publicationSequence
        val value = _diagnostics.value + (uri to items); _diagnostics.value = value
        publications.trySend(ElpDiagnosticPublication(publicationOwner, at) { publish -> synchronized(lock) {
            if (publicationSequence != at || !current(c) || reportSerials[uri] != report) false else { publish(value); true }
        } })
    }
    fun observeEditorDocument(path: String, text: String, opened: Boolean = false) {
        val absolute = runCatching { full(path) }.getOrNull() ?: return
        mutate {
            if (closed) return
            val needsConfirmation = unconfirmedEditorPaths.remove(absolute)
            if (!opened && !needsConfirmation && buffers[absolute]?.text == text) return
            val old = buffers[absolute]
            buffers[absolute] = Overlay(text, if (opened || old == null) ++opens else old.generation, ++revision)
            invalidateLocked()
            projects.values.forEach { p ->
                p.session?.let { s -> s.replayCommitted = false
                    if (opened && absolute in s.delivered) retireLocked(p, "ELP reopened document requires a fresh owner.") }
                if (p.observation.toGuest(absolute) in p.observation.observedPaths && p.observation.observedPaths[p.observation.toGuest(absolute)]?.kind == "regular")
                    retireLocked(p, "ELP configuration changed in the editor; save and enable the current configuration.")
            }
            changed()
        }
    }
    fun closeEditorDocument(path: String) {
        val absolute = runCatching { full(path) }.getOrNull() ?: return
        mutate {
            if (buffers.remove(absolute) == null) return
            unconfirmedEditorPaths.remove(absolute)
            ++revision; invalidateLocked(); pathProjects.remove(absolute)
            projects.values.forEach { p ->
                p.demands.remove(absolute)
                if (p.observation.containsLocal(absolute) || p.session?.delivered?.containsKey(absolute) == true)
                    retireLocked(p, "ELP Close/discard requires owned retirement and replay on next demand.")
            }; changed()
        }
    }
    fun observeEditorSave(path: String, currentText: String? = null) {
        val absolute = runCatching { full(path) }.getOrNull() ?: return
        mutate { if (closed) return
            buffers[absolute]?.let { if (currentText != null && currentText != it.text) unconfirmedEditorPaths += absolute }
            ++revision; invalidateLocked()
            projects.values.filter { it.observation.containsLocal(absolute) || it.observation.toGuest(absolute) in it.observation.observedPaths }.forEach { retireLocked(it, "ELP saved files require refreshed native analysis.") }; changed() }
    }
    override suspend fun didOpen(path: String, text: String) = observeEditorDocument(path, text, true)
    override suspend fun didChange(path: String, text: String) = observeEditorDocument(path, text)
    override suspend fun didClose(path: String) = closeEditorDocument(path)
    override suspend fun didSave(path: String, text: String?) = observeEditorSave(path, text)
    override suspend fun didSaveSnapshot(path: String, savedText: String, currentText: String) = observeEditorSave(path, currentText)
    override fun workspaceFilesChanged() { mutate { if (closed) return; ++revision; invalidateLocked(); projects.values.forEach { retireLocked(it, "ELP persisted workspace mutation; Refresh/Retry required.") }; changed() } }
    override fun completionPolicy(path: String) = LspCompletionPolicy.ELP_NATIVE
    override fun heldResultGuard(path: String): LspHeldResultGuard? = synchronized(lock) { held[full(path)] }
    private fun project(path: String): Project? = pathProjects[path]?.let(projects::get) ?: projects.values.filter { it.observation.containsLocal(path) && live(it) }.maxByOrNull { it.observation.root.length } ?: projects.values.filter { it.observation.containsLocal(path) }.maxByOrNull { it.observation.root.length }
    override fun projectAnalysisStatus(path: String): LspProjectAnalysisStatus = synchronized(lock) {
        val p = project(full(path)); LspProjectAnalysisStatus(LspElpProfile.ID, p?.let(::live) == true,
            p?.observation?.root ?: workspace.absolutePath, LspElpProfile.FEATURE_NOTE,
            p != null || pending.isNotEmpty() || metadata.isNotEmpty(), p?.phase ?: LspProjectAnalysisReadiness.ON_DEMAND, if (cleanupUnknown && p?.session?.active != true) "ELP cleanup unconfirmed; replacement remains blocked." else p?.detail ?: detail)
    }
    override fun serverStatus(path: String): String = projectAnalysisStatus(path).readinessDetail.orEmpty()
    private suspend fun inspect(path: String, deadline: ElpDeadline): ElpSelectionInspection = withContext(Dispatchers.IO) {
        withTimeout(minOf(5_000, deadline.remainingMillis()).coerceAtLeast(1)) {
            requireNotNull(host?.inspectElpSelection(workspace.absolutePath, path, deadline.limited(5_000))) { "Typed local ELP metadata inspection is unavailable" }
        }
    }
    private suspend fun resolve(raw: ElpSelectionInspection, toml: JsonObject?, deadline: ElpDeadline): ElpProjectObservation =
        LspElpProjectObservation.resolve(raw, toml) { paths -> requireNotNull(host).observeElpPaths(raw, paths, deadline) }
    private fun requireCleanConfig(observation: ElpProjectObservation) = synchronized(lock) {
        observation.observedPaths.values.filter { it.kind == "regular" && it.bytes != null }.forEach { file ->
            runCatching { observation.toLocal(file.guestPath) }.getOrNull()?.let(buffers::get)?.let { require(it.text.toByteArray(Charsets.UTF_8).contentEquals(file.bytes)) { "Save controlling configuration before ELP analysis: ${file.guestPath}" } }
        }
    }
    override suspend fun enableProjectAnalysis(path: String): String {
        val absolute = full(path)
        val capturedEpoch: Long; val initiating: Overlay
        synchronized(lock) { check(!closed && routeCurrent() && routeForPath(path)); initiating = requireNotNull(buffers[absolute]); capturedEpoch = epoch
            detail = "Observing native ELP project selection without executing tools."; changed() }
        val remote = requireNotNull(host); val observationDeadline = ElpDeadline.after(20_000)
        val raw = inspect(absolute, observationDeadline)
        val native = withContext(Dispatchers.IO) { requireNotNull(remote.reviewedElpLaunchIdentity(LspElpProfile.COMMAND, workspace.absolutePath, observationDeadline)) { "Install and explicitly approve health for the exact original ELP package first" } }
        var parsed: JsonObject? = null
        val toml = LspElpProjectObservation.nearestToml(raw)
        if (toml != null) parsed = synchronized(lock) { projects.values.firstOrNull { p -> live(p) &&
            LspElpProjectObservation.nearestToml(p.observation.inspection)?.let { it.guestPath == toml.guestPath && it.identity == toml.identity } == true }?.parsedToml }
        if (toml != null && parsed == null) {
            val python = requireNotNull(raw.python) { "This .elp.toml project needs explicitly selected preinstalled CPython 3.12.x metadata inspection" }
            val bytes = requireNotNull(toml.bytes); val digest = LspElpProjectObservation.sha(bytes)
            val attempt = UUID.randomUUID().toString()
            val request = ElpMetadataInspectionRequest(raw, python, toml.guestPath, bytes.copyOf(), digest, attempt)
            val permit = ElpMetadataInspectionPermit(attempt, request.identity) { synchronized(lock) {
                !closed && epoch == capturedEpoch && buffers[absolute] === initiating && metadata[attempt]?.identity == attempt && routeCurrent()
            } }
            synchronized(lock) { metadata[attempt] = permit; detail = "Waiting for one-attempt selected Python metadata permission."; changed() }
            try {
                val allowed = approve(LspProjectAnalysisRequest(LspElpProfile.ID, workspace.absolutePath, toml.guestPath,
                    listOf(listOf(python.file.guestPath, "-I", "-S")), "", purpose = LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION,
                    metadataDisclosure = ElpMetadataInspectionDisclosure(python.file.guestPath, python.file.sha256, toml.guestPath, digest))) { permit.isCurrent }
                check(allowed && permit.isCurrent) { "ELP metadata inspection was not approved" }
                val deadline = ElpDeadline.after(20_000)
                val result = withTimeout(deadline.remainingMillis().coerceAtLeast(1)) { remote.inspectElpToml(request, permit, deadline) }
                require(result.cleanup.complete && result.inputSha256 == digest) { "Metadata inspection cleanup or identity is unconfirmed" }
                parsed = result.data
            } finally { permit.invalidate(); synchronized(lock) { metadata.remove(attempt) } }
        }
        val observation = resolve(raw, parsed, ElpDeadline.after(5_000)); requireCleanConfig(observation)
        synchronized(lock) { projects[observation.configurationIdentity]?.takeIf(::live)?.let { existing ->
            existing.demands += absolute; pathProjects[absolute] = observation.configurationIdentity
            if (existing.session?.ready != true) startLocked(existing)
            return existing.detail
        } }
        require(raw.tools != null) { "Capture a compatible preinstalled OTP pair; no tools were executed" }
        val identity = LspProjectAnalysisIdentity(workspace.absolutePath, LspElpProfile.ID, observation.root,
            LspElpProjectObservation.sha((lifetime + observation.configurationIdentity + native.consentIdentity + LspElpProfile.settings).toByteArray()))
        val ticket = synchronized(lock) { check(!closed && epoch == capturedEpoch && buffers[absolute] === initiating && routeCurrent())
            requireNotNull(consent.begin(identity)).also { pending[absolute] = it; detail = "Waiting for the separate ELP project execution grant."; changed() } }
        try {
            val tools = raw.tools
            val disclosure = "Selected native descriptor: ${observation.descriptor}. Rebar profile: ${observation.rebarProfile}. " +
                "Selected unverified erl: ${tools.erl.file.guestPath}; escript: ${tools.escript.file.guestPath}; Rebar: ${if (observation.needsRebar) tools.rebar?.file?.guestPath else "not required"}. " +
                "Entry identities are captured; fixed compatibility probes have not yet run. " + LspElpProfile.WATCH_NOTE
            val allowed = approve(LspProjectAnalysisRequest(LspElpProfile.ID, workspace.absolutePath, observation.root,
                listOf(listOf(native.launchExecutable)), LspElpProfile.APPROVAL_EXPLANATION,
                LspElpProfile.INSTALLATION_DISCLOSURE, disclosure)) { synchronized(lock) { !closed && epoch == capturedEpoch && pending[absolute] === ticket && buffers[absolute] === initiating && consent.isCurrent(ticket) && routeCurrent() } }
            check(allowed) { "ELP project analysis was not approved" }
            // No native process existed during either prompt. Approval starts the single total now.
            val deadline = ElpDeadline.start()
            val fresh = inspect(absolute, deadline.limited(20_000)); require(fresh.observationIdentity == raw.observationIdentity) { "ELP selection changed while approval was pending" }
            requireCleanConfig(observation)
            synchronized(lock) {
                check(!closed && epoch == capturedEpoch && pending[absolute] === ticket && buffers[absolute] === initiating && routeCurrent())
                val grant = requireNotNull(consent.approve(ticket)); pending.remove(absolute)
                projects.values.filter { it.observation.root == observation.root }.forEach { retireLocked(it, "ELP selection was replaced.") }
                val project = Project(observation, parsed, native, grant, epoch, absolute); project.demands += absolute
                projects[observation.configurationIdentity] = project; pathProjects[absolute] = observation.configurationIdentity
                startLocked(project, deadline); return project.detail
            }
        } finally { synchronized(lock) { if (pending[absolute] === ticket) pending.remove(absolute) } }
    }
    private fun startLocked(p: Project, deadline: ElpDeadline = ElpDeadline.start()) {
        check(live(p)); check(unconfirmedEditorPaths.none { p.observation.containsLocal(it) }) { "ELP needs an authoritative editor publication after a mismatching Save callback" }; if (p.starting?.isActive == true || p.session?.let { live(it) && it.ready } == true) return
        check(!p.cleanupUnknown && !cleanupUnknown) { "ELP cleanup is unconfirmed; replacement remains blocked" }
        p.phase = LspProjectAnalysisReadiness.PREPARING; p.detail = "ELP preparing; native loading and replay pending."; changed()
        val generation = ++ownerSequence; p.preparationGeneration = generation; p.preparationDeadline = deadline
        val previousCleanup = cleanupTail
        p.starting = ownedScope.async(Dispatchers.IO) { prepare(p, generation, deadline, previousCleanup) }
    }
    private suspend fun prepare(p: Project, generation: Long, deadline: ElpDeadline, previousCleanup: Job?) {
        var owner: ElpPreparedOwner? = null; var session: Session? = null; var ownerTransferred = false
        try {
            val work = deadline.reserve(10_000); withTimeout(work.remainingMillis().coerceAtLeast(1)) { previousCleanup?.join() }; work.check()
            synchronized(lock) { check(preparing(p, generation) && !p.cleanupUnknown && !cleanupUnknown && p.demands.any { it in buffers }) }
            val preflight = work.limited(20_000)
            val raw = inspect(p.observation.inspection.rootPath, preflight)
            require(raw.observationIdentity == p.observation.inspection.observationIdentity) { "ELP controlling selection changed; enable the new configuration" }
            requireCleanConfig(p.observation)
            val remote = requireNotNull(host)
            require(remote.reviewedElpLaunchIdentity(LspElpProfile.COMMAND, workspace.absolutePath, preflight) == p.native) { "ELP managed package generation changed" }
            val permit = ElpProjectAnalysisPermit(p.permit.identity.configuration, raw.observationIdentity,
                requireNotNull(raw.tools).consentIdentity, p.native.consentIdentity, p.observation.needsRebar, p.observation.rebarProfile) { synchronized(lock) { preparing(p, generation) } }
            owner = withTimeout(preflight.remainingMillis().coerceAtLeast(1)) {
                remote.prepareReviewedElpOwner(p.native, ElpOwnerRequest(workspace.absolutePath, p.observation.root, lifetime, generation, raw, permit), deadline)
            }
            val s = Session(p, owner, generation, deadline); session = s
            s.requests = LspElpRequests(ownedScope, { _, at -> synchronized(lock) { if (p.session === s) retireLocked(p, "ELP request exceeded its original budget; exact owner retired.", ElpDeadline.at(at)) } })
            val adapter = object : StdioProcessHost by remote {
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                    require(argv == listOf(p.native.launchExecutable) && environment.isEmpty() && resourceLimits == null)
                    return remote.startReviewedElp(s.owner, work) { create ->
                        synchronized(lock) { check(live(s)); work.check(); check(p.demands.any { it in buffers }) }
                        create().also { created -> synchronized(lock) { s.process = created; check(live(s)); work.check() } }
                    }
                }
            }
            s.rpc = JsonRpcProcess(ownedScope, File(p.observation.root), listOf(p.native.launchExecutable), processHost = adapter,
                sessionAdmission = { wireAdmission(s, it) }, inboundRequestHandler = { method, params -> callback(s, method, params) })
            synchronized(lock) { check(preparing(p, generation) && p.demands.any { it in buffers }); p.session = s; ownerTransferred = true }
            s.notifications = ownedScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { s.rpc.notifications.collect { notification -> onNotification(s, notification) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { synchronized(lock) { if (live(s)) retireLocked(p, "ELP notification contract failed: ${failure.message?.take(160)}") } }
            }
            s.rpc.start(); observe(s)
            val initialize = request(s, null, null, "initialize", buildJsonObject {
                put("processId", JsonNull); put("rootUri", LspElpProfile.rootUri(p.observation.root)); put("capabilities", LspElpProfile.clientCapabilities())
                put("initializationOptions", LspElpProfile.settings)
            }, work.limited(40_000))
            s.caps = LspElpProfile.capabilities(initialize); s.sync = LspDocumentSyncAuthority(s.rpc, s.caps.sync.copy(rangeLessReplacement = true))
            notify(s, null, "initialized", buildJsonObject {}, work)
            val capture = capture(s, p.initiating.takeIf { synchronized(lock) { it in buffers } } ?: synchronized(lock) { p.demands.first { it in buffers } }, work.limited(30_000))
            replay(capture, null)
            synchronized(lock) { check(current(capture)); s.replayCommitted = true; updateReadiness(s) }
            while (true) {
                work.check()
                if (synchronized(lock) { check(live(s)); s.nativeStatus == "Running" && s.replayCommitted && s.setupReturned }) break
                delay(minOf(25, work.remainingMillis()).coerceAtLeast(1))
            }
            synchronized(lock) { check(live(s)); s.startupFinished = true; updateReadiness(s) }
        } catch (failure: Throwable) {
            synchronized(lock) {
                // A canceled preparation may settle after Retry has registered its successor.
                // Only its exact generation may publish readiness or retire a published session.
                if (preparing(p, generation)) {
                    if (session != null && p.session === session) retireLocked(p, "ELP preparation failed: ${failure.message?.take(200)}", deadline)
                    else { p.phase = LspProjectAnalysisReadiness.FAILED; p.detail = if (cleanupUnknown || p.cleanupUnknown) "ELP cleanup unconfirmed; replacement remains blocked." else "ELP preparation unavailable: ${failure.message?.take(200)}"; changed() }
                }
            }
            if (owner != null && !ownerTransferred) {
                owner.fence()
                val receipt = withContext(NonCancellable) { runCatching { owner.retire(deadline.limited(10_000)) }.getOrNull() }
                // Cleanup truth survives attempt retirement; it can never be hidden by a newer phase.
                if (receipt?.complete != true) synchronized(lock) { p.cleanupUnknown = true; cleanupUnknown = true; p.detail = "ELP owner cleanup unconfirmed; replacement blocked."; changed() }
            }
        }
    }

    private fun updateReadiness(s: Session) {
        if (!live(s)) return
        s.ready = s.nativeStatus == "Running" && s.replayCommitted
        s.project.phase = if (s.ready) LspProjectAnalysisReadiness.READY else LspProjectAnalysisReadiness.PREPARING
        s.project.detail = if (s.ready) LspElpProfile.RUNNING_NOTE + if (s.degraded) " Native errors were observed." else "" else "ELP loading; native readiness is pending."
        changed()
    }
    private fun observe(s: Session) {
        s.observer = ownedScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(100)
                synchronized(lock) {
                    if (!s.active || s.project.session !== s) return@launch
                    if (!live(s) || s.process?.isAlive != true || !s.rpc.isRunning || s.rpc.omittedNotifications != 0L) {
                        retireLocked(s.project, "ELP owner/transport ended or authority changed."); return@launch
                    }
                    if ((!s.startupFinished || s.nativeStatus != "Running" || !s.setupReturned) && s.phaseDeadline.reserve(10_000).expired) {
                        retireLocked(s.project, "ELP loading/setup did not finish within its absolute preparation budget.", s.phaseDeadline); return@launch
                    }
                }
            }
        }
    }
    private fun wireAdmission(s: Session, frame: JsonObject?) = synchronized(lock) {
        check(live(s))
        if (frame == null) { s.startDeadline.reserve(10_000).check(); return@synchronized }
        require(frame.toString().toByteArray(Charsets.UTF_8).size <= ContentLengthProtocol.MAX_MESSAGE_BYTES)
        val method = (frame["method"] as? JsonPrimitive)?.content
        if (method == "$/cancelRequest") {
            val id = ((frame["params"] as? JsonObject)?.get("id") as? JsonPrimitive); require(id in s.outboundIds); return@synchronized
        }
        if (method == null) {
            require(frame["id"] is JsonPrimitive)
            val result = frame["result"]
            if (result != null) require(s.callbackResults.remove(result) == epoch) else require(frame["error"] is JsonObject)
            return@synchronized
        }
        val params = requireNotNull(frame["params"])
        val ticket = s.wire[params]
        if (ticket != null) {
            require(ticket.epoch == epoch && ticket.method == method); ticket.deadline.check(); ticket.capture?.let { check(current(it)) }; ticket.operation?.markDispatched()
        } else {
            val capture = requireNotNull(s.replayCapture) { "ELP frame has no original capture" }; check(current(capture)); capture.deadline.check()
            require(method in setOf("textDocument/didOpen", "textDocument/didChange"))
            val obj = params as? JsonObject ?: error("Missing replay object"); val doc = obj["textDocument"] as? JsonObject ?: error("Missing replay document")
            val uri = LspElpResultAuthority.string(doc["uri"], 16_384); val path = s.project.observation.toLocal(LspElpProfile.ownedPath(uri, s.project.observation.inspection.guestWorkspaceRoot))
            val overlay = requireNotNull(capture.ledger[path]); val version = LspElpResultAuthority.integer(doc["version"])
            if (method == "textDocument/didOpen") require(!s.sync.isOpen(uri) && version == 1 && doc["languageId"] == JsonPrimitive("erlang") && doc["text"] == JsonPrimitive(overlay.text))
            else require(s.sync.isOpen(uri) && version == requireNotNull(s.sync.version(uri)) + 1 && obj["contentChanges"] == buildJsonArray { add(buildJsonObject { put("text", overlay.text) }) })
        }
        (frame["id"] as? JsonPrimitive)?.let { require(s.outboundIds.size < 128); s.outboundIds += it; ticket?.requestId = it }
    }
    private suspend fun request(s: Session, c: Capture?, op: LspElpRequests.Operation?, method: String, params: JsonElement, deadline: ElpDeadline): JsonElement {
        synchronized(lock) { check(live(s)); c?.let { check(current(it)) }; require(s.wire.size < 16); s.wire[params] = WireTicket(c, op, deadline, epoch, method) }
        try {
            val result = s.rpc.request(method, params, deadline.remainingMillis().coerceAtLeast(1), "$/cancelRequest")
            deadline.check(); synchronized(lock) { check(live(s)); c?.let { check(current(it)) } }
            require(result.toString().toByteArray(Charsets.UTF_8).size <= 4_194_304); return result
        } finally { synchronized(lock) { s.wire.remove(params)?.requestId?.let(s.outboundIds::remove) } }
    }
    private suspend fun notify(s: Session, c: Capture?, method: String, params: JsonObject, deadline: ElpDeadline) {
        synchronized(lock) { check(live(s)); s.wire[params] = WireTicket(c, null, deadline, epoch, method) }
        try { withTimeout(deadline.remainingMillis().coerceAtLeast(1)) { s.rpc.notify(method, params) } }
        finally { synchronized(lock) { s.wire.remove(params) } }
    }
    private fun callback(s: Session, method: String, params: JsonElement?): JsonElement = synchronized(lock) {
        check(live(s))
        val value: JsonElement = when (method) {
            "workspace/configuration" -> {
                val items = (params as? JsonObject)?.get("items") as? JsonArray ?: error("Malformed configuration request"); require(items.size <= 128)
                JsonArray(items.map { raw -> val item = raw as? JsonObject ?: error("Malformed configuration item")
                    if (item["section"] == JsonPrimitive("elp") && (item["scopeUri"] == null || item["scopeUri"] == JsonNull)) LspElpProfile.settings else JsonNull })
            }
            "window/workDoneProgress/create" -> {
                val token = (params as? JsonObject)?.get("token") as? JsonPrimitive ?: error("Malformed progress token")
                require(token.isString && token.content.length <= 256 || !token.isString && token.longOrNull != null)
                require(s.progress.size < 128 && !s.progress.containsKey(token)); s.progress[token] = null; JsonNull
            }
            "workspace/applyEdit" -> buildJsonObject { put("applied", false); put("failureReason", "Review a captured native edit proposal in the editor") }
            "window/showDocument" -> buildJsonObject { put("success", false) }
            "window/showMessageRequest" -> JsonNull
            else -> error("Unsupported ELP callback: ${method.take(128)}; no dynamic watcher or code-lens refresh is implemented")
        }
        require(s.callbackResults.size < 128); s.callbackResults[value] = epoch; value
    }
    private fun onNotification(s: Session, notification: JsonRpcProcess.Notification) = synchronized(lock) {
        if (!live(s)) return@synchronized
        val obj = notification.params as? JsonObject
        when (notification.method) {
            "elp/status" -> {
                val status = obj?.get("status")?.let { LspElpResultAuthority.string(it, 32) } ?: error("Missing ELP status")
                require(status in setOf("Loading", "Running", "Invalid", "ShuttingDown"))
                if (status == "Invalid" || status == "ShuttingDown") { retireLocked(s.project, "ELP native status: $status"); return@synchronized }
                if (status == "Loading") {
                    invalidateLocked(); if (s.startupFinished && s.nativeStatus != "Loading") s.phaseDeadline = ElpDeadline.start()
                }
                s.nativeStatus = status; updateReadiness(s)
            }
            "$/progress" -> {
                val token = obj?.get("token") as? JsonPrimitive ?: error("Malformed progress token"); require(s.progress.containsKey(token))
                val value = obj["value"] as? JsonObject ?: error("Malformed progress value")
                value["message"]?.let { LspElpResultAuthority.string(it, 4096) }
                when (LspElpResultAuthority.string(value["kind"], 16)) {
                    "begin" -> { val title = LspElpResultAuthority.string(value["title"], 512); require(s.progress[token] == null); s.progress[token] = title
                        if (title == "ELP compiling dependencies for EqWAlizer") s.setupStarted = true }
                    "report" -> require(s.progress[token] != null)
                    "end" -> { val title = s.progress.remove(token) ?: error("Progress ended before begin")
                        if (title == "ELP compiling dependencies for EqWAlizer") s.setupReturned = true }
                    else -> error("Unsupported progress kind")
                }
            }
            "window/logMessage", "window/showMessage" -> {
                obj?.get("message")?.let { LspElpResultAuthority.string(it, 16_384) }
                if (obj?.get("type") == JsonPrimitive(1)) { s.degraded = true; updateReadiness(s) }
            }
            "textDocument/publishDiagnostics" -> {
                if (!s.replayCommitted || !s.ready || s.nativeStatus != "Running") return@synchronized
                val report = obj ?: error("Malformed diagnostic report")
                val uri = LspElpResultAuthority.string(report["uri"], 16_384)
                val path = runCatching { s.project.observation.toLocal(LspElpProfile.ownedPath(uri, s.project.observation.inspection.guestWorkspaceRoot)) }.getOrNull() ?: return@synchronized
                val overlay = buffers[path] ?: return@synchronized
                val version = (report["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return@synchronized
                if (s.delivered[path] !== overlay || s.sync.version(uri) != version) return@synchronized
                val ledger = s.delivered.toMap(); if (ledger.any { (p, o) -> buffers[p] !== o }) return@synchronized
                val c = Capture(s, path, ledger, revision, epoch, ++requestSequence, ElpDeadline.feature())
                c.versions = ledger.keys.associate { s.project.observation.toGuest(it) to requireNotNull(s.sync.version(LspElpProfile.uri(s.project.observation.toGuest(it)))) }
                val serial = ++requestSequence
                val reportGuard = LspHeldResultGuard { synchronized(lock) { current(c) && reportSerials[LspElpProfile.uri(path)] == serial && s.ready } }
                val items = LspElpResultAuthority.diagnostics(report["diagnostics"], overlay.text, reportGuard)
                items.forEach { diagnosticReceipts[it] = DiagnosticReceipt(c, serial) }
                publishDiagnosticsLocked(c, LspElpProfile.uri(path), items, serial)
            }
            // Telemetry and unknown notifications have no action or forwarding side effect.
        }
    }
    private fun observedSelectionChanged(s: Session, reason: String): Nothing {
        mutate { if (live(s)) retireLocked(s.project, reason) }
        error(reason)
    }
    private suspend fun capture(s: Session, path: String, deadline: ElpDeadline, retirementDeadline: ElpDeadline = deadline, original: EditorCapture? = null): Capture {
        val editors = synchronized(lock) { check(live(s)); (original ?: editorCaptureLocked()).also { check(current(it)) } }
        val all = editors.ledger; val rev = editors.revision; val ep = editors.epoch
        synchronized(lock) { require(unconfirmedEditorPaths.none { s.project.observation.containsLocal(it) }) { "ELP editor state is unconfirmed after Save" } }
        require(all.size <= 256 && all.values.all { LspElpResultAuthority.validText(it.text) && it.text.toByteArray(Charsets.UTF_8).size <= 1_048_576 } && all.values.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } <= 16_777_216)
        val selected = linkedMapOf<String, Overlay>()
        for ((file, overlay) in all) {
            deadline.check(); val raw = inspect(file, deadline)
            val nearest = LspElpProjectObservation.nearestToml(raw)
            val sameToml = nearest?.identity?.sha256 == LspElpProjectObservation.nearestToml(s.project.observation.inspection)?.identity?.sha256 && nearest?.guestPath == LspElpProjectObservation.nearestToml(s.project.observation.inspection)?.guestPath
            val parsed = if (nearest == null) null else if (sameToml) s.project.parsedToml else {
                if (s.project.observation.containsLocal(file)) observedSelectionChanged(s, "Observed conflicting nested ELP selection; enable the current configuration before analysis") else continue
            }
            val observed = try { resolve(raw, parsed, deadline) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (s.project.observation.containsLocal(file)) observedSelectionChanged(s, "Observed ELP project selection is no longer admissible; Refresh and enable the current configuration")
                else throw failure
            }
            requireCleanConfig(observed)
            // Per-input deeper discovery is fresh, while the exact selected-root identity is shared.
            if (observed.configurationIdentity == s.project.observation.configurationIdentity) selected[file] = overlay
            else if (s.project.observation.containsLocal(file)) observedSelectionChanged(s, "Observed sibling belongs to a changed native selection; Refresh and enable the current configuration")
        }
        require(path in selected) { "Requested editor does not belong to the approved native selection" }
        return synchronized(lock) { check(live(s) && revision == rev && epoch == ep); Capture(s, path, selected, rev, ep, ++requestSequence, deadline, retirementDeadline, editors.invalidationGeneration) }
    }
    private suspend fun replay(c: Capture, operation: LspElpRequests.Operation?) = c.session.syncMutex.withLock {
        val s = c.session
        synchronized(lock) { check(current(c)); s.replayCommitted = false; s.replayCapture = c }
        try {
            val ordered = listOf(c.path) + c.ledger.keys.filter { it != c.path }.sorted()
            for (path in ordered) {
                c.deadline.check(); operation?.let { check(it.isDemandCurrent()) }
                val overlay = requireNotNull(c.ledger[path]); val old = synchronized(lock) { check(current(c)); s.delivered[path] }
                require(old == null || old.generation == overlay.generation) { "Reopened ELP editor requires new owner" }
                withTimeout(c.deadline.remainingMillis().coerceAtLeast(1)) { s.sync.synchronize(LspElpProfile.uri(s.project.observation.toGuest(path)), "erlang", overlay.text) }
                synchronized(lock) { check(current(c)); s.delivered[path] = overlay }
            }
            synchronized(lock) {
                check(current(c)); require(s.delivered.keys == c.ledger.keys) { "Removed ELP overlay requires retirement" }
                c.versions = c.ledger.keys.associate { s.project.observation.toGuest(it) to requireNotNull(s.sync.version(LspElpProfile.uri(s.project.observation.toGuest(it)))) }
                s.replayCommitted = true; updateReadiness(s)
            }
        } catch (failure: Throwable) { synchronized(lock) { if (live(s)) retireLocked(s.project, "ELP replay failed; the owned transport cannot be reused.", c.retirementDeadline) }; throw failure }
        finally { synchronized(lock) { if (s.replayCapture === c) s.replayCapture = null } }
    }
    private suspend fun <T> feature(path: String, method: String, supplied: String? = null, line: Int? = null, col: Int? = null,
        original: Capture? = null, block: suspend (Capture, LspElpRequests.Operation) -> T): T? {
        val absolute = full(path)
        val editors: EditorCapture
        val s = synchronized(lock) {
            require(LspElpProfile.supportsPath(path) && routeForPath(path)); val p = project(absolute) ?: throw LspProjectAnalysisRequiredException("Enable ELP analysis first")
            check(live(p)); require(buffers[absolute] != null && (supplied == null || buffers[absolute]?.text == supplied))
            original?.let { check(current(it) && it.session === p.session) { "Original ELP result no longer belongs to the current owner" } }
            p.demands += absolute; val session = p.session
            if (session == null || !live(session)) { startLocked(p); throw LspProjectAnalysisNotReadyException(LspProjectAnalysisReadiness.PREPARING, p.detail) }
            if (!session.ready || session.nativeStatus != "Running") throw LspProjectAnalysisNotReadyException(LspProjectAnalysisReadiness.PREPARING, p.detail)
            editors = editorCaptureLocked(bindResults = true); session
        }
        return s.requests.owned(method, { synchronized(lock) { live(s) && current(editors) && (original == null || current(original)) } }, documentKey = "$absolute:${s.generation}") { operation ->
            val deadline = ElpDeadline.at(operation.deadlineNanos)
            val raw = inspect(s.project.observation.inspection.rootPath, deadline)
            if (raw.observationIdentity != s.project.observation.inspection.observationIdentity) observedSelectionChanged(s, "Observed ELP controlling metadata changed; Refresh and enable the current selection")
            val c = capture(s, absolute, deadline, ElpDeadline.at(operation.totalDeadlineNanos), editors)
            original?.let { check(synchronized(lock) { current(it) }) }
            if (line != null) { require(line > 0 && requireNotNull(col) > 0); requireNotNull(LspElpResultAuthority.offset(c.text, line - 1, col - 1)) }
            replay(c, operation)
            check(synchronized(lock) { current(c) && s.nativeStatus == "Running" && s.ready })
            val result = block(c, operation)
            deadline.check(); check(operation.isDemandCurrent() && synchronized(lock) { current(c) && s.ready && s.nativeStatus == "Running" }); result
        }
    }
    private fun params(c: Capture, line: Int? = null, col: Int? = null) = buildJsonObject {
        put("textDocument", buildJsonObject { put("uri", c.uri) })
        if (line != null) put("position", buildJsonObject { put("line", line - 1); put("character", requireNotNull(col) - 1) })
    }
    private suspend fun request(c: Capture, op: LspElpRequests.Operation, method: String, params: JsonElement): JsonElement = request(c.session, c, op, method, params, c.deadline)
    override suspend fun completionItems(path: String, line: Int, col: Int) = completionItems(path, line, col, null, null)
    override suspend fun completionItems(path: String, line: Int, col: Int, triggerCharacter: String?, currentText: String?): List<LspCompletionItem> =
        feature(path, "textDocument/completion", currentText, line, col) { c, op ->
            check(c.session.caps.completion); val context = requireNotNull(LspElpCompletionContext.capture(c.text, line, col)) { "ELP completion is unavailable inside a quoted token, comment or token suffix" }
            val trigger = LspElpCompletionContext.trigger(context, triggerCharacter, c.session.caps.completionTriggerCharacters)
            val value = request(c, op, "textDocument/completion", JsonObject(params(c, line, col) + ("context" to buildJsonObject { put("triggerKind", if (trigger == null) 1 else 2); trigger?.let { put("triggerCharacter", it) } })))
            val at = synchronized(lock) { ++batch }; val guard = LspCompletionApplicationGuard(c.text, context.caret) { synchronized(lock) { batch == at && current(c) && c.session.ready } }
            LspElpCompletionContext.parse(value, context, guard, at)
        }.orEmpty()
    override fun signatureHelpTriggers(path: String) = synchronized(lock) { project(full(path))?.session?.takeIf { it.ready }?.caps?.let { LspSignatureTriggers(it.signatureHelpTriggerCharacters, it.signatureHelpRetriggerCharacters) } ?: LspSignatureTriggers() }
    override suspend fun signatureHelp(path: String, line: Int, col: Int, currentText: String?, triggerKind: LspSignatureTriggerKind, triggerCharacter: String?, isRetrigger: Boolean, previous: LspSignatureHelp?): LspSignatureHelp? =
        feature(path, "textDocument/signatureHelp", currentText, line, col) { c, op ->
            check(c.session.caps.signatureHelp)
            val raw = request(c, op, "textDocument/signatureHelp", JsonObject(params(c, line, col) + ("context" to LspSignatureHelpProtocol.context(triggerKind,
                triggerCharacter?.takeIf { it in c.session.caps.signatureHelpTriggerCharacters }, isRetrigger, previous?.takeIf { it.applicationGuard?.isCurrent?.invoke() == true }))))
            LspElpResultAuthority.signature(raw)?.copy(applicationGuard = LspCompletionApplicationGuard(c.text, LspCompletionCaret(line, col)) { guard(c).accepts() })
        }
    override suspend fun heldTextResult(op: String, path: String, line: Int, col: Int): LspHeldTextResult {
        if (op == "documentSymbol") { val items = symbolItems(path); return LspHeldTextResult(items.joinToString("\n") { it.name }, items.firstOrNull()?.activationGuard) }
        require(op == "hover") { "Unsupported ELP text operation" }
        return feature(path, "textDocument/hover", line = line, col = col) { c, request ->
            check(c.session.caps.hover); val held = guard(c); val value = LspElpResultAuthority.hover(request(c, request, "textDocument/hover", params(c, line, col)), c.text)
            synchronized(lock) { this.held[c.path] = held }; LspHeldTextResult(value, held)
        } ?: LspHeldTextResult("ELP result became stale.")
    }
    override suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> {
        val method = when (op) { "goToDefinition" -> "textDocument/definition"; "goToTypeDefinition" -> "textDocument/typeDefinition"; "goToImplementation" -> "textDocument/implementation"; "findReferences" -> "textDocument/references"; else -> return emptyList() }
        return feature(path, method, line = line, col = col) { c, request ->
            check(c.session.caps.supportsOperation(op)); val base = params(c, line, col)
            val args = if (op == "findReferences") JsonObject(base + ("context" to buildJsonObject { put("includeDeclaration", true) })) else base
            val result = LspElpResultAuthority.locations(request(c, request, method, args), c.guestRoot, c.sources(), guard(c))
            synchronized(lock) { c.session.project.detail = LspElpProfile.RUNNING_NOTE + " Navigation is limited to captured open workspace targets; unopened/external targets are omitted."; changed() }; result
        }.orEmpty()
    }
    override suspend fun symbolItems(path: String): List<LspSymbol> = feature(path, "textDocument/documentSymbol") { c, op ->
        check(c.session.caps.documentSymbol); LspElpResultAuthority.symbols(request(c, op, "textDocument/documentSymbol", params(c)), c.guestRoot, c.guestPath, c.sources(), guard(c))
    }.orEmpty()
    override suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> = feature(pathHint, "workspace/symbol") { c, op ->
        check(c.session.caps.workspaceSymbol); require(query.length <= 1024); LspElpResultAuthority.symbols(request(c, op, "workspace/symbol", buildJsonObject { put("query", query) }), c.guestRoot, c.guestPath, c.sources(), guard(c))
    }.orEmpty()
    override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> = feature(path, "textDocument/semanticTokens/full", currentText) { c, op ->
        check(c.session.caps.semanticTokens); val guard = guard(c)
        LspElpResultAuthority.semantic(request(c, op, "textDocument/semanticTokens/full", params(c)), c.text, c.session.caps.semanticTokenLegend).map { it.copy(activationGuard = guard) }.also { synchronized(lock) { held[c.path] = guard } }
    }.orEmpty()
    override suspend fun inlayHints(path: String, currentText: String?): List<LspInlayHint> = feature(path, "textDocument/inlayHint", currentText) { c, op ->
        check(c.session.caps.inlayHint); val line = c.text.count { it == '\n' }; val col = c.text.length - (c.text.lastIndexOf('\n') + 1)
        val args = JsonObject(params(c) + ("range" to buildJsonObject { put("start", buildJsonObject { put("line", 0); put("character", 0) }); put("end", buildJsonObject { put("line", line); put("character", col) }) }))
        LspElpResultAuthority.inlay(request(c, op, "textDocument/inlayHint", args), c.text, guard(c))
    }.orEmpty()
    private fun action(c: Capture, raw: JsonObject): LspCodeAction {
        require(raw.toString().toByteArray().size <= 1_048_576)
        val command = raw["command"]; require(command == null || command == JsonNull) { "Command-bearing ELP actions are unavailable; no partial edit is applied" }
        val guard = guard(c); val edit = raw["edit"]?.takeUnless { it == JsonNull }?.let { LspElpResultAuthority.workspaceEdit(it, c.guestRoot, c.sources(), c.versions, guard) }
        val item = LspCodeAction(LspElpResultAuthority.string(raw["title"], 2000), raw["kind"]?.let { LspElpResultAuthority.string(it, 256) }, raw["isPreferred"] == JsonPrimitive(true), edit,
            disabledReason = (raw["disabled"] as? JsonObject)?.get("reason")?.let { LspElpResultAuthority.string(it, 2000) }, rawJson = raw.toString(), activationGuard = guard)
        synchronized(lock) { check(current(c)); require(actionReceipts.size < 512); actionReceipts[item] = ActionReceipt(c, raw) }; return item
    }
    override suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> {
        val original = synchronized(lock) { val receipt = diagnosticReceipts[diagnostic] ?: error("ELP diagnostic no longer belongs to a displayed report"); check(diagnostic.reportGuard?.accepts() == true); receipt.capture }
        return actions(path, diagnostic.line, diagnostic.column, diagnostic.endLine, diagnostic.endColumn, diagnostic, original)
    }
    override suspend fun codeActionsAt(path: String, line: Int, col: Int) = actions(path, line, col, line, col, null, null)
    private suspend fun actions(path: String, line: Int, col: Int, endLine: Int, endCol: Int, diagnostic: LspDiagnostic?, original: Capture?): List<LspCodeAction> =
        feature(path, "textDocument/codeAction", line = line, col = col, original = original) { c, op ->
            check(c.session.caps.codeAction); requireNotNull(LspElpResultAuthority.offset(c.text, endLine - 1, endCol - 1))
            diagnostic?.let { check(it.reportGuard?.accepts() == true) }
            val args = JsonObject(params(c) + mapOf("range" to LspElpResultAuthority.rangeJson(TextRangeEdit(line, col, endLine, endCol, "")),
                "context" to buildJsonObject { put("diagnostics", JsonArray(listOfNotNull(diagnostic?.let(LspElpResultAuthority::diagnosticJson)))) }))
            val value = request(c, op, "textDocument/codeAction", args); if (value == JsonNull) emptyList() else {
                val list = value as? JsonArray ?: error("Malformed ELP actions"); require(list.size <= 256); list.map { action(c, it as? JsonObject ?: error("Malformed ELP action")) }
            }
        }.orEmpty()
    override suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction {
        val receipt = synchronized(lock) { requireNotNull(actionReceipts[action]).also { check(action.activationGuard?.accepts() == true && current(it.capture)) } }
        return feature(path, "codeAction/resolve", original = receipt.capture) { c, op ->
            check(c.session.caps.codeActionResolve); this.action(c, request(c, op, "codeAction/resolve", receipt.raw) as? JsonObject ?: error("Malformed ELP resolved action"))
        } ?: error("ELP resolved action became stale")
    }
    override suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? = feature(path, "textDocument/rename", line = line, col = col) { c, op ->
        check(c.session.caps.rename); require(newName.isNotEmpty() && newName.length <= 1024 && LspElpResultAuthority.validText(newName))
        val raw = request(c, op, "textDocument/rename", JsonObject(params(c, line, col) + ("newName" to JsonPrimitive(newName))))
        if (raw == JsonNull) null else LspElpResultAuthority.workspaceEdit(raw, c.guestRoot, c.sources(), c.versions, guard(c))
    }
    private fun hierarchy(c: Capture, raw: JsonObject): LspCallHierarchyItem? {
        require(raw.toString().toByteArray().size <= 1_048_576)
        val path = runCatching { LspElpProfile.ownedPath(LspElpResultAuthority.string(raw["uri"], 16_384), c.guestRoot) }.getOrNull() ?: return null
        val source = c.ledger[c.session.project.observation.toLocal(path)]?.text ?: return null; LspElpResultAuthority.range(raw["range"], source)
        val range = LspElpResultAuthority.range(raw["selectionRange"], source)
        val item = LspCallHierarchyItem(LspElpResultAuthority.string(raw["name"]), raw["detail"]?.let { LspElpResultAuthority.string(it) }, raw["kind"]?.let(LspElpResultAuthority::integer), path.removePrefix(c.guestRoot.trimEnd('/') + "/"), range.startLine, range.startColumn, raw.toString(), guard(c))
        synchronized(lock) { check(current(c)); require(hierarchyReceipts.size < 2000); hierarchyReceipts[item] = HierarchyReceipt(c, raw) }; return item
    }
    override suspend fun prepareCallHierarchy(path: String, line: Int, col: Int): List<LspCallHierarchyItem> = feature(path, "textDocument/prepareCallHierarchy", line = line, col = col) { c, op ->
        check(c.session.caps.callHierarchy); val raw = request(c, op, "textDocument/prepareCallHierarchy", params(c, line, col)); if (raw == JsonNull) emptyList() else {
            val items = raw as? JsonArray ?: error("Malformed ELP hierarchy"); require(items.size <= 500); items.mapNotNull { hierarchy(c, it as? JsonObject ?: error("Malformed hierarchy item")) }
        }
    }.orEmpty()
    override suspend fun incomingCalls(item: LspCallHierarchyItem) = relations(item, "callHierarchy/incomingCalls", "from")
    override suspend fun outgoingCalls(item: LspCallHierarchyItem) = relations(item, "callHierarchy/outgoingCalls", "to")
    private suspend fun relations(item: LspCallHierarchyItem, method: String, field: String): List<LspCallHierarchyItem> {
        val receipt = synchronized(lock) { requireNotNull(hierarchyReceipts[item]) { "Hierarchy item is not an original current ELP result" }.also { check(item.activationGuard?.accepts() == true && current(it.capture)) } }
        return feature(relative(receipt.capture.path), method, original = receipt.capture) { c, op ->
            val raw = request(c, op, method, buildJsonObject { put("item", receipt.raw) }); if (raw == JsonNull) emptyList() else {
                val items = raw as? JsonArray ?: error("Malformed hierarchy relations"); require(items.size <= 500)
                items.mapNotNull { relation -> val obj = relation as? JsonObject ?: error("Malformed relation"); val target = obj[field] as? JsonObject ?: error("Missing relation item")
                    val ranges = obj["fromRanges"] as? JsonArray ?: error("Missing relation ranges"); require(ranges.size <= 2000)
                    val rangeSource = if (field == "to") c.ledger[full(item.path)]?.text else {
                        val targetPath = runCatching { LspElpProfile.ownedPath(LspElpResultAuthority.string(target["uri"], 16_384), c.guestRoot) }.getOrNull()
                        targetPath?.let { c.ledger[c.session.project.observation.toLocal(it)]?.text }
                    }
                    if (rangeSource == null) null else { ranges.forEach { LspElpResultAuthority.range(it, rangeSource) }; hierarchy(c, target) }
                }
            }
        }.orEmpty()
    }
    override suspend fun diagnose(relPath: String): String = synchronized(lock) {
        val path = full(relPath); val p = project(path) ?: return@synchronized "Enable native ELP analysis first."
        val items = _diagnostics.value[LspElpProfile.uri(path)]
        if (items == null) "${p.detail} No admitted native diagnostic report. ${LspElpProfile.REPORT_NOTE}"
        else if (items.isEmpty()) "ELP reported no items; freshness/completeness unverified."
        else LspElpProfile.REPORT_NOTE + "\n" + LspDiagnosticsProtocol.format(relPath, items)
    }
    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = when (op) {
        "hover", "documentSymbol" -> heldTextResult(op, path, line, col).text
        "completion" -> completionItems(path, line, col).joinToString("\n") { it.label }
        "goToDefinition", "goToTypeDefinition", "goToImplementation", "findReferences" -> locationItems(op, path, line, col).joinToString("\n") { "${it.path}:${it.line}:${it.column}" }
        else -> "ELP operation unavailable. ${LspElpProfile.FEATURE_NOTE}"
    }
    override suspend fun retryProjectAnalysis(path: String): String = mutate {
        val p = project(full(path)) ?: return@mutate "Enable ELP analysis first."
        check(live(p)); p.demands += full(path); retireLocked(p, "Explicit ELP Refresh/Retry requested."); startLocked(p); p.detail
    }
    private fun retireLocked(p: Project, reason: String, deadline: ElpDeadline? = null) {
        val startup = p.starting?.takeUnless { it.isCompleted }
        p.preparationGeneration = 0L; p.starting = null; startup?.cancel()
        val s = p.session?.takeIf { it.active }; invalidateLocked()
        p.phase = LspProjectAnalysisReadiness.FAILED; p.detail = reason
        if (s != null || startup != null) {
            val limit = (deadline ?: s?.requests?.earliestDispatchedDeadlineNanos()?.let(ElpDeadline::at)
                ?: s?.takeUnless { it.startupFinished }?.startDeadline
                ?: p.preparationDeadline?.takeIf { startup != null }
                ?: ElpDeadline.retirement()).limited(10_000)
            if (s != null) {
                // Analysis retirement is immediate admission/feature fencing and exact forced
                // settlement. Staged health, separately, can exchange shutdown/exit before fence.
                s.active = false; s.ready = false; s.owner.fence(); s.requests.close(); s.observer?.cancel(); s.notifications?.cancel()
            }
            val previous = cleanupTail
            p.cleanup = retirementScope.launch {
                // Never join a preparation from its own coroutine. This retained external task
                // includes its eventual returned-owner cleanup before allowing any replacement.
                val startupSettled = withTimeoutOrNull(limit.remainingMillis().coerceAtLeast(1)) {
                    previous?.join(); startup?.join(); true
                } == true
                if (s != null) runCatching { s.rpc.close() }
                val receipt = if (s == null) null else runCatching { s.owner.retire(limit) }.getOrNull()
                synchronized(lock) {
                    if (startupSettled && (s == null || receipt?.complete == true)) { if (s != null && p.session === s) p.session = null }
                    else { p.cleanupUnknown = true; cleanupUnknown = true; p.detail = "ELP cleanup unconfirmed; replacement and package reuse remain blocked." }
                    changed()
                }
            }
            cleanupTail = p.cleanup
        }; changed()
    }
    override fun revokeProjectAnalysis(path: String): String = mutate {
        val absolute = full(path); pending.remove(absolute)?.let { consent.revoke(it.identity) }; metadata.values.forEach(ElpMetadataInspectionPermit::invalidate); metadata.clear()
        val p = project(absolute); if (p != null) { consent.revoke(p.permit.identity); retireLocked(p, "ELP analysis revoked; owned cleanup remains tracked.") }
        detail = "ELP analysis revoked."; changed(); detail
    }
    override fun retireProjectAnalysisAuthority(): () -> Unit {
        mutate { if (!closed) { closed = true; ++epoch; consent.close(); metadata.values.forEach(ElpMetadataInspectionPermit::invalidate); metadata.clear(); pending.clear(); projects.values.forEach { retireLocked(it, "ELP workspace closed.") }; invalidateLocked() } }
        return { publications.close(); job.cancel() }
    }
    override fun close() = retireProjectAnalysisAuthority().invoke()
    override suspend fun shutdown() { close(); synchronized(lock) { cleanupTail }?.join() }
}

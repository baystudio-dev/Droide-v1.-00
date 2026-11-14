package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One finite local-schema owner. Consent, snapshots, transport and UI share captured authority. */
internal class GraphqlLspManager(
    scope: CoroutineScope,
    private val workspace: File,
    private val host: StdioProcessHost?,
    private val routeCurrent: () -> Boolean,
    private val approve: suspend (LspProjectAnalysisRequest, () -> Boolean) -> Boolean,
    private val onDiagnostics: (GraphqlDiagnosticPublication) -> Unit = {},
    private val routeForPath: (String) -> Boolean = { routeCurrent() },
) : LspManager {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownedScope = CoroutineScope(scope.coroutineContext + job)
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val consent = LspProjectAnalysisConsent()
    private val buffers = linkedMapOf<String, LspGraphqlSnapshotAuthority.Overlay>()
    private var nextOpen = 0L
    private var revision = 0L
    private var lifetime = 0L
    private var ownerGeneration = 0L
    private var startupEpoch = 0L
    private val workspaceLifetime = java.util.UUID.randomUUID().toString()
    @Volatile private var admissionOwner: GraphqlPreparedOwner? = null
    @Volatile private var clientFencedOwner: GraphqlPreparedOwner? = null
    private fun fenceForClientRetirement(owner: GraphqlPreparedOwner?) {
        if (owner == null) return
        // Publish exact retirement intent before the independent fence, so observation
        // cannot mistake an ordinary same-grant editor refresh for managed-owner loss.
        clientFencedOwner = owner
        owner.fence()
    }
    @Volatile private var dependencyPaths: Set<String> = emptySet()
    private var serial = 0L
    private var batch = 0L
    private var requestGeneration = 0L
    private var closed = false
    private var permit: LspProjectAnalysisConsent.Permit? = null
    private var pending: LspProjectAnalysisConsent.Ticket? = null
    private var identity: GraphqlManagedLaunchIdentity? = null
    private var grantedRoot: LspGraphqlSnapshotAuthority.Root? = null
    private var session: Session? = null
    private var starting: Deferred<Session>? = null
    private var cleanup: Job? = null
    private var cleanupUnknown = false
    private var grantRecorded = false
    private var refresh: Job? = null
    private var phase = LspProjectAnalysisReadiness.ON_DEMAND
    private var detail = "DISABLED: Enable native GraphQL local SDL analysis for this workspace."
    private val _status = MutableStateFlow(0L)
    override val statusRevision: StateFlow<Long> = _status.asStateFlow()
    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()
    private val publications = Channel<GraphqlDiagnosticPublication>(Channel.CONFLATED)
    @Volatile private var invalidation: GraphqlDiagnosticPublication? = null
    // Called only after releasing our monitor. Parent admits owner/serial atomically under
    // its outer lifetime lock, so a delayed clear cannot erase a replacement publication.
    private fun publishInvalidation() { if (!Thread.holdsLock(lock)) invalidation?.let(onDiagnostics) }
    private val held = hashMapOf<String, LspHeldResultGuard>()
    private val publicationOwner = Any()
    private var requests = newRequests()
    private fun newRequests(): LspGraphqlRequests = LspGraphqlRequests(ownedScope, onDeadline = { _, totalDeadlineNanos ->
        fenceForClientRetirement(admissionOwner)
        synchronized(lock) { retireLocked("INPUT_FAILED: Native GraphQL request expired; exact owner cleanup required.", GraphqlDeadline.at(totalDeadlineNanos)); if (!closed) requests = newRequests() }
        publishInvalidation()
    })
    private class Capture(val path: String, val overlay: LspGraphqlSnapshotAuthority.Overlay, val lifetime: Long, val requestGeneration: Long) {
        val text: String get() = overlay.text
        val uri: String get() = LspGraphqlProfile.uri(path)
        // Computed off the manager lock, once per admitted request.
        var digest: String = ""
    }
    private class Session(val snapshot: LspGraphqlSnapshotAuthority.Snapshot, val permit: LspProjectAnalysisConsent.Permit,
        val identity: GraphqlManagedLaunchIdentity, val owner: GraphqlPreparedOwner, val lifetime: Long, val epoch: Long, val startDeadline: GraphqlDeadline) {
        var active = true; var ready = false; var retiring = false
        var observedAtNanos = snapshot.observedAtNanos
        lateinit var rpc: JsonRpcProcess
        lateinit var caps: LspNegotiatedCapabilities
        var process: GraphqlHostedProcess? = null
        var poller: Job? = null
        val wire = java.util.IdentityHashMap<JsonElement, Ticket>()
        val documents = linkedMapOf<String, LspGraphqlSnapshotAuthority.Overlay>()
        val primaryObservations = linkedMapOf<String, LspGraphqlSnapshotAuthority.Primary>()
        val syncMutex = Mutex()
    }
    private class Ticket(val capture: Capture?, val operation: LspGraphqlRequests.Operation?, val deadline: GraphqlDeadline,
        val authority: JsonObject, val generation: Long)
    init { ownedScope.launch(Dispatchers.Default) { for (publication in publications) onDiagnostics(publication) } }
    private fun changed() { _status.value++ }
    private fun full(path: String): String = LspGraphqlSnapshotAuthority.path(workspace.absolutePath, path)
    private fun relative(path: String): String = path.removePrefix(workspace.absolutePath.trimEnd('/') + "/")
    private fun routeLocked(): Boolean {
        if (routeCurrent()) return true
        if (permit != null || pending != null || session != null) revokeLocked("DISABLED: GraphQL route changed; enable the new exact route separately.")
        return false
    }
    private fun grantedLocked(): Boolean = !closed && job.isActive && permit?.let(consent::isCurrent) == true && routeCurrent()
    private fun currentLocked(s: Session): Boolean = grantedLocked() && session === s && s.active && s.lifetime == lifetime && s.epoch == startupEpoch &&
        permit === s.permit && s.owner.isCurrent && s.process?.fatalControl == null && s.snapshot.overlaysCurrent(buffers) &&
        (!s.ready || System.nanoTime() - s.observedAtNanos <= LspGraphqlSnapshotAuthority.OBSERVATION_MAX_AGE_NANOS)
    private fun captureCurrentLocked(capture: Capture): Boolean = grantedLocked() && capture.lifetime == lifetime &&
        buffers[capture.path] === capture.overlay && routeForPath(relative(capture.path))
    private fun currentLocked(s: Session, capture: Capture): Boolean = currentLocked(s) && captureCurrentLocked(capture)
    private fun invalidateResultsLocked() {
        held.clear(); ++batch; val at = ++serial; _diagnostics.value = emptyMap()
        val clear = GraphqlDiagnosticPublication(publicationOwner, at) { publish -> synchronized(lock) {
            if (serial != at) false else { publish(emptyMap()); true }
        } }
        invalidation = clear
        publications.trySend(clear)
    }
    private fun publishLocked(s: Session, capture: Capture, value: Map<String, List<LspDiagnostic>>) {
        check(currentLocked(s, capture)); val at = ++serial
        publications.trySend(GraphqlDiagnosticPublication(publicationOwner, at) { publish -> synchronized(lock) {
            if (serial != at || !currentLocked(s, capture)) false else { _diagnostics.value = value; publish(value); true }
        } })
    }
    /** Parent invokes the envelope only while holding its outer lifetime admission. */
    override fun heldResultGuard(path: String): LspHeldResultGuard? = synchronized(lock) { held[full(path)] }
    override fun completionPolicy(path: String): LspCompletionPolicy = LspCompletionPolicy.GRAPHQL_NATIVE
    override fun projectAnalysisStatus(path: String): LspProjectAnalysisStatus = synchronized(lock) {
        routeLocked()
        LspProjectAnalysisStatus(LspGraphqlProfile.ID, permit?.let(consent::isCurrent) == true, workspace.absolutePath,
            LspGraphqlProfile.FEATURE_NOTE, grantRecorded || permit != null || pending != null || session != null || cleanupUnknown || cleanup?.isActive == true, phase, detail)
    }.also { publishInvalidation() }
    override fun serverStatus(path: String): String = synchronized(lock) { routeLocked(); "GraphQL · $detail" }.also { publishInvalidation() }
    private fun relevant(path: String): Boolean = LspGraphqlProfile.supportsPath(path) || relative(path) in LspGraphqlConfiguration.competitors + listOf(LspGraphqlConfiguration.CONFIG, "package.json")
    fun observeEditorDocument(path: String, text: String, opened: Boolean = false) {
        if (SensitivePathPolicy.isSensitive(path)) return
        val absolute = runCatching { full(path) }.getOrNull() ?: return; if (!relevant(absolute)) return
        val owned = synchronized(lock) {
            if (closed) return
            if (!opened && buffers[absolute]?.text == text) return
            session?.takeIf { it.active && (absolute in it.snapshot.capturedOverlays || opened && absolute in it.documents || relative(absolute) in LspGraphqlConfiguration.competitors) }?.owner
        }
        fenceForClientRetirement(owned)
        synchronized(lock) {
            if (closed) return
            val previous = buffers[absolute]
            val generation = if (opened || previous == null) ++nextOpen else previous.openGeneration
            buffers[absolute] = LspGraphqlSnapshotAuthority.Overlay(text, generation, ++revision)
            invalidateResultsLocked()
            if (owned != null && session?.owner === owned || session?.snapshot?.capturedOverlays?.containsKey(absolute) == true || relative(absolute) in LspGraphqlConfiguration.competitors)
                { retireLocked("CAPTURING: GraphQL dependency editor changed; current snapshot withdrawn."); scheduleRefreshLocked() }
            changed()
        }
        publishInvalidation()
    }
    fun closeEditorDocument(path: String) {
        val absolute = runCatching { full(path) }.getOrNull() ?: return
        val observed = synchronized(lock) { buffers[absolute] ?: return }
        val owned = synchronized(lock) {
            session?.takeIf { it.active && (absolute in it.documents || absolute in it.snapshot.capturedOverlays) }?.owner
        }
        fenceForClientRetirement(owned)
        synchronized(lock) {
            if (buffers[absolute]?.openGeneration != observed.openGeneration) return
            buffers.remove(absolute); ++revision; invalidateResultsLocked()
            if (owned != null && session?.owner === owned) {
                retireLocked("CAPTURING: GraphQL editor lifetime closed; fresh replay required."); scheduleRefreshLocked()
            }
            changed()
        }
        publishInvalidation()
    }
    fun observeEditorSave(path: String) {
        val absolute = runCatching { full(path) }.getOrNull() ?: return
        val owned = synchronized(lock) {
            if (closed || absolute !in buffers) return
            session?.takeIf { it.active && absolute in it.snapshot.capturedOverlays }?.owner
        }
        fenceForClientRetirement(owned)
        synchronized(lock) {
            if (owned != null && session?.owner === owned) {
                retireLocked("CAPTURING: GraphQL dependency saved; fresh observation required."); scheduleRefreshLocked()
            }
        }
        publishInvalidation()
    }
    /** A parent's pre-save snapshot can confirm an existing lifetime, never create or replace it.
     * A delayed generic post-save publication is deliberately not an editor observation here. */
    fun confirmEditorSave(path: String, currentLedgerText: String?) {
        val absolute = runCatching { full(path) }.getOrNull() ?: return
        synchronized(lock) {
            val existing = buffers[absolute] ?: return
            if (currentLedgerText != existing.text) return
            // Identity and revision remain unchanged. Save only records disk activity.
        }
    }
    override suspend fun didOpen(path: String, text: String) = observeEditorDocument(path, text, true)
    override suspend fun didChange(path: String, text: String) = observeEditorDocument(path, text)
    override suspend fun didClose(path: String) = closeEditorDocument(path)
    override suspend fun didSave(path: String, text: String?) = observeEditorSave(path)
    override suspend fun didSaveSnapshot(path: String, savedText: String, currentText: String) = observeEditorSave(path)
    override fun workspaceFilesChanged() { fenceForClientRetirement(admissionOwner); synchronized(lock) { retireLocked("CAPTURING: Workspace files changed; GraphQL observation withdrawn."); scheduleRefreshLocked() }; publishInvalidation() }
    override suspend fun enableProjectAnalysis(path: String): String {
        val absolute = full(path)
        synchronized(lock) { check(!closed && routeLocked()); require(absolute in buffers); if (permit?.let(consent::isCurrent) == true) return detail }
        // Metadata-only root/package identity precedes approval. No project-content read or capture here.
        val admissionDeadline = GraphqlDeadline.after(60_000)
        val root = withContext(Dispatchers.IO) { LspGraphqlSnapshotAuthority.root(workspace) }
        val native = withContext(Dispatchers.IO) { requireNotNull(host?.reviewedGraphqlLaunchIdentity(LspGraphqlProfile.COMMAND, workspace.absolutePath, admissionDeadline)) { "Install the exact reviewed GraphQL and private Node prerequisites first" } }
        val identity = LspProjectAnalysisIdentity(workspace.absolutePath, LspGraphqlProfile.ID, root.path,
            LspGraphqlSnapshotAuthority.sha(root.identity + "\u0000" + native.consentIdentity + "\u0000" + LspGraphqlProfile.IDENTITY))
        val ticket = synchronized(lock) {
            check(!closed && routeLocked()); consent.begin(identity)?.also { pending = it; detail = "APPROVAL_PENDING: No GraphQL project content has been captured."; changed() } ?: error("GraphQL workspace closed")
        }
        try {
        val allowed = approve(LspProjectAnalysisRequest(LspGraphqlProfile.ID, workspace.absolutePath, root.path,
            listOf(listOf(LspGraphqlProfile.COMMAND)), LspGraphqlProfile.APPROVAL_EXPLANATION, LspGraphqlProfile.INSTALLATION_DISCLOSURE,
            executionDisclosure = "The selected config is ${root.path}/${LspGraphqlConfiguration.CONFIG}. One grant covers fresh bounded local snapshots for this workspace lifetime; content edits do not request another grant.")) {
            synchronized(lock) { !closed && pending === ticket && routeLocked() && consent.isCurrent(ticket) }
        }
        if (!allowed) { synchronized(lock) { if (pending === ticket) { pending = null; detail = "DISABLED: GraphQL approval declined."; changed() } }; return "GraphQL analysis remains disabled." }
        // The startup total starts after approval, before first content capture or allocation.
        val deadline = GraphqlDeadline.after(60_000)
        val freshRoot = withContext(Dispatchers.IO) { LspGraphqlSnapshotAuthority.root(workspace) }
        val freshIdentity = withContext(Dispatchers.IO) { host?.reviewedGraphqlLaunchIdentity(LspGraphqlProfile.COMMAND, workspace.absolutePath, deadline) }
        synchronized(lock) {
            require(freshRoot.identity == root.identity && freshIdentity == native)
            check(!closed && pending === ticket && routeLocked())
            permit = consent.approve(ticket) ?: error("GraphQL approval expired")
            pending = null; grantRecorded = true; this.identity = native; grantedRoot = root; ++lifetime
            detail = "CAPTURING: Approved GraphQL local inputs."; phase = LspProjectAnalysisReadiness.PREPARING; changed()
            startLocked(deadline)
        }
        return retryProjectAnalysis(path)
        } finally {
            synchronized(lock) {
                if (pending === ticket) {
                    consent.revoke(ticket.identity); pending = null
                    phase = LspProjectAnalysisReadiness.ON_DEMAND
                    detail = "DISABLED: GraphQL approval did not complete."
                    changed()
                }
            }
        }
    }
    private fun startLocked(deadline: GraphqlDeadline = GraphqlDeadline.after(60_000)): Deferred<Session> {
        check(grantedLocked() && !cleanupUnknown)
        starting?.takeIf { it.isActive }?.let { return it }
        session?.takeIf { it.ready && currentLocked(it) }?.let { return CompletableDeferred(it) }
        val generation = lifetime; val epoch = startupEpoch; val native = requireNotNull(identity); val grant = requireNotNull(permit)
        return ownedScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { prepare(generation, epoch, native, grant, deadline) }.also { task ->
            starting = task; task.invokeOnCompletion { synchronized(lock) { if (starting === task) starting = null } }; task.start()
        }
    }
    private suspend fun prepare(generation: Long, epoch: Long, native: GraphqlManagedLaunchIdentity, grant: LspProjectAnalysisConsent.Permit, deadline: GraphqlDeadline): Session {
        var owner: GraphqlPreparedOwner? = null; var current: Session? = null
        try {
            val workDeadline = deadline.reserve(10_000)
            cleanup?.join(); workDeadline.check()
            val overlays = synchronized(lock) { check(grantedLocked() && lifetime == generation && epoch == startupEpoch && permit === grant && !cleanupUnknown); buffers.toMap() }
            val snapshot = LspGraphqlSnapshotAuthority.capture(workspace, overlays)
            workDeadline.check()
            synchronized(lock) {
                check(grantedLocked() && lifetime == generation && epoch == startupEpoch && permit === grant && snapshot.overlaysCurrent(buffers))
                if (snapshot.root.identity != grantedRoot?.identity) { revokeLocked("DISABLED: GraphQL root identity changed; fresh enablement required.", keepRecord = true); error("GraphQL root identity changed") }
                detail = "BUILDING: Native GraphQL SDL and fragment validation pending."; phase = LspProjectAnalysisReadiness.PREPARING; changed()
            }
            val remote = requireNotNull(host)
            require(remote.reviewedGraphqlLaunchIdentity(LspGraphqlProfile.COMMAND, workspace.absolutePath, workDeadline) == native)
            val sources = snapshot.sources; val descriptor = GraphqlDescriptorCodec.sources(sources).toString().toByteArray(Charsets.UTF_8)
            require(descriptor.size <= LspGraphqlSnapshotAuthority.MAX_DESCRIPTOR_BYTES)
            val request = GraphqlOwnerRequest(workspace.absolutePath, snapshot.root.path, "$workspaceLifetime:$generation", snapshot.root.identity,
                LspGraphqlProfile.rootUri(snapshot.root.path), synchronized(lock) { ++ownerGeneration }, LspGraphqlSnapshotAuthority.sha(grant.identity.configuration),
                snapshot.generation, snapshot.configDigest, snapshot.schemaDigest, snapshot.fragmentsDigest, sources, GraphqlDescriptorCodec.digest(sources))
            owner = remote.prepareReviewedGraphqlOwner(native, request, deadline)
            val s = Session(snapshot, grant, native, owner, generation, epoch, deadline); current = s
            val adapter = object : StdioProcessHost by remote {
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                    require(argv == listOf(native.launchExecutable) && environment.isEmpty() && resourceLimits == null)
                    return remote.startReviewedGraphql(s.owner, deadline) { create ->
                        synchronized(lock) { check(currentLocked(s)); workDeadline.check() }
                        // Native factory never holds the client lifetime monitor. The host's independent
                        // owner fence is rechecked at creation and retains any late handle for cleanup.
                        create().also { process -> synchronized(lock) { s.process = process; check(currentLocked(s)) } }
                    }.also { process -> process.onControl {
                        if (process.fatalControl != null) {
                            synchronized(lock) { if (session === s && s.active) retireLocked("INPUT_FAILED: Native GraphQL control failure; owner fenced.") }
                            publishInvalidation()
                        }
                    } }
                }
            }
            s.rpc = JsonRpcProcess(ownedScope, workspace, listOf(native.launchExecutable), processHost = adapter,
                sessionAdmission = { frame -> wireAdmission(s, frame) },
                inboundRequestHandler = { _, _ -> error("GraphQL client callbacks, edits and commands are unavailable") })
            synchronized(lock) { check(grantedLocked() && lifetime == generation && epoch == startupEpoch && permit === grant && snapshot.overlaysCurrent(buffers)); session = s; admissionOwner = s.owner; dependencyPaths = snapshot.capturedOverlays.keys }
            check(LspGraphqlSnapshotAuthority.observeCurrent(snapshot, overlays))
            observe(s)
            s.rpc.start()
            val initialize = ownedRequest(s, null, null, "initialize", buildJsonObject {
                put("processId", JsonNull); put("rootUri", LspGraphqlProfile.rootUri(snapshot.root.path)); put("capabilities", LspGraphqlProfile.clientCapabilities())
            }, deadline)
            s.caps = LspGraphqlProfile.capabilities(initialize)
            ownedNotify(s, null, null, "initialized", buildJsonObject {}, deadline)
            requireNotNull(s.process).awaitBootstrapAccepted(workDeadline)
            val status = ownedRequest(s, null, null, "droide/graphqlStatus", buildJsonObject {}, deadline) as? JsonObject ?: error("GraphQL readiness response missing")
            require(status["ready"] == JsonPrimitive(true)) { "Native GraphQL schema and fragments are not ready" }
            synchronized(lock) {
                check(currentLocked(s)); workDeadline.check(); s.ready = true; s.observedAtNanos = System.nanoTime()
                phase = LspProjectAnalysisReadiness.READY; detail = "READY: Current native SDL and fragment snapshot accepted."; changed()
            }
            return s
        } catch (failure: Throwable) {
            val rootNow = withContext(NonCancellable + Dispatchers.IO) { runCatching { LspGraphqlSnapshotAuthority.root(workspace).identity }.getOrNull() }
            synchronized(lock) {
                if (lifetime == generation && permit === grant && rootNow != grantedRoot?.identity)
                    revokeLocked("DISABLED: GraphQL root unavailable or replaced; fresh enablement required.", keepRecord = true)
                if (current != null && session === current) retireLocked("INPUT_FAILED: ${failure.message?.take(200).orEmpty()}")
                else if (!closed && lifetime == generation) { phase = LspProjectAnalysisReadiness.FAILED; detail = "INPUT_FAILED: ${failure.message?.take(200).orEmpty()}"; changed() }
            }
            publishInvalidation()
            if (owner != null && (current == null || synchronized(lock) { session !== current })) {
                owner.fence()
                withContext(NonCancellable) { val receipt = runCatching { owner.retire(deadline) }.getOrNull(); if (receipt?.complete != true) synchronized(lock) { cleanupUnknown = true; detail = "CLEANUP_UNKNOWN: GraphQL replacement blocked."; changed() } }
            }
            throw failure
        }
    }
    private fun observe(s: Session) {
        s.poller = ownedScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(500)
                val observations = synchronized(lock) { if (session !== s || !s.active) return@launch; buffers.toMap() to s.primaryObservations.values.toList() }
                val rootIdentity = runCatching { LspGraphqlSnapshotAuthority.root(workspace).identity }.getOrNull()
                val observed = runCatching { LspGraphqlSnapshotAuthority.observeCurrent(s.snapshot, observations.first) && observations.second.all(LspGraphqlSnapshotAuthority::primaryCurrent) }.getOrDefault(false)
                synchronized(lock) {
                    if (session !== s || !s.active) return@launch
                    if (rootIdentity != s.snapshot.root.identity || !routeCurrent()) {
                        revokeLocked("DISABLED: GraphQL root or route identity changed; fresh enablement required.", keepRecord = true)
                    }
                    else if (!s.owner.isCurrent && clientFencedOwner !== s.owner) {
                        revokeLocked("DISABLED: GraphQL managed owner authority was lost; fresh enablement required.", keepRecord = true)
                    }
                    else if (!s.owner.isCurrent) {
                        retireLocked("RETIRING: GraphQL client retirement is already in progress.")
                    }
                    else if (!observed || !s.snapshot.overlaysCurrent(buffers)) { retireLocked("CAPTURING: GraphQL source observation changed; results withdrawn."); scheduleRefreshLocked() }
                    else s.observedAtNanos = System.nanoTime()
                }
                publishInvalidation()
                if (!s.active) return@launch
            }
        }
    }
    /** One granted background preparation owns its original 60-second total. Cold feature
     * demand may expire at 15 seconds without resetting or abandoning that independent owner. */
    private fun scheduleRefreshLocked() {
        if (!grantedLocked() || cleanupUnknown || refresh?.isActive == true) return
        phase = LspProjectAnalysisReadiness.PREPARING
        refresh = ownedScope.launch(Dispatchers.IO) {
            delay(50)
            val previous = synchronized(lock) { starting }
            runCatching { previous?.await() }
            val next = synchronized(lock) { if (!grantedLocked() || cleanupUnknown) return@launch; startLocked() }
            runCatching { next.await() }
        }
    }
    private fun authority(s: Session, capture: Capture?, generation: Long): JsonObject = buildJsonObject {
        put("rootIdentity", s.snapshot.root.identity); put("configDigest", s.snapshot.configDigest); put("schemaDigest", s.snapshot.schemaDigest); put("fragmentsDigest", s.snapshot.fragmentsDigest)
        put("uri", capture?.uri?.let(::JsonPrimitive) ?: JsonNull); put("openGeneration", capture?.overlay?.openGeneration?.let(::JsonPrimitive) ?: JsonNull)
        put("version", capture?.overlay?.revision?.let(::JsonPrimitive) ?: JsonNull); put("textDigest", capture?.digest?.let(::JsonPrimitive) ?: JsonNull); put("requestGeneration", generation)
    }
    private fun wireAdmission(s: Session, frame: JsonObject?) = synchronized(lock) {
        check(currentLocked(s))
        if (frame == null) { s.startDeadline.reserve(10_000).check(); return@synchronized }
        if (frame["method"] == JsonPrimitive("$/cancelRequest")) return@synchronized
        val params = frame["params"] as? JsonObject ?: error("GraphQL frame params missing")
        val ticket = s.wire[params] ?: error("GraphQL queued frame lost its captured ticket")
        ticket.deadline.reserve(if (ticket.capture == null) 10_000 else 5_000).check(); ticket.capture?.let { check(currentLocked(s, it)) }; ticket.operation?.markDispatched()
        val process = requireNotNull(s.process)
        val method = (frame["method"] as? JsonPrimitive)?.content
        if (method == "textDocument/didOpen" || method == "textDocument/didChange") {
            val capture = requireNotNull(ticket.capture)
            process.registerDocument(capture.uri, capture.overlay.openGeneration, capture.overlay.revision, capture.digest)
        }
        (frame["id"] as? JsonPrimitive)?.let { process.registerRequest(it, ticket.authority, ticket.deadline) }
    }
    private suspend fun ownedRequest(s: Session, capture: Capture?, operation: LspGraphqlRequests.Operation?, method: String, params: JsonObject,
        deadline: GraphqlDeadline): JsonElement {
        val number = synchronized(lock) { ++requestGeneration }; val auth = authority(s, capture, number)
        val sent = JsonObject(params + ("_graphqlAuthority" to auth)); val ticket = Ticket(capture, operation, deadline, auth, number)
        synchronized(lock) { check(currentLocked(s)); require(s.wire.size < 16); s.wire[sent] = ticket }
        try {
            val workDeadline = deadline.reserve(if (capture == null) 10_000 else 5_000)
            val value = s.rpc.request(method, sent, workDeadline.remainingMillis(), "$/cancelRequest")
            workDeadline.check(); check(synchronized(lock) { if (capture == null) currentLocked(s) else currentLocked(s, capture) })
            require(value.toString().toByteArray(Charsets.UTF_8).size <= 2_097_152)
            return value
        } finally { synchronized(lock) { s.wire.remove(sent) } }
    }
    private suspend fun ownedNotify(s: Session, capture: Capture?, operation: LspGraphqlRequests.Operation?, method: String, params: JsonObject, deadline: GraphqlDeadline) {
        val number = synchronized(lock) { ++requestGeneration }; val auth = authority(s, capture, number)
        val sent = JsonObject(params + ("_graphqlAuthority" to auth)); val ticket = Ticket(capture, operation, deadline, auth, number)
        synchronized(lock) { check(currentLocked(s)); require(s.wire.size < 16); s.wire[sent] = ticket }
        try { s.rpc.notify(method, sent) } finally { synchronized(lock) { s.wire.remove(sent) } }
    }
    private suspend fun synchronize(s: Session, capture: Capture, operation: LspGraphqlRequests.Operation, deadline: GraphqlDeadline) = s.syncMutex.withLock {
        val old = synchronized(lock) { check(currentLocked(s, capture)); s.documents[capture.path] }
        if (old === capture.overlay) return@withLock
        val first = old == null
        require(old == null || old.openGeneration == capture.overlay.openGeneration) { "GraphQL reopened document requires a new owner" }
        ownedNotify(s, capture, operation, if (first) "textDocument/didOpen" else "textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", capture.uri); put("version", capture.overlay.revision)
                if (first) { put("languageId", "graphql"); put("text", capture.text) }
            })
            if (!first) put("contentChanges", buildJsonArray { add(buildJsonObject { put("text", capture.text) }) })
        }, deadline)
        synchronized(lock) { check(currentLocked(s, capture)); s.documents[capture.path] = capture.overlay }
    }
    private suspend fun <T> feature(path: String, method: String, suppliedText: String? = null, line: Int? = null, column: Int? = null,
        block: suspend (Session, Capture, LspGraphqlRequests.Operation, GraphqlDeadline) -> T): T? {
        val absolute = full(path)
        val capture = synchronized(lock) {
            check(grantedLocked()) { "Enable native GraphQL analysis first" }
            require(LspGraphqlProfile.supportsPath(path) && routeForPath(path))
            val overlay = buffers[absolute] ?: error("GraphQL requires an open authoritative editor")
            require(suppliedText == null || suppliedText == overlay.text)
            Capture(absolute, overlay, lifetime, ++requestGeneration)
        }
        return requests.owned(method, { synchronized(lock) { captureCurrentLocked(capture) } }, documentKey = "${capture.path}:${capture.overlay.openGeneration}") { operation ->
            val deadline = GraphqlDeadline.at(operation.totalDeadlineNanos)
            require(LspGraphqlPositionCodec.validText(capture.text) && capture.text.toByteArray(Charsets.UTF_8).size <= 1_048_576)
            capture.digest = LspGraphqlSnapshotAuthority.sha(capture.text)
            val s = synchronized(lock) { startLocked() }.await()
            check(operation.isDemandCurrent() && synchronized(lock) { currentLocked(s, capture) })
            val replay = synchronized(lock) { buffers.filterKeys { LspGraphqlProfile.supportsPath(it) && routeForPath(relative(it)) } }
            require(replay.size <= 32 && replay.values.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } <= 8_388_608)
            val primaryObservation = withContext(Dispatchers.IO) { LspGraphqlSnapshotAuthority.primary(s.snapshot.root, capture.path) }
            synchronized(lock) { check(currentLocked(s, capture)); s.primaryObservations[capture.path] = primaryObservation }
            check(operation.isDemandCurrent())
            if (method != "textDocument/diagnostic") require(s.snapshot.entries[capture.path]?.role != "schema") { "Schema-role tabs expose native errors; executable completion/hover is unavailable" }
            if (line != null) require(LspGraphqlPositionCodec.cursor(capture.text, line - 1, requireNotNull(column) - 1) != null) { "CURSOR_UNSUPPORTED: GraphQL cursor splits a scalar/newline or uses bare CR" }
            if (s.snapshot.entries[capture.path]?.role != "schema") synchronize(s, capture, operation, deadline)
            val result = block(s, capture, operation, deadline)
            check(operation.isDemandCurrent() && synchronized(lock) { currentLocked(s, capture) }); result
        }
    }
    private fun params(capture: Capture, line: Int? = null, column: Int? = null): JsonObject = buildJsonObject {
        put("textDocument", buildJsonObject { put("uri", capture.uri) })
        if (line != null) put("position", buildJsonObject { put("line", line - 1); put("character", requireNotNull(column) - 1) })
    }
    override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> = completionItems(path, line, col, null, null)
    override suspend fun completionItems(path: String, line: Int, col: Int, triggerCharacter: String?, currentText: String?): List<LspCompletionItem> =
        feature(path, "textDocument/completion", currentText, line, col) { s, capture, operation, deadline ->
            check(s.caps.completion); val context = requireNotNull(LspGraphqlCompletionContext.capture(capture.text, line, col)); val at = synchronized(lock) { ++batch }
            val raw = ownedRequest(s, capture, operation, "textDocument/completion", params(capture, line, col), deadline)
            val guard = LspCompletionApplicationGuard(capture.text, context.caret) { synchronized(lock) { batch == at && currentLocked(s, capture) } }
            val result = LspGraphqlCompletionContext.parse(raw, context, guard, at)
            if (result.followOnUnsupported) synchronized(lock) { if (currentLocked(s, capture)) { detail = "READY: Native insertion retained. Request the next suggestions manually; automatic follow-on is unavailable."; changed() } }
            result.items
        }.orEmpty()
    private fun guard(s: Session, capture: Capture) = LspHeldResultGuard { synchronized(lock) { currentLocked(s, capture) } }
    override suspend fun heldTextResult(op: String, path: String, line: Int, col: Int): LspHeldTextResult {
        require(op == "hover" || op == "documentSymbol") { LspGraphqlProfile.FEATURE_NOTE }
        return feature(path, "textDocument/$op", line = if (op == "hover") line else null, column = if (op == "hover") col else null) { s, capture, operation, deadline ->
            val heldGuard = guard(s, capture)
            val raw = ownedRequest(s, capture, operation, "textDocument/$op", params(capture, if (op == "hover") line else null, col), deadline)
            val text = if (op == "hover") LspGraphqlResultAuthority.hover(raw) else LspGraphqlResultAuthority.symbols(raw, capture.text, capture.uri, relative(capture.path), heldGuard).joinToString("\n") { it.name }.ifEmpty { "No native document symbols." }
            synchronized(lock) { check(currentLocked(s, capture)); held[capture.path] = heldGuard }; LspHeldTextResult(text, heldGuard)
        } ?: LspHeldTextResult("GraphQL result became stale.")
    }
    override suspend fun symbolItems(path: String): List<LspSymbol> = feature(path, "textDocument/documentSymbol") { s, capture, operation, deadline ->
        LspGraphqlResultAuthority.symbols(ownedRequest(s, capture, operation, "textDocument/documentSymbol", params(capture), deadline), capture.text, capture.uri, relative(capture.path), guard(s, capture))
    }.orEmpty()
    override suspend fun diagnose(relPath: String): String = feature(relPath, "textDocument/diagnostic") { s, capture, operation, deadline ->
        if (s.snapshot.entries[capture.path]?.role == "schema") return@feature "Native GraphQL schema snapshot accepted. Schema-role tabs expose snapshot status; executable-query diagnostics are not requested."
        val sources = s.snapshot.entries.values.filter { it.role != "config" }.associate { it.path.let(LspGraphqlProfile::uri) to LspGraphqlResultAuthority.Source(it.text, it.overlay?.openGeneration, it.overlay?.revision, it.digest, if (it.overlay != null) "editor" else "disk") }.toMutableMap()
        sources[capture.uri] = LspGraphqlResultAuthority.Source(capture.text, capture.overlay.openGeneration, capture.overlay.revision, capture.digest)
        val reports = LspGraphqlResultAuthority.diagnostics(ownedRequest(s, capture, operation, "textDocument/diagnostic", params(capture), deadline), capture.uri, sources, s.snapshot.generation)
        synchronized(lock) { publishLocked(s, capture, reports) }
        reports.values.flatten().let { if (it.isEmpty()) "No native GraphQL diagnostics for this exact current snapshot." else it.joinToString("\n") { item -> "${item.line}:${item.column}: ${item.message}" } }
    } ?: "GraphQL diagnostic request became stale."
    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = when (op) {
        "hover", "documentSymbol" -> heldTextResult(op, path, line, col).text
        "completion" -> completionItems(path, line, col).joinToString("\n") { it.label }
        else -> "This native GraphQL operation is unavailable. ${LspGraphqlProfile.FEATURE_NOTE}"
    }
    override suspend fun retryProjectAnalysis(path: String): String {
        val startup = synchronized(lock) { startLocked() }
        try { startup.await() } catch (cancel: CancellationException) { currentCoroutineContext().ensureActive() } catch (_: Exception) { }
        return synchronized(lock) { detail }
    }
    private fun retireLocked(reason: String, retirementDeadline: GraphqlDeadline? = null) {
        ++startupEpoch
        starting?.cancel(CancellationException("GraphQL startup authority retired"))
        invalidateResultsLocked(); detail = reason; phase = LspProjectAnalysisReadiness.FAILED
        val old = session
        if (old != null && old.active) {
            old.active = false; old.owner.fence(); old.poller?.cancel()
            val previous = cleanup
            val deadline = retirementDeadline ?: requests.earliestDispatchedDeadlineNanos()?.let { GraphqlDeadline.at(it) }
                ?: if (!old.ready) old.startDeadline else GraphqlDeadline.after(10_000)
            cleanup = retirementScope.launch {
                previous?.join()
                runCatching { old.rpc.close() }
                val receipt = runCatching { old.owner.retire(deadline) }.getOrNull()
                synchronized(lock) {
                    if (receipt?.complete == true) { if (session === old) { session = null; admissionOwner = null; dependencyPaths = emptySet() } }
                    else { cleanupUnknown = true; detail = "CLEANUP_UNKNOWN: GraphQL owner cleanup unconfirmed; replacement blocked." }
                    changed()
                }
            }
        }
        changed()
    }
    private fun revokeLocked(reason: String, keepRecord: Boolean = false) {
        if (!keepRecord) grantRecorded = false
        pending?.let { consent.revoke(it.identity) }; pending = null
        permit?.let { consent.revoke(it.identity) }; permit = null; identity = null; grantedRoot = null; ++lifetime
        retireLocked(reason); phase = LspProjectAnalysisReadiness.ON_DEMAND
    }
    override fun revokeProjectAnalysis(path: String): String {
        fenceForClientRetirement(admissionOwner)
        val result = synchronized(lock) { revokeLocked("DISABLED: GraphQL analysis revoked; exact cleanup remains owned."); detail }
        publishInvalidation()
        return result
    }
    override fun retireProjectAnalysisAuthority(): () -> Unit {
        fenceForClientRetirement(admissionOwner)
        synchronized(lock) { revokeLocked("DISABLED: GraphQL workspace closed."); closed = true; consent.close(); requests.close() }
        publishInvalidation()
        return { publications.close(); job.cancel() }
    }
    override fun close() = retireProjectAnalysisAuthority().invoke()
    override suspend fun shutdown() { close(); cleanup?.join() }
}

package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

 
data class LspHeldTextResult(val text: String, val guard: LspHeldResultGuard? = null)

enum class LspCompletionPolicy { POWERSHELL_NATIVE, CMAKE_NATIVE, VUE_NATIVE, CSHARP_NATIVE, TOML_NATIVE, GRAPHQL_NATIVE, ELP_NATIVE }
private val LSP_DOCUMENT_REVISION = java.util.concurrent.atomic.AtomicLong()

interface LspManager : AutoCloseable {
    val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>>
    val statusRevision: StateFlow<Long> get() = INERT_LSP_STATUS_REVISION
    suspend fun diagnose(relPath: String): String
    suspend fun lsp(op: String, path: String, line: Int = 1, col: Int = 1): String
    suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> = emptyList()
    suspend fun completionItems(
        path: String,
        line: Int,
        col: Int,
        triggerCharacter: String?,
        currentText: String?,
    ): List<LspCompletionItem> = completionItems(path, line, col)
    fun completionPolicy(path: String): LspCompletionPolicy? = null
    fun heldResultGuard(path: String): LspHeldResultGuard? = null
    suspend fun heldTextResult(op: String, path: String, line: Int, col: Int): LspHeldTextResult = LspHeldTextResult(lsp(op, path, line, col))
    fun signatureHelpTriggers(path: String): LspSignatureTriggers = LspSignatureTriggers()
    suspend fun signatureHelp(
        path: String,
        line: Int,
        col: Int,
        currentText: String?,
        triggerKind: LspSignatureTriggerKind = LspSignatureTriggerKind.INVOKED,
        triggerCharacter: String? = null,
        isRetrigger: Boolean = false,
        previous: LspSignatureHelp? = null,
    ): LspSignatureHelp? = null
    suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> = emptyList()
    suspend fun inlayHints(path: String, currentText: String?): List<LspInlayHint> = emptyList()
    suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> = emptyList()
    suspend fun symbolItems(path: String): List<LspSymbol> = emptyList()
    suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> = emptyList()
    suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> = emptyList()
    suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> = emptyList()
    suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction = action
    suspend fun prepareCallHierarchy(path: String, line: Int, col: Int): List<LspCallHierarchyItem> = emptyList()
    suspend fun incomingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> = emptyList()
    suspend fun outgoingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> = emptyList()
    suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? = null
    suspend fun formatDocument(path: String): LspWorkspaceEdit? = null
    /** Compatibility default for managers without formatting; active formatters override this. */
    suspend fun formatDocument(path: String, options: LspFormattingOptions): LspWorkspaceEdit? =
        formatDocument(path)
    suspend fun didOpen(path: String, text: String) {}
    suspend fun didChange(path: String, text: String) {}
    suspend fun didSave(path: String, text: String? = null) {}
    /** Disk snapshot and live editor text can differ when typing continues during Save. */
    suspend fun didSaveSnapshot(path: String, savedText: String, currentText: String) {
        didSave(path, savedText)
        if (currentText != savedText) didChange(path, currentText)
    }
    suspend fun didClose(path: String) {}
    /** Persisted IDE mutations may invalidate a server-owned disk index; never starts tools. */
    fun workspaceFilesChanged() {}
    fun environmentInvalidated() {}
    fun startupFailure(path: String): LspStartupFailure? = null
    suspend fun retryStartup(path: String): String = "No failed language-server startup to retry."
    fun serverStatus(path: String): String = "fallback"
    fun documentSupportStatus(path: String, currentText: String? = null): LspDocumentSupportStatus? = null
    fun projectAnalysisStatus(path: String): LspProjectAnalysisStatus? = null
    fun csharpSelectionStatus(path: String): CSharpAnalysisSelections.Status? = null
    suspend fun saveCsharpSelection(path: String, rootPath: String, mode: CSharpAnalysisSelections.Mode, solutionPath: String?): String =
        "C# analysis selection is unavailable for this workspace."
    fun cmakeSelectionStatus(path: String): CMakeAnalysisSelections.Status? = null
    suspend fun saveCmakeSelection(path: String, sourcePath: String, configuredBuildPath: String?): String =
        "CMake source/build selection is unavailable for this workspace."
    suspend fun enableProjectAnalysis(path: String): String = "This language server has no project-analysis enablement action."
    suspend fun retryProjectAnalysis(path: String): String = "This server has no background analysis retry action."
    fun revokeProjectAnalysis(path: String): String = "No project-analysis grant exists."
    /** Invalidates authority synchronously; cancellation/process I/O belongs to the returned cleanup. */
    fun retireProjectAnalysisAuthority(): () -> Unit = {}
    suspend fun shutdown() { close() }
    override fun close() {}
}

private val INERT_LSP_STATUS_REVISION: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

/** Temporary built-in startup recovery never resolves or starts a language server or CLI tool. */
class TemporaryRecoveryLspManager : LspManager {
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> =
        MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap()).asStateFlow()

    override suspend fun diagnose(relPath: String): String = "Diagnostics unavailable. $UNAVAILABLE_REASON"

    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = UNAVAILABLE_REASON

    override fun serverStatus(path: String): String = UNAVAILABLE_REASON

    // All other operations retain LspManager's inert defaults, including close and shutdown.
    private companion object {
        const val UNAVAILABLE_REASON =
            "Language servers and installed tools are temporarily unavailable in built-in startup recovery."
    }
}

data class LspDiagnostic(
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: Int?,
    val message: String,
    val source: String?,
    val code: JsonPrimitive? = null,
    val reportGuard: LspHeldResultGuard? = null,
    val originNote: String? = null,
)

data class LspLocation(
    val path: String,
    val line: Int,
    val column: Int,
    val activationGuard: LspHeldResultGuard? = null,
)

data class LspSymbol(
    val name: String,
    val kind: Int?,
    val path: String,
    val line: Int,
    val column: Int,
    val depth: Int = 0,
    val activationGuard: LspHeldResultGuard? = null,
)

data class LspTextEdit(
    val path: String,
    val range: TextRangeEdit,
)

data class LspWorkspaceEdit(
    val edits: List<LspTextEdit>,
     
    val expectedContent: Map<String, String> = emptyMap(),
    val documentVersions: Map<String, Int> = emptyMap(),
    /** Optional exact-owner preflight; does not promise rollback after an application failure. */
    val applicationGuard: (() -> Boolean)? = null,
) {
    val fileCount: Int get() = edits.map { it.path }.distinct().size
}

data class LspCallHierarchyItem(
    val name: String,
    val detail: String? = null,
    val kind: Int? = null,
    val path: String,
    val line: Int,
    val column: Int,
    val rawJson: String,
    val activationGuard: LspHeldResultGuard? = null,
)

data class LspCodeAction(
    val title: String,
    val kind: String? = null,
    val preferred: Boolean = false,
    val edit: LspWorkspaceEdit? = null,
    val commandTitle: String? = null,
    val disabledReason: String? = null,
    val rawJson: String? = null,
    val activationGuard: LspHeldResultGuard? = null,
)


class ProfessionalLspManager(
    private val scope: CoroutineScope,
    private val files: FileRepository,
    private val fallback: LspManager,
    private val executableDirs: List<File> = emptyList(),
    private val remoteProcessHost: StdioProcessHost? = null,
    private val extraLanguageServers: () -> List<LanguageServerSpec> = { emptyList() },
    private val approveProjectAnalysis: suspend (LspProjectAnalysisRequest, () -> Boolean) -> Boolean = { _, _ -> false },
) : LspManager {
    private data class ServerKey(
        val serverId: String,
        val projectPath: String,
        val initializationOptions: JsonObject,
        val analysisConfiguration: String?,
        val marksmanConfiguration: String?,
        val publishesWorkspaceRoot: Boolean,
        val workspaceMutationEpoch: Long,
        val sqruffTextEpoch: Long,
        val cmakeRefreshEpoch: Long = 0L,
        val nimRefreshEpoch: Long = 0L,
        // Generic servers have no project-analysis permit, but replacing an extension's
        // executable/arguments or route must still create a distinct process owner.
        val launchConfiguration: String = "",
    )

    private data class ServerState(
        val key: ServerKey,
        val projectRoot: File,
        val spec: LanguageServerSpec,
        val rpc: JsonRpcProcess,
        val command: List<String>,
        val processHost: StdioProcessHost,
        val pathMapper: WorkspacePathMapper? = null,
        val capabilities: LspNegotiatedCapabilities,
        val analysisPermit: LspProjectAnalysisConsent.Permit?,
        val sqruffIdentity: SqruffManagedLaunchIdentity? = null,
        val powershellIdentity: PowerShellManagedLaunchIdentity? = null,
        val svelteIdentity: SvelteManagedLaunchIdentity? = null,
        val nimProcess: NimHostedProcess? = null,
        val nimOwner: NimPreparedOwner? = null,
        val cmakeSelection: CMakeAnalysisSelections.Selection? = null,
        val cmakeProcess: CMakeHostedProcess? = null,
        val cmakeOwner: CMakePreparedOwner? = null,
        val vueOwner: VuePreparedOwner? = null,
        val vueProcess: VueHostedProcess? = null,
        val vueEpoch: Long = 0L,
        val vueIdentity: VueManagedLaunchIdentity? = null,
    ) {
        @Volatile var vueActive = true // lifecycle event token; acceptance never probes a process
        var vueWatch: LspVueWatchAuthority? = null
        var vueObservation: LspVueWatchAuthority.Observation? = null
        var vueRequests: LspVueRequests? = null
        val vueSync = LspVueCompanionAuthority()
        var vueReceipt: LspVueCompanionAuthority.Receipt? = null
        val vueTickets = java.util.IdentityHashMap<JsonElement, VueTicket>() // lifetimeLock
        var vueBatch = 0L // only completion acceptance depends on this
        var vueSignature = 0L
        var vueRequestSerial = 0L
        var vueOutcomeNote = "No captured native result yet."
        var vueDiagnosticGeneration = 0L // native refresh notification, lifetimeLock
        var vueDiagnosticPath: String? = null
        var vueDiagnosticRerun: Job? = null
        var nimObservation: NimMetadataObservation? = null
        var nimRequests: LspNimRequests? = null
        var nimPartialResults = false // lifetimeLock
        val nimTickets = java.util.IdentityHashMap<JsonElement, NimTicket>() // lifetimeLock
        var nimWireTicket: NimTicket? = null // documentMutex + lifetimeLock
        val cmakeTickets = java.util.IdentityHashMap<JsonElement, CMakeTicket>() // lifetimeLock
        var cmakeWireTicket: CMakeTicket? = null // documentMutex + lifetimeLock
        var svelteWatch: LspSvelteWatchAuthority? = null
        val svelteCompanions = LspSvelteCompanionAuthority()
        @Volatile var svelteObservation: LspSvelteWatchAuthority.Observation? = null
        @Volatile var svelteDraining = false
        @Volatile var svelteSuperseded = false
        var svelteWireCapture: Map<String, DocumentSnapshot>? = null // lifetimeLock
        var svelteWireRequest: DocumentRequest? = null // lifetimeLock
        val svelteTickets = java.util.IdentityHashMap<JsonElement, SvelteTicket>() // lifetimeLock
        val documentSync = LspDocumentSyncAuthority(rpc, capabilities.sync, forceInitialFullChange = LspServerProfiles.isPowerShell(spec) || LspServerProfiles.isNim(spec))
        var powershellRequests: LspPowerShellRequests? = null
        val closedDocumentUris = ConcurrentHashMap.newKeySet<String>()
        val documentPaths = ConcurrentHashMap.newKeySet<String>()
        val powershellDocumentUris = ConcurrentHashMap<String, String>()
        val powershellDocumentRequests = ConcurrentHashMap<String, DocumentRequest>()
        val clojureDocumentGenerations = ConcurrentHashMap<String, Long>()
        val prismaWireDocuments = ConcurrentHashMap<String, PrismaWireDocument>()
        val sqruffWireDocuments = ConcurrentHashMap<String, SqruffWireDocument>()
        val sqruffFormatMutex = Mutex()
        @Volatile var sqruffFormatting = false
        var sqruffFormatIssued = false // lifetimeLock, actual serialized writer admission
        var sqruffFormatPath: String? = null // lifetimeLock
        @Volatile var prismaDegraded = false
        @Volatile var powershellRequestUnavailable = false
        val diagnosticRequests = ConcurrentHashMap<String, Long>()
        // Open lifetimes may reuse wire versions. A late push at a retired version
        // cannot certify which lifetime it describes; correlated pulls still can.
        val retiredDiagnosticVersions = ConcurrentHashMap<String, Int>()
        var notificationJob: Job? = null

        fun close() {
            vueActive = false
            vueWatch?.close()
            vueDiagnosticRerun?.cancel()
            vueRequests?.close()
            vueOwner?.close()
            svelteWatch?.close()
            svelteCompanions.close()
            powershellRequests?.close()
            nimRequests?.close()
            notificationJob?.cancel()
            try { rpc.close() } finally { try { cmakeOwner?.close() } finally { nimOwner?.close() } }
        }
    }
    private data class VueTicket(val snapshots: Map<String, DocumentSnapshot>, val mutation: Long,
        val observation: LspVueWatchAuthority.Observation, val request: DocumentRequest? = null,
        val era: Long = 1L,
        val completionBatch: Long? = null, val diagnosticGeneration: Long? = null,
        val operation: LspVueRequests.Operation? = null)
    private class VueOperation(val state: ServerState, val admission: LspVueRequests.Operation,
        val completionBatch: Long?, val signatureSerial: Long?) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<VueOperation>
    }

    private fun vueOwnerCurrent(state: ServerState): Boolean = !closed && managerJob.isActive &&
        servers[state.key] === state && state.vueActive && state.vueOwner?.isCurrent == true && clojureReady(state) &&
        state.analysisPermit?.let(analysisConsent::isCurrent) == true && state.key.workspaceMutationEpoch == diskIndexMutationEpoch &&
        state.vueIdentity == vueIdentity && state.vueObservation?.let { state.vueWatch?.isCurrent(it) } == true &&
        LanguageServerRegistry.forPath("__vue_profile__.vue", extraLanguageServers()).firstOrNull() == state.spec

    private suspend fun <T> vueOwnedOperation(state: ServerState, request: DocumentRequest, method: String,
        block: suspend () -> T): T? {
        val batch = synchronized(lifetimeLock) { if (method == "textDocument/completion") ++state.vueBatch else null }
        val signature = synchronized(lifetimeLock) { if (method == "textDocument/signatureHelp") ++state.vueSignature else null }
        return requireNotNull(state.vueRequests).owned(method, isCurrent = {
            synchronized(lifetimeLock) { vueOwnerCurrent(state) && documentRequestStillCurrent(request) }
        }) { admission -> withContext(VueOperation(state, admission, batch, signature)) { block() } }
    }

    private fun vueMethod(op: String): String? = when (op) {
        "hover" -> "textDocument/hover"
        "completion" -> "textDocument/completion"
        "goToDefinition" -> "textDocument/definition"
        "goToTypeDefinition" -> "textDocument/typeDefinition"
        "goToImplementation" -> "textDocument/implementation"
        "findReferences" -> "textDocument/references"
        "documentSymbol" -> "textDocument/documentSymbol"
        else -> null
    }

    @Volatile private var vueIdentity: VueManagedLaunchIdentity? = null
    private var vueLedgerMutation = 0L // monotonic across eras, closes, reopen and all dirty publications
    private var vueLedgerEra = 1L
    private val vueLedger = LinkedHashMap<String, DocumentSnapshot>() // Vue-owned bounded lifetime view; generic tombstones remain untouched
    private var vueLedgerUnavailable = false
    private val vueOwnerSequence = java.util.concurrent.atomic.AtomicLong()
    private val vueGrantedPermits = ConcurrentHashMap<String, LspProjectAnalysisConsent.Permit>()
    private val vueHeldResults = ConcurrentHashMap<String, LspHeldResultGuard>()
    private fun builtinVue(path: String): LanguageServerSpec? =
        LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.takeIf(LspServerProfiles::isVue)
    override fun heldResultGuard(path: String): LspHeldResultGuard? = if (isElp(path)) elp.heldResultGuard(path) else if (isGraphql(path)) graphql.heldResultGuard(path) else if (isToml(path)) toml.heldResultGuard(path) else if (isCSharp(path)) csharp.heldResultGuard(path) else vueHeldResults[path]

    private data class NimTicket(val method: String, val ledger: Map<String, DocumentSnapshot>,
        val observation: NimMetadataObservation, val request: DocumentRequest? = null)
    private val nimSaveRefreshEpochs = ConcurrentHashMap<String, Long>()
    private val nimOwnerSequence = java.util.concurrent.atomic.AtomicLong()
    private val nimGrantedPermits = ConcurrentHashMap<String, LspProjectAnalysisConsent.Permit>()
    private fun nimRecordedPermit(path: String, projectPath: String): LspProjectAnalysisConsent.Permit? =
        nimGrantedPermits[projectPath]?.takeIf(analysisConsent::isCurrent) ?: nimGrantedPermits.values
            .filter { analysisConsent.isCurrent(it) && File(root, path).path.startsWith(it.identity.projectPath.trimEnd('/') + "/") }
            .maxByOrNull { it.identity.projectPath.length }

    private fun builtinNim(path: String): LanguageServerSpec? =
        LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.takeIf(LspServerProfiles::isNim)

    private data class CMakeTicket(val method: String, val snapshots: Map<String, DocumentSnapshot>,
        val observation: CMakeHostAdmissionObservation, val request: DocumentRequest? = null)
    private val cmakeWorkspaceIdentity = java.util.UUID.randomUUID().toString()
    @Volatile private var cmakeSelections: CMakeAnalysisSelections? = null
    @Volatile private var cmakeIdentity: CMakeManagedLaunchIdentity? = null
    private val cmakeSelectionMutex = Mutex()
    private val cmakeRefreshEpochs = ConcurrentHashMap<String, Long>()
    private val cmakeOwnerSequence = java.util.concurrent.atomic.AtomicLong()

    private data class SvelteTicket(val method: String, val snapshots: Map<String, DocumentSnapshot>,
        val observation: LspSvelteWatchAuthority.Observation, val request: DocumentRequest? = null)
    private val svelteConfigDigests = ConcurrentHashMap<String, String>()
    // Retains only an already-issued authority pointer for unavailable-root status.
    // It cannot authorize startup and is always checked against the consent owner.
    private val svelteGrantedPermits = ConcurrentHashMap<String, LspProjectAnalysisConsent.Permit>()
    private val svelteAdmissions = ConcurrentHashMap<String, DocumentRequest>()
    private val root = files.root.canonicalFile
    private val lifetimeLock = Any()
    private val managerJob = SupervisorJob(scope.coroutineContext[Job])
    private val managerScope = CoroutineScope(scope.coroutineContext + managerJob)
    private val csharp by lazy { CSharpLspManager(scope, root, remoteProcessHost,
        { LanguageServerRegistry.forPath("__csharp_profile__.cs", extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isCSharp) == true }, approveProjectAnalysis,
        onDiagnostics = { native -> synchronized(lifetimeLock) {
            if (!closed) {
                _diagnostics.update { ordinary -> (ordinary - csharpDiagnosticUris) + native }
                csharpDiagnosticUris = native.keys
            }
        } }) }
    private var csharpDiagnosticUris: Set<String> = emptySet()
    private val elp by lazy { ElpLspManager(scope, root, remoteProcessHost,
        { LanguageServerRegistry.forPath("__elp_profile__.erl", extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isElp) == true }, approveProjectAnalysis,
        onDiagnostics = { publication -> synchronized(lifetimeLock) {
            if (!closed) publication.publishIfCurrent { native ->
                _diagnostics.update { ordinary -> (ordinary - elpDiagnosticUris) + native }
                elpDiagnosticUris = native.keys
            }
        } }, routeForPath = ::isElp) }
    private var elpDiagnosticUris: Set<String> = emptySet()
    private fun isElp(path: String): Boolean = LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isElp) == true
    // Capture every editor companion before ordinary-family routing; disk configuration may
    // be non-Erlang and every closed/reopened lifetime must withdraw ELP held results.
    private fun observeElpCompanion(path: String, text: String, opened: Boolean = false) {
        if (!SensitivePathPolicy.isSensitive(path)) elp.observeEditorDocument(path, text, opened)
    }

    private val graphql by lazy { GraphqlLspManager(scope, root, remoteProcessHost,
        { LanguageServerRegistry.forPath("__graphql_profile__.graphql", extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isGraphql) == true }, approveProjectAnalysis,
        onDiagnostics = { publication -> synchronized(lifetimeLock) {
            if (!closed) publication.publishIfCurrent { native ->
                // Outer parent lifetime then inner GraphQL admission; the callback is a pure state update.
                _diagnostics.update { ordinary -> (ordinary - graphqlDiagnosticUris) + native }
                graphqlDiagnosticUris = native.keys
            }
        } }, routeForPath = ::isGraphql) }
    private var graphqlDiagnosticUris: Set<String> = emptySet()
    private fun isGraphql(path: String): Boolean = LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isGraphql) == true
    private val graphqlEditorKeys = mutableMapOf<String, String>()
    private fun graphqlCompanionKey(path: String, record: Boolean = false): String? {
        if (SensitivePathPolicy.isSensitive(path)) return null
        val name = path.substringAfterLast('/')
        if (!LspGraphqlProfile.supportsPath(path) && name !in LspGraphqlConfiguration.competitors + listOf(LspGraphqlConfiguration.CONFIG, "package.json")) return null
        val lexical = runCatching {
            val absolute = root.toPath().resolve(path).normalize()
            require(absolute.startsWith(root.toPath()))
            root.toPath().relativize(absolute).toString()
        }.getOrNull()
        val key = graphqlEditorKeys[path] ?: lexical?.let(graphqlEditorKeys::get)
            ?: runCatching { canonicalPath(path) }.getOrNull() ?: return null
        if (SensitivePathPolicy.isSensitive(key)) return null
        if (record) { graphqlEditorKeys[path] = key; graphqlEditorKeys[key] = key; lexical?.let { graphqlEditorKeys[it] = key } }
        return key
    }
    private fun observeGraphqlCompanion(path: String, text: String, opened: Boolean = false) {
        graphqlCompanionKey(path, record = true)?.let { graphql.observeEditorDocument(it, text, opened) }
    }
    private fun saveGraphqlCompanion(path: String) {
        graphqlCompanionKey(path)?.let { key -> graphql.confirmEditorSave(key, documents[key]?.text); graphql.observeEditorSave(key) }
    }
    private fun closeGraphqlCompanion(path: String) {
        graphqlCompanionKey(path)?.let { key ->
            graphql.closeEditorDocument(key)
            graphqlEditorKeys.entries.removeAll { it.value == key }
        }
    }
    private val toml by lazy { TomlLspManager(scope, root, remoteProcessHost,
        { LanguageServerRegistry.forPath("__toml_profile__.toml", extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isToml) == true }, approveProjectAnalysis,
        onDiagnostics = { native -> synchronized(lifetimeLock) {
            if (!closed) {
                _diagnostics.update { ordinary -> (ordinary - tomlDiagnosticUris) + native }
                tomlDiagnosticUris = native.keys
            }
        } }, routeForPath = ::isToml) }
    private var tomlDiagnosticUris: Set<String> = emptySet()
    private fun isToml(path: String): Boolean = LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isToml) == true
    // Ordinary editor routes keep their existing canonical authority. A path that
    // this read-only profile can never admit must not break an unrelated editor.
    private fun tomlCompanionAllowed(key: String): Boolean = key.isNotEmpty() &&
        !SensitivePathPolicy.isSensitive(File(root, key).path)
    private fun observeTomlCompanion(key: String, text: String) {
        if (tomlCompanionAllowed(key)) toml.observeEditorDocument(key, text)
    }
    private fun saveTomlCompanion(key: String) {
        if (tomlCompanionAllowed(key)) toml.observeEditorSave(key)
    }
    private fun closeTomlCompanion(key: String) {
        if (tomlCompanionAllowed(key)) toml.closeEditorDocument(key)
    }
    private fun isCSharp(path: String): Boolean = LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isCSharp) == true
    override fun csharpSelectionStatus(path: String): CSharpAnalysisSelections.Status? = if (isCSharp(path)) csharp.csharpSelectionStatus(path) else null
    override suspend fun saveCsharpSelection(path: String, rootPath: String, mode: CSharpAnalysisSelections.Mode, solutionPath: String?): String {
        check(isCSharp(path)); return csharp.saveCsharpSelection(path, rootPath, mode, solutionPath)
    }
    @Volatile private var closed = false
    private val startMutex = Mutex()
    private val servers = ConcurrentHashMap<ServerKey, ServerState>()
    private val unavailableUntil = ConcurrentHashMap<ServerKey, Long>()
    private var environmentEpoch = 0L // guarded by lifetimeLock
    private val startupFailures = ConcurrentHashMap<ServerKey, LspStartupFailure>()
    @Volatile private var diskIndexMutationEpoch = 0L
    private val analysisConsent = LspProjectAnalysisConsent()
    // Tokens order admitted ZLS saves within one open lifetime. They do not retract
    // a save already admitted to the transport's NonCancellable write boundary.
    private val zlsSaveTokens = mutableMapOf<String, Long>() // lifetimeLock
    private data class ZlsSaveDemand(val path: String, val spec: LanguageServerSpec, val key: ServerKey,
        val permit: LspProjectAnalysisConsent.Permit, val generation: Long, val token: Long)
    private val pendingAnalysis = ConcurrentHashMap<LspProjectAnalysisConsent.Ticket, Deferred<Boolean>>()
    // Registered before discovery. Authority retirement captures startup preparation as
    // well as established sessions; only the exact attempt may remove its ownership.
    private class AnalysisStartup(
        val permit: LspProjectAnalysisConsent.Permit, val path: String,
        val spec: LanguageServerSpec, val key: ServerKey,
        val background: Boolean = false, val initialGeneration: Long? = null,
    ) {
        val registeredAtNanos = System.nanoTime()
        lateinit var task: Deferred<ServerState?>
        var rpc: JsonRpcProcess? = null
        var state: ServerState? = null
        var phase = LspProjectAnalysisReadiness.PREPARING
        var detail: String? = null
        var powershellNoticeBits = 0 // lifetimeLock; fixed local categories, never remote message text
    }
    private val clojureWarmups = mutableMapOf<ServerKey, AnalysisStartup>() // lifetimeLock
    private val _statusRevision = MutableStateFlow(0L)
    override val statusRevision: StateFlow<Long> = _statusRevision.asStateFlow()
    private fun statusChanged() { _statusRevision.update { it + 1L } }
    private val analysisStartups = mutableSetOf<AnalysisStartup>() // lifetimeLock
    // Immutable entries are revision/open-generation tokens. A null text is a durable
    // close tombstone, including when no process existed at the time of didClose.
    private class DocumentSnapshot(val text: String?, val generation: Long) { val revision = LSP_DOCUMENT_REVISION.incrementAndGet() }
    private data class DocumentRequest(
        val path: String,
        val snapshot: DocumentSnapshot?,
        val text: String,
        val suppliedText: String?,
        val admission: Any?,
    )
    // A file-backed request is evidence only for its immutable captured bytes. It
    // neither opens an editor ledger entry nor certifies external disk freshness.
    private class PrismaAdmission(val request: DocumentRequest, val key: ServerKey) {
        var owner: ServerState? = null // lifetimeLock
    }
    private data class PrismaWireDocument(val snapshot: DocumentSnapshot?, val admission: Any?, val text: String)
    private val prismaAdmissions = ConcurrentHashMap<String, PrismaAdmission>()
    private data class SqruffWireDocument(val snapshot: DocumentSnapshot?, val view: LspSqruffProfile.WireSnapshot)
    private val sqruffAdmissions = ConcurrentHashMap<String, DocumentRequest>()
    private val sqruffTextEpochs = ConcurrentHashMap<String, Long>()
    private val sqruffUnavailable = ConcurrentHashMap<ServerKey, String>()
    private data class SqruffCleanup(val states: List<ServerState>, val startups: List<Pair<Deferred<ServerState?>, JsonRpcProcess?>>)
    private fun builtinSqruff(path: String): LanguageServerSpec? =
        LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.takeIf(LspServerProfiles::isSqruff)

    // Called under lifetimeLock after publishing the authoritative revision. Queued
    // frames fail on the epoch immediately; actual process cleanup stays outside it.
    private fun invalidateSqruffText(path: String): SqruffCleanup {
        val projects = buildSet {
            builtinSqruff(path)?.let { add(serverKey(it, path).projectPath) }
            servers.values.filter { LspServerProfiles.isSqruff(it.spec) && path in it.documentPaths }.forEach { add(it.key.projectPath) }
            analysisStartups.filter { LspServerProfiles.isSqruff(it.spec) && it.path == path }.forEach { add(it.key.projectPath) }
        }
        projects.forEach { sqruffTextEpochs.merge(it, 1L, Long::plus) }
        sqruffAdmissions.remove(path)
        return SqruffCleanup(servers.values.filter { LspServerProfiles.isSqruff(it.spec) && it.key.projectPath in projects },
            analysisStartups.filter { LspServerProfiles.isSqruff(it.spec) && it.key.projectPath in projects }.map { it.task to it.rpc })
    }
    private fun cleanSqruff(cleanup: SqruffCleanup?) {
        cleanup ?: return
        cleanup.startups.forEach(::stopAnalysisStartup)
        cleanup.states.forEach(::retireServer)
    }
    private fun sqruffWireStillCurrent(state: ServerState, path: String): Boolean {
        val wire = state.sqruffWireDocuments[path] ?: return false
        if (documents[path] !== wire.snapshot) return false
        return wire.snapshot?.text?.let { it == wire.view.originalText } ?: sqruffAdmissions[path]?.let {
            documentRequestStillCurrent(it) && it.text == wire.view.originalText
        } == true
    }
    private fun sqruffOwnerOpen(state: ServerState): Boolean = !LspServerProfiles.isSqruff(state.spec) ||
        (!state.sqruffFormatting && state.key.sqruffTextEpoch == (sqruffTextEpochs[state.key.projectPath] ?: 0L))
    private fun requireSqruffText(text: String): LspSqruffProfile.WireSnapshot =
        requireNotNull(LspSqruffProfile.wireSnapshot(text)) {
            "SQL analysis unavailable: use bounded, well-formed UTF-16 text with LF or CRLF line endings (no lone CR)."
        }

    private data class PreparedDocument(
        val request: DocumentRequest,
        val uri: String,
        val text: String,
        val snapshots: Map<String, DocumentSnapshot>,
        val requestText: Map<String, String>,
        val requestVersions: Map<String, Int?>,
        val svelteObservation: LspSvelteWatchAuthority.Observation? = null,
        val cmakeObservation: CMakeHostAdmissionObservation? = null,
        val nimObservation: NimMetadataObservation? = null,
        val nimLedger: Map<String, DocumentSnapshot>? = null,
        val vueTicket: VueTicket? = null,
        var vueCompletionBatch: Long? = null,
    )
    private data class DiagnosticReport(
        val state: ServerState,
        val version: Int?,
        val text: String?,
        val snapshots: Map<String, DocumentSnapshot>,
        val items: List<LspDiagnostic>,
        val provenance: String,
        val verifiedVersion: Boolean,
        val resultId: String? = null,
        val svelteObservation: LspSvelteWatchAuthority.Observation? = null,
        val vueSourceOutcomes: JsonObject? = null,
    )
    private val diagnosticReports = ConcurrentHashMap<String, DiagnosticReport>()
    private val diagnosticSequence = java.util.concurrent.atomic.AtomicLong()
    // Editor snapshots remain memory-only. Updates never discover/install/start tools.
    private val documents = ConcurrentHashMap<String, DocumentSnapshot>()
    private val unmanagedAdmissions = ConcurrentHashMap<String, Any>()
    private val documentMutex = Mutex()
    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()
    init {
        managerScope.launch { csharp.statusRevision.collect { statusChanged() } }
        managerScope.launch { toml.statusRevision.collect { statusChanged() } }
        managerScope.launch { elp.statusRevision.collect { statusChanged() } }
        managerScope.launch { graphql.statusRevision.collect { statusChanged() } }
    }

    override suspend fun diagnose(relPath: String): String = if (isElp(relPath)) elp.diagnose(relPath) else if (isGraphql(relPath)) graphql.diagnose(relPath) else if (isToml(relPath)) toml.diagnose(relPath) else if (isCSharp(relPath)) csharp.diagnose(relPath) else diagnoseRequest(relPath)

    private fun usesLocalDocumentFeatures(path: String): Boolean = LocalDocumentIntelligence.supports(path) &&
        LanguageServerRegistry.forPath(path, extraLanguageServers()).isEmpty()

    private fun localDocumentGuard(request: DocumentRequest) = LspHeldResultGuard {
        !closed && managerJob.isActive && documentRequestStillCurrent(request) && usesLocalDocumentFeatures(request.path)
    }

    private suspend fun localDocumentResult(request: DocumentRequest): LocalDocumentIntelligence.Result? {
        val result = withContext(Dispatchers.Default) { LocalDocumentIntelligence.analyze(request.path, request.text) }
        return result.takeIf { localDocumentGuard(request).isCurrent() }
    }

    private suspend fun localDocumentDefinitions(request: DocumentRequest, line: Int, column: Int): List<LspLocation> {
        val result = withContext(Dispatchers.Default) {
            LocalDocumentIntelligence.definition(request.path, request.text, line, column)
        }
        val guard = localDocumentGuard(request)
        return if (guard.isCurrent()) result.map { it.copy(activationGuard = guard) } else emptyList()
    }

    private suspend fun diagnoseExistingVue(state: ServerState, path: String): String = diagnoseRequest(path, state)

    private suspend fun diagnoseRequest(relPath: String, expectedVue: ServerState? = null): String {
        if (builtinCMake(canonicalPath(relPath)) != null) return LspCMakeProfile.FEATURE_NOTE
        val request = admitDocument(relPath)
        if (usesLocalDocumentFeatures(request.path)) {
            val result = localDocumentResult(request) ?: return "Document changed; request local checks again."
            result.limit?.let { return it }
            if (LanguageRegistry.forFile(request.path)?.id == "batch")
                return "Batch outline and navigation are available. Syntax diagnostics require a custom language server."
            return result.diagnostics.joinToString("\n") { "${it.line}:${it.column}: ${it.message}" }
                .ifBlank { "Local syntax checks completed. No issue found in the supported checks; runtime and schema validation were not performed." }
        }
        val state = (if (expectedVue == null) serverFor(request.path) else synchronized(lifetimeLock) {
            expectedVue.takeIf { vueOwnerCurrent(it) }
        }) ?: return if (analysisTarget(request.path) != null)
            "Language server unavailable for this project; install its reviewed server and required runtime. No CLI analysis was started."
            else fallback.diagnose(request.path)
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null)
            return vueOwnedOperation(state, request, "textDocument/diagnostic") { diagnoseRequest(relPath, state) }
                ?: "Diagnostics stale: Vue demand changed before publication."
        val prepared = prepareDocument(state, request)
            ?: return "Diagnostics stale: document changed; request diagnostics again."
        val uri = prepared.uri
        val diagnosticUri = localUri(uri, state)
        val version = prepared.requestVersions[request.path]
        if (!state.capabilities.pullDiagnostics) return synchronized(lifetimeLock) {
            renderDiagnostics(request.path, state, prepared, diagnosticReports[diagnosticUri])
        }
        val requestId = diagnosticSequence.incrementAndGet()
        val previous = synchronized(lifetimeLock) {
            state.diagnosticRequests[uri] = requestId
            if (LspServerProfiles.isVue(state.spec)) state.vueDiagnosticPath = request.path
            diagnosticReports[diagnosticUri]
        }
        val basis = previous?.takeIf { !LspServerProfiles.isVue(state.spec) && !LspServerProfiles.isSvelte(state.spec) && it.state === state && it.resultId != null }
        val provenance = "pull request $requestId; version $version"
        // The timeout bounds waiting only. Neither silence, cancellation nor an error
        // constitutes an empty report. Caller cancellation still propagates normally.
        val response = runSuspendCatching {
            withTimeoutOrNull(if (LspServerProfiles.isVue(state.spec)) 11_000 else 2_000) {
                featureRequest(state, prepared, "textDocument/diagnostic", buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    state.capabilities.diagnosticIdentifier?.let { put("identifier", it) }
                    basis?.resultId?.let { put("previousResultId", it) }
                }, timeoutMs = 3_000, cancellationNotificationMethod = "$/cancelRequest")
            }
        }
        currentCoroutineContext().ensureActive()
        return documentMutex.withLock {
            synchronized(lifetimeLock) {
                if (!requestStillCurrent(state, prepared) || state.diagnosticRequests[uri] != requestId) {
                    return@synchronized "Diagnostics stale: ${request.path} (${state.spec.id}; $provenance). Document or request was superseded; request diagnostics again."
                }
                if (diagnosticReports[diagnosticUri] !== previous) {
                    return@synchronized "Diagnostic request superseded by a newer report ($provenance).\n" +
                        renderDiagnostics(request.path, state, prepared, diagnosticReports[diagnosticUri])
                }
                val parsed = if (LspServerProfiles.isVue(state.spec)) LspVueResultAuthority.diagnosticReport(response.getOrNull(), prepared.text)
                    else LspDiagnosticsProtocol.parsePull(response.getOrNull())
                val items = when (parsed) {
                    is LspDiagnosticsProtocol.PullReport.Full -> parsed.items
                    is LspDiagnosticsProtocol.PullReport.Unchanged -> basis?.items
                    null -> null
                }
                if (items == null) {
                    val reason = when {
                        response.isFailure -> "server returned an error"
                        response.getOrNull() == null -> "no response before the wait limit"
                        parsed is LspDiagnosticsProtocol.PullReport.Unchanged -> "unchanged report without a previous result basis"
                        else -> "missing or invalid diagnostic report"
                    }
                    return@synchronized "Diagnostics no result: ${request.path} (${state.spec.id}; $provenance): $reason."
                }
                val report = DiagnosticReport(state, version, prepared.text, prepared.snapshots,
                    items.take(500), provenance, true, parsed?.resultId, prepared.svelteObservation,
                    if (LspServerProfiles.isVue(state.spec)) (response.getOrNull() as? JsonObject)?.get("droideVueSourceOutcomes") as? JsonObject else null)
                updateDiagnostics(diagnosticUri, report)
                renderDiagnostics(request.path, state, prepared, report)
            }
        }
    }

    // Caller owns lifetimeLock: checking provenance and producing the verdict is atomic
    // with editor revision publication and newer push/pull report admission.
    private fun renderDiagnostics(path: String, state: ServerState, prepared: PreparedDocument, report: DiagnosticReport?): String {
        val version = prepared.requestVersions[prepared.request.path]
        if (!requestStillCurrent(state, prepared)) return "Diagnostics stale: $path changed; request diagnostics again."
        if (report == null) return "Diagnostics pending: $path (${state.spec.id}; version $version). No report received; this server does not advertise pull diagnostics."
        val source = "${state.spec.id}; ${report.provenance}"
        val featureNote = LspServerProfiles.optionalToolNote(state.spec)?.let { "\n$it" }.orEmpty()
        if (report.state !== state || report.version != version || report.text != prepared.text || report.snapshots != prepared.snapshots) {
            return "Diagnostics stale: $path ($source). No report for current version $version; request diagnostics again."
        }
        if (LspServerProfiles.isVue(state.spec) && report.vueSourceOutcomes?.let(LspVueResultAuthority::diagnosticComplete) != true) {
            val statuses = report.vueSourceOutcomes?.entries?.joinToString("; ") { (name, raw) ->
                "$name: ${((raw as? JsonObject)?.get("status") as? JsonPrimitive)?.content ?: "unavailable"}"
            } ?: "source status unavailable"
            return "Diagnostics incomplete for this captured open file: $path ($source). $statuses. No clean result is established.\n" +
                (if (report.items.isEmpty()) "No errors returned by the available sources." else LspDiagnosticsProtocol.format(path, report.items)) + featureNote
        }
        if (!report.verifiedVersion) {
            val last = if (report.items.isEmpty()) "Last report contains no diagnostics." else LspDiagnosticsProtocol.format(path, report.items)
            return "Diagnostics unverified: $path ($source). Report does not verify the current document version.\n$last$featureNote"
        }
        return (if (report.items.isEmpty()) {
            if (LspServerProfiles.isVue(state.spec)) "No diagnostics reported by these four sources in this captured open file: $path ($source)"
            else if (featureNote.isEmpty()) "OK: $path ($source)" else "No diagnostics reported: $path ($source)"
        } else LspDiagnosticsProtocol.format(path, report.items) + "\nSource: $source") + featureNote
    }

    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = if (isElp(path)) elp.lsp(op, path, line, col) else if (isGraphql(path)) graphql.lsp(op, path, line, col) else if (isToml(path)) toml.lsp(op, path, line, col) else if (isCSharp(path)) csharp.lsp(op, path, line, col) else lspText(op, path, line, col)

    override suspend fun heldTextResult(op: String, path: String, line: Int, col: Int): LspHeldTextResult {
        if (isElp(path)) return elp.heldTextResult(op, path, line, col)
        if (isGraphql(path)) return graphql.heldTextResult(op, path, line, col)
        if (isToml(path)) return toml.heldTextResult(op, path, line, col)
        if (isCSharp(path)) return csharp.heldTextResult(op, path, line, col)
        var guard: LspHeldResultGuard? = null
        val text = lspText(op, path, line, col) { guard = it }
        return LspHeldTextResult(text, guard)
    }

    private suspend fun lspText(op: String, path: String, line: Int, col: Int,
        captureGuard: ((LspHeldResultGuard) -> Unit)? = null): String {
        if (builtinSqruff(canonicalPath(path)) != null) return LspSqruffProfile.FEATURE_NOTE
        if (builtinCMake(canonicalPath(path)) != null && op !in setOf("completion", "hover")) return LspCMakeProfile.FEATURE_NOTE
        val request = admitDocument(path)
        if (usesLocalDocumentFeatures(request.path)) {
            captureGuard?.invoke(localDocumentGuard(request))
            val result = localDocumentResult(request) ?: return "Document changed; request local features again."
            result.limit?.let { return it }
            return when (op) {
                "documentSymbol" -> result.symbols.joinToString("\n") { "${it.line}:${it.column}: ${it.name}" }.ifBlank { "(no symbols)" }
                "goToDefinition" -> localDocumentDefinitions(request, line, col).joinToString("\n") { "${it.path}:${it.line}:${it.column}" }.ifBlank { "(no local definition)" }
                else -> "${LocalDocumentIntelligence.status(request.path)}. $op requires an appropriate custom language server."
            }
        }
        if (builtinCMake(request.path) != null) {
            val view = LspCMakePositionCodec.capture(request.text) ?: return "CMake text is unavailable for scalar analysis."
            if (view.toWire(line, col) == null || op == "completion" && LspCMakeCompletionContext.capture(view, line, col) == null)
                return "CMake completion/hover position has no safe native captured boundary."
        }
        val state = serverFor(request.path) ?: return if (analysisTarget(request.path) != null)
            "Language server unavailable for this project; install its reviewed server and required runtime. No CLI analysis was started."
            else fallback.lsp(op, request.path, line, col)
        if (!state.capabilities.supportsOperation(op)) return (if (LspServerProfiles.isMake(state.spec)) LspMakeProfile.unavailableOperation(op) else null)
                ?: "LSP ${state.spec.id} does not advertise support for $op"
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null) {
            val ownedMethod = vueMethod(op) ?: return "Vue operation unavailable"
            return vueOwnedOperation(state, request, ownedMethod) { lspText(op, path, line, col, captureGuard) }
                ?: "Vue result unavailable or superseded."
        }
        val prepared = prepareDocument(state, request) ?: return "Document changed; request code intelligence again."
        if (LspServerProfiles.isVue(state.spec) && op == "completion") return LspTextLimit.characters(vueCompletion(state, prepared, line, col, null).joinToString("\n") { it.label })
        if (LspServerProfiles.isCMake(state.spec)) {
            if (op == "completion") return LspTextLimit.characters(cmakeCompletion(state, prepared, line, col).joinToString("\n") { it.label })
            val view = LspCMakePositionCodec.capture(prepared.text) ?: return "CMake text is unavailable for the scalar adapter."
            val params = view.positionParams(prepared.uri, line, col) ?: return "CMake position is outside a valid captured scalar boundary."
            val result = cmakeRequest(state, prepared, "textDocument/hover", params)
            if (result is JsonObject && result.containsKey("range") && view.hoverRange(result["range"]) == null)
                return "CMake hover returned an invalid captured scalar range."
            return LspTextLimit.characters(LspResultRenderer.render("hover", result))
        }
        val uri = prepared.uri
        val pos = buildJsonObject {
            put("line", (line - 1).coerceAtLeast(0))
            put("character", (col - 1).coerceAtLeast(0))
        }
        val textDocument = buildJsonObject { put("uri", uri) }
        val params = buildJsonObject { put("textDocument", textDocument); put("position", pos) }
        val (method, payload) = when (op) {
            "hover" -> "textDocument/hover" to params
            "completion" -> "textDocument/completion" to params
            "goToDefinition" -> "textDocument/definition" to params
            "goToTypeDefinition" -> "textDocument/typeDefinition" to params
            "findReferences" -> "textDocument/references" to buildJsonObject {
                put("textDocument", textDocument); put("position", pos)
                put("context", buildJsonObject { put("includeDeclaration", true) })
            }
            "goToImplementation" -> "textDocument/implementation" to params
            "documentSymbol" -> "textDocument/documentSymbol" to buildJsonObject { put("textDocument", textDocument) }
            "workspaceSymbol" -> "workspace/symbol" to buildJsonObject { put("query", path.take(200)) }
            else -> return "ERROR: unknown code-intelligence operation: $op"
        }
        val result = runSuspendCatching { featureRequest(state, prepared, method, payload, timeoutMs = 8_000) }
            .getOrElse { return "LSP ${state.spec.id} error: ${it.message ?: it::class.java.simpleName}" }
        if (!requestStillCurrent(state, prepared)) return "Document changed; request code intelligence again."
        if (LspServerProfiles.isNim(state.spec)) {
            if (op == "hover" && result is JsonObject && result.containsKey("range") &&
                !LspNimResultAuthority.validRange(prepared.text, result["range"])) return "Nim hover returned an invalid captured UTF-16 range."
            return when (op) {
                "completion" -> LspTextLimit.characters(LspResultRenderer.render(op, LspNimResultAuthority.plainCompletion(result)))
                "goToDefinition", "findReferences" -> LspTextLimit.characters(parseLocations(result, state, prepared.requestText).joinToString("\n") { "${it.path}:${it.line}:${it.column}" })
                "documentSymbol", "workspaceSymbol" -> LspTextLimit.characters(parseSymbols(result, request.path, state, prepared.requestText).joinToString("\n") { "${it.name} ${it.path}:${it.line}:${it.column}" })
                else -> LspTextLimit.characters(LspResultRenderer.render(op, result))
            }
        }
        if (LspServerProfiles.isVue(state.spec)) {
            if (op == "hover" && result is JsonObject && result.containsKey("range") &&
                !LspVueResultAuthority.validRange(prepared.text, result["range"])) return "Vue hover returned an invalid captured UTF-16 range."
            val guard = vueHeldGuard(state, prepared)
            vueHeldResults[request.path] = guard
            captureGuard?.invoke(guard)
        }
        return LspTextLimit.characters(LspResultRenderer.render(op, result))
    }

    override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> =
        completionItems(path, line, col, triggerCharacter = null, currentText = null)

    override suspend fun completionItems(
        path: String,
        line: Int,
        col: Int,
        triggerCharacter: String?,
        currentText: String?,
    ): List<LspCompletionItem> {
        if (isElp(path)) return elp.completionItems(path, line, col, triggerCharacter, currentText)
        if (isGraphql(path)) return graphql.completionItems(path, line, col, triggerCharacter, currentText)
        if (isToml(path)) return toml.completionItems(path, line, col, triggerCharacter, currentText)
        if (isCSharp(path)) return csharp.completionItems(path, line, col, triggerCharacter, currentText)
        if (builtinSqruff(canonicalPath(path)) != null) return emptyList()
        val request = admitDocument(path, currentText)
        if (builtinCMake(request.path) != null && LspCMakeCompletionContext.capture(request.text, line, col) == null) return emptyList()
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.completion) return emptyList()
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null)
            return vueOwnedOperation(state, request, "textDocument/completion") { completionItems(path, line, col, triggerCharacter, currentText) } ?: emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        if (LspServerProfiles.isVue(state.spec)) return vueCompletion(state, prepared, line, col, triggerCharacter)
        val uri = prepared.uri
        if (LspServerProfiles.isCMake(state.spec)) return cmakeCompletion(state, prepared, line, col)
        val powershellContext = if (LspServerProfiles.isPowerShell(state.spec))
            LspPowerShellCompletionContext.capture(prepared.text, line, col) ?: return emptyList() else null
        val prismaContext = if (LspServerProfiles.isPrisma(state.spec)) LspPrismaCompletionContext.capture(prepared.text, line, col) else null
        val advertisedTrigger = (if (LspServerProfiles.isPowerShell(state.spec)) powershellContext?.triggerCharacter else prismaContext?.triggerCharacter ?: triggerCharacter)?.takeIf { it in state.capabilities.completionTriggerCharacters }
        val base = LspProtocolContract.positionParams(uri, line, col)
        val params = buildJsonObject {
            put("textDocument", base["textDocument"] ?: JsonNull)
            put("position", base["position"] ?: JsonNull)
            put("context", buildJsonObject {
                put("triggerKind", if (advertisedTrigger != null) 2 else 1)
                advertisedTrigger?.let { put("triggerCharacter", it) }
            })
        }
        val result = runSuspendCatching {
            featureRequest(state, prepared,
                "textDocument/completion",
                params,
                timeoutMs = 4_000,
                cancellationNotificationMethod = "$/cancelRequest",
            )
        }.getOrNull() ?: return emptyList()
        if (!requestStillCurrent(state, prepared)) return emptyList()
        if (LspServerProfiles.isPowerShell(state.spec) && !LspPowerShellProfile.acceptsCompletionEnvelope(result)) {
            markPowerShellResultUnavailable(state, prepared)
            return emptyList()
        }
        val completionResult = if (LspServerProfiles.isNim(state.spec)) LspNimResultAuthority.plainCompletion(result) else result
        val parsed = LspCompletionProtocol.parse(completionResult, line, col, prepared.text, strictSnippets = LspServerProfiles.isPrisma(state.spec) || LspServerProfiles.isPowerShell(state.spec),
            requirePrimaryEdit = LspServerProfiles.isPowerShell(state.spec), rejectCommand = LspServerProfiles.isPowerShell(state.spec) || LspServerProfiles.isSvelte(state.spec))
        if (!usesBackgroundPreparation(state.spec) && !LspServerProfiles.isPrisma(state.spec)) return parsed
        fun applicationCurrent(): Boolean = synchronized(lifetimeLock) {
            !closed && servers[state.key] === state && clojureReady(state) && sqruffOwnerOpen(state) &&
            (!LspServerProfiles.isNim(state.spec) || state.nimObservation?.let { state.nimProcess?.isAdmissionCurrent(it) } == true) && state.rpc.isRunning &&
                state.key.workspaceMutationEpoch == mutationEpoch(state.spec) &&
                state.analysisPermit?.let(analysisConsent::isCurrent) == true &&
                documentRequestStillCurrent(prepared.request) &&
                prepared.snapshots.all { (path, snapshot) -> documents[path] === snapshot } &&
                (!LspServerProfiles.isPowerShell(state.spec) ||
                    LanguageServerRegistry.forPath(request.path, extraLanguageServers()).firstOrNull() == state.spec) &&
                (!LspServerProfiles.isPrisma(state.spec) ||
                    (prismaDocumentEligible(request.path, state.key) && prismaWireStillCurrent(state, request.path) &&
                        LanguageServerRegistry.forPath(request.path, extraLanguageServers()).firstOrNull() == state.spec))
        }
        if (LspServerProfiles.isNim(state.spec)) {
            val ticket = NimTicket("completion-accept", requireNotNull(prepared.nimLedger), requireNotNull(prepared.nimObservation), prepared.request)
            return parsed.map { item -> item.copy(applicationGuard = LspCompletionApplicationGuard(prepared.text,
                requiredCaret = LspCompletionCaret(line, col)) { synchronized(lifetimeLock) { nimCaptureCurrent(state, ticket, requireReady = true) } }) }
        }
        if (LspServerProfiles.isSvelte(state.spec)) return parsed.map { item ->
            item.copy(applicationGuard = LspCompletionApplicationGuard(prepared.text) {
                synchronized(lifetimeLock) { svelteCaptureCurrent(state, prepared.snapshots, prepared.svelteObservation) &&
                    clojureReady(state) && documentRequestStillCurrent(prepared.request) }
            })
        }
        if (LspServerProfiles.isPowerShell(state.spec)) return parsed.map { item ->
            item.copy(applicationGuard = LspCompletionApplicationGuard(prepared.text,
                requiredCaret = powershellContext?.requestCaret, isCurrent = ::applicationCurrent))
        }
        if (LspServerProfiles.isPrisma(state.spec)) return parsed.mapNotNull { item ->
            val inferred = item.editStartLine == null
            val contextual = if (inferred) prismaContext?.withInferredRange(item) ?: return@mapNotNull null else item
            contextual.copy(applicationGuard = LspCompletionApplicationGuard(prepared.text,
                requiredCaret = if (inferred) prismaContext?.requestCaret else null, isCurrent = ::applicationCurrent))
        }
        val guard = LspCompletionApplicationGuard(prepared.text) {
            // A popup acceptance check performs no filesystem I/O or suspension.
            synchronized(lifetimeLock) {
                !closed && servers[state.key] === state && clojureReady(state) && state.rpc.isRunning &&
                    state.key.workspaceMutationEpoch == mutationEpoch(state.spec) &&
                    state.analysisPermit?.let(analysisConsent::isCurrent) == true &&
                    documentRequestStillCurrent(prepared.request) &&
                    prepared.snapshots.all { (path, snapshot) -> documents[path] === snapshot }
            }
        }
        return parsed.map { it.copy(applicationGuard = guard) }
    }

    override fun completionPolicy(path: String): LspCompletionPolicy? =
        LanguageServerRegistry.forPath(canonicalPath(path), extraLanguageServers()).firstOrNull()?.let {
            when {
                LspServerProfiles.isElp(it) -> LspCompletionPolicy.ELP_NATIVE
                LspServerProfiles.isGraphql(it) -> LspCompletionPolicy.GRAPHQL_NATIVE
                LspServerProfiles.isToml(it) -> LspCompletionPolicy.TOML_NATIVE
                LspServerProfiles.isCSharp(it) -> LspCompletionPolicy.CSHARP_NATIVE
                LspServerProfiles.isVue(it) -> LspCompletionPolicy.VUE_NATIVE
                LspServerProfiles.isPowerShell(it) -> LspCompletionPolicy.POWERSHELL_NATIVE
                LspServerProfiles.isCMake(it) -> LspCompletionPolicy.CMAKE_NATIVE
                else -> null
            }
        }

    override fun signatureHelpTriggers(path: String): LspSignatureTriggers {
        if (isElp(path)) return elp.signatureHelpTriggers(path)
        if (isGraphql(path)) return LspSignatureTriggers()
        if (isToml(path)) return toml.signatureHelpTriggers(path)
        if (isCSharp(path)) return csharp.signatureHelpTriggers(path)
        val state = existingServerFor(canonicalPath(path)) ?: return LspSignatureTriggers()
        return LspSignatureTriggers(
            triggerCharacters = state.capabilities.signatureHelpTriggerCharacters,
            retriggerCharacters = state.capabilities.signatureHelpRetriggerCharacters,
        )
    }

    override suspend fun signatureHelp(
        path: String,
        line: Int,
        col: Int,
        currentText: String?,
        triggerKind: LspSignatureTriggerKind,
        triggerCharacter: String?,
        isRetrigger: Boolean,
        previous: LspSignatureHelp?,
    ): LspSignatureHelp? {
        if (isElp(path)) return elp.signatureHelp(path, line, col, currentText, triggerKind, triggerCharacter, isRetrigger, previous)
        if (isGraphql(path) || isToml(path)) return null
        if (isCSharp(path)) return csharp.signatureHelp(path, line, col, currentText, triggerKind, triggerCharacter, isRetrigger, previous)
        if (builtinSqruff(canonicalPath(path)) != null || builtinCMake(canonicalPath(path)) != null) return null
        val request = admitDocument(path, currentText)
        val state = serverFor(request.path) ?: return null
        if (!state.capabilities.signatureHelp) return null
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null)
            return vueOwnedOperation(state, request, "textDocument/signatureHelp") { signatureHelp(path, line, col, currentText, triggerKind, triggerCharacter, isRetrigger, previous) } ?: null
        val prepared = prepareDocument(state, request) ?: return null
        val vueSignature = if (LspServerProfiles.isVue(state.spec)) currentCoroutineContext()[VueOperation]?.signatureSerial
            ?: synchronized(lifetimeLock) { ++state.vueSignature } else null
        val uri = prepared.uri

        val acceptedTrigger = triggerCharacter?.takeIf { candidate ->
            candidate in state.capabilities.signatureHelpTriggerCharacters ||
                (isRetrigger && candidate in state.capabilities.signatureHelpRetriggerCharacters)
        }
        if (triggerKind == LspSignatureTriggerKind.TRIGGER_CHARACTER && acceptedTrigger == null) return null
        val base = LspProtocolContract.positionParams(uri, line, col)
        val params = buildJsonObject {
            put("textDocument", base["textDocument"] ?: JsonNull)
            put("position", base["position"] ?: JsonNull)
            put("context", LspSignatureHelpProtocol.context(
                kind = triggerKind,
                triggerCharacter = acceptedTrigger,
                isRetrigger = isRetrigger,
                previous = previous,
            ))
        }
        val result = runSuspendCatching {
            featureRequest(state, prepared, "textDocument/signatureHelp", params, timeoutMs = 3_500)
        }.getOrNull() ?: return null
        if (!requestStillCurrent(state, prepared)) return null
        val checked = when {
            LspServerProfiles.isVue(state.spec) -> LspVueResultAuthority.signatureHelp(result)
            LspServerProfiles.isNim(state.spec) -> LspNimResultAuthority.signatureLabels(result)
            else -> result
        }
        val help = LspSignatureHelpProtocol.parse(checked) ?: return null
        return if (vueSignature == null) help else help.copy(applicationGuard = LspCompletionApplicationGuard(prepared.text, LspCompletionCaret(line, col)) {
            synchronized(lifetimeLock) { state.vueSignature == vueSignature && requestStillCurrent(state, prepared) }
        })
    }

    override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> {
        if (isElp(path)) return elp.semanticTokens(path, currentText)
        if (isGraphql(path)) return emptyList()
        if (isToml(path)) return toml.semanticTokens(path, currentText)
        if (isCSharp(path)) return emptyList()
        if (builtinCMake(canonicalPath(path)) != null || builtinNim(canonicalPath(path)) != null) return emptyList()
        val request = admitDocument(path, currentText)
        if (builtinSqruff(request.path) != null) {
            // A finite quiet interval bounds automatic demand without launching from
            // didChange or retrying forever during continuous typing.
            delay(500)
            if (!documentRequestStillCurrent(request)) return emptyList()
        }
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.semanticTokens) return emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        if (LspServerProfiles.isSqruff(state.spec)) {
            try {
                val result = featureRequest(state, prepared, "textDocument/semanticTokens/full", buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", prepared.uri) })
                }, timeoutMs = 8_000)
                if (!requestStillCurrent(state, prepared)) return emptyList()
                val view = state.sqruffWireDocuments[request.path]?.view ?: return emptyList()
                return LspSqruffProfile.parseSemanticTokens(result, state.capabilities.semanticTokenLegend, view)
                    ?: run { retireServer(state); emptyList() }
            } catch (cancel: CancellationException) { retireServer(state); throw cancel }
            catch (_: Exception) { retireServer(state); return emptyList() }
        }
        if (LspServerProfiles.isPowerShell(state.spec)) {
            val result = featureRequest(state, prepared, "textDocument/semanticTokens/full", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", prepared.uri) })
            }, timeoutMs = 4_500)
            if (!requestStillCurrent(state, prepared)) return emptyList()
            return LspPowerShellSemanticTokens.parseFullChecked(result, state.capabilities.semanticTokenLegend, prepared.text)
                ?: run { markPowerShellResultUnavailable(state, prepared); emptyList() }
        }
        val result = LspSemanticTokensProtocol.requestFull(state.rpc, prepared.uri, state.capabilities.semanticTokenLegend)
        return if (requestStillCurrent(state, prepared)) result else emptyList()
    }

    override suspend fun inlayHints(path: String, currentText: String?): List<LspInlayHint> {
        if (isElp(path)) return elp.inlayHints(path, currentText)
        if (isGraphql(path) || isToml(path)) return emptyList()
        if (isCSharp(path)) return emptyList()
        if (builtinSqruff(canonicalPath(path)) != null || builtinCMake(canonicalPath(path)) != null || builtinNim(canonicalPath(path)) != null) return emptyList()
        val request = admitDocument(path, currentText)
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.inlayHint) return emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        val result = LspInlayHintProtocol.requestFull(state.rpc, prepared.uri, prepared.text)
        return if (requestStillCurrent(state, prepared)) result else emptyList()
    }

    override suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> {
        if (usesLocalDocumentFeatures(path)) {
            if (op != "goToDefinition") return emptyList()
            val request = admitDocument(path)
            return localDocumentDefinitions(request, line, col)
        }
        if (isElp(path)) return elp.locationItems(op, path, line, col)
        if (isGraphql(path) || isToml(path)) return emptyList()
        if (isCSharp(path)) return csharp.locationItems(op, path, line, col)
        if (builtinSqruff(canonicalPath(path)) != null || builtinCMake(canonicalPath(path)) != null) return emptyList()
        val request = admitDocument(path)
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.supportsOperation(op)) return emptyList()
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null)
            return vueOwnedOperation(state, request, vueMethod(op) ?: return emptyList()) { locationItems(op, path, line, col) } ?: emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        val base = LspProtocolContract.positionParams(prepared.uri, line, col)
        val (method, payload) = when (op) {
            "goToDefinition" -> "textDocument/definition" to base
            "goToTypeDefinition" -> "textDocument/typeDefinition" to base
            "goToImplementation" -> "textDocument/implementation" to base
            "findReferences" -> "textDocument/references" to buildJsonObject {
                put("textDocument", base["textDocument"] ?: JsonNull)
                put("position", base["position"] ?: JsonNull)
                put("context", buildJsonObject { put("includeDeclaration", true) })
            }
            else -> return emptyList()
        }
        val result = runSuspendCatching { featureRequest(state, prepared, method, payload, timeoutMs = 8_000) }.getOrNull() ?: return emptyList()
        if (!requestStillCurrent(state, prepared)) return emptyList()
        return parseLocations(result, state, if (LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) prepared.requestText else null)
            .map { if (LspServerProfiles.isVue(state.spec)) it.copy(activationGuard = vueHeldGuard(state, prepared)) else it }
    }

    override suspend fun symbolItems(path: String): List<LspSymbol> {
        if (usesLocalDocumentFeatures(path)) {
            val request = admitDocument(path)
            val result = localDocumentResult(request) ?: return emptyList()
            val guard = localDocumentGuard(request)
            return result.symbols.map { it.copy(activationGuard = guard) }
        }
        if (isElp(path)) return elp.symbolItems(path)
        if (isGraphql(path)) return graphql.symbolItems(path)
        if (isToml(path)) return toml.symbolItems(path)
        if (isCSharp(path)) return csharp.symbolItems(path)
        if (builtinSqruff(canonicalPath(path)) != null || builtinCMake(canonicalPath(path)) != null) return emptyList()
        val request = admitDocument(path)
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.documentSymbol) return emptyList()
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null)
            return vueOwnedOperation(state, request, "textDocument/documentSymbol") { symbolItems(path) } ?: emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        val result = runSuspendCatching {
            featureRequest(state, prepared,
                "textDocument/documentSymbol",
                buildJsonObject { put("textDocument", buildJsonObject { put("uri", prepared.uri) }) },
                timeoutMs = 8_000,
            )
        }.getOrNull() ?: return emptyList()
        if (!requestStillCurrent(state, prepared)) return emptyList()
        return parseSymbols(result, request.path, state, if (LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) prepared.requestText else null)
            .map { if (LspServerProfiles.isVue(state.spec)) it.copy(activationGuard = vueHeldGuard(state, prepared)) else it }
    }

    override suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> {
        if (isElp(pathHint)) return elp.workspaceSymbols(pathHint, query)
        if (isGraphql(pathHint)) return emptyList()
        if (isToml(pathHint)) return emptyList()
        if (isCSharp(pathHint)) return csharp.workspaceSymbols(pathHint, query)
        if (builtinSqruff(canonicalPath(pathHint)) != null || builtinCMake(canonicalPath(pathHint)) != null) return emptyList()
        val clean = query.trim().take(256)
        if (clean.isBlank()) return emptyList()
        val path = canonicalPath(pathHint)
        val supportRequest = if (builtinPrisma(path) != null) admitDocument(path) else null
        val state = serverFor(path) ?: return emptyList()
        if (!state.capabilities.workspaceSymbol) return emptyList()
        val snapshots = documentMutex.withLock {
            if (!handlesDocument(state, path) || servers[state.key] !== state) return emptyList()
            replayDocuments(state)
        }
        if (!handlesDocument(state, path) || !snapshotsStillCurrent(state, snapshots) ||
            (supportRequest != null && !documentRequestStillCurrent(supportRequest))) return emptyList()
        val result = runSuspendCatching {
            featureRequest(state, null,
                "workspace/symbol",
                buildJsonObject { put("query", clean) },
                timeoutMs = 8_000,
                additionalCurrent = { handlesDocument(state, path) && snapshotsStillCurrent(state, snapshots) &&
                    (supportRequest == null || documentRequestStillCurrent(supportRequest)) },
            )
        }.getOrNull() ?: return emptyList()
        if (!handlesDocument(state, path) || !snapshotsStillCurrent(state, snapshots) ||
            (supportRequest != null && !documentRequestStillCurrent(supportRequest))) return emptyList()
        return parseSymbols(result, path, state, if (LspServerProfiles.isNim(state.spec))
            snapshots.mapNotNull { (p, snapshot) -> snapshot.text?.let { p to it } }.toMap() else null).take(500)
    }

    override suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> =
        if (isElp(path)) elp.codeActions(path, diagnostic) else if (isGraphql(path) || isToml(path) || isCSharp(path)) emptyList() else         requestCodeActions(path, diagnostic.line, diagnostic.column, diagnostic.endLine, diagnostic.endColumn, diagnostic)

    override suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> =
        if (isElp(path)) elp.codeActionsAt(path, line, col) else if (isGraphql(path) || isToml(path) || isCSharp(path)) emptyList() else         requestCodeActions(path, line, col, line, col, null)

    override suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction {
        if (isElp(path)) return elp.resolveCodeAction(path, action)
        if (isGraphql(path)) return action.copy(edit = null, disabledReason = LspGraphqlProfile.FEATURE_NOTE)
        if (isToml(path)) return action.copy(edit = null, disabledReason = LspTomlProfile.FEATURE_NOTE)
        if (isCSharp(path)) error("C# commands and code actions are unsupported")
        if (builtinNim(canonicalPath(path)) != null) return action.copy(edit = null, disabledReason = LspNimProfile.FEATURE_NOTE)
        if (builtinCMake(canonicalPath(path)) != null) return action.copy(edit = null, disabledReason = LspCMakeProfile.FEATURE_NOTE)
        if (builtinSqruff(canonicalPath(path)) != null) return action.copy(edit = null, disabledReason = "sqruff does not provide code actions")
        if (builtinPrisma(canonicalPath(path)) != null) admitDocument(path)
        if (action.edit != null || action.rawJson.isNullOrBlank()) return action
        val request = admitDocument(path)
        val state = serverFor(request.path) ?: return action
        if (!state.capabilities.codeActionResolve) return action
        val prepared = prepareDocument(state, request) ?: return action
        val requestText = prepared.requestText
        val requestVersions = prepared.requestVersions
        val raw = runCatching { Json.parseToJsonElement(action.rawJson) as? JsonObject }.getOrNull() ?: return action
        val resolved = runSuspendCatching {
            featureRequest(state, prepared, "codeAction/resolve", raw, timeoutMs = 8_000)
        }.getOrNull() as? JsonObject ?: return action
        if (!requestStillCurrent(state, prepared)) return action
        return parseCodeAction(resolved, state, requestText, requestVersions, prepared) ?: action
    }

    override suspend fun prepareCallHierarchy(path: String, line: Int, col: Int): List<LspCallHierarchyItem> {
        if (isElp(path)) return elp.prepareCallHierarchy(path, line, col)
        if (isGraphql(path) || isToml(path)) return emptyList()
        if (isCSharp(path)) return emptyList()
        if (builtinCMake(canonicalPath(path)) != null || builtinNim(canonicalPath(path)) != null) return emptyList()
        if (builtinSqruff(canonicalPath(path)) != null) return emptyList()
        val request = admitDocument(path)
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.callHierarchy) return emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        val result = runSuspendCatching {
            featureRequest(state, prepared, "textDocument/prepareCallHierarchy", LspProtocolContract.positionParams(prepared.uri, line, col), timeoutMs = 8_000)
        }.getOrNull() as? JsonArray ?: return emptyList()
        if (!requestStillCurrent(state, prepared)) return emptyList()
        return result.mapNotNull { parseCallHierarchyItem(it as? JsonObject ?: return@mapNotNull null, state) }.take(100)
    }

    override suspend fun incomingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        if (isElp(item.path)) elp.incomingCalls(item) else callHierarchyRelations("callHierarchy/incomingCalls", item, "from")

    override suspend fun outgoingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        if (isElp(item.path)) elp.outgoingCalls(item) else callHierarchyRelations("callHierarchy/outgoingCalls", item, "to")

    private suspend fun callHierarchyRelations(method: String, item: LspCallHierarchyItem, field: String): List<LspCallHierarchyItem> {
        if (builtinSqruff(canonicalPath(item.path)) != null || builtinCMake(canonicalPath(item.path)) != null || builtinNim(canonicalPath(item.path)) != null) return emptyList()
        val request = admitDocument(item.path)
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.callHierarchy) return emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        val raw = runCatching { Json.parseToJsonElement(item.rawJson) as? JsonObject }.getOrNull() ?: return emptyList()
        val result = runSuspendCatching {
            featureRequest(state, prepared, method, buildJsonObject { put("item", raw) }, timeoutMs = 8_000)
        }.getOrNull() as? JsonArray ?: return emptyList()
        if (!requestStillCurrent(state, prepared)) return emptyList()
        return result.mapNotNull { relation ->
            val obj = relation as? JsonObject ?: return@mapNotNull null
            parseCallHierarchyItem(obj[field] as? JsonObject ?: return@mapNotNull null, state)
        }.distinctBy { Triple(it.path, it.line, it.name) }.take(500)
    }

    private suspend fun requestCodeActions(
        path: String,
        line: Int,
        col: Int,
        endLine: Int,
        endCol: Int,
        diagnostic: LspDiagnostic?,
    ): List<LspCodeAction> {
        if (builtinSqruff(canonicalPath(path)) != null || builtinCMake(canonicalPath(path)) != null || builtinNim(canonicalPath(path)) != null) return emptyList()
        val request = admitDocument(path)
        val state = serverFor(request.path) ?: return emptyList()
        if (!state.capabilities.codeAction) return emptyList()
        val prepared = prepareDocument(state, request) ?: return emptyList()
        val requestText = prepared.requestText
        val requestVersions = prepared.requestVersions
        val powerShellReport = if (LspServerProfiles.isPowerShell(state.spec)) synchronized(lifetimeLock) {
            diagnosticReports[localUri(prepared.uri, state)]?.takeIf { report ->
                report.state === state && report.text == prepared.text && report.snapshots == prepared.snapshots && requestStillCurrent(state, prepared)
            }
        } else null
        val contextDiagnostics = if (LspServerProfiles.isPowerShell(state.spec)) {
            if (diagnostic != null) {
                if (powerShellReport?.items?.any { it === diagnostic } != true) return emptyList()
                listOf(diagnostic)
            } else powerShellReport?.items.orEmpty().filter { d ->
                val start = d.line to d.column; val end = d.endLine to d.endColumn
                (line > start.first || line == start.first && col >= start.second) &&
                    (line < end.first || line == end.first && col <= end.second)
            }
        } else listOfNotNull(diagnostic)
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", prepared.uri) })
            put("range", buildJsonObject {
                put("start", buildJsonObject {
                    put("line", (line - 1).coerceAtLeast(0))
                    put("character", (col - 1).coerceAtLeast(0))
                })
                put("end", buildJsonObject {
                    put("line", (endLine - 1).coerceAtLeast(0))
                    put("character", (endCol - 1).coerceAtLeast(0))
                })
            })
            put("context", buildJsonObject {
                put("diagnostics", buildJsonArray {
                    contextDiagnostics.forEach { diagnostic -> add(buildJsonObject {
                        put("range", buildJsonObject {
                            put("start", buildJsonObject {
                                put("line", (diagnostic.line - 1).coerceAtLeast(0))
                                put("character", (diagnostic.column - 1).coerceAtLeast(0))
                            })
                            put("end", buildJsonObject {
                                put("line", (diagnostic.endLine - 1).coerceAtLeast(0))
                                put("character", (diagnostic.endColumn - 1).coerceAtLeast(0))
                            })
                        })
                        diagnostic.severity?.let { put("severity", it) }
                        diagnostic.source?.let { put("source", it) }
                        if (LspServerProfiles.isPowerShell(state.spec)) diagnostic.code?.let { put("code", it) }
                        put("message", diagnostic.message.take(2_000))
                    }) }
                })
                put("triggerKind", 1)
            })
        }
        val result = runSuspendCatching {
            featureRequest(state, prepared, "textDocument/codeAction", params, timeoutMs = 8_000)
        }.getOrNull() as? JsonArray ?: return emptyList()
        if (!requestStillCurrent(state, prepared)) return emptyList()
        if (LspServerProfiles.isPowerShell(state.spec) && synchronized(lifetimeLock) {
                diagnosticReports[localUri(prepared.uri, state)] !== powerShellReport
            }) return emptyList()
        return parseCodeActions(result, state, requestText, requestVersions, prepared).take(100)
    }

    override suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? {
        if (isElp(path)) return elp.renameSymbol(path, line, col, newName)
        if (isGraphql(path)) return null
        if (isToml(path)) return toml.renameSymbol(path, line, col, newName)
        if (isCSharp(path)) return csharp.renameSymbol(path, line, col, newName)
        if (builtinCMake(canonicalPath(path)) != null) return null
        if (builtinSqruff(canonicalPath(path)) != null) return null
        val name = newName.trim()
        require(name.isNotBlank() && name.length <= 256 && '\u0000' !in name) { "Invalid symbol name" }
        val request = admitDocument(path)
        val state = serverFor(request.path) ?: return null
        if (!state.capabilities.rename) return null
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null)
            return vueOwnedOperation(state, request, "textDocument/rename") { renameSymbol(path, line, col, newName) } ?: null
        val prepared = prepareDocument(state, request) ?: return null
        val requestText = prepared.requestText
        val requestVersions = prepared.requestVersions
        val base = LspProtocolContract.positionParams(prepared.uri, line, col)
        val params = buildJsonObject {
            put("textDocument", base["textDocument"] ?: JsonNull)
            put("position", base["position"] ?: JsonNull)
            put("newName", name)
        }
        val result = runSuspendCatching { featureRequest(state, prepared, "textDocument/rename", params, timeoutMs = 10_000) }.getOrNull() ?: return null
        if (!requestStillCurrent(state, prepared)) return null
        return parseWorkspaceEdit(result, state, requestText, requestVersions, prepared)
    }

    override suspend fun formatDocument(path: String): LspWorkspaceEdit? =
        formatDocumentWithOptions(path, null)

    override suspend fun formatDocument(path: String, options: LspFormattingOptions): LspWorkspaceEdit? =
        formatDocumentWithOptions(path, options)

    private suspend fun formatDocumentWithOptions(path: String, options: LspFormattingOptions?): LspWorkspaceEdit? {
        if (isElp(path)) return null
        if (isGraphql(path)) return null
        if (isToml(path)) return if (options == null) toml.formatDocument(path) else toml.formatDocument(path, options)
        if (isCSharp(path)) return if (options == null) csharp.formatDocument(path) else csharp.formatDocument(path, options)
        if (builtinCMake(canonicalPath(path)) != null || builtinNim(canonicalPath(path)) != null) return null
        val request = admitDocument(path)
        val state = serverFor(request.path) ?: return null
        if (!state.capabilities.formatting) return null
        if (LspServerProfiles.isVue(state.spec) && currentCoroutineContext()[VueOperation] == null)
            return vueOwnedOperation(state, request, "textDocument/formatting") { formatDocumentWithOptions(path, options) } ?: null
        if (LspServerProfiles.isSqruff(state.spec)) return formatSqruff(state, request, options)
        val prepared = prepareDocument(state, request) ?: return null
        val requestText = prepared.requestText
        val requestVersions = prepared.requestVersions
        val result = runSuspendCatching {
            featureRequest(state, prepared,
                "textDocument/formatting",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", prepared.uri) })
                    put("options", buildJsonObject {
                        put("tabSize", options?.tabSize ?: if (LspServerProfiles.isLemMinx(state.spec)) 2 else 4)
                        put("insertSpaces", options?.insertSpaces ?: true)
                        put("trimTrailingWhitespace", true)
                        put("insertFinalNewline", false)
                        put("trimFinalNewlines", false)
                    })
                },
                timeoutMs = 10_000,
            )
        }.getOrNull() as? JsonArray ?: return null
        if (!requestStillCurrent(state, prepared)) return null
        if (result.isEmpty()) return LspWorkspaceEdit(emptyList())
        val edits = if (LspServerProfiles.isClojure(state.spec)) LspClojureProfile.normalizeFormatting(result, prepared.text) else result
        return parseWorkspaceEdit(buildJsonObject {
            put("changes", buildJsonObject { put(prepared.uri, edits) })
        }, state, requestText, requestVersions, prepared)
    }


    private suspend fun formatSqruff(state: ServerState, request: DocumentRequest, options: LspFormattingOptions?): LspWorkspaceEdit? =
        state.sqruffFormatMutex.withLock {
            val prepared = prepareDocument(state, request) ?: return@withLock null
            synchronized(lifetimeLock) {
                if (!requestStillCurrent(state, prepared)) return@withLock null
                state.sqruffFormatting = true
                state.sqruffFormatPath = request.path
                val obsolete = diagnosticReports.filterValues { it.state === state }.keys
                obsolete.forEach(diagnosticReports::remove)
                _diagnostics.update { it - obsolete }
            }
            try {
                val result = featureRequest(state, prepared, "textDocument/formatting", buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", prepared.uri) })
                    put("options", (options ?: LspFormattingOptions.DEFAULT).toJson())
                }, timeoutMs = 10_000)
                val identity = requireNotNull(state.sqruffIdentity)
                val fresh = state.processHost.reviewedSqruffLaunchIdentity(identity.discoveredExecutable, root.path)
                check(fresh == identity) { "SQL managed payload or selected policy changed; formatting proposal discarded" }
                synchronized(lifetimeLock) {
                    if (!requestStillCurrent(state, prepared, allowSqruffFormatting = true)) return@synchronized null
                    val edits = LspSqruffProfile.normalizeFormatting(result, prepared.text) ?: return@synchronized null
                    parseWorkspaceEdit(buildJsonObject {
                        put("changes", buildJsonObject { put(prepared.uri, edits) })
                    }, state, prepared.requestText, prepared.requestVersions)
                }
            } finally {
                // Native formatting mutates its own buffer before acceptance. Never
                // let a proposal, rejection, timeout or cancellation reuse that owner.
                retireServer(state)
            }
        }

    private fun markPowerShellResultUnavailable(state: ServerState, prepared: PreparedDocument) {
        synchronized(lifetimeLock) {
            if (servers[state.key] === state && clojureReady(state) && requestStillCurrent(state, prepared) &&
                !state.powershellRequestUnavailable) {
                state.powershellRequestUnavailable = true
                statusChanged()
            }
        }
    }

    /** PSES owns native work independently from transient UI waiters; all older routes delegate unchanged. */
    private suspend fun featureRequest(state: ServerState, prepared: PreparedDocument?, method: String,
                                       params: JsonElement? = null, timeoutMs: Long = 15_000,
                                       cancellationNotificationMethod: String? = null,
                                       additionalCurrent: () -> Boolean = { true }): JsonElement {
        if (LspServerProfiles.isAsm(state.spec) &&
            (prepared == null || !LspAsmProfile.acceptsPositionFeatures(prepared.text))) return JsonNull
        if (LspServerProfiles.isVue(state.spec)) return vueFeatureRequest(state, requireNotNull(prepared), method, params ?: JsonNull, additionalCurrent)
        if (LspServerProfiles.isNim(state.spec)) {
            val position = (params as? JsonObject)?.get("position")
            if (prepared != null && position != null) {
                val pos = position as? JsonObject ?: return JsonNull
                val line = (pos["line"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return JsonNull
                val character = (pos["character"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return JsonNull
                if (!LspNimResultAuthority.validPosition(prepared.text, line, character)) return JsonNull
            }
            val observation = prepared?.nimObservation ?: state.nimObservation ?: return JsonNull
            val ticket = NimTicket(method, prepared?.nimLedger ?: synchronized(lifetimeLock) { nimLedger(state) }, observation, prepared?.request)
            val payload = params ?: JsonNull
            val requests = state.nimRequests ?: return JsonNull
            return requests.request(method, isCurrent = { synchronized(lifetimeLock) {
                nimCaptureCurrent(state, ticket, requireReady = true) && additionalCurrent()
            } }) {
                synchronized(lifetimeLock) {
                    check(nimCaptureCurrent(state, ticket, requireReady = true) && additionalCurrent()) { "Nim request owner changed" }
                    state.nimTickets[payload] = ticket
                }
                try { state.rpc.request(method, payload, LspNimRequests.FEATURE_MS, "$/cancelRequest") }
                finally { synchronized(lifetimeLock) { state.nimTickets.remove(payload) } }
            } ?: JsonNull
        }
        if (LspServerProfiles.isCMake(state.spec)) return cmakeRequest(state, requireNotNull(prepared), method, requireNotNull(params))
        if (LspServerProfiles.isSvelte(state.spec)) {
            val capture = requireNotNull(prepared) { "Svelte detached feature requests are unavailable" }
            val payload = requireNotNull(params)
            val observation = requireNotNull(capture.svelteObservation)
            return svelteRequest(state, SvelteTicket(method, capture.snapshots, observation, capture.request), payload,
                timeoutMs, cancellationNotificationMethod)
        }
        if (!LspServerProfiles.isPowerShell(state.spec)) return state.rpc.request(method, params, timeoutMs, cancellationNotificationMethod)
        val requests = state.powershellRequests ?: return JsonNull
        val nativeTimeout = if (method == "textDocument/completion") 4_000L else timeoutMs
        val result = requests.request(method, nativeTimeout, completion = method == "textDocument/completion", isCurrent = {
            synchronized(lifetimeLock) {
                !closed && servers[state.key] === state && clojureReady(state) && state.rpc.isRunning &&
                    state.analysisPermit?.let(analysisConsent::isCurrent) == true &&
                    state.key.workspaceMutationEpoch == mutationEpoch(state.spec) &&
                    (prepared == null || requestStillCurrent(state, prepared)) && additionalCurrent()
            }
        }) { state.rpc.request(method, params, nativeTimeout, cancellationNotificationMethod) }
        synchronized(lifetimeLock) {
            if (servers[state.key] === state && clojureReady(state) && state.powershellRequestUnavailable != (result == null)) {
                state.powershellRequestUnavailable = result == null; statusChanged()
            }
        }
        return result ?: JsonNull
    }

    override suspend fun didOpen(path: String, text: String) {
        synchronized(lifetimeLock) { if (closed) return; observeElpCompanion(path, text, true); observeGraphqlCompanion(path, text, true) }
        if (isElp(path) || isGraphql(path)) return
        if (isToml(path)) { toml.didOpen(path, text); return }
        if (isCSharp(path)) {
            val key = canonicalPath(path)
            synchronized(lifetimeLock) { if (closed) return; observeTomlCompanion(key, text) }
            csharp.didOpen(path, text)
        } else updateDocument(path, text)
    }

    override suspend fun didChange(path: String, text: String) {
        synchronized(lifetimeLock) { if (closed) return; observeElpCompanion(path, text); observeGraphqlCompanion(path, text) }
        if (isElp(path) || isGraphql(path)) return
        if (isToml(path)) { toml.didChange(path, text); return }
        if (isCSharp(path)) {
            val key = canonicalPath(path)
            synchronized(lifetimeLock) { if (closed) return; observeTomlCompanion(key, text) }
            csharp.didChange(path, text)
        } else updateDocument(path, text)
    }

    private suspend fun updateDocument(path: String, text: String) {
        if (closed) return
        val key = canonicalPath(path)
        var sqruffCleanup: SqruffCleanup? = null
        val obsoletePrismaStarts = synchronized(lifetimeLock) {
            if (closed) return
            if (documents[key]?.text != text) {
                documents[key] = DocumentSnapshot(text, documents[key]?.generation ?: 0L)
                clearSvelteDiagnosticsForRevision(key)
                sqruffCleanup = invalidateSqruffText(key)
            }
            observeTomlCompanion(key, documents.getValue(key).text ?: return)
            unmanagedAdmissions.remove(key)
            prismaAdmissions.remove(key)
            if (builtinPrisma(key) == null) emptyList() else {
                statusChanged()
                val uri = files.resolveChecked(key).toURI().toString()
                diagnosticReports.remove(uri); _diagnostics.update { it - uri }
                if (prismaTextStatus(text).permitsAnalysis) emptyList()
                else analysisStartups.filter { it.path == key && LspServerProfiles.isPrisma(it.spec) }.map { it.task to it.rpc }
            }
        }
        cleanSqruff(sqruffCleanup)
        obsoletePrismaStarts.forEach(::stopAnalysisStartup)
        retireObservedClojure(key)
        retireChangedProjectRoots(key)
        documentMutex.withLock {
            // An earlier update waiting for the wire may only deliver the latest entry.
            val latest = documents[key]?.text ?: return@withLock
            if (closed) return@withLock
            matchingServers(key).filterNot { LspServerProfiles.isVue(it.spec) || LspServerProfiles.isSvelte(it.spec) || LspServerProfiles.isAsm(it.spec) }.forEach { synchronizeServerDocument(it, key, latest) }
        }
    }

    override suspend fun didSave(path: String, text: String?) {
        synchronized(lifetimeLock) { if (closed) return; elp.observeEditorSave(path, text); saveGraphqlCompanion(path) }
        if (isElp(path) || isGraphql(path)) return
        if (isToml(path)) { toml.didSave(path, text); return }
        if (isCSharp(path)) {
            val key = canonicalPath(path)
            synchronized(lifetimeLock) { if (closed) return; text?.let { observeTomlCompanion(key, it) }; saveTomlCompanion(key) }
            csharp.didSave(path, text); return
        }
        if (closed) return
        if (text != null) {
            didSaveSnapshot(path, text, text)
            return
        }
        val key = canonicalPath(path)
        val beforeRead = synchronized(lifetimeLock) {
            if (closed) return
            val snapshot = documents[key]
            // Both save entrypoints require an existing editor lifetime for ZLS.
            // A null-text save after Close must not resurrect a disk-backed tab.
            if (LanguageServerRegistry.forPath(key, extraLanguageServers()).firstOrNull()?.let { LspServerProfiles.isZls(it) || LspServerProfiles.isPrisma(it) || LspServerProfiles.isSqruff(it) || LspServerProfiles.isCMake(it) || LspServerProfiles.isNim(it) } == true &&
                snapshot?.text == null) return
            snapshot
        }
        val savedText = beforeRead?.text ?: files.readTextForEdit(key, maxBytes = 2_000_000)
        val savedGeneration = synchronized(lifetimeLock) {
            // A suspended disk read cannot publish over an edit/close that arrived later.
            if (closed || documents[key] !== beforeRead) return
            if (beforeRead?.text == null) {
                documents[key] = DocumentSnapshot(savedText, beforeRead?.generation ?: 0L)
                clearSvelteDiagnosticsForRevision(key)
            }
            observeTomlCompanion(key, documents.getValue(key).text ?: return)
            saveTomlCompanion(key)
            documents.getValue(key)
        }
        saveRecordedSnapshot(key, savedText, savedGeneration)
    }

    override suspend fun didSaveSnapshot(path: String, savedText: String, currentText: String) {
        synchronized(lifetimeLock) { if (closed) return; elp.observeEditorSave(path, currentText); saveGraphqlCompanion(path) }
        if (isElp(path) || isGraphql(path)) return
        if (isToml(path)) { toml.didSaveSnapshot(path, savedText, currentText); return }
        if (isCSharp(path)) {
            val key = canonicalPath(path)
            synchronized(lifetimeLock) { if (closed) return; observeTomlCompanion(key, currentText); saveTomlCompanion(key) }
            csharp.didSaveSnapshot(path, savedText, currentText); return
        }
        if (closed) return
        val key = canonicalPath(path)
        if (builtinVue(key) != null || builtinCMake(key) != null || builtinNim(key) != null) {
            val current = synchronized(lifetimeLock) {
                val latest = documents[key]?.takeIf { !closed && it.text != null } ?: return
                observeTomlCompanion(key, requireNotNull(latest.text))
                saveTomlCompanion(key)
                latest
            }
            // Invalidate for a real save without publishing stale caller bytes over the live ledger.
            saveRecordedSnapshot(key, savedText, current)
            return
        }
        var sqruffCleanup: SqruffCleanup? = null
        val savedGeneration = synchronized(lifetimeLock) {
            if (closed) return
            if (LanguageServerRegistry.forPath(key, extraLanguageServers()).firstOrNull()?.let { LspServerProfiles.isZls(it) || LspServerProfiles.isPrisma(it) || LspServerProfiles.isSqruff(it) || LspServerProfiles.isCMake(it) || LspServerProfiles.isNim(it) } == true &&
                documents[key]?.text == null) return
            if (documents[key]?.text != currentText) {
                documents[key] = DocumentSnapshot(currentText, documents[key]?.generation ?: 0L)
                clearSvelteDiagnosticsForRevision(key)
                sqruffCleanup = invalidateSqruffText(key)
            }
            unmanagedAdmissions.remove(key); prismaAdmissions.remove(key)
            if (builtinPrisma(key) != null) {
                statusChanged()
                val uri = files.resolveChecked(key).toURI().toString()
                diagnosticReports.remove(uri); _diagnostics.update { it - uri }
            }
            observeTomlCompanion(key, documents.getValue(key).text ?: return)
            saveTomlCompanion(key)
            documents.getValue(key)
        }
        cleanSqruff(sqruffCleanup)
        if (builtinPrisma(key) != null && !prismaTextStatus(currentText).permitsAnalysis) {
            val obsolete = synchronized(lifetimeLock) {
                analysisStartups.filter { it.path == key && LspServerProfiles.isPrisma(it.spec) }.map { it.task to it.rpc }
            }
            obsolete.forEach(::stopAnalysisStartup)
        }
        saveRecordedSnapshot(key, savedText, savedGeneration)
    }

    private suspend fun saveRecordedSnapshot(key: String, savedText: String, savedGeneration: DocumentSnapshot) {
        if (LspVueProfile.relevantPath(key)) {
            val vueOwners = synchronized(lifetimeLock) {
                vueLedgerMutation++
                clearDiagnosticReports { LspServerProfiles.isVue(it.state.spec) }
                vueHeldResults.clear()
                val states = servers.values.filter { LspServerProfiles.isVue(it.spec) }
                val startups = analysisStartups.filter { LspServerProfiles.isVue(it.spec) }.map { it.task to it.rpc }
                clojureWarmups.entries.removeAll { LspServerProfiles.isVue(it.value.spec) }
                statusChanged()
                states to startups
            }
            vueOwners.second.forEach(::stopAnalysisStartup)
            vueOwners.first.forEach(::retireServer)
            if (builtinVue(key) != null) return // persisted mutation never starts analysis
        }
        if (builtinNim(key) != null) {
            val cleanup = synchronized(lifetimeLock) {
                if (closed || documents[key] !== savedGeneration) return
                // Direct save API refresh is scoped to this Nim root. The ordinary
                // persisted-workspace callback separately invalidates all disk indexes.
                val spec = builtinNim(key) ?: return
                val project = serverKey(spec, key).projectPath
                nimSaveRefreshEpochs.merge(project, 1L, Long::plus)
                statusChanged()
                servers.values.filter { LspServerProfiles.isNim(it.spec) && it.key.projectPath == project } to
                    analysisStartups.filter { LspServerProfiles.isNim(it.spec) && it.key.projectPath == project }.map { it.task to it.rpc }
            }
            cleanup.second.forEach(::stopAnalysisStartup)
            cleanup.first.forEach(::retireServer)
            return
        }
        if (builtinCMake(key) != null) {
            val cleanup = synchronized(lifetimeLock) {
                if (closed || documents[key] !== savedGeneration) return
                val project = cmakeSelection(key)?.sourceLocalCanonical ?: return
                cmakeRefreshEpochs.merge(project, 1L, Long::plus)
                statusChanged()
                servers.values.filter { LspServerProfiles.isCMake(it.spec) && it.key.projectPath == project } to
                    analysisStartups.filter { LspServerProfiles.isCMake(it.spec) && it.key.projectPath == project }.map { it.task to it.rpc }
            }
            cleanup.second.forEach(::stopAnalysisStartup)
            cleanup.first.forEach(::retireServer)
            return // Next supported demand starts once; no native didSave or automatic restart.
        }
        val demand = synchronized(lifetimeLock) {
            val spec = LanguageServerRegistry.forPath(key, extraLanguageServers()).firstOrNull()
            if (spec == null || !LspServerProfiles.isZls(spec)) null else {
                val serverKey = serverKey(spec, key)
                val identity = requireNotNull(analysisIdentity(spec, key, serverKey))
                val permit = analysisConsent.observe(identity)
                if (closed || permit == null || !analysisConsent.isCurrent(permit) ||
                    !currentRoute(spec, serverKey, key) || documents[key]?.text == null ||
                    documents[key]?.generation != savedGeneration.generation) null
                else {
                    val token = (zlsSaveTokens[key] ?: 0L) + 1L
                    zlsSaveTokens[key] = token
                    ZlsSaveDemand(key, spec, serverKey, permit, savedGeneration.generation, token)
                }
            }
        }
        retireChangedProjectRoots(key)
        if (demand != null) runSuspendCatching { serverForZlsSave(demand) }
        val states = matchingServers(key)
        for (state in states) {
            if (LspServerProfiles.isZls(state.spec) && (demand == null || !currentZlsSave(demand))) continue
            (state.processHost as? DeviceWorkstationProcessHost)?.refreshWorkspace()
            documentMutex.withLock {
                if (closed || !handlesDocument(state, key) ||
                    (LspServerProfiles.isZls(state.spec) && (demand == null || !currentZlsSave(demand)))) return@withLock
                // A fresh ZLS owner must see every live dirty sibling before its
                // save-triggered build work, not just the file that was persisted.
                if (LspServerProfiles.isZls(state.spec)) {
                    replayDocuments(state)
                    if (demand == null || !currentZlsSave(demand)) return@withLock
                }
                // A close/reopen during refresh must not save into a new open lifetime.
                val latest = documents[key]?.text ?: return@withLock
                if (documents[key]?.generation != savedGeneration.generation) return@withLock
                synchronizeServerDocument(state, key, latest)
                if (LspServerProfiles.isZls(state.spec) && (demand == null || !currentZlsSave(demand))) return@withLock
                // ZLS advertises save=true without text. A later already-admitted
                // notification may redundantly check, but cannot restore older text.
                state.documentSync.saveSnapshot(uriFor(state, key), languageId(key), savedText, latest)
            }
        }
    }

    private fun currentZlsSave(demand: ZlsSaveDemand): Boolean = synchronized(lifetimeLock) {
        zlsSaveTokens[demand.path] == demand.token && documents[demand.path]?.text != null &&
            documents[demand.path]?.generation == demand.generation && analysisConsent.isCurrent(demand.permit) &&
            currentRoute(demand.spec, demand.key, demand.path)
    }

    private suspend fun serverForZlsSave(demand: ZlsSaveDemand): ServerState? {
        if (!currentZlsSave(demand)) return null
        return startMutex.withLock {
            if (!currentZlsSave(demand)) return@withLock null
            servers[demand.key]?.takeIf { it.rpc.isRunning && it.analysisPermit === demand.permit }?.let { return@withLock it }
            if ((unavailableUntil[demand.key] ?: 0L) > System.currentTimeMillis()) return@withLock null
            servers[demand.key]?.let(::retireServer)
            val state = startServer(demand.spec, demand.key, demand.path, demand.permit) {
                check(currentZlsSave(demand)) { "ZLS save was superseded before startup publication" }
            }
            if (currentZlsSave(demand)) state else null
        }
    }

    override suspend fun didClose(path: String) {
        synchronized(lifetimeLock) { if (closed) return; elp.closeEditorDocument(path); closeGraphqlCompanion(path) }
        if (isElp(path) || isGraphql(path)) return
        if (isToml(path)) { toml.didClose(path); return }
        if (isCSharp(path)) {
            val key = canonicalPath(path)
            synchronized(lifetimeLock) { if (closed) return; closeTomlCompanion(key) }
            csharp.didClose(path); return
        }
        val key = canonicalPath(path)
        // A native server may retain its last dirty buffer after didClose. Reuse only
        // when the closing snapshot matches disk; discarded/unknown text retires it.
        val closingSnapshot = synchronized(lifetimeLock) {
            documents[key]?.takeIf { snapshot -> snapshot.text != null && clojureWarmups.values.any { owner ->
                owner.phase == LspProjectAnalysisReadiness.READY &&
                    LspServerProfiles.reusesReadySessionAcrossDocumentClose(owner.spec) &&
                    owner.state?.let { state -> state.rpc.isRunning && key in state.documentPaths &&
                        state.documentPaths.any { sibling -> sibling != key && documents[sibling]?.text != null }
                    } == true
            } }
        }
        val persistedClosingText = if (closingSnapshot == null) null else withContext(Dispatchers.IO) {
            runCatching { files.readTextForEdit(key, maxBytes = 2_000_000) }.getOrNull()
        }
        var cleanSharedClose = false
        var sqruffCleanup: SqruffCleanup? = null
        val clojureCleanup = synchronized(lifetimeLock) {
            if (closed) return
            closeTomlCompanion(key)
            zlsSaveTokens.remove(key)
            unmanagedAdmissions.remove(key); prismaAdmissions.remove(key)
            if (builtinPrisma(key) != null) statusChanged()
            cleanSharedClose = closingSnapshot != null && documents[key] === closingSnapshot &&
                persistedClosingText != null && closingSnapshot.text == persistedClosingText
            documents[key] = DocumentSnapshot(null, (documents[key]?.generation ?: 0L) + 1L)
            clearSvelteDiagnosticsForRevision(key)
            svelteAdmissions.remove(key)
            sqruffCleanup = invalidateSqruffText(key)
            clojureWarmups.values.filter {
                val retainsOpenSiblings = cleanSharedClose && it.phase == LspProjectAnalysisReadiness.READY &&
                    LspServerProfiles.reusesReadySessionAcrossDocumentClose(it.spec) &&
                    it.state?.let { owner -> owner.rpc.isRunning && owner.documentPaths.any { sibling ->
                        sibling != key && documents[sibling]?.text != null && handlesDocument(owner, sibling)
                    } } == true
                if (retainsOpenSiblings) false
                else if (LspServerProfiles.isVue(it.spec)) it.phase != LspProjectAnalysisReadiness.READY && it.path == key
                else it.path == key || it.state?.documentPaths?.contains(key) == true ||
                    it.state?.let { state -> LspServerProfiles.isSvelte(state.spec) && state.svelteCompanions.owns(key) } == true
            }
                .also { owners -> owners.forEach { clojureWarmups.remove(it.key, it) }; if (owners.isNotEmpty()) statusChanged() }
                .map { it.task to it.rpc } + analysisStartups.filter { it.path == key && (LspServerProfiles.isPrisma(it.spec) || LspServerProfiles.isCMake(it.spec)) }.map { it.task to it.rpc }
        }
        cleanSqruff(sqruffCleanup)
        clojureCleanup.forEach(::stopAnalysisStartup)
        documentMutex.withLock {
            if (closed) return@withLock
            // Close the recorded owner even if a marker changed the current route.
            // If a new open won, only its current owner may receive that buffer.
            servers.values.toList().filter { key in it.documentPaths || handlesDocument(it, key) }.forEach { state ->
                val currentOwner = handlesDocument(state, key)
                val latest = documents[key]?.text
                if (latest != null && currentOwner && state.rpc.isRunning) synchronizeServerDocument(state, key, latest)
                else closeServerDocument(state, key, allowCleanSharedClose = cleanSharedClose)
                if (!currentOwner && usesScopedRouting(state)) retireEmptyProjectServer(state)
            }
        }
    }

    private fun builtinCMake(path: String): LanguageServerSpec? =
        LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.takeIf(LspServerProfiles::isCMake)

    private fun cmakeSelection(path: String): CMakeAnalysisSelections.Selection? =
        cmakeSelections?.selectionFor(File(root, path))

    override fun cmakeSelectionStatus(path: String): CMakeAnalysisSelections.Status? {
        val document = canonicalPath(path)
        if (builtinCMake(document) == null) return null
        return cmakeSelections?.status(File(root, document)) ?: CMakeAnalysisSelections.Status(null,
            File(root, document).parent.orEmpty(), "Select and save a source directory before enabling CMake analysis. No build is discovered automatically.", false, true)
    }

    override suspend fun saveCmakeSelection(path: String, sourcePath: String, configuredBuildPath: String?): String {
        val document = canonicalPath(path)
        check(builtinCMake(document) != null && !closed) { "CMake route or workspace changed" }
        val store = cmakeSelectionMutex.withLock {
            cmakeSelections ?: run {
                val host = requireNotNull(remoteProcessHost) { "CMake requires the reviewed local Ubuntu host" }
                val context = withContext(Dispatchers.IO) { host.cmakeSelectionContext(root.path) }
                    ?: error("Read-only CMake selection is unavailable for this host")
                val next = CMakeAnalysisSelections(root, cmakeWorkspaceIdentity, context.environmentIdentity, context.mappingRevision, context.mapper, context.inspector)
                synchronized(lifetimeLock) { check(!closed); cmakeSelections = next }
                next
            }
        }
        val previous = synchronized(lifetimeLock) { cmakeSelection(document) to analysisTarget(document)?.second }
        val candidate = withContext(Dispatchers.IO) { store.validate(sourcePath, configuredBuildPath) }
        try {
            currentCoroutineContext().ensureActive()
            require(File(root, document).path.startsWith(candidate.sourceLocalCanonical.trimEnd('/') + "/")) { "The selected source must contain this CMake document" }
            val cleanup = synchronized(lifetimeLock) {
                check(!closed && builtinCMake(document) != null && cmakeSelections === store) { "CMake selection owner changed" }
                check(cmakeSelection(document) === previous.first) { "The CMake selection changed during validation" }
                val committed = store.commit(candidate, replacing = previous.first?.takeIf { it.sourceLocalCanonical != candidate.sourceLocalCanonical })
                previous.second?.let(analysisConsent::revoke)
                val affected = servers.values.filter { LspServerProfiles.isCMake(it.spec) &&
                    (it.key.projectPath == committed.sourceLocalCanonical || it.documentPaths.any { p -> serverKey(it.spec, p) != it.key }) }
                val starting = analysisStartups.filter { LspServerProfiles.isCMake(it.spec) &&
                    (it.key.projectPath == committed.sourceLocalCanonical || serverKey(it.spec, it.path) != it.key) }
                val target = analysisTarget(document)
                // Observing the committed revision revokes only that exact source/configuration group.
                target?.second?.takeIf { it.projectPath == committed.sourceLocalCanonical }?.let(analysisConsent::observe)
                affected.filter { it.key.projectPath == committed.sourceLocalCanonical }.mapNotNull { it.analysisPermit?.identity }.forEach(analysisConsent::revoke)
                starting.filter { it.key.projectPath == committed.sourceLocalCanonical }.map { it.permit.identity }.forEach(analysisConsent::revoke)
                unavailableUntil.keys.removeAll { it.serverId == LspCMakeProfile.ID && it.projectPath == committed.sourceLocalCanonical }
                statusChanged()
                val changedGroups = listOfNotNull(previous.second?.group, target?.second?.group).toSet()
                Triple(affected, starting.map { it.task to it.rpc }, pendingAnalysis.filterKeys { it.identity.group in changedGroups }.values.toList())
            }
            cleanup.third.forEach { it.cancel() }
            cleanup.second.forEach(::stopAnalysisStartup)
            cleanup.first.forEach(::retireServer)
            return "CMake selection saved. Enable analysis separately after reviewing the selected source/build effects."
        } finally { store.discard(candidate) }
    }

    /** Metadata and bounded cache checks only, before consent; no executable discovery or startup. */
    private suspend fun observeCMakeSelection(path: String): CMakeAnalysisSelections.Selection {
        val store = requireNotNull(cmakeSelections) { "Save an explicit CMake source/build selection first" }
        val selection = requireNotNull(cmakeSelection(path)) { "This CMake document has no saved source selection" }
        check(withContext(Dispatchers.IO) { store.revalidate(selection) }) { store.status(File(root, path)).message }
        val proof = withContext(Dispatchers.IO) { remoteProcessHost?.reviewedCMakeLaunchIdentity(LspCMakeProfile.COMMAND, root.path) }
            ?: error("Install/select the exact reviewed managed CMake server and runtime before enabling analysis")
        synchronized(lifetimeLock) {
            check(!closed && builtinCMake(path) != null && store.isCurrent(selection)) { "CMake selection changed during validation" }
            cmakeIdentity = proof
        }
        return selection
    }

    private fun analysisIdentity(spec: LanguageServerSpec, path: String, key: ServerKey = serverKey(spec, path)): LspProjectAnalysisIdentity? =
        key.analysisConfiguration?.let { LspProjectAnalysisIdentity(root.path, spec.id, key.projectPath, it) }

    private fun analysisTarget(path: String): Pair<LanguageServerSpec, LspProjectAnalysisIdentity>? {
        val spec = LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull() ?: return null
        return analysisIdentity(spec, path)?.let { spec to it }
    }

    override fun projectAnalysisStatus(path: String): LspProjectAnalysisStatus? {
        if (isElp(path) || LspElpProfile.supportsPath(path) && elp.projectAnalysisStatus(path).canRevoke) return elp.projectAnalysisStatus(path)
        if (isGraphql(path) || LspGraphqlProfile.supportsPath(path) && graphql.projectAnalysisStatus(path).canRevoke) return graphql.projectAnalysisStatus(path)
        if (isToml(path) || LspTomlProfile.supportsPath(path) && toml.projectAnalysisStatus(path)?.canRevoke == true) return toml.projectAnalysisStatus(path)
        if (isCSharp(path) || LspCSharpProfile.supportsPath(path) && csharp.projectAnalysisStatus(path).canRevoke) return csharp.projectAnalysisStatus(path)
        val document = canonicalPath(path)
        retireObservedClojure(document)
        val target = analysisTarget(document) ?: return null
        return synchronized(lifetimeLock) {
            val available = projectAvailable(target.first, File(target.second.projectPath)) &&
                (!LspServerProfiles.isVue(target.first) || LspVueProfile.documentAvailable(root, document))
            // Temporary disk unavailability is an admission barrier, not a newly
            // observed configuration that should erase an unchanged prior grant.
            val permit = if (LspServerProfiles.isVue(target.first) && !available)
                vueGrantedPermits[root.path]?.takeIf(analysisConsent::isCurrent)
            else if (LspServerProfiles.isNim(target.first) && !available)
                nimRecordedPermit(document, target.second.projectPath)
            else if (LspServerProfiles.isSvelte(target.first) && !available)
                svelteGrantedPermits[target.second.projectPath]?.takeIf { it.identity.group == target.second.group }
            else analysisConsent.observe(target.second)
            val granted = !closed && permit != null && analysisConsent.isCurrent(permit)
            val recordedOwner = servers.values.any { it.analysisPermit != null && document in it.documentPaths } ||
                analysisStartups.any { it.path == document }
            val warmup = if (usesBackgroundPreparation(target.first)) clojureWarmups[serverKey(target.first, document)] else null
            LspProjectAnalysisStatus(target.first.id, granted && available, target.second.projectPath,
                if (!available) unavailableProjectMessage(target.first, File(target.second.projectPath)) + "; restore it before analysis."
                else LspServerProfiles.optionalToolNote(target.first), canRevoke = granted || recordedOwner,
                readiness = warmup?.phase ?: LspProjectAnalysisReadiness.ON_DEMAND, readinessDetail = warmup?.detail)
        }
    }

    override suspend fun enableProjectAnalysis(path: String): String {
        if (isElp(path)) return elp.enableProjectAnalysis(path)
        if (isGraphql(path)) return graphql.enableProjectAnalysis(path)
        if (isToml(path)) return toml.enableProjectAnalysis(path)
        if (isCSharp(path)) return csharp.enableProjectAnalysis(path)
        check(!closed && managerJob.isActive) { "Language-server workspace is closed" }
        val document = canonicalPath(path)
        retireObservedClojure(document)
        if (builtinVue(document) != null) {
            check(LspVueProfile.documentAvailable(root, document)) { "Vue exact root or document parent is unavailable" }
            val identity = withContext(Dispatchers.IO) { remoteProcessHost?.reviewedVueLaunchIdentity(LspVueProfile.COMMAND, root.path) }
                ?: error("Install/select the exact reviewed Vue bundle and private Node helper before enabling analysis")
            synchronized(lifetimeLock) { check(!closed && builtinVue(document) != null); vueIdentity = identity }
        }
        val cmakeApprovedSelection = if (builtinCMake(document) != null) observeCMakeSelection(document) else null
        LanguageServerRegistry.forPath(document, extraLanguageServers()).firstOrNull()?.takeIf(LspServerProfiles::isSvelte)?.let { spec ->
            val project = LspSvelteProfile.projectRoot(root, files.resolveChecked(document))
            val baseline = LspSvelteFilePoller(project).scan()
            synchronized(lifetimeLock) { check(!closed); svelteConfigDigests[project.path] = baseline.criticalDigest }
        }
        val supportAdmission = if (builtinVue(document) != null || builtinPrisma(document) != null || builtinSqruff(document) != null ||
            LanguageServerRegistry.forPath(document, extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isSvelte) == true) admitDocument(document) else null
        val (spec, identity) = analysisTarget(document) ?: return "This server does not require project-analysis enablement."
        LspServerProfiles.requireSupportedDocumentPath(spec, files.resolveChecked(document).path)
        if (!projectAvailable(spec, File(identity.projectPath)))
            return unavailableProjectMessage(spec, File(identity.projectPath)) + "; project analysis remains disabled."
        val ticket = synchronized(lifetimeLock) { analysisConsent.begin(identity) }
            ?: return "This workspace's analysis authority has been retired."
        val request = LspProjectAnalysisRequest(spec.id, root.path, identity.projectPath,
            spec.commands.map { it.toList() }, LspServerProfiles.approvalExplanation(spec, File(identity.projectPath), document) +
                (cmakeApprovedSelection?.let { selected ->
                    "\nSelected source: ${selected.sourceLocalCanonical} (guest ${selected.sourceGuestAbsolute})." +
                        "\nSelected CMake: ${cmakeIdentity?.cmakeExecutableGuestAbsolute}." +
                        if (selected.buildMode == CMakeAnalysisSelections.BuildMode.BUILTIN_ONLY_PRIVATE)
                            "\n" + LspCMakeProfile.PRIVATE_BUILD_DISCLOSURE
                        else "\nConfigured build: ${selected.configuredBuildLocalCanonical} (guest ${selected.configuredBuildGuestAbsolute}). Initialization may reconfigure this exact build."
                } ?: ""),
            installationDisclosure = LspServerProfiles.installationDisclosure(spec, document)
                ?: "Enabling analysis does not install packages or authorize database migrations or deployment.")
        val approval = managerScope.async(start = CoroutineStart.LAZY) {
            approveProjectAnalysis(request) {
                synchronized(lifetimeLock) {
                    !closed && (supportAdmission == null || documentRequestStillCurrent(supportAdmission)) &&
                        (!LspServerProfiles.isPrisma(spec) || prismaDocumentEligible(document, serverKey(spec, document))) &&
                        analysisConsent.isCurrent(ticket) && analysisTarget(document)?.second == identity &&
                        projectAvailable(spec, File(identity.projectPath))
                }
            }
        }
        try {
            synchronized(lifetimeLock) {
                if (closed || !analysisConsent.isCurrent(ticket))
                    return "Project analysis remains disabled; this workspace's authority changed."
                pendingAnalysis[ticket] = approval
            }
            approval.start()
            val approved = approval.await()
            if (approved && cmakeApprovedSelection != null) {
                check(observeCMakeSelection(document) === cmakeApprovedSelection) { "CMake selection changed while awaiting approval" }
            }
            currentCoroutineContext().ensureActive()
            val enabled = synchronized(lifetimeLock) {
                if (!approved || closed || (supportAdmission != null && !documentRequestStillCurrent(supportAdmission)) ||
                    (LspServerProfiles.isPrisma(spec) && !prismaDocumentEligible(document, serverKey(spec, document))) ||
                    analysisTarget(document)?.second != identity ||
                    !projectAvailable(spec, File(identity.projectPath)) || analysisConsent.approve(ticket) == null) false
                else {
                    if (LspServerProfiles.isVue(spec))
                        vueGrantedPermits[identity.projectPath] = requireNotNull(analysisConsent.observe(identity))
                    if (LspServerProfiles.isNim(spec))
                        nimGrantedPermits[identity.projectPath] = requireNotNull(analysisConsent.observe(identity))
                    if (LspServerProfiles.isSvelte(spec))
                        svelteGrantedPermits[identity.projectPath] = requireNotNull(analysisConsent.observe(identity))
                    unavailableUntil.keys.removeAll { it.serverId == identity.serverId && it.projectPath == identity.projectPath }
                    true
                }
            }
            statusChanged()
            if (!enabled) return "Project analysis remains disabled; approval was declined, canceled or its configuration changed."
            if (usesBackgroundPreparation(spec)) {
                queueClojure(spec, serverKey(spec, document), document, requireNotNull(requireAnalysisPermit(spec, document, serverKey(spec, document))))
                return "Preparing ${preparationLabel(spec)} project analysis; Revoke stops it. " + LspServerProfiles.enabledNote(spec)
            }
            return "Project analysis enabled for ${identity.projectPath} (${spec.id}) in this workspace session. " +
                LspServerProfiles.enabledNote(spec)
        } finally {
            synchronized(lifetimeLock) { pendingAnalysis.remove(ticket, approval) }
            approval.cancel()
        }
    }

    override fun revokeProjectAnalysis(path: String): String {
        if (isElp(path) || LspElpProfile.supportsPath(path) && elp.projectAnalysisStatus(path).canRevoke) return elp.revokeProjectAnalysis(path)
        if (isGraphql(path) || LspGraphqlProfile.supportsPath(path) && graphql.projectAnalysisStatus(path).canRevoke) return graphql.revokeProjectAnalysis(path)
        if (isToml(path) || LspTomlProfile.supportsPath(path) && toml.projectAnalysisStatus(path)?.canRevoke == true) return toml.revokeProjectAnalysis(path)
        if (isCSharp(path) || LspCSharpProfile.supportsPath(path) && csharp.projectAnalysisStatus(path).canRevoke) return csharp.revokeProjectAnalysis(path)
        val document = canonicalPath(path)
        val cleanup = synchronized(lifetimeLock) {
            val current = analysisTarget(document)?.second
            // Root/config replacement must not hide a recorded owner's stop action.
            val owners = servers.values.filter { it.analysisPermit != null && document in it.documentPaths }
            val starting = analysisStartups.filter { it.path == document }
            val nimRecorded = if (builtinNim(document) != null) nimGrantedPermits.values.filter {
                File(root, document).path.startsWith(it.identity.projectPath.trimEnd('/') + "/") && analysisConsent.isCurrent(it)
            }.map { it.identity } else emptyList()
            val identities = (listOfNotNull(current) + (if (builtinVue(document) != null) vueGrantedPermits.values.filter(analysisConsent::isCurrent).map { it.identity } else emptyList()) + nimRecorded + owners.mapNotNull { it.analysisPermit?.identity } +
                starting.map { it.permit.identity }).distinct()
            if (identities.isEmpty()) return "No project-analysis grant exists for this file."
            val groups = identities.map { it.group }.toSet()
            identities.forEach(analysisConsent::revoke)
            clojureWarmups.entries.removeAll { it.value.permit.identity.group in groups }
            statusChanged()
            Triple(pendingAnalysis.filterKeys { it.identity.group in groups }.values.toList(),
                servers.values.filter { it.analysisPermit?.identity?.group in groups },
                analysisStartups.filter { it.permit.identity.group in groups }.map { it.task to it.rpc })
        }
        cleanup.first.forEach { it.cancel() }
        cleanup.third.forEach(::stopAnalysisStartup)
        cleanup.second.forEach(::retireServer)
        return "Project analysis revoked for this file's project; affected language servers were stopped."
    }

    override fun retireProjectAnalysisAuthority(): () -> Unit {
        val retireElp = elp.retireProjectAnalysisAuthority()
        val retireGraphql = graphql.retireProjectAnalysisAuthority()
        val retireToml = toml.retireProjectAnalysisAuthority()
        val retireCSharp = csharp.retireProjectAnalysisAuthority(); retireCSharp()
        val cleanup = synchronized(lifetimeLock) {
            analysisConsent.close()
            _diagnostics.update { it - graphqlDiagnosticUris - elpDiagnosticUris }; graphqlDiagnosticUris = emptySet(); elpDiagnosticUris = emptySet()
            svelteGrantedPermits.clear()
            clojureWarmups.clear()
            statusChanged()
            Triple(pendingAnalysis.values.toList(), servers.values.filter { it.analysisPermit != null },
                analysisStartups.map { it.task to it.rpc })
        }
        return {
            retireElp()
            retireGraphql()
            retireToml()
            cleanup.first.forEach { it.cancel() }
            cleanup.third.forEach(::stopAnalysisStartup)
            cleanup.second.forEach(::retireServer)
        }
    }

    private fun stopAnalysisStartup(startup: Pair<Deferred<ServerState?>, JsonRpcProcess?>) {
        try { startup.first.cancel() } finally {
            startup.second?.let { rpc ->
                // Never remove a replacement generation by the reusable server key.
                servers.values.firstOrNull { it.rpc === rpc }?.let(::retireServer)
                rpc.close()
            }
        }
    }

    private fun requireAnalysisPermit(spec: LanguageServerSpec, path: String, key: ServerKey): LspProjectAnalysisConsent.Permit? {
        val identity = analysisIdentity(spec, path, key) ?: return null
        if (LspServerProfiles.isSvelte(spec)) check(projectAvailable(spec, File(key.projectPath))) {
            unavailableProjectMessage(spec, File(key.projectPath))
        }
        return synchronized(lifetimeLock) { analysisConsent.observe(identity) } ?: throw LspProjectAnalysisRequiredException(
            "Project analysis is disabled for ${spec.id}. Use Code actions → Enable project analysis and review the project-code execution risk.")
    }

    private fun requireCurrentAnalysis(permit: LspProjectAnalysisConsent.Permit?) {
        if (permit != null && !analysisConsent.isCurrent(permit)) throw LspProjectAnalysisRequiredException(
            "Project-analysis authority changed or was revoked; enable the current configuration explicitly before retrying.")
    }

    private fun projectAvailable(spec: LanguageServerSpec, project: File): Boolean =
        if (LspServerProfiles.isCMake(spec)) cmakeSelections?.selectionFor(File(project, "CMakeLists.txt"))?.let {
            it.sourceLocalCanonical == project.path && cmakeSelections?.isCurrent(it) == true && cmakeIdentity != null
        } == true else LspServerProfiles.projectAvailable(spec, project) &&
            (!LspServerProfiles.isZls(spec) || LspZlsProfile.hint(root, project).usable)

    private fun unavailableProjectMessage(spec: LanguageServerSpec, project: File): String =
        if (LspServerProfiles.isCMake(spec)) cmakeSelections?.status(File(project, "CMakeLists.txt"))?.message
            ?: "Save an explicit source/build selection, then install/select the reviewed CMake runtime"
        else if (LspServerProfiles.isZls(spec)) LspZlsProfile.hint(root, project).problem
            ?: LspServerProfiles.unavailableProjectMessage(spec)
        else LspServerProfiles.unavailableProjectMessage(spec)

    private fun currentRoute(spec: LanguageServerSpec, key: ServerKey, path: String): Boolean =
        !closed && managerJob.isActive && projectAvailable(spec, File(key.projectPath)) &&
            (!LspServerProfiles.isVue(spec) || LspVueProfile.documentAvailable(root, path)) &&
            (!LspServerProfiles.isPrisma(spec) || prismaDocumentEligible(path, key)) &&
            LanguageServerRegistry.forPath(path, extraLanguageServers()).any { it.id == spec.id && serverKey(it, path) == key }

    private fun requireCurrentRoute(spec: LanguageServerSpec, key: ServerKey, path: String,
                                    permit: LspProjectAnalysisConsent.Permit?) {
        requireCurrentAnalysis(permit)
        check(currentRoute(spec, key, path)) { "Language-server project or configuration changed; retry with the current configuration" }
    }

    override fun workspaceFilesChanged() {
        elp.workspaceFilesChanged()
        graphql.workspaceFilesChanged()
        toml.workspaceFilesChanged()
        csharp.workspaceFilesChanged()
        val obsolete = synchronized(lifetimeLock) {
            if (closed) return
            diskIndexMutationEpoch++
            vueLedgerMutation++
            vueHeldResults.clear()
            clearDiagnosticReports { LspServerProfiles.isVue(it.state.spec) }
            prismaAdmissions.clear()
            sqruffAdmissions.clear()
            // Retain readiness for servers that synchronize edits/saves in place (such as R).
            clojureWarmups.entries.removeAll { LspServerProfiles.refreshAfterPersistedMutation(it.value.spec) }
            statusChanged()
            servers.values.filter { LspServerProfiles.refreshAfterPersistedMutation(it.spec) } to
                analysisStartups.filter { LspServerProfiles.refreshAfterPersistedMutation(it.spec) }
                    .map { it.task to it.rpc }
        }
        // Freshness is independent of project consent. Capture exact owners under
        // the publication lock, then cancel/close outside it; never launch on mutation.
        obsolete.second.forEach(::stopAnalysisStartup)
        obsolete.first.forEach(::retireServer)
    }

    private fun mutationEpoch(spec: LanguageServerSpec): Long =
        if (LspServerProfiles.refreshAfterPersistedMutation(spec)) diskIndexMutationEpoch else 0L

    override fun environmentInvalidated() {
        synchronized(lifetimeLock) {
            if (closed || !managerJob.isActive) return
            environmentEpoch++
            // Invalidate confirmed absence only. Failed initialized owners still require scoped Retry.
            unavailableUntil.keys.removeAll { !startupFailures.containsKey(it) }
            statusChanged()
        }
    }

    override fun startupFailure(path: String): LspStartupFailure? {
        val canonical = canonicalPath(path)
        return LanguageServerRegistry.forPath(canonical, extraLanguageServers())
            .filterNot(::usesBackgroundPreparation)
            .firstNotNullOfOrNull { startupFailures[serverKey(it, canonical)] }
    }

    override suspend fun retryStartup(path: String): String {
        val canonical = canonicalPath(path)
        startMutex.withLock {
            check(!closed && managerJob.isActive) { "Language-server manager has been closed" }
            for (spec in LanguageServerRegistry.forPath(canonical, extraLanguageServers()).filterNot(::usesBackgroundPreparation)) {
                val key = serverKey(spec, canonical)
                if (startupFailures.remove(key) != null) unavailableUntil.remove(key)
            }
            statusChanged()
        }
        // Normal startup revalidates consent, route and process admission; never replaces a healthy owner.
        return if (serverFor(canonical) != null) serverStatus(canonical) else
            startupFailure(canonical)?.message ?: serverStatus(canonical)
    }

    override fun serverStatus(path: String): String {
        if (isElp(path)) return elp.serverStatus(path)
        if (isGraphql(path)) return graphql.serverStatus(path)
        if (isToml(path)) return toml.serverStatus(path)
        if (isCSharp(path)) return csharp.serverStatus(path)
        if (closed) return "LSP closed"
        val canonical = canonicalPath(path)
        if (builtinSqruff(canonical) != null) {
            documents[canonical]?.text?.let { if (LspSqruffProfile.wireSnapshot(it) == null)
                return "SQL analysis unavailable: malformed/oversized UTF-16 or lone CR line endings" }
            builtinSqruff(canonical)?.let { sqruffUnavailable[serverKey(it, canonical)]?.let { reason -> return reason } }
        }
        documentSupportStatus(path)?.takeUnless { it.permitsAnalysis }?.let { return it.message }
        projectAnalysisStatus(path)?.let {
            val spec = LanguageServerRegistry.forPath(canonicalPath(path), extraLanguageServers()).firstOrNull { spec -> spec.id == it.serverId }
            if (spec != null && !projectAvailable(spec, File(it.projectPath)))
                return "LSP unavailable; " + unavailableProjectMessage(spec, File(it.projectPath))
            if (!it.enabled) return "Project analysis disabled · enable explicitly in Code actions"
            val powershellNotice = if (spec != null && LspServerProfiles.isPowerShell(spec)) synchronized(lifetimeLock) {
                val bits = clojureWarmups[serverKey(spec, canonical)]?.powershellNoticeBits ?: 0
                buildString {
                    if (bits and 1 != 0) append(" · native prompt declined; affected operation may be unavailable")
                    if (bits and 2 != 0) append(" · native warning/error reported; analysis success remains unverified")
                }
            } else ""
            if (spec != null && usesBackgroundPreparation(spec)) return when (it.readiness) {
                LspProjectAnalysisReadiness.PREPARING -> "${preparationLabel(spec)} analysis preparing · Revoke stops indexing" + powershellNotice
                LspProjectAnalysisReadiness.FAILED -> "${preparationLabel(spec)} analysis failed · Retry or Revoke in Code actions"
                LspProjectAnalysisReadiness.READY -> "LSP: ${spec.id} · ready" + LspServerProfiles.runningToolNote(spec) + powershellNotice +
                    (if (LspServerProfiles.isVue(spec)) " · " + (servers[serverKey(spec, canonical)]?.vueOutcomeNote ?: "No captured native result yet.") else "") +
                    (if (servers[serverKey(spec, canonical)]?.nimPartialResults == true) " · Some cross-file results were omitted because their current text and positions could not be verified." else "") +
                    (if (servers[serverKey(spec, canonicalPath(path))]?.powershellRequestUnavailable == true)
                        " · last native request unavailable; analysis readiness remains unverified" else "")
                LspProjectAnalysisReadiness.ON_DEMAND -> "${preparationLabel(spec)} analysis enabled · ready to prepare on demand"
            }
        }
        val specs = LanguageServerRegistry.forPath(canonicalPath(path), extraLanguageServers())
        if (specs.isEmpty()) return LanguageServerRegistry.unavailableReason(path) ?: "fallback"
        val running = specs.firstOrNull { servers[serverKey(it, canonicalPath(path))]?.rpc?.isRunning == true }
        val markdownNote = specs.firstOrNull(LspServerProfiles::isMarksman)?.let {
            if (serverKey(it, canonicalPath(path)).publishesWorkspaceRoot)
                " · Markdown project index; reopen workspace after external file/config changes"
            else " · single-file Markdown; cross-file indexing needs a .marksman.toml file or supported VCS directory"
        }.orEmpty()
        val failedStartup = startupFailure(path)
        return when {
            running != null -> "LSP: ${running.id}" + LspServerProfiles.runningToolNote(running) +
                if (servers[serverKey(running, canonicalPath(path))]?.prismaDegraded == true)
                    " · server reported an error; configuration and results may be incomplete" else ""
            failedStartup != null -> failedStartup.message
            specs.all { (unavailableUntil[serverKey(it, canonicalPath(path))] ?: 0L) > System.currentTimeMillis() } ->
                if (specs.any { LspServerProfiles.isTexlab(it) || LspServerProfiles.isTerraform(it) || LspServerProfiles.isZls(it) || LspServerProfiles.isLemMinx(it) || LspServerProfiles.isMake(it) || LspServerProfiles.isPrisma(it) || LspServerProfiles.isSqruff(it) || LspServerProfiles.isCMake(it) }) "LSP unavailable; no CLI fallback was started"
                else if (analysisTarget(canonicalPath(path)) != null) "LSP unavailable; project analysis was not run" else "LSP unavailable; CLI fallback"
            else -> "LSP available on demand"
        } + markdownNote
    }

    private fun usesBackgroundPreparation(spec: LanguageServerSpec): Boolean = LspBackgroundPreparationPolicy.forSpec(spec) != null
    private fun preparationLabel(spec: LanguageServerSpec): String = requireNotNull(LspBackgroundPreparationPolicy.forSpec(spec)).label

    private fun clojureReady(state: ServerState): Boolean = !usesBackgroundPreparation(state.spec) || synchronized(lifetimeLock) {
        clojureWarmups[state.key]?.let { it.state === state && it.phase == LspProjectAnalysisReadiness.READY } == true
    }

    private fun clojureNotReady(owner: AnalysisStartup): LspProjectAnalysisNotReadyException? = synchronized(lifetimeLock) {
        // Replay can publish READY between queueing and this observation. Capture
        // the phase and construct its typed result under the same publication lock.
        if (owner.phase == LspProjectAnalysisReadiness.READY) return@synchronized null
        LspProjectAnalysisNotReadyException(owner.phase, owner.detail ?: if (owner.phase == LspProjectAnalysisReadiness.FAILED)
            "${preparationLabel(owner.spec)} project analysis failed; check project tools/configuration, then choose Retry or Revoke."
        else "${preparationLabel(owner.spec)} project analysis is preparing; Revoke stops it.")
    }

    private fun requireClojureStartup(owner: AnalysisStartup) {
        synchronized(lifetimeLock) {
            check(clojureWarmups[owner.key] === owner && owner.phase == LspProjectAnalysisReadiness.PREPARING) {
                "${preparationLabel(owner.spec)} background attempt was superseded"
            }
            if (owner.initialGeneration != null) check(documents[owner.path]?.let {
                it.text != null && it.generation == owner.initialGeneration
            } == true) { "${preparationLabel(owner.spec)} initiating document lifetime changed" }
            requireCurrentRoute(owner.spec, owner.key, owner.path, owner.permit)
        }
    }

    /** Registers a manager-owned attempt without holding the global startup mutex. */
    private fun queueClojure(spec: LanguageServerSpec, key: ServerKey, path: String,
                             permit: LspProjectAnalysisConsent.Permit): AnalysisStartup {
        val owner = synchronized(lifetimeLock) {
            requireCurrentRoute(spec, key, path, permit)
            clojureWarmups[key]?.takeIf { it.permit === permit }?.let {
                if (it.phase == LspProjectAnalysisReadiness.READY && it.state?.rpc?.isRunning != true) {
                    it.phase = LspProjectAnalysisReadiness.FAILED
                    it.detail = "${preparationLabel(spec)} language-server transport ended; check project tools, then Retry."
                    statusChanged()
                }
                return it
            }
            val created = AnalysisStartup(permit, path, spec, key, background = true,
                initialGeneration = documents[path]?.takeIf { it.text != null }?.generation)
            created.task = managerScope.async(start = CoroutineStart.LAZY) {
                var completed = false
                try {
                    // The deadline starts at registration, not when a delayed lazy
                    // coroutine finally gets scheduled. No retry resets this budget.
                    val elapsedMs = (System.nanoTime() - created.registeredAtNanos).coerceAtLeast(0L) / 1_000_000L
                    val remainingMs = requireNotNull(LspBackgroundPreparationPolicy.forSpec(spec)).attemptMs - elapsedMs
                    if (remainingMs <= 0L) {
                        failClojure(created, "${preparationLabel(spec)} preparation timed out before launch; Retry when the workspace is responsive.")
                        return@async null
                    }
                    withTimeout(remainingMs) {
                        val state = startServerAttempt(spec, key, path, permit, created) { requireClojureStartup(created) }
                            ?: error("${preparationLabel(spec)} server was unavailable or initialization failed")
                        withTimeout(requireNotNull(LspBackgroundPreparationPolicy.forSpec(spec)).replayMs) {
                            documentMutex.withLock {
                                while (true) {
                                    requireClojureStartup(created)
                                    val snapshots = replayDocuments(state)
                                    if (LspServerProfiles.isVue(spec)) vueInitialFeature(state, created.path)
                                    if (LspServerProfiles.isSvelte(spec)) svelteInitialPull(state, created.path, snapshots)
                                    val ready = synchronized(lifetimeLock) {
                                        requireClojureStartup(created)
                                        check(servers[key] === state && state.rpc.isRunning) { "${preparationLabel(spec)} process ended during replay" }
                                        if (relevantDocuments(state) != snapshots) false else {
                                            created.phase = LspProjectAnalysisReadiness.READY
                                            statusChanged()
                                            true
                                        }
                                    }
                                    if (ready) break
                                    currentCoroutineContext().ensureActive()
                                }
                            }
                        }
                        completed = true
                        state
                    }
                } catch (timeout: TimeoutCancellationException) {
                    failClojure(created, "${preparationLabel(spec)} preparation timed out; check project tools/configuration, then Retry.")
                    null
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    failClojure(created, if (LspServerProfiles.isNim(spec) && failure is NimAdmissionException) failure.safeMessage
                        else "${preparationLabel(spec)} preparation failed; check the server and project tools/configuration, then Retry.")
                    null
                } finally {
                    val cleanup = synchronized(lifetimeLock) {
                        analysisStartups.remove(created)
                        if (!completed && clojureWarmups[key] === created && created.phase != LspProjectAnalysisReadiness.FAILED) {
                            clojureWarmups.remove(key, created); statusChanged()
                        }
                        if (completed) null else created.rpc
                    }
                    cleanup?.let { rpc ->
                        servers.values.firstOrNull { it.rpc === rpc }?.let(::retireServer)
                        rpc.close()
                    }
                }
            }
            clojureWarmups[key] = created
            analysisStartups.add(created)
            statusChanged()
            created
        }
        owner.task.start()
        return owner
    }

    private fun failClojure(owner: AnalysisStartup, safeDetail: String) {
        val cleanup = synchronized(lifetimeLock) {
            if (closed || clojureWarmups[owner.key] !== owner || !analysisConsent.isCurrent(owner.permit) ||
                owner.key.workspaceMutationEpoch != mutationEpoch(owner.spec) || owner.phase == LspProjectAnalysisReadiness.FAILED) return
            owner.phase = LspProjectAnalysisReadiness.FAILED
            owner.detail = safeDetail.take(300)
            statusChanged()
            owner.task to owner.rpc
        }
        // Never cancel/close while holding the publication lock or await this owner
        // from its own inbound request/notification callback.
        managerScope.launch { stopAnalysisStartup(cleanup) }
    }

    private fun clojureClasspathFailure(params: JsonElement?): Boolean {
        val obj = params as? JsonObject ?: return false
        val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return message.startsWith("LSP classpath lookup failed when running `") ||
            message.startsWith("Classpath lookup failed when running `")
    }

    private fun retireObservedClojure(path: String) {
        val cleanup = synchronized(lifetimeLock) {
            val observed = LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()
            val key = observed?.let { serverKey(it, path) }
            clojureWarmups.values.filter { owner ->
                !analysisConsent.isCurrent(owner.permit) || owner.state?.svelteSuperseded == true ||
                    ((owner.path == path && owner.phase == LspProjectAnalysisReadiness.PREPARING ||
                        owner.state?.documentPaths?.contains(path) == true &&
                            !(LspServerProfiles.isVue(owner.spec) && LspVueProfile.relevantPath(path) && !LspVueProfile.supportsPath(path))) &&
                        (key != owner.key || !projectAvailable(owner.spec, File(owner.key.projectPath))))
            }.also { owners ->
                owners.forEach { clojureWarmups.remove(it.key, it) }
                if (owners.isNotEmpty()) statusChanged()
            }.map { it.task to it.rpc }
        }
        if (cleanup.isNotEmpty()) managerScope.launch(Dispatchers.IO) { cleanup.forEach(::stopAnalysisStartup) }
    }

    override suspend fun retryProjectAnalysis(path: String): String {
        if (isElp(path)) return elp.retryProjectAnalysis(path)
        if (isGraphql(path)) return graphql.retryProjectAnalysis(path)
        if (isToml(path)) return toml.retryProjectAnalysis(path)
        if (isCSharp(path)) return csharp.retryProjectAnalysis(path)
        val document = canonicalPath(path)
        retireObservedClojure(document)
        val (spec, _) = analysisTarget(document) ?: return "Project analysis is disabled."
        if (!usesBackgroundPreparation(spec)) return "This server has no background analysis retry action."
        val key = serverKey(spec, document)
        val permit = requireNotNull(requireAnalysisPermit(spec, document, key))
        val old = synchronized(lifetimeLock) {
            requireCurrentRoute(spec, key, document, permit)
            val previous = clojureWarmups[key]
            if (previous != null && previous.phase != LspProjectAnalysisReadiness.FAILED)
                return "${preparationLabel(spec)} analysis is already ${if (previous.phase == LspProjectAnalysisReadiness.READY) "ready" else "preparing"}; Revoke stops it."
            clojureWarmups.remove(key)
            unavailableUntil.remove(key)
            previous?.let { it.task to it.rpc }
        }
        old?.let(::stopAnalysisStartup)
        queueClojure(spec, key, document, permit)
        return "Preparing ${preparationLabel(spec)} project analysis again under the current grant; Revoke stops it."
    }

    private suspend fun serverFor(path: String): ServerState? {
        currentCoroutineContext()[VueOperation]?.let { operation ->
            return synchronized(lifetimeLock) { operation.state.takeIf { vueOwnerCurrent(it) && operation.admission.isDemandCurrent() } }
        }
        check(!closed && managerJob.isActive) { "Language-server manager has been closed" }
        retireObservedClojure(path)
        requirePrismaSupport(path)
        // Initial replay owns documentMutex. A feature caller must observe the
        // manager-owned preparation state without waiting behind that replay.
        // Keep custom routes and all other families on their existing path.
        LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()
            ?.takeIf(::usesBackgroundPreparation)?.let { spec ->
                val key = serverKey(spec, path)
                check(projectAvailable(spec, File(key.projectPath))) { unavailableProjectMessage(spec, File(key.projectPath)) }
                val permit = requireNotNull(requireAnalysisPermit(spec, path, key))
                val owner = queueClojure(spec, key, path, permit)
                clojureNotReady(owner)?.let { throw it }
                if (LspServerProfiles.isVue(spec)) return synchronized(lifetimeLock) { owner.state?.takeIf(::vueOwnerCurrent) }
            }
        retireChangedProjectRoots(path)
        existingServerFor(path)?.let { return it }
        val candidates = LanguageServerRegistry.forPath(path, extraLanguageServers())
        var probeFailure: ToolchainProbeFailed? = null
        for (spec in candidates) {
            val key = serverKey(spec, path)
            check(projectAvailable(spec, File(key.projectPath))) { unavailableProjectMessage(spec, File(key.projectPath)) }
            val permit = requireAnalysisPermit(spec, path, key)
            if (usesBackgroundPreparation(spec)) {
                val owner = queueClojure(spec, key, path, requireNotNull(permit))
                clojureNotReady(owner)?.let { throw it }
                return existingServerFor(path)
            }
            val now = System.currentTimeMillis()
            if ((unavailableUntil[key] ?: 0L) > now) continue
            unavailableUntil.remove(key)
            servers[key]?.takeIf { it.rpc.isRunning && (it.analysisPermit == null || analysisConsent.isCurrent(it.analysisPermit)) }?.let { return it }
            servers[key]?.let(::retireServer)
            val started = try { startMutex.withLock {
                check(!closed && managerJob.isActive) { "Language-server manager has been closed" }
                servers[key]?.takeIf { it.rpc.isRunning && (it.analysisPermit == null || analysisConsent.isCurrent(it.analysisPermit)) }
                    ?: startServer(spec, key, path, permit)
            } } catch (failure: ToolchainProbeFailed) {
                if (probeFailure == null) probeFailure = failure else probeFailure.addSuppressed(failure)
                continue
            }
            if (started != null) return started
        }
        probeFailure?.let { throw it }
        return null
    }

    private fun existingServerFor(path: String): ServerState? =
        if (closed) null else LanguageServerRegistry.forPath(path, extraLanguageServers()).firstNotNullOfOrNull { servers[serverKey(it, path)]?.takeIf { s -> s.rpc.isRunning && clojureReady(s) && currentRoute(s.spec, s.key, path) && (s.analysisPermit == null || analysisConsent.isCurrent(s.analysisPermit)) } }

    private fun analysisConfiguration(spec: LanguageServerSpec, project: File): String =
        LspServerProfiles.analysisConfiguration(spec, root, project) +
            (if (LspServerProfiles.isSvelte(spec)) "|observed-config:" + (svelteConfigDigests[project.path] ?: "unobserved") else "") +
            (if (LspServerProfiles.isVue(spec)) "|managed:" + vueIdentity?.consentIdentity else "") +
            (if (LspServerProfiles.isCMake(spec)) "|selection:" + cmakeSelections?.selectionFor(File(project, "CMakeLists.txt")) +
                "|managed:" + cmakeIdentity?.consentIdentity else "")

    private fun serverKey(spec: LanguageServerSpec, path: String): ServerKey {
        val project = if (LspServerProfiles.isCMake(spec)) cmakeSelection(path)?.sourceLocalCanonical?.let(::File)
            ?: File(root, path).parentFile else if (LspServerProfiles.hasProjectRoots(spec))
            LspServerProfiles.projectRoot(spec, root, files.resolveChecked(path)) else root
        // The released Vim server uses marker names from initialization, not rootUri.
        // Replacing markers at the same path must therefore invalidate the session.
        return ServerKey(spec.id, project.path, LspServerProfiles.initializationOptions(spec, project),
            if (LspServerProfiles.requiresProjectAnalysis(spec, path)) analysisConfiguration(spec, project) else null,
            if (spec.id == "marksman") LspServerProfiles.analysisConfiguration(spec) else null,
            LspServerProfiles.publishesWorkspaceRoot(spec, project),
            mutationEpoch(spec), if (LspServerProfiles.isSqruff(spec)) sqruffTextEpochs[project.path] ?: 0L else 0L,
            if (LspServerProfiles.isCMake(spec)) cmakeRefreshEpochs[project.path] ?: 0L else 0L,
            if (LspServerProfiles.isNim(spec)) nimSaveRefreshEpochs[project.path] ?: 0L else 0L,
            LspServerProfiles.analysisConfiguration(spec))
    }

    private suspend fun startServer(spec: LanguageServerSpec, key: ServerKey, path: String, permit: LspProjectAnalysisConsent.Permit?,
                                    startupAdmission: (() -> Unit)? = null): ServerState? {
        val sqlToken = if (LspServerProfiles.isSqruff(spec)) documents[path]?.takeIf { it.text != null } ?: sqruffAdmissions[path] else null
        val sqlAdmission: (() -> Unit)? = if (LspServerProfiles.isSqruff(spec)) ({
            startupAdmission?.invoke()
            val current = documents[path]?.takeIf { it.text != null } ?: sqruffAdmissions[path]?.takeIf(::documentRequestStillCurrent)
            check(sqlToken != null && current === sqlToken) { "SQL initiating snapshot changed during startup" }
        }) else startupAdmission
        val prismaToken = if (LspServerProfiles.isPrisma(spec)) prismaScopeToken(path) else null
        val exactAdmission: (() -> Unit)? = if (LspServerProfiles.isPrisma(spec)) ({
            startupAdmission?.invoke()
            check(prismaToken != null && prismaScopeToken(path) === prismaToken && prismaDocumentEligible(path, key)) {
                "Prisma initiating document changed during startup"
            }
        }) else sqlAdmission
        if (permit == null) return startServerAttempt(spec, key, path, null, null, exactAdmission)
        val startup = AnalysisStartup(permit, path, spec, key)
        startup.task = managerScope.async(start = CoroutineStart.LAZY) {
            startServerAttempt(spec, key, path, permit, startup, exactAdmission)
        }
        var delivered = false
        try {
            synchronized(lifetimeLock) {
                requireCurrentRoute(spec, key, path, permit)
                exactAdmission?.invoke()
                analysisStartups.add(startup)
            }
            startup.task.start()
            val result = startup.task.await()
            currentCoroutineContext().ensureActive()
            delivered = true
            return result
        } finally {
            val cleanup = synchronized(lifetimeLock) {
                analysisStartups.remove(startup)
                startup.task to startup.rpc
            }
            // Caller cancellation ends only its startup, never its project grant.
            if (!delivered) stopAnalysisStartup(cleanup) else startup.task.cancel()
        }
    }

    /** Exactly one typed managed process owns the entire native pair. No generic launch fallback. */
    private suspend fun startVueServer(spec: LanguageServerSpec, key: ServerKey, path: String,
        permit: LspProjectAnalysisConsent.Permit, startup: AnalysisStartup?, startupAdmission: (() -> Unit)?): ServerState? {
        val remote = requireNotNull(remoteProcessHost) { "Vue requires the reviewed managed host" }
        val identity = requireNotNull(vueIdentity) { "Review the managed Vue selection before enabling analysis" }
        var owner: VuePreparedOwner? = null
        var transport: JsonRpcProcess? = null
        var watch: LspVueWatchAuthority? = null
        var publishedState: ServerState? = null
        var delivered = false
        val stateRef = java.util.concurrent.atomic.AtomicReference<ServerState?>(null)
        val initialLedger = synchronized(lifetimeLock) { check(!vueLedgerUnavailable); vueLedger.filterValues { it.text != null } }
        val initialMutation = synchronized(lifetimeLock) { vueLedgerMutation }
        fun currentStart() {
            requireCurrentRoute(spec, key, path, permit)
            check(vueIdentity == identity && documents[path]?.text != null) { "Vue selection or initiating lifetime changed" }
            startupAdmission?.invoke()
        }
        try {
            currentStart()
            check(remote.reviewedVueLaunchIdentity(LspVueProfile.COMMAND, root.path) == identity) { "Vue managed selection changed; review the new identity" }
            val mapper = requireNotNull(remote.pathMapper()) { "Vue workspace mapping unavailable" }
            currentStart()
            val observer = LspVueWatchAuthority(root, managerScope, onInvalidated = { reason ->
                synchronized(lifetimeLock) {
                    vueHeldResults.clear()
                    clearDiagnosticReports { LspServerProfiles.isVue(it.state.spec) }
                    statusChanged()
                }
                stateRef.get()?.let { failVue(it, reason) }
                    ?: startup?.let { failClojure(it, reason) }
            })
            watch = observer
            val observation = observer.start()
            currentStart()
            val epoch = vueOwnerSequence.incrementAndGet()
            val preparedOwner = remote.prepareReviewedVueOwner(identity,
                VueOwnerRequest(root.path, mapper.localToRemote(root), epoch, LspVueLaunchPolicy.sha(permit.identity.toString().toByteArray(Charsets.UTF_8)),
                    projectLocalCanonical = root.path))
            owner = preparedOwner
            currentStart()
            val process = java.util.concurrent.atomic.AtomicReference<VueHostedProcess?>(null)
            val command = listOf(identity.launchExecutable)
            val typedHost = object : StdioProcessHost by remote {
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                    check(argv == command && environment.isEmpty() && resourceLimits == null) { "Vue managed launch cannot be replaced" }
                    currentStart()
                    val hosted = remote.startReviewedVueInWorkspace(identity, preparedOwner) { create ->
                        synchronized(lifetimeLock) { currentStart(); check(preparedOwner.isCurrent); create() }
                    }
                    process.set(hosted)
                    return hosted
                }
            }
            val rpc = JsonRpcProcess(managerScope, root, command, processHost = typedHost,
                sessionAdmission = { frame -> synchronized(lifetimeLock) {
                    val state = stateRef.get()
                    val method = (frame?.get("method") as? JsonPrimitive)?.contentOrNull
                    if (state == null) {
                        currentStart()
                        check(preparedOwner.isCurrent && observer.isCurrent(observation)) { "Vue startup observation retired" }
                        check(initialMutation == vueLedgerMutation && !vueLedgerUnavailable && initialLedger == vueLedger.filterValues { it.text != null }) {
                            "Vue complete ledger changed during startup"
                        }
                        check(method == null || method in setOf("initialize", "initialized", "shutdown", "exit", "$/cancelRequest")) { "Unexpected Vue startup frame" }
                    } else {
                        check(servers[key] === state && preparedOwner.isCurrent && observer.isCurrent(observation)) { "Vue exact owner retired" }
                        if (method in setOf("droide/vue/synchronize", "droide/vue/request")) {
                            val ticket = frame?.get("params")?.let { state.vueTickets[it] }
                            check(ticket != null && vueCaptureCurrent(state, ticket, false)) { "Vue queued write lost complete capture" }
                            ticket.operation?.markDispatched()
                        } else check(method == null || method in setOf("shutdown", "exit", "$/cancelRequest")) { "Generic Vue feature or document write is forbidden" }
                    }
                } }, inboundRequestHandler = { method, _ -> when (method) {
                    "workspace/applyEdit" -> buildJsonObject { put("applied", false); put("failureReason", "Native Vue edits require reviewed captured client proposals") }
                    "window/showDocument" -> buildJsonObject { put("success", false) }
                    "window/showMessageRequest" -> JsonNull
                    else -> error("Vue helper callback unavailable")
                } })
            transport = rpc
            synchronized(lifetimeLock) { currentStart(); startup?.let { check(it in analysisStartups); it.rpc = rpc } }
            rpc.start()
            currentStart()
            val result = rpc.request("initialize", buildJsonObject {
                put("processId", JsonNull)
                put("rootUri", mapper.remoteUriFor(root))
                put("workspaceFolders", buildJsonArray { add(workspaceFolder(mapper, root, spec)) })
                put("clientInfo", buildJsonObject { put("name", "Droide"); put("version", "1.00") })
                put("capabilities", LspServerProfiles.clientCapabilities(spec))
                put("initializationOptions", buildJsonObject { put("contractVersion", 1); put("pairEpoch", epoch) })
                put("trace", "off")
            }, timeoutMs = 25_000)
            LspVueProfile.requireInitializeContract(result)
            rpc.notify("initialized", buildJsonObject {})
            currentStart()
            val state = ServerState(key, root, spec, rpc, command, remote, mapper,
                LspVueProfile.supportedCapabilities(LspNegotiatedCapabilities.fromInitialize(result, enableTypeDefinition = true)), permit,
                vueOwner = preparedOwner, vueProcess = requireNotNull(process.get()), vueEpoch = epoch, vueIdentity = identity)
            state.vueWatch = observer
            state.vueObservation = observation
            state.vueRequests = LspVueRequests(managerScope, onDeadline = { failVue(state, "Vue native request deadline expired; the pair is unavailable. Retry after cleanup.") })
            synchronized(lifetimeLock) {
                currentStart()
                check(preparedOwner.isCurrent && observer.isCurrent(observation))
                servers[key] = state
                startup?.state = state
                stateRef.set(state)
                publishedState = state
            }
            state.notificationJob = managerScope.launch {
                try { rpc.notifications.collect { notification ->
                    if (notification.method == "droide/vue/diagnosticsInvalidated") vueDiagnosticsInvalidated(state, notification.params)
                    // Native pushes never establish captured current diagnostics.
                } }
                finally { retireServer(state) }
            }
            delivered = true
            return state
        } finally {
            if (!delivered) { publishedState?.let(::retireServer); watch?.close(); transport?.close(); owner?.close() }
        }
    }

    private fun vueDiagnosticsInvalidated(state: ServerState, raw: JsonElement?) {
        val params = raw as? JsonObject ?: return failVue(state, "Malformed Vue diagnostic invalidation")
        fun number(key: String) = (params[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val generation = number("refreshGeneration")
        if (params.keys != setOf("contractVersion", "pairEpoch", "refreshGeneration") || number("contractVersion") != 1L ||
            number("pairEpoch") != state.vueEpoch || generation == null || generation !in 1..9_007_199_254_740_991L)
            return failVue(state, "Malformed Vue diagnostic invalidation")
        synchronized(lifetimeLock) {
            if (servers[state.key] !== state || !state.vueActive || generation <= state.vueDiagnosticGeneration) return
            state.vueDiagnosticGeneration = generation
            state.diagnosticRequests.clear()
            clearDiagnosticReports { it.state === state }
            statusChanged()
            if (state.vueDiagnosticRerun?.isActive == true || state.vueDiagnosticPath == null) return
            state.vueDiagnosticRerun = managerScope.launch {
                try {
                    while (isActive) {
                        val ready = synchronized(lifetimeLock) {
                            if (servers[state.key] !== state || !state.vueActive) return@launch
                            state.vueTickets.isEmpty() && state.vueRequests?.isIdle() == true
                        }
                        if (ready) break
                        delay(10)
                    }
                    val path = synchronized(lifetimeLock) { state.vueDiagnosticPath?.takeIf {
                        servers[state.key] === state && state.vueActive && clojureReady(state) && vueLedger[it]?.text != null
                    } } ?: return@launch
                    runSuspendCatching { diagnoseExistingVue(state, path) }
                } finally { synchronized(lifetimeLock) { state.vueDiagnosticRerun = null } }
            }
        }
    }

    private fun failVue(state: ServerState, reason: String) {
        val startup = synchronized(lifetimeLock) {
            state.vueActive = false
            vueHeldResults.clear()
            clearDiagnosticReports { it.state === state }
            statusChanged()
            clojureWarmups[state.key]
        }
        if (startup != null) failClojure(startup, reason) else retireServer(state)
    }

    /** Pure admission. All path inspection is confined to preparation/observation, never acceptance. */
    private fun vueCaptureCurrent(state: ServerState, ticket: VueTicket, requireReady: Boolean): Boolean =
        !closed && managerJob.isActive && servers[state.key] === state && state.vueActive &&
            state.vueOwner?.isCurrent == true && (!requireReady || clojureReady(state)) &&
            state.analysisPermit?.let(analysisConsent::isCurrent) == true &&
            state.key.workspaceMutationEpoch == diskIndexMutationEpoch && state.vueIdentity == vueIdentity &&
            state.vueWatch?.isCurrent(ticket.observation) == true && !vueLedgerUnavailable && ticket.era == vueLedgerEra && ticket.mutation == vueLedgerMutation &&
            relevantDocuments(state) == ticket.snapshots &&
            LanguageServerRegistry.forPath("__vue_profile__.vue", extraLanguageServers()).firstOrNull() == state.spec &&
            (ticket.request?.let(::documentRequestStillCurrent) != false) &&
            (ticket.completionBatch?.let { it == state.vueBatch } != false) &&
            (ticket.diagnosticGeneration?.let { it == state.vueDiagnosticGeneration } != false)

    // Caller owns documentMutex. No ordinary LspDocumentSyncAuthority method is reachable here.
    private suspend fun synchronizeVue(state: ServerState, request: DocumentRequest? = null): VueTicket {
        val operation = currentCoroutineContext()[VueOperation]?.admission
        while (true) {
            if (operation != null && !operation.isDemandCurrent()) throw LspVueRequests.NotAdmitted()
            val ticket = synchronized(lifetimeLock) {
                if (request != null && !documentRequestStillCurrent(request)) throw LspVueRequests.NotAdmitted()
                VueTicket(relevantDocuments(state), vueLedgerMutation, requireNotNull(state.vueObservation), request,
                    vueLedgerEra, operation = operation).also {
                    check(vueCaptureCurrent(state, it, false)) { "Vue capture unavailable before synchronization" }
                }
            }
            val capture = LspVueCompanionAuthority.capture(ticket.era, ticket.mutation, ticket.snapshots.map { (path, snapshot) ->
                LspVueCompanionAuthority.Document(path, uriFor(state, path), languageId(path), snapshot.generation + 1L,
                    snapshot.revision, requireNotNull(snapshot.text))
            })
            val reused = state.vueSync.reusable(capture, state.vueEpoch)
            if (reused != null) { state.vueReceipt = reused; return ticket }
            val params = state.vueSync.synchronize(capture, state.vueEpoch)
            synchronized(lifetimeLock) {
                check(vueCaptureCurrent(state, ticket, false)); check(state.vueTickets.size < 16)
                state.vueTickets[params] = ticket
            }
            try {
                if (operation != null && !operation.isDemandCurrent()) throw LspVueRequests.NotAdmitted()
                val response = state.rpc.request("droide/vue/synchronize", params,
                    timeoutMs = minOf(5_000L, operation?.remainingMs() ?: 5_000L))
                val current = synchronized(lifetimeLock) {
                    // Validate the actual receipt even when newer dirty text superseded its capture.
                    val accepted = state.vueSync.accept(capture, params, response)
                    check(servers[state.key] === state && state.vueActive && state.vueOwner?.isCurrent == true &&
                        state.vueWatch?.isCurrent(ticket.observation) == true && ticket.era == vueLedgerEra) { "Vue synchronization owner retired" }
                    state.vueReceipt = accepted
                    state.documentPaths.clear(); state.documentPaths.addAll(ticket.snapshots.keys)
                    vueCaptureCurrent(state, ticket, false)
                }
                if (current) return ticket
                // No failure or retagging: replay the latest full set in the same operation budget.
                if (request != null && !synchronized(lifetimeLock) { documentRequestStillCurrent(request) }) throw LspVueRequests.NotAdmitted()
            } catch (drop: LspVueRequests.NotAdmitted) { throw drop }
            catch (failure: Throwable) { failVue(state, "Vue synchronization failed; Retry after owned cleanup."); throw failure }
            finally { synchronized(lifetimeLock) { state.vueTickets.remove(params) } }
        }
    }

    private fun vueEnvelope(state: ServerState, ticket: VueTicket, method: String, params: JsonElement): JsonObject {
        val receipt = requireNotNull(state.vueReceipt)
        require(receipt.capture.mutation == ticket.mutation) { "Vue feature requires its exact completed synchronization receipt" }
        return buildJsonObject {
            put("contractVersion", 1); put("pairEpoch", state.vueEpoch); put("syncSerial", receipt.serial)
            put("captureDigest", receipt.capture.digest); put("requestSerial", ++state.vueRequestSerial)
            put("method", method); put("params", params)
            ticket.completionBatch?.let { put("completionBatch", it) }
        }.also { require(it.toString().toByteArray(Charsets.UTF_8).size <= LspVueCompanionAuthority.MAX_REQUEST_BYTES) }
    }

    private suspend fun vueWireRequest(state: ServerState, ticket: VueTicket, method: String, params: JsonElement): JsonElement {
        val envelope = synchronized(lifetimeLock) {
            check(vueCaptureCurrent(state, ticket, false))
            check(state.vueTickets.size < 16) { "Vue request capacity exhausted" }
            vueEnvelope(state, ticket, method, params).also { state.vueTickets[it] = ticket }
        }
        try {
            val response = state.rpc.request("droide/vue/request", envelope,
                timeoutMs = minOf(if (method == "textDocument/completion") 8_000L else 10_000L, ticket.operation?.remainingMs() ?: 10_000L))
            val (result, outcomes) = LspVueResultAuthority.response(response, envelope)
            synchronized(lifetimeLock) {
                if (ticket.diagnosticGeneration != null && ticket.diagnosticGeneration != state.vueDiagnosticGeneration) return JsonNull
                // Native work completed with a valid correlated envelope. Superseded UI/capture results
                // are inert: only malformed envelopes, uncertain transport and deadlines retire the pair.
                if (!vueCaptureCurrent(state, ticket, false)) return JsonNull
                state.vueOutcomeNote = outcomes.entries.joinToString("; ") { (source, raw) ->
                    val o = raw as JsonObject
                    "$source: ${(o["status"] as JsonPrimitive).content}" +
                        if (method == "textDocument/completion") " (${o["accepted"] ?: "?"}/${o["selected"] ?: "?"} selected native items; rejected ${o["rejected"] ?: "?"})" else ""
                }.take(800)
                statusChanged()
                if (method == "textDocument/diagnostic") require(outcomes.keys == setOf("vue", "ts-syntactic", "ts-semantic", "ts-suggestion")) {
                    "Vue diagnostic source envelope is incomplete"
                }
            }
            return if (method == "textDocument/diagnostic") {
                val report = result as? JsonObject ?: error("Vue native diagnostic report missing")
                require((report["droideVueRefreshGeneration"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull == ticket.diagnosticGeneration) { "Vue native refresh generation mismatched" }
                JsonObject(report + ("droideVueSourceOutcomes" to outcomes))
            } else if (method != "textDocument/completion" && outcomes.values.any { ((it as JsonObject)["status"] as JsonPrimitive).content != "ok" }) JsonNull else result
        } catch (drop: LspVueRequests.NotAdmitted) { return JsonNull }
        catch (cancel: CancellationException) {
            if (ticket.operation?.hasDispatched != false) failVue(state, "Vue native request interrupted; Retry after owned cleanup.")
            throw cancel
        }
        catch (failure: Exception) {
            failVue(state, "Vue native response unavailable; Retry after owned cleanup.")
            throw failure
        } finally { synchronized(lifetimeLock) { state.vueTickets.remove(envelope) } }
    }

    private suspend fun vueFeatureRequest(state: ServerState, prepared: PreparedDocument, method: String, params: JsonElement,
        additionalCurrent: () -> Boolean): JsonElement {
        val ticket = synchronized(lifetimeLock) { requireNotNull(prepared.vueTicket).copy(completionBatch = prepared.vueCompletionBatch,
            diagnosticGeneration = if (method == "textDocument/diagnostic") state.vueDiagnosticGeneration else null) }
        val position = (params as? JsonObject)?.get("position")
        if (position != null) {
            val p = position as? JsonObject ?: return JsonNull
            val line = (p["line"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return JsonNull
            val col = (p["character"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return JsonNull
            if (!LspVueResultAuthority.validPosition(prepared.text, line, col)) return JsonNull
        }
        val operation = currentCoroutineContext()[VueOperation]?.admission
        if (operation != null) {
            if (!operation.isDemandCurrent() || !synchronized(lifetimeLock) { vueCaptureCurrent(state, ticket, true) && additionalCurrent() }) return JsonNull
            return vueWireRequest(state, ticket.copy(operation = operation), method, params)
        }
        return requireNotNull(state.vueRequests).request(method, isCurrent = {
            synchronized(lifetimeLock) { vueCaptureCurrent(state, ticket, true) && additionalCurrent() }
        }) { vueWireRequest(state, ticket, method, params) } ?: JsonNull
    }

    private suspend fun vueInitialFeature(state: ServerState, path: String) {
        val ticket = synchronized(lifetimeLock) {
            VueTicket(relevantDocuments(state), vueLedgerMutation, requireNotNull(state.vueObservation), era = vueLedgerEra).also {
                check(vueCaptureCurrent(state, it, false)); check(path in it.snapshots)
            }
        }
        val result = vueWireRequest(state, ticket, "textDocument/documentSymbol", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uriFor(state, path)) })
        })
        require(result is JsonArray) { "Vue native document-symbol readiness response unavailable" }
    }

    private suspend fun vueCompletion(state: ServerState, prepared: PreparedDocument, line: Int, col: Int, trigger: String?): List<LspCompletionItem> {
        if (!LspVueResultAuthority.validPosition(prepared.text, line - 1, col - 1)) return emptyList()
        val batch = (currentCoroutineContext()[VueOperation]?.completionBatch
            ?: synchronized(lifetimeLock) { ++state.vueBatch }).also { prepared.vueCompletionBatch = it }
        val base = LspProtocolContract.positionParams(prepared.uri, line, col)
        val params = JsonObject(base + ("context" to buildJsonObject {
            val character = trigger?.takeIf { it in state.capabilities.completionTriggerCharacters }
            put("triggerKind", if (character == null) 1 else 2); character?.let { put("triggerCharacter", it) }
        }))
        val result = vueFeatureRequest(state, prepared, "textDocument/completion", params) { true }
        val ticket = requireNotNull(prepared.vueTicket).copy(completionBatch = batch)
        val guard = LspCompletionApplicationGuard(prepared.text, LspCompletionCaret(line, col)) {
            synchronized(lifetimeLock) { vueCaptureCurrent(state, ticket, true) }
        }
        return if (!guard.isCurrent()) emptyList() else LspVueResultAuthority.completion(result, line, col, prepared.text, batch, guard)
    }

    private fun vueHeldGuard(state: ServerState, prepared: PreparedDocument): LspHeldResultGuard =
        LspHeldResultGuard { synchronized(lifetimeLock) { requestStillCurrent(state, prepared) } }

    /** This exact profile has a typed owner; no generic command or caller environment can substitute. */
    private suspend fun startCMakeServer(spec: LanguageServerSpec, key: ServerKey, path: String,
        permit: LspProjectAnalysisConsent.Permit, startup: AnalysisStartup?, startupAdmission: (() -> Unit)?): ServerState? {
        val remote = requireNotNull(remoteProcessHost)
        val selection = requireNotNull(cmakeSelection(path))
        val identity = requireNotNull(cmakeIdentity)
        val store = requireNotNull(cmakeSelections)
        var owner: CMakePreparedOwner? = null
        var rpc: JsonRpcProcess? = null
        var publishedState: ServerState? = null
        var delivered = false
        fun currentStart() {
            requireCurrentRoute(spec, key, path, permit)
            check(store.isCurrent(selection) && cmakeSelection(path) === selection && cmakeIdentity == identity) { "CMake approved selection changed" }
            startupAdmission?.invoke()
        }
        try {
            return withTimeout(30_000) {
                currentStart()
                check(withContext(Dispatchers.IO) { store.revalidate(selection) }) { "CMake source/build selection is unavailable" }
                check(remote.reviewedCMakeLaunchIdentity(LspCMakeProfile.COMMAND, root.path) == identity) { "CMake managed package changed; review and enable the new identity" }
                currentStart()
                val preparedOwner = remote.prepareReviewedCMakeOwner(identity, CMakeOwnerRequest(selection, cmakeOwnerSequence.incrementAndGet()))
                owner = preparedOwner
                currentStart()
                val context = requireNotNull(remote.cmakeSelectionContext(root.path)) { "CMake mapping is unavailable" }
                check(context.environmentIdentity == selection.environmentIdentity && context.mappingRevision == selection.mappingRevision) { "CMake environment mapping changed" }
                val mapper = context.mapper
                val process = java.util.concurrent.atomic.AtomicReference<CMakeHostedProcess?>(null)
                val observation = java.util.concurrent.atomic.AtomicReference<CMakeHostAdmissionObservation?>(null)
                val stateRef = java.util.concurrent.atomic.AtomicReference<ServerState?>(null)
                val command = listOf(identity.launchExecutable)
                val typedHost = object : StdioProcessHost by remote {
                    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                        check(argv == command && environment.isEmpty() && resourceLimits == null) { "CMake launch arguments cannot be replaced" }
                        currentStart()
                        val hosted = remote.startReviewedCMakeInWorkspace(identity, preparedOwner) { create ->
                            synchronized(lifetimeLock) { currentStart(); create() }
                        }
                        process.set(hosted)
                        currentStart()
                        observation.set(requireNotNull(hosted.checkedAdmission()) { "CMake owned process admission failed" })
                        currentStart()
                        return hosted
                    }
                }
                lateinit var transport: JsonRpcProcess
                transport = JsonRpcProcess(managerScope, File(selection.sourceLocalCanonical), command, processHost = typedHost,
                    sessionAdmission = { frame -> synchronized(lifetimeLock) {
                        val state = stateRef.get()
                        if (state == null || servers[key] !== state) {
                            check(state == null) { "CMake owner was retired" }
                            currentStart()
                            if (frame != null) check(observation.get()?.let { process.get()?.isAdmissionCurrent(it) } == true) { "CMake startup observation changed" }
                        } else {
                            val method = (frame?.get("method") as? JsonPrimitive)?.contentOrNull
                            val params = frame?.get("params")
                            val ticket = params?.let { state.cmakeTickets[it] }
                                ?: state.cmakeWireTicket?.takeIf { method in setOf("textDocument/didOpen", "textDocument/didChange") }
                            check(ticket != null && (ticket.method == method || ticket.method == "document-sync" && method in setOf("textDocument/didOpen", "textDocument/didChange")) &&
                                cmakeCaptureCurrent(state, ticket.snapshots, ticket.observation, ticket.request)) {
                                "CMake queued frame has no current captured admission"
                            }
                        }
                    } }, inboundRequestHandler = { method, _ ->
                        when (method) {
                            "workspace/applyEdit" -> buildJsonObject { put("applied", false); put("failureReason", "CMake server-driven edits are unavailable") }
                            "window/showDocument" -> buildJsonObject { put("success", false) }
                            "window/showMessageRequest" -> JsonNull
                            else -> error("CMake client callback was not advertised")
                        }
                    })
                rpc = transport
                synchronized(lifetimeLock) {
                    currentStart()
                    if (startup != null) { check(startup in analysisStartups && startup.task.isActive); startup.rpc = transport }
                }
                transport.start()
                currentStart()
                val hosted = requireNotNull(process.get())
                observation.set(requireNotNull(hosted.checkedAdmission()) { "CMake startup selection changed" })
                currentStart()
                val init = buildJsonObject {
                    put("processId", JsonNull)
                    put("clientInfo", buildJsonObject { put("name", "Droide"); put("version", "1.00") })
                    put("rootUri", mapper.remoteUriFor(File(selection.sourceLocalCanonical)))
                    put("workspaceFolders", buildJsonArray { add(workspaceFolder(mapper, File(selection.sourceLocalCanonical), spec)) })
                    put("capabilities", LspServerProfiles.clientCapabilities(spec))
                    put("initializationOptions", LspCMakeProfile.initializationOptions(identity.cmakeExecutableGuestAbsolute, preparedOwner.buildGuestAbsolute))
                    put("trace", "off")
                }
                val initialized = transport.request("initialize", init, timeoutMs = 20_000)
                LspCMakeProfile.requireInitializeContract(initialized)
                currentStart()
                observation.set(requireNotNull(hosted.checkedAdmission()) { "CMake initialization changed its owned admission" })
                currentStart()
                val native = LspNegotiatedCapabilities.fromInitialize(initialized, "utf-32", requireExplicitEncoding = true)
                val capabilities = LspCMakeProfile.supportedCapabilities(native).copy(
                    sync = native.sync.copy(change = LspTextSyncPolicy.ChangeKind.FULL, save = false))
                transport.notify("initialized", buildJsonObject {})
                // No acknowledgement exists for native query completion. This proves protocol/ownership only.
                val finalObservation = requireNotNull(hosted.checkedAdmission()) { "CMake owner became unavailable before publication" }
                currentStart()
                val state = ServerState(key, File(selection.sourceLocalCanonical), spec, transport, command, remote, mapper,
                    capabilities, permit, cmakeSelection = selection, cmakeProcess = hosted, cmakeOwner = preparedOwner)
                synchronized(lifetimeLock) {
                    currentStart()
                    check(hosted.isAdmissionCurrent(finalObservation)) { "CMake final ownership changed" }
                    check(startup == null || startup.task.isActive)
                    servers[key] = state
                    stateRef.set(state)
                    publishedState = state
                }
                // Unexpected native diagnostics are deliberately not accepted as this profile has none.
                state.notificationJob = managerScope.launch {
                    try { transport.notifications.collect { /* No native client notifications are required. */ } }
                    finally { retireServer(state) }
                }
                statusChanged()
                delivered = true
                state
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            if (runCatching { currentStart() }.isSuccess) unavailableUntil[key] = System.currentTimeMillis() + 30_000
            statusChanged()
            return null
        } finally {
            if (!delivered) {
                publishedState?.let(::retireServer)
                rpc?.close()
                // Retained owner cleanup is independent of the canceled manager/request job.
                owner?.close()
            }
        }
    }

    /** Pure captured-owner check for queued writes and popup acceptance. No path or cache I/O. */
    private fun cmakeCaptureCurrent(state: ServerState, snapshots: Map<String, DocumentSnapshot>,
        observation: CMakeHostAdmissionObservation, request: DocumentRequest? = null): Boolean =
        !closed && managerJob.isActive && servers[state.key] === state && state.rpc.isRunning &&
            state.analysisPermit?.let(analysisConsent::isCurrent) == true &&
            state.key.workspaceMutationEpoch == mutationEpoch(state.spec) &&
            state.key.cmakeRefreshEpoch == (cmakeRefreshEpochs[state.key.projectPath] ?: 0L) &&
            state.cmakeSelection?.let { cmakeSelections?.isCurrent(it) } == true &&
            analysisConfiguration(state.spec, state.projectRoot) == state.key.analysisConfiguration &&
            state.cmakeProcess?.isAdmissionCurrent(observation) == true && relevantDocuments(state) == snapshots &&
            (request == null || documentRequestStillCurrent(request) &&
                LanguageServerRegistry.forPath(request.path, extraLanguageServers()).firstOrNull() == state.spec)

    private suspend fun checkedCMakeAdmission(state: ServerState): CMakeHostAdmissionObservation {
        try {
            val selection = requireNotNull(state.cmakeSelection)
            check(withContext(Dispatchers.IO) { cmakeSelections?.revalidate(selection) == true }) { "CMake selection is unavailable" }
            val observation = requireNotNull(state.cmakeProcess?.checkedAdmission()) { "CMake owned process is unavailable or quarantined" }
            synchronized(lifetimeLock) {
                check(cmakeCaptureCurrent(state, relevantDocuments(state), observation)) { "CMake owner changed during observation" }
            }
            return observation
        } catch (failure: Throwable) { retireServer(state); throw failure }
    }

    private suspend fun cmakeRequest(state: ServerState, prepared: PreparedDocument, method: String, params: JsonElement): JsonElement {
        check(method in setOf("textDocument/completion", "textDocument/hover")) { "Unsupported CMake operation" }
        val original = requireNotNull(prepared.cmakeObservation)
        checkedCMakeAdmission(state)
        val ticket = CMakeTicket(method, prepared.snapshots, original, prepared.request)
        synchronized(lifetimeLock) {
            check(cmakeCaptureCurrent(state, ticket.snapshots, ticket.observation, ticket.request)) { "CMake request capture changed" }
            check(state.cmakeTickets.size < 16) { "Too many CMake native requests are pending" }
            state.cmakeTickets[params] = ticket
        }
        try {
            val result = state.rpc.request(method, params, timeoutMs = 15_000)
            checkedCMakeAdmission(state)
            synchronized(lifetimeLock) { check(cmakeCaptureCurrent(state, ticket.snapshots, ticket.observation, ticket.request)) { "CMake result capture changed" } }
            return result
        } catch (failure: Throwable) {
            // Native request cancellation is not a completion acknowledgement. Stop the exact owner.
            retireServer(state)
            throw failure
        } finally { synchronized(lifetimeLock) { state.cmakeTickets.remove(params) } }
    }

    private suspend fun cmakeCompletion(state: ServerState, prepared: PreparedDocument, line: Int, col: Int): List<LspCompletionItem> {
        val context = LspCMakeCompletionContext.capture(prepared.text, line, col) ?: return emptyList()
        val base = context.snapshot.positionParams(prepared.uri, line, col) ?: return emptyList()
        val params = JsonObject(base + ("context" to buildJsonObject {
            put("triggerKind", if (context.triggerCharacter == null) 1 else 2)
            context.triggerCharacter?.let { put("triggerCharacter", it) }
        }))
        val result = cmakeRequest(state, prepared, "textDocument/completion", params)
        val observation = requireNotNull(prepared.cmakeObservation)
        val guard = LspCompletionApplicationGuard(prepared.text, requiredCaret = context.requestCaret) {
            synchronized(lifetimeLock) { cmakeCaptureCurrent(state, prepared.snapshots, observation, prepared.request) }
        }
        return LspCMakeCompletionContext.parse(result, context, guard)
    }

    private suspend fun startServerAttempt(spec: LanguageServerSpec, key: ServerKey, path: String,
                                          permit: LspProjectAnalysisConsent.Permit?, startup: AnalysisStartup?,
                                          startupAdmission: (() -> Unit)?): ServerState? {
        if (LspServerProfiles.isVue(spec)) return startVueServer(spec, key, path, requireNotNull(permit), startup, startupAdmission)
        if (LspServerProfiles.isCMake(spec)) return startCMakeServer(spec, key, path, requireNotNull(permit), startup, startupAdmission)
        val capturedEnvironmentEpoch = synchronized(lifetimeLock) { environmentEpoch }
        val projectRoot = File(key.projectPath)
        fun requireCurrentStart() {
            requireCurrentRoute(spec, key, path, permit)
            startupAdmission?.invoke()
        }
        requireCurrentStart()
        val svelteBaseline = if (LspServerProfiles.isSvelte(spec)) {
            val observed = LspSvelteFilePoller(projectRoot).scan()
            requireCurrentStart()
            if (observed.criticalDigest != svelteConfigDigests[projectRoot.path]) {
                synchronized(lifetimeLock) {
                    permit?.identity?.let(analysisConsent::revoke)
                    svelteConfigDigests[projectRoot.path] = observed.criticalDigest
                    statusChanged()
                }
                error("Svelte configuration changed; enable project analysis for the new configuration")
            }
            observed
        } else null
        var lastError: LspStartupFailure? = null
        var discoveryFailure: ToolchainProbeFailed? = null
        for (candidate in spec.commands) {
            if (candidate.isEmpty()) continue
            

            val remote = remoteProcessHost ?: continue
            val resolved = try {
                val executable = checkedToolchainProbe("Language server ${candidate.first()}") { remote.resolveExecutable(candidate.first()) } ?: continue
                val mapper = checkedToolchainProbe("Language server workspace") { requireNotNull(remote.pathMapper()) { "Workspace path mapping is unavailable" } }
                executable to mapper
            } catch (failure: ToolchainProbeFailed) {
                if (discoveryFailure == null) discoveryFailure = failure else discoveryFailure.addSuppressed(failure)
                continue
            }
            val executable = resolved.first
            val processHost: StdioProcessHost = remote
            val mapper: WorkspacePathMapper = resolved.second
            check(!closed && managerJob.isActive) { "Language-server manager was closed during discovery" }
            requireCurrentStart()
            LspServerProfiles.requireSupportedDocumentPath(spec, mapper.localRelativeToRemote(path))
            val perl = if (LspServerProfiles.isPerl(spec)) remote.resolveExecutable("perl")
                ?: throw IllegalStateException("PerlNavigator requires a separately installed Perl interpreter in local Ubuntu") else null
            requireCurrentStart()
            val node = if (LspServerProfiles.isZls(spec)) remote.resolveExecutable("node")
                ?: throw IllegalStateException("ZLS requires Node for its owned launcher in local Ubuntu") else null
            requireCurrentStart()
            val zig = if (LspServerProfiles.isZls(spec)) remote.resolveExecutable("zig")
                ?: throw IllegalStateException("ZLS requires a separately selected stable Zig 0.16.x compiler in local Ubuntu") else null
            requireCurrentStart()
            val sqruffIdentity = if (LspServerProfiles.isSqruff(spec)) {
                val proof = remote.reviewedSqruffLaunchIdentity(executable, root.path)
                requireCurrentStart()
                if (proof == null) {
                    sqruffUnavailable[key] = "SQL analysis unavailable: the selected executable is not the byte-verified managed sqruff 0.41.0 payload. Install/select the reviewed package, or explicitly configure a generic SQL server."
                    continue
                }
                sqruffUnavailable.remove(key)
                proof
            } else null
            val powershellIdentity = if (LspServerProfiles.isPowerShell(spec)) {
                require(remote.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) { "The reviewed PowerShell host requires local Ubuntu" }
                val identity = remote.reviewedPowerShellLaunchIdentity(executable, root.path)
                requireCurrentStart()
                check(identity != null && LspPowerShellLaunchPolicy.matchesIdentity(identity)) {
                    "PowerShell requires the exact reviewed managed runtime and Editor Services host; generic commands cannot establish deny-safe prompt behavior"
                }
                identity
            } else null
            val svelteIdentity = if (LspServerProfiles.isSvelte(spec)) {
                require(remote.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) { "The reviewed Svelte adapter requires local Ubuntu" }
                val identity = remote.reviewedSvelteLaunchIdentity(executable, root.path)
                requireCurrentStart()
                requireNotNull(identity) { "Svelte requires the reviewed managed dependency graph; generic discovery cannot establish this adapter contract" }
            } else null
            val nimIdentity = if (LspServerProfiles.isNim(spec)) {
                require(remote.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) { "Nim fixed-toolchain analysis requires local Ubuntu" }
                val identity = remote.reviewedNimLaunchIdentity(executable, root.path)
                requireCurrentStart()
                requireNotNull(identity) { "Nim fixed-toolchain analysis requires the exact managed server, Nim 2.2.12 and private Node helper" }
            } else null
            var nimOwner: NimPreparedOwner? = null
            var nimProcess: NimHostedProcess? = null
            val settings = if (LspServerProfiles.isLemMinx(spec)) buildJsonObject {
                put("settings", key.initializationOptions.getValue("settings"))
            } else LspServerProfiles.configurationAfterInitialize(spec, perl)
            val rpcHost = if (LspServerProfiles.isPerl(spec) || LspServerProfiles.isTexlab(spec) || LspServerProfiles.isTerraform(spec) || LspServerProfiles.isBuf(spec) || LspServerProfiles.isZls(spec) || LspServerProfiles.isLemMinx(spec) || LspServerProfiles.isMake(spec) || usesBackgroundPreparation(spec) || LspServerProfiles.isPrisma(spec) || LspServerProfiles.isSqruff(spec)) object : StdioProcessHost by remote {
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                    requireCurrentStart()
                    if (LspServerProfiles.isNim(spec)) {
                        require(resourceLimits == null) { "Nim owned runtime cannot inherit generic limits" }
                        val owner = remote.prepareReviewedNimOwner(requireNotNull(nimIdentity),
                            NimOwnerRequest(root.path, mapper.localToRemote(projectRoot), nimOwnerSequence.incrementAndGet(),
                                svelteTextDigest(requireNotNull(key.analysisConfiguration)), projectRoot.path))
                        nimOwner = owner
                        try {
                            requireCurrentStart()
                            return remote.startReviewedNimInWorkspace(nimIdentity, owner) { create -> synchronized(lifetimeLock) {
                                requireCurrentStart()
                                check(owner.isCurrent) { "Nim prepared owner was retired" }
                                create().also { nimProcess = it }
                            } }
                        } catch (failure: Throwable) { owner.close(); throw failure }
                    }
                    if (LspServerProfiles.isSqruff(spec))
                        return remote.startReviewedSqruffInWorkspace(requireNotNull(sqruffIdentity), argv, environment,
                            mapper.localToRemote(projectRoot), requireNotNull(resourceLimits)) { create ->
                            // The host verifies managed bytes under its package gates before entering
                            // this bounded action. No suspension or hashing occurs under lifetimeLock.
                            synchronized(lifetimeLock) {
                                requireCurrentStart()
                                create()
                            }
                        }
                    if (LspServerProfiles.isSvelte(spec)) {
                        require(resourceLimits == null) { "Svelte cannot silently discard resource limits" }
                        return remote.startReviewedSvelteInWorkspace(requireNotNull(svelteIdentity), argv, environment,
                            mapper.localToRemote(projectRoot)) { create -> synchronized(lifetimeLock) {
                                requireCurrentStart()
                                create()
                            } }
                    }
                    if (LspServerProfiles.isPowerShell(spec)) {
                        require(resourceLimits == null) { "PowerShell cannot silently inherit native address-space limits" }
                        return remote.startReviewedPowerShellInWorkspace(requireNotNull(powershellIdentity), argv, environment,
                            mapper.localToRemote(projectRoot)) { create -> synchronized(lifetimeLock) {
                                requireCurrentStart()
                                create()
                            } }
                    }
                    require(resourceLimits == null) { "Project-scoped process host cannot silently discard resource limits" }
                    return remote.startInWorkspace(argv, environment, mapper.localToRemote(projectRoot))
                }
            } else remote
            val originalCommand = LspClangdLaunchPolicy.command(spec, listOf(nimIdentity?.launchExecutable ?: svelteIdentity?.launchExecutable ?: powershellIdentity?.launchExecutable ?: sqruffIdentity?.launchExecutable ?: executable) + candidate.drop(1), root, mapper)
            val command = if (LspServerProfiles.isZls(spec)) {
                require(remote.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) { "The reviewed ZLS launcher requires local Ubuntu" }
                LspZlsLaunchPolicy.command(originalCommand, requireNotNull(node), requireNotNull(zig), LspZlsProfile.hint(root, projectRoot).version)
            } else if (LspServerProfiles.isTexlab(spec)) {
                require(remote.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) { "The reviewed Texlab launcher requires local Ubuntu" }
                LspTexlabLaunchPolicy.command(originalCommand)
            } else originalCommand
            // Compute mapping once on the ordinary preparation path, never while
            // answering a native callback on the serialized inbound handler.
            val powershellWorkspaceFolders = if (LspServerProfiles.isPowerShell(spec)) buildJsonArray {
                if (key.publishesWorkspaceRoot) add(workspaceFolder(mapper, projectRoot, spec))
            } else null
            lateinit var rpc: JsonRpcProcess
            val sessionPublished = java.util.concurrent.atomic.AtomicBoolean(false)
            val svelteState = java.util.concurrent.atomic.AtomicReference<ServerState?>(null)
            val svelteCallbackAdmission = java.util.concurrent.atomic.AtomicReference<(() -> Boolean)?>(null)
            val svelteRegistrationWritten = CompletableDeferred<Unit>()
            val svelteDrain = java.util.concurrent.atomic.AtomicReference<Pair<JsonElement, LspSvelteWatchAuthority.EventDrain>?>(null)
            var svelteWatch: LspSvelteWatchAuthority? = null
            fun svelteOwnerCurrent(): Boolean = !closed && permit != null && analysisConsent.isCurrent(permit) &&
                key.workspaceMutationEpoch == mutationEpoch(spec) && projectAvailable(spec, projectRoot) &&
                analysisConfiguration(spec, projectRoot) == key.analysisConfiguration &&
                (if (sessionPublished.get()) servers[key]?.rpc === rpc else
                    startup?.let { it in analysisStartups && it.rpc === rpc && it.task.isActive } == true)
            if (LspServerProfiles.isSvelte(spec)) svelteWatch = LspSvelteWatchAuthority(
                ownerKey = "${key.serverId}:${key.projectPath}:${System.identityHashCode(startup)}", root = projectRoot,
                scope = managerScope, toUri = { file -> mapper.remoteUriFor(file) }, initialSnapshot = svelteBaseline,
                onInvalidated = { invalidation ->
                    synchronized(lifetimeLock) {
                        val owner = svelteState.get()
                        if (owner != null) {
                            owner.svelteDraining = true
                            val stale = diagnosticReports.filterValues { it.state === owner }.keys
                            stale.forEach { diagnosticReports.remove(it) }
                            _diagnostics.update { it - stale }
                        }
                        if (invalidation.criticalChange) {
                            permit?.identity?.let(analysisConsent::revoke)
                            svelteConfigDigests.remove(projectRoot.path)
                        }
                        statusChanged()
                    }
                    if (invalidation.kind != LspSvelteWatchAuthority.InvalidationKind.DIFFERENCE)
                        managerScope.launch(Dispatchers.IO) {
                            svelteState.get()?.let(::retireServer)
                            startup?.let { stopAnalysisStartup(it.task to it.rpc) }
                        }
                }, onObservation = { observation ->
                    synchronized(lifetimeLock) {
                        val owner = svelteState.get()
                        if (owner != null) clearDiagnosticReports { report ->
                            report.state === owner && (report.svelteObservation?.generation ?: Long.MIN_VALUE) < observation.generation
                        }
                    }
                }, onEvents = { drain ->
                    try {
                        if (drain.events.isNotEmpty() && !drain.criticalChanged && synchronized(lifetimeLock) { svelteOwnerCurrent() }) {
                            val params = drain.params()
                            svelteDrain.set(params to drain)
                            withTimeout(5_000) { rpc.notify("workspace/didChangeWatchedFiles", params) }
                        }
                    } finally {
                        svelteDrain.set(null)
                        svelteState.get()?.let(::retireServer)
                        startup?.let { stopAnalysisStartup(it.task to it.rpc) }
                    }
                })
            // JsonRpcProcess handles inbound requests serially and awaits each response write.
            // A single exact callback guard therefore covers the queued response without a
            // per-request map, transport changes or filesystem reads on the callback path.
            val powershellCallbackAdmission = java.util.concurrent.atomic.AtomicReference<(() -> Boolean)?>(null)
            val nimCallbackAdmission = java.util.concurrent.atomic.AtomicReference<(() -> Boolean)?>(null)
            val nimRootUri = if (LspServerProfiles.isNim(spec)) mapper.remoteUriFor(projectRoot).trimEnd('/') else ""
            fun nimCallbackOwnerCurrent(): Boolean = !closed && nimOwner?.isCurrent == true &&
                permit != null && analysisConsent.isCurrent(permit) && key.workspaceMutationEpoch == mutationEpoch(spec) &&
                key.nimRefreshEpoch == (nimSaveRefreshEpochs[key.projectPath] ?: 0L) &&
                LanguageServerRegistry.forPath("__nim_profile__.nim", extraLanguageServers()).firstOrNull() == spec &&
                (if (sessionPublished.get()) servers[key]?.rpc === rpc &&
                    clojureWarmups[key]?.let { it.rpc === rpc && it.phase != LspProjectAnalysisReadiness.FAILED } == true
                 else startup?.let { it in analysisStartups && it.rpc === rpc && it.task.isActive } == true)
            fun nimScopePath(uri: String): String? {
                val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
                if (parsed.scheme != "file" || !parsed.rawAuthority.isNullOrEmpty() || parsed.rawQuery != null || parsed.rawFragment != null) return null
                val value = parsed.path ?: return null
                if (value.contains('\\') || value.split('/').any { it == "." || it == ".." }) return null
                val prefix = mapper.remoteRoot.trimEnd('/') + "/"
                return value.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            }
            val prismaDegraded = java.util.concurrent.atomic.AtomicBoolean(false)
            fun recordPowerShellNotice(bit: Int) {
                if (!LspServerProfiles.isPowerShell(spec) || startup == null) return
                synchronized(lifetimeLock) {
                    if (clojureWarmups[key] === startup && startup.rpc === rpc &&
                        ownsPowerShellCallbackOwner(spec, key, rpc) && startup.powershellNoticeBits and bit == 0) {
                        startup.powershellNoticeBits = startup.powershellNoticeBits or bit
                        statusChanged()
                    }
                }
            }
            rpc = JsonRpcProcess(managerScope, projectRoot, command, processHost = rpcHost,
                resourceLimits = if (LspServerProfiles.isSqruff(spec)) ProcessResourceLimits() else null,
                sessionAdmission = admission@ { frame ->
                    if (LspServerProfiles.isNim(spec)) {
                        val response = frame != null && !frame.containsKey("method") && frame.containsKey("id") &&
                            (frame.containsKey("result") || frame.containsKey("error"))
                        if (response) {
                            val callback = nimCallbackAdmission.getAndSet(null)
                            check(callback != null && synchronized(lifetimeLock) { callback() }) { "Nim callback owner changed before response" }
                            return@admission
                        }
                        if (frame != null) check(nimOwner?.isCurrent == true) { "Nim owned runtime was retired" }
                        if (sessionPublished.get()) synchronized(lifetimeLock) {
                            val owner = servers[key]?.takeIf { it.rpc === rpc } ?: error("Nim session owner was retired")
                            val method = (frame?.get("method") as? JsonPrimitive)?.contentOrNull
                            if (method != null && method !in setOf("$/cancelRequest", "shutdown", "exit")) {
                                val ticket = frame["params"]?.let { owner.nimTickets[it] } ?: owner.nimWireTicket
                                check(ticket != null && (ticket.method == method || ticket.method == "document-sync" &&
                                    method in setOf("textDocument/didOpen", "textDocument/didChange")) &&
                                    nimCaptureCurrent(owner, ticket, requireReady = ticket.method != "document-sync")) {
                                    "Nim captured source changed before queued write"
                                }
                            }
                        }
                    }
                    if (LspServerProfiles.isSvelte(spec)) {
                        val response = frame != null && !frame.containsKey("method") && frame.containsKey("id") &&
                            (frame.containsKey("result") || frame.containsKey("error"))
                        if (response) {
                            val callback = svelteCallbackAdmission.getAndSet(null)
                            check(callback != null && synchronized(lifetimeLock) { callback() }) { "Svelte callback owner changed before response" }
                            return@admission
                        }
                        val drain = svelteDrain.get()
                        if ((frame?.get("method") as? JsonPrimitive)?.contentOrNull == "workspace/didChangeWatchedFiles") {
                            check(drain != null && frame?.get("params") === drain.first && synchronized(lifetimeLock) {
                                svelteOwnerCurrent() && svelteWatch?.isDrainCurrent(drain.second) == true
                            }) { "Svelte event drain was superseded" }
                            return@admission
                        }
                        if (sessionPublished.get()) synchronized(lifetimeLock) {
                            val owner = svelteState.get() ?: error("Svelte session owner is unavailable")
                            check(svelteOwnerCurrent() && !owner.svelteDraining && !owner.svelteSuperseded) { "Svelte observation was retired" }
                            val method = (frame?.get("method") as? JsonPrimitive)?.contentOrNull
                            val params = frame?.get("params")
                            if (method != null && method !in setOf("$/cancelRequest", "shutdown", "exit")) {
                                val ticket = params?.let { owner.svelteTickets[it] }
                                val valid = if (ticket != null) ticket.method == method && svelteTicketCurrent(owner, ticket)
                                    else method in setOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didSave") &&
                                        owner.svelteWireCapture?.let { svelteCaptureCurrent(owner, it, owner.svelteObservation) } == true &&
                                        (owner.svelteWireRequest?.let(::documentRequestStillCurrent) != false)
                                if (!valid) owner.svelteSuperseded = true
                                check(valid) { "Svelte captured documents changed before queued write" }
                            }
                        }
                    }
                    if (LspServerProfiles.isPowerShell(spec) && frame != null && !frame.containsKey("method") &&
                        frame.containsKey("id") && (frame.containsKey("result") || frame.containsKey("error"))) {
                        val callback = powershellCallbackAdmission.getAndSet(null)
                        check(callback != null && synchronized(lifetimeLock) { callback() }) {
                            "PowerShell callback authority changed before its response write"
                        }
                        return@admission
                    }
                    if (permit != null) {
                        requireCurrentAnalysis(permit)
                        if (!sessionPublished.get()) requireCurrentStart()
                        else {
                            check(!closed && servers[key]?.rpc === rpc) { "Language-server session was retired" }
                            if (usesBackgroundPreparation(spec)) synchronized(lifetimeLock) {
                                check(clojureWarmups[key]?.let { it.rpc === rpc && it.phase != LspProjectAnalysisReadiness.FAILED } == true) {
                                    "${preparationLabel(spec)} analysis owner was retired"
                                }
                            }
                            check(key.workspaceMutationEpoch == mutationEpoch(spec)) { "Language-server disk index changed" }
                            if (LspServerProfiles.isSqruff(spec)) synchronized(lifetimeLock) {
                                val owner = servers[key]?.takeIf { it.rpc === rpc } ?: error("SQL owner was retired")
                                check(key.sqruffTextEpoch == (sqruffTextEpochs[key.projectPath] ?: 0L)) { "SQL immutable source changed" }
                                val method = (frame?.get("method") as? JsonPrimitive)?.contentOrNull
                                if (owner.sqruffFormatting && method != null) {
                                    val uri = ((frame?.get("params") as? JsonObject)?.get("textDocument") as? JsonObject)
                                        ?.get("uri")?.let { (it as? JsonPrimitive)?.contentOrNull }
                                    check(method == "textDocument/formatting" && !owner.sqruffFormatIssued &&
                                        uri == owner.sqruffFormatPath?.let { uriFor(owner, it) }) { "SQL formatting owner is exclusive" }
                                    owner.sqruffFormatIssued = true
                                }
                            }
                            check(projectAvailable(spec, projectRoot)) { unavailableProjectMessage(spec, File(key.projectPath)) }
                            // A shared parent session is not owned forever by its first
                            // document. Check configuration here and each actual URI below.
                            check(LanguageServerRegistry.forPath(path, extraLanguageServers()).any {
                                it.id == spec.id && analysisConfiguration(it, projectRoot) == key.analysisConfiguration
                            }) { "Language-server configuration changed" }
                            val method = frame?.get("method")?.jsonPrimitive?.contentOrNull
                            if (method != "textDocument/didClose") {
                                val uri = (frame?.get("params") as? JsonObject)?.get("textDocument")
                                    ?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
                                if (uri != null) {
                                    val target = requireNotNull(mapper.uriToLocal(uri)) { "Language-server document escaped the workspace" }
                                    val targetPath = target.relativeTo(root).invariantSeparatorsPath
                                    requireCurrentRoute(spec, key, targetPath, permit)
                                    if (LspServerProfiles.isSqruff(spec)) synchronized(lifetimeLock) {
                                        val owner = servers[key]?.takeIf { it.rpc === rpc } ?: error("SQL owner was retired")
                                        check(sqruffWireStillCurrent(owner, targetPath)) { "SQL immutable document changed before queued write" }
                                    }
                                    if (LspServerProfiles.isPrisma(spec)) synchronized(lifetimeLock) {
                                        val owner = servers[key]?.takeIf { it.rpc === rpc } ?: error("Prisma owner was retired")
                                        check(prismaWireStillCurrent(owner, targetPath)) { "Prisma document changed before queued write" }
                                    }
                                    if (usesBackgroundPreparation(spec)) synchronized(lifetimeLock) {
                                        val owner = servers[key]?.takeIf { it.rpc === rpc }
                                            ?: error("${preparationLabel(spec)} document owner was retired")
                                        owner.clojureDocumentGenerations[targetPath]?.let { generation ->
                                            check(documents[targetPath]?.let { it.text != null && it.generation == generation } == true) {
                                                "${preparationLabel(spec)} document lifetime changed before queued write"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }, inboundRequestHandler = { method, params ->
                if (LspServerProfiles.isNim(spec)) nimCallbackAdmission.set { nimCallbackOwnerCurrent() }
                if (LspServerProfiles.isSvelte(spec)) svelteCallbackAdmission.set { svelteOwnerCurrent() }
                if (LspServerProfiles.isPowerShell(spec)) powershellCallbackAdmission.set {
                    ownsPowerShellCallbackOwner(spec, key, rpc)
                }
                when (method) {
                    "workspace/configuration" -> if (LspServerProfiles.isScala(spec)) {
                        LspScalaProfile.configurationReply(params)
                    } else if (LspServerProfiles.isElixir(spec)) {
                        LspElixirProfile.configurationReply(params)
                    } else if (LspServerProfiles.isHaskell(spec)) {
                        LspHaskellProfile.configurationReply(params)
                    } else if (LspServerProfiles.isNim(spec)) {
                        val capturedScopes = mutableListOf<Pair<String, DocumentSnapshot>>()
                        val response = LspNimProfile.configurationReply(params) { uri -> synchronized(lifetimeLock) {
                            if (!nimCallbackOwnerCurrent()) false
                            else if (uri.trimEnd('/') == nimRootUri) true
                            else {
                                val target = nimScopePath(uri)
                                val snapshot = target?.let(documents::get)
                                val owner = servers[key]?.takeIf { it.rpc === rpc }
                                val accepted = target != null && snapshot?.text != null &&
                                    (target in owner?.documentPaths.orEmpty() || target == startup?.path) &&
                                    LanguageServerRegistry.forPath(target, extraLanguageServers()).firstOrNull() == spec
                                if (accepted) capturedScopes += requireNotNull(target) to requireNotNull(snapshot)
                                accepted
                            }
                        } }
                        nimCallbackAdmission.set { nimCallbackOwnerCurrent() && capturedScopes.all { (p, token) -> documents[p] === token } }
                        response
                    } else if (LspServerProfiles.isPowerShell(spec)) {
                        val approvedScopes = mutableListOf<() -> Boolean>()
                        val response = LspPowerShellProfile.configurationReply(params) { uri -> synchronized(lifetimeLock) {
                            val owns = ownsPowerShellConfigurationScope(spec, key, rpc, startup, uri)
                            if (owns) {
                                val owner = servers[key]?.takeIf { it.rpc === rpc }
                                val captured = if (uri == null) owner?.powershellDocumentRequests?.values.orEmpty()
                                    .filter(::documentRequestStillCurrent).toList()
                                else owner?.powershellDocumentUris?.get(uri)?.let { owner.powershellDocumentRequests[it] }?.let(::listOf).orEmpty()
                                val capturedStartup = startup?.takeIf { it in analysisStartups && it.rpc === rpc &&
                                    it.phase == LspProjectAnalysisReadiness.PREPARING }
                                approvedScopes += {
                                    ownsPowerShellConfigurationScope(spec, key, rpc, startup, uri) &&
                                        (captured.any(::documentRequestStillCurrent) ||
                                            (uri == null && capturedStartup != null && capturedStartup in analysisStartups &&
                                                capturedStartup.phase == LspProjectAnalysisReadiness.PREPARING))
                                }
                            }
                            owns
                        } }
                        powershellCallbackAdmission.set {
                            ownsPowerShellCallbackOwner(spec, key, rpc) && approvedScopes.all { it() }
                        }
                        response
                    } else if (LspServerProfiles.isLemMinx(spec)) {
                        LspLemMinxProfile.configurationReply(params) { uri ->
                            synchronized(lifetimeLock) {
                                val owner = servers[key]?.takeIf { it.rpc === rpc }
                                owner != null && ownsLemMinxConfigurationScope(owner, uri)
                            }
                        }
                    } else {
                        val count = ((params as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
                        buildJsonArray { repeat(count) { add(JsonNull) } }
                    }
                    "powerShell/showInputPrompt", "powerShell/showChoicePrompt" ->
                        if (LspServerProfiles.isPowerShell(spec)) {
                            recordPowerShellNotice(1)
                            LspPowerShellProfile.cancelPrompt(method, params)
                        } else JsonNull
                    "workspace/workspaceFolders" -> powershellWorkspaceFolders ?: buildJsonArray {
                        if (key.publishesWorkspaceRoot) add(workspaceFolder(mapper, projectRoot, spec))
                    }
                    "client/registerCapability", "client/unregisterCapability" -> if (LspServerProfiles.isSvelte(spec)) {
                        val authority = requireNotNull(svelteWatch)
                        val result = if (method == "client/registerCapability") authority.register(params) else authority.unregister(params)
                        svelteCallbackAdmission.set {
                            val current = svelteOwnerCurrent() && authority.registrationCurrent(result)
                            if (current && result is LspSvelteWatchAuthority.RegistrationResult.Accepted && result.observation != null)
                                svelteRegistrationWritten.complete(Unit) // inside the serialized writer, before any replay frame
                            current
                        }
                        when (result) {
                            is LspSvelteWatchAuthority.RegistrationResult.Accepted -> JsonNull
                            is LspSvelteWatchAuthority.RegistrationResult.Rejected -> error(result.reason)
                        }
                    } else error("Dynamic registration was not advertised by Droide")
                    "window/workDoneProgress/create" -> if (usesBackgroundPreparation(spec)) error("Work-done progress was not advertised") else JsonNull
                    "workspace/applyEdit" -> buildJsonObject { put("applied", false); put("failureReason", "Server-driven workspace edits require explicit IDE review") }
                    "xml/executeClientCommand", "workspace/codeLens/refresh" ->
                        if (LspServerProfiles.isLemMinx(spec) || usesBackgroundPreparation(spec)) error("This client operation is not supported") else JsonNull
                    "window/showMessageRequest" -> {
                        recordPowerShellNotice(1)
                        if (LspServerProfiles.isPrisma(spec) && ((params as? JsonObject)?.get("type") as? JsonPrimitive)?.intOrNull == 1) {
                            synchronized(lifetimeLock) {
                                val owner = servers[key]?.takeIf { it.rpc === rpc }
                                if (owner != null || startup?.let { it in analysisStartups && it.rpc === rpc } == true) {
                                    prismaDegraded.set(true)
                                    owner?.prismaDegraded = true
                                    statusChanged()
                                }
                            }
                        }
                        if (startup?.background == true && LspServerProfiles.isClojure(spec) && clojureClasspathFailure(params))
                            failClojure(startup, "Classpath lookup failed; check project tools/configuration, then Retry.")
                        JsonNull
                    }
                    "window/showDocument" -> if (LspServerProfiles.isTexlab(spec) || LspServerProfiles.isLemMinx(spec) || usesBackgroundPreparation(spec))
                        buildJsonObject { put("success", false) } else JsonNull
                    else -> if (usesBackgroundPreparation(spec)) error("Unsupported background language-server client request") else JsonNull
                }
            })
            var failureStage = LspStartupStage.PROCESS_START
            var state: ServerState? = null
            val clojureState = java.util.concurrent.atomic.AtomicReference<ServerState?>(null)
            val earlyNotifications = if (startup?.background == true) managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    rpc.notifications.collect { event ->
                        val owner = clojureState.get()
                        if (event.method == "textDocument/publishDiagnostics") {
                            if (owner != null && clojureReady(owner)) consumeDiagnostics(event.params, owner)
                        } else if (event.method == "window/showMessage" || event.method == "window/logMessage") {
                            if (LspServerProfiles.isPowerShell(spec) &&
                                ((event.params as? JsonObject)?.get("type") as? JsonPrimitive)?.intOrNull in 1..2)
                                recordPowerShellNotice(2)
                            if (LspServerProfiles.isClojure(spec) && clojureClasspathFailure(event.params))
                                failClojure(startup, "Classpath lookup failed; check project tools/configuration, then Retry.")
                            else synchronized(lifetimeLock) {
                                if (clojureWarmups[key] === startup && startup.phase == LspProjectAnalysisReadiness.PREPARING &&
                                    ((event.params as? JsonObject)?.get("type") as? JsonPrimitive)?.intOrNull in 1..2) {
                                    startup.detail = "Server reported a warning during preparation; project tools and dependencies remain unverified."
                                    statusChanged()
                                }
                            }
                        }
                    }
                    failClojure(startup, "${preparationLabel(spec)} language-server transport ended; check project tools, then Retry.")
                } catch (cancel: CancellationException) { throw cancel }
            } else null
            try {
                synchronized(lifetimeLock) {
                    requireCurrentStart()
                    if (startup != null) {
                        check(startup in analysisStartups && startup.task.isActive) { "Language-server startup was retired" }
                        startup.rpc = rpc
                    }
                }
                rpc.start()
                requireCurrentStart()
                if (sqruffIdentity != null) {
                    check(remote.reviewedSqruffLaunchIdentity(executable, root.path) == sqruffIdentity) { "SQL managed identity changed before initialize" }
                    requireCurrentStart()
                }
                if (powershellIdentity != null) {
                    check(remote.reviewedPowerShellLaunchIdentity(executable, root.path) == powershellIdentity) { "PowerShell managed identity changed before initialize" }
                    requireCurrentStart()
                }
                val init = buildJsonObject {
                    put("processId", JsonNull)
                    put("clientInfo", buildJsonObject { put("name", "Droide"); put("version", "1.00") })
                    put("rootUri", if (key.publishesWorkspaceRoot) JsonPrimitive(rootUri(mapper, projectRoot, spec)) else JsonNull)
                    put("workspaceFolders", buildJsonArray { if (key.publishesWorkspaceRoot) add(workspaceFolder(mapper, projectRoot, spec)) })
                    put("capabilities", LspServerProfiles.clientCapabilities(spec))
                    put("initializationOptions", if (LspServerProfiles.isPowerShell(spec))
                        LspPowerShellProfile.initializationOptions(mapper.localToRemote(projectRoot))
                        else if (LspMdxProfile.matches(spec)) LspMdxProfile.initializationOptions(executable)
                        else key.initializationOptions)
                    put("trace", "off")
                }
                failureStage = LspStartupStage.INITIALIZE
                val initResult = rpc.request("initialize", init, timeoutMs = if (startup?.background == true) requireNotNull(LspBackgroundPreparationPolicy.forSpec(spec)).initializeMs else 12_000)
                require(initResult is JsonObject && initResult["capabilities"] is JsonObject) { "Invalid language-server initialize result" }
                if (LspServerProfiles.isNim(spec)) LspNimProfile.requireInitializeContract(initResult)
                if (LspServerProfiles.isSqruff(spec)) LspSqruffProfile.requireInitializeContract(initResult)
                if (LspServerProfiles.isPowerShell(spec)) LspPowerShellProfile.requireInitializeContract(initResult)
                if (LspServerProfiles.isSvelte(spec)) LspSvelteProfile.requireInitializeContract(initResult)
                val nativeCapabilities = LspNegotiatedCapabilities.fromInitialize(initResult)
                val negotiated = when {
                    LspServerProfiles.isAsm(spec) -> LspAsmProfile.supportedCapabilities(nativeCapabilities)
                    LspServerProfiles.isNim(spec) -> LspNimProfile.supportedCapabilities(nativeCapabilities)
                    LspServerProfiles.isPowerShell(spec) -> nativeCapabilities.copy(
                        sync = nativeCapabilities.sync.copy(change = LspTextSyncPolicy.ChangeKind.FULL),
                        codeActionResolve = false, inlayHint = false, pullDiagnostics = false)
                    LspServerProfiles.isSqruff(spec) -> LspSqruffProfile.supportedCapabilities(nativeCapabilities, sqruffIdentity != null)
                    LspServerProfiles.isMake(spec) -> LspMakeProfile.supportedCapabilities(nativeCapabilities)
                    LspServerProfiles.isSvelte(spec) -> LspSvelteProfile.supportedCapabilities(nativeCapabilities)
                    LspServerProfiles.isUnverifiedSqruff(spec) -> nativeCapabilities.copy(formatting = false)
                    else -> nativeCapabilities
                }
                failureStage = LspStartupStage.DOCUMENT_REPLAY
                val initializedState = ServerState(key, projectRoot, spec, rpc, command, processHost, mapper, negotiated, permit, sqruffIdentity, powershellIdentity, svelteIdentity, nimProcess, nimOwner)
                state = initializedState
                initializedState.svelteWatch = svelteWatch
                if (LspServerProfiles.isNim(spec)) initializedState.nimRequests = LspNimRequests(managerScope, onDeadline = {
                    val owner = synchronized(lifetimeLock) { clojureWarmups[key]?.takeIf { it.state === initializedState } }
                    if (owner != null) failClojure(owner, "Nim native request timed out; the owned session was stopped. Choose Retry.")
                })
                svelteState.set(initializedState)
                if (LspServerProfiles.isPowerShell(spec)) initializedState.powershellRequests = LspPowerShellRequests(managerScope, onDeadline = {
                    val owner = synchronized(lifetimeLock) { clojureWarmups[key]?.takeIf { it.state === initializedState } }
                    if (owner != null) failClojure(owner, "PowerShell native request timed out; the owned session was stopped. Choose Retry to run profiles again.")
                })
                initializedState.prismaDegraded = prismaDegraded.get()
                val published = CompletableDeferred<Unit>()
                clojureState.set(initializedState)
                if (startup?.background == true) startup.state = initializedState
                initializedState.notificationJob = earlyNotifications ?: managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    rpc.notifications.collect { event ->
                        // Subscribe before initialized, but expose neither events nor the
                        // server to callers until that notification is fully sent.
                        published.await()
                        if (event.method == "textDocument/publishDiagnostics") consumeDiagnostics(event.params, initializedState)
                    }
                }
                requireCurrentStart()
                rpc.notify("initialized", buildJsonObject {})
                settings?.let {
                    requireCurrentStart()
                    rpc.notify("workspace/didChangeConfiguration", it)
                }
                if (nimIdentity != null) {
                    initializedState.nimObservation = requireNotNull(nimProcess).awaitMetadataAdmission()
                    requireCurrentStart()
                    check(nimProcess?.isAdmissionCurrent(requireNotNull(initializedState.nimObservation)) == true) { "Nim metadata authority changed before publication" }
                }
                if (svelteIdentity != null) {
                    val observation = requireNotNull(svelteWatch).awaitRegisteredObservation()
                    withTimeout(20_000) { svelteRegistrationWritten.await() }
                    requireCurrentStart()
                    check(observation.criticalDigest == svelteConfigDigests[projectRoot.path]) { "Svelte configuration changed before replay" }
                    initializedState.svelteObservation = observation
                    check(remote.reviewedSvelteLaunchIdentity(executable, root.path) == svelteIdentity) { "Svelte managed identity changed before publication" }
                    requireCurrentStart()
                }
                if (sqruffIdentity != null) {
                    check(remote.reviewedSqruffLaunchIdentity(executable, root.path) == sqruffIdentity) { "SQL managed identity changed before publication" }
                    requireCurrentStart()
                }
                if (powershellIdentity != null) {
                    check(remote.reviewedPowerShellLaunchIdentity(executable, root.path) == powershellIdentity) { "PowerShell managed identity changed before publication" }
                    requireCurrentStart()
                }
                synchronized(lifetimeLock) {
                    check(!closed && managerJob.isActive && (startup == null || startup.task.isActive)) { "Language-server manager was closed during initialization" }
                    requireCurrentAnalysis(permit)
                    requireCurrentStart()
                    initializedState.prismaDegraded = prismaDegraded.get()
                    startupFailures.remove(key)
                    servers[key] = initializedState
                    sessionPublished.set(true)
                }
                published.complete(Unit)
                statusChanged()
                return initializedState
            } catch (cancel: CancellationException) {
                earlyNotifications?.cancel()
                svelteWatch?.close()
                state?.let { servers.remove(key, it); it.close() } ?: try { rpc.close() } finally { nimOwner?.close() }
                if (cancel !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw cancel
                lastError = LspStartupFailure(spec.id, failureStage, timedOut = true, cause = cancel)
            } catch (t: Throwable) {
                lastError = LspStartupFailure(spec.id, failureStage, timedOut = false, cause = t)
                svelteWatch?.close()
                earlyNotifications?.cancel()
                state?.let { servers.remove(key, it); it.close() } ?: try { rpc.close() } finally { nimOwner?.close() }
            }
        }
        

        // Failed observation must not look like a missing server or enter the missing-tool backoff.
        discoveryFailure?.let { throw it }
        // Failure publication and backoff share the exact lifetime/consent/admission fence.
        synchronized(lifetimeLock) {
            if (!closed && managerJob.isActive && runCatching { requireCurrentStart() }.isSuccess) {
                if (lastError != null || capturedEnvironmentEpoch == environmentEpoch)
                    unavailableUntil[key] = System.currentTimeMillis() + 30_000L
                if (lastError != null) {
                    startupFailures[key] = lastError
                    statusChanged()
                }
            }
        }
        return null
    }

    /** A marker change transfers only the affected document, never another project's session. */
    private suspend fun retireChangedProjectRoots(path: String) = documentMutex.withLock {
        for (state in servers.values.toList()) {
            if (LspServerProfiles.isVue(state.spec) && LspVueProfile.relevantPath(path) && !LspVueProfile.supportsPath(path)) continue
            if (path !in state.documentPaths || handlesDocument(state, path)) continue
            if (!usesScopedRouting(state)) {
                // Generic sessions span this workspace. A changed/removed route no
                // longer owns this document; never reuse its process or diagnostics.
                retireServer(state)
                continue
            }
            closeServerDocument(state, path)
            retireEmptyProjectServer(state)
        }
    }

    // Caller owns documentMutex. Keep other documents in a shared old session alive
    // until their own routing is reconciled; never remove a replacement instance.
    private fun retireEmptyProjectServer(state: ServerState) {
        if (state.documentPaths.isEmpty()) retireServer(state)
    }

    private fun retireServer(state: ServerState) {
        val removed = synchronized(lifetimeLock) {
            if (!servers.remove(state.key, state)) false else {
                val obsolete = diagnosticReports.filterValues { it.state === state }.keys
                obsolete.forEach { diagnosticReports.remove(it) }
                _diagnostics.update { it - obsolete }
                state.documentPaths.clear()
                state.clojureDocumentGenerations.clear()
                state.powershellDocumentUris.clear()
                state.powershellDocumentRequests.clear()
                state.prismaWireDocuments.clear()
                state.sqruffWireDocuments.clear()
                prismaAdmissions.entries.removeAll { it.value.owner === state }
                if (LspServerProfiles.isPrisma(state.spec)) statusChanged()
                clojureWarmups[state.key]?.takeIf { it.state === state && it.phase != LspProjectAnalysisReadiness.FAILED }?.let {
                    clojureWarmups.remove(state.key, it); statusChanged()
                }
                true
            }
        }
        if (removed) state.close()
    }

    private fun canonicalPath(path: String): String =
        files.resolveChecked(path).relativeTo(root).invariantSeparatorsPath

    private fun builtinPrisma(path: String): LanguageServerSpec? =
        LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull()?.takeIf(LspServerProfiles::isPrisma)

    private fun prismaTextStatus(text: String): LspDocumentSupportStatus =
        if (LspPrismaProfile.classify(text) == LspPrismaProfile.Generation.LEGACY)
            LspDocumentSupportStatus(LspDocumentSupportPhase.LEGACY_ELIGIBLE,
                "Prisma legacy schema profile; project configuration and native runtime remain unverified.")
        else LspDocumentSupportStatus(LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8,
            "Prisma Next / ORM 8 requires a project-local adapter that is not configured. This legacy server will not analyze this document.")

    override fun documentSupportStatus(path: String, currentText: String?): LspDocumentSupportStatus? {
        val key = canonicalPath(path)
        if (builtinPrisma(key) == null) return null
        return synchronized(lifetimeLock) {
            if (closed) return@synchronized LspDocumentSupportStatus(LspDocumentSupportPhase.CLOSED, "Language-server workspace is closed.")
            val snapshot = documents[key]
            if (snapshot?.text != null) {
                val authoritative = prismaTextStatus(snapshot.text)
                if (!authoritative.permitsAnalysis) authoritative
                else if (currentText != null && currentText != snapshot.text)
                    LspDocumentSupportStatus(LspDocumentSupportPhase.INSPECTION_PENDING, "Synchronize the current Prisma editor snapshot before analysis.")
                else authoritative
            } else {
                val admitted = prismaAdmissions[key]?.takeIf { documentRequestStillCurrent(it.request) &&
                    it.key.workspaceMutationEpoch == diskIndexMutationEpoch }
                when {
                    admitted != null -> prismaTextStatus(admitted.request.text)
                    snapshot != null -> LspDocumentSupportStatus(LspDocumentSupportPhase.CLOSED, "Prisma document is closed; request it explicitly before analysis.")
                    currentText != null -> prismaTextStatus(currentText)
                    else -> LspDocumentSupportStatus(LspDocumentSupportPhase.INSPECTION_PENDING, "Prisma schema generation will be checked before analysis or enablement.")
                }
            }
        }
    }

    private fun prismaScopeToken(path: String): Any? =
        documents[path]?.takeIf { it.text != null } ?: prismaAdmissions[path]?.takeIf { documentRequestStillCurrent(it.request) }

    private fun prismaDocumentEligible(path: String, key: ServerKey): Boolean {
        val snapshot = documents[path]
        if (snapshot?.text != null) return prismaTextStatus(snapshot.text).permitsAnalysis
        val admission = prismaAdmissions[path] ?: return false
        return admission.key == key && documentRequestStillCurrent(admission.request) &&
            prismaTextStatus(admission.request.text).permitsAnalysis
    }

    private fun requirePrismaSupport(path: String) {
        documentSupportStatus(path)?.takeUnless { it.permitsAnalysis }?.let { throw LspDocumentSupportUnavailableException(it) }
    }

    private fun prismaWireStillCurrent(state: ServerState, path: String): Boolean {
        val token = state.prismaWireDocuments[path] ?: return false
        if (documents[path] !== token.snapshot) return false
        if (token.admission != null) {
            val admitted = prismaAdmissions[path] ?: return false
            if (admitted.owner !== state || admitted.request.admission !== token.admission ||
                !documentRequestStillCurrent(admitted.request) || admitted.request.text != token.text) return false
        }
        return token.snapshot?.text?.let { it == token.text } ?: (token.admission != null)
    }

    private suspend fun admitDocument(path: String, currentText: String? = null): DocumentRequest {
        val key = canonicalPath(path)
        if (builtinVue(key) != null) require(documents[key]?.text != null) { "Open this Vue document before requesting analysis" }
        val (snapshot, admission) = synchronized(lifetimeLock) {
            check(!closed && managerJob.isActive) { "Language-server manager has been closed" }
            val snapshot = documents[key]
            // Without an editor-owned revision, a later file-backed request supersedes
            // an older one. Reserve before disk I/O/discovery can suspend either caller.
            val admission = if (snapshot?.text == null) Any().also {
                unmanagedAdmissions[key] = it; prismaAdmissions.remove(key)
                clearSvelteDiagnosticsForRevision(key)
            } else null
            snapshot to admission
        }
        // This immutable baseline belongs to the request, never to the editor ledger.
        val text = snapshot?.text ?: currentText ?: files.readTextForEdit(key, maxBytes = 2_000_000)
        val request = DocumentRequest(key, snapshot, text, currentText, admission)
        if (LanguageServerRegistry.forPath(key, extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isPowerShell) == true) {
            require(LspPowerShellCompletionContext.acceptsText(text)) { "PowerShell analysis requires bounded valid UTF-16 text with LF or CRLF, no NUL or lone CR" }
            require(currentText == null || currentText == text) { "PowerShell editor snapshot changed; synchronize before analysis" }
        }
        builtinSqruff(key)?.let {
            val cleanup = synchronized(lifetimeLock) {
                check(documentRequestStillCurrent(request)) { "SQL document changed during admission" }
                val changed = servers.values.any { state -> LspServerProfiles.isSqruff(state.spec) &&
                    state.sqruffWireDocuments[key]?.view?.originalText?.let { it != text } == true } ||
                    sqruffAdmissions[key]?.text?.let { it != text } == true
                val cleanup = if (changed) invalidateSqruffText(key) else null
                if (admission != null) sqruffAdmissions[key] = request
                cleanup
            }
            cleanSqruff(cleanup)
            requireSqruffText(text)
            require(currentText == null || currentText == text) { "SQL editor snapshot changed; synchronize before analysis" }
        }
        val unavailable = builtinPrisma(key)?.let { spec ->
            synchronized(lifetimeLock) {
                check(documentRequestStillCurrent(request)) { "Prisma document changed during admission; retry with current text" }
                val support = prismaTextStatus(text)
                // Unsupported authoritative text wins over a stale supplied buffer.
                if (support.permitsAnalysis) require(currentText == null || currentText == text) {
                    "Prisma editor snapshot changed; synchronize before analysis"
                }
                if (admission != null) {
                    prismaAdmissions[key] = PrismaAdmission(request, serverKey(spec, key))
                    statusChanged()
                }
                support.takeUnless { it.permitsAnalysis }
            }
        }
        if (unavailable != null) {
            val obsolete = synchronized(lifetimeLock) {
                val uri = files.resolveChecked(key).toURI().toString()
                diagnosticReports.remove(uri); _diagnostics.update { it - uri }
                analysisStartups.filter { it.path == key && LspServerProfiles.isPrisma(it.spec) }.map { it.task to it.rpc }
            }
            obsolete.forEach(::stopAnalysisStartup)
            // Eligibility is already published. Reconcile the recorded old owner,
            // including an unmanaged URI that never entered the editor ledger.
            retireChangedProjectRoots(key)
            throw LspDocumentSupportUnavailableException(unavailable)
        }
        if (LanguageServerRegistry.forPath(key, extraLanguageServers()).firstOrNull()?.let(LspServerProfiles::isSvelte) == true)
            synchronized(lifetimeLock) { svelteAdmissions[key] = request }
        return request
    }


    /** Pure captured-owner check: lexical ledger, registry declaration and completed observation only. */
    private fun svelteCaptureCurrent(state: ServerState, snapshots: Map<String, DocumentSnapshot>,
                                     observation: LspSvelteWatchAuthority.Observation?): Boolean =
        !closed && servers[state.key] === state && state.rpc.isRunning && !state.svelteDraining && !state.svelteSuperseded &&
            state.analysisPermit?.let(analysisConsent::isCurrent) == true &&
            state.key.workspaceMutationEpoch == mutationEpoch(state.spec) &&
            observation != null && state.svelteWatch?.isCurrent(observation) == true &&
            relevantDocuments(state) == snapshots &&
            (state.documentPaths.toList() + listOfNotNull(clojureWarmups[state.key]?.path)).any { path ->
                LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull() == state.spec
            }

    private fun svelteTicketCurrent(state: ServerState, ticket: SvelteTicket): Boolean =
        svelteCaptureCurrent(state, ticket.snapshots, ticket.observation) &&
            (ticket.request?.let(::documentRequestStillCurrent) != false)

    private suspend fun svelteRequest(state: ServerState, ticket: SvelteTicket, params: JsonElement,
                                      timeoutMs: Long, cancellationNotificationMethod: String? = null): JsonElement {
        synchronized(lifetimeLock) {
            check(svelteTicketCurrent(state, ticket)) { "Svelte request capture is stale" }
            check(state.svelteTickets.size < 64) { "Too many Svelte requests are pending" }
            state.svelteTickets[params] = ticket
        }
        try {
            val result = state.rpc.request(ticket.method, params, timeoutMs, cancellationNotificationMethod)
            synchronized(lifetimeLock) { check(svelteTicketCurrent(state, ticket)) { "Svelte result capture is stale" } }
            return result
        } finally { synchronized(lifetimeLock) { state.svelteTickets.remove(params) } }
    }

    private suspend fun svelteNotify(state: ServerState, ticket: SvelteTicket, params: JsonElement) {
        synchronized(lifetimeLock) {
            check(svelteTicketCurrent(state, ticket)) { "Svelte companion capture is stale" }
            check(state.svelteTickets.size < 64) { "Too many Svelte frames are pending" }
            state.svelteTickets[params] = ticket
        }
        try {
            withTimeout(5_000) { state.rpc.notify(ticket.method, params) }
            synchronized(lifetimeLock) { check(svelteTicketCurrent(state, ticket)) { "Svelte companion changed during write" } }
        } finally { synchronized(lifetimeLock) { state.svelteTickets.remove(params) } }
    }

    private fun svelteTextDigest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Caller owns documentMutex. Custom scripts never enter the ordinary LSP open/version ledger. */
    private suspend fun replaySvelteCompanions(state: ServerState, snapshots: Map<String, DocumentSnapshot>) {
        val observation = requireNotNull(state.svelteWatch).observation()
        synchronized(lifetimeLock) {
            state.svelteObservation = observation
            state.svelteWireCapture = snapshots
            state.svelteWireRequest = null
            check(svelteCaptureCurrent(state, snapshots, observation)) { "Svelte replay observation is stale" }
        }
        for ((path, snapshot) in snapshots) {
            if (!LspSvelteProfile.isScriptCandidate(path)) continue
            if (snapshot.text == null) {
                check(!state.svelteCompanions.owns(path)) { "A Svelte companion closed; restart its exact owner" }
                continue
            }
            val file = files.resolveChecked(path)
            val inside = PathSecurity.contains(state.projectRoot, file)
            val relative = if (inside) file.relativeTo(state.projectRoot).invariantSeparatorsPath else null
            val entry = relative?.let { observation.snapshot.entries[it] }
            val sameRoot = LspSvelteProfile.projectRoot(root, file) == state.projectRoot
            val supported = inside && sameRoot && LspSvelteProfile.supportedCompanion(file.path) &&
                entry?.type == LspSvelteFilePoller.Type.FILE
            if (!supported) {
                // An unsupported managed dirty source may influence native disk reads. Never
                // silently ignore it or manufacture a custom update for an unsupported suffix.
                val diskMatches = if (entry?.type == LspSvelteFilePoller.Type.FILE)
                    entry.digest == svelteTextDigest(snapshot.text)
                else if (!inside) runSuspendCatching {
                    java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                        files.readTextForEdit(path, maxBytes = 4 * 1024 * 1024) == snapshot.text
                }.getOrDefault(false) else false
                require(diskMatches) { "Svelte analysis is unavailable while an unsupported or cross-root script has unsaved text: $path" }
                continue
            }
            val capture = LspSvelteCompanionAuthority.Capture(path, uriFor(state, path), snapshot.text, snapshot)
            if (state.svelteCompanions.needsPrimer(capture)) {
                val result = svelteRequest(state, SvelteTicket("$/getFileReferences", snapshots, observation),
                    JsonPrimitive(capture.uri), 10_000, "$/cancelRequest")
                state.svelteCompanions.acceptPrimer(capture, result)
            }
            if (state.svelteCompanions.needsWrite(capture)) {
                svelteNotify(state, SvelteTicket("$/onDidChangeTsOrJsFile", snapshots, observation),
                    LspSvelteCompanionAuthority.change(capture))
                state.svelteCompanions.recordWritten(capture)
            }
        }
        synchronized(lifetimeLock) { check(svelteCaptureCurrent(state, snapshots, observation)) { "Svelte replay was superseded" } }
    }

    /** A real captured pull is the readiness boundary, not an invented companion acknowledgement. */
    private suspend fun svelteInitialPull(state: ServerState, path: String, snapshots: Map<String, DocumentSnapshot>) {
        val request = svelteAdmissions[path]?.takeIf(::documentRequestStillCurrent)
        val snapshot = documents[path]?.takeIf { it.text != null }
        val text = snapshot?.text ?: request?.text ?: error("Svelte preparation requires a current document capture")
        synchronized(lifetimeLock) { state.svelteWireRequest = request }
        val uri = synchronizeServerDocument(state, path, text)
        val observation = requireNotNull(state.svelteObservation)
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            state.capabilities.diagnosticIdentifier?.let { put("identifier", it) }
        }
        val result = svelteRequest(state, SvelteTicket("textDocument/diagnostic", snapshots, observation, request), params, 10_000, "$/cancelRequest")
        require(LspDiagnosticsProtocol.parsePull(result) is LspDiagnosticsProtocol.PullReport.Full) {
            "Svelte initial captured diagnostic pull did not produce a full report"
        }
    }

    private fun relevantDocuments(state: ServerState): Map<String, DocumentSnapshot> =
        if (LspServerProfiles.isVue(state.spec)) vueLedger.filterValues { it.text != null }
        else if (LspServerProfiles.isSvelte(state.spec)) documents.filterKeys { path ->
            // Conservative workspace-wide capture stays pure on popup/result acceptance.
            // Replay still routes only exact primary-root documents; scripts use the separate ledger.
            LspSvelteProfile.supportsPath(path) || LspSvelteProfile.isScriptCandidate(path)
        } else if (LspServerProfiles.isCMake(state.spec)) documents.filterKeys { path ->
            LspCMakeProfile.supportsPath(path) && cmakeSelection(path) === state.cmakeSelection
        } else documents.filterKeys { handlesDocument(state, it) }

    private fun usesScopedRouting(state: ServerState): Boolean =
        LspServerProfiles.hasProjectRoots(state.spec) || state.analysisPermit != null || state.key.marksmanConfiguration != null

    private fun handlesDocument(state: ServerState, path: String): Boolean =
        (state.analysisPermit == null || analysisConsent.isCurrent(state.analysisPermit)) && currentRoute(state.spec, state.key, path)

    private fun matchingServers(path: String): List<ServerState> =
        if (closed) emptyList() else servers.values.filter { it.rpc.isRunning && clojureReady(it) && handlesDocument(it, path) }

    private fun nimLedger(state: ServerState): Map<String, DocumentSnapshot> {
        val prefix = state.projectRoot.path.trimEnd('/') + "/"
        return documents.filter { (path, _) -> LspNimProfile.supportsPath(path) && File(root, path).path.startsWith(prefix) }
    }

    private fun nimCaptureCurrent(state: ServerState, ticket: NimTicket, requireReady: Boolean): Boolean =
        !closed && servers[state.key] === state && state.rpc.isRunning && (!requireReady || clojureReady(state)) &&
            state.nimOwner?.isCurrent == true && state.nimProcess?.isAdmissionCurrent(ticket.observation) == true &&
            state.analysisPermit?.let(analysisConsent::isCurrent) == true &&
            state.key.workspaceMutationEpoch == mutationEpoch(state.spec) &&
            state.key.nimRefreshEpoch == (nimSaveRefreshEpochs[state.key.projectPath] ?: 0L) &&
            LanguageServerRegistry.forPath("__nim_profile__.nim", extraLanguageServers()).firstOrNull() == state.spec &&
            nimLedger(state) == ticket.ledger && (ticket.request?.let(::documentRequestStillCurrent) != false)

    private fun snapshotsStillCurrent(state: ServerState, snapshots: Map<String, DocumentSnapshot>): Boolean =
        !closed && servers[state.key] === state && clojureReady(state) && sqruffOwnerOpen(state) &&
            (!LspServerProfiles.isNim(state.spec) || state.nimObservation?.let { state.nimProcess?.isAdmissionCurrent(it) } == true) &&
            (!LspServerProfiles.isSvelte(state.spec) || (!state.svelteDraining && !state.svelteSuperseded &&
                state.svelteObservation?.let { state.svelteWatch?.isCurrent(it) } == true)) &&
            (state.analysisPermit == null || analysisConsent.isCurrent(state.analysisPermit)) && relevantDocuments(state) == snapshots

    private fun documentRequestStillCurrent(request: DocumentRequest): Boolean =
        documents[request.path] === request.snapshot &&
            (request.admission == null || unmanagedAdmissions[request.path] === request.admission)

    private fun requestStillCurrent(state: ServerState, prepared: PreparedDocument, allowSqruffFormatting: Boolean = false): Boolean =
        if (LspServerProfiles.isVue(state.spec)) prepared.vueTicket?.let { vueCaptureCurrent(state, it, true) } == true
        else handlesDocument(state, prepared.request.path) && documentRequestStillCurrent(prepared.request) &&
            (if (allowSqruffFormatting && LspServerProfiles.isSqruff(state.spec))
                !closed && servers[state.key] === state && state.sqruffFormatting && relevantDocuments(state) == prepared.snapshots
             else snapshotsStillCurrent(state, prepared.snapshots)) &&
            (!LspServerProfiles.isNim(state.spec) || (prepared.nimObservation != null && prepared.nimLedger != null &&
                nimCaptureCurrent(state, NimTicket("capture", prepared.nimLedger, prepared.nimObservation, prepared.request), requireReady = true))) &&
            (!LspServerProfiles.isPrisma(state.spec) || prismaWireStillCurrent(state, prepared.request.path)) &&
            (!LspServerProfiles.isSqruff(state.spec) || sqruffWireStillCurrent(state, prepared.request.path)) &&
            (!LspServerProfiles.isSvelte(state.spec) || prepared.svelteObservation?.let { state.svelteWatch?.isCurrent(it) } == true)

    // Caller owns documentMutex. If edits arrive during a suspendable wire write,
    // repeat until every relevant buffer/close has reached this session's ledger.
    private suspend fun replayDocuments(state: ServerState): Map<String, DocumentSnapshot> {
        if (LspServerProfiles.isVue(state.spec)) return synchronizeVue(state).snapshots
        while (true) {
            check(!closed && managerJob.isActive) { "Language-server manager has been closed" }
            val snapshots = relevantDocuments(state)
            if (LspServerProfiles.isSvelte(state.spec)) replaySvelteCompanions(state, snapshots)
            for ((path, snapshot) in snapshots) {
                if (LspServerProfiles.isSvelte(state.spec) && !handlesDocument(state, path)) continue
                if (snapshot.text != null) synchronizeServerDocument(state, path, snapshot.text)
                else closeServerDocument(state, path)
            }
            if (relevantDocuments(state) == snapshots) return snapshots
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun prepareDocument(state: ServerState, request: DocumentRequest): PreparedDocument? { return try {
        prepareDocumentLocked(state, request)
    } catch (failure: Throwable) {
        if (LspServerProfiles.isVue(state.spec)) {
            val operation = currentCoroutineContext()[VueOperation]?.admission
            if (failure is LspVueRequests.NotAdmitted) return null
            if (operation == null || operation.hasDispatched) failVue(state, "Vue preparation failed after dispatch; Retry after owned cleanup.")
        } else if (LspServerProfiles.isSqruff(state.spec) || LspServerProfiles.isSvelte(state.spec) || LspServerProfiles.isCMake(state.spec)) retireServer(state)
        throw failure
    }
    }

    private suspend fun prepareDocumentLocked(state: ServerState, request: DocumentRequest): PreparedDocument? = documentMutex.withLock {
        if (servers[state.key] !== state || !sqruffOwnerOpen(state) || !handlesDocument(state, request.path)) return@withLock null
        if (LspServerProfiles.isVue(state.spec)) {
            check(LspVueProfile.documentAvailable(root, request.path)) { "Vue document parent is unavailable" }
            val ticket = synchronizeVue(state, request)
            check(request.snapshot?.text == request.text && request.suppliedText?.let { it == request.text } != false) { "Vue requires its exact current open editor snapshot" }
            val texts = ticket.snapshots.mapValues { requireNotNull(it.value.text) }
            val receipt = requireNotNull(state.vueReceipt)
            val versions = texts.keys.associateWith { receipt.versions[uriFor(state, it)] }
            return@withLock PreparedDocument(request, uriFor(state, request.path), request.text, ticket.snapshots, texts, versions, vueTicket = ticket)
        }
        val cmakeObservation = if (LspServerProfiles.isCMake(state.spec)) checkedCMakeAdmission(state) else null
        val snapshots = replayDocuments(state)
        if (!handlesDocument(state, request.path) || !documentRequestStillCurrent(request)) return@withLock null
        require(request.suppliedText == null || request.suppliedText == request.text) {
            "Editor snapshot changed for ${request.path}; synchronize the current buffer and request code intelligence again."
        }
        if (LspServerProfiles.isSvelte(state.spec)) synchronized(lifetimeLock) { state.svelteWireRequest = request }
        val uri = synchronizeServerDocument(state, request.path, request.text)
        if (!documentRequestStillCurrent(request) || relevantDocuments(state) != snapshots) return@withLock null
        val requestText = snapshots.mapNotNull { (path, snapshot) -> snapshot.text?.let { path to it } }.toMap() +
            (request.path to request.text)
        val requestVersions = requestText.keys.associateWith { state.documentSync.version(uriFor(state, it)) }
        PreparedDocument(request, uri, request.text, snapshots, requestText, requestVersions, state.svelteObservation, cmakeObservation,
            state.nimObservation, if (LspServerProfiles.isNim(state.spec)) synchronized(lifetimeLock) { nimLedger(state) } else null)
    }

    private suspend fun closeServerDocument(state: ServerState, path: String, allowCleanSharedClose: Boolean = false) {
        if (LspServerProfiles.isVue(state.spec)) {
            // Full desired membership is the sole close route; no generic native close is written.
            if (state.rpc.isRunning && servers[state.key] === state) synchronizeVue(state)
            return
        }
        if ((usesBackgroundPreparation(state.spec) && !(allowCleanSharedClose &&
                LspServerProfiles.reusesReadySessionAcrossDocumentClose(state.spec))) ||
            LspServerProfiles.isSqruff(state.spec) || LspServerProfiles.isCMake(state.spec)) {
            // Native didClose retains dirty analysis for files still on disk. A
            // never-owned tombstone is inert; an actual owner must be discarded.
            if (path in state.documentPaths) retireServer(state)
            return
        }
        val uri = uriFor(state, path)
        state.closedDocumentUris.add(uri)
        state.documentSync.version(uri)?.let { version ->
            state.retiredDiagnosticVersions.merge(uri, version, ::maxOf)
        }
        var retireTransport = !state.rpc.isRunning
        try {
            if (!retireTransport) state.documentSync.close(uri)
        } catch (cancel: CancellationException) {
            retireTransport = true
            throw cancel
        } catch (_: Exception) {
            // A failed close has uncertain wire delivery. Retire this exact process;
            // a subsequent request will replay current buffers into a fresh ledger.
            retireTransport = true
        } finally {
            state.documentPaths.remove(path)
            state.clojureDocumentGenerations.remove(path)
            state.prismaWireDocuments.remove(path)
            synchronized(lifetimeLock) {
                state.diagnosticRequests.remove(uri)
                val local = localUri(uri, state)
                if (!usesScopedRouting(state) || diagnosticReports[local]?.state === state) {
                    diagnosticReports.remove(local)
                    _diagnostics.update { it - local }
                }
            }
            if (retireTransport) retireServer(state)
        }
    }

    private suspend fun synchronizeServerDocument(state: ServerState, path: String, text: String): String {
        if (LspServerProfiles.isVue(state.spec)) { synchronizeVue(state); return uriFor(state, path) }
        check(!closed && managerJob.isActive) { "Language-server manager has been closed" }
        requireCurrentAnalysis(state.analysisPermit)
        LspServerProfiles.requireSupportedDocumentPath(state.spec, state.pathMapper?.localRelativeToRemote(path) ?: files.resolveChecked(path).path)
        val uri = uriFor(state, path)
        if (LspServerProfiles.isPowerShell(state.spec)) require(LspPowerShellCompletionContext.acceptsText(text)) {
            "PowerShell analysis requires bounded valid UTF-16 text with LF or CRLF, no NUL or lone CR"
        }
        if (!state.capabilities.sync.openClose) {
            val disk = files.readTextForEdit(path, maxBytes = 2_000_000)
            require(text == disk) {
                "Language server ${state.spec.id} does not accept open buffers; save $path before requesting code intelligence."
            }
        }
        requireCurrentAnalysis(state.analysisPermit)
        check(handlesDocument(state, path)) { "Language-server document route changed before synchronization" }
        if (usesBackgroundPreparation(state.spec)) synchronized(lifetimeLock) {
            check(servers[state.key] === state) { "${preparationLabel(state.spec)} session was retired" }
            documents[path]?.let { snapshot ->
                check(snapshot.text != null) { "${preparationLabel(state.spec)} document was closed before synchronization" }
                state.clojureDocumentGenerations[path] = snapshot.generation
            }
            state.documentPaths.add(path)
        }
        val effectiveText = if (LspServerProfiles.isPrisma(state.spec)) synchronized(lifetimeLock) {
            val snapshot = documents[path]
            val admitted = if (snapshot?.text == null) prismaAdmissions[path] else null
            val latest = snapshot?.text ?: requireNotNull(admitted?.takeIf {
                it.key == state.key && documentRequestStillCurrent(it.request)
            }?.request?.text) { "Prisma file-backed admission was superseded" }
            check(prismaTextStatus(latest).permitsAnalysis && handlesDocument(state, path)) { "Prisma document generation is unavailable" }
            admitted?.owner = state
            state.prismaWireDocuments[path] = PrismaWireDocument(snapshot, admitted?.request?.admission, latest)
            state.documentPaths.add(path)
            latest
        } else if (LspServerProfiles.isSqruff(state.spec)) synchronized(lifetimeLock) {
            check(servers[state.key] === state && sqruffOwnerOpen(state)) { "SQL immutable owner is unavailable or formatting" }
            val snapshot = documents[path]
            val latest = snapshot?.text ?: requireNotNull(sqruffAdmissions[path]?.takeIf(::documentRequestStillCurrent)?.text) {
                "SQL file-backed admission changed"
            }
            check(latest == text) { "SQL snapshot changed before synchronization" }
            val previous = state.sqruffWireDocuments[path]
            if (previous != null) {
                check(previous.snapshot === snapshot && previous.view.originalText == latest) { "SQL owner cannot accept a second source revision" }
                previous.view.wireText
            } else {
                val view = requireSqruffText(latest)
                check(state.sqruffWireDocuments.size < 32 && state.sqruffWireDocuments.values.sumOf { it.view.originalText.length.toLong() } + latest.length <= 2_000_000L) {
                    "SQL immutable project replay exceeds its bounded document/text budget"
                }
                state.sqruffWireDocuments[path] = SqruffWireDocument(snapshot, view)
                state.documentPaths.add(path)
                view.wireText
            }
        } else text
        if (LspServerProfiles.isPowerShell(state.spec)) synchronized(lifetimeLock) {
            val snapshot = documents[path]
            val admission = if (snapshot?.text == null) unmanagedAdmissions[path] else null
            check(snapshot?.text != null || admission != null) { "PowerShell document admission was retired" }
            state.powershellDocumentUris[uri] = path
            state.powershellDocumentRequests[path] = DocumentRequest(path, snapshot, effectiveText, null, admission)
        }
        if (LspServerProfiles.isNim(state.spec)) {
            val observation = requireNotNull(state.nimObservation) { "Nim metadata is not admitted" }
            val ticket = synchronized(lifetimeLock) {
                val snapshot = documents[path]
                val request = DocumentRequest(path, snapshot, effectiveText, null, if (snapshot?.text == null) unmanagedAdmissions[path] else null)
                check(snapshot?.text == effectiveText || snapshot == null && request.admission != null) { "Nim source changed before replay" }
                NimTicket("document-sync", nimLedger(state), observation, request).also {
                    check(nimCaptureCurrent(state, it, requireReady = false)) { "Nim replay owner changed" }
                    state.nimWireTicket = it
                }
            }
            try {
                state.documentSync.synchronize(uri, languageId(path), effectiveText)
                synchronized(lifetimeLock) { check(nimCaptureCurrent(state, ticket, requireReady = false)) { "Nim replay was superseded" } }
            } catch (failure: Throwable) { retireServer(state); throw failure }
            finally { synchronized(lifetimeLock) { if (state.nimWireTicket === ticket) state.nimWireTicket = null } }
        } else if (LspServerProfiles.isCMake(state.spec)) {
            require(LspCMakePositionCodec.acceptsText(effectiveText)) { "CMake requires bounded valid scalar text with LF/CRLF, no NUL or lone CR" }
            val observation = checkedCMakeAdmission(state)
            val ticket = synchronized(lifetimeLock) {
                val snapshot = documents[path]
                val admission = if (snapshot?.text == null) unmanagedAdmissions[path] else null
                val request = DocumentRequest(path, snapshot, effectiveText, null, admission)
                check(snapshot?.text == effectiveText || snapshot?.text == null && admission != null) { "CMake source changed before synchronization" }
                CMakeTicket("document-sync", relevantDocuments(state), observation, request).also {
                    check(cmakeCaptureCurrent(state, it.snapshots, it.observation, request))
                    state.cmakeWireTicket = it
                }
            }
            try {
                state.documentSync.synchronize(uri, languageId(path), effectiveText)
                synchronized(lifetimeLock) { check(cmakeCaptureCurrent(state, ticket.snapshots, ticket.observation, ticket.request)) { "CMake synchronization was superseded" } }
            } catch (failure: Throwable) { retireServer(state); throw failure }
            finally { synchronized(lifetimeLock) { if (state.cmakeWireTicket === ticket) state.cmakeWireTicket = null } }
        } else if (LspServerProfiles.isAsm(state.spec)) {
            // Upstream applies incremental tree edits against already-updated text,
            // and rejects range-less full changes. Reopen only the changed buffer on
            // demand; retain the healthy process, configuration and workspace grant.
            try {
                if (state.documentSync.text(uri)?.let { it != effectiveText } == true) state.documentSync.close(uri)
                state.documentSync.synchronize(uri, languageId(path), effectiveText)
            } catch (failure: Throwable) { retireServer(state); throw failure }
        } else state.documentSync.synchronize(uri, languageId(path), effectiveText)
        state.documentPaths.add(path)
        state.closedDocumentUris.remove(uri)
        return uri
    }

    private fun languageId(path: String): String =
        LanguageServerRegistry.protocolLanguageId(path)

    private fun uriFor(state: ServerState, path: String): String =
        state.pathMapper?.remoteUriFor(files.resolveChecked(path)) ?: files.resolveChecked(path).toURI().toString()

    private fun rootUri(mapper: WorkspacePathMapper?, projectRoot: File, spec: LanguageServerSpec): String {
        val uri = mapper?.remoteUriFor(projectRoot) ?: projectRoot.toURI().toString()
        return if (LspServerProfiles.isLemMinx(spec) && !uri.endsWith("/")) "$uri/" else uri
    }

    private fun workspaceFolder(mapper: WorkspacePathMapper?, projectRoot: File, spec: LanguageServerSpec) = buildJsonObject {
        put("uri", rootUri(mapper, projectRoot, spec))
        put("name", projectRoot.name.ifBlank { "workspace" })
    }

    // Called only after the finite profile parser validates the file URI envelope.
    // No discovery, prompts or documentMutex: native codeAction waits for this reply.
    private fun ownsLemMinxConfigurationScope(state: ServerState, uri: String): Boolean {
        if (closed || !managerJob.isActive || !state.rpc.isRunning || servers[state.key] !== state ||
            state.key.workspaceMutationEpoch != mutationEpoch(state.spec) ||
            !projectAvailable(state.spec, state.projectRoot)) return false
        val file = localFileFromUri(uri, state) ?: return false
        val path = file.relativeTo(root).invariantSeparatorsPath
        return path in state.documentPaths && documents[path]?.text != null && handlesDocument(state, path)
    }

    /** Pure exact owner admission for deny/null/error replies, even with no open document. */
    private fun ownsPowerShellCallbackOwner(spec: LanguageServerSpec, key: ServerKey, rpc: JsonRpcProcess): Boolean {
        if (closed || !managerJob.isActive || !rpc.isRunning || key.workspaceMutationEpoch != mutationEpoch(spec)) return false
        val attempt = clojureWarmups[key] ?: return false
        if (attempt.rpc !== rpc || attempt.phase == LspProjectAnalysisReadiness.FAILED) return false
        val state = servers[key]?.takeIf { it.rpc === rpc }
        if (state == null && !(attempt in analysisStartups && attempt.phase == LspProjectAnalysisReadiness.PREPARING)) return false
        if (state != null && attempt.state !== state) return false
        val permit = state?.analysisPermit ?: attempt.permit
        return analysisConsent.isCurrent(permit) &&
            LanguageServerRegistry.forPath(attempt.path, extraLanguageServers()).firstOrNull() == spec
    }

    /** Pure observed configuration eligibility, not a fresh filesystem/marker attestation. */
    private fun ownsPowerShellConfigurationScope(spec: LanguageServerSpec, key: ServerKey, rpc: JsonRpcProcess,
                                                 startup: AnalysisStartup?, uri: String?): Boolean {
        if (!ownsPowerShellCallbackOwner(spec, key, rpc)) return false
        val state = servers[key]?.takeIf { it.rpc === rpc }
        val currentStartup = startup?.takeIf { it in analysisStartups && it.rpc === rpc && it.phase == LspProjectAnalysisReadiness.PREPARING }
        fun recorded(path: String): Boolean {
            val owner = state ?: return false
            val request = owner.powershellDocumentRequests[path] ?: return false
            return path in owner.documentPaths && documentRequestStillCurrent(request) &&
                LanguageServerRegistry.forPath(path, extraLanguageServers()).firstOrNull() == spec
        }
        if (uri == null) return state?.powershellDocumentRequests?.keys?.any(::recorded) == true ||
            currentStartup?.let { LanguageServerRegistry.forPath(it.path, extraLanguageServers()).firstOrNull() == spec } == true
        val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return false
        if (parsed.scheme != "file" || !parsed.rawAuthority.isNullOrEmpty() || parsed.rawQuery != null || parsed.rawFragment != null) return false
        val path = state?.powershellDocumentUris?.get(uri) ?: return false
        return recorded(path)
    }

    private fun localFileFromUri(uri: String, state: ServerState): File? =
        state.pathMapper?.uriToLocal(uri) ?: runCatching { File(java.net.URI(uri)).canonicalFile }.getOrNull()?.takeIf { PathSecurity.contains(root, it) }

    private fun localUri(uri: String, state: ServerState): String =
        localFileFromUri(uri, state)?.toURI()?.toString() ?: uri


    private suspend fun consumeDiagnostics(params: JsonElement?, state: ServerState) = documentMutex.withLock {
        // Svelte unversioned pushes cannot replace captured inter-file pull evidence.
        if (LspServerProfiles.isVue(state.spec) || LspServerProfiles.isSvelte(state.spec) || LspServerProfiles.isAsm(state.spec)) return@withLock
        synchronized(lifetimeLock) {
            if (closed || servers[state.key] !== state || (state.analysisPermit != null && !analysisConsent.isCurrent(state.analysisPermit))) return@synchronized
            val obj = params as? JsonObject ?: return@synchronized
            val uri = (obj["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@synchronized
            if (uri in state.closedDocumentUris) return@synchronized
            val localFile = localFileFromUri(uri, state) ?: return@synchronized
            val publishedVersion = (obj["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (obj.containsKey("version") && publishedVersion == null) return@synchronized
            val currentVersion = state.documentSync.version(uri)
            if (publishedVersion != null && publishedVersion != currentVersion) return@synchronized
            val path = localFile.relativeTo(root).invariantSeparatorsPath
            if (usesBackgroundPreparation(state.spec) && (!clojureReady(state) ||
                path !in state.documentPaths || currentVersion == null ||
                (if (LspServerProfiles.isPowerShell(state.spec)) state.powershellDocumentRequests[path]?.let(::documentRequestStillCurrent) != true
                 else documents[path]?.text == null))) return@synchronized
            if (!handlesDocument(state, path)) return@synchronized
            if (LspServerProfiles.isPrisma(state.spec) && (path !in state.documentPaths ||
                !prismaWireStillCurrent(state, path))) return@synchronized
            val sqlView = if (LspServerProfiles.isSqruff(state.spec)) {
                if (!sqruffOwnerOpen(state) || !sqruffWireStillCurrent(state, path) || publishedVersion != null) return@synchronized
                state.sqruffWireDocuments[path]?.view ?: return@synchronized
            } else null
            val text = sqlView?.originalText ?: state.documentSync.text(uri)
            if (LspServerProfiles.isPrisma(state.spec) && text != state.prismaWireDocuments[path]?.text) return@synchronized
            // An edit may have published its new authoritative snapshot while waiting
            // for documentMutex. An old wire-version report must not validate it.
            if (documents[path]?.text?.let { it != text } == true) return@synchronized
            val diagnostics = if (sqlView != null) LspSqruffProfile.convertDiagnostics(
                obj["diagnostics"] as? JsonArray ?: return@synchronized, sqlView) ?: return@synchronized else obj["diagnostics"]
            if (LspServerProfiles.isNim(state.spec)) {
                if (state.nimObservation?.let { state.nimProcess?.isAdmissionCurrent(it) } != true || text == null || publishedVersion != null) return@synchronized
                val raw = diagnostics as? JsonArray ?: return@synchronized
                if (raw.size > 500 || raw.any { item -> (item as? JsonObject)?.get("range")?.let { LspNimResultAuthority.validRange(text, it) } != true }) return@synchronized
            }
            val items = LspDiagnosticsProtocol.parseItems(diagnostics, retainCode = LspServerProfiles.isPowerShell(state.spec)) ?: return@synchronized
            val reusedVersion = publishedVersion != null && publishedVersion <= (state.retiredDiagnosticVersions[uri] ?: 0)
            val source = when {
                publishedVersion == null -> "unversioned push; observed at local version $currentVersion"
                reusedVersion -> "push; reused version $publishedVersion after close/reopen; open lifetime unverified"
                else -> "push; version $publishedVersion"
            }
            updateDiagnostics(localFile.toURI().toString(), DiagnosticReport(state, currentVersion, text,
                relevantDocuments(state), items.take(500), source, publishedVersion != null && !reusedVersion))
        }
    }

    // Caller owns lifetimeLock. The Svelte capture conservatively includes every
    // in-workspace primary/companion ledger entry, so any effective revision can
    // invalidate a captured pull from any Svelte owner. Identical-text updates do
    // not call this method. This only withdraws reports; it never certifies new text.
    private fun clearSvelteDiagnosticsForRevision(path: String) {
        if (LspVueProfile.relevantPath(path)) {
            vueLedgerMutation++
            val open = documents.filter { LspVueProfile.relevantPath(it.key) && it.value.text != null }
            var retireReason: String? = null
            if (open.size > LspVueCompanionAuthority.MAX_OPEN) {
                if (!vueLedgerUnavailable) { vueLedgerEra++; retireReason = "Vue open-buffer limit exceeded; no dirty buffer was evicted." }
                vueLedgerUnavailable = true
                vueLedger.clear()
            } else {
                if (vueLedgerUnavailable) {
                    vueLedgerEra++
                    vueLedgerUnavailable = false
                    vueLedger.clear()
                    vueLedger.putAll(open)
                }
                documents[path]?.let { vueLedger[path] = it }
                if (vueLedger.size > 512) {
                    vueLedgerEra++
                    vueLedger.entries.removeAll { it.value.text == null }
                    retireReason = "Vue ledger era compacted; Retry with current complete open buffers."
                }
            }
            if (retireReason != null) {
                val states = servers.values.filter { LspServerProfiles.isVue(it.spec) }
                states.forEach { it.vueActive = false }
                managerScope.launch { states.forEach { failVue(it, retireReason) } }
            }
            clearDiagnosticReports { LspServerProfiles.isVue(it.state.spec) }
            vueHeldResults.clear()
            statusChanged()
        }
        if (LspNimProfile.supportsPath(path)) clearDiagnosticReports { LspServerProfiles.isNim(it.state.spec) }
        if (path.endsWith(".svelte") || LspSvelteProfile.isScriptCandidate(path))
            clearDiagnosticReports { LspServerProfiles.isSvelte(it.state.spec) }
    }

    private fun clearDiagnosticReports(predicate: (DiagnosticReport) -> Boolean) {
        val stale = diagnosticReports.filterValues(predicate).keys
        if (stale.isEmpty()) return
        stale.forEach(diagnosticReports::remove)
        _diagnostics.update { it - stale }
    }

    private fun updateDiagnostics(uri: String, report: DiagnosticReport) {
        synchronized(lifetimeLock) {
            if (!closed) {
                diagnosticReports[uri] = report
                _diagnostics.update { current -> current + (uri to report.items) }
            }
        }
    }

    private fun parseWorkspaceEdit(
        result: JsonElement, state: ServerState,
        requestText: Map<String, String>, requestVersions: Map<String, Int?>,
        prepared: PreparedDocument? = null,
    ): LspWorkspaceEdit? {
        val rootEdit = result as? JsonObject ?: return null
        // A workspace edit is one result: malformed containers must not become a partial batch.
        require(!rootEdit.containsKey("changes") || rootEdit["changes"] is JsonObject) {
            "Language server returned malformed workspace changes"
        }
        require(!rootEdit.containsKey("documentChanges") || rootEdit["documentChanges"] is JsonArray) {
            "Language server returned malformed workspace document changes"
        }
        if (LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) {
            require(prepared != null && (LspServerProfiles.isVue(state.spec) && prepared.vueTicket != null || prepared.nimObservation != null && prepared.nimLedger != null)) { "Nim edit requires its original request capture" }
            require(rootEdit.keys.all { it in setOf("changes", "documentChanges") }) { "Unsupported Nim workspace-edit shape" }
            rootEdit["changes"]?.let { require(it is JsonObject && it.values.all { v -> v is JsonArray }) }
            rootEdit["documentChanges"]?.let { raw ->
                require(raw is JsonArray && raw.all { item ->
                    val obj = item as? JsonObject
                    val doc = obj?.get("textDocument") as? JsonObject
                    obj != null && obj.keys == setOf("textDocument", "edits") && doc != null &&
                        (doc["uri"] as? JsonPrimitive)?.isString == true && obj["edits"] is JsonArray
                }) { "Nim resource or malformed document edit is unavailable" }
            }
        }
        val edits = mutableListOf<LspTextEdit>()
        val expected = linkedMapOf<String, String>()
        val versions = linkedMapOf<String, Int>()
        fun addEdits(uri: String, values: JsonArray, serverVersion: Int? = null) {
            if (LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) require(nimStrictUri(uri)) { "Nim edit URI is not a local owned file URI" }
            val file = localFileFromUri(uri, state)
                ?: throw IllegalArgumentException("Language server returned a workspace edit outside the mapped workspace")
            require(PathSecurity.contains(root, file)) { "Language server edit escaped the workspace" }
            val rel = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
            require(!SensitivePathPolicy.isSensitive(rel)) { "Language server attempted to edit a sensitive path: $rel" }
            if (LspServerProfiles.isPrisma(state.spec)) require(handlesDocument(state, rel) &&
                requestText[rel]?.let { prismaTextStatus(it).permitsAnalysis } == true) { "Prisma edit target is not an admitted legacy schema" }
            // Unknown targets cannot be proven unchanged since the request began.
            expected[rel] = requireNotNull(requestText[rel]) { "Open $rel and re-request this language-server edit" }
            if (LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) {
                require(rel in state.documentPaths && requestVersions[rel] != null) { "Nim target was not client-open before this request" }
                require(values.all { raw ->
                    val edit = raw as? JsonObject
                    edit != null && edit.keys == setOf("range", "newText") &&
                        (edit["newText"] as? JsonPrimitive)?.isString == true && LspNimResultAuthority.validRange(expected.getValue(rel), edit["range"])
                }) { "Nim edit contains an invalid captured UTF-16 range or unsupported shape" }
            }
            if (serverVersion != null) {
                require(requestVersions[rel] == serverVersion) { "Language-server version changed for $rel; re-request the edit" }
                versions[rel] = serverVersion
            }
            for (el in values) {
                require(edits.size < 2_000) { "Language server returned too many edits" }
                val range = requireNotNull(LspWorkspaceTextEditParser.parse(el)) {
                    "Language server returned a malformed workspace text edit"
                }
                edits += LspTextEdit(rel, range)
            }
        }

        (rootEdit["changes"] as? JsonObject)?.forEach { (uri, value) ->
            val arr = requireNotNull(value as? JsonArray) {
                "Language server returned malformed workspace file edits"
            }
            addEdits(uri, arr)
        }
        (rootEdit["documentChanges"] as? JsonArray)?.forEach { el ->
            val o = requireNotNull(el as? JsonObject) {
                "Language server returned a malformed workspace document edit"
            }
            
            require(o["kind"] == null) { "Language server requested a file resource operation; Droide will not apply it implicitly" }
            val textDocument = requireNotNull(o["textDocument"] as? JsonObject) {
                "Language server document edit is missing its document"
            }
            val uri = requireNotNull((textDocument["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) {
                "Language server document edit is missing its file URI"
            }
            val arr = requireNotNull(o["edits"] as? JsonArray) {
                "Language server document edit is missing its edits"
            }
            val rawVersion = textDocument["version"]
            // Retain existing absent/null-version compatibility, but never discard a supplied guard.
            val version = if (rawVersion == null || rawVersion == JsonNull) null else
                requireNotNull((rawVersion as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull) {
                    "Language server document edit has an invalid version"
                }
            addEdits(uri, arr, version)
        }
        val totalNewText = edits.sumOf { it.range.newText.length.toLong() }
        require(totalNewText <= 1_000_000L) { "Language server workspace edit is too large" }
        require(edits.map { it.path }.distinct().size <= 100) { "Language server attempted to edit too many files" }
        if (LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) edits.groupBy { it.path }.forEach { (path, items) ->
            LspNimResultAuthority.requireEdits(expected.getValue(path), items.map { it.range })
        }
        val guard = if (LspServerProfiles.isVue(state.spec)) {
            val capture = requireNotNull(prepared)
            ({ synchronized(lifetimeLock) { requestStillCurrent(state, capture) } })
        } else if (LspServerProfiles.isNim(state.spec)) {
            val capture = requireNotNull(prepared)
            val ticket = NimTicket("edit-accept", requireNotNull(capture.nimLedger), requireNotNull(capture.nimObservation), capture.request)
            ({ synchronized(lifetimeLock) { nimCaptureCurrent(state, ticket, requireReady = true) } })
        } else if (LspServerProfiles.isSvelte(state.spec)) {
            val capture = requireNotNull(prepared) { "Svelte edits require their original request capture" }
            ({ synchronized(lifetimeLock) {
                svelteCaptureCurrent(state, capture.snapshots, capture.svelteObservation) && clojureReady(state) &&
                    documentRequestStillCurrent(capture.request)
            } })
        } else null
        return LspWorkspaceEdit(edits, expected, versions, guard)
    }

    private fun parseCallHierarchyItem(o: JsonObject, state: ServerState): LspCallHierarchyItem? {
        val uri = (o["uri"] as? JsonPrimitive)?.contentOrNull ?: return null
        val file = localFileFromUri(uri, state) ?: return null
        val range = (o["selectionRange"] ?: o["range"]) as? JsonObject ?: return null
        fun position(element: JsonElement?): Pair<Int, Int>? {
            val value = element as? JsonObject ?: return null
            fun coordinate(key: String): Int? {
                val primitive = value[key] as? JsonPrimitive ?: return null
                if (primitive.isString) return null
                return primitive.intOrNull?.takeIf { it >= 0 && it < Int.MAX_VALUE }
            }
            return (coordinate("line") ?: return null) to
                (coordinate("character") ?: return null)
        }
        // Reject malformed targets instead of inventing coordinates or overflowing +1.
        val start = position(range["start"]) ?: return null
        val end = position(range["end"]) ?: return null
        if (end.first < start.first || (end.first == start.first && end.second < start.second)) return null
        val rel = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        return LspCallHierarchyItem(
            name = (o["name"] as? JsonPrimitive)?.contentOrNull?.take(500) ?: return null,
            detail = (o["detail"] as? JsonPrimitive)?.contentOrNull?.take(1_000),
            kind = (o["kind"] as? JsonPrimitive)?.intOrNull,
            path = rel,
            line = start.first + 1,
            column = start.second + 1,
            // Protocol identity/data must round-trip intact for incoming/outgoing calls.
            // Display fields above are independently bounded; truncating this JSON
            // would silently turn a valid item into an unusable follow-up request.
            rawJson = o.toString(),
        )
    }

    private fun parseCodeActions(result: JsonArray, state: ServerState, requestText: Map<String, String>, requestVersions: Map<String, Int?>, prepared: PreparedDocument? = null): List<LspCodeAction> =
        result.mapNotNull { parseCodeAction(it as? JsonObject ?: return@mapNotNull null, state, requestText, requestVersions, prepared) }
        // Matching display text does not identify a deferred action. Opaque data or
        // commands can resolve to different fixes even when no edit is available yet.
        .distinctBy { Triple(it.title, it.kind, it.edit?.edits) to it.rawJson }

    private fun parseCodeAction(o: JsonObject, state: ServerState, requestText: Map<String, String>, requestVersions: Map<String, Int?>, prepared: PreparedDocument? = null): LspCodeAction? {
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.take(500) ?: return null
        val unsupportedProfileCommand = (LspServerProfiles.isLemMinx(state.spec) || LspServerProfiles.isMake(state.spec) || usesBackgroundPreparation(state.spec) || LspServerProfiles.isPrisma(state.spec)) && o["command"] != null && o["command"] != JsonNull
        val edit = if (unsupportedProfileCommand) null else o["edit"]?.let { runCatching { parseWorkspaceEdit(it, state, requestText, requestVersions, prepared) }.getOrNull() }
        val command = if (unsupportedProfileCommand) null else o["command"] as? JsonObject
        // Invalid disabled metadata must not turn an action into an enabled one.
        // Ignore only that malformed item, preserving other actions in the response.
        val disabledMetadata = if (unsupportedProfileCommand || o["disabled"] == null || o["disabled"] == JsonNull) null
            else o["disabled"] as? JsonObject ?: return null
        val disabledReason = disabledMetadata?.let {
            (it["reason"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull?.take(1_000)
                ?: return null
        }
        val commandTitle = command?.get("title")?.let {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull?.take(500)
                ?: return null
        }
        val disabled = if (unsupportedProfileCommand) "This ${if (LspServerProfiles.isMake(state.spec)) "Make" else if (usesBackgroundPreparation(state.spec)) preparationLabel(state.spec) else if (LspServerProfiles.isPrisma(state.spec)) "Prisma" else "XML"} action requires an unsupported server command; no partial edit will be applied" else disabledReason
        return LspCodeAction(
            title = title,
            kind = (o["kind"] as? JsonPrimitive)?.contentOrNull?.take(200),
            preferred = (o["isPreferred"] as? JsonPrimitive)?.booleanOrNull == true,
            edit = edit,
            commandTitle = commandTitle,
            disabledReason = disabled,
            // Keep opaque resolve data intact; only the display fields are previews.
            // A truncated JSON string silently prevents codeAction/resolve dispatch.
            rawJson = o.toString(),
        )
    }

    private fun nimStrictUri(uri: String): Boolean = runCatching {
        val parsed = java.net.URI(uri)
        parsed.scheme == "file" && parsed.rawAuthority.isNullOrEmpty() && parsed.rawQuery == null && parsed.rawFragment == null &&
            parsed.path?.let { it.startsWith('/') && it.split('/').none { part -> part == "." || part == ".." } } == true
    }.getOrDefault(false)

    private fun nimOmitted(state: ServerState) {
        synchronized(lifetimeLock) { if (servers[state.key] === state && !state.nimPartialResults) { state.nimPartialResults = true; statusChanged() } }
    }

    private fun parseSymbols(result: JsonElement, defaultPath: String, state: ServerState, captured: Map<String, String>? = null): List<LspSymbol> {
        val arr = result as? JsonArray ?: return emptyList()
        val out = mutableListOf<LspSymbol>()
        fun walk(items: JsonArray, depth: Int) {
            for (el in items) {
                if (out.size >= 500) return
                val o = el as? JsonObject ?: continue
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.take(500) ?: continue
                val kind = (o["kind"] as? JsonPrimitive)?.intOrNull
                val location = if ("location" in o) o["location"] as? JsonObject ?: continue else null
                val uri = if (location != null) {
                    (location["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
                } else null
                val range = (o["selectionRange"] ?: o["range"] ?: location?.get("range")) as? JsonObject
                // Invalid symbol coordinates must not silently navigate to 1:1 or
                // overflow the editor's one-based position representation.
                fun position(element: JsonElement?): Pair<Int, Int>? {
                    val value = element as? JsonObject ?: return null
                    fun coordinate(key: String): Int? {
                        val primitive = value[key] as? JsonPrimitive ?: return null
                        if (primitive.isString) return null
                        return primitive.intOrNull?.takeIf { it >= 0 && it < Int.MAX_VALUE }
                    }
                    return (coordinate("line") ?: return null) to
                        (coordinate("character") ?: return null)
                }
                val start = position(range?.get("start")) ?: continue
                val end = position(range?.get("end")) ?: continue
                if (end.first < start.first || (end.first == start.first && end.second < start.second)) continue
                val symbolPath = if (uri != null) {
                    if ((LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) && !nimStrictUri(uri)) { nimOmitted(state); continue }
                    val file = localFileFromUri(uri, state) ?: run { if ((LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec))) nimOmitted(state); null } ?: continue
                    file.relativeTo(root.canonicalFile).invariantSeparatorsPath
                } else defaultPath
                if ((LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) && (captured?.get(symbolPath)?.let { LspNimResultAuthority.validRange(it, range) } != true || symbolPath !in state.documentPaths)) { nimOmitted(state); continue }
                out += LspSymbol(
                    name = name,
                    kind = kind,
                    path = symbolPath,
                    line = start.first + 1,
                    column = start.second + 1,
                    depth = depth.coerceAtMost(16),
                )
                val children = o["children"] as? JsonArray
                if (children != null) walk(children, depth + 1)
            }
        }
        walk(arr, 0)
        return out
    }


    private fun parseLocations(result: JsonElement, state: ServerState, captured: Map<String, String>? = null): List<LspLocation> {
        val arr = when (result) {
            is JsonArray -> result
            is JsonObject -> JsonArray(listOf(result))
            else -> JsonArray(emptyList())
        }
        return arr.take(200).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val uri = (o["uri"] as? JsonPrimitive)?.contentOrNull ?: (o["targetUri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if ((LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) && !nimStrictUri(uri)) { nimOmitted(state); return@mapNotNull null }
            val file = localFileFromUri(uri, state) ?: run { if ((LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec))) nimOmitted(state); null } ?: return@mapNotNull null
            val rel = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
            val range = (o["range"] ?: o["targetSelectionRange"]) as? JsonObject
            if ((LspServerProfiles.isNim(state.spec) || LspServerProfiles.isVue(state.spec)) && (captured?.get(rel)?.let { LspNimResultAuthority.validRange(it, range) } != true || rel !in state.documentPaths)) { nimOmitted(state); return@mapNotNull null }
            // A malformed target must not become a plausible 1:1 navigation result.
            // Validate zero-based UTF-16 coordinates before converting to the editor's
            // one-based representation; never let malformed siblings abort valid results.
            fun position(element: JsonElement?): Pair<Int, Int>? {
                val value = element as? JsonObject ?: return null
                fun coordinate(key: String): Int? {
                    val primitive = value[key] as? JsonPrimitive ?: return null
                    if (primitive.isString) return null
                    return primitive.intOrNull?.takeIf { it >= 0 && it < Int.MAX_VALUE }
                }
                return (coordinate("line") ?: return null) to
                    (coordinate("character") ?: return null)
            }
            val start = position(range?.get("start")) ?: return@mapNotNull null
            val end = position(range?.get("end")) ?: return@mapNotNull null
            if (end.first < start.first || (end.first == start.first && end.second < start.second)) {
                return@mapNotNull null
            }
            LspLocation(rel, start.first + 1, start.second + 1)
        }.distinct()
    }
    override suspend fun shutdown() {
        elp.shutdown()
        graphql.shutdown()
        toml.shutdown()
        csharp.shutdown()
        val snapshot = detachServers() ?: return
        try {
            snapshot.approvals.forEach { it.cancel() }
            snapshot.startups.forEach(::stopAnalysisStartup)
            for (state in snapshot.servers) {
                runSuspendCatching { state.rpc.request("shutdown", timeoutMs = 1_500) }
                runSuspendCatching { state.rpc.notify("exit") }
            }
        } finally {
            snapshot.servers.forEach { runCatching { it.close() } }
            managerJob.cancel()
        }
        fallback.shutdown()
    }

    override fun close() {
        elp.close()
        graphql.close()
        toml.close()
        csharp.close()
        val snapshot = detachServers() ?: return
        snapshot.approvals.forEach { it.cancel() }
        snapshot.startups.forEach(::stopAnalysisStartup)
        managerJob.cancel()
        snapshot.servers.forEach { runCatching { it.close() } }
        fallback.close()
    }

    private data class DetachedServers(
        val servers: List<ServerState>,
        val approvals: List<Deferred<Boolean>>,
        val startups: List<Pair<Deferred<ServerState?>, JsonRpcProcess?>>,
    )

    private fun detachServers(): DetachedServers? = synchronized(lifetimeLock) {
        if (closed) return@synchronized null
        closed = true
        analysisConsent.close()
        cmakeSelections?.clear()
        cmakeIdentity = null
        val snapshot = DetachedServers(servers.values.toList(), pendingAnalysis.values.toList(),
            analysisStartups.map { it.task to it.rpc })
        pendingAnalysis.clear()
        analysisStartups.clear()
        clojureWarmups.clear()
        statusChanged()
        servers.clear()
        unavailableUntil.clear()
        startupFailures.clear()
        documents.clear()
        unmanagedAdmissions.clear()
        vueHeldResults.clear()
        vueGrantedPermits.clear()
        svelteAdmissions.clear()
        svelteConfigDigests.clear()
        svelteGrantedPermits.clear()
        prismaAdmissions.clear()
        diagnosticReports.clear()
        _diagnostics.value = emptyMap()
        snapshot
    }
}

 
class CliLspManager(
    private val terminal: ITerminalSession,
    private val files: FileRepository? = null,
) : LspManager {
    private val emptyDiagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = emptyDiagnostics.asStateFlow()
    override suspend fun diagnose(relPath: String): String {
        val root = files?.root
            ?: return "CLI diagnostics require a bound workspace; no compiler was started."
        return ExportWriterAdmission.withSuspendingWriter(root, "CLI diagnostics") { diagnoseAdmitted(relPath) }
    }

    private suspend fun diagnoseAdmitted(relPath: String): String {
        val ext = relPath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py" -> {
                val probe = execInWorkspace(
                    listOf(
                        "sh", "-c",
                        "if command -v ruff >/dev/null 2>&1; then printf ruff; " +
                            "elif command -v python3 >/dev/null 2>&1; then printf python3; else exit 127; fi",
                    ),
                    timeoutMs = 5_000,
                )
                if (probe.timedOut || probe.exitCode == -1 || probe.exitCode == 126) {
                    return "Python diagnostics backend unavailable: ${probe.output.take(2_000)}"
                }
                if (probe.exitCode == 127) {
                    return "Python diagnostics unavailable: install Python 3 (and optionally Ruff) in local Ubuntu."
                }
                val r = when (probe.output.trim()) {
                    "ruff" -> execInWorkspace(listOf("ruff", "check", relPath), timeoutMs = 15_000)
                    "python3" -> execInWorkspace(listOf("python3", "-m", "py_compile", relPath), timeoutMs = 15_000)
                    else -> return "Python diagnostics backend returned an invalid capability probe."
                }
                when {
                    r.timedOut || r.exitCode == -1 ->
                        "Python diagnostics backend unavailable: ${r.output.take(2_000)}"
                    r.exitCode == 0 ->
                        "OK: $relPath\n${r.output.take(2_000)}"
                    else ->
                        "Diagnostics for $relPath:\n${r.output.take(4_000)}"
                }
            }
            "kt", "kts" -> {
                val out = files?.root?.let { PathSecurity.resolveWithin(it, ".droide/tmp/lsp-check.jar").apply { parentFile?.mkdirs() }.absolutePath } ?: "droide-lsp-check.jar"
                val r = execInWorkspace(listOf("kotlinc", relPath, "-d", out), timeoutMs = 20_000)
                if (r.exitCode == 0) "OK: $relPath" else "Diagnostics for $relPath:\n${r.output.take(4_000)}"
            }
            else -> "No language server or CLI diagnostic provider configured for .$ext."
        }
    }

    private suspend fun execInWorkspace(argv: List<String>, timeoutMs: Long): ExecResult = files?.root?.let {
        WorkspaceCommandInvocation.execute(terminal, it, it, argv, timeoutMs)
    } ?: terminal.execArgv(argv, timeoutMs)

    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String {
        return when (op) {
            "hover" -> diagnose(path).take(2_000)
            "documentSymbol" -> documentSymbols(path)
            "workspaceSymbol" -> {
                val repo = files ?: return "Workspace symbol search requires a file repository"
                val q = path.trim().ifBlank { return "Symbol query is empty" }
                val hits = repo.search(q, 80)
                if (hits.isBlank()) "(no symbols)" else hits.lineSequence().filter { text ->
                    Regex("\\b(class|fun|def|interface|object|struct|enum|fn|func)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
                }.take(80).joinToString("\n").ifBlank { "(no symbols)" }
            }
            else -> "Persistent language server unavailable for $op. Install a supported language server in Droide's executable path."
        }
    }

    private suspend fun documentSymbols(path: String): String {
        val repo = files ?: return "Document symbol search requires a file repository"
        val text = runSuspendCatching { repo.readTextForEdit(path, 1_000_000) }.getOrElse { return "ERROR: ${it.message}" }
        val patterns = listOf(
            Regex("^\\s*(class|interface|object|enum|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            Regex("^\\s*(fun|def|fn|func)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        )
        return buildString {
            text.lineSequence().forEachIndexed { idx, l ->
                patterns.firstNotNullOfOrNull { it.find(l) }?.let { m -> appendLine("${idx + 1}: ${m.value.trim()}") }
            }
        }.take(4_000).ifBlank { "(no symbols)" }
    }
}

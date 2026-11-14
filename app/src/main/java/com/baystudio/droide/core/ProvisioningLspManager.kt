package com.baystudio.droide.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// This wrapper checks installed executable availability; it does not install tools.
// Confirmed absence is briefly backed off; failed observations remain retryable and visible.


class ProvisioningLspManager(
    private val delegate: LspManager,
    private val ensureLanguageServer: suspend (String) -> Boolean,
    private val retryBackoffMs: Long = 30_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val readinessTtlMs: Long = 30_000L,
) : LspManager {
    private val mutex = Mutex()
    private val cacheLock = Any()
    private var environmentEpoch = 0L
    override fun environmentInvalidated() {
        synchronized(cacheLock) { environmentEpoch++; readyAt.clear(); retryAfter.clear() }
        delegate.environmentInvalidated()
    }
    private val readyAt = ConcurrentHashMap<String, Long>()
    private val retryAfter = ConcurrentHashMap<String, Long>()

    init {
        require(readinessTtlMs in 1..300_000L) { "Invalid language-server readiness cache lifetime" }
    }

    override val statusRevision: StateFlow<Long> get() = delegate.statusRevision

    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> get() = delegate.diagnostics

    private suspend fun ensureFor(path: String) {
        val planned = LanguageServerRegistry.forPath(path).mapNotNull { spec ->
            ManagedLanguageServerPlan.forServer(spec.id)?.let { spec.id }
        }
        if (planned.isEmpty()) return
        mutex.withLock {
            val capturedEpoch = synchronized(cacheLock) { environmentEpoch }
            var probeFailure: ToolchainProbeFailed? = null
            for (serverId in planned) {
                val now = nowMs()
                val checkedAt = readyAt[serverId]
                if (checkedAt != null && now >= checkedAt && now - checkedAt < readinessTtlMs) return@withLock
                readyAt.remove(serverId)
                if ((retryAfter[serverId] ?: 0L) > now) continue
                val installed = try {
                    checkedToolchainProbe("Language server $serverId") { ensureLanguageServer(serverId) }
                } catch (failure: ToolchainProbeFailed) {
                    if (probeFailure == null) probeFailure = failure else probeFailure.addSuppressed(failure)
                    continue
                }
                if (installed) {
                    synchronized(cacheLock) {
                        if (capturedEpoch == environmentEpoch) {
                            readyAt[serverId] = now
                            retryAfter.remove(serverId)
                        }
                    }
                    return@withLock
                } else {
                    synchronized(cacheLock) { if (capturedEpoch == environmentEpoch) retryAfter[serverId] = now + retryBackoffMs }
                }
            }
            probeFailure?.let { throw it }
        }
    }

    private suspend fun <T> provisioned(path: String, currentText: String? = null, request: suspend () -> T): T {
        // Built-in Prisma admission belongs to the delegate: it classifies an
        // immutable editor/file snapshot before any executable discovery.
        val support = delegate.documentSupportStatus(path, currentText)
        if (support?.phase == LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8)
            throw LspDocumentSupportUnavailableException(support)
        // Presence discovery must not precede this explicit execution-consent gate.
        val analysis = delegate.projectAnalysisStatus(path)
        if (analysis?.enabled == true && (analysis.readiness == LspProjectAnalysisReadiness.PREPARING ||
                analysis.readiness == LspProjectAnalysisReadiness.FAILED)) {
            throw LspProjectAnalysisNotReadyException(
                analysis.readiness,
                analysis.readinessDetail ?: if (analysis.readiness == LspProjectAnalysisReadiness.PREPARING)
                    "Preparing project analysis. First indexing may take several minutes; Revoke stops it."
                else "Project analysis failed. Check project tools/configuration, then Retry.",
            )
        }
        // Reviewed typed delegates perform health-free exact package admission themselves.
        // Generic installed-presence probes can execute health and cannot establish that authority.
        val typedManagedRoute = LanguageServerRegistry.forPath(path).any {
            LspServerProfiles.isNim(it) || LspServerProfiles.isVue(it) || LspServerProfiles.isToml(it) || LspServerProfiles.isGraphql(it) || LspServerProfiles.isElp(it)
        }
        if (support == null && analysis?.enabled != false && delegate.cmakeSelectionStatus(path) == null && delegate.csharpSelectionStatus(path) == null && !typedManagedRoute) ensureFor(path)
        return request()
    }

    override suspend fun diagnose(relPath: String): String = userFacingTextFailure("Diagnostics", relPath) {
        provisioned(relPath) { delegate.diagnose(relPath) }
    }

    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String =
        userFacingTextFailure("LSP $op", path) { provisioned(path) { delegate.lsp(op, path, line, col) } }

    override suspend fun heldTextResult(op: String, path: String, line: Int, col: Int): LspHeldTextResult = try {
        provisioned(path) { delegate.heldTextResult(op, path, line, col) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Throwable) { LspHeldTextResult("LSP $op unavailable for ${path.take(240)}: ${error.message?.take(320) ?: error::class.java.simpleName}") }

    private suspend fun userFacingTextFailure(label: String, path: String, request: suspend () -> String): String = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        "$label unavailable for ${path.take(240)}: ${error.message?.take(320) ?: error::class.java.simpleName}"
    }

    override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> =
        provisioned(path) { delegate.completionItems(path, line, col) }

    override suspend fun completionItems(
        path: String,
        line: Int,
        col: Int,
        triggerCharacter: String?,
        currentText: String?,
    ): List<LspCompletionItem> = provisioned(path, currentText) {
        delegate.completionItems(path, line, col, triggerCharacter, currentText)
    }

    override fun signatureHelpTriggers(path: String): LspSignatureTriggers = delegate.signatureHelpTriggers(path)

    // A cold UI hint only: never ensure, discover, grant, or start a server here.
    override fun completionPolicy(path: String): LspCompletionPolicy? = delegate.completionPolicy(path)
    override fun heldResultGuard(path: String): LspHeldResultGuard? = delegate.heldResultGuard(path)

    override suspend fun signatureHelp(
        path: String,
        line: Int,
        col: Int,
        currentText: String?,
        triggerKind: LspSignatureTriggerKind,
        triggerCharacter: String?,
        isRetrigger: Boolean,
        previous: LspSignatureHelp?,
     ): LspSignatureHelp? = provisioned(path, currentText) {
        delegate.signatureHelp(path, line, col, currentText, triggerKind, triggerCharacter, isRetrigger, previous)
    }

    override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> =
        provisioned(path, currentText) { delegate.semanticTokens(path, currentText) }

    override suspend fun inlayHints(path: String, currentText: String?): List<LspInlayHint> =
        provisioned(path, currentText) { delegate.inlayHints(path, currentText) }

    override suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> =
        provisioned(path) { delegate.locationItems(op, path, line, col) }

    override suspend fun symbolItems(path: String): List<LspSymbol> = provisioned(path) { delegate.symbolItems(path) }
    override suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> =
        provisioned(pathHint) { delegate.workspaceSymbols(pathHint, query) }

    override suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> =
        provisioned(path) { delegate.codeActions(path, diagnostic) }

    override suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> =
        provisioned(path) { delegate.codeActionsAt(path, line, col) }

    override suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction =
        provisioned(path) { delegate.resolveCodeAction(path, action) }

    override suspend fun prepareCallHierarchy(path: String, line: Int, col: Int): List<LspCallHierarchyItem> =
        provisioned(path) { delegate.prepareCallHierarchy(path, line, col) }

    override suspend fun incomingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        provisioned(item.path) { delegate.incomingCalls(item) }
    override suspend fun outgoingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        provisioned(item.path) { delegate.outgoingCalls(item) }

    override suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? =
        provisioned(path) { delegate.renameSymbol(path, line, col, newName) }

    override suspend fun formatDocument(path: String): LspWorkspaceEdit? = provisioned(path) { delegate.formatDocument(path) }
    override suspend fun formatDocument(path: String, options: LspFormattingOptions): LspWorkspaceEdit? =
        provisioned(path) { delegate.formatDocument(path, options) }

    override suspend fun didOpen(path: String, text: String) = delegate.didOpen(path, text)
    override suspend fun didChange(path: String, text: String) = delegate.didChange(path, text)
    override suspend fun didSave(path: String, text: String?) = delegate.didSave(path, text)
    override suspend fun didSaveSnapshot(path: String, savedText: String, currentText: String) =
        delegate.didSaveSnapshot(path, savedText, currentText)
    override suspend fun didClose(path: String) = delegate.didClose(path)
    override fun workspaceFilesChanged() = delegate.workspaceFilesChanged()
    override fun serverStatus(path: String): String = delegate.serverStatus(path)
    override fun documentSupportStatus(path: String, currentText: String?): LspDocumentSupportStatus? = delegate.documentSupportStatus(path, currentText)
    override fun projectAnalysisStatus(path: String): LspProjectAnalysisStatus? = delegate.projectAnalysisStatus(path)
    override fun csharpSelectionStatus(path: String): CSharpAnalysisSelections.Status? = delegate.csharpSelectionStatus(path)
    override suspend fun saveCsharpSelection(path: String, rootPath: String, mode: CSharpAnalysisSelections.Mode, solutionPath: String?): String =
        delegate.saveCsharpSelection(path, rootPath, mode, solutionPath)
    override fun cmakeSelectionStatus(path: String): CMakeAnalysisSelections.Status? = delegate.cmakeSelectionStatus(path)
    override suspend fun saveCmakeSelection(path: String, sourcePath: String, configuredBuildPath: String?): String =
        delegate.saveCmakeSelection(path, sourcePath, configuredBuildPath)
    override suspend fun enableProjectAnalysis(path: String): String = delegate.enableProjectAnalysis(path)
    override fun startupFailure(path: String): LspStartupFailure? = delegate.startupFailure(path)
    override suspend fun retryStartup(path: String): String = delegate.retryStartup(path)
    override suspend fun retryProjectAnalysis(path: String): String = delegate.retryProjectAnalysis(path)
    override fun revokeProjectAnalysis(path: String): String = delegate.revokeProjectAnalysis(path)
    override fun retireProjectAnalysisAuthority(): () -> Unit = delegate.retireProjectAnalysisAuthority()
    override suspend fun shutdown() = delegate.shutdown()
    override fun close() = delegate.close()
}

package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Live-buffer route. Never publishes to disk or falls back when the editor is unavailable. */
internal object AgentStreamedLiveWriteTool {
    sealed interface LiveResult {
        val output: String
        data class Rejected(override val output: String) : LiveResult
        data class Committed(override val output: String,
            val proof: StreamedLiveEditObservation.PublicationProof) : LiveResult
    }

    suspend fun execute(
        prepared: StreamedFileArgumentExtractor.Prepared,
        sourceSession: OwnedResponseContentStore.Session,
        c: AgentTools.Ctx,
        hooks: AgentHookLifecycleBridge,
        hookOwner: HookInvocationAuthority,
        requireAuthority: () -> Unit,
        onApproved: suspend (StreamedLiveEditObservation) -> Unit,
    ): LiveResult {
        val toolName = prepared.wireName
        val verb = if (prepared.tool == StreamedFileArgumentExtractor.Tool.WRITE_FILE) "Write" else "Edit"
        val root = ProjectCopyEvidence.root(c.files.root)
        val job = currentCoroutineContext()
        fun current() {
            job.ensureActive(); requireAuthority(); hookOwner.requireCurrent()
            check(ProjectCopyEvidence.root(c.files.root) == root) { "Original workspace changed" }
        }
        current()
        require(c.mode == hookOwner.mode && c.provenance == hookOwner.provenance && c.permissionScope == hookOwner.permissionScope)
        if (!c.perms.toolAllowed(prepared.tool.wireName)) return LiveResult.Rejected("DENIED (agent profile): $toolName is not available")
        AgentModePolicy.check(c.mode, prepared.tool.wireName)?.let { return LiveResult.Rejected(it) }
        val requested = prepared.requestedPath.replace('\\', '/')
        require(!requested.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(requested) && !requested.endsWith('/'))
        require(requested.split('/').none { it == "." || it == ".." })
        val path = requested.split('/').filter { it.isNotEmpty() }.joinToString("/")
        require(path.isNotEmpty())

        if (c.perms.decide("edit", path, c.permissionScope) == PermEffect.DENY)
            return LiveResult.Rejected("DENIED (policy): edit $path")
        c.files.resolveChecked(path)
        val expected = prepared.expectedDocument ?: return LiveResult.Rejected("Live write requires the reviewed document identity and version")
        val version = WorkspaceDocumentVersion(expected.revision, expected.changeVersion, expected.documentId)
        val documents = c.documentAuthority ?: return LiveResult.Rejected("Live editor authority is unavailable; no disk fallback was used")
        prepared.originalArguments.verify(sourceSession)
        val input = HookToolInputSource.admit(sourceSession, prepared.originalArguments.handle, ::current)
        var observation: StreamedLiveEditObservation? = null
        var result: LiveResult? = null
        var pending: EditHistory.PendingLive? = null
        var settled = false
        suspend fun settle() {
            if (settled) return
            val observed = observation
            val publication = observed?.replacement?.publication
            if (publication != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    // A failed commit may already have inserted the entry. Never retry it blindly.
                    settled = true
                    c.history.commitLive(observed.undo, EditHistory.LiveTransition(publication.before, publication.after))
                    observed.acknowledgeUndo()
                }
            } else if (observed?.replacement?.publicationAttempted != true) pending?.let { undo ->
                withContext(NonCancellable + Dispatchers.IO) { c.history.discardLive(undo); settled = true }
            }
        }
        try {
            val hookOutput = hooks.toolSource(toolName, input, owner = hookOwner) {
                current()
                val before = documents.snapshot(path)
                if (before == null || before.version != version || !before.loaded || !before.editable || before.kind != WorkspaceDocumentKind.TEXT) {
                    result = LiveResult.Rejected("The reviewed live document changed or is not editable; no write was performed")
                    return@toolSource requireNotNull(result).output
                }
                val content = when (prepared.tool) {
                    StreamedFileArgumentExtractor.Tool.WRITE_FILE -> requireNotNull(prepared.content).also { it.verify(sourceSession) }
                    StreamedFileArgumentExtractor.Tool.EDIT_FILE -> StreamedLiveEditPreparation.prepare(prepared, sourceSession, before, ::current)
                }
                val proposal = withContext(Dispatchers.IO) { ProposedFileContent.admit(sourceSession, content, ::current) }
                val preview = withContext(Dispatchers.IO) { proposal.readPage(0, ::current).text }
                val detail = "Replace live buffer $path with ${content.bytes} bytes.\nSHA-256: ${content.sha256}\nThis is a partial preview, not a complete diff. Read or export the full proposal before approving.\n\n$preview"
                when (c.perms.decide("edit", path, c.permissionScope)) {
                    PermEffect.DENY -> { result = LiveResult.Rejected("DENIED (policy): edit $path"); return@toolSource requireNotNull(result).output }
                    PermEffect.ASK -> {
                        c.onProgress(AgentToolProgress(detail = "Waiting for approval"))
                        val decision = c.approvals.requestApproval(toolName, "$verb $path", detail,
                            action = "edit", resource = path, permissionScope = c.permissionScope, provenance = c.provenance,
                            proposedContent = proposal, isStillCurrent = hookOwner.isStillCurrent, ownerAdmission = hookOwner.approvalAdmission)
                        if (decision is ApprovalDecision.Denied) { result = LiveResult.Rejected("DENIED by user: edit $path"); return@toolSource requireNotNull(result).output }
                    }
                    PermEffect.ALLOW -> Unit
                }
                fun admitted() {
                    current(); observation?.requireBackupFence()
                    check(c.perms.toolAllowed(prepared.tool.wireName) && AgentModePolicy.check(c.mode, prepared.tool.wireName) == null &&
                        ApprovalPolicyGuard.remainsPermitted(c.perms, "edit", path, c.permissionScope)) { "Write permission changed after review" }
                }
                admitted()
                val replacement = WorkspaceSourceTextReplacement.prepare(sourceSession, content, ::admitted)
                admitted()
                check(documents.snapshot(path)?.version == version) { "Live document changed after review" }
                // Retain pending identity even when prompt cancellation wins at IO return.
                withContext(Dispatchers.IO) { pending = c.history.prepareLive(path, before.content) }
                val observed = withContext(Dispatchers.IO) { StreamedLiveEditObservation(c.files.root, path, before, replacement,
                    prepared.originalArguments.sha256, requireNotNull(pending)) }
                observation = observed
                onApproved(observed)
                admitted(); withContext(Dispatchers.IO) { observed.verifyUndo() }
                val applied = documents.replaceSourceText(path, version, replacement, "agent $toolName", ::admitted, {})
                if (applied !is WorkspaceDocumentMutationResult.Applied) {
                    result = LiveResult.Rejected("Live document changed or became unavailable; no write was performed")
                } else {
                    settle()
                    val saved = AgentEditPersistence.save(documents, listOf(applied), ::admitted)
                    result = LiveResult.Committed("OK: updated live buffer $path" + saved, observed.publicationProof())
                }
                requireNotNull(result).output
            }
            if (result == null) result = LiveResult.Rejected(hookOutput)
        } catch (failure: SourceBackedHookTransportUnavailableException) {
            if (observation?.replacement?.publicationAttempted == true && observation?.replacement?.publication == null) throw failure
            result = if (observation?.replacement?.publication != null) {
                settle()
                LiveResult.Committed("Live buffer updated; lifecycle hook transport unavailable after publication. Do not repeat this write.", requireNotNull(observation).publicationProof())
            } else LiveResult.Rejected("Source-backed write with configured hooks is unavailable; no write was executed")
        } finally {
            // Only undo metadata settlement after Stop, never another project/editor mutation.
            settle()
        }
        return requireNotNull(result)
    }
}

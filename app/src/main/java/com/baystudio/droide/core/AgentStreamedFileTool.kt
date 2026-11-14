package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One supported vertical route; not a replacement for the general54-tool executor.
 * Caller durably records the completed call before invocation. No streamed deltas execute here.
 */
internal object AgentStreamedFileTool {
    suspend fun execute(
        prepared: StreamedFileArgumentExtractor.Prepared,
        sourceSession: OwnedResponseContentStore.Session,
        c: AgentTools.Ctx,
        hooks: AgentHookLifecycleBridge,
        hookOwner: HookInvocationAuthority,
        call: AgentStreamedTextEditCall,
        requireAuthority: () -> Unit,
        onApproved: suspend (StreamedTextEditObservation) -> Unit,
    ): AgentStreamedWriteTool.Result {
        val toolName = prepared.wireName
        val verb = if (prepared.tool == StreamedFileArgumentExtractor.Tool.WRITE_FILE) "Write" else "Edit"
        val originalRoot = c.files.root.canonicalPath
        fun current() {
            requireAuthority(); hookOwner.requireCurrent()
            check(c.files.root.canonicalPath == originalRoot) { "Original workspace changed" }
        }
        current()
        require(c.mode == hookOwner.mode && c.provenance == hookOwner.provenance && c.permissionScope == hookOwner.permissionScope) {
            "Streamed write hook authority differs from the tool context"
        }
        if (!c.perms.toolAllowed(prepared.tool.wireName)) return AgentStreamedWriteTool.Result.Rejected("DENIED (agent profile): $toolName is not available")
        AgentModePolicy.check(c.mode, prepared.tool.wireName)?.let { return AgentStreamedWriteTool.Result.Rejected(it) }
        if (prepared.expectedDocument != null) return AgentStreamedWriteTool.Result.Rejected("Large live-editor replacement is not supported by this route; the editor and disk were left unchanged")
        val documents = c.documentAuthority ?: return AgentStreamedWriteTool.Result.Rejected("Streamed write requires an active editor publication authority")
        val barrier = documents as? StreamedDiskCommitAuthority
            ?: return AgentStreamedWriteTool.Result.Rejected("This editor cannot atomically admit a streamed disk write")
        prepared.originalArguments.verify(sourceSession)
        val hookInput = HookToolInputSource.admit(sourceSession, prepared.originalArguments.handle, ::current)
        var result: AgentStreamedWriteTool.Result? = null
        try { hooks.toolSource(toolName, hookInput, owner = hookOwner) {
            current()
            val requested = prepared.requestedPath.replace('\\', '/')
            require(!requested.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(requested) && !requested.endsWith('/')) {
                "Expected a relative workspace file path"
            }
            require(requested.split('/').none { it == "." || it == ".." }) { "Aliased workspace paths are unsupported" }
            val path = requested.split('/').filter { it.isNotEmpty() }.joinToString("/")
            if (c.perms.decide("edit", path, c.permissionScope) == PermEffect.DENY) {
                result = AgentStreamedWriteTool.Result.Rejected("DENIED (policy): edit $path")
                return@toolSource requireNotNull(result).output
            }
            // Capture rejects aliases and invalid components before any review/backup mutation.
            val before = StreamFileVersion.capture(c.files.root, path)
            if (documents.snapshot(path) != null) {
                result = AgentStreamedWriteTool.Result.Rejected("The target is open in the editor. Large live-editor replacement is not supported; no disk fallback was used")
                return@toolSource requireNotNull(result).output
            }
            val content = when (prepared.tool) {
                StreamedFileArgumentExtractor.Tool.WRITE_FILE -> requireNotNull(prepared.content).also { it.verify(sourceSession) }
                StreamedFileArgumentExtractor.Tool.EDIT_FILE -> StreamedDiskEditPreparation.prepare(
                    prepared, sourceSession, c.files, path, before, ::current)
            }
            val proposal = withContext(Dispatchers.IO) { ProposedFileContent.admit(sourceSession, content, ::current) }
            val preview = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                proposal.readPage(0) { job.ensureActive() }.text
            }
            val detail = "Replace $path with ${content.bytes} bytes.\nSHA-256: ${content.sha256}\nThis is a partial preview, not a complete diff. Read or export the full proposal before approving.\n\n$preview"
            val denied = when (c.perms.decide("edit", path, c.permissionScope)) {
                PermEffect.DENY -> "DENIED (policy): edit $path"
                PermEffect.ALLOW -> null
                PermEffect.ASK -> {
                    c.onProgress(AgentToolProgress(detail = "Waiting for approval"))
                    val decision = c.approvals.requestApproval(toolName, "$verb $path", detail,
                        action = "edit", resource = path, permissionScope = c.permissionScope,
                        provenance = c.provenance, proposedContent = proposal,
                        isStillCurrent = hookOwner.isStillCurrent, ownerAdmission = hookOwner.approvalAdmission)
                    if (decision is ApprovalDecision.Denied) "DENIED by user: edit $path" else null
                }
            }
            if (denied != null) {
                result = AgentStreamedWriteTool.Result.Rejected(denied)
                return@toolSource denied
            }
            fun admitted() {
                current()
                check(c.perms.toolAllowed(prepared.tool.wireName) && AgentModePolicy.check(c.mode, prepared.tool.wireName) == null &&
                    ApprovalPolicyGuard.remainsPermitted(c.perms, "edit", path, c.permissionScope)) {
                    "Write permission changed after review"
                }
            }
            admitted()
            val observation = StreamedTextEditObservation(c.files, path, before,
                StreamContentVersion(true, content.bytes, content.sha256), prepared.originalArguments.sha256,
                barrier, ::admitted)
            // The journal records approval/execution intent before any project publication.
            onApproved(observation)
            admitted()
            val pending = c.history.prepareStream(path, before)
            admitted()
            val applied = StreamedDiskEditTransaction.execute(c.files, c.history, pending, observation, call, sourceSession, content)
            val proof = call.publicationProof(observation)
            val output = "OK: ${if (prepared.tool == StreamedFileArgumentExtractor.Tool.WRITE_FILE) "wrote" else "edited"} $path" + if (applied.synchronizationPending)
                "; saved locally, synchronization pending. Do not repeat this write." else ""
            result = AgentStreamedWriteTool.Result.Committed(output, proof, applied.synchronizationPending)
            output
        }.also { hookOutput ->
            // A PreToolUse denial does not call the body. Never invent a committed result.
            if (result == null) result = AgentStreamedWriteTool.Result.Rejected(hookOutput)
        }
        } catch (unavailable: SourceBackedHookTransportUnavailableException) {
            result = when (val currentResult = result) {
                is AgentStreamedWriteTool.Result.Committed -> currentResult.copy(output = currentResult.output +
                    "; lifecycle hook transport unavailable after publication. The write is already committed; do not repeat it.")
                is AgentStreamedWriteTool.Result.Rejected -> currentResult
                null -> AgentStreamedWriteTool.Result.Rejected("Source-backed write with configured hooks is unavailable without immutable event transport; no write was executed")
            }
        }
        return requireNotNull(result)
    }
}

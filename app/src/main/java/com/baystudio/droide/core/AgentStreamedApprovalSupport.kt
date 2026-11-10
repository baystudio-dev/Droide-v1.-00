package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AgentStreamedApprovalSupport {
    suspend fun doomLoopDenied(prepared: StreamedFileArgumentExtractor.Prepared,
        sourceSession: OwnedResponseContentStore.Session, c: AgentTools.Ctx,
        owner: HookInvocationAuthority, requireCurrent: () -> Unit): Boolean {
        requireCurrent(); owner.requireCurrent()
        require(c.mode == owner.mode && c.provenance == owner.provenance && c.permissionScope == owner.permissionScope)
        return when (c.perms.decide("doom_loop", prepared.wireName, c.permissionScope)) {
            PermEffect.ALLOW -> false
            PermEffect.DENY -> true
            PermEffect.ASK -> {
                val content = requireNotNull(prepared.content ?: prepared.replacement)
                val proposal = withContext(Dispatchers.IO) {
                    ProposedFileContent.admit(sourceSession, content) { requireCurrent(); owner.requireCurrent() }
                }
                val detail = "Repeated ${prepared.wireName} request\nPath: ${prepared.requestedPath}\n" +
                    "Original arguments SHA-256: ${prepared.originalArguments.sha256}\n" +
                    "${if (prepared.content != null) "Proposed file" else "Replacement fragment"} bytes: ${content.bytes}; SHA-256: ${content.sha256}\n" +
                    "Read or export this exact source. An edit replacement fragment is not the final file. This approval permits continuation of the repetition check only; target write permission and conflict checks still apply."
                val decision = c.approvals.requestApproval(prepared.wireName, "Repeated ${prepared.wireName} request — continue?", detail,
                    action = "doom_loop", resource = prepared.wireName, permissionScope = c.permissionScope,
                    provenance = c.provenance, isStillCurrent = owner.isStillCurrent,
                    ownerAdmission = owner.approvalAdmission, proposedContent = proposal)
                requireCurrent(); owner.requireCurrent()
                decision is ApprovalDecision.Denied ||
                    !ApprovalPolicyGuard.remainsPermitted(c.perms, "doom_loop", prepared.wireName, c.permissionScope)
            }
        }
    }
}

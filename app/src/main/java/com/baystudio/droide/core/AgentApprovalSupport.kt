package com.baystudio.droide.core

object AgentApprovalSupport {
    suspend fun doomLoopDenied(
        approvals: ApprovalManager,
        permissions: PermissionEngine,
        permissionScope: String,
        toolName: String,
        rawArgs: String,
        provenance: AgentRequestProvenance,
        arguments: kotlinx.serialization.json.JsonObject? = null,
        commandSnapshot: BackgroundCommandManager.Snapshot? = null,
    ): Boolean = when (permissions.decide("doom_loop", toolName, permissionScope)) {
        PermEffect.ALLOW -> false
        PermEffect.DENY -> true
        PermEffect.ASK -> {
            if (AgentCommandSessions.isQuietObservation(toolName, arguments, commandSnapshot)) false else {
                val decision = approvals.requestApproval(
                    kind = toolName,
                    summary = "Detected 3x repetition ($toolName) — continue?",
                    detail = rawArgs.take(1_000),
                    action = "doom_loop",
                    resource = toolName,
                    permissionScope = permissionScope,
                    provenance = provenance,
                )
                decision is ApprovalDecision.Denied ||
                    !ApprovalPolicyGuard.remainsPermitted(permissions, "doom_loop", toolName, permissionScope)
            }
        }
    }
}

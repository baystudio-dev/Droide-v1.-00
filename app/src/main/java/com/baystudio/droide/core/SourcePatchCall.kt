package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Bounded compatibility execution, not a file-backed/unlimited patch engine. */

internal class SourcePatchCall(
    private val input: PreparedToolInput.SourcePatch,
    private val journal: ToolCallHistoryJournal,
    private val pending: ToolCallHistoryJournal.Pending,
): SourceCallLifecycle {
    override var settledResult: JsonObject? = null
        private set
    private var invoked=false
    private var execution:ToolCallHistoryJournal.Execution?=null
    private var completion:ToolCallHistoryJournal.PatchCompletion?=null

    suspend fun execute(c:AgentTools.Ctx,hooks:AgentHookLifecycleBridge,owner:HookInvocationAuthority,
        responseOwner:OwnedResponseContentStore.Owner,repeatOrdinal:Int,requireCurrent:()->Unit):SourceCallLifecycle.Outcome {
        check(!invoked);invoked=true
        val task=currentCoroutineContext()
        val root=ProjectCopyEvidence.root(c.workDir)
        fun current() {task.ensureActive();requireCurrent();owner.requireCurrent();check(ProjectCopyEvidence.root(c.workDir)==root)}
        current()
        require(responseOwner.sessionId==pending.locator.sessionId && responseOwner.runId==pending.locator.runId && responseOwner.callId==pending.locator.callId)
        require(c.mode==owner.mode && c.provenance==owner.provenance && c.permissionScope==owner.permissionScope)
        AgentToolRequestPolicy.denial("apply_patch",c.activeToolNames)?.let {return deny(it)}
        if(!c.perms.toolAllowed("apply_patch")) return deny("DENIED (agent profile): apply_patch is not available")
        AgentModePolicy.check(c.mode,"apply_patch")?.let {return deny(it)}
        input.original.verify(input.session)
        if(repeatOrdinal>=3) {
            val denied=when(c.perms.decide("doom_loop","apply_patch",c.permissionScope)) {
                PermEffect.DENY->true
                PermEffect.ALLOW->false
                PermEffect.ASK->c.approvals.requestApproval("apply_patch","Repeated patch request — continue?",
                    "Original arguments SHA-256: ${input.original.sha256}. Patch target approval still applies.",
                    action="doom_loop",resource="apply_patch",permissionScope=c.permissionScope,provenance=c.provenance,
                    isStillCurrent=owner.isStillCurrent,ownerAdmission=owner.approvalAdmission) is ApprovalDecision.Denied
            }
            current()
            if(denied || !ApprovalPolicyGuard.remainsPermitted(c.perms,"doom_loop","apply_patch",c.permissionScope)) return deny("DENIED (doom-loop guard): apply_patch")
        }
        val hookInput=HookToolInputSource.admit(input.session,input.original.handle,::current)
        val output=hooks.toolSource("apply_patch",hookInput,owner=owner) {
            current()
            val started=journal.beginPatchInvocation(pending,::current)
            execution=started // retain even if dispatcher cancellation discards the result
            journal.invokePatch(started,input.prepared,c,::current) { completion=it }.output
        }
        // A blocked pre-hook never entered the tool. A completed invocation's exact receipt, not a
        // post-hook rewrite, is authoritative for replay and duplicate prevention.
        if(execution==null) return deny(output)
        val result=withContext(NonCancellable+Dispatchers.IO) {
            journal.checkpointPatchCompletion(checkNotNull(execution),checkNotNull(completion)).also {settledResult=it}
        }
        val exact=result.getValue("content").jsonPrimitive.content
        return SourceCallLifecycle.Outcome(exact,exact.startsWith("ERROR") || exact.startsWith("DENIED"))
    }
    private suspend fun deny(output:String):SourceCallLifecycle.Outcome {
        withContext(Dispatchers.IO) {settleSkipped(output)}
        return SourceCallLifecycle.Outcome(output,true)
    }
    override fun settleSkipped(output:String):JsonObject {
        check(execution==null && settledResult==null)
        return buildJsonObject {put("role","tool");put("tool_call_id",pending.locator.callId);put("content",output)}.also {
            journal.settleWithoutExecution(pending,it);settledResult=it
        }
    }
    override suspend fun checkpointInterrupted():JsonObject?=withContext(NonCancellable+Dispatchers.IO) {
        settledResult?.let {return@withContext it}
        val started=execution
        val result=if(started==null) journal.checkpointNoExecution(pending) else {
            val returned=completion ?: return@withContext null // unknown effects; never auto-repeat
            journal.checkpointPatchCompletion(started,returned)
        }
        settledResult=result;result
    }
}

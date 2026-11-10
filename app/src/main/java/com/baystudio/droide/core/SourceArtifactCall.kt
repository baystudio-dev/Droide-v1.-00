package com.baystudio.droide.core

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Runtime-only source artifact invocation. No file-edit or restored execution capability. */
internal class SourceArtifactCall(
    private val input: PreparedToolInput.SourceArtifact,
    private val journal: ToolCallHistoryJournal,
    private val pending: ToolCallHistoryJournal.Pending,
) : SourceCallLifecycle {
    override var settledResult: JsonObject? = null
        private set
    private var invoked = false
    private var execution: ToolCallHistoryJournal.Execution? = null
    private var publisher: SourceArtifactPublisher? = null
    private var observation: SourceArtifactPublisher.Observation? = null
    private var receipt: SourceArtifactPublisher.Receipt? = null
    private var publicationOwner: OwnedResponseContentStore.Owner? = null
    private val operationId = UUID.randomUUID().toString()

    suspend fun execute(c: AgentTools.Ctx, hooks: AgentHookLifecycleBridge, owner: HookInvocationAuthority,
        responseOwner: OwnedResponseContentStore.Owner, repeatOrdinal: Int, requireCurrent: () -> Unit): SourceCallLifecycle.Outcome {
        check(!invoked); invoked=true
        val prepared=input.prepared
        val task=currentCoroutineContext()
        val originalRoot=ProjectCopyEvidence.root(c.workDir)
        publicationOwner=responseOwner
        fun current() {
            task.ensureActive();requireCurrent();owner.requireCurrent()
            check(ProjectCopyEvidence.root(c.workDir)==originalRoot) { "Artifact workspace changed" }
        }
        current()
        require(responseOwner.sessionId==pending.locator.sessionId && responseOwner.runId==pending.locator.runId && responseOwner.callId==pending.locator.callId)
        require(c.mode==owner.mode && c.provenance==owner.provenance && c.permissionScope==owner.permissionScope)
        if(!c.perms.toolAllowed("artifact")) return settle(SourceCallLifecycle.Outcome("DENIED (agent profile): artifact is not available",true))
        AgentModePolicy.check(c.mode,"artifact")?.let {return settle(SourceCallLifecycle.Outcome(it,true))}
        prepared.originalArguments.verify(input.session);prepared.content.verify(input.session)
        val proposal=withContext(Dispatchers.IO) {ProposedFileContent.admit(input.session,prepared.content,::current)}
        if(repeatOrdinal>=3) {
            val denied=when(c.perms.decide("doom_loop","artifact",c.permissionScope)) {
                PermEffect.DENY -> true
                PermEffect.ALLOW -> false
                PermEffect.ASK -> c.approvals.requestApproval("artifact","Repeated artifact request — continue?",
                    "Original arguments SHA-256: ${input.original.sha256}. This permits continuation only; artifact creation permission still applies.",
                    action="doom_loop",resource="artifact",permissionScope=c.permissionScope,provenance=c.provenance,
                    proposedContent=proposal,isStillCurrent=owner.isStillCurrent,ownerAdmission=owner.approvalAdmission) is ApprovalDecision.Denied
            }
            current()
            if(denied || !ApprovalPolicyGuard.remainsPermitted(c.perms,"doom_loop","artifact",c.permissionScope))
                return settle(SourceCallLifecycle.Outcome("DENIED (doom-loop guard): stop repetition artifact",true))
        }
        val hookInput=HookToolInputSource.admit(input.session,input.original.handle,::current)
        var result:SourceCallLifecycle.Outcome?=null
        try {
            val hookOutput=hooks.toolSource("artifact",hookInput,owner=owner) {
                current()
                val denial=when(c.perms.decide("edit",prepared.originalType,c.permissionScope)) {
                    PermEffect.DENY -> "DENIED (policy): edit ${prepared.originalType}"
                    PermEffect.ALLOW -> null
                    PermEffect.ASK -> {
                        val preview=withContext(Dispatchers.IO) {proposal.readPage(0,::current).text}
                        val decision=c.approvals.requestApproval("artifact","Create artifact",
                            "Type: ${prepared.originalType}\nContent: ${prepared.content.bytes} bytes; SHA-256 ${prepared.content.sha256}.\nPartial preview; read or export the full content before approving.\n\n$preview",
                            action="edit",resource=prepared.originalType,permissionScope=c.permissionScope,provenance=c.provenance,
                            proposedContent=proposal,isStillCurrent=owner.isStillCurrent,ownerAdmission=owner.approvalAdmission)
                        if(decision is ApprovalDecision.Denied) "DENIED by user: edit ${prepared.originalType}" else null
                    }
                }
                if(denial!=null) {result=SourceCallLifecycle.Outcome(denial,true);return@toolSource denial}
                fun admitted() {
                    current()
                    check(c.perms.toolAllowed("artifact") && AgentModePolicy.check(c.mode,"artifact")==null &&
                        ApprovalPolicyGuard.remainsPermitted(c.perms,"edit",prepared.originalType,c.permissionScope)) {"Artifact permission changed"}
                }
                admitted()
                withContext(Dispatchers.IO) {
                    // Reserve the operation identity before any workspace staging. Prompt cancellation may lose a
                    // return value but never these captured lifecycle references.
                    journal.reserveArtifactOperation(pending,operationId,prepared.originalType)
                    val backend=SourceArtifactPublisher(c.workDir,journal.artifactStorage(),input.session.scratchResources().second)
                    publisher=backend
                    val observed=backend.prepare(operationId,responseOwner,input.original.sha256,prepared.originalType,
                        input.session,prepared.content.handle,::admitted)
                    observation=observed
                    journal.approveArtifact(pending,observed,backend,::admitted)
                    execution=journal.beginExecution(pending,::admitted)
                    receipt=backend.publish(observed,::admitted,::admitted)
                }
                val observed=checkNotNull(observation)
                result=SourceCallLifecycle.Outcome("Created artifact ${observed.id} (${prepared.originalType}) at ${c.workDir.resolve(observed.path).absolutePath}" +
                    if (receipt?.durabilityConfirmed == true) "" else "; publication verified but durability confirmation is unavailable. Do not repeat this operation.",false)
                checkNotNull(result).output
            }
            if(result==null) result=SourceCallLifecycle.Outcome(hookOutput,true)
        } catch (failure: SourceBackedHookTransportUnavailableException) {
            // Only a verified publication can convert a post-hook error into a committed outcome.
            if(receipt==null) throw failure
            result=SourceCallLifecycle.Outcome("Artifact created at ${checkNotNull(observation).path}; lifecycle hook transport unavailable after publication. Do not repeat this operation.",false)
        }
        return settle(checkNotNull(result))
    }

    private suspend fun settle(result:SourceCallLifecycle.Outcome):SourceCallLifecycle.Outcome {
        val entry=entry(result.output)
        val persisted=withContext(Dispatchers.IO) {
            val started=execution
            val actual=if(started!=null) {
                check(!result.rejected);journal.settleArtifact(started,checkNotNull(receipt),entry)
            } else {journal.settleWithoutExecution(pending,entry);entry}
            settledResult=actual
            actual
        }
        return result.copy(output=persisted.getValue("content").jsonPrimitive.content)
    }
    override fun settleSkipped(output:String):JsonObject {
        check(execution==null && settledResult==null)
        return entry(output).also {journal.settleWithoutExecution(pending,it);settledResult=it}
    }
    override suspend fun checkpointInterrupted():JsonObject?=withContext(NonCancellable+Dispatchers.IO) {
        settledResult?.let {return@withContext it}
        val started=execution
        val result=if(started==null) journal.checkpointNoExecution(pending) else {
            val verified=receipt ?: when(val recovery=publisher?.inspect(operationId,checkNotNull(publicationOwner),input.original.sha256)) {
                is SourceArtifactPublisher.Recovery.Published -> recovery.receipt
                is SourceArtifactPublisher.Recovery.Historical -> recovery.receipt
                else -> return@withContext null
            }
            journal.checkpointArtifactCommitted(started,verified)
        }
        settledResult=result;result
    }
    private fun entry(output:String)=buildJsonObject {
        put("role","tool");put("tool_call_id",pending.locator.callId);put("content",output)
    }
}

package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Source transport around the existing single-offer stdin consumer, not a new shell/PTY protocol. */
internal class SourceStdinCall(
    private val input:PreparedToolInput.SourceStdin,
    private val journal:ToolCallHistoryJournal,
    private val pending:ToolCallHistoryJournal.Pending,
):SourceCallLifecycle {
    override var settledResult:JsonObject?=null
        private set
    private var invoked=false
    private var execution:ToolCallHistoryJournal.Execution?=null
    private var completion:ToolCallHistoryJournal.StdinCompletion?=null

    suspend fun execute(c:AgentTools.Ctx,hooks:AgentHookLifecycleBridge,owner:HookInvocationAuthority,
        responseOwner:OwnedResponseContentStore.Owner,repeatOrdinal:Int,requireCurrent:()->Unit):SourceCallLifecycle.Outcome {
        check(!invoked);invoked=true
        val task=currentCoroutineContext()
        val root=ProjectCopyEvidence.root(c.workDir)
        // Passed under manager lock: runtime-only, no filesystem/source IO.
        fun runtimeCurrent() {task.ensureActive();requireCurrent();owner.requireCurrent()}
        fun current() {runtimeCurrent();check(ProjectCopyEvidence.root(c.workDir)==root)}
        current()
        require(responseOwner.sessionId==pending.locator.sessionId && responseOwner.runId==pending.locator.runId && responseOwner.callId==pending.locator.callId)
        require(c.mode==owner.mode && c.provenance==owner.provenance && c.permissionScope==owner.permissionScope)
        AgentToolRequestPolicy.denial("write_stdin",c.activeToolNames)?.let {return deny(it)}
        if(!c.perms.toolAllowed("write_stdin")) return deny("DENIED (agent profile): write_stdin is not available")
        AgentModePolicy.check(c.mode,"write_stdin")?.let {return deny(it)}
        input.original.verify(input.session)
        if(repeatOrdinal>=3) {
            val args=StreamedStdinArgumentExtractor.materialize(input.session,input.prepared,::current)
            val id=args.getValue("session_id").jsonPrimitive.content
            // Preserve the existing quiet-poll exemption; original JSON is never fabricated/clipped.
            val denied=AgentApprovalSupport.doomLoopDenied(c.approvals,c.perms,owner.permissionScope,"write_stdin",
                "Source arguments SHA-256: ${input.original.sha256}",owner.provenance,
                arguments=args,commandSnapshot=c.backgroundCommands?.snapshot(id))
            current();if(denied)return deny("DENIED (doom-loop guard): write_stdin")
        }
        val hookInput=HookToolInputSource.admit(input.session,input.original.handle,::current)
        val output=try {
            hooks.toolSource("write_stdin",hookInput,owner=owner) {
                current()
                journal.invokeStdin(pending,input.prepared,c,::current,::runtimeCurrent,
                    started={execution=it},completed={completion=it}).output
            }
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {
            if(execution!=null) throw failure
            return deny("ERROR: ${failure.message ?: "Command input preparation failed"}. No input offer was attempted by this tool call.")
        }
        if(execution==null)return deny(output) // pre-hook denied; the executor never entered
        val result=withContext(NonCancellable+Dispatchers.IO) {
            journal.checkpointStdinCompletion(checkNotNull(execution),checkNotNull(completion)).also {settledResult=it}
        }
        val exact=result.getValue("content").jsonPrimitive.content
        return SourceCallLifecycle.Outcome(exact,AgentToolResultSemantics.classify("write_stdin",exact).isFailure)
    }
    private suspend fun deny(output:String):SourceCallLifecycle.Outcome {
        withContext(Dispatchers.IO){settleSkipped(output)}
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
        val result=if(started==null)journal.checkpointNoExecution(pending) else {
            val returned=completion
            if(returned==null) {journal.checkpointStdinUnresolved(started);return@withContext null}
            try {journal.checkpointStdinCompletion(started,returned)}
            catch(uncertain:ToolCallHistoryJournal.StdinOutcomeUncertainException){return@withContext null}
        }
        settledResult=result;result
    }
}

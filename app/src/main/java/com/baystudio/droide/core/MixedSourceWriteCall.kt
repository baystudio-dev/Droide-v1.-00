package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Runtime-only execution ownership for one source call in a native mixed assistant group. */
internal class MixedSourceWriteCall(
    val input: PreparedToolInput.SourceFile,
    private val journal: ToolCallHistoryJournal,
    private val pending: ToolCallHistoryJournal.Pending,
) : SourceCallLifecycle {
    init {
        require(input.prepared.wireName==pending.locator.name && input.prepared.callId==pending.locator.callId) {
            "Source file wire identity differs from the private pending call"
        }
    }
    private var execution: ToolCallHistoryJournal.Execution? = null
    private var observation: StreamedTextEditObservation? = null
    private var editCall: AgentStreamedTextEditCall? = null
    override var settledResult: JsonObject? = null
        private set

    private var liveObservation: StreamedLiveEditObservation? = null

    suspend fun execute(c: AgentTools.Ctx, hooks: AgentHookLifecycleBridge,
        owner: HookInvocationAuthority, call: AgentStreamedTextEditCall,
        repeatOrdinal: Int, requireCurrent: ()->Unit): SourceCallLifecycle.Outcome {
        check(editCall==null) { "A source invocation cannot execute twice" };editCall=call
        val source = input.prepared
        val result = if(repeatOrdinal>=3 && AgentStreamedApprovalSupport.doomLoopDenied(
            source,input.session,c,owner,requireCurrent)) {
            SourceCallLifecycle.Outcome("DENIED (doom-loop guard): stop repetition ${source.wireName}", true)
        } else {
            requireCurrent()
            // Choose once from editor authority. The chosen wrapper revalidates and never falls back.
            val requested = source.requestedPath.replace('\\', '/')
            require(!requested.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(requested) &&
                !requested.endsWith('/') && requested.split('/').none { it == "." || it == ".." })
            val path = requested.split('/').filter { it.isNotEmpty() }.joinToString("/")
            require(path.isNotEmpty())
            if (!c.perms.toolAllowed(source.tool.wireName))
                return settleOutcome(SourceCallLifecycle.Outcome("DENIED (agent profile): ${source.wireName} is not available", true))
            AgentModePolicy.check(c.mode, source.tool.wireName)?.let { return settleOutcome(SourceCallLifecycle.Outcome(it, true)) }
            if (c.perms.decide("edit", path, c.permissionScope) == PermEffect.DENY)
                return settleOutcome(SourceCallLifecycle.Outcome("DENIED (policy): edit $path", true))
            c.files.resolveChecked(path)
            val snapshot = c.documentAuthority?.snapshot(path)
            if (source.expectedDocument != null || snapshot != null) {
                if (source.expectedDocument == null) {
                    val before = checkNotNull(snapshot)
                    return settleOutcome(SourceCallLifecycle.Outcome("The target is open in the editor. Review its content and retry with expected_document_id=${before.documentId}, expected_revision=${before.revision}, expected_change_version=${before.changeVersion}. No write was performed and no disk fallback was used.", true))
                }
                val live = AgentStreamedLiveWriteTool.execute(source,input.session,c,hooks,owner,requireCurrent) { observed ->
                    liveObservation=observed
                    withContext(Dispatchers.IO) {
                        journal.approveLive(pending,input.original.sha256,observed,requireCurrent)
                        execution=journal.beginExecution(pending,requireCurrent)
                    }
                }
                SourceCallLifecycle.Outcome(live.output, live is AgentStreamedLiveWriteTool.LiveResult.Rejected)
            } else {
                val disk = AgentStreamedFileTool.execute(source,input.session,c,hooks,owner,call,requireCurrent) { observed ->
                    observation=observed
                    withContext(Dispatchers.IO) {
                        journal.approveStreamed(pending,input.original.sha256,observed,requireCurrent)
                        execution=journal.beginExecution(pending,requireCurrent)
                    }
                }
                SourceCallLifecycle.Outcome(disk.output, disk is AgentStreamedWriteTool.Result.Rejected)
            }
        }
        return settleOutcome(result)
    }

    private suspend fun settleOutcome(result: SourceCallLifecycle.Outcome): SourceCallLifecycle.Outcome {
        val entry=entry(result.output)
        withContext(Dispatchers.IO) {
            val started = execution
            if(started != null) journal.settle(started,entry)
            else journal.settleWithoutExecution(pending,entry)
            settledResult=entry
        }
        return result
    }

    override fun settleSkipped(output: String): JsonObject {
        check(execution==null && settledResult==null)
        return entry(output).also { journal.settleWithoutExecution(pending,it);settledResult=it }
    }

    /** Returns null for unknown execution: that protocol slot must remain unresolved, never guessed. */
    override suspend fun checkpointInterrupted(): JsonObject? = withContext(NonCancellable + Dispatchers.IO) {
        settledResult?.let { return@withContext it }
        val started=execution
        val saved=if(started==null) journal.checkpointNoExecution(pending) else {
            val live = liveObservation
            if (live != null) {
                if (live.replacement.publication == null) return@withContext null
                journal.checkpointLiveCommitted(started, live.publicationProof())
            } else {
                val observed=observation ?: return@withContext null
                val proof=editCall?.publicationProof(observed) ?: return@withContext null
                journal.checkpointCommitted(started,proof)
            }
        }
        settledResult=saved;saved
    }

    private fun entry(output:String)=buildJsonObject {
        put("role","tool");put("tool_call_id",pending.locator.callId);put("content",output)
    }
}

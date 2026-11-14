package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One supported vertical route; not a replacement for the general54-tool executor.
 * Caller durably records the completed call before invocation. No streamed deltas execute here.
 */
internal object AgentStreamedWriteTool {
    sealed interface Result {
        val output: String
        data class Rejected(override val output: String) : Result
        data class Committed(override val output: String,
            val proof: AgentStreamedTextEditCall.PublicationProof,
            val synchronizationPending: Boolean) : Result
    }

    suspend fun execute(
        prepared: StreamedFileArgumentExtractor.Prepared,
        sourceSession: OwnedResponseContentStore.Session,
        c: AgentTools.Ctx,
        hooks: AgentHookLifecycleBridge,
        hookOwner: HookInvocationAuthority,
        call: AgentStreamedTextEditCall,
        requireAuthority: () -> Unit,
        onApproved: suspend (StreamedTextEditObservation) -> Unit,
    ): Result {
        require(prepared.tool == StreamedFileArgumentExtractor.Tool.WRITE_FILE)
        return AgentStreamedFileTool.execute(prepared, sourceSession, c, hooks, hookOwner, call, requireAuthority, onApproved)
    }
}

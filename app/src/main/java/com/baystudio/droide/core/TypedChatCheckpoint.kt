package com.baystudio.droide.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

/** Explicit source-backed request history. Legacy apiHistory is NOT authoritative when present. */
@Serializable
data class TypedChatCheckpoint(
    val version: Int = 1,
    val target: TypedChatTarget,
    val live: List<TypedChatEntry>,
    val exact: List<TypedChatEntry>,
) {
    init { require(version == 1) }
}
@Serializable
data class TypedChatTarget(val providerId: String,val modelId: String,val endpointIdentity: String) {
    init { require(providerId.isNotBlank() && modelId.isNotBlank() && endpointIdentity.matches(Regex("endpoint-sha256:[a-f0-9]{64}"))) }
    internal fun runtime() = ProviderReplayTarget(providerId,modelId,ProviderReplayTarget.Protocol.OPENAI_CHAT,endpointIdentity)
    companion object {
        internal fun of(target:ProviderReplayTarget):TypedChatTarget {
            require(target.protocol==ProviderReplayTarget.Protocol.OPENAI_CHAT)
            return TypedChatTarget(target.providerId,target.modelId,target.endpointIdentity)
        }
    }
}
@Serializable
sealed class TypedChatEntry {
    @Serializable @SerialName("legacy") data class Legacy(val message:JsonObject):TypedChatEntry()
    @Serializable @SerialName("owned_assistant") data class OwnedAssistant(
        val registryId:String,val runId:String,val responseId:String,val callIds:List<String>,
    ):TypedChatEntry() {
        init {
            require(registryId.matches(Regex("[a-f0-9-]{36}")))
            require(runId.isNotBlank() && runId.length<=512 && responseId.isNotBlank() && responseId.length<=512)
            require(callIds.size<=1024 && callIds.all { it.isNotBlank() && it.length<=512 } && callIds.toSet().size==callIds.size)
        }
    }
    @Serializable @SerialName("owned_chat_turn") data class OwnedTurn(
        val registryId:String,val runId:String,val responseId:String,val toolCallCount:Int,
    ):TypedChatEntry() {
        init {
            require(registryId.matches(Regex("[a-f0-9-]{36}")))
            require(runId.isNotBlank() && runId.length<=512 && responseId.isNotBlank() && responseId.length<=512)
            require(toolCallCount in 0..1024)
        }
    }
}

/** All capabilities are resolved from the independent current session, never from persisted entry IDs. */
internal class TypedChatHistory(private val registry:CanonicalChatTurnRegistry,private val nativeAssistants:NativeAssistantRegistry=registry.nativeAssistantRegistry()) {
    fun verify(checkpoint:TypedChatCheckpoint,sessionId:String) {
        PathSecurity.safeLeafName(sessionId)
        val nativeExact=checkpoint.exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().toSet()
        require(checkpoint.live.filterIsInstance<TypedChatEntry.OwnedAssistant>().all { it in nativeExact })
        (checkpoint.exact+checkpoint.live).filterIsInstance<TypedChatEntry.OwnedAssistant>().distinct().forEach { resolveNative(it,sessionId,checkpoint.target.runtime()) }
        val exact=checkpoint.exact.filterIsInstance<TypedChatEntry.OwnedTurn>().toSet()
        require(checkpoint.live.filterIsInstance<TypedChatEntry.OwnedTurn>().all { it in exact }) { "Live source history is missing from exact history" }
        (checkpoint.exact+checkpoint.live).filterIsInstance<TypedChatEntry.OwnedTurn>().distinct().forEach { resolve(it,sessionId,checkpoint.target.runtime()) }
    }
    private fun resolveNative(entry:TypedChatEntry.OwnedAssistant,sessionId:String,target:ProviderReplayTarget):NativeAssistantRegistry.BoundAssistant {
        val bound=nativeAssistants.reopen(entry.registryId,OwnedResponseContentStore.Owner(sessionId,entry.runId,entry.responseId),target)
        require(bound.callIds==entry.callIds) { "Native assistant call identity changed" }
        return bound
    }
    private fun resolve(entry:TypedChatEntry.OwnedTurn,sessionId:String,target:ProviderReplayTarget):CanonicalChatTurnRegistry.BoundTurn {
        val turn=registry.reopen(entry.registryId,OwnedResponseContentStore.Owner(sessionId,entry.runId,entry.responseId),target)
        require(turn.calls.size==entry.toolCallCount) { "Typed history group identity changed" }
        return turn
    }
    fun live(checkpoint:TypedChatCheckpoint,sessionId:String,target:ProviderReplayTarget):List<SourceAwareChatHistoryWriter.Item> {
        require(checkpoint.target.runtime()==target) { "This source-backed conversation cannot switch provider/model/endpoint without an explicit migration" }
        verify(checkpoint,sessionId)
        return checkpoint.live.map { when(it) {
            is TypedChatEntry.Legacy -> SourceAwareChatHistoryWriter.Item.Legacy(it.message)
            is TypedChatEntry.OwnedAssistant -> SourceAwareChatHistoryWriter.Item.OwnedAssistant(resolveNative(it,sessionId,target))
            is TypedChatEntry.OwnedTurn -> SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup(resolve(it,sessionId,target))
        } }
    }
    /** Existing forkHistoryEnd counts protocol messages. A group cannot be split at assistant/results. */
    fun fork(checkpoint:TypedChatCheckpoint,sourceSession:String,targetSession:String,
        targetRegistry:CanonicalChatTurnRegistry,end:Int?=null):TypedChatCheckpoint {
        verify(checkpoint,sourceSession)
        val selected=if(end==null)checkpoint.exact else takeBoundary(checkpoint.exact,end)
        // A selectable fork is a resumable conversation, not an unresolved recovery backup. Check every
        // selected group before publishing any target-owned copies.
        selected.filterIsInstance<TypedChatEntry.OwnedTurn>().forEach { entry ->
            val turn = resolve(entry,sourceSession,checkpoint.target.runtime())
            turn.calls.forEach { call -> turn.journal.completedArguments(call);turn.journal.verifyResult(call) }
        }
        SourceAwareChatHistoryWriter.validateCompleteGroups(selected.map { entry -> when(entry) {
            is TypedChatEntry.Legacy -> SourceAwareChatHistoryWriter.Item.Legacy(entry.message)
            is TypedChatEntry.OwnedAssistant -> SourceAwareChatHistoryWriter.Item.OwnedAssistant(resolveNative(entry, sourceSession, checkpoint.target.runtime()))
            is TypedChatEntry.OwnedTurn -> SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup(resolve(entry, sourceSession, checkpoint.target.runtime()))
        } }, checkpoint.target.runtime())
        TypedChatForkBoundary.requireComplete(selected)
        val nativeCopies=mutableMapOf<TypedChatEntry.OwnedAssistant,TypedChatEntry.OwnedAssistant>()
        val copies=mutableMapOf<TypedChatEntry.OwnedTurn,TypedChatEntry.OwnedTurn>()
        fun copy(entry:TypedChatEntry):TypedChatEntry = when(entry) {
            is TypedChatEntry.Legacy -> entry
            is TypedChatEntry.OwnedAssistant -> nativeCopies.getOrPut(entry) {
                val owner=OwnedResponseContentStore.Owner(sourceSession,entry.runId,entry.responseId)
                entry.copy(registryId=nativeAssistants.copyForFork(entry.registryId,owner,checkpoint.target.runtime(),targetRegistry.nativeAssistantRegistry(),owner.copy(sessionId=targetSession)))
            }
            is TypedChatEntry.OwnedTurn -> copies.getOrPut(entry) {
                val owner=OwnedResponseContentStore.Owner(sourceSession,entry.runId,entry.responseId)
                entry.copy(registryId=registry.copyForFork(entry.registryId,owner,checkpoint.target.runtime(),targetRegistry,owner.copy(sessionId=targetSession)))
            }
        }
        val exact=selected.map(::copy)
        return checkpoint.copy(live=if(end==null)checkpoint.live.map(::copy) else exact,exact=exact)
    }
    /** Read-only recovery from privately bound SETTLED source calls. Never runs a tool, grants
     * permission, replaces a contradictory workspace result, or invents an ordinary-tool result. */
    fun reconcileSettledSources(checkpoint: TypedChatCheckpoint, sessionId: String): TypedChatCheckpoint {
        verify(checkpoint, sessionId)
        fun reconcile(entries: List<TypedChatEntry>): List<TypedChatEntry> {
            val output = ArrayList<TypedChatEntry>()
            var i = 0
            while (i < entries.size) {
                val entry = entries[i++]; output += entry
                if (entry !is TypedChatEntry.OwnedAssistant) continue
                val bound = resolveNative(entry, sessionId, checkpoint.target.runtime())
                val seen = mutableSetOf<String>()
                while (i < entries.size) {
                    val next = entries[i] as? TypedChatEntry.Legacy ?: break
                    val role = next.message["role"] as? kotlinx.serialization.json.JsonPrimitive
                    if (role?.content != "tool") break
                    val id = (next.message["tool_call_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    require(id != null && id in bound.callIds && seen.add(id)) { "Native recovery result identity mismatch" }
                    bound.verifyToolResult(id, next.message)
                    output += next; i++
                }
                bound.callIds.filter { it !in seen }.forEach { id ->
                    bound.settledSourceResult(id)?.let { output += TypedChatEntry.Legacy(it) }
                }
            }
            return output
        }
        return checkpoint.copy(live = reconcile(checkpoint.live), exact = reconcile(checkpoint.exact))
    }
    fun appendLegacy(checkpoint:TypedChatCheckpoint,message:JsonObject):TypedChatCheckpoint {
        SessionJsonSize.requireSupportedDepth(message)
        val entry=TypedChatEntry.Legacy(message)
        return checkpoint.copy(live=checkpoint.live+entry,exact=checkpoint.exact+entry)
    }
    fun appendOwned(checkpoint:TypedChatCheckpoint,entry:TypedChatEntry.OwnedTurn,sessionId:String):TypedChatCheckpoint {
        resolve(entry,sessionId,checkpoint.target.runtime())
        require(checkpoint.exact.filterIsInstance<TypedChatEntry.OwnedTurn>().none { it.registryId==entry.registryId }) { "Canonical turn was already appended" }
        return checkpoint.copy(live=checkpoint.live+entry,exact=checkpoint.exact+entry)
    }
    fun appendNative(checkpoint:TypedChatCheckpoint,entry:TypedChatEntry.OwnedAssistant,sessionId:String):TypedChatCheckpoint {
        resolveNative(entry,sessionId,checkpoint.target.runtime())
        require(checkpoint.exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().none { it.registryId==entry.registryId })
        return checkpoint.copy(live=checkpoint.live+entry,exact=checkpoint.exact+entry)
    }
    fun validateCompleteRequest(checkpoint:TypedChatCheckpoint,sessionId:String,target:ProviderReplayTarget,
        checkCancelled:()->Unit={}):List<SourceAwareChatHistoryWriter.Item> = live(checkpoint,sessionId,target).also {
        SourceAwareChatHistoryWriter.validateCompleteGroups(it,target,checkCancelled)
    }
    companion object {
        fun protocolWidth(entries:List<TypedChatEntry>):Int = entries.fold(0) { count,entry ->
            Math.addExact(count,when(entry) { is TypedChatEntry.Legacy->1;is TypedChatEntry.OwnedAssistant->1;is TypedChatEntry.OwnedTurn->1+entry.toolCallCount })
        }
        fun requireLegacy(checkpoint:TypedChatCheckpoint?) {
            check(checkpoint==null) { "Source-backed history requires the typed request route; legacy projection must not be sent" }
        }
        fun takeBoundary(entries:List<TypedChatEntry>,end:Int):List<TypedChatEntry> {
            require(end>=0);var count=0;val selected=ArrayList<TypedChatEntry>()
            for(entry in entries) {
                if(count==end) { TypedChatForkBoundary.requireComplete(selected);return selected }
                val width=when(entry) { is TypedChatEntry.Legacy->1;is TypedChatEntry.OwnedAssistant->1;is TypedChatEntry.OwnedTurn->1+entry.toolCallCount }
                require(width<=end-count) { "Fork boundary splits a source-backed assistant/tool-result group" }
                count+=width;selected+=entry
            }
            require(count==end) { "Fork boundary is outside exact typed history" };TypedChatForkBoundary.requireComplete(selected);return selected
        }
    }
}

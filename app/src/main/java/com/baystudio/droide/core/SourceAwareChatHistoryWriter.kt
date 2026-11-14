package com.baystudio.droide.core

import java.io.OutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/** An explicit request-history route. Never insert capability markers in legacy JsonObjects. */
internal object SourceAwareChatHistoryWriter {
    sealed class Item {
        class Legacy(val message: JsonObject) : Item()
        class OwnedAssistant(val bound: NativeAssistantRegistry.BoundAssistant) : Item()
        /** Native replay is admitted by the Chat adapter, with original reasoning/refusal and calls.
         * The caller preserves ordering; settled results are emitted immediately after this assistant.
         */
        class OwnedAssistantGroup(val turn: CanonicalChatTurnRegistry.BoundTurn) : Item() {
            val responseSession get() = turn.responseSession
            val assistant get() = turn.assistant
            val journal get() = turn.journal
            val calls get() = turn.calls
        }

    }
    /** Writes only the messages array; request builder owns model/tools/settings and budget selection.
     * Callers must select complete assistant/result groups, never budget individual tool results away.
     */
    fun writeMessages(items: List<Item>, target: ProviderReplayTarget, output: OutputStream,
        checkCancelled: () -> Unit = {}) {
        require(target.protocol == ProviderReplayTarget.Protocol.OPENAI_CHAT)
        validateCompleteGroups(items,target,checkCancelled)
        output.write('['.code)
        var first = true
        fun separator() { if (!first) output.write(','.code);first=false }
        items.forEach { item ->
            checkCancelled(); separator()
            when(item) {
                is Item.OwnedAssistant -> OwnedProviderReplayWriter.writePrivateObject(item.bound.responseSession,item.bound.assistant,target,output,checkCancelled)
                is Item.Legacy -> output.write(item.message.toString().toByteArray(Charsets.UTF_8))
                is Item.OwnedAssistantGroup -> {
                    OwnedProviderReplayWriter.writePrivateObject(item.responseSession,item.assistant,target,output,checkCancelled)
                    item.calls.forEach { call -> checkCancelled();separator();item.journal.writeResult(call,output) }
                }
            }
        }
        checkCancelled();output.write(']'.code)
    }
    fun validateCompleteGroups(items:List<Item>,target:ProviderReplayTarget,checkCancelled:()->Unit={}) {
        require(target.protocol==ProviderReplayTarget.Protocol.OPENAI_CHAT)
        // Preflight ALL groups before emitting any wire bytes. A transport failure later still requires
        // cancellation, not blind retry; no tool execution occurs in this serializer.
        var nativePending: MutableSet<String>? = null
        var privateNative: NativeAssistantRegistry.BoundAssistant? = null
        items.forEach { item ->
            checkCancelled()
            if(nativePending?.isEmpty()==true) { nativePending=null;privateNative=null }
            val pending=nativePending
            if(pending!=null) {
                require(item is Item.Legacy) { "Native assistant results are missing" }
                val role=(item.message["role"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val id=(item.message["tool_call_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                require(role=="tool" && id!=null && pending.remove(id)) { "Native assistant result identity mismatch" }
                privateNative?.verifyToolResult(requireNotNull(id),item.message)
            } else if(item is Item.Legacy) {
                val role=(item.message["role"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                require(role!="tool") { "Orphan or duplicate tool result" }
                if(role=="assistant") {
                    val calls=item.message["tool_calls"]
                    if(calls!=null && calls!=kotlinx.serialization.json.JsonNull) {
                        require(calls is kotlinx.serialization.json.JsonArray)
                        val ids=calls.map { call ->
                            requireNotNull(((call as? JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull).also { require(it.isNotBlank()) }
                        }
                        require(ids.toSet().size==ids.size)
                        nativePending=ids.toMutableSet().takeIf { it.isNotEmpty() }
                    }
                }
            } else if(item is Item.OwnedAssistant) {
                require(item.bound.assistant.target==target)
                item.bound.assistant.reference.verify(item.bound.responseSession)
                require(item.bound.callIds.toSet().size==item.bound.callIds.size)
                item.bound.requireSettledSources()
                privateNative=item.bound
                nativePending=item.bound.callIds.toMutableSet().takeIf { it.isNotEmpty() }
            }
            if (item is Item.OwnedAssistantGroup) {
                require(item.assistant.target == target)
                item.assistant.reference.verify(item.responseSession)
                require(item.calls.map { it.callId }.toSet().size == item.calls.size)
                require(item.calls.map { it.sessionId to it.runId }.toSet().size <= 1)
                item.calls.forEach { item.journal.completedArguments(it); item.journal.verifyResult(it) }
            }
        }
        require(nativePending.isNullOrEmpty()) { "Native assistant tool results are incomplete" }
    }
}

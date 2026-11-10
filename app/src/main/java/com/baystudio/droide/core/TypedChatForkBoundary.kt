package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Protocol metadata only. Callers stamp the semantic UI item at its actual admission site. */
internal object TypedChatForkBoundary {
    /** null means a valid but unfinished legacy tool batch, never a usable fork anchor. */
    fun completedEnd(entries: List<TypedChatEntry>): Int? = scan(entries) {}

    /** Valid earlier anchors survive an unfinished or malformed later tail. Width is the serialized
     * message width, not the number of typed entries (OwnedTurn is atomic). */
    fun completedPrefixWidths(entries: List<TypedChatEntry>): Set<Int> {
        val ends = mutableSetOf(0)
        try {
            scan(entries, completedWidth = { ends.add(it) }) {}
        } catch (_: IllegalArgumentException) {

        } catch (_: ArithmeticException) {
            // Overflow cannot produce a usable anchor.
        }
        return ends
    }

    private fun scan(entries: List<TypedChatEntry>, completedWidth: (Int) -> Unit = {},
        boundary: (Int) -> Unit): Int? {
        var pending: MutableSet<String>? = null
        var width = 0
        for ((index, entry) in entries.withIndex()) {
            if (pending?.isEmpty() == true) pending = null
            when (entry) {
                is TypedChatEntry.OwnedAssistant -> {
                    require(pending == null) { "Native assistant crosses an unfinished tool batch" }
                    pending = entry.callIds.toMutableSet().takeIf { it.isNotEmpty() }
                    width = Math.addExact(width, 1)
                }
                is TypedChatEntry.OwnedTurn -> {
                    require(pending == null) { "Owned turn crosses an unfinished legacy tool batch" }
                    width = Math.addExact(width, 1 + entry.toolCallCount)
                }
                is TypedChatEntry.Legacy -> {
                    val message = entry.message
                    val role = (message["role"] as? JsonPrimitive)?.contentOrNull
                    val waiting = pending
                    if (waiting != null) {
                        require(role == "tool") { "Legacy tool results are missing before the next message" }
                        val id = (message["tool_call_id"] as? JsonPrimitive)?.contentOrNull
                        require(!id.isNullOrBlank() && waiting.remove(id)) { "Unknown or duplicate legacy tool result" }
                    } else {
                        require(role in setOf("system", "developer", "user", "assistant")) { "Orphan tool result or unsupported history role" }
                        if (role == "assistant" && message.containsKey("tool_calls") && message["tool_calls"] != JsonNull) {
                            val calls = message["tool_calls"] as? JsonArray
                            require(calls != null) { "Malformed legacy tool-call list" }
                            if (calls.isNotEmpty()) {
                                val ids = mutableSetOf<String>()
                                for (call in calls) {
                                    val id = ((call as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                                    require(!id.isNullOrBlank() && ids.add(id)) { "Missing or duplicate legacy tool-call identity" }
                                }
                                pending = ids
                            }
                        }
                    }
                    width = Math.addExact(width, 1)
                }
            }
            if(pending.isNullOrEmpty()) { boundary(index+1); completedWidth(width) }
        }
        return width.takeIf { pending.isNullOrEmpty() }
    }

    fun completeGroups(entries: List<TypedChatEntry>): List<List<TypedChatEntry>> {
        val groups=mutableListOf<List<TypedChatEntry>>()
        var start=0
        val end=scan(entries) { next -> groups+=entries.subList(start,next).toList();start=next }
        requireNotNull(end) { "Incomplete assistant/tool group cannot be compacted" }
        return groups
    }

    fun requireComplete(entries: List<TypedChatEntry>): Int = requireNotNull(completedEnd(entries)) {
        "Fork boundary splits a legacy assistant/tool-result group"
    }

    fun stamp(message: AgentMessage, entries: List<TypedChatEntry>): AgentMessage =
        message.copy(forkHistoryEnd = completedEnd(entries))
}

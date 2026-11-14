package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object AgentHistory {
    /** boundedUi keeps a suffix. Rebase the frozen/live split when its prefix is evicted. */
    fun retainedBaseMessageCount(baseCount: Int, sourceSize: Int, retainedSize: Int): Int {
        require(sourceSize >= 0 && retainedSize in 0..sourceSize)
        return (baseCount.coerceIn(0, sourceSize) - (sourceSize - retainedSize)).coerceAtLeast(0)
    }

    data class InterruptedClosure(
        val apiEntries: List<JsonObject>,
        val messages: List<AgentMessage>,
    )

    fun closeInterruptedTools(
        unresolved: Map<String, String>,
        reason: String,
        terminalState: AgentStreamPartState,
        parts: List<AgentStreamPart>,
        activeStep: Int,
    ): InterruptedClosure {
        val api = mutableListOf<JsonObject>()
        val messages = mutableListOf<AgentMessage>()
        unresolved.forEach { (callId, toolName) ->
            api += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", callId)
                put("content", reason.take(12_000))
            }
            val source = parts.firstOrNull { it.toolCallId == callId }
            val durable = (source ?: AgentStreamPart(
                id = "interrupted:$callId",
                step = activeStep,
                kind = AgentStreamPartKind.TOOL,
                state = terminalState,
                title = toolName.ifBlank { "Tool" },
                toolName = toolName,
                toolCallId = callId,
            )).copy(
                state = terminalState,
                detail = source?.textEdit?.detail(terminalState, reason.take(500)) ?: reason.take(500),
                endedAtMs = System.currentTimeMillis(),
            )
            messages += AgentMessage(
                role = "assistant",
                content = "TOOL|${toolName.ifBlank { "tool" }}|${durable.input}|${reason.take(2_000)}",
                streamPart = durable,
            )
        }
        return InterruptedClosure(api, messages)
    }

    fun bounded(source: List<JsonObject>, maxEntries: Int, maxChars: Int): List<JsonObject> {
        val groups = completeGroups(source)
        val selected = ArrayDeque<List<JsonObject>>()
        var entries = 0
        var chars = 0L
        for (group in groups.asReversed()) {
            val groupChars = group.sumOf { ContextBudget.serializedChars(it).toLong() }
            if (entries + group.size > maxEntries || chars + groupChars > maxChars) break
            selected.addFirst(group)
            entries += group.size
            chars += groupChars
        }
        return selected.flatMap { it }
    }


    fun boundedUi(source: List<AgentMessage>, maxMessages: Int, maxChars: Int): List<AgentMessage> {
        val reversed = ArrayList<AgentMessage>()
        var chars = 0L
        for (entry in source.asReversed()) {
            val retainedChars = entry.content.length.toLong() + entry.presentationId.length + entry.publicTextSpans.size * 12L + (entry.streamPart?.retainedPreviewChars() ?: 0L)
            if (reversed.size >= maxMessages || chars + retainedChars > maxChars) break
            reversed += entry
            chars += retainedChars
        }
        return reversed.asReversed()
    }

    /** Compaction/request admission must not silently discard incomplete or duplicate tool evidence. */
    fun requireCompleteGroups(source: List<JsonObject>): List<List<JsonObject>> {
        val groups = completeGroups(source)
        require(groups.flatten() == source) {
            "Conversation contains incomplete or mismatched tool calls/results; existing conversation was preserved"
        }
        return groups
    }

    data class CompactionSelection(val historical: List<List<JsonObject>>, val recent: List<List<JsonObject>>)

    fun selectForCompaction(source: List<JsonObject>, keepRecentTokens: Int): CompactionSelection {
        require(keepRecentTokens > 0)
        val groups = requireCompleteGroups(source)
        val selected = linkedSetOf<Int>()
        var recentBytes = 0L
        for (index in groups.indices.reversed()) {
            val size = groups[index].sumOf { ContextBudget.serializedUtf8Bytes(it).toLong() }
            if (selected.isNotEmpty() && recentBytes + size > keepRecentTokens.toLong() * 4L) break
            selected += index
            recentBytes += size
        }
        // Keep the most recent admitted user request byte-for-byte, even across a long tool turn.
        groups.indexOfLast { group -> group.firstOrNull()?.get("role") == JsonPrimitive("user") }
            .takeIf { it >= 0 }?.let(selected::add)
        groups.forEachIndexed { index, group ->
            if (group.any { it["role"] in listOf(JsonPrimitive("system"), JsonPrimitive("developer")) }) selected += index
        }
        // Keep a bounded suffix of earlier user requests verbatim as well. A short
        // constraint must not depend only on the summary after a long tool turn.
        // Whole messages retain their roles, media and provenance; never splice text.
        var userBytes = minOf(4_000L, keepRecentTokens.toLong() / 4L) * 4L
        for (index in groups.indices.reversed()) {
            if (index in selected || groups[index].firstOrNull()?.get("role") != JsonPrimitive("user")) continue
            val size = groups[index].sumOf { ContextBudget.serializedUtf8Bytes(it).toLong() }
            if (size > userBytes) break
            selected += index
            userBytes -= size
        }
        return CompactionSelection(
            historical = groups.filterIndexed { index, _ -> index !in selected },
            recent = groups.filterIndexed { index, _ -> index in selected },
        )
    }

    fun completeGroups(source: List<JsonObject>): List<List<JsonObject>> {
        val groups = mutableListOf<List<JsonObject>>()
        var i = 0
        while (i < source.size) {
            val entry = source[i]
            val role = (entry["role"] as? JsonPrimitive)?.contentOrNull
            if (role == "tool") {
                i++
                continue
            }
            val calls = if (role == "assistant") entry["tool_calls"] as? JsonArray else null
            if (calls != null && calls.isNotEmpty()) {
                val required = calls.mapNotNull { call ->
                    ((call as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                }.filter { it.isNotBlank() }.toSet()
                if (required.size != calls.size) {
                    i++
                    continue
                }
                val group = mutableListOf(entry)
                val found = mutableSetOf<String>()
                var j = i + 1
                while (j < source.size) {
                    val tool = source[j]
                    if ((tool["role"] as? JsonPrimitive)?.contentOrNull != "tool") break
                    val id = (tool["tool_call_id"] as? JsonPrimitive)?.contentOrNull
                    if (id != null && id in required && id !in found) {
                        group += tool
                        found += id
                    }
                    j++
                }
                if (found == required) groups += group
                i = j
            } else {
                if (role == "user" || role == "assistant" || role == "system" || role == "developer") groups += listOf(entry)
                i++
            }
        }
        return groups
    }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SessionForkHistory {
    const val SCHEMA_VERSION = 1

    fun isExact(history: List<JsonObject>): Boolean {
        // Only the ledger/storage owner marks a fully validated immutable snapshot. Ordinary and
        // unverified Lists still follow the grammar.
        if ((history as? VerifiedSessionHistory)?.exactVerified == true) return true
        // Exact histories may be immutable, page-backed snapshots. Validate the stream without retaining
        // every entry or flattening all tool groups.
        var remainingToolIds: MutableSet<String>? = null
        for (index in history.indices) {
            val entry = history[index]
            val role = roleOf(entry)
            val remaining = remainingToolIds
            if (remaining != null) {
                if (role == "tool") {
                    val id = (entry["tool_call_id"] as? JsonPrimitive)?.contentOrNull
                    if (id == null || !remaining.remove(id)) return false
                    continue
                }
                if (remaining.isNotEmpty()) return false
                remainingToolIds = null
            }
            if (role != "user" && role != "assistant" && role != "system") return false
            val calls = if (role == "assistant") entry["tool_calls"] as? JsonArray else null
            if (calls != null && calls.isNotEmpty()) {
                val ids = mutableSetOf<String>()
                for (call in calls) {
                    val id = ((call as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                    if (id == null || !ids.add(id)) return false
                }
                remainingToolIds = ids
            }
        }
        return remainingToolIds?.isEmpty() != false
    }

    fun migrateLegacy(messages: List<AgentMessage>, activeHistory: List<JsonObject>): List<JsonObject>? {
        val hasUiToolEvidence = messages.any {
            it.content.startsWith("TOOL|") || it.streamPart?.kind == AgentStreamPartKind.TOOL
        }
        val hasStructuredToolEvidence = activeHistory.any { entry ->
            roleOf(entry) == "tool" ||
                ((entry["tool_calls"] as? JsonArray)?.isNotEmpty() == true)
        }
        if (hasUiToolEvidence || hasStructuredToolEvidence) return null

        val candidate = if (activeHistory.isEmpty() && messages.isNotEmpty()) {
            messages.asSequence()
                .filter { it.streamPart == null && (it.role == "user" || it.role == "assistant") }
                .filterNot { it.content.startsWith("TOOL|") }
                .map { message -> buildJsonObject { put("role", message.role); put("content", message.content) } }
                .toList()
        } else activeHistory
        if (candidate.isEmpty()) return emptyList()
        if (!isExact(candidate)) return null
        if (candidate.any { isCompactionCheckpoint(it) }) return null
        val annotated = annotate(messages, candidate)
        if (annotated.none { it.forkHistoryEnd != null }) return null
        return candidate
    }

    fun annotate(messages: List<AgentMessage>, history: List<JsonObject>): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        if (!isExact(history)) {
            return messages.map { it.copy(forkHistoryEnd = null) }
        }

        val boundaries = arrayOfNulls<Int>(messages.size)
        var cursor = messages.lastIndex
        var groupEnd = history.size
        while (groupEnd > 0) {
            val semantic = previousSemantic(messages, cursor)
            if (semantic < 0) break
            val groupStart = previousGroupStart(history, groupEnd)
            val head = history[groupStart]
            if (isInvisibleStructuredEntry(head)) {
                groupEnd = groupStart
                continue
            }

            val match = matchGroupBackward(messages, cursor, history, groupStart, groupEnd) ?: break
            boundaries[match.endUi] = groupEnd
            cursor = match.startUi - 1
            groupEnd = groupStart
        }

        return messages.mapIndexed { index, message -> message.copy(forkHistoryEnd = boundaries[index]) }
    }

    fun slice(messages: List<AgentMessage>, history: List<JsonObject>, atIndex: Int): List<JsonObject>? {
        if (atIndex !in messages.indices) return null
        if (!isExact(history)) return null
        val annotated = if (messages[atIndex].forkHistoryEnd != null) messages else annotate(messages, history)
        val end = annotated[atIndex].forkHistoryEnd ?: return null
        if (end !in 0..history.size) return null
        val verified = (history as? VerifiedSessionHistory)?.exactVerified == true
        // In an exact stream only a contiguous tool run can cross a boundary. Keep a verified immutable
        // snapshot lazy, including a cross-page group.
        if (verified && end > 0 && end < history.size && roleOf(history[end]) == "tool") return null
        val prefix = HistoryPrefix(history, end, verified)
        return prefix.takeIf(::isExact)
    }

    private data class Match(val startUi: Int, val endUi: Int)

    private class HistoryPrefix(
        private val history: List<JsonObject>, override val size: Int, override val exactVerified: Boolean,
    ) : AbstractList<JsonObject>(), VerifiedSessionHistory {
        override fun get(index: Int): JsonObject {
            if (index !in 0 until size) throw IndexOutOfBoundsException("History prefix index $index, size $size")
            return history[index]
        }
    }

    // Called only after isExact: every terminal tool run has one assistant head.
    private fun previousGroupStart(history: List<JsonObject>, endExclusive: Int): Int {
        var start = endExclusive - 1
        while (start > 0 && roleOf(history[start]) == "tool") start--
        return start
    }

    private fun matchGroupBackward(
        messages: List<AgentMessage>, cursor: Int, history: List<JsonObject>, startIndex: Int, endExclusive: Int,
    ): Match? {
        val assistant = history[startIndex]
        val role = roleOf(assistant)
        if (role == "user") {
            val index = findPreviousSemantic(messages, cursor) { message ->
                message.streamPart == null && message.role == "user" && textMatches(message.content, contentOf(assistant))
            } ?: return null
            return Match(index, index)
        }
        if (role != "assistant") return null

        val calls = assistant["tool_calls"] as? JsonArray
        if (calls == null || calls.isEmpty()) {
            val index = findPreviousSemantic(messages, cursor) { message ->
                message.streamPart == null && message.role == "assistant" && textMatches(message.content, contentOf(assistant))
            } ?: return null
            return Match(index, index)
        }

        var at = cursor
        var start = Int.MAX_VALUE
        var end = -1
        for (toolIndex in endExclusive - 1 downTo startIndex + 1) {
            val tool = history[toolIndex]
            val callId = (tool["tool_call_id"] as? JsonPrimitive)?.contentOrNull ?: return null
            val index = findPreviousSemantic(messages, at) { message ->
                message.streamPart?.kind == AgentStreamPartKind.TOOL && message.streamPart.toolCallId == callId
            } ?: return null
            start = minOf(start, index)
            end = maxOf(end, index)
            at = index - 1
        }

        val assistantText = contentOf(assistant)
        if (assistantText.isNotBlank()) {
            val index = findPreviousSemantic(messages, at) { message ->
                message.streamPart == null && message.role == "assistant" && textMatches(message.content, assistantText)
            } ?: return null
            start = minOf(start, index)
            end = maxOf(end, index)
        }
        if (end < 0) return null
        return Match(start, end)
    }

    private fun findPreviousSemantic(
        messages: List<AgentMessage>,
        cursor: Int,
        predicate: (AgentMessage) -> Boolean,
    ): Int? {
        var index = cursor.coerceAtMost(messages.lastIndex)
        while (index >= 0) {
            val message = messages[index]
            if (!isPresentationOnly(message)) return index.takeIf { predicate(message) }
            index--
        }
        return null
    }

    private fun previousSemantic(messages: List<AgentMessage>, cursor: Int): Int {
        var index = cursor.coerceAtMost(messages.lastIndex)
        while (index >= 0 && isPresentationOnly(messages[index])) index--
        return index
    }

    private fun isPresentationOnly(message: AgentMessage): Boolean =
        message.streamPart != null && message.streamPart.kind != AgentStreamPartKind.TOOL

    private fun isInvisibleStructuredEntry(entry: JsonObject): Boolean {
        val content = contentOf(entry)
        return roleOf(entry) == "system" || isCompactionCheckpoint(entry) ||
            content.startsWith("[Background subagent completion;")
    }

    private fun isCompactionCheckpoint(entry: JsonObject): Boolean =
        contentOf(entry).startsWith("[Historical conversation checkpoint;")

    private fun roleOf(entry: JsonObject): String? = (entry["role"] as? JsonPrimitive)?.contentOrNull
    private fun contentOf(entry: JsonObject): String = (entry["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun textMatches(ui: String, api: String): Boolean =
        ui == api || (api.isNotEmpty() && ui.length >= api.length && ui.startsWith(api))
}

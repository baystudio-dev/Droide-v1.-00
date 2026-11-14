package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.util.UUID
import java.security.MessageDigest

/** One run's ordered parts. Persistence receives uncapped text deltas. */
internal class ExternalAgentTranscript {
    private val prefix = "external:${UUID.randomUUID()}:"
    private val parts = mutableListOf<AgentStreamPart>()
    private val textDigest = MessageDigest.getInstance("SHA-256")
    private var textCount = 0L
    private var sequence = 0L
    private var activitySeen = false
    private var omittedParts = 0
    private var finalTextDigest: ByteArray? = null
    private val textBuffer = StringBuilder()
    private var textPartIndex: Int? = null
    private fun MessageDigest.updateText(value: String) {
        val buffer = ByteArray(4096)
        var size = 0
        for (char in value) {
            buffer[size++] = (char.code ushr 8).toByte(); buffer[size++] = char.code.toByte()
            if (size == buffer.size) { update(buffer); size = 0 }
        }
        if (size > 0) update(buffer, 0, size)
    }
    val hasActivity: Boolean get() = activitySeen
    val snapshot: List<AgentStreamPart> get() {
        val result = parts.toMutableList()
        textPartIndex?.let { result[it] = result[it].copy(text = textBuffer.toString()) }
        var remaining = 256_000
        for (index in result.indices.reversed()) {
            val part = result[index]
            if (part.kind == AgentStreamPartKind.TEXT) {
                val tail = part.text.takeLast(remaining)
                result[index] = part.copy(text = tail); remaining -= tail.length
            }
        }
        return result
    }
    val liveParts: List<AgentStreamPart> get() = if (omittedParts == 0) snapshot else listOf(
        AgentStreamPart(prefix + "earlier", 0, AgentStreamPartKind.STATUS, AgentStreamPartState.COMPLETED,
            title = "Showing the latest activities", detail = "$omittedParts earlier entries", startedAtMs = 0L)) + snapshot
    private fun sealText() {
        val index = textPartIndex ?: return
        val current = snapshot
        parts.clear(); parts.addAll(current)
        parts[index] = parts[index].copy(state = AgentStreamPartState.COMPLETED, endedAtMs = System.currentTimeMillis())
        textPartIndex = null; textBuffer.setLength(0)
    }

    fun accept(event: WorkspaceAgentEvent): ExternalTranscriptUpdate? = when (event) {
        is WorkspaceAgentEvent.Text -> text(event.text)
        is WorkspaceAgentEvent.Tool -> event.update?.let(::tool)
        is WorkspaceAgentEvent.Plan -> {
            val tasks = event.entries.take(64).map { Todo(it.content.take(1_000),
                it.status.takeIf { status -> status in setOf("pending", "in_progress", "completed") } ?: "pending", "medium") }
            val output = tasks.joinToString("\n") {
                "${when (it.status) { "completed" -> "✓"; "in_progress" -> "→"; else -> "○" }} ${it.content}"
            }.take(32_000)
            val status = if (tasks.isNotEmpty() && tasks.all { it.status == "completed" }) "completed" else "in_progress"
            tool(ExternalAgentToolUpdate("plan", "Plan", "plan", status, output = output, tasks = tasks), plan = true)
        }
        else -> null
    }

    private fun text(value: String): ExternalTranscriptUpdate? {
        if (value.isEmpty()) return null
        // UTF-16 code units make equality independent of chunk boundaries, including split surrogates.
        textDigest.updateText(value)
        textCount += value.length
        val index = textPartIndex ?: run {
            val part = newPart(prefix + "text:${sequence++}", AgentStreamPartKind.TEXT)
            makeRoom(); parts += part
            parts.lastIndex.also { textPartIndex = it }
        }
        appendBoundedTextTail(textBuffer, value, 256_000)
        return ExternalTranscriptUpdate(parts[index], value)
    }

    private fun newPart(id: String, kind: AgentStreamPartKind): AgentStreamPart {
        return AgentStreamPart(id, 0, kind, AgentStreamPartState.RUNNING,
            textPresentation = AgentTextPresentation.RESPONSE, presentationId = id)
    }

    private fun makeRoom() {
        if (parts.size >= 256) {
            parts.removeAt(0); omittedParts++
            textPartIndex = textPartIndex?.minus(1)?.takeIf { it >= 0 }
        }
    }

    private fun tool(patch: ExternalAgentToolUpdate, plan: Boolean = false): ExternalTranscriptUpdate {
        activitySeen = true
        val id = prefix + (if (plan) "plan:" else "tool:") + patch.id
        val index = parts.indexOfFirst { it.id == id }
        val old = parts.getOrNull(index)
        val base = old ?: newPart(id, AgentStreamPartKind.TOOL).copy(state = AgentStreamPartState.PENDING)
        val part = patch.applyTo(base, plan)
        if (index >= 0) parts[index] = part
        else if (!patch.isUpdate || plan || omittedParts == 0) {
            sealText(); makeRoom(); parts += part
        }
        // A patch for an older, off-screen tool still merges into its original SQLite slot.
        return ExternalTranscriptUpdate(part, patch = patch, plan = plan)
    }

    fun messages(reply: String?, status: PluginHistoryStatus, media: List<ChatMediaRef>): List<AgentMessage> {
        if (!hasActivity) return listOfNotNull(reply?.let { AgentMessage("assistant", it, media = media) })
        val visibleParts = snapshot
        val messages = visibleParts.map { externalFinishedPart(it, status) }.map { part ->
            if (part.kind == AgentStreamPartKind.TEXT) AgentMessage("assistant", part.text)
            else AgentMessage("assistant", "", streamPart = part)
        }.filter { it.streamPart != null || it.content.isNotEmpty() }.toMutableList()
        val tail = visibleParts.filter { it.kind == AgentStreamPartKind.TEXT }.joinToString("") { it.text }
        val digest = MessageDigest.getInstance("SHA-256")
        reply?.let { digest.updateText(it) }
        val savedDigest = finalTextDigest ?: textDigest.digest().also { finalTextDigest = it }
        val matches = reply != null && reply.length.toLong() == textCount && savedDigest.contentEquals(digest.digest())
        if (!reply.isNullOrEmpty() && !matches && !tail.endsWith(reply)) messages += AgentMessage("assistant", reply)
        if (media.isNotEmpty()) {
            if (messages.isEmpty()) messages += AgentMessage("assistant", "", media = media)
            else messages[messages.lastIndex] = messages.last().copy(media = media)
        }
        return messages
    }
}

internal fun externalAgentTasks(state: ExternalAgentConversationState): List<Todo> =
    state.streamParts.asReversed().firstNotNullOfOrNull { it.tasks }
        ?: state.messages.asReversed().firstNotNullOfOrNull { it.streamPart?.tasks }.orEmpty()

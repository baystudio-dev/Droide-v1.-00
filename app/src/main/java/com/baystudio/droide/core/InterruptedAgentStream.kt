package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Preserve interrupted public text, never turn streamed tool fragments or native
 * reasoning/signatures into an executable or provider-private continuation. */
internal object InterruptedAgentStream {
    data class Snapshot(val message: AgentMessage, val apiEntry: JsonObject)

    fun snapshot(part: AgentStreamPart?, cancelled: Boolean, runId: Long): Snapshot? {
        if (part == null || part.kind != AgentStreamPartKind.TEXT || part.text.isBlank()) return null
        val detail = if (cancelled) "Partial response · stopped by user" else "Partial response · interrupted before completion"
        val durable = part.copy(
            id = "interrupted-text:${UUID.randomUUID()}:$runId:${part.id}",
            state = if (cancelled) AgentStreamPartState.CANCELLED else AgentStreamPartState.ERROR,
            detail = detail,
            finishReason = "",
            textPresentation = if (part.textPresentation == AgentTextPresentation.LEGACY) AgentTextPresentation.LEGACY else AgentTextPresentation.RESPONSE,
            publicTextSpans = emptyList(),
            endedAtMs = System.currentTimeMillis(),
        )
        return Snapshot(
            AgentMessage("assistant", "", streamPart = durable),
            buildJsonObject {
                put("role", "assistant")
                put("content", "[${detail}. This is incomplete text, not a completed answer or tool request.]\n${part.text}")
            },
        )
    }
}

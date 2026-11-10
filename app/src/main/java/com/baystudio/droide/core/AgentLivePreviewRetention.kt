package com.baystudio.droide.core

/** The model/exact ledger owns conversation truth; live UI previews have a separate memory budget. */
internal fun AgentStreamPart.retainedPreviewChars(): Long =
    id.length.toLong() + presentationId.length + publicTextSpans.size * 12L + title.length + detail.length + text.length + toolName.length + toolCallId.length +
        input.length + output.length + finishReason.length + fileChanges.sumOf {
            it.path.length.toLong() + it.diff.length + (it.previousPath?.length ?: 0)
        }

internal object AgentLivePreviewRetention {
    const val MAX_COMPLETED_CHARS = 1_000_000
    const val MAX_PARTS = 500

    /** Pending/running tool owners must stay visible and addressable even above the preview budget. */
    fun bounded(parts: List<AgentStreamPart>): List<AgentStreamPart> {
        val activeCount = parts.count { it.state == AgentStreamPartState.PENDING || it.state == AgentStreamPartState.RUNNING }
        var completedChars = 0L
        var completedCount = 0
        val accepted = ArrayList<AgentStreamPart>(parts.size.coerceAtMost(MAX_PARTS))
        for (part in parts.asReversed()) {
            if (part.state == AgentStreamPartState.PENDING || part.state == AgentStreamPartState.RUNNING) {
                accepted += part
            } else {
                val chars = part.retainedPreviewChars()
                if (completedCount + activeCount >= MAX_PARTS || completedChars + chars > MAX_COMPLETED_CHARS) continue
                accepted += part
                completedCount++
                completedChars += chars
            }
        }
        return accepted.asReversed()
    }
}

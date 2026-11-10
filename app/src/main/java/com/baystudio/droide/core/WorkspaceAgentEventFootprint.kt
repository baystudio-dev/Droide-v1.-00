package com.baystudio.droide.core

/** UTF-16 payload units plus a small fixed per-event allowance; not measured heap bytes. */
internal fun WorkspaceAgentEvent.retainedChars(): Long = 64L + when (this) {
    is WorkspaceAgentEvent.Text -> text.length.toLong()
    is WorkspaceAgentEvent.Thought -> text.length.toLong()
    is WorkspaceAgentEvent.Tool -> title.length.toLong() + raw.length + (update?.retainedChars() ?: 0L)
    is WorkspaceAgentEvent.Plan -> entries.sumOf { it.content.length.toLong() + it.status.length + 32L }
    is WorkspaceAgentEvent.Status -> message.length.toLong()
    is WorkspaceAgentEvent.Usage -> snapshot.costCurrency?.length?.toLong() ?: 0L
    is WorkspaceAgentEvent.SessionBound -> sessionId.length.toLong()
    is WorkspaceAgentEvent.Configuration -> sessionId.length.toLong() + value.retainedChars()
    is WorkspaceAgentEvent.Media -> ref.id.length.toLong() + ref.path.length + ref.name.length + ref.mediaType.length
}

private fun WorkspaceAgentSessionConfiguration.retainedChars(): Long {
    fun String?.chars() = this?.length?.toLong() ?: 0L
    fun WorkspaceAgentModelOption.chars() = id.length.toLong() + name.length + description.length + 32L
    return currentModelId.chars() + modelConfigId.chars() + currentModeId.chars() + modeConfigId.chars() +
        currentReasoningId.chars() + reasoningConfigId.chars() + modes.sumOf { it.length.toLong() + 16L } +
        models.sumOf { it.chars() } + reasoningOptions.sumOf { it.chars() } +
        availableCommands.sumOf { it.length.toLong() + 16L }
}

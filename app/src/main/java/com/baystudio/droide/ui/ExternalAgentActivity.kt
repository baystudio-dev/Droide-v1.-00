package com.baystudio.droide.ui

import com.baystudio.droide.core.WorkspaceAgentEvent
import kotlinx.serialization.json.*

internal data class ExternalToolActivity(val status: String, val kind: String, val title: String) {
    val finished: Boolean get() = status == "completed" || status == "failed"
}

internal data class ExternalAgentActivity(
    val phase: AgentActivityKind = AgentActivityKind.WORKING,
    val tools: Map<String, ExternalToolActivity> = emptyMap(),
    val incomplete: Boolean = false,
    val finished: Boolean = false,
) {
    fun finish(kind: AgentActivityKind) = copy(phase = kind, finished = true)

    fun accept(event: WorkspaceAgentEvent): ExternalAgentActivity {
        if (finished) return this
        return when (event) {
            is WorkspaceAgentEvent.Text -> if (event.text.isEmpty()) this else copy(phase = AgentActivityKind.RESPONDING)
            is WorkspaceAgentEvent.Thought -> if (event.text.isEmpty()) this else copy(phase = AgentActivityKind.THINKING)
            is WorkspaceAgentEvent.Tool -> tool(event)
            is WorkspaceAgentEvent.Status -> copy(phase = AgentActivityKind.WORKING)
            else -> this
        }
    }

    private fun tool(event: WorkspaceAgentEvent.Tool): ExternalAgentActivity {
        val patch = event.update ?: return copy(phase = AgentActivityKind.WORKING)
        val id = patch.id
        val old = tools[id]
        if (old?.finished == true) return this
        val status = patch.status ?: old?.status ?: "pending"
        if (status !in setOf("pending", "in_progress", "completed", "failed")) return copy(phase = AgentActivityKind.WORKING)
        val next = tools.toMutableMap()
        if (id !in next && next.size >= 128) {
            return copy(phase = AgentActivityKind.WORKING, incomplete = true)
        }
        val entry = ExternalToolActivity(status, patch.kind ?: old?.kind.orEmpty(), patch.title ?: old?.title ?: event.title.take(160))
        next[id] = entry
        val nextPhase = when {
            !entry.finished -> AgentActivityKind.TOOL
            phase == AgentActivityKind.TOOL -> if (next.values.any { !it.finished }) AgentActivityKind.TOOL else AgentActivityKind.WORKING
            else -> phase
        }
        return copy(phase = nextPhase, tools = next)
    }

    fun presentation(approval: Boolean = false): AgentActivityPresentation {
        if (finished) return AgentActivityPresentation(phase)
        if (approval) return AgentActivityPresentation(AgentActivityKind.APPROVAL)
        if (incomplete) return AgentActivityPresentation(AgentActivityKind.WORKING, "Additional tool details omitted")
        if (phase != AgentActivityKind.TOOL) return AgentActivityPresentation(phase)
        val active = tools.values.filterNot { it.finished }
        if (active.size > 1) return AgentActivityPresentation(AgentActivityKind.TOOL, "${active.size} tools active")
        val tool = active.firstOrNull() ?: return AgentActivityPresentation(AgentActivityKind.WORKING)
        val kind = if (tool.status == "pending") AgentActivityKind.PREPARING else when (tool.kind) {
            "read" -> AgentActivityKind.READING
            "search" -> AgentActivityKind.SEARCHING
            "edit", "delete", "move" -> AgentActivityKind.EDITING
            "execute" -> AgentActivityKind.COMMAND
            else -> AgentActivityKind.TOOL
        }
        return AgentActivityPresentation(kind, tool.title)
    }
}

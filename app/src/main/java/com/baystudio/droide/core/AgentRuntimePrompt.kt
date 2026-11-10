package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object AgentRuntimePrompt {
    fun compose(
        mode: AgentMode,
        modeInstruction: String,
        projectBrief: String,
        systemOverlay: String,
        finalStep: Boolean,
        tools: JsonArray?,
        greetingOnly: Boolean = false,
        stepBudgetReached: Boolean = finalStep,
    ): String {
        val overlay = systemOverlay.takeIf { it.isNotBlank() }?.let { "\n\nSUBAGENT PROFILE:\n$it" }.orEmpty()
        val lifecycle = if (stepBudgetReached) "\n\n" + AgentService.FINAL_STEP_INSTRUCTION else ""
        val names = tools?.let(::toolNames).orEmpty()
        val capability = if (tools == null) {
            "\n\nACTIVE TOOL SURFACE: none. This response is text-only; do not request tools."
        } else {
            "\n\nACTIVE TOOL SURFACE (${names.size}): ${names.joinToString(", ")}. " +
                "Only these tools are callable in this step. This proves Agent-tool advertisement/callability only; external binaries and runtime dependencies may still be absent. Mode-hidden tools must not be requested."
        }
        // Help is normally deferred by AgentToolSelection. Keep the routing instruction visible before
        // discovery; otherwise the agent must already know to load the guide.
        val handbook = when {
            "droide_help" in names -> DroideHandbookPolicy.BOOTSTRAP
            "tool_search" in names -> DroideHandbookPolicy.DISCOVERY_BOOTSTRAP
            else -> ""
        }.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
        val intent = if (greetingOnly) "\n\nThis is an initial standalone greeting. Reply conversationally; no project task has been requested." else ""
        // This reuses the existing classified greeting only; null tools alone is not conversational
        // intent. Final/model-limited responses retain authority, evidence and lifecycle guidance.
        val base = if (greetingOnly && tools == null && !finalStep && !stepBudgetReached)
            AgentSystemPrompt.GREETING_TEXT else AgentSystemPrompt.forTools(names.toSet())
        return base + overlay + "\n\nCurrent mode: $mode. " + modeInstruction + projectBrief +
            capability + handbook + lifecycle + intent + "\n\n" + AgentRequestScope.AUTHORITY_BOUNDARY.trimIndent()
    }

    internal fun toolNames(tools: JsonArray): List<String> = tools.mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
        (function["name"] as? JsonPrimitive)?.contentOrNull
    }.distinct()
}

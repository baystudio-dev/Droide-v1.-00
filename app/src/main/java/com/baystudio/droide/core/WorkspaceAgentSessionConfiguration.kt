package com.baystudio.droide.core

import kotlinx.serialization.json.*

data class WorkspaceAgentModelOption(val id: String, val name: String, val description: String = "")

/** Agent-reported identifiers, scoped to one live session; never a guessed provider catalog. */
data class WorkspaceAgentSessionConfiguration(
    val models: List<WorkspaceAgentModelOption> = emptyList(),
    val currentModelId: String? = null,
    val modelConfigId: String? = null,
    val modes: List<String> = emptyList(),
    val currentModeId: String? = null,
    val modeConfigId: String? = null,
    val reasoningOptions: List<WorkspaceAgentModelOption> = emptyList(),
    val currentReasoningId: String? = null,
    val reasoningConfigId: String? = null,
    val availableCommands: List<String> = emptyList(),
    // Separate from provider approval/sandbox settings. Do not conflate the two axes.
    val collaborationModes: List<String> = emptyList(),
    val currentCollaborationModeId: String? = null,
    val collaborationModeConfigId: String? = null,
    // Once observed, a disappearing/malformed replacement cannot silently select legacy mode.
    val collaborationModeObserved: Boolean = false,
    // Monotonic capability evidence: disappearance is not a mode-less adapter.
    val modeCapabilityObserved: Boolean = false,
) {
    val currentModelName: String? get() = models.firstOrNull { it.id == currentModelId }?.name ?: currentModelId
    val currentReasoningName: String? get() = reasoningOptions.firstOrNull { it.id == currentReasoningId }?.name ?: currentReasoningId
}

internal object WorkspaceAgentSessionConfigurations {
    fun merge(result: JsonObject, previous: WorkspaceAgentSessionConfiguration = WorkspaceAgentSessionConfiguration()): WorkspaceAgentSessionConfiguration {
        val malformedConfigs = "configOptions" in result && result["configOptions"] !is JsonArray
        val configs = if (malformedConfigs) emptyList() else
            (result["configOptions"] as? JsonArray)?.take(64)?.mapNotNull { it as? JsonObject }
        fun config(category: String) = configs?.firstOrNull {
            it.string("id") != null && it.string("type") == "select" &&
                (it.string("category") == category || it.string("id") == category)
        }
        val model = config("model")
        val mode = config("mode")
        val reasoning = config("thought_level")
        val collaboration = config("collaboration_mode")
        val collaborationObserved = previous.collaborationModeObserved || configs.orEmpty().any {
            it.string("category") == "collaboration_mode" || it.string("id") == "collaboration_mode"
        }
        val legacyModels = result["models"] as? JsonObject
        val legacyModes = result["modes"] as? JsonObject
        val clearModelConfig = malformedConfigs || configs != null && model == null && previous.modelConfigId != null
        val clearModeConfig = malformedConfigs || configs != null && mode == null && previous.modeConfigId != null
        val clearReasoningConfig = malformedConfigs || configs != null && reasoning == null && previous.reasoningConfigId != null
        return WorkspaceAgentSessionConfiguration(
            models = when {
                model != null -> choices(model["options"] as? JsonArray)
                legacyModels != null -> (legacyModels["availableModels"] as? JsonArray).orEmpty().take(512).mapNotNull {
                    val entry = it as? JsonObject ?: return@mapNotNull null
                    val id = entry.string("modelId") ?: return@mapNotNull null
                    WorkspaceAgentModelOption(id, entry.string("name") ?: id, entry.description())
                }.distinctBy { it.id }
                clearModelConfig -> emptyList()
                else -> previous.models
            },
            currentModelId = when {
                model != null -> model.string("currentValue")
                legacyModels != null -> legacyModels.string("currentModelId")
                clearModelConfig -> null
                else -> previous.currentModelId
            },
            modelConfigId = when {
                model != null -> model.string("id")
                legacyModels != null || clearModelConfig -> null
                else -> previous.modelConfigId
            },
            modes = when {
                mode != null -> choices(mode["options"] as? JsonArray).map { it.id }
                legacyModes != null -> (legacyModes["availableModes"] as? JsonArray).orEmpty().take(128)
                    .mapNotNull { (it as? JsonObject)?.string("id") }.distinct()
                clearModeConfig -> emptyList()
                else -> previous.modes
            },
            currentModeId = when {
                mode != null -> mode.string("currentValue")
                legacyModes != null -> legacyModes.string("currentModeId")
                clearModeConfig -> null
                else -> previous.currentModeId
            },
            modeConfigId = when {
                mode != null -> mode.string("id")
                legacyModes != null || clearModeConfig -> null
                else -> previous.modeConfigId
            },
            reasoningOptions = when {
                reasoning != null -> choices(reasoning["options"] as? JsonArray).take(64)
                clearReasoningConfig -> emptyList()
                else -> previous.reasoningOptions
            },
            currentReasoningId = when {
                reasoning != null -> reasoning.string("currentValue")
                clearReasoningConfig -> null
                else -> previous.currentReasoningId
            },
            reasoningConfigId = when {
                reasoning != null -> reasoning.string("id")
                clearReasoningConfig -> null
                else -> previous.reasoningConfigId
            },
            availableCommands = previous.availableCommands,
            collaborationModes = if (configs == null) previous.collaborationModes
                else collaboration?.let { choices(it["options"] as? JsonArray).map { option -> option.id } }.orEmpty(),
            currentCollaborationModeId = if (configs == null) previous.currentCollaborationModeId else collaboration?.string("currentValue"),
            collaborationModeConfigId = if (configs == null) previous.collaborationModeConfigId else collaboration?.string("id"),
            collaborationModeObserved = collaborationObserved,
            modeCapabilityObserved = previous.modeCapabilityObserved || clearModeConfig || "modes" in result ||
                malformedConfigs || configs.orEmpty().any {
                    it.string("category") == "mode" || it.string("id") == "mode"
                },
        )
    }

    fun update(update: JsonObject, previous: WorkspaceAgentSessionConfiguration): WorkspaceAgentSessionConfiguration =
        when (update.string("sessionUpdate")) {
            "available_commands_update" -> previous.copy(availableCommands = AcpSessionCommands.parseSnapshot(update).orEmpty())
            // This notification is a complete snapshot, unlike an ordinary empty response.
            "config_option_update" -> merge(if ("configOptions" in update) update else
                JsonObject(update + ("configOptions" to JsonNull)), previous)
            "current_model_update" -> previous.copy(currentModelId = update.string("currentModelId") ?: previous.currentModelId)
            "current_mode_update" -> previous.copy(currentModeId = update.string("currentModeId") ?: previous.currentModeId, modeCapabilityObserved = true)
            else -> previous
        }

    fun modelRequest(sessionId: String, configuration: WorkspaceAgentSessionConfiguration, modelId: String): Pair<String, JsonObject> {
        require(configuration.models.any { it.id == modelId }) { "Model is not available in this Agent session; refresh models" }
        val configId = configuration.modelConfigId
        return (if (configId == null) "session/set_model" else "session/set_config_option") to buildJsonObject {
            put("sessionId", sessionId)
            if (configId == null) put("modelId", modelId)
            else { put("configId", configId); put("value", modelId) }
        }
    }

    fun modeRequest(sessionId: String, configuration: WorkspaceAgentSessionConfiguration, modeId: String,
                    collaborationMode: Boolean = false): Pair<String, JsonObject> {
        require(modeId in if (collaborationMode) configuration.collaborationModes else configuration.modes) { "Mode is not available in this CLI session; refresh configuration" }
        val configId = if (collaborationMode) requireNotNull(configuration.collaborationModeConfigId) {
            "CLI collaboration configuration is unavailable. No prompt was sent."
        } else configuration.modeConfigId
        return (if (configId == null) "session/set_mode" else "session/set_config_option") to buildJsonObject {
            put("sessionId", sessionId)
            if (configId == null) put("modeId", modeId)
            else { put("configId", configId); put("value", modeId) }
        }
    }

    fun modeResponse(result: JsonObject, previous: WorkspaceAgentSessionConfiguration,
                     modeId: String, usesConfigOption: Boolean, collaborationMode: Boolean = false): WorkspaceAgentSessionConfiguration {
        if (usesConfigOption || collaborationMode) require(result["configOptions"] is JsonArray) {
            "CLI did not return its updated mode configuration. No prompt was sent."
        }
        val reported = merge(result, previous)
        // A legacy set_mode success is the protocol's acknowledgement of that exact ID.
        // Modern set_config_option must instead confirm the returned currentValue. Never
        // overwrite a contradictory replacement snapshot with the requested value.
        val confirmed = if (!collaborationMode && !usesConfigOption && "modes" !in result && "configOptions" !in result)
            reported.copy(currentModeId = modeId) else reported
        check(if (collaborationMode) confirmed.currentCollaborationModeId == modeId && modeId in confirmed.collaborationModes &&
            confirmed.collaborationModeConfigId == previous.collaborationModeConfigId
        else confirmed.currentModeId == modeId && modeId in confirmed.modes) {
            "CLI reported a different or unavailable mode. No prompt was sent."
        }
        return confirmed
    }

    fun reasoningRequest(sessionId: String, configuration: WorkspaceAgentSessionConfiguration, value: String): Pair<String, JsonObject> {
        val configId = requireNotNull(configuration.reasoningConfigId) { "This CLI has not reported Reasoning choices" }
        require(configuration.reasoningOptions.any { it.id == value }) { "Reasoning level is not available for the current CLI model; refresh configuration" }
        return "session/set_config_option" to buildJsonObject {
            put("sessionId", sessionId); put("configId", configId); put("value", value)
        }
    }

    private fun choices(options: JsonArray?): List<WorkspaceAgentModelOption> {
        val result = linkedMapOf<String, WorkspaceAgentModelOption>()
        var visited = 0
        fun collect(entries: JsonArray?, depth: Int) {
            if (depth > 2) return
            for (raw in entries.orEmpty()) {
                if (result.size >= 512 || visited++ >= 2_048) return
                val entry = raw as? JsonObject ?: continue
                val value = entry.string("value")
                if (value != null) result.putIfAbsent(value,
                    WorkspaceAgentModelOption(value, entry.string("name") ?: value, entry.description()))
                else collect(entry["options"] as? JsonArray, depth + 1)
            }
        }
        collect(options, 0)
        return result.values.toList()
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 512 && it.none { c -> c == '\u0000' || c == '\r' || c == '\n' } }
    private fun JsonObject.description(): String = (get("description") as? JsonPrimitive)?.contentOrNull.orEmpty().take(2_000)
}

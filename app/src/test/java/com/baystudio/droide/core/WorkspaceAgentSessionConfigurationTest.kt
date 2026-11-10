package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Captured session/new responses from supported external command-line agents. */
class WorkspaceAgentSessionConfigurationTest {
    private fun fixture(name: String): JsonObject = requireNotNull(javaClass.getResourceAsStream("/agent-acp/$name-session.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun originalOpenCodeSuppliesElevenModelsThroughConfigOptions() {
        val config = WorkspaceAgentSessionConfigurations.merge(fixture("opencode"))
        assertEquals(11, config.models.size)
        assertEquals("opencode/big-pickle", config.currentModelId)
        assertEquals("model", config.modelConfigId)
        assertEquals(listOf("build", "plan"), config.modes)
        assertEquals("mode", config.modeConfigId)
    }

    @Test fun originalClaudeModelOptionsAreSeparateFromModesAndEffort() {
        val config = WorkspaceAgentSessionConfigurations.merge(fixture("claude"))
        assertEquals(listOf("default", "opus", "claude-fable-5-1", "sonnet", "haiku"), config.models.map { it.id })
        assertEquals("default", config.currentModelId)
        assertTrue(config.modes.contains("plan"))
        assertFalse(config.models.any { it.id == "bypassPermissions" || it.id == "max" })
    }

    @Test fun originalCodexPrefersEightModernModelsOverFortyFourLegacyVariants() {
        val raw = fixture("codex")
        assertEquals(44, raw["models"]!!.jsonObject["availableModels"]!!.jsonArray.size)
        val config = WorkspaceAgentSessionConfigurations.merge(raw)
        assertEquals(8, config.models.size)
        assertEquals("gpt-6.1-sol", config.currentModelId)
        val (method, params) = WorkspaceAgentSessionConfigurations.modelRequest("existing_session", config, config.models.last().id)
        assertEquals("session/set_config_option", method)
        assertEquals("existing_session", params["sessionId"]!!.jsonPrimitive.content)
        assertEquals("model", params["configId"]!!.jsonPrimitive.content)
        assertFalse("modelId" in params)
    }

    @Test fun legacyModelsUseTheExistingSessionSetModelMethod() {
        val config = WorkspaceAgentSessionConfigurations.merge(json("""{"models":{"currentModelId":"alpha","availableModels":[{"modelId":"alpha","name":"Alpha"},{"modelId":"beta","name":"Beta"}]}}"""))
        val (method, params) = WorkspaceAgentSessionConfigurations.modelRequest("kept_session", config, "beta")
        assertEquals("session/set_model", method)
        assertEquals("kept_session", params["sessionId"]!!.jsonPrimitive.content)
        assertEquals("beta", params["modelId"]!!.jsonPrimitive.content)
        assertNull(config.modelConfigId)
    }

    @Test fun codexReasoningUsesReportedThoughtLevelValuesAndSameNativeSession() {
        val config = WorkspaceAgentSessionConfigurations.merge(fixture("codex"))
        assertEquals("reasoning_effort", config.reasoningConfigId)
        assertEquals("low", config.currentReasoningId)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra"), config.reasoningOptions.map { it.id })
        val (method, params) = WorkspaceAgentSessionConfigurations.reasoningRequest("kept_native_session", config, "ultra")
        assertEquals("session/set_config_option", method)
        assertEquals("kept_native_session", params["sessionId"]!!.jsonPrimitive.content)
        assertEquals("reasoning_effort", params["configId"]!!.jsonPrimitive.content)
        assertEquals("ultra", params["value"]!!.jsonPrimitive.content)
        assertFalse("modelId" in params)
    }

    @Test fun claudeEffortKeepsItsOwnReportedDefaultAndUnsupportedAgentsHaveNoInventedLevels() {
        val config = WorkspaceAgentSessionConfigurations.merge(fixture("claude"))
        assertEquals("effort", config.reasoningConfigId)
        assertEquals("default", config.currentReasoningId)
        assertTrue(config.reasoningOptions.any { it.id == "default" })
        assertTrue(config.reasoningOptions.any { it.id == "max" })
        val none = WorkspaceAgentSessionConfigurations.merge(fixture("opencode"))
        assertTrue(none.reasoningOptions.isEmpty())
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.reasoningRequest("session", none, "high") }.isFailure)
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.reasoningRequest("session", config, "invented") }.isFailure)
    }

    @Test fun modelConfigurationReplacementRemovesOrChangesReasoningWithoutLeakingPreviousModelLevels() {
        val old = WorkspaceAgentSessionConfigurations.merge(fixture("codex"))
        val changed = WorkspaceAgentSessionConfigurations.merge(json("""{"configOptions":[{"id":"effort","category":"thought_level","type":"select","currentValue":"medium","options":[{"value":"medium","name":"Medium"}]}]}"""), old)
        assertEquals(listOf("medium"), changed.reasoningOptions.map { it.id })
        assertEquals("Medium", changed.currentReasoningName)
        assertEquals("effort", changed.reasoningConfigId)
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.reasoningRequest("session", changed, "ultra") }.isFailure)
        val removed = WorkspaceAgentSessionConfigurations.merge(json("""{"configOptions":[]}"""), changed)
        assertTrue(removed.reasoningOptions.isEmpty())
        assertNull(removed.currentReasoningId)
        assertNull(removed.reasoningConfigId)
        assertEquals(old, WorkspaceAgentSessionConfigurations.merge(json("{}"), old))
    }

    @Test fun completeConfigUpdatesReplaceChoicesAndRemoveDeletedCategories() {
        val original = WorkspaceAgentSessionConfigurations.merge(fixture("opencode"))
        val updated = WorkspaceAgentSessionConfigurations.update(json("""{"sessionUpdate":"config_option_update","configOptions":[{"id":"new-model","category":"model","type":"select","currentValue":"only-model","options":[{"value":"only-model","name":"Only model"}]}]}"""), original)
        assertEquals(listOf("only-model"), updated.models.map { it.id })
        assertEquals("Only model", updated.currentModelName)
        assertEquals("new-model", updated.modelConfigId)
        assertTrue(updated.modes.isEmpty())
        assertNull(updated.modeConfigId)
        val removed = WorkspaceAgentSessionConfigurations.merge(json("""{"configOptions":[]}"""), updated)
        assertTrue(removed.models.isEmpty())
        assertNull(removed.currentModelId)
        assertNull(removed.modelConfigId)
    }

    @Test fun groupedOptionsAreFlattenedDeduplicatedAndBoundedBeforeAllocatingAllGroups() {
        val options = buildJsonArray {
            repeat(10) { group -> add(buildJsonObject { put("group", "Group $group"); put("options", buildJsonArray {
                repeat(600) { add(buildJsonObject { put("value", "model-$it"); put("name", "Model $it") }) }
            }) }) }
        }
        val config = WorkspaceAgentSessionConfigurations.merge(buildJsonObject { put("configOptions", buildJsonArray {
            add(buildJsonObject { put("id", "model"); put("type", "select"); put("currentValue", "model-0"); put("options", options) })
        }) })
        assertEquals(512, config.models.size)
        assertEquals(512, config.models.map { it.id }.distinct().size)
    }

    @Test fun malformedOptionValuesAndConfigIdsCannotBecomeRequests() {
        val config = WorkspaceAgentSessionConfigurations.merge(json("""{"configOptions":[{"id":42,"category":"model","type":"select","options":[{"value":"unusable"}]},{"id":"model","type":"select","currentValue":"valid","options":[{},42,{"value":42},{"value":"bad\nmodel"},{"value":"valid","name":"Valid"}]}]}"""))
        assertEquals(listOf("valid"), config.models.map { it.id })
        assertEquals("model", config.modelConfigId)
    }

    @Test fun unknownModelsAreRejectedBeforeSendingAndLegacyUpdatesPreserveChoices() {
        val original = WorkspaceAgentSessionConfigurations.merge(fixture("opencode"))
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.modelRequest("same_session", original, "invented/model") }.isFailure)
        val changed = WorkspaceAgentSessionConfigurations.update(json("""{"sessionUpdate":"current_model_update","currentModelId":"opencode/another"}"""), original)
        assertEquals("opencode/another", changed.currentModelId)
        assertEquals(original.models, changed.models)
        assertEquals(original, WorkspaceAgentSessionConfigurations.update(json("""{"sessionUpdate":"agent_message_chunk"}"""), original))
    }
}

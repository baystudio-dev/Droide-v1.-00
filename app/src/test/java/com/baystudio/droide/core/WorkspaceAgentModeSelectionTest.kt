package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Candidate regression sources only. This file has NOT been compiled or executed. */
class WorkspaceAgentModeSelectionTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun config(current: String?, vararg ids: String) =
        WorkspaceAgentSessionConfiguration(modes = ids.toList(), currentModeId = current)
    private fun claude() = WorkspaceAgentModeSelection(listOf("plan"), listOf("default"))

    @Test fun originalClaudeFixtureCanLeavePlanUsingItsActualAdvertisedDefault() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/agent-acp/claude-session.json"))
            .bufferedReader().use { json(it.readText()) }
        val initial = WorkspaceAgentSessionConfigurations.merge(raw)
        val spec = WorkspaceAgentPluginCatalog.entries.single { it.id == "claude" }
        val state = WorkspaceAgentModeSelection(spec.planModeCandidates, spec.editModeCandidates)
        val plan = state.resolve(WorkspaceAgentMode.PLAN, initial)
        assertEquals("plan", plan.id)
        val planning = initial.copy(currentModeId = "plan")
        state.accepted(plan, planning)
        val edit = state.resolve(WorkspaceAgentMode.EDIT, planning)
        assertEquals("default", edit.id)
        assertTrue(edit.needsRequest)
        assertFalse(edit.usedSafeFallback)
    }

    @Test fun exactPreviouslyActiveApprovalProfileIsRestoredWithoutNormalization() {
        for (id in listOf("acceptEdits", "auto", "bypassPermissions", "Vendor/Profile:MiXeD")) {
            val state = claude()
            val old = config(id, id, "default", "plan")
            val plan = state.resolve(WorkspaceAgentMode.PLAN, old)
            val planning = old.copy(currentModeId = "plan")
            state.accepted(plan, planning)
            assertEquals(id, state.resolve(WorkspaceAgentMode.EDIT, planning).id)
        }
    }

    @Test fun repeatedPlanTurnsDoNotOverwriteTheSavedProfileOrResendMode() {
        val state = claude()
        val old = config("acceptEdits", "acceptEdits", "default", "plan")
        val first = state.resolve(WorkspaceAgentMode.PLAN, old)
        val planning = old.copy(currentModeId = "plan")
        state.accepted(first, planning)
        repeat(3) {
            val next = state.resolve(WorkspaceAgentMode.PLAN, planning)
            assertFalse(next.needsRequest)
            state.accepted(next, planning)
        }
        assertEquals("acceptEdits", state.resolve(WorkspaceAgentMode.EDIT, planning).id)
    }

    @Test fun alreadyActiveEditProfileIsPreservedRatherThanReplacedByASafeDefault() {
        val selected = claude().resolve(WorkspaceAgentMode.EDIT, config("acceptEdits", "default", "acceptEdits", "plan"))
        assertEquals("acceptEdits", selected.id)
        assertFalse(selected.needsRequest)
    }

    @Test fun restoredSessionWithUnknownHistoryUsesOnlySafeDefaultAndReportsFallback() {
        val selected = claude().resolve(WorkspaceAgentMode.EDIT,
            config("plan", "auto", "bypassPermissions", "acceptEdits", "plan", "default"))
        assertEquals("default", selected.id)
        assertTrue(selected.usedSafeFallback)
    }

    @Test fun missingSafeDefaultNeverSelectsAnAdvertisedPermissiveProfile() {
        val failure = runCatching { claude().resolve(WorkspaceAgentMode.EDIT,
            config("plan", "auto", "bypassPermissions", "acceptEdits", "plan")) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.startsWith("Build cannot be confirmed"))
        assertFalse(failure.exceptionOrNull()!!.message!!.contains("Select a native edit profile"))
    }

    @Test fun removedPreviousProfileUsesSafeDefaultRatherThanAnotherPermissiveChoice() {
        val state = claude()
        val old = config("acceptEdits", "acceptEdits", "default", "plan")
        val plan = state.resolve(WorkspaceAgentMode.PLAN, old)
        state.accepted(plan, old.copy(currentModeId = "plan"))
        val edit = state.resolve(WorkspaceAgentMode.EDIT, config("plan", "plan", "default", "auto"))
        assertEquals("default", edit.id)
        assertTrue(edit.usedSafeFallback)
    }

    @Test fun anUnacceptedPlanRequestCannotCreateARestorableApprovalProfile() {
        val state = claude()
        state.resolve(WorkspaceAgentMode.PLAN, config("bypassPermissions", "bypassPermissions", "plan", "default"))
        val edit = state.resolve(WorkspaceAgentMode.EDIT, config("plan", "bypassPermissions", "plan", "default"))
        assertEquals("default", edit.id)
        assertTrue(edit.usedSafeFallback)
    }

    @Test fun unknownOrCaseChangedPlanIdsAreNotGuessed() {
        assertTrue(runCatching { claude().resolve(WorkspaceAgentMode.PLAN, config("default", "default", "PLAN")) }.isFailure)
        assertTrue(runCatching { claude().resolve(WorkspaceAgentMode.PLAN, config(null)) }.isFailure)
    }

    @Test fun successfulLegacyAcknowledgementConfirmsExactCaseSensitiveRequest() {
        val old = config("plan", "plan", "acceptEdits")
        val (method, params) = WorkspaceAgentSessionConfigurations.modeRequest("same-session", old, "acceptEdits")
        assertEquals("session/set_mode", method)
        assertEquals("acceptEdits", params["modeId"]!!.jsonPrimitive.content)
        assertEquals("same-session", params["sessionId"]!!.jsonPrimitive.content)
        val changed = WorkspaceAgentSessionConfigurations.modeResponse(json("{}"), old, "acceptEdits", false)
        assertEquals("acceptEdits", changed.currentModeId)
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.modeRequest("same-session", old, "acceptedits") }.isFailure)
    }

    @Test fun modernModeUsesReportedConfigIdAndMustReturnAnAuthoritativeSnapshot() {
        val old = config("plan", "plan", "default").copy(modeConfigId = "vendor-mode-id")
        val (method, params) = WorkspaceAgentSessionConfigurations.modeRequest("same-session", old, "default")
        assertEquals("session/set_config_option", method)
        assertEquals("vendor-mode-id", params["configId"]!!.jsonPrimitive.content)
        assertEquals("default", params["value"]!!.jsonPrimitive.content)
        assertFalse("modeId" in params)
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.modeResponse(json("{}"), old, "default", true) }.isFailure)
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.modeResponse(json("""{"configOptions":[]}"""), old, "default", true) }.isFailure)
    }

    @Test fun contradictoryModernAndLegacyResultsCannotBeReplacedByRequestedValue() {
        val old = config("plan", "plan", "default").copy(modeConfigId = "mode")
        val modern = json("""{"configOptions":[{"id":"mode","type":"select","currentValue":"plan","options":[{"value":"plan"},{"value":"default"}]}]}""")
        val legacy = json("""{"modes":{"currentModeId":"plan","availableModes":[{"id":"plan"},{"id":"default"}]}}""")
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.modeResponse(modern, old, "default", true) }.isFailure)
        assertTrue(runCatching { WorkspaceAgentSessionConfigurations.modeResponse(legacy, old.copy(modeConfigId = null), "default", false) }.isFailure)
    }

    @Test fun acceptedModePreservesUnchangedModelsReasoningAndCommandChoices() {
        val old = config("plan", "plan", "default").copy(
            models = listOf(WorkspaceAgentModelOption("model", "Model")), currentModelId = "model",
            reasoningOptions = listOf(WorkspaceAgentModelOption("high", "High")), currentReasoningId = "high",
            availableCommands = listOf("help", "compact"))
        val changed = WorkspaceAgentSessionConfigurations.modeResponse(json("{}"), old, "default", false)
        assertEquals(old.copy(currentModeId = "default"), changed)
    }

    @Test fun nativeModeChangedAfterAcknowledgementBlocksTurnAdmission() {
        val state = claude()
        val current = config("default", "default", "plan")
        val selection = state.resolve(WorkspaceAgentMode.EDIT, current)
        assertTrue(runCatching { state.accepted(selection, current.copy(currentModeId = "plan")) }.isFailure)
    }

    @Test fun modeChangeObservedAfterActivationRejectsWriterAdmission() {
        val state = claude()
        val current = config("default", "default", "plan")
        val admission = state.admission(state.resolve(WorkspaceAgentMode.EDIT, current), current, 10L)
        assertTrue(runCatching { state.requireCurrent(admission, current.copy(currentModeId = "plan"), 11L) }.isFailure)
    }

    @Test fun observedModeChangeAwayAndBackCannotReuseEarlierAdmission() {
        val state = claude()
        val current = config("default", "default", "plan")
        val admission = state.admission(state.resolve(WorkspaceAgentMode.EDIT, current), current, 10L)
        // The mode revision owner observed default -> plan -> default after activation.
        assertTrue(runCatching { state.requireCurrent(admission, current, 12L) }.isFailure)
    }

    @Test fun unchangedModeRevisionRemainsAdmissibleButRemovedProfileDoesNot() {
        val state = claude()
        val current = config("default", "default", "plan")
        val admission = state.admission(state.resolve(WorkspaceAgentMode.EDIT, current), current, 10L)
        state.requireCurrent(admission, current.copy(availableCommands = listOf("help")), 10L)
        assertTrue(runCatching { state.requireCurrent(admission, current.copy(modes = listOf("plan")), 11L) }.isFailure)
    }
}

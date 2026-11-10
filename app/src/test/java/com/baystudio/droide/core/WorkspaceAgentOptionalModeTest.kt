package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentOptionalModeTest {
    private fun state() = WorkspaceAgentModeSelection(listOf("plan"), listOf("default"))
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun config(value: String) = WorkspaceAgentSessionConfigurations.merge(json(value))

    @Test fun absentModeUsesNativeDefaultWithoutRpcOrPlanClaim() {
        val config = config("""{"sessionId":"x"}""")
        val selector = state()
        val selection = selector.resolve(WorkspaceAgentMode.EDIT, config)
        assertTrue(selection.nativeDefault)
        assertFalse(selection.needsRequest)
        val admitted = selector.admission(selection, config, 1)
        selector.requireCurrent(admitted, config, 1)
        assertTrue(runCatching { selector.resolve(WorkspaceAgentMode.PLAN, config) }.isFailure)
    }
    @Test fun unrelatedModelConfigDoesNotRequireModes() {
        val config = config("""{"configOptions":[{"id":"model","category":"model","type":"select","currentValue":"a","options":[{"value":"a"}]}]}""")
        assertTrue(state().resolve(WorkspaceAgentMode.EDIT, config).nativeDefault)
    }
    @Test fun explicitlyMalformedOrEmptyModeCapabilityDoesNotBecomeDefault() {
        for (raw in listOf("""{"modes":null}""", """{"modes":{}}""", """{"configOptions":null}""",
            """{"configOptions":[{"id":"mode","type":"invalid"}]}""")) {
            assertTrue(raw, runCatching { state().resolve(WorkspaceAgentMode.EDIT, config(raw)) }.isFailure)
        }
    }
    @Test fun disappearingObservedModeCannotDowngradeToAbsentCapability() {
        val configured = config("""{"configOptions":[{"id":"mode","category":"mode","type":"select","currentValue":"default","options":[{"value":"default"}]}]}""")
        val missing = WorkspaceAgentSessionConfigurations.merge(json("""{"configOptions":[]}"""), configured)
        assertTrue(missing.modeCapabilityObserved)
        assertTrue(runCatching { state().resolve(WorkspaceAgentMode.EDIT, missing) }.isFailure)
    }
    @Test fun unsolicitedModeUpdateDisablesDefaultAdmission() {
        val original = WorkspaceAgentSessionConfiguration()
        val selector = state()
        val admission = selector.admission(selector.resolve(WorkspaceAgentMode.EDIT, original), original, 1)
        val updated = WorkspaceAgentSessionConfigurations.update(json("""{"sessionUpdate":"current_mode_update","currentModeId":"plan"}"""), original)
        assertTrue(runCatching { selector.requireCurrent(admission, updated, 2) }.isFailure)
        assertTrue(runCatching { selector.resolve(WorkspaceAgentMode.EDIT, updated) }.isFailure)
    }
    @Test fun sameConfigurationWithNewRevisionCannotReuseAdmission() {
        val original = WorkspaceAgentSessionConfiguration()
        val selector = state()
        val admission = selector.admission(selector.resolve(WorkspaceAgentMode.EDIT, original), original, 1)
        assertTrue(runCatching { selector.requireCurrent(admission, original, 2) }.isFailure)
    }
    @Test fun collaborationMetadataNeverUsesModeLessCompatibility() {
        val config = WorkspaceAgentSessionConfiguration(collaborationModeObserved = true)
        assertTrue(runCatching { state().resolve(WorkspaceAgentMode.EDIT, config) }.isFailure)
    }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Additional unit regressions authored for this run. Real production classes, no stubs. */
class WorkspaceAgentModeGuardAdditionalTest {
    private fun state() = WorkspaceAgentModeSelection(listOf("plan"), listOf("default"))
    private fun config(current: String?, vararg ids: String) =
        WorkspaceAgentSessionConfiguration(modes = ids.toList(), currentModeId = current)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun rejected(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Guard must reject the operation", failure)
        assertTrue("Expected production guard, got $failure", failure is IllegalStateException || failure is IllegalArgumentException)
    }

    @Test fun rejectedLaterPlanAcknowledgementDoesNotOverwriteEarlierSavedProfile() {
        val selection = state()
        val first = config("acceptEdits", "acceptEdits", "auto", "plan", "default")
        selection.accepted(selection.resolve(WorkspaceAgentMode.PLAN, first), first.copy(currentModeId = "plan"))
        val second = first.copy(currentModeId = "auto")
        val request = selection.resolve(WorkspaceAgentMode.PLAN, second)
        rejected { selection.accepted(request, second) }
        assertEquals("acceptEdits", selection.resolve(WorkspaceAgentMode.EDIT, first.copy(currentModeId = "plan")).id)
    }

    @Test fun modeMismatchRejectsWriterEvenWhenRevisionHasNotChanged() {
        val selection = state()
        val current = config("default", "default", "plan")
        val admission = selection.admission(selection.resolve(WorkspaceAgentMode.EDIT, current), current, 7L)
        rejected { selection.requireCurrent(admission, current.copy(currentModeId = "plan"), 7L) }
    }

    @Test fun removedProfileRejectsWriterEvenWhenModeAndRevisionHaveNotChanged() {
        val selection = state()
        val current = config("default", "default", "plan")
        val admission = selection.admission(selection.resolve(WorkspaceAgentMode.EDIT, current), current, 7L)
        rejected { selection.requireCurrent(admission, current.copy(modes = listOf("plan")), 7L) }
    }

    @Test fun failedAdmissionCannotSaveAnUnconfirmedPlanOrigin() {
        val selection = state()
        val current = config("auto", "auto", "default", "plan")
        val plan = selection.resolve(WorkspaceAgentMode.PLAN, current)
        // currentModeId alone is insufficient: the accepted profile must still be advertised.
        rejected { selection.admission(plan, current.copy(currentModeId = "plan", modes = listOf("auto", "default")), 1L) }
        val next = selection.resolve(WorkspaceAgentMode.EDIT, current.copy(currentModeId = "plan"))
        assertEquals("default", next.id)
        assertTrue(next.usedSafeFallback)
    }

    @Test fun separateSessionInstancesCannotShareSavedApprovalProfiles() {
        val oldSession = state()
        val configuration = config("auto", "auto", "default", "plan")
        val planning = configuration.copy(currentModeId = "plan")
        oldSession.accepted(oldSession.resolve(WorkspaceAgentMode.PLAN, configuration), planning)
        assertEquals("auto", oldSession.resolve(WorkspaceAgentMode.EDIT, planning).id)
        val newSession = state().resolve(WorkspaceAgentMode.EDIT, planning)
        assertEquals("default", newSession.id)
        assertTrue(newSession.usedSafeFallback)
    }

    @Test fun alreadyActiveSecondPlanAliasIsNotReplacedByFirstCandidate() {
        val selection = WorkspaceAgentModeSelection(listOf("plan", "vendor-review"), listOf("default"))
        val resolved = selection.resolve(WorkspaceAgentMode.PLAN, config("vendor-review", "plan", "vendor-review", "default"))
        assertEquals("vendor-review", resolved.id)
        assertFalse(resolved.needsRequest)
        assertNull(resolved.enteringPlanFrom)
    }

    @Test fun safeFallbackCandidatesThatAreAlsoPlanAliasesCannotAuthorizeBuild() {
        val selection = WorkspaceAgentModeSelection(listOf("plan", "review"), listOf("review", "plan"))
        rejected { selection.resolve(WorkspaceAgentMode.EDIT, config("plan", "plan", "review", "auto")) }
    }

    @Test fun unadvertisedCurrentProfileCannotBecomeTheRestorationTarget() {
        val selection = state()
        val before = config("auto", "default", "plan")
        val request = selection.resolve(WorkspaceAgentMode.PLAN, before)
        assertNull(request.enteringPlanFrom)
        val planning = before.copy(currentModeId = "plan", modes = listOf("default", "plan", "auto"))
        selection.accepted(request, planning)
        val after = selection.resolve(WorkspaceAgentMode.EDIT, planning)
        assertEquals("default", after.id)
        assertTrue(after.usedSafeFallback)
    }

    @Test fun modernAcknowledgementCannotConfirmAnUnadvertisedRequestedProfile() {
        val prior = config("plan", "plan", "default").copy(modeConfigId = "mode")
        val reply = json("""{"configOptions":[{"id":"mode","type":"select","currentValue":"default","options":[{"value":"plan"}]}]}""")
        rejected { WorkspaceAgentSessionConfigurations.modeResponse(reply, prior, "default", true) }
    }

    @Test fun malformedModernConfigurationContainersCannotCountAsAcknowledgements() {
        // Keep the mode aligned so currentModeId/profile checks cannot mask a missing shape guard.
        val prior = config("default", "plan", "default").copy(modeConfigId = "mode")
        val valid = json("""{"configOptions":[{"id":"mode","type":"select","currentValue":"default","options":[{"value":"plan"},{"value":"default"}]}]}""")
        assertEquals("default", WorkspaceAgentSessionConfigurations.modeResponse(valid, prior, "default", true).currentModeId)
        for (container in listOf("null", "false", "42", "{}", "\"default\"")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                WorkspaceAgentSessionConfigurations.modeResponse(json("{\"configOptions\":$container}"), prior, "default", true)
            }
            assertEquals("CLI did not return its updated mode configuration. No prompt was sent.", failure.message)
        }
    }

    @Test fun legacyAcknowledgementCannotConfirmAnUnadvertisedRequestedProfile() {
        val prior = config("plan", "plan", "default")
        val reply = json("""{"modes":{"currentModeId":"default","availableModes":[{"id":"plan"}]}}""")
        rejected { WorkspaceAgentSessionConfigurations.modeResponse(reply, prior, "default", false) }
    }

    @Test fun validModernSnapshotConfirmsExactRequestedOpaqueMode() {
        val target = "Vendor/Profile:MiXeD"
        val prior = config("plan", "plan", target).copy(modeConfigId = "vendor-mode-id")
        val (method, request) = WorkspaceAgentSessionConfigurations.modeRequest("kept-native-session", prior, target)
        assertEquals("session/set_config_option", method)
        assertEquals("kept-native-session", request["sessionId"]!!.jsonPrimitive.content)
        assertEquals("vendor-mode-id", request["configId"]!!.jsonPrimitive.content)
        assertEquals(target, request["value"]!!.jsonPrimitive.content)
        val reply = json("""{"configOptions":[{"id":"vendor-mode-id","category":"mode","type":"select","currentValue":"Vendor/Profile:MiXeD","options":[{"value":"plan"},{"value":"Vendor/Profile:MiXeD"}]}]}""")
        val confirmed = WorkspaceAgentSessionConfigurations.modeResponse(reply, prior, target, true)
        assertEquals(prior.copy(currentModeId = target, modeCapabilityObserved = true), confirmed)
    }

    @Test fun initialReadOnlyPolicyRejectsAllMutationKindsCaseInsensitively() {
        val policy = WorkspaceAgentAccessPolicy()
        assertEquals(WorkspaceAgentMode.PLAN, policy.currentMode())
        assertFalse(policy.allowsWorkspaceMutation())
        rejected { policy.requireMutation("write") }
        for (kind in listOf("edit", "delete", "move", "execute", "write", "create")) {
            assertTrue(kind, policy.rejectsPermissionKind(kind))
            assertTrue(kind.uppercase(), policy.rejectsPermissionKind(kind.uppercase()))
        }
    }

    @Test fun returningToPlanImmediatelyRevokesPreviouslyGrantedMutationAccess() {
        val policy = WorkspaceAgentAccessPolicy()
        policy.setMode(WorkspaceAgentMode.EDIT)
        assertTrue(policy.allowsWorkspaceMutation())
        policy.requireMutation("write")
        assertFalse(policy.rejectsPermissionKind("execute"))
        policy.setMode(WorkspaceAgentMode.PLAN)
        assertFalse(policy.allowsWorkspaceMutation())
        assertTrue(policy.rejectsPermissionKind("execute"))
        rejected { policy.requireMutation("write after transition") }
    }
}

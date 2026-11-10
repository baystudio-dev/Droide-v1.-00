package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalProjectAnalysisChoiceTest {
    @Test(timeout=10000) fun sessionChoicesCannotSubstituteForExplicitProjectAnalysisAction() = runBlocking {
        val manager = ApprovalManager()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval(kind = "lsp_project_analysis", summary = "Enable analysis", detail = "Project code may execute",
                action = "enable_project_analysis", resource = "/workspace/project",
                permissionScope = "", approvalSessionId = "", sessionGrantAction = "", sessionGrantResource = "")
        }
        val request = manager.pending.value!!
        assertFalse(request.canAllowForSession)
        assertFalse(manager.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        manager.resolve(ApprovalResolution.ALLOW_SESSION)
        assertFalse(pending.isCompleted); assertEquals(request.id, manager.pending.value!!.id)
        assertTrue(manager.resolve(request.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(ApprovalDecision.Approved, pending.await())
        assertFalse(manager.resolve(request.id, ApprovalResolution.ALLOW_ONCE)); assertNull(manager.pending.value)
    }

    @Test(timeout=10000) fun priorKindsRetainTheirLegacyChoiceCompatibility() = runBlocking {
        val manager = ApprovalManager()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval(kind = "existing_test_kind", summary = "Existing action", detail = "Existing action",
                permissionScope = "", approvalSessionId = "", sessionGrantAction = "", sessionGrantResource = "")
        }
        assertFalse(manager.pending.value!!.canAllowForSession)
        manager.resolve(ApprovalResolution.ALLOW_SESSION)
        assertEquals(ApprovalDecision.Approved, pending.await()); assertNull(manager.pending.value)
    }
}

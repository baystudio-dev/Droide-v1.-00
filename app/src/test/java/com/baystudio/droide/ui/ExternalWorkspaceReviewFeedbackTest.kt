package com.baystudio.droide.ui

import com.baystudio.droide.core.WorkspaceAgentSyncPhase
import com.baystudio.droide.core.WorkspaceAgentSyncState
import org.junit.Assert.*
import org.junit.Test

class ExternalWorkspaceReviewFeedbackTest {
    private fun pending(reason: String) = WorkspaceAgentSyncState(WorkspaceAgentSyncPhase.PENDING, "/project", "/review", listOf("a.html"), 1, reason)
    @Test fun normalApplyIsReported() {
        val result = ExternalWorkspaceReviewFeedback().messages("agent:1", listOf("a.html"), emptyList(), null, false)
        assertEquals(listOf("Workspace changes applied: a.html"), result)
    }
    @Test fun deniedReviewExplainsReasonAndRetry() {
        val result = ExternalWorkspaceReviewFeedback().messages("agent:1", emptyList(), listOf("a.html"), pending("Project application was not approved."), true).single()
        assertTrue(result.contains("not approved")); assertTrue(result.contains("Open Files")); assertTrue(result.contains("retained"))
    }
    @Test fun conflictReasonIsNotReplacedByGenericPending() {
        val result = ExternalWorkspaceReviewFeedback().messages("agent:1", emptyList(), listOf("a.html"), pending("Project and CLI both changed."), true).single()
        assertTrue(result.contains("Project and CLI both changed.")); assertFalse(result.contains("All reviewed changes were applied"))
    }
    @Test fun identicalAutomaticPendingIsSuppressedAcrossTurns() {
        val feedback = ExternalWorkspaceReviewFeedback()
        assertEquals(1, feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("reason"), false).size)
        assertTrue(feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("reason"), false).isEmpty())
    }
    @Test fun changedReasonAndPendingSetAreNotSuppressed() {
        val feedback = ExternalWorkspaceReviewFeedback()
        feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("reason"), false)
        assertEquals(1, feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("new reason"), false).size)
        assertEquals(1, feedback.messages("agent:1", emptyList(), listOf("a.html", "b.html"), pending("new reason"), false).size)
    }
    @Test fun explicitRetryAlwaysReturnsFeedbackAndSuccessResetsSuppression() {
        val feedback = ExternalWorkspaceReviewFeedback()
        repeat(2) { assertEquals(1, feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("reason"), true).size) }
        assertTrue(feedback.messages("agent:1", listOf("a.html"), emptyList(), null, true).last().contains("All reviewed changes were applied"))
        assertEquals(1, feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("reason"), false).size)
    }
    @Test fun partialSuccessKeepsBothOutcomeAndPendingReason() {
        val result = ExternalWorkspaceReviewFeedback().messages("agent:1", listOf("b.html"), listOf("a.html"), pending("Local file changed."), true)
        assertEquals(2, result.size); assertTrue(result[0].contains("b.html")); assertTrue(result[1].contains("Local file changed."))
    }
    @Test fun sessionAndWorkspaceChangesDoNotHidePending() {
        val feedback = ExternalWorkspaceReviewFeedback()
        feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("reason"), false)
        assertEquals(1, feedback.messages("agent:2", emptyList(), listOf("a.html"), pending("reason"), false).size)
        assertEquals(1, feedback.messages("agent:1", emptyList(), listOf("a.html"), pending("reason").copy(sourceRoot="/other"), false).size)
    }
    @Test fun interruptedReasonAndEmptyManualReviewRemainVisible() {
        val feedback = ExternalWorkspaceReviewFeedback()
        assertTrue(feedback.messages("agent:1", emptyList(), emptyList(), pending("Storage unavailable").copy(phase=WorkspaceAgentSyncPhase.INTERRUPTED), true).single().contains("Storage unavailable"))
        assertEquals("Workspace review complete. No pending file changes remain.", feedback.messages("agent:1", emptyList(), emptyList(), null, true).single())
    }
}

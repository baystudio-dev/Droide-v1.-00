package com.baystudio.droide.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** Wording contracts, not proof of model compliance or semantic equivalence. */
class AgentPromptCompressionPolicyTest {
    private fun retains(vararg phrases: String) = phrases.forEach {
        assertTrue("Missing policy: $it", AgentSystemPrompt.TEXT.contains(it))
    }
    @Test fun webBudgetRetainsEscalationAndUncertainEffectLimits() = retains(
        "required content unavailable to the text reader", "Interaction tasks may go directly to browser",
        "fallback URLs are options", "CAPTCHA may require the user", "no second browser",
        "DOM before pixels", "wait for foreground/recovery", "uncertain effects")
    @Test fun exactReadsAndContinuationCannotBecomeCompletionEvidence() = retains(
        "UTF-16 code units, not bytes", "Do not combine offsets with start/end lines",
        "Continue has_more=true", "recheck live revision", "never silently skip clipped text",
        "Compaction means continuation, not completion", "running command IDs/cursors",
        "preserve editor revision checks", "honest continuation checkpoint")
    @Test fun dirtyDocumentAuthorityAndScopedInstructionsSurviveCompression() = retains(
        "Search overlays dirty live buffers once", "suppressing their stale disk matches",
        "possibly unsaved text", "persisted disk until explicit save",
        "expected_document_id/expected_revision/expected_change_version",
        "must not overwrite newer user edits", "Context must match uniquely",
        "call project_instructions(path)", "nearer rules overriding broader within scope",
        "Continue incomplete instructions/read/search results", "CAS preserves unsaved updates",
        "Dirty-buffer delete and open-live-document patch-move fail closed")
    @Test fun verificationAndRollbackKeepTheirLimits() = retains(
        "presence never proves workload success", "Not universal commands",
        "runs supported source, not a binary build", "the user must save dirty buffers",
        "Edit success is not repair success", "only the latest tracked Agent edit/patch",
        "never instead of inspecting user changes")
}

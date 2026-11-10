package com.baystudio.droide.core

/** Completion comes from the supervised runner, never markers printed by the target. */
internal object AgentCapabilityResult {
    fun render(result: RunnerExecutionResult): String = buildString {
        appendLine("CAPABILITY_RUN")
        appendLine("outcome=${if (result.success) "succeeded" else "failed"}")
        appendLine("operation_started=${result.processStarted}")
        result.exitCode?.let { appendLine("exit=$it") }
        appendLine("timed_out=${result.timedOut}")
        appendLine("output:")
        append(result.output)
    }

    fun evidence(raw: String): AgentToolEvidence {
        val lines = raw.lineSequence().take(3).toList()
        val success = lines.getOrNull(1) == "outcome=succeeded"
        val failed = lines.getOrNull(1) == "outcome=failed"
        val started = when (lines.getOrNull(2)) {
            "operation_started=true" -> true
            "operation_started=false" -> false
            else -> null
        }
        if (lines.firstOrNull() != "CAPABILITY_RUN" || (!success && !failed) || started == null || (success && !started))
            return AgentToolEvidence(AgentToolEvidenceState.FAILED, false, false, "Capability returned no valid execution evidence")
        return AgentToolEvidence(if (success) AgentToolEvidenceState.SUCCEEDED else AgentToolEvidenceState.FAILED,
            started, success, "Capability ${if (success) "completed successfully" else "failed"}")
    }
}

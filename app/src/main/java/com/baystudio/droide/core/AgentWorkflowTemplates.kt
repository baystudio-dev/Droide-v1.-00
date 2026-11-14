package com.baystudio.droide.core

/** Reusable skill-backed plans; execution remains owned by the selected agent and its normal permissions. */
internal object AgentWorkflowTemplates {
    data class Template(val skill: String, val title: String, val description: String, val instructions: String) {
        val invocation: String get() = "@skill/$skill "
    }

    val entries = listOf(
        Template("workflow-review", "Review a project", "Inspect relevant code, check risks, and report evidence.", """
            Ask for the review target if it is missing. Use the current project's instructions and relevant
            files only. Create a short task list when the work has multiple steps. Work through: understand
            the affected behavior; inspect implementation and dependencies; run focused read-only checks;
            report actionable findings with paths and evidence. Do not edit files merely to complete a review.
            Distinguish verified findings from concerns and state which checks could not run.
        """),
        Template("workflow-repair", "Repair and verify", "Diagnose a problem, make a focused fix, and verify the result.", """
            Obtain the target problem from the user; do not invent one. Inspect project instructions and the
            relevant code, reproduce the failure when possible, then record a short task list. Preserve
            unrelated edits. Make the smallest maintainable fix using the editor/workspace tools. Run the
            checks relevant to the behavior and inspect their results. Allow at most two repair-and-check
            cycles for the same failure before explaining the blocker and the evidence needed to continue.
            Report changed paths, validation, and remaining uncertainty. Never mark a failed check as passed.
        """),
        Template("workflow-build", "Build and report", "Use the project's build configuration and identify its outputs.", """
            Determine the user's requested build target and existing project instructions/configuration.
            If the target is ambiguous, ask before choosing release signing or a different platform. Record
            the plan, check only the required runtime, and use normal toolchain/build tools and permissions.
            Diagnose an actual missing dependency before installing it. Preserve signing credentials and
            existing build settings. Do not publish or deploy merely because a build was requested.
            Verify command completion and the resulting artifacts; report exact project-relative output
            paths. On the built-in agent, use send_media with reference_only=true to register large build
            outputs in /results without copying them. Use a normal attachment for supported small files when
            requested. External agents use their available file/media tools or direct Explorer export.
            After two unsuccessful attempts at the same failure, report its cause/evidence and next step.
        """),
    )

    val executionRules = """
        Execute steps in order and update the existing task list only from observed results. Use the
        selected agent's normal step/time limits, permission flow, cancellation and Stop controls. Do not
        create an automatic retry loop or restart stopped work. After interruption, inspect saved results
        and affected files/processes before repeating an action whose outcome is unknown. Load only the
        context required for the current step. A skill is a plan, not permission to bypass a denied tool.
    """.trimIndent()
}

package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object AgentCommandSessions {
    const val STOP_REQUEST_NOTICE = "Stop requested; process exit and cleanup are unconfirmed. Detached processes may still be running."
    const val FINAL_AGENT_CHARS = 12_000
    const val EVIDENCE_RESERVE_CHARS = 128
    const val HOOK_OMITTED = "\n[hook context omitted: result size limit]"
    const val HOOK_TRUNCATED = "\n[hook context truncated: result size limit]"
    const val COMMAND_BODY_CHARS = FINAL_AGENT_CHARS - EVIDENCE_RESERVE_CHARS
    val PRIMARY_COMMAND_CHARS = COMMAND_BODY_CHARS - HOOK_OMITTED.length
    private val commandTools = setOf("run_command", "bash", "exec_command", "write_stdin")

    fun usesAgentEnvelope(tool: String): Boolean = tool in commandTools

    /** Recognition only, never permission or execution authority. DENY is checked by the caller. */
    fun isQuietObservation(tool: String, args: JsonObject?, job: BackgroundCommandManager.Snapshot?): Boolean {
        if (tool != "write_stdin" || args == null || job == null || job.state != BackgroundCommandManager.State.RUNNING) return false
        if (args.keys.any { it !in setOf("session_id", "chars", "output_cursor", "yield_time_ms", "wait_for_completion", "close_stdin", "interrupt") }) return false
        fun text(name: String): String? = (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(name: String): Boolean? = if (name !in args) false else
            (args[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        fun number(name: String, default: Long): Long? = if (name !in args) default else
            (args[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (text("session_id") != job.id || ("chars" in args && text("chars") != "")) return false
        if (flag("close_stdin") != false || flag("interrupt") != false || flag("wait_for_completion") == null) return false
        // A zero wait stays subject to repetition review, including an explicitly
        // contradictory zero-yield/completion request. Behind-end cursors replay output.
        val wait = number("yield_time_ms", 1_000) ?: return false
        val from = number("output_cursor", 0) ?: return false
        return wait in 1..30_000 && from >= 0 && from == job.outputEndCursor
    }

    fun cursor(args: JsonObject): Long = args["output_cursor"]?.jsonPrimitive?.longOrNull ?: 0
    fun yieldMs(args: JsonObject, default: Long = 1_000): Long =
        (args["yield_time_ms"]?.jsonPrimitive?.longOrNull ?: default).also {
            require(it in 0..30_000) { "yield_time_ms must be between 0 and 30000" }
        }

    fun render(job: BackgroundCommandManager.Snapshot, cursor: Long, interrupted: Boolean = false): String =
        renderPage(job, cursor, interrupted, "", "", PRIMARY_COMMAND_CHARS)

    /** Assemble the trusted envelope before choosing output or advancing its cursor. */
    fun renderForAgent(job: BackgroundCommandManager.Snapshot, cursor: Long, operation: String,
        interrupted: Boolean = false, reconciliation: WorkspacePersistedReconciliation? = null,
        includeContinuation: Boolean = false): String {
        val prefix = job.launchDiskState.annotate(operation, "")
        val continuation = if (includeContinuation && job.state == BackgroundCommandManager.State.RUNNING)
            "\nProcess continues. Resume with write_stdin(session_id=\"${job.id}\", output_cursor=next_output_cursor, yield_time_ms=1000) or inspect background_job. Do not restart this command or claim completion."
            else ""
        return renderPage(job, cursor, interrupted, prefix,
            continuation + reconciliation.agentCommandReconciliationNotice(operation), PRIMARY_COMMAND_CHARS)
    }

    /** Embedded callers have no cursor. Retain their exit wrapper and mark clipping explicitly. */
    fun renderEmbeddedForAgent(state: AgentDiskExecutionState, operation: String, result: String,
        reconciliation: WorkspacePersistedReconciliation?): String {
        val prefix = state.annotate(operation, "")
        val suffix = reconciliation.agentCommandReconciliationNotice(operation)
        val available = PRIMARY_COMMAND_CHARS - prefix.length - suffix.length
        require(available >= 128) { "Command metadata exceeds the Agent output budget" }
        val omitted = "\n[command output truncated: result size limit; no output cursor is available]"
        val body = if (result.length <= available) result else safePrefix(result, available - omitted.length) + omitted
        return prefix + body + suffix
    }

    /** The admitted tool name selects this path; stdout marker text is never inspected. */
    fun appendHookContext(primary: String, context: String): String {
        if (primary.length > PRIMARY_COMMAND_CHARS) return "ERROR: command result exceeded its producer output budget; the result was withheld to avoid an invalid continuation cursor. Inspect background_job before deciding what to do next. Do not restart the command automatically."
        if (context.isBlank()) return primary
        val room = COMMAND_BODY_CHARS - primary.length
        val heading = "\n[hook context]\n"
        val bounded = safePrefix(context, HookLifecycleProtocol.MAX_CONTEXT_CHARS)
        val contextNotice = if (bounded.length < context.length) HOOK_TRUNCATED else ""
        if (heading.length + bounded.length + contextNotice.length <= room) return primary + heading + bounded + contextNotice
        if (room < heading.length + HOOK_TRUNCATED.length + 1) return primary + HOOK_OMITTED
        return primary + heading + safePrefix(bounded, room - heading.length - HOOK_TRUNCATED.length) + HOOK_TRUNCATED
    }

    private fun renderPage(job: BackgroundCommandManager.Snapshot, cursor: Long, interrupted: Boolean,
        prefix: String, suffix: String, limit: Int): String {
        require(cursor >= 0 && cursor <= job.outputEndCursor) { "Invalid session output cursor" }
        val start = job.outputEndCursor - job.output.length
        val from = maxOf(cursor, start)
        val remaining = job.output.substring((from - start).toInt())
        val header = buildString {
            appendLine("COMMAND_SESSION id=${job.id} state=${job.state.name.lowercase()} backend=${if (job.interactivePty) "pty" else "pipe"} " +
                "stdin_available=${job.stdinAvailable} interrupted=$interrupted timeout_ms=${job.timeoutMs ?: "none"}")
            if (job.interactivePty) appendLine("Interactive process stays in its original terminal tab; output contains bounded screen snapshots. close_stdin sends Ctrl+D; input remains available if the process continues. Resume with this session ID, not a new exec_command.")
            job.exitCode?.let { appendLine("exit=$it") }
            if (job.state == BackgroundCommandManager.State.KILLED) appendLine(STOP_REQUEST_NOTICE)
            if (job.state == BackgroundCommandManager.State.TIMED_OUT) appendLine("The requested process lifetime deadline expired; polling did not extend it. This session cannot accept further input.")
        }
        fun metadata(next: Long): String = buildJsonObject {
            put("session_id", job.id)
            put("next_output_cursor", next)
            put("output_start_cursor", start)
            put("output_end_cursor", job.outputEndCursor)
            put("output_lost", cursor < start)
            put("more_output", next < job.outputEndCursor)
            put("output_incomplete", job.outputIncomplete)
        }.toString() + "\n"
        val guidance = if (job.state != BackgroundCommandManager.State.RUNNING) job.exitCode?.let { exit ->
            TerminalFailureDiagnostics.hint(exit, job.output, job.command)?.let { "\n[DROIDE_COMMAND_GUIDANCE]\n$it" }
        }.orEmpty() else ""
        // end cursor has the greatest decimal width; more_output=false is longer than true.
        val available = limit - prefix.length - header.length - metadata(job.outputEndCursor).length - guidance.length - suffix.length
        require(available >= 256) { "Command metadata exceeds the Agent output budget" }
        val page = remaining.take(minOf(8_000, available))
        // The returned cursor must never split a supplementary Unicode code
        // point, or resuming would send two invalid strings instead of an emoji.
        val delta = if (page.lastOrNull()?.isHighSurrogate() == true && remaining.length > page.length &&
            remaining[page.length].isLowSurrogate()) page.dropLast(1) else page
        val next = from + delta.length
        return buildString {
            append(prefix).append(header).append(metadata(next))
            append(delta.ifEmpty {
                when {
                    job.state == BackgroundCommandManager.State.RUNNING ->
                        "(still running; no new output yet. Resume this session ID; do not rerun the command.)"
                    job.state == BackgroundCommandManager.State.KILLED -> "(no additional output; stop requested; process exit and cleanup are unconfirmed)"
                    job.outputEndCursor > 0 -> "(no additional output; this command has finished)"
                    job.state == BackgroundCommandManager.State.EXITED && job.exitCode == 0 ->
                        "(completed successfully; the command produced no output)"
                    job.state == BackgroundCommandManager.State.TIMED_OUT -> "(command timed out; no output)"
                    else -> "(command failed; no output. Check the exit code above.)"
                }
            })
            append(guidance).append(suffix)
        }.also { check(it.length <= limit) { "Command envelope budget accounting failed" } }
    }

    private fun safePrefix(text: String, maxChars: Int): String {
        val head = text.take(maxChars.coerceAtLeast(0))
        return if (head.lastOrNull()?.isHighSurrogate() == true && text.length > head.length &&
            text[head.length].isLowSurrogate()) head.dropLast(1) else head
    }
}

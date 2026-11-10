package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal suspend fun AgentTools.Ctx.diskBacked(
    operation: String,
    reconcileAfter: Boolean = false,
    commandEnvelope: Boolean = false,
    block: suspend () -> String,
): String {
    val state = AgentDiskExecutionState.capture(documentAuthority)
    val output = try {
        block()
    } catch (t: Throwable) {
        if (reconcileAfter) withContext(NonCancellable) {
            runSuspendCatching { documentAuthority?.reconcileAfterExternalCommand() }
        }
        throw t
    }
    var refreshWarning = ""
    val reconciliation = if (reconcileAfter) try {
        documentAuthority?.reconcileAfterExternalCommand()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        refreshWarning = "\n[workspace refresh pending] ${failure.message?.take(300) ?: failure.javaClass.simpleName}"
        null
    } else null
    val result = if (commandEnvelope) AgentCommandSessions.renderEmbeddedForAgent(state, operation, output, reconciliation)
    else state.annotate(operation, output) + reconciliation.agentReconciliationNotice(operation)
    return result + refreshWarning
}

/** Command-only bounded notice. Keep the complete control line and critical warning. */
internal fun WorkspacePersistedReconciliation?.agentCommandReconciliationNotice(operation: String): String {
    val result = this ?: return ""
    if (!result.changed && result.conflicts.isEmpty()) return ""
    val safeOperation = operation.replace(Regex("[\\r\\n\\t]+"), " ").take(80)
    val warning = if (result.conflicts.isNotEmpty())
        "\nWARNING: persisted workspace changed behind unsaved editor buffers. Live editor text was preserved; review/reload before saving over disk."
        else ""
    val labels = result.conflicts.take(12).map { path ->
        val safe = if (SensitivePathPolicy.isSensitive(path)) "<sensitive-path>" else path.replace(Regex("[\\r\\n\\t]+"), " ")
        val head = safe.take(120).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        head to (safe.length > head.length)
    }
    var shown = labels.size
    while (true) {
        val truncated = shown < result.conflicts.size || labels.take(shown).any { it.second }
        val header = "\n[DROIDE_EDITOR_RECONCILE operation=$safeOperation refreshed=${result.refreshed.size} removed=${result.removed.size} remapped=${result.remapped.size} conflicts=${result.conflicts.size} details_truncated=$truncated]"
        val detail = if (shown == 0) "" else "\nConflict paths: " + labels.take(shown).joinToString(", ") { it.first }
        val omitted = if (truncated) "\nSome conflict path details were omitted or shortened to fit this result." else ""
        val notice = header + warning + detail + omitted
        if (notice.length <= 1_024) return notice
        check(shown > 0) { "Command reconciliation control metadata exceeds its budget" }
        shown--
    }
}

internal fun WorkspacePersistedReconciliation?.agentReconciliationNotice(operation: String): String {
    val result = this ?: return ""
    if (!result.changed && result.conflicts.isEmpty()) return ""
    return buildString {
        append("\n[DROIDE_EDITOR_RECONCILE operation=")
        append(operation)
        append(" refreshed=")
        append(result.refreshed.size)
        append(" removed=")
        append(result.removed.size)
        append(" remapped=")
        append(result.remapped.size)
        append(" conflicts=")
        append(result.conflicts.size)
        append(']')
        if (result.conflicts.isNotEmpty()) {
            append("\nWARNING: persisted workspace changed behind unsaved editor buffer(s): ")
            append(result.conflicts.take(12).joinToString(", "))
            if (result.conflicts.size > 12) append(" …")
            append(". Live editor text was preserved; review/reload before saving over disk.")
        }
    }
}

internal fun BackgroundCommandManager.Snapshot.agentSummary(includeDiskNotice: Boolean = false): String {
    val exit = exitCode?.let { " exit=$it" }.orEmpty()
    val timeout = timeoutMs?.let { " timeout_ms=$it" } ?: " timeout=none"
    val dirty = if (launchDiskState.hasDirtyBuffers) " launch_dirty_buffers=${launchDiskState.dirtyCount}" else ""
    val summary = "id=$id state=${state.name.lowercase()}$exit$timeout$dirty output_incomplete=$outputIncomplete command=${command.take(500)}" +
        if (state == BackgroundCommandManager.State.KILLED) "\n${AgentCommandSessions.STOP_REQUEST_NOTICE}" else ""
    return if (includeDiskNotice) launchDiskState.annotate("background_job:$id:launch", summary) else summary
}

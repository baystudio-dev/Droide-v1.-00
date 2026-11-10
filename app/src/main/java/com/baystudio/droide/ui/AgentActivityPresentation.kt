package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.baystudio.droide.core.*

// A dock can constrain optional metadata without changing normal transcript callers.
internal val LocalAgentStatusDetailMaxHeight = staticCompositionLocalOf { Dp.Infinity }

internal enum class AgentActivityKind(val label: String, val spinning: Boolean = true) {
    WORKING("Working…"), THINKING("Thinking…"), RESPONDING("Responding…"),
    PREPARING("Preparing tool…"), READING("Reading files…"), SEARCHING("Searching workspace…"),
    EDITING("Editing files…"), COMMAND("Running command…"), TOOL("Running tool…"),
    APPROVAL("Waiting for approval", false), INPUT("Waiting for your input", false),
    RETRYING("Retrying…"), COMPACTING("Compacting context…"),
    COMPLETED("Completed", false), STOPPED("Stopped", false), FAILED("Failed", false);
}

internal data class AgentActivityPresentation(val kind: AgentActivityKind, val detail: String = "")

internal fun AgentStreamPart.visibleInChat(details: Boolean): Boolean = details || kind != AgentStreamPartKind.STEP ||
    state == AgentStreamPartState.ERROR || state == AgentStreamPartState.CANCELLED

internal fun visibleAgentStreamParts(parts: List<AgentStreamPart>, details: Boolean): List<AgentStreamPart> =
    if (details) parts else parts.filter { it.visibleInChat(false) }

internal fun nativeAgentActivity(state: AgentStreamState, approval: Boolean = false, input: Boolean = false): AgentActivityPresentation {
    when (state.status) {
        AgentStreamState.Status.ERROR -> return AgentActivityPresentation(AgentActivityKind.FAILED)
        AgentStreamState.Status.CANCELLED -> return AgentActivityPresentation(AgentActivityKind.STOPPED)
        AgentStreamState.Status.IDLE -> return AgentActivityPresentation(if (state.endedAtMs != null) AgentActivityKind.COMPLETED else AgentActivityKind.WORKING)
        else -> Unit
    }
    val activeTools = state.parts.filter { it.kind == AgentStreamPartKind.TOOL && it.state == AgentStreamPartState.RUNNING }
    if (approval || activeTools.any { toolUiState(it) == ToolUiState.WAITING_APPROVAL }) return AgentActivityPresentation(AgentActivityKind.APPROVAL)
    if (input || activeTools.any { toolUiState(it) == ToolUiState.WAITING_INPUT }) return AgentActivityPresentation(AgentActivityKind.INPUT)
    if (state.status == AgentStreamState.Status.RETRYING) return AgentActivityPresentation(AgentActivityKind.RETRYING)
    if (state.parts.any { it.kind == AgentStreamPartKind.COMPACTION && it.state == AgentStreamPartState.RUNNING }) return AgentActivityPresentation(AgentActivityKind.COMPACTING)
    if (activeTools.size > 1) return AgentActivityPresentation(AgentActivityKind.TOOL, "${activeTools.size} tools active")
    activeTools.firstOrNull()?.let { tool ->
        val kind = when (tool.toolName) {
            "read", "read_file", "list_files", "list_directory" -> AgentActivityKind.READING
            "search_files", "search_code", "grep", "glob" -> AgentActivityKind.SEARCHING
            "write", "edit", "write_file", "edit_file", "apply_patch" -> AgentActivityKind.EDITING
            "bash", "run_command", "terminal", "exec_command", "write_stdin", "command_wait" -> AgentActivityKind.COMMAND
            else -> AgentActivityKind.TOOL
        }
        return AgentActivityPresentation(kind, tool.title.take(160))
    }
    return AgentActivityPresentation(when (state.modelActivity) {
        AgentModelActivity.WORKING -> AgentActivityKind.WORKING
        AgentModelActivity.THINKING -> AgentActivityKind.THINKING
        AgentModelActivity.RESPONDING -> AgentActivityKind.RESPONDING
        AgentModelActivity.PREPARING_TOOL -> AgentActivityKind.PREPARING
    })
}

@Composable
internal fun AgentActivityStatus(activity: AgentActivityPresentation, modifier: Modifier = Modifier) {
    val motionEnabled = rememberDroideMotionEnabled()
    val scope = rememberCoroutineScope()
    val appearance = remember(scope) {
        DroideKeyedMotion(scope, initiallyEnabled = false, animateInitially = false,
            animationSpec = tween(AgentActivityMotion.StatusMillis))
    }
    // Tool/status events may arrive faster than a fade. Continue that trajectory while displaying
    // the latest label immediately; never pulse on every event.
    SideEffect { appearance.update(activity.kind, motionEnabled && activity.kind.spinning) }
    val tint = when (activity.kind) {
        AgentActivityKind.FAILED -> DroideColors.Error
        AgentActivityKind.APPROVAL, AgentActivityKind.INPUT -> DroideColors.Warning
        AgentActivityKind.COMPLETED -> DroideColors.Success
        else -> DroideColors.Muted
    }
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }.graphicsLayer {
        alpha = if (motionEnabled && activity.kind.spinning) .82f + .18f * appearance.progress.value else 1f
    },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (activity.kind.spinning && motionEnabled) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        else Icon(when (activity.kind) {
            AgentActivityKind.FAILED -> Icons.Default.ErrorOutline
            AgentActivityKind.COMPLETED -> Icons.Default.CheckCircle
            AgentActivityKind.STOPPED -> Icons.Default.Cancel
            AgentActivityKind.APPROVAL, AgentActivityKind.INPUT -> Icons.Default.PauseCircleOutline
            else -> Icons.Default.MoreHoriz
        }, null, Modifier.size(14.dp), tint = tint)
        Column(Modifier.weight(1f)) {
            Text(activity.kind.label, style = MaterialTheme.typography.labelMedium, color = tint,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (activity.detail.isNotBlank()) Text(activity.detail, style = MaterialTheme.typography.labelSmall,
                color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

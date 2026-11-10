package com.baystudio.droide.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.baystudio.droide.core.AgentStreamPart
import com.baystudio.droide.core.AgentStreamPartKind
import com.baystudio.droide.core.AgentTextPresentation
import com.baystudio.droide.core.AgentStreamPartState
import com.baystudio.droide.core.AgentStreamState
import com.baystudio.droide.core.AgentStreamTransport
import com.baystudio.droide.core.AgentToolEvidenceState
import com.baystudio.droide.core.AgentToolResultSemantics
import com.baystudio.droide.core.AgentService
import com.baystudio.droide.core.ProviderRegistry
import com.baystudio.droide.core.TokenUsage
import com.baystudio.droide.core.UsageAuthority
import kotlinx.coroutines.delay

@Composable
internal fun AgentStreamRunHeader(
    state: AgentStreamState,
    tokenTotal: Int,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
    waitingForApproval: Boolean = false,
    waitingForInput: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var clock by remember(state.runId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.runId, state.status) {
        while (state.status == AgentStreamState.Status.BUSY || state.status == AgentStreamState.Status.RETRYING) {
            delay(1_000)
            clock = System.currentTimeMillis()
        }
    }
    val elapsed = if (state.startedAtMs > 0L) ((state.endedAtMs ?: clock) - state.startedAtMs).coerceAtLeast(0L) else 0L
    // Project rendered inputs, not unrelated transcript data or the ticking clock.
    val activity = nativeAgentActivity(state, waitingForApproval, waitingForInput)
    val toolSummary = remember(state.parts) {
        var count = 0
        var succeeded = 0
        var started = 0
        var failed = 0
        state.parts.forEach { part ->
            if (part.kind == AgentStreamPartKind.TOOL) {
                count++
                when (toolUiState(part)) {
                    ToolUiState.SUCCEEDED -> succeeded++
                    ToolUiState.STARTED -> started++
                    ToolUiState.FAILED, ToolUiState.DENIED, ToolUiState.UNAVAILABLE -> failed++
                    else -> Unit
                }
            }
        }
        if (count == 0) "" else buildString {
            append(succeeded).append('/').append(count).append(" succeeded")
            if (started > 0) append(" · $started started")
            if (failed > 0) append(" · $failed blocked/failed")
        }
    }
    val disclosureMotion = rememberAgentDisclosureMotion(listOf(
        activity, state.modelId, state.providerId, state.transport, state.activeStep.coerceAtLeast(1),
        toolSummary,
        state.startedAtMs, state.endedAtMs,
    ))
    val onDisclosure = LocalAgentDisclosureStarted.current
    Column(modifier.fillMaxWidth().agentDisclosureSize(disclosureMotion)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AgentActivityStatus(activity, Modifier.weight(1f))
            Text(formatStreamDuration(elapsed), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            IconButton(onClick = { onDisclosure(); disclosureMotion.begin(); onToggleDetails() }, modifier = Modifier.size(48.dp)) {
                Icon(if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (showDetails) "Hide stream details" else "Show stream details", Modifier.size(18.dp), tint = DroideColors.Muted)
            }
        }
        if (showDetails) {
            val detailMaxHeight = LocalAgentStatusDetailMaxHeight.current
            // Docked callers bound metadata independently; the status row never scrolls away.
            Column(if (detailMaxHeight == Dp.Infinity) Modifier else
                Modifier.heightIn(max = detailMaxHeight).verticalScroll(rememberScrollState())) {
                val providerName = ProviderRegistry.findById(state.providerId)?.name ?: state.providerId.ifBlank { "Provider" }
                Text("${state.modelId.ifBlank { "Agent model" }} · $providerName", style = MaterialTheme.typography.labelSmall,
                    color = DroideColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AgentTransportBadge(state.transport)
                Text("Step ${state.activeStep.coerceAtLeast(1)}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                if (toolSummary.isNotEmpty()) Text(toolSummary, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        }
    }
}

@Composable
private fun AgentTransportBadge(transport: AgentStreamTransport) {
    if (transport == AgentStreamTransport.UNKNOWN) return
    val label = if (transport == AgentStreamTransport.NATIVE) "Live" else "Buffered"
    val tint = if (transport == AgentStreamTransport.NATIVE) DroideColors.Success else DroideColors.Muted
    Surface(color = tint.copy(alpha = .08f), shape = MaterialTheme.shapes.extraSmall) {
        Text(label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
internal fun AgentStreamPartItem(part: AgentStreamPart, showDetails: Boolean, modifier: Modifier = Modifier) {
    if (!part.visibleInChat(showDetails)) return
    when (part.kind) {
        AgentStreamPartKind.INPUT -> AgentLiveInputPart(part)
        AgentStreamPartKind.TEXT -> AgentLiveTextPart(part, showDetails)
        AgentStreamPartKind.TOOL -> AgentLiveToolPart(part, showDetails, modifier)
        AgentStreamPartKind.STEP -> AgentLiveStepPart(part, showDetails)
        AgentStreamPartKind.COMPACTION, AgentStreamPartKind.RETRY, AgentStreamPartKind.STATUS -> AgentLifecyclePart(part, showDetails)
    }
}

@Composable
private fun AgentLiveInputPart(part: AgentStreamPart) {
    if (part.text.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = DroideColors.Primary.copy(alpha = .10f),
            border = BorderStroke(1.dp, DroideColors.Primary.copy(alpha = .22f)),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(part.title.ifBlank { "Instruction" }, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Primary)
                Text(part.text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD6DCE2))
            }
        }
    }
}

@Composable
private fun AgentLiveTextPart(part: AgentStreamPart, showDetails: Boolean) {
    if (part.text.isBlank()) return
    if (part.textPresentation != AgentTextPresentation.LEGACY) {
        Column(Modifier.fillMaxWidth()) {
            if (part.detail.contains("buffered", ignoreCase = true)) {
                Text("buffered", Modifier.padding(bottom = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = DroideColors.Text)
            }
            AgentPublicTextContent(
                markdown = part.text,
                presentation = part.textPresentation,
                presentationId = part.presentationId,
                publicTextSpans = part.publicTextSpans,
                state = part.state,
                streamId = part.id,
                modifier = Modifier.fillMaxWidth(),
            )
            // Interrupted output stays expanded and its status is visible even with details off.
            if (part.state == AgentStreamPartState.ERROR || part.state == AgentStreamPartState.CANCELLED) {
                Text(if (part.state == AgentStreamPartState.ERROR) "Response interrupted" else "Response stopped",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = DroideColors.Text)
                if (part.detail.isNotBlank()) Text(part.detail, style = MaterialTheme.typography.bodySmall, color = DroideColors.Text)
            }
            if (showDetails && part.state != AgentStreamPartState.RUNNING) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (part.finishReason.isNotBlank()) Text("finish: ${part.finishReason}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Text)
                    if (part.startedAtMs > 0L) Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Text)
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DROIDE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
            if (part.detail.contains("buffered", ignoreCase = true)) {
                Surface(color = DroideColors.Surface3, shape = MaterialTheme.shapes.extraSmall) {
                    Text("buffered", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        AgentStreamingMarkdownContent(
            markdown = part.text,
            streamId = part.id,
            completed = part.state != AgentStreamPartState.RUNNING && part.state != AgentStreamPartState.PENDING,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showDetails && part.state != AgentStreamPartState.RUNNING) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (part.finishReason.isNotBlank()) Text("finish: ${part.finishReason}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                if (part.startedAtMs > 0L) Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        }
    }
}

@Composable
private fun AgentLiveStepPart(part: AgentStreamPart, showDetails: Boolean) {
    // Stream details exposes the step summary, not every provider statistic at once. Step IDs repeat
    // between runs, so a new request must not inherit an open disclosure.
    var expanded by rememberSaveable(part.id, part.startedAtMs) { mutableStateOf(false) }
    val canExpand = showDetails && part.state == AgentStreamPartState.COMPLETED
    val disclosureMotion = rememberAgentDisclosureMotion(listOf(
        part.id, part.startedAtMs, part.endedAtMs, part.state, part.title, part.detail,
        part.totalTokens, part.costUsd, part.tokenUsageAuthority, part.costAuthority,
        part.promptTokens, part.completionTokens, part.reasoningTokens,
        part.cacheReadTokens, part.cacheWriteTokens, part.finishReason,
    ))
    val onDisclosure = LocalAgentDisclosureStarted.current
    val motionEnabled = rememberDroideMotionEnabled()
    val disclosure = animateFloatAsState(
        targetValue = if (canExpand && expanded) 1f else 0f,
        animationSpec = if (motionEnabled) DroideMotion.ActivitySpring else snap(),
        label = "agentStepStatisticsDisclosure",
    )
    // Saved steps predate aggregate coverage fields. Honor their persisted source authorities.
    val usage = TokenUsage(total = part.totalTokens, costUsd = part.costUsd,
        tokenAuthority = part.tokenUsageAuthority, costAuthority = part.costAuthority,
        requestCount = 1,
        knownTokenRequestCount = if (part.tokenUsageAuthority == UsageAuthority.UNKNOWN) 0 else 1,
        knownCostRequestCount = if (part.costAuthority == UsageAuthority.UNKNOWN) 0 else 1)
    Column(Modifier.fillMaxWidth().agentDisclosureSize(disclosureMotion)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (canExpand) 48.dp else 30.dp)
                .then(if (canExpand) Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button,
                        onClickLabel = if (expanded) "Hide step statistics" else "Show step statistics") {
                        onDisclosure()
                        disclosureMotion.begin()
                        expanded = !expanded
                    }.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                else Modifier)
                .padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AgentStreamStateGlyph(part.state)
            Text(if (showDetails) part.title else if (part.state == AgentStreamPartState.ERROR) "Response failed" else "Response stopped",
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = DroideColors.Muted)
            if (part.detail.isNotBlank()) {
                Text(part.detail, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Spacer(Modifier.weight(1f))
            if (showDetails && part.state != AgentStreamPartState.PENDING) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    if (usageHasKnownTokens(usage)) {
                        Text("${usageTokenValue(usage)} tokens", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                }
            }
            if (canExpand) Icon(Icons.Default.KeyboardArrowDown, null,
                Modifier.size(17.dp).graphicsLayer { rotationZ = 180f * disclosure.value }, tint = DroideColors.Muted)
        }
        if (canExpand && expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 26.dp, end = 5.dp, bottom = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(usageTokenAuthorityLabel(usage), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                // A saved step cannot prove that absent zero-valued components were actually reported.
                val components = if (usageHasKnownTokens(usage)) buildList {
                    if (part.promptTokens > 0) add("in ${part.promptTokens}")
                    if (part.completionTokens > 0) add("out ${part.completionTokens}")
                    if (part.reasoningTokens > 0) add("reason ${part.reasoningTokens}")
                    if (part.cacheReadTokens > 0) add("cache read ${part.cacheReadTokens}")
                    if (part.cacheWriteTokens > 0) add("cache write ${part.cacheWriteTokens}")
                } else emptyList()
                if (components.isNotEmpty()) Text(components.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                Text(usageCostSummary(usage), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                if (part.finishReason.isNotBlank()) Text("finish ${part.finishReason}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        }
    }
}

@Composable
private fun AgentLifecyclePart(part: AgentStreamPart, showDetails: Boolean) {
    val tint = when (part.state) {
        AgentStreamPartState.ERROR -> DroideColors.Error
        AgentStreamPartState.CANCELLED -> DroideColors.Muted
        else -> DroideColors.Muted
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (part.state == AgentStreamPartState.ERROR) DroideColors.Error.copy(alpha = .05f) else Color.Transparent,
        border = if (part.state == AgentStreamPartState.ERROR) BorderStroke(1.dp, DroideColors.Error.copy(alpha = .20f)) else null,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentStreamStateGlyph(part.state)
            Icon(
                when (part.kind) {
                    AgentStreamPartKind.RETRY -> Icons.Default.Replay
                    AgentStreamPartKind.COMPACTION -> Icons.Default.AutoFixHigh
                    else -> Icons.Default.Info
                },
                null,
                Modifier.size(14.dp),
                tint = tint,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(part.title, style = MaterialTheme.typography.labelMedium)
                    if (part.kind == AgentStreamPartKind.RETRY && part.attempt > 0) {
                        Text("#${part.attempt}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                }
                if (part.detail.isNotBlank()) {
                    Text(part.detail, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = if (showDetails) 4 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showDetails && part.startedAtMs > 0L) {
                Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        }
    }
}

@Composable
internal fun AgentCompletedToolPart(tool: String, input: String, output: String) {
    val evidence = AgentToolResultSemantics.parseAnnotation(output)
    val legacyFailure = output.startsWith("DENIED", ignoreCase = true) || output.startsWith("ERROR", ignoreCase = true)
    AgentLiveToolPart(
        AgentStreamPart(
            id = "durable:${tool}:${input.hashCode()}:${output.hashCode()}",
            step = 0,
            kind = AgentStreamPartKind.TOOL,
            state = if (evidence?.isFailure == true || (evidence == null && legacyFailure)) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED,
            title = tool.replace('_', ' '),
            toolName = tool,
            input = input,
            output = output,
            detail = evidence?.uiDetail ?: if (output.startsWith("DENIED", ignoreCase = true)) "Denied" else if (output.startsWith("ERROR", ignoreCase = true)) "Failed" else "Succeeded",
            startedAtMs = 0L,
            endedAtMs = 0L,
        ),
        showDetails = false,
    )
}

@Composable
private fun AgentLiveToolPart(part: AgentStreamPart, showDetails: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    val disclosureMotion = rememberAgentDisclosureMotion(listOf(
        part.id, part.state, part.title, part.detail, part.toolName, part.toolCallId,
        part.input, part.output, part.fileChanges, part.startedAtMs, part.endedAtMs, part.finishReason,
    ))
    val onDisclosure = LocalAgentDisclosureStarted.current
    val motionEnabled = rememberDroideMotionEnabled()
    val disclosure = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (motionEnabled) DroideMotion.ActivitySpring else snap(),
        label = "agentToolDisclosure",
    )
    val tool = part.toolName.ifBlank { part.title.replace(' ', '_') }
    val inputPreview = remember(part.input) { AgentToolInputPreview.parse(part.input) }
    val activityPath = inputPreview.path()
    val primary = streamPrimaryToolDetail(tool, part.input, activityPath, inputPreview)
    val visibleOutput = AgentToolResultSemantics.stripAnnotation(part.output)
    val fileEdit = tool in setOf("edit_file", "edit", "write_file", "write", "apply_patch", "lsp_edit")
    val fileRead = tool in setOf("read_file", "read")
    val changes = remember(part.fileChanges, visibleOutput, fileEdit, activityPath) {
        part.fileChanges.ifEmpty {
            if (fileEdit) listOfNotNull(legacyFileChange(visibleOutput, activityPath.orEmpty())) else emptyList()
        }
    }
    val uiState = toolUiState(part)
    val canExpand = part.input.isNotBlank() || visibleOutput.isNotBlank() || part.detail.isNotBlank() || changes.isNotEmpty()
    val resultSummary = streamResultSummary(part, visibleOutput, uiState)
    val stateTint = toolUiTint(uiState)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (uiState in setOf(ToolUiState.RUNNING, ToolUiState.STARTED)) stateTint.copy(alpha = .035f) else Color.Transparent,
        border = if (uiState in setOf(ToolUiState.RUNNING, ToolUiState.STARTED, ToolUiState.FAILED, ToolUiState.DENIED, ToolUiState.UNAVAILABLE)) BorderStroke(1.dp, stateTint.copy(alpha = .20f)) else null,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().agentDisclosureSize(disclosureMotion)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(MaterialTheme.shapes.small)
                    .then(if (canExpand) Modifier.clickable { onDisclosure(); disclosureMotion.begin(); expanded = !expanded } else Modifier)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentToolStateGlyph(uiState)
                Spacer(Modifier.width(7.dp))
                Icon(streamToolIcon(tool), null, Modifier.size(15.dp), tint = DroideColors.Muted)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${toolUiStateLabel(uiState)} · ${if (part.id.startsWith("external:")) part.title.ifBlank { streamToolDisplayName(tool) } else streamToolDisplayName(tool)}", style = MaterialTheme.typography.labelMedium)
                        AgentChangeCounts(changes)
                        if (showDetails && part.toolCallId.isNotBlank()) {
                            Text(part.toolCallId.takeLast(8), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (activityPath != null) {
                        FileIdentityLabel(path = activityPath, label = activityPath, iconSize = 13.dp, monospaced = true, color = DroideColors.Muted)
                    } else if (primary.isNotBlank()) {
                        Text(primary, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = if (tool in setOf("run_command", "bash", "exec_command", "write_stdin")) FontFamily.Monospace else FontFamily.Default)
                    } else if (part.detail.isNotBlank()) {
                        Text(part.detail, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (showDetails && part.startedAtMs > 0L) {
                    Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    Spacer(Modifier.width(4.dp))
                }
                if (canExpand) {
                    Icon(Icons.Default.KeyboardArrowDown, if (expanded) "Collapse" else "Expand",
                        Modifier.size(17.dp).graphicsLayer {
                            rotationZ = 180f * (if (motionEnabled) disclosure.value else if (expanded) 1f else 0f)
                        }, tint = DroideColors.Muted)
                }
            }

            if (resultSummary.isNotBlank() && !expanded && (showDetails || uiState != ToolUiState.SUCCEEDED)) {
                Text(resultSummary, Modifier.padding(start = 36.dp, end = 8.dp, bottom = 5.dp), style = MaterialTheme.typography.labelSmall, color = if (uiState == ToolUiState.FAILED) DroideColors.Error else if (uiState in setOf(ToolUiState.DENIED, ToolUiState.UNAVAILABLE, ToolUiState.STARTED)) DroideColors.Warning else DroideColors.Muted, maxLines = if (showDetails) 2 else 1, overflow = TextOverflow.Ellipsis)
            }

            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().graphicsLayer {
                        alpha = if (motionEnabled) AgentActivityMotion.InitialAlpha +
                            (1f - AgentActivityMotion.InitialAlpha) * disclosure.value else 1f
                    }.padding(start = 36.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (showDetails) StreamDetailRow("STATE", toolUiStateDetail(uiState, part.detail))
                    if (showDetails && part.input.isNotBlank()) {
                        Text("INPUT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                        Surface(color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.extraSmall) {
                            Text(rememberHighlightedCode(part.input.take(6_000), "json"), Modifier.fillMaxWidth().padding(7.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (visibleOutput.isNotBlank() || changes.isNotEmpty()) {
                        Text(if (changes.isNotEmpty()) "FILE CHANGES" else if (fileRead && uiState == ToolUiState.SUCCEEDED) "CODE" else "OUTPUT",
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            if (changes.isNotEmpty()) AgentFileChangesPreview(changes)
                            else if (fileRead && uiState == ToolUiState.SUCCEEDED) AgentReadCodePreview(visibleOutput, activityPath.orEmpty())
                            else Surface(color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.extraSmall) {
                                Text(visibleOutput.take(12_000), Modifier.fillMaxWidth().padding(7.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    if (showDetails && part.finishReason.isNotBlank()) StreamDetailRow("FINISH", part.finishReason)
                }
            }
        }
    }
}

@Composable
private fun StreamDetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    }
}

@Composable
private fun AgentStreamStateGlyph(state: AgentStreamPartState) {
    when (state) {
        AgentStreamPartState.PENDING -> Icon(Icons.Default.MoreHoriz, null, Modifier.size(14.dp), tint = DroideColors.Muted)
        AgentStreamPartState.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        AgentStreamPartState.COMPLETED -> Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = DroideColors.Success)
        AgentStreamPartState.ERROR -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = DroideColors.Error)
        AgentStreamPartState.CANCELLED -> Icon(Icons.Default.Cancel, null, Modifier.size(14.dp), tint = DroideColors.Muted)
    }
}

private fun streamStateLabel(state: AgentStreamPartState): String = when (state) {
    AgentStreamPartState.PENDING -> "Preparing"
    AgentStreamPartState.RUNNING -> "Running"
    AgentStreamPartState.COMPLETED -> "Completed"
    AgentStreamPartState.ERROR -> "Failed"
    AgentStreamPartState.CANCELLED -> "Stopped"
}

internal enum class ToolUiState {
    PREPARING, RUNNING, WAITING_APPROVAL, WAITING_INPUT, STARTED, SUCCEEDED, FAILED, DENIED, UNAVAILABLE, STOPPED,
}

internal fun toolUiState(part: AgentStreamPart): ToolUiState {
    if (part.state == AgentStreamPartState.PENDING) return ToolUiState.PREPARING
    if (part.state == AgentStreamPartState.RUNNING) {
        if (part.detail.startsWith("Waiting for approval", ignoreCase = true)) return ToolUiState.WAITING_APPROVAL
        if (part.detail.startsWith("Waiting for user", ignoreCase = true)) return ToolUiState.WAITING_INPUT
        return ToolUiState.RUNNING
    }
    if (part.state == AgentStreamPartState.CANCELLED) return ToolUiState.STOPPED
    val evidence = AgentToolResultSemantics.parseAnnotation(part.output)?.state ?: when {
        part.detail.startsWith("Started", ignoreCase = true) -> AgentToolEvidenceState.STARTED
        part.detail.startsWith("Denied", ignoreCase = true) -> AgentToolEvidenceState.DENIED
        part.detail.startsWith("Unavailable", ignoreCase = true) -> AgentToolEvidenceState.UNAVAILABLE
        part.detail.startsWith("Succeeded", ignoreCase = true) -> AgentToolEvidenceState.SUCCEEDED
        part.state == AgentStreamPartState.ERROR -> AgentToolEvidenceState.FAILED
        else -> AgentToolEvidenceState.SUCCEEDED
    }
    return when (evidence) {
        AgentToolEvidenceState.SUCCEEDED -> ToolUiState.SUCCEEDED
        AgentToolEvidenceState.STARTED -> ToolUiState.STARTED
        AgentToolEvidenceState.FAILED -> ToolUiState.FAILED
        AgentToolEvidenceState.DENIED -> ToolUiState.DENIED
        AgentToolEvidenceState.UNAVAILABLE -> ToolUiState.UNAVAILABLE
    }
}

private fun toolUiStateLabel(state: ToolUiState): String = when (state) {
    ToolUiState.PREPARING -> "Preparing"
    ToolUiState.RUNNING -> "Running"
    ToolUiState.WAITING_APPROVAL -> "Waiting approval"
    ToolUiState.WAITING_INPUT -> "Waiting input"
    ToolUiState.STARTED -> "Started"
    ToolUiState.SUCCEEDED -> "Succeeded"
    ToolUiState.FAILED -> "Failed"
    ToolUiState.DENIED -> "Denied"
    ToolUiState.UNAVAILABLE -> "Unavailable"
    ToolUiState.STOPPED -> "Stopped"
}

private fun toolUiStateDetail(state: ToolUiState, rawDetail: String): String = when (state) {
    ToolUiState.STARTED -> "Running"
    ToolUiState.SUCCEEDED -> "Done"
    ToolUiState.FAILED -> rawDetail.ifBlank { "Failed" }
    ToolUiState.DENIED -> "Denied · execution did not start"
    ToolUiState.UNAVAILABLE -> "Unavailable"
    ToolUiState.STOPPED -> "Stopped before completion"
    else -> rawDetail.ifBlank { toolUiStateLabel(state) }
}

private fun toolUiTint(state: ToolUiState): Color = when (state) {
    ToolUiState.RUNNING, ToolUiState.WAITING_INPUT -> DroideColors.Primary
    ToolUiState.STARTED, ToolUiState.WAITING_APPROVAL, ToolUiState.DENIED, ToolUiState.UNAVAILABLE -> DroideColors.Warning
    ToolUiState.SUCCEEDED -> DroideColors.Success
    ToolUiState.FAILED -> DroideColors.Error
    ToolUiState.PREPARING, ToolUiState.STOPPED -> DroideColors.Muted
}

@Composable
private fun AgentToolStateGlyph(state: ToolUiState) {
    val motionEnabled = rememberDroideMotionEnabled()
    // Only the inert fixed-size glyph crossfades; status text and actions update immediately.
    if (motionEnabled) {
        Crossfade(targetState = state, modifier = Modifier.size(14.dp).clearAndSetSemantics {},
            animationSpec = tween(AgentActivityMotion.StatusMillis), label = "agentToolStatus") { displayed ->
            AgentToolStateGlyphContent(displayed, motionEnabled = true)
        }
    } else Box(Modifier.size(14.dp).clearAndSetSemantics {}) {
        AgentToolStateGlyphContent(state, motionEnabled = false)
    }
}

@Composable
private fun AgentToolStateGlyphContent(state: ToolUiState, motionEnabled: Boolean) {
    when (state) {
        ToolUiState.PREPARING -> Icon(Icons.Default.MoreHoriz, null, Modifier.size(14.dp), tint = DroideColors.Muted)
        ToolUiState.RUNNING, ToolUiState.WAITING_APPROVAL, ToolUiState.WAITING_INPUT -> {
            if (motionEnabled) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = toolUiTint(state))
            else Icon(Icons.Default.MoreHoriz, null, Modifier.size(14.dp), tint = toolUiTint(state))
        }
        ToolUiState.STARTED -> Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp), tint = DroideColors.Warning)
        ToolUiState.SUCCEEDED -> Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = DroideColors.Success)
        ToolUiState.FAILED -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = DroideColors.Error)
        ToolUiState.DENIED -> Icon(Icons.Default.Block, null, Modifier.size(14.dp), tint = DroideColors.Warning)
        ToolUiState.UNAVAILABLE -> Icon(Icons.Default.WarningAmber, null, Modifier.size(14.dp), tint = DroideColors.Warning)
        ToolUiState.STOPPED -> Icon(Icons.Default.Cancel, null, Modifier.size(14.dp), tint = DroideColors.Muted)
    }
}

private fun streamResultSummary(part: AgentStreamPart, visibleOutput: String, uiState: ToolUiState): String {
    if (uiState == ToolUiState.STARTED) return "Running"
    if (uiState == ToolUiState.DENIED) return "Execution blocked by permission or policy"
    if (uiState == ToolUiState.UNAVAILABLE) return "Required tool unavailable"
    if (uiState == ToolUiState.FAILED) return part.detail.ifBlank { visibleOutput.lineSequence().firstOrNull()?.take(240).orEmpty() }
    if (uiState == ToolUiState.STOPPED) return "Cancelled before completion"
    if (visibleOutput.isBlank()) return ""
    val lines = visibleOutput.count { it == '\n' } + 1
    val suffix = if (part.output.length >= AgentService.MAX_STREAM_TOOL_OUTPUT_CHARS) " · live tail capped" else ""
    if (uiState in setOf(ToolUiState.RUNNING, ToolUiState.WAITING_APPROVAL, ToolUiState.WAITING_INPUT)) {
        val tail = visibleOutput.lineSequence().filter { it.isNotBlank() }.lastOrNull()?.trim()?.take(140).orEmpty()
        return buildString {
            append("Live · ").append(lines).append(" line").append(if (lines == 1) "" else "s")
            if (tail.isNotBlank()) append(" · ").append(tail)
            append(suffix)
        }
    }
    return "$lines line${if (lines == 1) "" else "s"} · ${visibleOutput.length} chars$suffix"
}

private fun streamPrimaryToolDetail(tool: String, input: String, activityPath: String?, preview: AgentToolInputPreview): String {
    if (activityPath != null) return activityPath
    if (input.isBlank()) return ""
    return when (tool) {
        "run_command", "bash" -> preview.text("command") ?: input.take(220)
        "exec_command" -> preview.text("cmd") ?: input.take(220)
        "write_stdin" -> preview.text("session_id") ?: input.take(220)
        "search_files", "grep" -> preview.text("query") ?: preview.text("pattern") ?: input.take(220)
        "glob" -> preview.text("pattern") ?: input.take(220)
        "read_file", "read", "write_file", "write", "edit_file", "edit", "apply_patch" -> preview.text("path") ?: preview.text("file") ?: input.take(220)
        "background_job" -> listOfNotNull(preview.text("action"), preview.text("job_id")).joinToString(" · ").ifBlank { input.take(220) }
        "lsp_edit" -> listOfNotNull(preview.text("operation"), preview.text("path"), preview.text("title")).joinToString(" · ").ifBlank { input.take(220) }
        "toolchain" -> listOfNotNull(preview.text("operation"), preview.text("family"), preview.text("version")).joinToString(" · ").ifBlank { input.take(220) }
        "browser" -> listOfNotNull(preview.text("operation"), preview.text("url"), preview.text("target")).joinToString(" · ").ifBlank { input.take(220) }
        else -> input.take(220)
    }.take(220)
}

private fun streamToolDisplayName(tool: String): String = when (tool) {
    "read_file", "read" -> "Read"
    "search_files", "grep", "glob" -> "Search"
    "write_file", "write" -> "Write"
    "edit_file", "edit", "apply_patch" -> "Edit"
    "run_command", "bash", "exec_command", "write_stdin" -> "Terminal"
    "background_job" -> "Background job"
    "environment_probe" -> "Environment check"
    "web", "websearch", "webfetch" -> "Web"
    "browser" -> "Browser"
    "lsp" -> "Language service"
    "lsp_edit" -> "LSP fix"
    "toolchain" -> "Toolchain"
    "skill" -> "Skill"
    "mcp" -> "MCP"
    else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun streamToolIcon(tool: String) = when (tool) {
    "read_file", "read" -> Icons.Default.Description
    "search_files", "grep" -> Icons.Default.Search
    "glob" -> Icons.Default.Folder
    "write_file", "write" -> Icons.Default.NoteAdd
    "edit_file", "edit", "apply_patch" -> Icons.Default.Edit
    "run_command", "bash", "exec_command", "write_stdin", "background_job" -> Icons.Default.Terminal
    "environment_probe" -> Icons.Default.Info
    "web", "websearch", "webfetch", "browser" -> Icons.Default.Language
    "lsp", "lsp_edit" -> Icons.Default.Code
    "toolchain" -> Icons.Default.Build
    "skill" -> Icons.Default.Extension
    "mcp" -> Icons.Default.Hub
    else -> Icons.Default.Build
}

private fun formatStreamDuration(ms: Long): String = when {
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1_000.0)
    else -> "${ms / 60_000}m ${((ms % 60_000) / 1_000)}s"
}

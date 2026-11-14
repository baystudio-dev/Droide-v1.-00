package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@Composable
internal fun ExternalAgentLiveStream(
    name: String,
    state: ExternalAgentConversationState,
    streamId: String,
    showDetails: Boolean = false,
    onToggleDetails: () -> Unit = {},
    waitingForApproval: Boolean = false,
    modifier: Modifier = Modifier,
    renderTranscript: Boolean = true,
) {
    // Only rendered inputs, including the selected transcript branch. Visibility
    // of the disclosure itself is intentionally excluded from this stream key.
    val activity = state.progress.presentation(waitingForApproval)
    val transcriptKey = if (!renderTranscript) null else if (state.streamParts.isNotEmpty())
        state.streamParts else listOf(state.liveText, streamId, state.progress.finished)
    val disclosureMotion = rememberAgentDisclosureMotion(listOf(name, state.liveThought, activity, transcriptKey))
    val onDisclosure = LocalAgentDisclosureStarted.current
    Column(modifier.fillMaxWidth().agentDisclosureSize(disclosureMotion), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentActivityStatus(activity, Modifier.weight(1f))
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
                Text(name, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                if (state.liveThought.isNotBlank()) {
                    Text("Reasoning summary", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    Text(state.liveThought, style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted,
                        maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (renderTranscript) {
            visibleAgentStreamParts(state.streamParts, showDetails).forEach { part -> AgentStreamPartItem(part, showDetails) }
            if (state.streamParts.isEmpty() && state.liveText.isNotBlank()) {
                AgentStreamingMarkdownContent(state.liveText, streamId, completed = state.progress.finished,
                    modifier = Modifier.fillMaxWidth(), bodyStyle = MaterialTheme.typography.bodyMedium.copy(color = DroideColors.Text))
            }
        }
    }
}

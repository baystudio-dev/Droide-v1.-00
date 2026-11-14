package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentPendingInput
import com.baystudio.droide.core.AgentPromptDelivery
import com.baystudio.droide.core.AgentInputOrigin

 
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentPendingPromptStrip(
    inputs: List<AgentPendingInput>,
    onRemove: (String) -> Unit,
) {
    if (inputs.isEmpty()) return
    val steerCount = inputs.count { it.delivery == AgentPromptDelivery.STEER }
    val queueCount = inputs.count { it.delivery == AgentPromptDelivery.QUEUE }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Default.ScheduleSend, null, Modifier.size(14.dp), tint = DroideColors.Muted)
            Text(
                buildString {
                    if (steerCount > 0) append("$steerCount steer")
                    if (steerCount > 0 && queueCount > 0) append(" · ")
                    if (queueCount > 0) append("$queueCount queued")
                },
                style = MaterialTheme.typography.labelSmall,
                color = DroideColors.Muted,
                fontWeight = FontWeight.SemiBold,
            )
        }
        inputs.take(4).forEach { pending ->
            val displayText = remember(pending.text, pending.media, pending.origin) {
                if (pending.origin == AgentInputOrigin.USER) agentUserMessageText(pending.text, pending.media)
                    .ifBlank { pending.media.joinToString { it.name } }
                else pending.text
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DroideColors.Surface.copy(alpha = .72f),
                border = BorderStroke(1.dp, DroideColors.Border),
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 3.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (pending.delivery == AgentPromptDelivery.STEER) Icons.Default.CallSplit else Icons.Default.PlaylistAdd,
                        null,
                        Modifier.size(13.dp),
                        tint = if (pending.delivery == AgentPromptDelivery.STEER) DroideColors.Primary else DroideColors.Muted,
                    )
                    Text(
                        if (pending.delivery == AgentPromptDelivery.STEER) "Steer" else "Queue",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (pending.delivery == AgentPromptDelivery.STEER) DroideColors.Primary else DroideColors.Muted,
                    )
                    Text(
                        displayText,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = DroideColors.Text,
                    )
                    IconButton(onClick = { onRemove(pending.id) }, modifier = Modifier.size(25.dp)) {
                        Icon(Icons.Default.Close, "Remove pending prompt", Modifier.size(13.dp), tint = DroideColors.Muted)
                    }
                }
            }
        }
        if (inputs.size > 4) {
            Text("+${inputs.size - 4} more pending", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        }
    }
}

@Composable
internal fun AgentDeliveryControl(
    delivery: AgentPromptDelivery,
    height: Dp,
    compact: Boolean,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    onSelect: (AgentPromptDelivery) -> Unit,
    canChooseDelivery: Boolean = true,
    onStop: (() -> Unit)? = null,
    stopLabel: String = "Stop active agent run",
    steerDescription: String = "Apply at the next safe step",
) {
    var expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded.value = true },
            modifier = Modifier.height(height).semantics { contentDescription = "Instruction delivery" },
            contentPadding = PaddingValues(horizontal = if (compact) 5.dp else 7.dp, vertical = 0.dp),
        ) {
            Icon(
                if (delivery == AgentPromptDelivery.STEER) Icons.Default.CallSplit else Icons.Default.PlaylistAdd,
                null,
                Modifier.size(14.dp),
            )
            if (!compact) {
                Spacer(Modifier.width(4.dp))
                Text(if (delivery == AgentPromptDelivery.STEER) "Steer" else "Queue", style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(12.dp))
        }
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Steer current run")
                        Text(steerDescription, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                },
                leadingIcon = { Icon(Icons.Default.CallSplit, null) },
                enabled = canChooseDelivery,
                trailingIcon = if (delivery == AgentPromptDelivery.STEER) {{ Icon(Icons.Default.Check, null) }} else null,
                onClick = { onSelect(AgentPromptDelivery.STEER); expanded.value = false },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Queue next turn")
                        Text("Runs in order when the current task is idle", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                },
                leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                enabled = canChooseDelivery,
                trailingIcon = if (delivery == AgentPromptDelivery.QUEUE) {{ Icon(Icons.Default.Check, null) }} else null,
                onClick = { onSelect(AgentPromptDelivery.QUEUE); expanded.value = false },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (delivery == AgentPromptDelivery.STEER) "Send instruction" else "Queue instruction") },
                leadingIcon = { Icon(Icons.Default.Send, null) },
                enabled = canSubmit,
                onClick = { expanded.value = false; onSubmit() },
            )
            if (onStop != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stopLabel) },
                    leadingIcon = { Icon(Icons.Default.Stop, null, tint = DroideColors.Error) },
                    onClick = { expanded.value = false; onStop() },
                )
            }
        }
    }
}

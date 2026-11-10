package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ExternalAgentReasoningMenu(
    expanded: Boolean,
    state: ExternalAgentConversationState,
    busy: Boolean,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 360.dp)) {
        Text("CLI Reasoning", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        state.connectionError?.let { DismissibleNotice(it.take(2_000), color = DroideColors.Error) }
        if (state.configuration?.reasoningOptions.isNullOrEmpty()) {
            Text(if (busy) "Loading CLI configuration…" else "This CLI has not reported Reasoning choices. Its configured default is used.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        state.configuration?.reasoningOptions.orEmpty().forEach { option ->
            DropdownMenuItem(
                text = { Column {
                    Text(option.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (option.description.isNotBlank()) Text(option.description, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                } },
                enabled = !busy,
                trailingIcon = if (state.configuration?.currentReasoningId == option.id) {{ Icon(Icons.Default.Check, null) }} else null,
                onClick = { onPick(option.id) },
            )
        }
        HorizontalDivider(color = DroideColors.Border)
        DropdownMenuItem(text = { Text(if (busy) "Loading…" else "Refresh configuration") }, enabled = !busy, onClick = onRefresh)
    }
}

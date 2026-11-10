package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.WorkspaceAgentPluginSpec

@Composable
internal fun ExternalAgentModelMenu(
    expanded: Boolean,
    state: ExternalAgentConversationState,
    busy: Boolean,
    spec: WorkspaceAgentPluginSpec,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 360.dp)) {
        Text("${spec.displayName} models", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall)
        state.connectionError?.let {
            DismissibleNotice(it.take(2_000), color = DroideColors.Error, ownerKey = spec.id)
            Text("If sign-in is required, run this in the same Linux terminal, then refresh:",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            Text(externalAgentLoginHint(spec.id), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        if (state.configuration?.models.isNullOrEmpty() && state.connectionError == null) {
            Text(if (busy) "Connecting and loading models…" else "This Agent has not reported model choices. Its configured default is used.",
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
        state.configuration?.models.orEmpty().forEach { option ->
            DropdownMenuItem(
                text = { Column {
                    Text(option.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (option.description.isNotBlank()) Text(option.description, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                } },
                enabled = !busy,
                trailingIcon = if (state.configuration?.currentModelId == option.id) {{ Icon(Icons.Default.Check, null) }} else null,
                onClick = { onPick(option.id) },
            )
        }
        HorizontalDivider(color = DroideColors.Border)
        DropdownMenuItem(text = { Text(if (busy) "Loading…" else "Refresh models") }, enabled = !busy, onClick = onRefresh)
    }
}

internal fun externalAgentLoginHint(agentId: String): String = when (agentId) {
    "opencode" -> "opencode auth login"
    "codex" -> "codex login"
    "claude" -> "claude-agent-acp --cli auth login"
    "gemini" -> "gemini"
    "copilot" -> "copilot login"
    "antigravity" -> "agy acp --login"
    "muse" -> "muse login"
    "kimi" -> "kimi login"
    "deepseek" -> "dsh web --no-open (configure provider in Settings)"
    else -> "Sign in using the Agent CLI"
}

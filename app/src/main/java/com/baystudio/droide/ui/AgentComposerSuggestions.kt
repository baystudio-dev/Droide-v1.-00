package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/** Transient, bounded suggestions anchored to the composer; typing keeps IME focus. */
@Composable
internal fun AgentComposerSuggestions(
    choices: List<AgentComposerChoice>,
    emptyMessage: String? = null,
    onDismiss: () -> Unit,
    onSelect: (AgentComposerChoice) -> Unit,
) {
    DropdownMenu(
        expanded = choices.isNotEmpty() || emptyMessage != null, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 216.dp),
        properties = PopupProperties(focusable = false),
    ) {
        if (emptyMessage != null) DropdownMenuItem(enabled = false, text = { Text(emptyMessage, style = MaterialTheme.typography.bodySmall) }, onClick = {})
        choices.forEach { choice ->
            DropdownMenuItem(onClick = { onSelect(choice) }, text = {
                Column {
                    Text(choice.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(choice.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
        }
    }
}

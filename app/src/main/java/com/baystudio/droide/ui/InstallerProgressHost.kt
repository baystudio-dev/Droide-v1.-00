package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.InstallerProgressEntry
import com.baystudio.droide.core.InstallerProgressOutcome
import com.baystudio.droide.core.InstallerProgressPresentation

/** Workspace footer, outside conditional panes. No overlay/scrim exists when minimized. */
@Composable
internal fun InstallerProgressHost(
    presentation: InstallerProgressPresentation,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars),
    trailingContent: @Composable () -> Unit = {},
) {
    val state by presentation.state.collectAsState()
    val expanded = state.expanded
    // Insets belong to the shared parent when this footer is present. A sibling approval
    // then observes consumed IME/nav insets instead of reserving keyboard space twice.
    // No padding is emitted for an empty host or a dialog-only presentation.
    val footerModifier = if (expanded == null && state.entries.isNotEmpty())
        modifier.windowInsetsPadding(insets) else modifier
    Column(footerModifier) {
        if (expanded != null) {
            key(presentation, expanded.id) {
                InstallerProgressDialog(expanded, state.entries,
                    onMinimize = { presentation.minimize(expanded.id) },
                    onCancel = { presentation.cancel(expanded.id) },
                    onClose = { presentation.dismiss(expanded.id) },
                    onOpen = presentation::open)
            }
        } else if (state.entries.isNotEmpty()) {
            val entry = state.entries.lastOrNull { it.running } ?: state.entries.last()
            InstallerProgressIndicator(entry, state.entries.size, { presentation.open(entry.id) }, Modifier)
        }
        trailingContent()
    }
}

private fun InstallerProgressEntry.statusLabel(): String = when {
    cancelRequested && running -> "Canceling…"
    outcome == InstallerProgressOutcome.SUCCEEDED -> "Complete"
    outcome == InstallerProgressOutcome.FAILED -> "Failed"
    outcome == InstallerProgressOutcome.CANCELED -> "Canceled"
    stage == "queued" || stage == "admitted" -> "Waiting…"
    stage == "downloading" -> "Downloading…"
    else -> "Installing…"
}

@Composable
private fun InstallerProgressIndicator(entry: InstallerProgressEntry, count: Int, onOpen: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().testTag("installer-progress-indicator"), tonalElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Only stable status is announced. Streaming output remains inside Details.
                Text("${entry.details.familyName} · ${entry.statusLabel()}" + if (count > 1) " ($count)" else "",
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (entry.outcome == InstallerProgressOutcome.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag("installer-progress-reopen")) {
                    Text("Details")
                }
            }
            if (entry.running) {
                val fraction = entry.fraction
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InstallerProgressDialog(
    entry: InstallerProgressEntry,
    entries: List<InstallerProgressEntry>,
    onMinimize: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("installer-progress-dialog"),
        // Back/outside dismiss only minimizes, including while cancellation cleanup is pending.
        onDismissRequest = onMinimize,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Install ${entry.details.familyName}", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onMinimize, modifier = Modifier.size(48.dp).testTag("installer-progress-minimize")) {
                    Icon(Icons.Default.Remove, contentDescription = "Minimize installation progress")
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.size > 1) {
                    val index = entries.indexOfFirst { it.id == entry.id }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { entries.getOrNull(index - 1)?.let { onOpen(it.id) } }, enabled = index > 0) { Text("Previous") }
                        Text("${index + 1} / ${entries.size}", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { entries.getOrNull(index + 1)?.let { onOpen(it.id) } }, enabled = index < entries.lastIndex) { Text("Next") }
                    }
                }
                Text("Version ${entry.details.version}", style = MaterialTheme.typography.bodyMedium)
                Text("Will install:", style = MaterialTheme.typography.labelMedium)
                entry.details.components.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(entry.statusLabel(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge)
                Text(entry.message, style = MaterialTheme.typography.bodySmall,
                    color = if (entry.outcome == InstallerProgressOutcome.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary)
                if (entry.running) {
                    val fraction = entry.fraction
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                if (entry.log.isNotBlank()) Text(entry.log, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            if (!entry.running) TextButton(onClick = onClose, modifier = Modifier.testTag("installer-progress-close")) { Text("Close") }
        },
        dismissButton = {
            if (entry.running) TextButton(onClick = onCancel, enabled = !entry.cancelRequested,
                modifier = Modifier.testTag("installer-progress-cancel")) { Text("Cancel") }
        },
    )
}

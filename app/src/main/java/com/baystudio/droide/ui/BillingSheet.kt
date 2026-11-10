package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.TokenTracker

@Composable
fun BillingSheet(tracker: TokenTracker, onDismiss: () -> Unit) {
    val snapshot by tracker.snapshot.collectAsState()
    val (usage, history, warning) = snapshot
    val visibleHistory = history.takeLast(20).reversed()
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        DroidePanelHeader(title = "Token Usage", onClose = onDismiss)
        // One bounded scroll owner keeps disclosures, history and reset reachable in short windows.
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(AGENT_USAGE_SCOPE, style = MaterialTheme.typography.titleSmall)
                    Text(AGENT_USAGE_CONTEXT_NOTE, style = MaterialTheme.typography.labelSmall)
                    Text("Kept in memory for this open Agent in this workspace. Excludes subagents and may exclude failed, interrupted or compaction work. Resetting clears these counters.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            warning?.let { message -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        DismissibleNotice(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, ownerKey = tracker)
                    }
                }
            } }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AgentUsageSummary(usage)
                        Text("Prices can change. Check your provider dashboard for billing.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("History per request", style = MaterialTheme.typography.labelLarge)
                    Text(usageHistoryLabel(visibleHistory.size, usage.requestCount), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visibleHistory) { (title, u) ->
                ListItem(
                    headlineContent = { Text(title.take(40), style = MaterialTheme.typography.bodySmall) },
                    supportingContent = { Text(usageRequestDetail(u), style = MaterialTheme.typography.labelSmall) },
                )
                HorizontalDivider()
            }
            if (history.isEmpty()) item { Text("No recorded requests yet.", style = MaterialTheme.typography.bodySmall) }
            item {
                OutlinedButton(onClick = { tracker.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Reset usage history") }
            }
        }
    }
}

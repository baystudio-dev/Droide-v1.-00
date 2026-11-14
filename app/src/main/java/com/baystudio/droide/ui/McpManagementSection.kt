package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun McpManagementSection(
    management: McpManagement,
    health: List<McpHealthSnapshot>,
    onOpenConfig: () -> Unit,
    onOpenPermissions: () -> Unit,
    pendingActions: Set<String>,
) {
    val scope = rememberWorkspaceCoroutineScope()
    var snapshot by remember(management) { mutableStateOf<McpConfigurationSnapshot?>(null) }
    var busy by remember(management) { mutableStateOf(false) }
    var error by remember(management) { mutableStateOf<String?>(null) }
    var editingName by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingName by remember { mutableStateOf<String?>(null) }
    var review by remember(management) { mutableStateOf<McpManagement.ConnectionReview?>(null) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launchUiCatching(onError = { error = it.message ?: "MCP operation failed" }) {
            try { action() } finally { busy = false }
        }
    }
    LaunchedEffect(management) { perform { snapshot = management.load() } }

    McpSettingsSection(
        health = health,
        header = {
            McpManagementHeader(
                canAdd = !busy && snapshot != null, busy = busy,
                onAdd = { error = null; editingName = "" },
                onReload = { perform { snapshot = management.load() } },
                onOpenConfig = onOpenConfig, onOpenPermissions = onOpenPermissions,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        },
        footer = {},
        onRetry = { name -> perform { review = management.reviewConnection(name) } },
        onOpenConfig = onOpenConfig,
        onOpenPermissions = onOpenPermissions,
        pendingActions = pendingActions + if (busy) health.map { "mcp:retry:${it.serverName}" }.toSet() else emptySet(),
        serverActions = { item ->
            val current = snapshot
            val server = current?.servers?.firstOrNull { it.name == item.serverName && item.source == "Workspace" }
            if (current != null && server != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = server.enabled, enabled = !busy, onCheckedChange = { enabled ->
                        perform { snapshot = management.setEnabled(current, server.name, enabled) }
                    })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { error = null; editingName = server.name }, enabled = !busy) { Text("Edit") }
                    TextButton(onClick = { error = null; deletingName = server.name }, enabled = !busy) { Text("Remove") }
                }
            } else if (item.source != "Workspace") {
                Text("Managed by its contributing plugin.", style = MaterialTheme.typography.bodySmall)
            }
        },
    )

    val current = snapshot
    val originalName = editingName
    if (current != null && originalName != null) {
        val original = current.servers.firstOrNull { it.name == originalName }
        key(originalName) {
            McpServerEditor(
                initial = original?.let(McpServerDraft::from) ?: McpServerDraft("", ""),
                adding = originalName.isEmpty(), busy = busy, error = error,
                configurationVersion = current.version,
                onDismiss = { if (!busy) { editingName = null; error = null } },
                onSave = { draft -> perform {
                    snapshot = management.save(current, draft, originalName.takeIf { it.isNotEmpty() })
                    editingName = null
                } },
            )
        }
    }
    deletingName?.let { name ->
        AlertDialog(
            onDismissRequest = { if (!busy) deletingName = null },
            title = { Text("Remove MCP server?") },
            text = { Column { Text("Remove $name from this workspace and close its Droide connection."); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !busy && current != null, onClick = {
                current?.let { perform { snapshot = management.remove(it, name); deletingName = null } }
            }) { Text("Remove") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deletingName = null; error = null }) { Text("Cancel") } },
        )
    }
    review?.let { selected ->
        AlertDialog(
            onDismissRequest = { if (!busy) review = null },
            title = { Text("Connect to ${selected.server.name}?") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This runs the configured program to discover its MCP tools. Agent tool calls still follow Agent Permissions.")
                    Text(selected.server.argv.first(), fontFamily = FontFamily.Monospace)
                    Text(McpServerDraft.from(selected.server).arguments, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                review = null // A one-use review cannot be retried without reviewing the current configuration.
                perform { management.connect(selected) }
            }) { Text("Connect once") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { review = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun McpServerEditor(
    initial: McpServerDraft,
    adding: Boolean,
    configurationVersion: DiskFileVersion,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (McpServerDraft) -> Unit,
) {
    val versionKey = "${configurationVersion.existed}:${configurationVersion.sha256}"
    val reviewedVersion = rememberSaveable { versionKey }
    val configurationCurrent = reviewedVersion == versionKey
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var command by rememberSaveable { mutableStateOf(initial.command) }
    var arguments by rememberSaveable { mutableStateOf(initial.arguments) }
    var protocol by rememberSaveable { mutableStateOf(initial.protocol) }
    var protocolMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "Add MCP server" else "Edit MCP server") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Local stdio server", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Server name") }, singleLine = true, enabled = !busy)
                OutlinedTextField(command, { command = it }, label = { Text("Command") }, placeholder = { Text("node") }, singleLine = true, enabled = !busy)
                OutlinedTextField(arguments, { arguments = it }, label = { Text("Arguments (JSON array)") },
                    supportingText = { Text("Example: [\"server.js\", \"--port\", \"3000\"]") }, minLines = 2, maxLines = 4, enabled = !busy)
                Box {
                    TextButton(enabled = !busy, onClick = { protocolMenu = true }) { Text("Protocol: ${protocol.label()}") }
                    DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                        McpProtocolPreference.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label()) }, onClick = { protocol = option; protocolMenu = false })
                        }
                    }
                }
                Text("Saved for this workspace. Connect to check the server.", style = MaterialTheme.typography.bodySmall)
                if (!configurationCurrent) Text("Configuration changed while this form was open. Cancel and reopen the server to review the current configuration.", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && configurationCurrent && name.isNotBlank() && command.isNotBlank(),
            onClick = { onSave(McpServerDraft(name.trim(), command.trim(), arguments, protocol, initial.enabled)) }) { Text("Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun McpProtocolPreference.label(): String = when (this) {
    McpProtocolPreference.AUTO -> "Auto"
    McpProtocolPreference.LEGACY_2025 -> "2025"
    McpProtocolPreference.MODERN_2026 -> "2026"
}


@Composable
private fun McpManagementHeader(
    canAdd: Boolean, busy: Boolean, onAdd: () -> Unit, onReload: () -> Unit,
    onOpenConfig: () -> Unit, onOpenPermissions: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("MCP servers", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(enabled = canAdd, onClick = onAdd) { Icon(Icons.Default.Add, "Add server") }
        IconButton(enabled = !busy, onClick = onReload) { Icon(Icons.Default.Refresh, "Reload MCP configuration") }
        Box {
            IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More MCP actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open config") }, onClick = { menu = false; onOpenConfig() })
                DropdownMenuItem(text = { Text("Agent Permissions") }, onClick = { menu = false; onOpenPermissions() })
                DropdownMenuItem(text = { Text("About MCP") }, onClick = { menu = false; help = true })
            }
        }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        title = { Text("Workspace MCP") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect local stdio servers to make their tools available to the agent. Remote HTTP, OAuth and custom environment variables are not supported yet.")
                Text("Saving or enabling does not start a server. Connect once checks its tools; Agent Permissions still control tool calls.")
                Text("Disabling closes the Droide connection. Restart existing external CLI sessions to apply configuration changes there.")
            }
        },
        confirmButton = { TextButton(onClick = { help = false }) { Text("Close") } },
    )
}

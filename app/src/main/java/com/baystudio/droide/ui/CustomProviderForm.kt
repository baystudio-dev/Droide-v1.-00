package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ProviderConnectionBackend
import com.baystudio.droide.core.ProviderWireProtocol
import com.baystudio.droide.core.UserProviderDefinition
import com.baystudio.droide.core.label

@Composable
internal fun CustomProviderForm(
    backend: ProviderConnectionBackend,
    initial: UserProviderDefinition?,
    modifier: Modifier = Modifier,
    onSaved: (UserProviderDefinition) -> Unit,
    onRemoved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var id by rememberSaveable(initial?.id) { mutableStateOf(initial?.id?.removePrefix("user.").orEmpty()) }
    var url by rememberSaveable(initial?.id) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var protocol by rememberSaveable(initial?.id) { mutableStateOf(initial?.protocol ?: ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) }
    var needsKey by rememberSaveable(initial?.id) { mutableStateOf(initial?.requiresKey ?: true) }
    var models by rememberSaveable(initial?.id) { mutableStateOf(initial?.models?.joinToString("\n").orEmpty()) }
    var protocolsOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberWorkspaceCoroutineScope()
    val focus = LocalFocusManager.current
    val height = LocalConfiguration.current.screenHeightDp.dp * .90f
    Column(
        modifier.fillMaxWidth().heightIn(max = height).navigationBarsPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to providers") }
            Text(if (initial == null) "Add custom provider" else "Edit custom provider", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Available across your projects. Save the endpoint, then connect with your API key.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
            OutlinedTextField(name, { name = it.take(160); error = null }, Modifier.fillMaxWidth(), label = { Text("Provider name") }, singleLine = true, enabled = !busy)
            OutlinedTextField(id, { id = it.take(80); error = null }, Modifier.fillMaxWidth(), label = { Text("Provider ID") },
                supportingText = { Text("Unique name, for example my-provider") }, singleLine = true, enabled = !busy && initial == null,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            OutlinedTextField(url, { url = it.take(2048); error = null }, Modifier.fillMaxWidth(), label = { Text("API base URL") },
                placeholder = { Text("https://api.example.com/v1") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
            Text("API protocol", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(onClick = { protocolsOpen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(protocol.label, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = protocolsOpen, onDismissRequest = { protocolsOpen = false }) {
                    ProviderWireProtocol.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(item.label) }, onClick = { protocol = item; protocolsOpen = false; error = null })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(needsKey, { needsKey = it; error = null }, enabled = !busy)
                Text("Requires API key", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(models, { models = it.take(4096); error = null }, Modifier.fillMaxWidth(), label = { Text("Model IDs (optional)") },
                supportingText = { Text("One ID per line. Leave empty to discover models automatically.") }, minLines = 2, maxLines = 4, enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            if (models.isNotBlank()) Text(
                "Connecting with manual IDs sends a short validation request for each model. Your provider may charge for these requests.",
                style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = {
                focus.clearFocus(); busy = true; error = null
                scope.launchUiCatching(onError = { error = it.message ?: "Could not save custom provider."; busy = false }) {
                    try {
                        val candidate = UserProviderDefinition("user.$id", name, url, protocol, needsKey, models.lines().map(String::trim).filter(String::isNotEmpty))
                        backend.saveCustomProvider(candidate, initial)
                            .onSuccess(onSaved).onFailure { error = it.message ?: "Could not save custom provider." }
                    } finally { busy = false }
                }
            }, enabled = !busy && name.isNotBlank() && id.isNotBlank() && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Save & connect")
            }
            if (initial != null) TextButton(onClick = { removing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Remove custom provider", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (removing && initial != null) AlertDialog(
        onDismissRequest = { if (!busy) removing = false },
        title = { Text("Remove ${initial.name}?") },
        text = { Text("Removes this provider and its saved credential. Existing chats are kept.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launchUiCatching(onError = { error = it.message ?: "Could not remove provider."; busy = false; removing = false }) {
                try {
                    backend.removeCustomProvider(initial).onSuccess { onRemoved(initial.id) }
                        .onFailure { error = it.message ?: "Could not remove provider." }
                } finally { busy = false; removing = false }
            }
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = false }, enabled = !busy) { Text("Cancel") } },
    )
}

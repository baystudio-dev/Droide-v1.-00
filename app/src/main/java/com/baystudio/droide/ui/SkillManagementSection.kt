package com.baystudio.droide.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.SkillDocument
import com.baystudio.droide.core.SkillManagement
import com.baystudio.droide.core.SkillSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SkillManagementSection(management: SkillManagement, storageScope: String) {
    val context = LocalContext.current
    val coroutineScope = rememberWorkspaceCoroutineScope()
    var catalog by remember(management, storageScope) { mutableStateOf<List<SkillSummary>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var visibleCount by remember(query) { mutableIntStateOf(20) }
    var editorOpen by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<SkillDocument?>(null) }
    var removing by remember { mutableStateOf(false) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        coroutineScope.launchUiCatching(onError = { error = it.message ?: "Skill operation failed" }) {
            try { action() } finally { busy = false }
        }
    }
    suspend fun refresh() { catalog = management.catalog(storageScope); loaded = true }
    LaunchedEffect(management, storageScope) { perform { refresh() } }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "SKILL.md"
                requireNotNull(resolver.openInputStream(uri)) { "Could not open the selected skill" }.use {
                    management.importFile(it, fileName, storageScope)
                }
            }
            refresh()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (storageScope == "user") "Personal skills" else "Project & plugin skills", Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(enabled = !busy, onClick = { error = null; original = null; editorOpen = true }) { Icon(Icons.Default.Add, "Create skill") }
            IconButton(enabled = !busy, onClick = { importFile.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, "Import skill") }
            IconButton(enabled = !busy, onClick = { perform { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh skills") }
        }
        Text(if (storageScope == "user") "Available across your projects. A project skill with the same name takes priority."
            else "Saved in .droide/skills. Standard project folders and enabled plugins are also listed.", style = MaterialTheme.typography.bodySmall)
        Text("Import SKILL.md or a ZIP containing one skill folder. Supporting files stay with the skill; importing does not run them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Select /skill/name or @skill/name in chat. Connected CLI agents can use droide_skill through Droide MCP.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Find skills") }, singleLine = true)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val matching = catalog.filter { it.name.contains(query, true) || it.description.contains(query, true) }
        if (loaded && matching.isEmpty()) Text(if (query.isBlank()) "No skills in this scope yet." else "No matching skills.", style = MaterialTheme.typography.bodyMedium)
        matching.take(visibleCount).forEach { item ->
            key(item.name, item.sourceLabel) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        perform { original = management.document(item.name, storageScope); editorOpen = true }
                    }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (matching.size > visibleCount) TextButton(onClick = { visibleCount += 20 }) { Text("Show more") }
        if (catalog.size >= 160) Text("Discovery is limited to 160 skills. Narrow your installed skill folders if an entry is missing.", style = MaterialTheme.typography.bodySmall)
    }

    if (editorOpen) key(original?.skill?.name) {
        SkillEditor(original, storageScope, busy, error,
            onDismiss = { if (!busy) { editorOpen = false; error = null } },
            onRemove = { removing = true },
            onSave = { markdown -> perform {
                management.save(markdown, storageScope, original?.version?.sha256)
                editorOpen = false
                refresh()
            } },
        )
    }
    if (removing) original?.let { document ->
        AlertDialog(onDismissRequest = { if (!busy) removing = false }, title = { Text("Remove ${document.skill.name}?") },
            text = { Column { Text("Remove its SKILL.md entry point. Supporting files are kept in the skill folder."); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !busy, onClick = { perform {
                management.remove(document.skill.name, storageScope, document.version.sha256)
                removing = false; editorOpen = false; refresh()
            } }) { Text("Remove") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SkillEditor(original: SkillDocument?, storageScope: String, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (String) -> Unit) {
    var markdown by rememberSaveable { mutableStateOf(original?.markdown ?: "---\nname: my-skill\ndescription: Describe when to use this skill\n---\n\nWrite the instructions here.\n") }
    val editable = original == null || original.managedScope == storageScope
    val reviewedVersion = rememberSaveable { original?.version?.sha256.orEmpty() }
    val currentVersion = reviewedVersion == original?.version?.sha256.orEmpty()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Create skill" else original.skill.name) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!editable) Text("Managed by its project folder or plugin. Edit the source or create a personal copy with a new name.", style = MaterialTheme.typography.bodySmall)
                else Text("SKILL.md · $storageScope. Use a lowercase name with hyphens, a description, then instructions below the closing ---.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(markdown, { markdown = it }, Modifier.fillMaxWidth(), readOnly = !editable, enabled = !busy,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 8, maxLines = 16)
                if (!currentVersion) Text("Skill changed while this form was open. Close and reopen it before saving.", color = MaterialTheme.colorScheme.error)
                if (original != null && editable) TextButton(enabled = !busy && currentVersion, onClick = onRemove) { Text("Remove skill", color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { if (editable) TextButton(enabled = !busy && currentVersion && markdown.isNotBlank(), onClick = { onSave(markdown) }) { Text("Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (editable) "Cancel" else "Close") } },
    )
}

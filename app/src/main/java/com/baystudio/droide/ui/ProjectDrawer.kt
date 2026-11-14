package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.DroideProject
import com.baystudio.droide.core.GitClonePolicy
import com.baystudio.droide.core.ProjectManager
import com.baystudio.droide.core.ProjectTemplateRegistry
import kotlinx.coroutines.launch

 
@Composable
fun ProjectDrawer(
    manager: ProjectManager,
    onPickSaf: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreateProject: suspend (String, String) -> Pair<DroideProject, String?> = { name, template -> manager.createFromTemplate(name, template) },
    onDismiss: (() -> Unit)? = null,
) {
    val recentProjects by manager.recentProjects.collectAsState()
    val otherProjects by manager.otherProjects.collectAsState()
    val storedProjects by manager.storedProjects.collectAsState()
    val active by manager.activeId.collectAsState()
    val scope = rememberWorkspaceCoroutineScope()
    var showNewProject by remember { mutableStateOf(false) }
    var showCloneProject by remember { mutableStateOf(false) }
    var cloneUrl by remember { mutableStateOf("") }
    var cloneName by remember { mutableStateOf("") }
    var cloneToken by remember { mutableStateOf("") }
    var cloneError by remember { mutableStateOf<String?>(null) }
    var cloning by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var templateId by remember { mutableStateOf("empty") }
    var templateMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showOtherProjects by remember { mutableStateOf(false) }
    var showStoredProjects by remember { mutableStateOf(false) }
    var restoringId by remember { mutableStateOf<String?>(null) }
    var restoreError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var projectMenuId by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<DroideProject?>(null) }
    var removing by remember { mutableStateOf(false) }
    var removalError by remember { mutableStateOf<String?>(null) }
    var removalNotice by remember { mutableStateOf<Pair<String, DroideProject?>?>(null) }

    Column(Modifier.fillMaxWidth()) {
        DroidePanelHeader(title = "Projects", onClose = onDismiss)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = { showCloneProject = true }) {
                Icon(Icons.Default.CloudDownload, null)
                Spacer(Modifier.width(6.dp))
                Text("Clone Git")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { showNewProject = true }) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("New Project")
            }
            ProjectCatalogRecoveryEntry(manager)
        }
        error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Recent projects", style = MaterialTheme.typography.titleSmall)
            TextButton(
                enabled = recentProjects.any { it.id != "default" && it.id != active },
                onClick = {
                    error = null
                    scope.launch {
                        runCatching { manager.clearRecent() }
                            .onFailure { error = it.message ?: "Could not clear recent projects" }
                    }
                },
            ) { Text("Clear Recent") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 420.dp)) {
            items(recentProjects, key = { "recent:${it.id}" }) { project ->
                ProjectHistoryRow(
                    project = project,
                    active = project.id == active,
                    menuExpanded = projectMenuId == project.id,
                    onMenuExpandedChange = { projectMenuId = if (it) project.id else null },
                    onOpen = { onSwitch(project.id) },
                    onRemoveRecent = if (project.id != "default" && project.id != active) {{
                        projectMenuId = null
                        error = null
                        scope.launch {
                            runCatching { manager.removeFromRecent(project.id) }
                                .onFailure { error = it.message ?: "Could not remove project from Recent" }
                        }
                    }} else null,
                    onRestoreRecent = null,
                    onRemoveWorkspace = if (project.id != "default" && project.id != active) {{
                        projectMenuId = null
                        removalError = null
                        pendingRemoval = project
                    }} else null,
                )
            }
            if (otherProjects.isNotEmpty()) {
                item(key = "other-header") {
                    TextButton(onClick = { showOtherProjects = !showOtherProjects }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (showOtherProjects) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Other projects (${otherProjects.size})")
                    }
                }
            }
            if (showOtherProjects) {
                items(otherProjects, key = { "other:${it.id}" }) { project ->
                    ProjectHistoryRow(
                        project = project,
                        active = false,
                        menuExpanded = projectMenuId == project.id,
                        onMenuExpandedChange = { projectMenuId = if (it) project.id else null },
                        onOpen = { onSwitch(project.id) },
                        onRemoveRecent = null,
                        onRestoreRecent = {
                            projectMenuId = null
                            error = null
                            scope.launch {
                                runCatching { manager.restoreToRecent(project.id) }
                                    .onFailure { error = it.message ?: "Could not restore project to Recent" }
                            }
                        },
                        onRemoveWorkspace = {
                            projectMenuId = null
                            removalError = null
                            pendingRemoval = project
                        },
                    )
                }
            }
            if (storedProjects.isNotEmpty()) {
                item(key = "stored-header") {
                    TextButton(onClick = { showStoredProjects = !showStoredProjects }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (showStoredProjects) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Stored projects (${storedProjects.size})")
                    }
                }
                if (showStoredProjects) {
                    items(storedProjects, key = { "stored:${it.id}" }) { project ->
                        ListItem(
                            headlineContent = { Text(project.name) },
                            supportingContent = {
                                Column {
                                    Text(project.rootPath.takeLast(56), maxLines = 1)
                                    restoreError?.takeIf { it.first == project.id }?.let {
                                        DismissibleNotice(it.second, color = MaterialTheme.colorScheme.error, ownerKey = project.id)
                                    }
                                }
                            },
                            trailingContent = {
                                TextButton(enabled = restoringId == null, onClick = {
                                    restoringId = project.id
                                    restoreError = null
                                    scope.launch {
                                        runCatching { manager.restoreWorkspace(project.id) }
                                            .onSuccess { showOtherProjects = true }
                                            .onFailure { restoreError = project.id to (it.message ?: "Could not restore project") }
                                        restoringId = null
                                    }
                                }) { Text(if (restoringId == project.id) "Restoring…" else "Restore") }
                            },
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onPickSaf, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Open folder from device")
        }
        Text(
            "External folders open through Android's folder picker.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }

    if (showNewProject) {
        val template = ProjectTemplateRegistry.byId(templateId) ?: ProjectTemplateRegistry.all.first()
        AlertDialog(
            onDismissRequest = { if (!creating) showNewProject = false },
            title = { Text("New Project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(120) },
                        label = { Text("Project name") },
                        placeholder = { Text(template.name) },
                        singleLine = true,
                        enabled = !creating,
                    )
                    Box {
                        OutlinedButton(onClick = { templateMenu = true }, enabled = !creating) {
                            Text(template.name)
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                            ProjectTemplateRegistry.all.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.name)
                                            Text(option.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { templateId = option.id; templateMenu = false },
                                )
                            }
                        }
                    }
                    Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Templates create project files only. Toolchains are installed separately.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !creating,
                    onClick = {
                        creating = true
                        error = null
                        scope.launch {
                            runCatching { onCreateProject(newName, templateId) }
                                .onSuccess {
                                    showNewProject = false
                                    newName = ""
                                    templateId = "empty"
                                }
                                .onFailure { error = it.message ?: "Project creation failed" }
                            creating = false
                        }
                    },
                ) { Text(if (creating) "Creating…" else "Create") }
            },
            dismissButton = { TextButton(onClick = { showNewProject = false }, enabled = !creating) { Text("Cancel") } },
        )
    }

    if (showCloneProject) {
        val suggestedName = remember(cloneUrl) { GitClonePolicy.suggestProjectName(cloneUrl) }
        AlertDialog(
            onDismissRequest = {
                if (!cloning) {
                    showCloneProject = false
                    cloneToken = ""
                    cloneError = null
                }
            },
            title = { Text("Clone Git Repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = cloneUrl,
                        onValueChange = { cloneUrl = it.take(2_000) },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://github.com/owner/repo.git") },
                        singleLine = true,
                        enabled = !cloning,
                    )
                    OutlinedTextField(
                        value = cloneName,
                        onValueChange = { cloneName = it.take(120) },
                        label = { Text("Project name (optional)") },
                        placeholder = { Text(suggestedName) },
                        singleLine = true,
                        enabled = !cloning,
                    )
                    OutlinedTextField(
                        value = cloneToken,
                        onValueChange = { cloneToken = it.take(4_096) },
                        label = { Text("PAT override (optional)") },
                        supportingText = {
                            Text("GitHub account is used for HTTPS URLs. A PAT here is temporary.")
                        },
                        singleLine = true,
                        enabled = !cloning,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        "Clones into a private workspace. Failed attempts may leave partial files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    cloneError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
                    if (cloning) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    enabled = cloneUrl.isNotBlank() && !cloning,
                    onClick = {
                        val url = cloneUrl
                        val name = cloneName
                        val token = cloneToken
                        cloning = true
                        cloneError = null
                        scope.launch {
                            runCatching { manager.cloneFromGit(name, url, token = token.ifBlank { null }) }
                                .onSuccess { project ->
                                    cloneToken = ""
                                    cloneUrl = ""
                                    cloneName = ""
                                    showCloneProject = false
                                    onSwitch(project.id)
                                }
                                .onFailure { throwable ->
                                    cloneToken = ""
                                    cloneError = throwable.message ?: "Clone failed"
                                }
                            cloning = false
                        }
                    },
                ) { Text(if (cloning) "Cloning…" else "Clone") }
            },
            dismissButton = {
                TextButton(
                    enabled = !cloning,
                    onClick = { showCloneProject = false; cloneToken = ""; cloneError = null },
                ) { Text("Cancel") }
            },
        )
    }

    pendingRemoval?.let { project ->
        AlertDialog(
            onDismissRequest = { if (!removing) pendingRemoval = null },
            title = { Text("Remove workspace?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remove ${project.name} from Droide's project list?")
                    Text("Removes this project from the list only. Files and storage usage stay unchanged.")
                    Text("You can restore it later from Stored projects.")
                    if (project.treeUri != null) {
                        Text(
                            "The original external folder is kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (removing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    removalError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !removing,
                    onClick = {
                        removing = true
                        removalError = null
                        scope.launch {
                            runCatching { manager.removeWorkspace(project.id) }
                                .onSuccess { outcome ->
                                    pendingRemoval = null
                                    removalNotice = project.name to outcome
                                }
                                .onFailure { removalError = it.message ?: "Workspace removal failed" }
                            removing = false
                        }
                    },
                ) { Text(if (removing) "Removing…" else "Remove Workspace") }
            },
            dismissButton = {
                TextButton(enabled = !removing, onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }

    removalNotice?.let { (name, outcome) ->
        WorkspaceRemovalNotice(name, outcome) { removalNotice = null }
    }
}

@Composable
internal fun WorkspaceRemovalNotice(name: String, outcome: DroideProject?, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (outcome == null) "Workspace not registered" else "Workspace removed") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (outcome == null) "$name is no longer registered in Droide."
                    else "$name was removed from Droide's project list.")
                if (outcome != null) {
                    Text("This action does not move or delete files and does not free storage space.")
                    Text("Find this project under Stored projects to restore it later.")
                    Text("Original project location:", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(outcome.rootPath, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun ProjectHistoryRow(
    project: DroideProject,
    active: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRemoveRecent: (() -> Unit)?,
    onRestoreRecent: (() -> Unit)?,
    onRemoveWorkspace: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(project.name) },
        supportingContent = {
            Text(
                if (onRestoreRecent != null) "Not in Recent · ${project.rootPath.takeLast(48)}" else project.rootPath.takeLast(56),
                maxLines = 1,
            )
        },
        trailingContent = {
            Row {
                if (active) Icon(Icons.Default.Check, contentDescription = "Active project")
                if (onRemoveRecent != null || onRestoreRecent != null || onRemoveWorkspace != null) {
                    Box {
                        IconButton(onClick = { onMenuExpandedChange(true) }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Project actions")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
                            onRemoveRecent?.let { action ->
                                DropdownMenuItem(
                                    text = { Text("Remove from Recent") },
                                    leadingIcon = { Icon(Icons.Default.History, null) },
                                    onClick = action,
                                )
                            }
                            onRestoreRecent?.let { action ->
                                DropdownMenuItem(
                                    text = { Text("Add to Recent") },
                                    leadingIcon = { Icon(Icons.Default.Restore, null) },
                                    onClick = action,
                                )
                            }
                            onRemoveWorkspace?.let { action ->
                                DropdownMenuItem(
                                    text = { Text("Remove Workspace…") },
                                    leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                                    onClick = action,
                                )
                            }
                        }
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

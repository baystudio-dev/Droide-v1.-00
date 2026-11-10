package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.CMakeAnalysisSelections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Staging is local UI state. Cancel/Back never saves or enables a selection. */
@Composable
internal fun CMakeAnalysisSelectionDialog(
    status: CMakeAnalysisSelections.Status,
    onDismiss: () -> Unit,
    onSave: suspend (sourcePath: String, configuredBuildPath: String?) -> Unit,
) {
    var source by remember { mutableStateOf(status.selection?.sourceLocalCanonical ?: status.suggestedSourcePath) }
    var configured by remember { mutableStateOf(status.selection?.buildMode == CMakeAnalysisSelections.BuildMode.EXISTING_CONFIGURED) }
    var build by remember { mutableStateOf(status.selection?.configuredBuildLocalCanonical.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("CMake source and build") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save validates existing directories and the configured cache without running CMake. Enable analysis is a separate action after saving.")
                OutlinedTextField(source, { if (it.length <= CMakeAnalysisSelections.MAX_PATH_CHARS) source = it },
                    label = { Text("Source directory") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (status.nestedSourceAmbiguity) Text("The document directory is a suggestion. Review nested CMake sources before saving.", style = MaterialTheme.typography.bodySmall)
                Row {
                    RadioButton(!configured, { configured = false }, enabled = !saving)
                    Text("Builtin documentation, private session build", Modifier.padding(top = 12.dp))
                }
                Row {
                    RadioButton(configured, { configured = true }, enabled = !saving)
                    Text("Explicit existing configured build", Modifier.padding(top = 12.dp))
                }
                if (configured) {
                    OutlinedTextField(build, { if (it.length <= CMakeAnalysisSelections.MAX_PATH_CHARS) build = it },
                        label = { Text("Existing build directory") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Requires a regular CMakeCache.txt associated with this source. Source and build must be inside the opened workspace; open their common parent for sibling directories. No build is discovered or created.", style = MaterialTheme.typography.bodySmall)
                } else Text("No project build is selected. After explicit enablement, each session gets a private build directory with cache checks. CMake still has the Ubuntu user's file and network access; these checks are not a sandbox.", style = MaterialTheme.typography.bodySmall)
                Text(status.message, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && source.isNotBlank() && (!configured || build.isNotBlank()), onClick = {
                saving = true
                error = null
                scope.launch {
                    try { onSave(source, if (configured) build else null); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Selection validation failed. The previous selection is unchanged." }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Validating…" else "Save selection") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

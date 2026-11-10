package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.CSharpAnalysisSelections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Staging is local UI state. Cancel/Back never saves or enables a selection. */
@Composable
internal fun CSharpAnalysisSelectionDialog(
    status: CSharpAnalysisSelections.Status,
    onDismiss: () -> Unit,
    onSave: suspend (rootPath: String, mode: CSharpAnalysisSelections.Mode, solutionPath: String?) -> Unit,
) {
    var source by remember { mutableStateOf(status.selection?.rootPath ?: status.suggestedRootPath) }
    var configured by remember { mutableStateOf(status.selection?.mode != CSharpAnalysisSelections.Mode.NATIVE_DIRECTORY_DISCOVERY) }
    var build by remember { mutableStateOf(status.selection?.solutionPath.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("C# analysis selection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save performs metadata-only checks of the existing root and solution. It never starts MSBuild or a language server. Enable analysis is a separate action.")
                OutlinedTextField(source, { if (it.length <= CSharpAnalysisSelections.MAX_PATH_CHARS) source = it },
                    label = { Text("Analysis directory") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("One explicit selection lasts until this workspace is closed. The directory must contain this document.", style = MaterialTheme.typography.bodySmall)
                Row {
                    RadioButton(!configured, { configured = false }, enabled = !saving)
                    Text("Native recursive directory discovery", Modifier.padding(top = 12.dp))
                }
                Row {
                    RadioButton(configured, { configured = true }, enabled = !saving)
                    Text("Explicit existing .sln or .slnx", Modifier.padding(top = 12.dp))
                }
                if (configured) {
                    OutlinedTextField(build, { if (it.length <= CSharpAnalysisSelections.MAX_PATH_CHARS) build = it },
                        label = { Text("Solution file (.sln or .slnx)") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("The solution must be a regular file inside the selected analysis directory. .csproj and .slnf are not solution overrides. Nothing is synthesized or restored.", style = MaterialTheme.typography.bodySmall)
                } else Text("After Enable, native recursive discovery prefers .sln/.slnx by declared project count, otherwise discovered .csproj/.fsproj outside node_modules. This is not selected-file-only, offline or sandboxed analysis. MSBuild targets, resolvers and generators may execute.", style = MaterialTheme.typography.bodySmall)
                Text(status.message, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && source.isNotBlank() && (!configured || build.isNotBlank()), onClick = {
                saving = true
                error = null
                scope.launch {
                    try { onSave(source, if (configured) CSharpAnalysisSelections.Mode.EXPLICIT_SOLUTION else CSharpAnalysisSelections.Mode.NATIVE_DIRECTORY_DISCOVERY, if (configured) build else null); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Selection validation failed. The previous selection is unchanged." }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Validating…" else "Save selection") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

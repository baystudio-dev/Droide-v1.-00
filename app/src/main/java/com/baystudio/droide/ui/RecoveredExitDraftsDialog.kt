package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ExitDraftRecord
import kotlinx.coroutines.launch

/** Shared by AgentPane and the paused Exit dialog, so a full archive is recoverable while fenced. */
@Composable
internal fun RecoveredExitDraftsDialog(uiState: AgentWorkspaceUiState, onDismiss: () -> Unit) {
    val drafts by uiState.recoveredExitDrafts
    val unconfirmedMedia by uiState.unconfirmedExitMediaDrafts
    val busy by uiState.exitDraftOperationBusy
    val loading by uiState.exitDraftLoading
    val frozen by uiState.exitDraftsFrozen
    val error by uiState.exitDraftError
    val notice by uiState.exitDraftNotice
    val input by uiState.input
    val attachments by uiState.attachments
    val importing by uiState.mediaImporting
    val picker by uiState.mediaPickerPending
    val preflight by uiState.preflightJob
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    fun copySavedText(text: String) {
        try {
            clipboard.setText(AnnotatedString(text))
            uiState.exitDraftError.value = null
            uiState.exitDraftNotice.value = "Complete saved text copied to the clipboard. The saved copy is unchanged."
        } catch (_: Exception) {
            uiState.exitDraftError.value = "Could not copy the saved text. The saved copy is unchanged; try again."
        }
    }
    var pendingDelete by remember(uiState) { mutableStateOf<ExitDraftRecord?>(null) }
    var confirmReferencesOnly by remember(uiState) { mutableStateOf<List<ExitDraftRecord>?>(null) }
    val canRestore = !frozen && !busy && !loading && input.isEmpty() && attachments.isEmpty() &&
        !importing && !picker && preflight?.isCompleted != false

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Saved unsent drafts") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (frozen) "Exit is paused. Delete selected saved copies you no longer need, then retry Exit. Current drafts are unchanged."
                            else "Restore all message text into an empty composer. Check the selected agent and reattach files before sending. Saved copies stay until deleted.",
                            style = MaterialTheme.typography.bodySmall)
                        if (!frozen && !canRestore && !busy && !loading) Text("Empty the composer and finish attachments before restoring.", style = MaterialTheme.typography.labelSmall)
                        error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
                        notice?.let { DismissibleNotice(it) }
                        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (unconfirmedMedia.isNotEmpty()) {
                            Text("These exit drafts are not confirmed saved: attachment copies could not be verified.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy && !loading, onClick = { confirmReferencesOnly = unconfirmedMedia.toList() }) { Text("Review unavailable attachment copies") }
                        }
                        if (!loading && drafts.isEmpty()) Text("No saved unsent drafts.")
                    }
                }
                itemsIndexed(drafts, key = { _, draft -> draft.identity }) { index, draft ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Saved draft ${index + 1} · ${draft.backend}", style = MaterialTheme.typography.titleSmall)
                        draft.session?.let { Text("Original session: $it", style = MaterialTheme.typography.labelSmall) }
                        val preview = exitDraftDisplayPreview(draft.text)
                        Text(if (draft.text.isEmpty()) "No message text; attachment metadata only."
                            else if (preview.length < draft.text.length) "Text preview: ${preview.length} of ${draft.text.length} UTF-16 units. Restore copies all text."
                            else "Complete message text (${draft.text.length} UTF-16 units):", style = MaterialTheme.typography.labelSmall)
                        if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodySmall)
                        draft.attachments.forEach { attachment ->
                            Text(exitDraftAttachmentDescription(attachment, System.currentTimeMillis()), style = MaterialTheme.typography.labelSmall)
                            if (attachment.preview.isNotEmpty()) {
                                val attachmentPreview = exitDraftDisplayPreview(attachment.preview, 160)
                                Text("Saved attachment preview${if (attachment.previewTruncated) " (already incomplete at capture)" else ""}: " + attachmentPreview +
                                    if (attachmentPreview.length < attachment.preview.length) " [Display excerpt; ${attachment.preview.length} UTF-16 units saved.]" else "",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { copySavedText(attachment.preview) }) { Text("Copy full saved preview") }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = canRestore && draft.text.isNotEmpty(), onClick = { if (uiState.restoreExitDraft(draft)) onDismiss() }) { Text("Restore text here") }
                            TextButton(enabled = !busy && !loading, onClick = { pendingDelete = draft }) { Text("Delete saved copy") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") } },
    )

    confirmReferencesOnly?.let { selected ->
        AlertDialog(
            onDismissRequest = { confirmReferencesOnly = null },
            title = { Text("Save attachment references only?") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Attachment copies could not be saved. Retry Exit when storage is available, or save full message text, available previews and references only. Original files may be unavailable or expire; reattach them before sending.") }
                    error?.let { message -> item { DismissibleNotice(message, color = MaterialTheme.colorScheme.error) } }
                    itemsIndexed(selected) { index, draft ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Current draft ${index + 1} · ${draft.backend} · ${draft.text.length} UTF-16 units")
                            Text("Message preview: " + exitDraftDisplayPreview(draft.text), style = MaterialTheme.typography.bodySmall)
                            draft.attachments.forEach { attachment ->
                                Text(exitDraftAttachmentDescription(attachment, System.currentTimeMillis()), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy && !loading, onClick = {
                if (uiState.allowReferenceOnlyExitDrafts(selected)) confirmReferencesOnly = null
            }) { Text("Allow reference-only recovery") } },
            dismissButton = { TextButton(onClick = { confirmReferencesOnly = null }) { Text("Keep paused") } },
        )
    }

    pendingDelete?.let { selected ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text("Delete this saved copy?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${selected.backend} · ${selected.text.length} UTF-16 units · ${selected.attachments.size} attachment references/previews")
                    selected.session?.let { Text("Original session: $it", style = MaterialTheme.typography.labelSmall) }
                    Text("Message preview: " + exitDraftDisplayPreview(selected.text), style = MaterialTheme.typography.bodySmall)
                    Text("Permanently deletes only this saved copy. Your current draft and original files are kept.")
                    error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && !loading, onClick = {
                    scope.launch { if (uiState.deleteSavedExitDraft(selected)) pendingDelete = null }
                }) { Text("Delete saved copy") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingDelete = null }) { Text("Keep saved copy") } },
        )
    }
}

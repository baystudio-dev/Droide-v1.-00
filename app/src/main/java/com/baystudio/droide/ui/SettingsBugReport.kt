package com.baystudio.droide.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import com.baystudio.droide.core.*

@Composable
internal fun rememberSettingsBugReportModel(): SettingsBugReportViewModel {
    val context = LocalContext.current
    val viewContext = LocalView.current.context
    val activity = remember(context, viewContext) { context.supportActivity() ?: viewContext.supportActivity() }
    val exitOwner = LocalExitUiWorkOwner.current
    val epoch = if (exitOwner == null) null else runCatching { ApplicationWorkAdmission.currentEpoch }.getOrNull()
    val model = remember(activity, epoch) {
        if (activity != null) ViewModelProvider(activity).get("droide-support-report-${epoch ?: "unavailable"}", SettingsBugReportViewModel::class.java)
        else SettingsBugReportViewModel(SavedStateHandle())
    }
    DisposableEffect(model, exitOwner, epoch) {
        model.bindWorkOwner(exitOwner, epoch)
        onDispose { if (activity == null) model.release() }
    }
    return model
}

private fun Context.supportActivity(): ComponentActivity? {
    var current: Context = this
    val seen = HashSet<Context>()
    while (seen.add(current)) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

@Composable
internal fun NativeReportBugRow(onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text("Report a bug") },
        supportingContent = { Text("Send a text report to BayStudio inside Droide") },
        leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Open native bug report form", onClick = onOpen),
    )
    HorizontalDivider()
}

@Composable
internal fun SettingsBugReportHost(model: SettingsBugReportViewModel, onOpenPrivacy: () -> Unit) {
    if (!model.visible) return
    LaunchedEffect(model) { model.prepare() }
    Dialog(onDismissRequest = model::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            SettingsBugReportForm(
                draft = model.draft, errors = model.errors, preparing = model.preparing, sending = model.sending,
                ready = model.ready, accepted = model.accepted, notice = model.notice,
                onEdit = model::edit, onSend = { model.send() }, onCancel = model::cancelSending,
                onPrepare = model::prepare, onClose = model::requestClose, onOpenPrivacy = onOpenPrivacy,
            )
        }
        if (model.confirmRetry) AlertDialog(
            onDismissRequest = model::dismissRetryPrompt,
            title = { Text("Send again?") },
            text = { Text("Delivery is unconfirmed. BayStudio may already have the report; resending could create a duplicate.") },
            confirmButton = { TextButton(onClick = { model.send(confirmUnknownRetry = true) }) { Text("Send another report") } },
            dismissButton = { TextButton(onClick = model::dismissRetryPrompt) { Text("Keep draft") } },
        )
        if (model.confirmClose) AlertDialog(
            onDismissRequest = model::dismissClosePrompt,
            title = { Text("Close bug report?") },
            text = { Text(if (model.sending) "This stops the request on this device. A report already accepted by BayStudio cannot be recalled. Keep or discard your local draft?" else "Keep this draft for later in this Droide session, or discard it?") },
            confirmButton = { TextButton(onClick = model::closeKeepingDraft) { Text("Keep draft") } },
            dismissButton = { TextButton(onClick = model::discardAndClose) { Text("Discard draft") } },
        )
    }
}

/** Stateless form for deterministic UI tests. It does not collect diagnostics or perform networking. */
@Composable
internal fun SettingsBugReportForm(
    draft: BugReportDraft,
    errors: Map<BugReportField, String>,
    preparing: Boolean,
    sending: Boolean,
    ready: Boolean,
    accepted: Boolean,
    notice: String,
    onEdit: (BugReportDraft) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onPrepare: () -> Unit,
    onClose: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val scroll = rememberScrollState()
    val focus = remember { BugReportField.entries.associateWith { FocusRequester() } }
    LaunchedEffect(errors.keys) {
        errors.keys.firstOrNull()?.let { field -> runCatching { focus.getValue(field).requestFocus() } }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Report a bug", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close bug report") }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("To BayStudio support", style = MaterialTheme.typography.titleMedium)
            if (notice.isNotBlank()) DismissibleNotice(notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (accepted) {
                Button(onClick = onClose) { Text("Done") }
            } else {
                Text("Reports include only the details you provide. Logs, files and screenshots are not collected. Do not include passwords, keys, tokens or private code.", style = MaterialTheme.typography.bodySmall)
                if (preparing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Preparing secure form…")
                }
                if (!ready && !preparing && !sending) OutlinedButton(onClick = onPrepare) { Text("Prepare secure form") }
                ReportTextField("Issue title *", draft.title, 120, BugReportField.TITLE, errors, focus, !sending,
                    singleLine = true, onChange = { onEdit(draft.copy(title = it)) })
                ReportChoice("Area *", draft.category?.label ?: "Choose an area", BugReportCategory.entries, { it.label }, !sending,
                    error = errors[BugReportField.CATEGORY], modifier = Modifier.focusRequester(focus.getValue(BugReportField.CATEGORY)),
                    onSelected = { onEdit(draft.copy(category = it)) })
                ReportChoice("Impact", draft.priority.label, BugReportPriority.entries, { it.label }, !sending,
                    onSelected = { onEdit(draft.copy(priority = it)) })
                ReportTextField("What happened? *", draft.description, 4000, BugReportField.DESCRIPTION, errors, focus, !sending,
                    onChange = { onEdit(draft.copy(description = it)) })
                ReportTextField("Steps to reproduce *", draft.steps, 2500, BugReportField.STEPS, errors, focus, !sending,
                    onChange = { onEdit(draft.copy(steps = it)) })
                ReportTextField("Expected result (optional)", draft.expected, 1200, BugReportField.EXPECTED, errors, focus, !sending,
                    onChange = { onEdit(draft.copy(expected = it)) })
                ReportTextField("Actual result (optional)", draft.actual, 1200, BugReportField.ACTUAL, errors, focus, !sending,
                    onChange = { onEdit(draft.copy(actual = it)) })
                Text("Optional details", style = MaterialTheme.typography.titleSmall)
                Text("Share only the details you want BayStudio to receive.", style = MaterialTheme.typography.bodySmall)
                ReportTextField("Droide version (optional)", draft.version, 50, BugReportField.VERSION, errors, focus, !sending,
                    singleLine = true, onChange = { onEdit(draft.copy(version = it)) })
                ReportTextField("Android version (optional)", draft.android, 60, BugReportField.ANDROID, errors, focus, !sending,
                    singleLine = true, onChange = { onEdit(draft.copy(android = it)) })
                ReportTextField("Device (optional)", draft.device, 100, BugReportField.DEVICE, errors, focus, !sending,
                    singleLine = true, onChange = { onEdit(draft.copy(device = it)) })
                ReportTextField("Your email (optional)", draft.email, 254, BugReportField.EMAIL, errors, focus, !sending,
                    singleLine = true, keyboardType = KeyboardType.Email, onChange = { onEdit(draft.copy(email = it)) })
                Text("Add your email only if you would like a reply.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .focusRequester(focus.getValue(BugReportField.PRIVACY))
                    .toggleable(value = draft.privacyAcknowledged, enabled = !sending, role = Role.Checkbox) { onEdit(draft.copy(privacyAcknowledged = it)) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.privacyAcknowledged, onCheckedChange = null, enabled = !sending)
                    Text("I agree to BayStudio using these details to investigate the issue under its Privacy Policy.", modifier = Modifier.weight(1f))
                }
                errors[BugReportField.PRIVACY]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onOpenPrivacy, enabled = !sending) { Text("Read website Privacy Policy (opens browser)") }
                if (sending) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel request") }
                    Text("Cancelling cannot recall a submitted report.", style = MaterialTheme.typography.bodySmall)
                } else Button(onClick = onSend, enabled = ready && !preparing, modifier = Modifier.fillMaxWidth()) { Text("Send bug report") }
                Text("Tap Send to submit. Your draft is kept if submission fails or delivery is unconfirmed.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReportTextField(
    label: String, value: String, maximum: Int, field: BugReportField,
    errors: Map<BugReportField, String>, focus: Map<BugReportField, FocusRequester>, enabled: Boolean,
    singleLine: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= maximum) onChange(it) }, enabled = enabled,
        label = { Text(label) }, isError = errors.containsKey(field),
        supportingText = { Text(errors[field] ?: "${value.length}/$maximum") },
        singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 7,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue(field)))
}

@Composable
private fun <T> ReportChoice(
    label: String, value: String, choices: List<T>, choiceLabel: (T) -> String, enabled: Boolean,
    error: String? = null, modifier: Modifier = Modifier, onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = modifier.fillMaxWidth()) { Text(value) }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                choices.forEach { choice -> DropdownMenuItem(text = { Text(choiceLabel(choice)) }, onClick = { expanded = false; onSelected(choice) }) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

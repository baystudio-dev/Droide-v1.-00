package com.baystudio.droide.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baystudio.droide.MainActivity
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** On-demand frozen retained text. It never observes output flow or changes terminal selection. */
@Composable
internal fun TerminalHistoryDialog(session: ITerminalSession, onDismiss: () -> Unit) {
    val scope = rememberWorkspaceCoroutineScope()
    val context = LocalContext.current
    var refresh by remember(session) { mutableIntStateOf(0) }
    var snapshot by remember(session) { mutableStateOf<TerminalRetainedHistory?>(null) }
    var capturedAt by remember(session) { mutableStateOf<String?>(null) }
    var capturing by remember(session) { mutableStateOf(true) }
    var query by remember(session) { mutableStateOf("") }
    var ignoreCase by remember(session) { mutableStateOf(true) }
    var searching by remember(session) { mutableStateOf(false) }
    var matches by remember(session) { mutableStateOf<TerminalRetainedHistory.SearchResult?>(null) }
    var captureError by remember(session) { mutableStateOf<String?>(null) }
    var searchError by remember(session) { mutableStateOf<String?>(null) }
    var actionError by remember(session) { mutableStateOf<String?>(null) }

    var captureOwner by remember(session) { mutableStateOf<Any?>(null) }
    var searchOwner by remember(session) { mutableStateOf<Any?>(null) }
    DisposableEffect(session, refresh) {
        val ticket = Any()
        captureOwner = ticket
        val job = scope.launch {
            capturing = true
            snapshot = null
            capturedAt = null
            captureError = null
            try {
                val epoch = ApplicationWorkAdmission.requireOpen()
                val text = when (session) {
                    // The pinned native emulator is owned by the main Looper.
                    is NativePtySessionHandle -> session.captureTranscript()
                    is DevicePtySessionHandle -> withContext(Dispatchers.Default) { session.terminalEmulator.snapshotText() }
                    is TerminalSession -> withContext(Dispatchers.Default) { session.captureRetainedHistory() }
                    else -> session.output.value
                }
                val frozen = withContext(Dispatchers.Default) {
                    val owner = currentCoroutineContext()
                    TerminalRetainedHistory.capture(text) { owner.ensureActive() }
                }
                currentCoroutineContext().ensureActive()
                check(ApplicationWorkAdmission.isCurrentOpen(epoch))
                snapshot = frozen
                capturedAt = java.text.DateFormat.getTimeInstance().format(java.util.Date())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { captureError = "Could not capture retained text. No terminal data was changed; refresh to retry." }
            finally { if (captureOwner === ticket) capturing = false }
        }
        onDispose { job.cancel() }
    }
    DisposableEffect(snapshot, query, ignoreCase) {
        val frozen = snapshot
        val searchQuery = query
        val foldCase = ignoreCase
        val ticket = Any()
        searchOwner = ticket
        val job = scope.launch {
            matches = null
            searchError = null
            searching = frozen != null && searchQuery.isNotEmpty()
            try {
                if (frozen != null) {
                    val result = withContext(Dispatchers.Default) {
                        val owner = currentCoroutineContext()
                        frozen.search(searchQuery, foldCase) { owner.ensureActive() }
                    }
                    currentCoroutineContext().ensureActive()
                    matches = result
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { searchError = "Use a valid text query of up to 256 UTF-16 units." }
            finally { if (searchOwner === ticket) searching = false }
        }
        onDispose { job.cancel() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 700.dp).fillMaxWidth().heightIn(max = 620.dp)
            .testTag("terminal-retained-history"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Retained terminal history", style = MaterialTheme.typography.titleLarge)
                Text("Snapshot of retained output, not a complete log. Older or replaced output is unavailable; spacing and line endings may differ.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Native/command mode retains up to 200,000 UTF-16 units; Device up to 300,000. Export may contain secrets. Choose a trusted destination.", style = MaterialTheme.typography.bodySmall)
                capturedAt?.let { Text("Snapshot at $it · ${snapshot?.text?.length ?: 0} UTF-16 units", style = MaterialTheme.typography.labelSmall) }
                OutlinedTextField(query, onValueChange = { value ->
                    if (value.length <= TerminalRetainedHistory.MAX_QUERY_UTF16) { query = value; searchError = null }
                    else searchError = "Query limit is 256 UTF-16 units; extra input was not accepted."
                }, label = { Text("Find literal text") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    enabled = snapshot != null)
                Row {
                    FilterChip(selected = ignoreCase, onClick = { ignoreCase = !ignoreCase }, label = { Text("Ignore case") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { refresh++ }, enabled = !capturing) { Text("Refresh") }
                }
                listOfNotNull(captureError, searchError, actionError).forEach { message -> key(message) { DismissibleNotice(message, color = MaterialTheme.colorScheme.error, ownerKey = session) } }
                when {
                    capturing -> Text("Capturing retained text…")
                    searching -> Text("Searching snapshot…")
                    query.isEmpty() -> Text("Search this snapshot. Export includes all retained output, not just matches.", style = MaterialTheme.typography.bodySmall)
                    matches != null -> Text(if (matches!!.capped) "First ${matches!!.matches.size} matches; more matches omitted" else "${matches!!.matches.size} matches", style = MaterialTheme.typography.bodySmall)
                }
                val found = matches?.matches.orEmpty()
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(found, key = { _, match -> match.startUtf16 }) { index, match ->
                        Column {
                            Text("Match ${index + 1} · position ${match.startUtf16}", style = MaterialTheme.typography.labelSmall)
                            val start = match.startUtf16 - match.snippetStartUtf16
                            val end = match.endUtf16Exclusive - match.snippetStartUtf16
                            val selected = MaterialTheme.colorScheme.secondaryContainer
                            val foreground = MaterialTheme.colorScheme.onSecondaryContainer
                            SelectionContainer {
                                Text(buildAnnotatedString {
                                    append(match.snippet.substring(0, start))
                                    withStyle(SpanStyle(background = selected, color = foreground)) { append(match.snippet.substring(start, end)) }
                                    append(match.snippet.substring(end))
                                }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val frozen = snapshot ?: return@TextButton
                        runUiCatching(onError = { actionError = "Could not start export. Finish any pending export and try again." }) {
                            checkNotNull(context.terminalHistoryActivity()) { "Activity unavailable" }.exportTerminalHistory(frozen)
                            actionError = null
                        }
                    }, enabled = snapshot != null && !capturing) { Text("Export snapshot…") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

private fun Context.terminalHistoryActivity(): MainActivity? {
    var current = this
    repeat(16) {
        if (current is MainActivity) return current as MainActivity
        val next = (current as? ContextWrapper)?.baseContext ?: return null
        if (next === current) return null
        current = next
    }
    return null
}

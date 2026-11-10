package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.baystudio.droide.core.ApprovalInputReview
import kotlinx.coroutines.*

/** Full input inspection only: no save, export, execution or network action. */
@Composable
internal fun AgentInputReview(review: ApprovalInputReview) {
    var viewing by remember(review) { mutableStateOf(false) }
    Column {
        Text("${review.label.title} · ${review.byteLength} UTF-8 bytes", style = MaterialTheme.typography.labelSmall)
        if (review.optionsSummary.isNotBlank()) Text(review.optionsSummary, style = MaterialTheme.typography.labelSmall)
        TextButton({ viewing = true }, Modifier.testTag("agent-read-full-input")) { Text("Read full ${review.label.title.lowercase()}") }
    }
    if (viewing) {
        var page by remember(review) { mutableIntStateOf(0) }
        var loaded by remember(review, page) { mutableStateOf<ApprovalInputReview.Page?>(null) }
        var failed by remember(review, page) { mutableStateOf(false) }
        LaunchedEffect(review, page) {
            try {
                loaded = withContext(Dispatchers.IO) {
                    val task = currentCoroutineContext()
                    review.readPage(page) { task.ensureActive() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        Dialog(onDismissRequest = { viewing = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 600.dp).padding(16.dp)) {
                    Text("${review.label.title} · page ${page + 1}", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text("SHA-256: ${review.sha256}", style = MaterialTheme.typography.labelSmall) }
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(key(page) { rememberScrollState() })) {
                        SelectionContainer { Text(if (failed) "Input unavailable or request expired. Close this review." else loaded?.text ?: "Loading…",
                            Modifier.testTag("agent-full-input-page")) }
                    }
                    Row {
                        TextButton({ page-- }, enabled = page > 0 && loaded != null) { Text("Previous") }
                        TextButton({ page++ }, enabled = loaded?.hasNext == true && page < Int.MAX_VALUE - 1) { Text("Next") }
                        TextButton({ viewing = false }) { Text("Close") }
                    }
                }
            }
        }
    }
}

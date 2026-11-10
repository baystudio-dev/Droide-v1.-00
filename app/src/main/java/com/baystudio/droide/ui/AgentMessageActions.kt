package com.baystudio.droide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

private val LocalSelectAgentText = staticCompositionLocalOf<((String) -> Unit)?> { null }
internal const val AGENT_SELECTION_TEXT_TAG = "Droide agent selection text"
// A conservative app-side IPC budget, not a promise about a device's shared Binder buffer. The
// complete source remains available through bounded selection pages.
internal const val MAX_AGENT_CLIPBOARD_TEXT_CHARS = 128_000

/** Pane-owned: live items can be replaced by durable messages without closing a selection. */
@Composable
internal fun AgentMessageInteractionHost(conversationKey: Any?, content: @Composable () -> Unit) {
    var snapshot by remember(conversationKey) { mutableStateOf<String?>(null) }
    val select = remember(conversationKey) { { text: String -> snapshot = text } }
    CompositionLocalProvider(LocalSelectAgentText provides select, content = content)
    snapshot?.let { text ->
        key(conversationKey) { AgentTextSelectionDialog(text, onDismiss = { snapshot = null }) }
    }
}

@Composable
internal fun AgentMessageActions(markdown: String, linkError: String? = null) {
    val context = LocalContext.current
    val select = LocalSelectAgentText.current
    var copied by remember { mutableStateOf(false) }
    var copyError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied) { delay(1_600); copied = false } }
    DisableSelection {
        Column {
            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = "Agent response actions" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        copyError = copyAgentText(context, markdown, "Droide response")
                        copied = copyError == null
                    },
                    modifier = Modifier.size(48.dp).semantics { stateDescription = if (copied) "Copied" else "" },
                ) {
                    Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        "Copy response", Modifier.size(19.dp),
                        tint = if (copied) DroideColors.Success else DroideColors.Muted)
                }
                if (select != null) {
                    IconButton(onClick = { select(markdown) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.TextFields, "Select response text", Modifier.size(20.dp), tint = DroideColors.Muted)
                    }
                }
                if (copied) Text("Copied", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
            (copyError ?: linkError)?.let {
                DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = markdown)
            }
        }
    }
}

internal fun copyAgentText(context: Context, text: String, label: String): String? = try {
    if (text.length > MAX_AGENT_CLIPBOARD_TEXT_CHARS) {
        "This response is too large for one clipboard copy. Select response text and copy a smaller page."
    } else {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) "Clipboard unavailable. Try selecting the text." else {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            null
        }
    }
} catch (_: RuntimeException) {
    "Could not copy this text. Try selecting a smaller section."
}

/** Explicit taps only; preserve percent-encoded auth URLs and recover if no browser is available. */
internal fun openAgentChatLink(context: Context, url: String): String? {
    if (!AgentMarkdownParser.isSafeHttpUrl(url)) return "This link cannot be opened."
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: RuntimeException) {
        "Cannot open this link. Copy the response and open it in a browser."
    }
}

private class AgentSelectableTextView(context: Context) : TextView(context) {
    var selectionChanged: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionChanged?.invoke(selStart, selEnd)
    }
}

private class AgentSelectionViewHolder { var view: AgentSelectableTextView? = null }

@Composable
private fun AgentTextSelectionDialog(markdown: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // A short selection should appear immediately. Larger conversions run off the UI thread.
    var document by remember(markdown) {
        mutableStateOf(if (markdown.length <= 4_096) AgentMessageSelection.fromMarkdown(markdown) else null)
    }
    LaunchedEffect(markdown) {
        if (document == null) document = withContext(Dispatchers.Default) {
            val task = currentCoroutineContext()
            AgentMessageSelection.fromMarkdown(markdown) { task.ensureActive() }
        }
    }
    var page by remember(markdown) { mutableIntStateOf(0) }
    var range by remember(markdown, page) { mutableStateOf(0 to 0) }
    var copyStatus by remember { mutableStateOf<String?>(null) }
    val holder = remember { AgentSelectionViewHolder() }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val height = minOf(520, (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160)).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(height), shape = MaterialTheme.shapes.large,
            color = DroideColors.Surface) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Select text", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Close text selection", Modifier.size(20.dp))
                    }
                }
                val loaded = document
                if (loaded?.sourceMode == true) {
                    Text("Full response source · all pages", style = MaterialTheme.typography.labelSmall,
                        color = DroideColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (loaded == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else {
                    val pageText = remember(loaded, page) { loaded.page(page) }
                    key(markdown, page) {
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                .semantics { contentDescription = "Selectable response text" },
                            factory = { ctx ->
                                ScrollView(ctx).apply {
                                    isFillViewport = true
                                    addView(AgentSelectableTextView(ctx).apply {
                                        tag = AGENT_SELECTION_TEXT_TAG
                                        text = pageText
                                        setTextColor(color)
                                        textSize = 14f
                                        val inset = (8 * resources.displayMetrics.density).roundToInt()
                                        setPadding(inset, inset, inset, inset * 2)
                                        setTextIsSelectable(true)
                                        setHorizontallyScrolling(false)
                                        selectionChanged = { start, end -> range = start to end }
                                        holder.view = this
                                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                                }
                            },
                            update = { (it.getChildAt(0) as TextView).setTextColor(color) },
                            onRelease = {
                                val released = it.getChildAt(0) as AgentSelectableTextView
                                released.selectionChanged = null
                                if (holder.view === released) holder.view = null
                            },
                        )
                    }
                    if (loaded.pages.size > 1) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { page--; copyStatus = null }, enabled = page > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
                            Text("${page + 1} / ${loaded.pages.size}", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { page++; copyStatus = null }, enabled = page < loaded.pages.lastIndex,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val view = holder.view
                        val start = minOf(range.first, range.second).coerceAtLeast(0)
                        val end = maxOf(range.first, range.second)
                        if (view != null && end in (start + 1)..view.text.length) {
                            copyStatus = copyAgentText(context, view.text.subSequence(start, end).toString(), "Droide selection") ?: "Copied"
                        }
                    }, enabled = range.first >= 0 && range.second >= 0 && range.first != range.second,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy selection") }
                    copyStatus?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
                }
            }
        }
    }
}

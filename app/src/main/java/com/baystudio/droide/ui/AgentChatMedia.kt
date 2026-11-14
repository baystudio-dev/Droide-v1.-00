package com.baystudio.droide.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.baystudio.droide.core.ChatMediaRef
import com.baystudio.droide.core.ChatMediaStore
import com.baystudio.droide.core.ChatMediaCleanup
import com.baystudio.droide.core.AgentService
import com.baystudio.droide.core.AgentMessage
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cancellation closes only this import stream, never a file transferred to a saved conversation. */
internal class AgentMediaImportOperation(val token: AgentMediaDraftToken) {
    val signal = android.os.CancellationSignal()
    private val stopped = AtomicBoolean(false)
    private val stream = AtomicReference<InputStream?>(null)
    var job: Job? = null
    val cancelled: Boolean get() = stopped.get()
    fun bindStream(input: InputStream) {
        stream.set(input)
        if (stopped.get()) {
            stream.compareAndSet(input, null)
            input.close()
            throw CancellationException("Attachment import canceled")
        }
    }
    fun releaseStream(input: InputStream) { stream.compareAndSet(input, null) }
    fun cancel(cleanupScope: CoroutineScope) {
        stopped.set(true)
        job?.cancel()
        cleanupScope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            runCatching { stream.getAndSet(null)?.close() }
            runCatching { signal.cancel() }
        }
    }
}

/** A successful idle history deletion must not leave the next prompt targeting its retired ID. */
internal fun resetDeletedAgentConversation(agent: AgentService, uiState: AgentWorkspaceUiState, deletedId: String): Boolean {
    if (agent.currentSessionId != deletedId || agent.busy.value || uiState.preflightJob.value?.isActive == true) return false
    agent.clear()
    if (uiState.backendId.value == "droide") {
        uiState.discardMediaDraft()
        uiState.input.value = ""
    }
    return true
}

internal data class AgentNativeLiveMediaEntry(val messageIndex: Int, val mediaIndex: Int, val ref: ChatMediaRef) {
    fun key(runId: Long): String = "live-media:$runId:$messageIndex:$mediaIndex:${ref.id}"
}

internal data class AgentNativeLiveMedia(val entries: List<AgentNativeLiveMediaEntry>, val total: Int = 0) {
    val hidden: Int get() = (total - entries.size).coerceAtLeast(0)
}

/** New native messages are frozen out of the transcript while text streams; expose only recent media. */
internal fun projectAgentNativeLiveMedia(messages: List<AgentMessage>, busy: Boolean, baseCount: Int): AgentNativeLiveMedia {
    if (!busy || baseCount !in 0..messages.size) return AgentNativeLiveMedia(emptyList())
    val latest = ArrayList<AgentNativeLiveMediaEntry>(16)
    var total = 0
    for (messageIndex in messages.lastIndex downTo baseCount) {
        val message = messages[messageIndex]
        if (message.role != "assistant") continue
        total += message.media.size
        if (latest.size < 16) for (mediaIndex in message.media.lastIndex downTo 0) {
            latest += AgentNativeLiveMediaEntry(messageIndex, mediaIndex, message.media[mediaIndex])
            if (latest.size == 16) break
        }
    }
    latest.reverse()
    return AgentNativeLiveMedia(latest, total)
}

/** Explicit category selection before launching an Android picker. */
@Composable
internal fun AgentAttachmentMenu(enabled: Boolean, onVisual: () -> Unit, onFile: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Add, "Add attachment", Modifier.size(18.dp), tint = DroideColors.Muted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Images & videos") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                onClick = { expanded = false; onVisual() }, modifier = Modifier.heightIn(min = 48.dp))
            DropdownMenuItem(text = { Text("Files") }, leadingIcon = { Icon(Icons.Default.Description, null) },
                onClick = { expanded = false; onFile() }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

/** One retained draft owns the import; closing/recreating AgentPane does not cancel accepted work. */
internal fun importPickedAgentMedia(
    context: android.content.Context,
    uiState: AgentWorkspaceUiState,
    uri: Uri?,
) {
    val token = uiState.pendingMediaPicker
    uiState.pendingMediaPicker = null
    uiState.mediaPickerPending.value = false
    if (uri == null || token == null || !uiState.isCurrentMediaDraft(token)) return
    val operation = AgentMediaImportOperation(token)
    val job = uiState.scope.launch(start = CoroutineStart.LAZY) {
        var attachment: AgentAttachedContext? = null
        var attached = false
        try {
            require(uiState.attachments.value.size < 4) { "Remove an attachment before adding another (maximum 4)." }
            attachment = readAgentAttachment(context.applicationContext, uri, token.root, token.ownerId, operation)
            withContext(Dispatchers.IO) { ChatMediaCleanup.schedule(context.applicationContext, token.root) }
            if (!operation.cancelled && uiState.isCurrentMediaDraft(token) && uiState.attachments.value.size < 4) {
                uiState.attachments.value = uiState.attachments.value + attachment
                attached = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!operation.cancelled && uiState.isCurrentMediaDraft(token)) uiState.mediaImportError.value =
                failure.message?.take(160)?.ifBlank { null } ?: "Could not attach this file. Try again."
        } finally {
            if (!attached) attachment?.media?.let { ref -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { ChatMediaStore(token.root).release(token.ownerId, ref) }
                runCatching { ChatMediaCleanup.schedule(context.applicationContext, token.root) }
            } }
            uiState.finishMediaImport(operation)
        }
    }
    operation.job = job
    uiState.ownMediaImport(operation)
    job.start()
}

@Composable
internal fun AgentDraftMediaRow(
    attachments: List<AgentAttachedContext>,
    importing: Boolean,
    error: String?,
    workDir: File,
    onRemove: (Int) -> Unit,
    onCancelImport: () -> Unit = {},
) {
    if (attachments.isEmpty() && !importing && error == null) return
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (attachments.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            attachments.forEachIndexed { index, attachment ->
                Surface(color = DroideColors.Surface, border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.small) {
                    Row(Modifier.heightIn(min = 52.dp).widthIn(max = 220.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ref = attachment.media
                        if (ref != null) AgentMediaThumbnail(ref, workDir, Modifier.size(44.dp).padding(4.dp))
                        else Icon(Icons.Default.Description, null, Modifier.size(40.dp).padding(8.dp), tint = DroideColors.Muted)
                        Text(attachment.name, Modifier.widthIn(max = 124.dp).padding(start = 3.dp), style = MaterialTheme.typography.labelSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, "Remove ${attachment.name}", Modifier.size(17.dp), tint = DroideColors.Muted)
                        }
                    }
                }
            }
        }
        if (importing) Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            Text("Attaching file…", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            IconButton(onClick = onCancelImport, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Cancel attachment import", Modifier.size(18.dp), tint = DroideColors.Muted) }
        }
        error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Metadata only in Compose; file resolution/decode happens for visible cards on background workers. */
@Composable
internal fun AgentMessageMedia(media: List<ChatMediaRef>, workDir: File) {
    AgentMessageMedia(media, workDir, alignToEnd = false)
}

@Composable
internal fun AgentMessageMedia(media: List<ChatMediaRef>, workDir: File, alignToEnd: Boolean) {
    if (media.isEmpty()) return
    var page by remember(media) { mutableIntStateOf(0) }
    val lastPage = (media.size - 1) / 4
    val effectivePage = page.coerceIn(0, lastPage)
    val first = effectivePage * 4
    val end = minOf(first + 4, media.size)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = if (alignToEnd) Alignment.End else Alignment.Start) {
        // Even a damaged history file cannot start hundreds of thumbnail jobs in one row.
        media.subList(first, end).forEach { ref -> key(ref.id) { AgentMediaCard(ref, workDir) } }
        if (media.size > 4) Row((if (alignToEnd) Modifier.widthIn(max = 340.dp) else Modifier).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Attachments ${first + 1}–$end of ${media.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            TextButton(onClick = { page = effectivePage - 1 }, enabled = effectivePage > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
            TextButton(onClick = { page = effectivePage + 1 }, enabled = effectivePage < lastPage, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
        }
    }
}

@Composable
internal fun AgentMediaCard(ref: ChatMediaRef, workDir: File) {
    val context = LocalContext.current
    val scope = rememberWorkspaceCoroutineScope()
    var preview by remember(ref.id) { mutableStateOf(false) }
    var error by remember(ref.id) { mutableStateOf<String?>(null) }
    val availability by rememberAgentMediaFile(workDir, ref)
    val file = availability.file
    val available = file != null
    val image = ref.mediaType.startsWith("image/")
    val video = ref.mediaType.startsWith("video/")
    fun open(share: Boolean = false) {
        scope.launch {
            error = withContext(Dispatchers.IO) {
                if (share) AgentChatMediaOpener.share(context.applicationContext, workDir, ref)
                else AgentChatMediaOpener.open(context.applicationContext, workDir, ref)
            }
        }
    }
    Surface(Modifier.widthIn(max = 340.dp).fillMaxWidth().semantics { contentDescription = "Attachment ${ref.name}" },
        color = DroideColors.Surface, border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(8.dp)) {
            if (image || video) Box(
                Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.extraSmall)
                    .background(DroideColors.Background).clickable(enabled = available) {
                        if (image) preview = true else open()
                    }.semantics { contentDescription = if (video) "Open video ${ref.name}" else "Preview image ${ref.name}" },
                contentAlignment = Alignment.Center,
            ) {
                if (available && image) AsyncImage(
                    model = agentMediaImageRequest(context, file!!, 256), contentDescription = ref.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, imageLoader = agentMediaImageLoader(context),
                ) else Icon(if (video) Icons.Default.PlayCircle else Icons.Default.Image, null, Modifier.size(36.dp), tint = DroideColors.Muted)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!image && !video) Icon(Icons.Default.Description, null, Modifier.size(24.dp), tint = DroideColors.Muted)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
                    Text(ref.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(agentMediaSize(ref.bytes), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
                IconButton(onClick = { open() }, enabled = available, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.OpenInNew, "Open ${ref.name}", Modifier.size(20.dp), tint = DroideColors.Muted)
                }
                IconButton(onClick = { open(share = true) }, enabled = available, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Download, "Export ${ref.name}", Modifier.size(20.dp), tint = DroideColors.Muted)
                }
            }
            if (!available && availability.loaded) Text(if (ref.expiresAt <= System.currentTimeMillis()) "Media removed after 7 days" else "Media unavailable",
                style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            error?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (preview && available) AgentImagePreview(ref, file!!, onDismiss = { preview = false })
}

@Composable
private fun AgentMediaThumbnail(ref: ChatMediaRef, workDir: File, modifier: Modifier) {
    val context = LocalContext.current
    val availability by rememberAgentMediaFile(workDir, ref)
    val file = availability.file
    if (file != null && ref.mediaType.startsWith("image/")) AsyncImage(
        model = agentMediaImageRequest(context, file, 128), contentDescription = ref.name,
        modifier = modifier.clip(MaterialTheme.shapes.extraSmall), contentScale = ContentScale.Crop, imageLoader = agentMediaImageLoader(context),
    ) else Box(modifier, contentAlignment = Alignment.Center) {
        Icon(if (ref.mediaType.startsWith("video/")) Icons.Default.PlayCircle else Icons.Default.Description,
            null, Modifier.size(24.dp), tint = DroideColors.Muted)
    }
}

private data class AgentMediaAvailability(val file: File? = null, val loaded: Boolean = false)

@Composable
private fun rememberAgentMediaFile(workDir: File, ref: ChatMediaRef): State<AgentMediaAvailability> =
    produceState(AgentMediaAvailability(), workDir, ref) {
    value = AgentMediaAvailability(withContext(Dispatchers.IO) { runCatching { ChatMediaStore(workDir).file(ref) }.getOrNull() }, loaded = true)
    // One expiry timer only while visible; there is no polling, wake lock, or background decoder.
    val remaining = ref.expiresAt - System.currentTimeMillis()
    if (remaining > 0 && value.file != null) { delay(remaining); value = AgentMediaAvailability(loaded = true) }
}

private object AgentMediaImageLoader {
    @Volatile var instance: coil.ImageLoader? = null
}

internal fun agentMediaImageLoader(context: android.content.Context): coil.ImageLoader =
    AgentMediaImageLoader.instance ?: synchronized(AgentMediaImageLoader) {
        AgentMediaImageLoader.instance ?: coil.ImageLoader.Builder(context.applicationContext)
            .memoryCache { coil.memory.MemoryCache.Builder(context.applicationContext).maxSizeBytes(16 * 1024 * 1024).build() }
            .diskCache(null).crossfade(false).build().also { AgentMediaImageLoader.instance = it }
    }

internal fun agentMediaImageRequest(context: android.content.Context, file: File, pixels: Int): ImageRequest =
    ImageRequest.Builder(context.applicationContext).data(file).size(pixels.coerceIn(1, 1_024))
        .memoryCacheKey("chat-media:${file.absolutePath}:${pixels.coerceIn(1, 1_024)}").diskCachePolicy(coil.request.CachePolicy.DISABLED).build()

internal fun agentMediaSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KiB"
    else -> "${bytes / 1_048_576} MiB"
}

@Composable
private fun AgentImagePreview(ref: ChatMediaRef, file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val height = (config.screenHeightDp - 48).coerceIn(160, 640).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(height), color = DroideColors.Surface, shape = MaterialTheme.shapes.large) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ref.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close image preview") }
                }
                AsyncImage(model = agentMediaImageRequest(context, file, 1_024), contentDescription = ref.name,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentScale = ContentScale.Fit, imageLoader = agentMediaImageLoader(context))
            }
        }
    }
}

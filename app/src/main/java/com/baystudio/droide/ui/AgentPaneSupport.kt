package com.baystudio.droide.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.TravelExplore
import com.baystudio.droide.core.AgentMode
import com.baystudio.droide.core.AgentService
import com.baystudio.droide.core.ChatMediaRef
import com.baystudio.droide.core.ChatMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AgentAttachedContext(
    val name: String,
    val text: String,
    val imagePath: String? = null,
    val media: ChatMediaRef? = null,
    val previewTruncated: Boolean = false,
)

internal suspend fun readAgentAttachment(context: Context, uri: Uri, workDir: java.io.File, ownerId: String, operation: AgentMediaImportOperation? = null): AgentAttachedContext = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, operation?.signal)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(120)?.ifBlank { null } ?: "attachment"
    val mime = resolver.getType(uri)?.substringBefore(';')?.lowercase() ?: "application/octet-stream"
    val store = ChatMediaStore(workDir)
    val ref = resolver.openAssetFileDescriptor(uri, "r", operation?.signal)?.use { descriptor ->
        descriptor.createInputStream().use { input ->
            operation?.bindStream(input)
            try { store.import(ownerId, name, mime, input) } finally { operation?.releaseStream(input) }
        }
    } ?: error("Unable to read attachment")
    try {
        val text = if (agentAttachmentIsText(name, ref.mediaType)) {
            store.file(ref)?.bufferedReader()?.use(::readAgentAttachmentPreview).orEmpty()
        } else "[${if (ref.mediaType.startsWith("image/")) "Image" else if (ref.mediaType.startsWith("video/")) "Video" else "File"} attachment: ${ref.name}]"
        var end = minOf(text.length, 12_000)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        AgentAttachedContext(ref.name, text.take(end), media = ref, previewTruncated = end < text.length)
    } catch (failure: Throwable) {
        store.release(ownerId, ref)
        throw failure
    }
}

internal fun agentAttachmentIsText(name: String, mime: String): Boolean = mime.startsWith("text/") ||
    mime in setOf("application/json", "application/xml", "application/yaml", "application/javascript", "application/x-sh") ||
    (mime == "application/octet-stream" && name.substringAfterLast('.').lowercase() in setOf(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "ini", "csv", "log", "kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "css", "rs", "go", "c", "h", "cpp", "hpp", "sh",
    ))

private fun readAgentAttachmentPreview(reader: java.io.Reader): String {
    val out = StringBuilder()
    val buffer = CharArray(2_048)
    while (out.length < 12_001) {
        val read = reader.read(buffer, 0, minOf(buffer.size, 12_001 - out.length))
        if (read <= 0) break
        out.append(buffer, 0, read)
    }
    return out.toString()
}

internal suspend fun readAgentTextAttachment(context: Context, uri: Uri): AgentAttachedContext = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.take(120) ?: "context.txt"
    val text = resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        val out = StringBuilder()
        val buffer = CharArray(2_048)
        while (out.length < 12_000) {
            val read = reader.read(buffer, 0, minOf(buffer.size, 12_000 - out.length))
            if (read <= 0) break
            out.append(buffer, 0, read)
        }
        out.toString()
    } ?: error("Unable to read attachment")
    AgentAttachedContext(name, text)
}

internal const val AGENT_DRAFT_LIMIT_ERROR =
    "That edit exceeds the 64,000 UTF-16 code-unit limit. Your previous draft is unchanged."
internal const val AGENT_PROMPT_LIMIT_ERROR =
    "The combined prompt exceeds the 64,000 UTF-16 code-unit limit. Shorten the message or remove an attachment; your draft and attachments are unchanged."

internal class AgentPromptLimitException : IllegalArgumentException(AGENT_PROMPT_LIMIT_ERROR)

internal fun composeAgentPrompt(userText: String, attachments: List<AgentAttachedContext>): String {
    val limit = AgentService.MAX_USER_MESSAGE_CHARS
    if (userText.length > limit) throw AgentPromptLimitException()
    if (attachments.isEmpty()) return userText
    val out = StringBuilder()
    fun appendChecked(value: String) {

        if (value.length > limit - out.length) throw AgentPromptLimitException()
        out.append(value)
    }
    appendChecked(userText.ifBlank { "Use the attached context for this request." })
    appendChecked("\n\n[Attached context]\n")
    attachments.forEach { attachment ->
        appendChecked("\n--- "); appendChecked(attachment.name); appendChecked(" ---\n")
        attachment.media?.let { ref ->
            appendChecked("[Chat attachment: path="); appendChecked(ref.path)
            appendChecked("; type="); appendChecked(ref.mediaType)
            appendChecked("; bytes="); appendChecked(ref.bytes.toString())
            appendChecked("; expires_at="); appendChecked(ref.expiresAt.toString()); appendChecked("]\n")
            if (attachment.previewTruncated) appendChecked("[Text excerpt is incomplete; use read_file with this path and next_offset to read the rest.]\n")
            if (ref.mediaType.startsWith("video/")) appendChecked("[Video input consists of bounded sampled frames only. Audio and unsampled intervals were not analyzed.]\n")
        }
        appendChecked(attachment.text)
        appendChecked("\n")
    }
    return out.toString()
}

internal fun agentModeLabel(mode: AgentMode): String = when (mode) {
    AgentMode.BUILD -> "Build"
    AgentMode.PLAN -> "Plan"
    AgentMode.REVIEW -> "Review"
    AgentMode.EXPLORE -> "Explore"
}

internal fun agentModeHint(mode: AgentMode): String = when (mode) {
    AgentMode.BUILD -> "Implement and verify with permission-gated tools"
    AgentMode.PLAN -> "Read-only investigation and implementation planning"
    AgentMode.REVIEW -> "Read-only code review focused on concrete findings"
    AgentMode.EXPLORE -> "Internal read-only exploration mode"
}

internal fun agentModeIcon(mode: AgentMode) = when (mode) {
    AgentMode.BUILD -> Icons.Default.Build
    AgentMode.PLAN -> Icons.Default.AccountTree
    AgentMode.REVIEW -> Icons.Default.RateReview
    AgentMode.EXPLORE -> Icons.Default.TravelExplore
}

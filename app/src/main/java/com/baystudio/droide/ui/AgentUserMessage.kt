package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentMessage
import com.baystudio.droide.core.AgentStreamPart
import com.baystudio.droide.core.AgentStreamPartKind
import com.baystudio.droide.core.ChatMediaRef
import java.io.File

private const val attachedContextMarker = "\n\n[Attached context]\n"
private const val attachedContextFallback = "Use the attached context for this request."
private val attachedContextHeading = Regex("^--- [^\\r\\n]+ ---\\n", RegexOption.MULTILINE)

/** Only sections backed by this message's exact media references can be hidden. */

internal fun agentUserMessageText(prompt: String, media: List<ChatMediaRef>): String {
    if (media.isEmpty() || !prompt.endsWith('\n')) return prompt
    val prefixes = media.associateWith { ref ->
        "\n--- ${ref.name} ---\n[Chat attachment: path=${ref.path}; type=${ref.mediaType}; bytes=${ref.bytes}; expires_at=${ref.expiresAt}]\n"
    }
    var marker = prompt.lastIndexOf(attachedContextMarker)
    while (marker >= 0) {
        val start = marker + attachedContextMarker.length
        val headings = attachedContextHeading.findAll(prompt, start).toList()
        if (headings.firstOrNull()?.range?.first == start + 1) {
            val retained = StringBuilder()
            val matched = mutableSetOf<ChatMediaRef>()
            headings.forEachIndexed { index, heading ->
                val block = prompt.substring(heading.range.first - 1,
                    headings.getOrNull(index + 1)?.range?.first?.minus(1) ?: prompt.length)
                val ref = prefixes.entries.firstOrNull { (ref, prefix) ->
                    block.startsWith(prefix) && attachmentBodyMatches(block.substring(prefix.length), ref)
                }?.key
                if (ref != null) matched += ref else retained.append(block)
            }
            if (matched.containsAll(media)) {
                val caption = prompt.substring(0, marker)
                return if (retained.isEmpty()) caption.takeUnless { it == attachedContextFallback }.orEmpty()
                else caption + attachedContextMarker + retained
            }
        }
        marker = prompt.lastIndexOf(attachedContextMarker, marker - 1)
    }
    return prompt
}

private fun attachmentBodyMatches(body: String, ref: ChatMediaRef): Boolean {
    if (agentAttachmentIsText(ref.name, ref.mediaType)) return true
    val kind = when {
        ref.mediaType.startsWith("image/") -> "Image"
        ref.mediaType.startsWith("video/") -> "Video"
        else -> "File"
    }
    val videoNote = if (kind == "Video")
        "[Video input consists of bounded sampled frames only. Audio and unsampled intervals were not analyzed.]\n" else ""
    return body == "$videoNote[$kind attachment: ${ref.name}]\n"
}

@Composable
internal fun AgentUserMessage(text: String, media: List<ChatMediaRef>, workDir: File, label: String = "YOU") {
    val caption = remember(text, media) { agentUserMessageText(text, media) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Primary)
        Spacer(Modifier.height(5.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(Modifier.align(Alignment.CenterEnd).widthIn(max = minOf(maxWidth * .80f, 540.dp)),
                horizontalAlignment = Alignment.End) {
                if (caption.isNotBlank()) Surface(
                    shape = MaterialTheme.shapes.small, color = DroideColors.Surface,
                    border = BorderStroke(1.dp, DroideColors.Border),
                ) {
                    Text(caption, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                AgentMessageMedia(media, workDir, alignToEnd = true)
            }
        }
    }
}

internal fun agentInputMediaByPrompt(messages: List<AgentMessage>): Map<String, List<ChatMediaRef>> =
    messages.asSequence().filter { it.role == "user" && it.media.isNotEmpty() }
        .associate { it.content to it.media }

@Composable
internal fun AgentStreamPartWithMedia(part: AgentStreamPart, showDetails: Boolean, media: List<ChatMediaRef>,
    workDir: File, modifier: Modifier = Modifier) {
    if (part.kind == AgentStreamPartKind.INPUT && media.isNotEmpty()) {
        AgentUserMessage(part.text, media, workDir, part.title.ifBlank { "Instruction" })
    } else {
        AgentStreamPartItem(part, showDetails, modifier)
        AgentMessageMedia(media, workDir)
    }
}

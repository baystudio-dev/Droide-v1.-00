package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentPublicTextPresentation
import com.baystudio.droide.core.AgentPublicTextSpan
import com.baystudio.droide.core.AgentStreamPartState
import com.baystudio.droide.core.AgentTextPresentation

private const val MAX_AGENT_OPEN_UPDATES = 256

private class AgentPublicTextDisclosureState {
    private val expanded = mutableStateMapOf<String, Boolean>()
    private val order = ArrayDeque<String>()
    fun isExpanded(identity: String): Boolean = expanded[identity] == true
    fun setExpanded(identity: String, value: Boolean) {
        if (value) {
            if (expanded[identity] != true) order.addLast(identity)
            expanded[identity] = true
            while (order.size > MAX_AGENT_OPEN_UPDATES) expanded.remove(order.removeFirst())
        } else {
            expanded.remove(identity)
            order.remove(identity)
        }
    }
    fun savedKeys(): List<String> = order.toList()
}

private val LocalAgentPublicTextDisclosure = staticCompositionLocalOf<AgentPublicTextDisclosureState?> { null }
private val AgentPublicTextDisclosureSaver = listSaver<AgentPublicTextDisclosureState, String>(
    save = { it.savedKeys() },
    restore = { keys -> AgentPublicTextDisclosureState().apply {
        keys.takeLast(MAX_AGENT_OPEN_UPDATES).forEach { setExpanded(it, true) }
    } },
)

/** Keep explicit expansion across live/durable replacement without changing the viewport keys. */
@Composable
internal fun AgentPublicTextInteractionHost(conversationKey: Any?, content: @Composable () -> Unit) {
    val disclosure = rememberSaveable(conversationKey, saver = AgentPublicTextDisclosureSaver) { AgentPublicTextDisclosureState() }
    CompositionLocalProvider(LocalAgentPublicTextDisclosure provides disclosure) {
        AgentMessageInteractionHost(conversationKey, content)
    }
}

/**
 * Presentation consumes public provenance only. It never infers a role from text or tool output.
 * The full original source owns the one copy/select row, including when updates are collapsed.
 */
@Composable
internal fun AgentPublicTextContent(
    markdown: String,
    presentation: AgentTextPresentation,
    presentationId: String,
    publicTextSpans: List<AgentPublicTextSpan>,
    state: AgentStreamPartState = AgentStreamPartState.COMPLETED,
    streamId: String? = null,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
) {
    if (markdown.isBlank()) return
    val interrupted = state == AgentStreamPartState.ERROR || state == AgentStreamPartState.CANCELLED
    val terminal = state != AgentStreamPartState.RUNNING && state != AgentStreamPartState.PENDING
    val preview = remember(markdown) { agentMarkdownPreview(markdown) }
    // Validate against the original source, then intersect its safe character/line preview.
    // Passing truncated source to the metadata validator would invalidate otherwise valid spans.
    val slices = remember(markdown, presentation, publicTextSpans, state, preview.text.length) {
        AgentPublicTextPresentation.slices(
            markdown,
            if (interrupted) AgentTextPresentation.RESPONSE else presentation,
            if (interrupted) emptyList() else publicTextSpans,
            completed = state == AgentStreamPartState.COMPLETED,
        ).filter { it.start < preview.text.length }
    }
    // Reserve one literal remainder plus one unparsed streaming tail per section. Even 128
    // sections cannot multiply the existing message-wide 512-node Markdown allowance.
    val sectionNodeBudget = (AgentMarkdownParser.MAX_RENDER_NODES / slices.size.coerceAtLeast(1) - 2).coerceAtLeast(0)
    val identity = presentationId.ifBlank { streamId ?: "public-message" }
    var bodyLimited by remember(identity) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // A typed message still has one shared height ceiling, not one ceiling per span.
        AgentMarkdownBodyBounds(onClipped = { bodyLimited = it }) {
            slices.forEach { slice ->
                key(identity, slice.start) {
                    val previewEnd = minOf(slice.endExclusive, preview.text.length)
                    val text = remember(markdown, slice.start, previewEnd) {
                        markdown.substring(slice.start, previewEnd)
                    }
                    AgentPublicTextSection(
                        text = text,
                        presentation = slice.presentation,
                        identity = "$identity:${slice.start}",
                        completed = terminal || slice.endExclusive < markdown.length,
                        streamId = streamId?.let { "$it:${slice.start}" },
                        persistDisclosure = presentationId.isNotBlank(),
                        sourceShortened = slice.endExclusive > preview.text.length,
                        maxRenderNodes = sectionNodeBudget,
                    )
                }
            }
        }
        if (preview.shortened || bodyLimited) AgentMarkdownPreviewNotice(DroideColors.Text)
        if (showActions) AgentMessageActions(markdown)
    }
}

@Composable
private fun AgentPublicTextSection(
    text: String,
    presentation: AgentTextPresentation,
    identity: String,
    completed: Boolean,
    streamId: String?,
    persistDisclosure: Boolean,
    sourceShortened: Boolean,
    maxRenderNodes: Int,
) {
    // Do not key by text, length, completion or presentation: an explicit expansion survives
    // streaming/completion changes. Running updates are forced open without changing this choice.
    var localExpanded by rememberSaveable(identity) { mutableStateOf(false) }
    val disclosure = LocalAgentPublicTextDisclosure.current.takeIf { persistDisclosure }
    val userExpanded = disclosure?.isExpanded(identity) ?: localExpanded
    val update = presentation == AgentTextPresentation.UPDATE
    val answer = presentation == AgentTextPresentation.ANSWER
    val expanded = !update || !completed || userExpanded
    val bodyStyle = (if (answer || presentation == AgentTextPresentation.RESPONSE)
        MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall)
        .copy(color = DroideColors.Text)
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (update) {
                val toggle = if (completed) Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Hide update" else "Show update",
                    onClick = {
                        if (disclosure != null) disclosure.setExpanded(identity, !userExpanded)
                        else localExpanded = !userExpanded
                    },
                ) else Modifier
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                        .then(toggle)
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (completed) {
                                if (expanded) "Hide update" else "Show update"
                            } else "Update in progress"
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Update", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, color = DroideColors.Text)
                        if (!expanded) {
                            val summary = remember(text) { agentPublicUpdatePreview(text) }
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = DroideColors.Text,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } else if (!completed) {
                            Text("In progress", style = MaterialTheme.typography.labelSmall, color = DroideColors.Text)
                        }
                    }
                    if (completed) Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(20.dp), tint = DroideColors.Text)
                }
            } else {
                Text(if (answer) "Answer" else if (presentation == AgentTextPresentation.LEGACY) "DROIDE" else "Response",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (answer) FontWeight.Bold else FontWeight.Medium,
                    color = DroideColors.Text)
            }
            if (expanded) {
                // A collapsed section never instantiates a Markdown parser/projector or code tree.
                if (streamId == null) {
                    AgentMarkdownContent(text, Modifier.fillMaxWidth(), bodyStyle = bodyStyle, showActions = false,
                        sourceShortened = sourceShortened, maxRenderNodes = maxRenderNodes)
                } else {
                    AgentStreamingMarkdownContent(text, streamId, completed, Modifier.fillMaxWidth(),
                        bodyStyle = bodyStyle, showActions = false, sourceShortened = sourceShortened, maxRenderNodes = maxRenderNodes)
                }
            }
        }
    }
    body()
}

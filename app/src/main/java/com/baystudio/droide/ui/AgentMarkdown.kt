package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MAX_STREAMING_TAIL_MARKDOWN_CHARS = 12_000
internal const val MAX_AGENT_PREVIEW_LAYOUT_PX = 8_000
internal const val MAX_AGENT_CODE_PREVIEW_HEIGHT_PX = 4_000
private val LocalAgentOpenLink = staticCompositionLocalOf<((String) -> Unit)?> { null }
private val LocalAgentPreviewLimited = staticCompositionLocalOf<() -> Unit> { {} }

private val LocalAgentMarkdownBodyStyle = staticCompositionLocalOf<TextStyle?> { null }

// Native, bounded Markdown renderer for durable assistant transcript messages.
@Composable
internal fun AgentMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    bodyStyle: TextStyle? = null,
    showActions: Boolean = true,
    sourceShortened: Boolean = false,
    maxRenderNodes: Int = AgentMarkdownParser.MAX_RENDER_NODES,
) {
    val preview = remember(markdown) { agentMarkdownPreview(markdown) }
    val blocks = remember(preview.text, maxRenderNodes) { AgentMarkdownParser.parse(preview.text, maxRenderNodes) }
    var bodyLimited by remember { mutableStateOf(false) }
    var textLimited by remember(preview.text) { mutableStateOf(false) }
    val context = LocalContext.current
    var linkError by remember { mutableStateOf<String?>(null) }
    val openLink = remember(context) { { url: String -> linkError = openAgentChatLink(context, url) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        CompositionLocalProvider(
            LocalAgentOpenLink provides openLink,
            LocalAgentPreviewLimited provides { textLimited = true },
            LocalAgentMarkdownBodyStyle provides bodyStyle,
        ) {
            SelectionContainer {
                AgentMarkdownBodyBounds(onClipped = { bodyLimited = it }) {
                    AgentMarkdownBlocks(blocks, keyPrefix = "final", shortenedResponse = preview.shortened || sourceShortened)
                }
            }
        }
        if (preview.shortened || bodyLimited || textLimited) AgentMarkdownPreviewNotice(bodyStyle?.color ?: DroideColors.Muted)
        if (showActions && markdown.isNotBlank()) AgentMessageActions(markdown, linkError)
        else linkError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = markdown) }
    }
}

@Composable
internal fun AgentStreamingMarkdownContent(
    markdown: String,
    streamId: String,
    completed: Boolean,
    modifier: Modifier = Modifier,
    bodyStyle: TextStyle? = null,
    showActions: Boolean = true,
    sourceShortened: Boolean = false,
    maxRenderNodes: Int = AgentMarkdownParser.MAX_RENDER_NODES,
) {
    val projector = remember(streamId, maxRenderNodes) { AgentStreamingMarkdownProjector(maxRenderNodes) }
    val preview = remember(markdown) { agentMarkdownPreview(markdown) }
    var bodyLimited by remember { mutableStateOf(false) }
    var textLimited by remember(streamId) { mutableStateOf(false) }
    val projection = remember(preview.text, completed, projector) { projector.project(preview.text, completed) }
    val remainingNodes = maxRenderNodes - projection.stableBlocks.sumOf { it.renderCost() }
    val liveTailBlocks = remember(projection.tail, remainingNodes) {
        if (remainingNodes > 0 && projection.tail.length <= MAX_STREAMING_TAIL_MARKDOWN_CHARS)
            AgentMarkdownParser.parse(projection.tail, remainingNodes) else emptyList()
    }
    val context = LocalContext.current
    var linkError by remember(streamId) { mutableStateOf<String?>(null) }
    val openLink = remember(context, streamId) { { url: String -> linkError = openAgentChatLink(context, url) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        CompositionLocalProvider(
            LocalAgentOpenLink provides openLink,
            LocalAgentPreviewLimited provides { textLimited = true },
            LocalAgentMarkdownBodyStyle provides bodyStyle,
        ) {
            SelectionContainer {
                AgentMarkdownBodyBounds(onClipped = { bodyLimited = it }) {
                    AgentMarkdownBlocks(projection.stableBlocks, keyPrefix = "stable", shortenedResponse = preview.shortened || sourceShortened)
                    if (projection.tail.isNotBlank()) {

                        DisableSelection {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                if (liveTailBlocks.isNotEmpty()) {
                                    AgentMarkdownBlocks(liveTailBlocks, keyPrefix = "tail", shortenedResponse = preview.shortened || sourceShortened)
                                } else {
                                    Text(projection.tail, style = bodyStyle ?: MaterialTheme.typography.bodySmall, color = bodyStyle?.color ?: Color(0xFFD6DCE2),
                                        maxLines = MAX_AGENT_MARKDOWN_PREVIEW_LINES, overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { if (it.hasVisualOverflow) textLimited = true })
                                }
                            }
                        }
                    }
                    if (!completed) DisableSelection {
                        Text("▍", style = MaterialTheme.typography.bodySmall, color = DroideColors.Primary)
                    }
                }
            }
        }
        if (preview.shortened || bodyLimited || textLimited) AgentMarkdownPreviewNotice(bodyStyle?.color ?: DroideColors.Muted)
        if (showActions && markdown.isNotBlank()) AgentMessageActions(markdown, linkError)
        else linkError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = markdown) }
    }
}

@Composable
internal fun AgentMarkdownBodyBounds(onClipped: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = with(LocalDensity.current) { MAX_AGENT_PREVIEW_LAYOUT_PX.toDp() }
    Column(Modifier.heightIn(max = maxHeight).clipToBounds()
        .semantics { contentDescription = "Agent response preview" }
        .onSizeChanged { onClipped(it.height >= MAX_AGENT_PREVIEW_LAYOUT_PX) },
        verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
}

@Composable
internal fun AgentMarkdownPreviewNotice(color: Color = DroideColors.Muted) {
    DismissibleNotice("Response preview shortened. Select response text to inspect the available text; Copy response is available within clipboard limits.", color = color)
}

@Composable
private fun ColumnScope.AgentMarkdownBlocks(
    blocks: List<AgentMarkdownBlock>,
    keyPrefix: String,
    shortenedResponse: Boolean = false,
) {
    blocks.forEachIndexed { index, block ->
        key(keyPrefix, index, block::class) {
            when (block) {
                is AgentMarkdownBlock.Paragraph -> AgentMarkdownInlineText(block.text)
                is AgentMarkdownBlock.Heading -> AgentMarkdownHeading(block)
                is AgentMarkdownBlock.Quote -> AgentMarkdownQuote(block.text)
                is AgentMarkdownBlock.ListBlock -> AgentMarkdownList(block.items)
                is AgentMarkdownBlock.Table -> AgentMarkdownTable(block)
                is AgentMarkdownBlock.CodeBlock -> AgentMarkdownCodeBlock(block, shortenedResponse = shortenedResponse)
                AgentMarkdownBlock.Rule -> HorizontalDivider(color = DroideColors.Border.copy(alpha = .75f))
            }
        }
    }
}

@Composable
private fun AgentMarkdownHeading(block: AgentMarkdownBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleMedium
        2 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.bodyMedium
    }
    AgentMarkdownInlineText(
        text = block.text,
        style = style.copy(fontWeight = FontWeight.SemiBold, color = DroideColors.Text),
    )
}

@Composable
private fun AgentMarkdownQuote(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(2.dp).heightIn(min = 22.dp).background(DroideColors.BorderStrong))
        Spacer(Modifier.width(8.dp))
        AgentMarkdownInlineText(
            text = text,
            modifier = Modifier.weight(1f),
            style = LocalAgentMarkdownBodyStyle.current ?: MaterialTheme.typography.bodySmall.copy(color = DroideColors.Muted),
        )
    }
}

@Composable
private fun AgentMarkdownList(items: List<AgentMarkdownListItem>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                val marker = when (item.task) {
                    true -> "☑"
                    false -> "☐"
                    null -> if (item.ordered) "${item.ordinal ?: 1}." else "•"
                }
                Text(
                    marker,
                    modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAgentMarkdownBodyStyle.current?.color ?: if (item.task == true) DroideColors.Success else DroideColors.Muted,
                )
                AgentMarkdownInlineText(item.text, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AgentMarkdownTable(block: AgentMarkdownBlock.Table) {
    val scroll = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DroideColors.Surface.copy(alpha = .46f),
        border = BorderStroke(1.dp, DroideColors.Border.copy(alpha = .8f)),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 5.dp)) {
            AgentMarkdownTableRow(block.headers, block.aligns, header = true)
            HorizontalDivider(color = DroideColors.Border.copy(alpha = .75f))
            block.rows.forEachIndexed { index, row ->
                AgentMarkdownTableRow(row, block.aligns, header = false)
                if (index != block.rows.lastIndex) HorizontalDivider(color = DroideColors.Border.copy(alpha = .35f))
            }
        }
    }
}

@Composable
private fun AgentMarkdownTableRow(cells: List<String>, aligns: List<AgentMarkdownTableAlign>, header: Boolean) {
    Row(Modifier.width(IntrinsicSize.Max)) {
        cells.forEachIndexed { index, cell ->
            val align = aligns.getOrNull(index) ?: AgentMarkdownTableAlign.START
            val textAlign = when (align) {
                AgentMarkdownTableAlign.START -> TextAlign.Start
                AgentMarkdownTableAlign.CENTER -> TextAlign.Center
                AgentMarkdownTableAlign.END -> TextAlign.End
            }
            Box(
                Modifier.width(148.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = when (align) {
                    AgentMarkdownTableAlign.START -> Alignment.CenterStart
                    AgentMarkdownTableAlign.CENTER -> Alignment.Center
                    AgentMarkdownTableAlign.END -> Alignment.CenterEnd
                },
            ) {
                AgentMarkdownInlineText(
                    text = cell,
                    modifier = Modifier.fillMaxWidth(),
                    style = (LocalAgentMarkdownBodyStyle.current ?: MaterialTheme.typography.bodySmall).copy(
                        color = if (header) DroideColors.Text else LocalAgentMarkdownBodyStyle.current?.color ?: Color(0xFFD6DCE2),
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = textAlign,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AgentMarkdownInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalAgentMarkdownBodyStyle.current ?: MaterialTheme.typography.bodySmall.copy(color = Color(0xFFD6DCE2)),
) {
    val openLink = LocalAgentOpenLink.current
    val previewLimited = LocalAgentPreviewLimited.current
    val gate = remember { AgentLinkClickGate() }
    val gatedLink = remember(openLink, gate) { { url: String -> if (gate.allowClick) openLink?.invoke(url) } }
    val readableColor = LocalAgentMarkdownBodyStyle.current?.color
    val annotated = remember(text, gatedLink, readableColor) { markdownAnnotatedString(text, gatedLink, readableColor) }
    val hasLinks = remember(annotated) { annotated.getLinkAnnotations(0, annotated.length).isNotEmpty() }

    val interaction = if (!hasLinks) Modifier else Modifier.pointerInput(gate) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate.allowClick = false
            try {
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.size > 1 || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!change.pressed) {
                        gate.allowClick = !moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                        awaitPointerEvent(PointerEventPass.Final)
                        break
                    }
                }
            } finally {
                gate.allowClick = true
            }
        }
    }
    Text(text = annotated, modifier = modifier.then(interaction), style = style,
        maxLines = MAX_AGENT_MARKDOWN_PREVIEW_LINES, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow) previewLimited() })
}

private class AgentLinkClickGate { var allowClick = true }

internal fun markdownAnnotatedString(text: String, onOpenLink: ((String) -> Unit)? = null, readableColor: Color? = null): AnnotatedString = buildAnnotatedString {
    fun render(source: String, depth: Int, allowLinks: Boolean) {
        if (depth >= 8) { append(source); return }
        AgentMarkdownParser.parseInline(source).forEach { token ->
            val start = length
            when (token.kind) {
                AgentMarkdownInlineKind.STRONG, AgentMarkdownInlineKind.EMPHASIS, AgentMarkdownInlineKind.STRIKE ->
                    render(token.text, depth + 1, allowLinks)
                AgentMarkdownInlineKind.LINK -> if (token.text == token.url) append(token.text)
                    else render(token.text, depth + 1, allowLinks = false)
                else -> append(token.text)
            }
            val end = length
            if (end <= start) return@forEach
            when (token.kind) {
                AgentMarkdownInlineKind.TEXT -> Unit
                AgentMarkdownInlineKind.STRONG -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                AgentMarkdownInlineKind.EMPHASIS -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                AgentMarkdownInlineKind.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                AgentMarkdownInlineKind.CODE -> addStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, color = readableColor ?: Color(0xFFE6EDF3), background = DroideColors.Surface3), start, end,
                )
                AgentMarkdownInlineKind.LINK -> if (allowLinks && AgentMarkdownParser.isSafeHttpUrl(token.url)) {
                    addLink(
                        LinkAnnotation.Url(token.url!!,
                            TextLinkStyles(style = SpanStyle(color = readableColor ?: DroideColors.Primary, textDecoration = TextDecoration.Underline)),
                            onOpenLink?.let { callback -> LinkInteractionListener { link ->
                                (link as? LinkAnnotation.Url)?.let { callback(it.url) }
                            } },
                        ), start, end,
                    )
                }
            }
        }
    }
    render(text, depth = 0, allowLinks = true)
}

@Composable
private fun AgentMarkdownCodeBlock(block: AgentMarkdownBlock.CodeBlock, shortenedResponse: Boolean = false) {
    val context = LocalContext.current
    val previewLimited = LocalAgentPreviewLimited.current
    val maxLayout = with(LocalDensity.current) { MAX_AGENT_PREVIEW_LAYOUT_PX.toDp() }
    val maxCodeHeight = with(LocalDensity.current) { MAX_AGENT_CODE_PREVIEW_HEIGHT_PX.toDp() }
    val preview = remember(block.code) { compactCodePreview(block.code) }
    val oversized = preview.wasCompacted
    var expanded by remember(block.code) { mutableStateOf(false) }
    var copyError by remember(block.code) { mutableStateOf<String?>(null) }
    val shownCode = if (oversized && !expanded) preview.text else block.code
    val highlighted = rememberHighlightedCode(shownCode, block.language, DroideColors.Surface2)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DroideColors.Surface2.copy(alpha = .92f),
        border = BorderStroke(1.dp, DroideColors.Border),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 9.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DisableSelection { Text(
                    block.language.ifBlank { "code" }.lowercase(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = DroideColors.Muted,
                    fontFamily = FontFamily.Monospace,
                ) }
                DisableSelection {
                    IconButton(onClick = { copyError = copyAgentText(context, block.code, if (shortenedResponse) "Droide code preview" else "Droide code") }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ContentCopy, if (shortenedResponse) "Copy code preview" else "Copy code", Modifier.size(19.dp), tint = DroideColors.Muted)
                    }
                }
            }
            copyError?.let { DisableSelection {
                Text(it, Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            } }
            HorizontalDivider(color = DroideColors.Border.copy(alpha = .65f))
            Text(
                    text = highlighted,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .widthIn(max = maxLayout).heightIn(max = maxCodeHeight).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = DroideColors.Text,
                    fontFamily = FontFamily.Monospace,
                    maxLines = MAX_AGENT_MARKDOWN_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow) previewLimited() },
                )
            if (oversized) {
                HorizontalDivider(color = DroideColors.Border.copy(alpha = .45f))
                DisableSelection { TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp),
                ) {
                    Text(if (expanded) "Collapse code" else "Show code preview", style = MaterialTheme.typography.labelSmall)
                } }
            }
        }
    }
}

private data class CodePreview(val text: String, val wasCompacted: Boolean)

private fun compactCodePreview(code: String): CodePreview {
    if (code.length <= 16_000 && code.count { it == '\n' } <= 160) return CodePreview(code, false)
    var lines = 0
    var cut = 0
    while (cut < code.length && lines < 80 && cut < 12_000) {
        if (code[cut] == '\n') lines++
        cut++
    }
    if (cut > 0 && cut < code.length && code[cut - 1].isHighSurrogate() && code[cut].isLowSurrogate()) cut--
    val preview = code.substring(0, cut).trimEnd() + "\n\n… ${code.length - cut} more characters"
    return CodePreview(preview, true)
}

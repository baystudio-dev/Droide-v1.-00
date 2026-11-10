package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.baystudio.droide.core.DroideEditorPalette
import com.baystudio.droide.core.DroideHighlightKind
import com.baystudio.droide.core.DroideSyntaxSemanticEngine
import com.baystudio.droide.core.EditorCodePalette
import com.baystudio.droide.core.LanguageRegistry
import com.baystudio.droide.core.ThemeContrastPolicy
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Presentation budgets only. Text, selection, clipboard and tool arguments remain unchanged. */

internal const val MAX_CODE_PREVIEW_HIGHLIGHT_CHARS = 96_000
private const val MAX_BATCH_HIGHLIGHT_CHARS = 2 * MAX_CODE_PREVIEW_HIGHLIGHT_CHARS

@OptIn(ExperimentalCoroutinesApi::class)
private val codeHighlightDispatcher = Dispatchers.Default.limitedParallelism(2)

internal fun codeLanguage(path: String): String = LanguageRegistry.forFile(path)?.id
    ?: if (path.endsWith(".gradle", ignoreCase = true)) "groovy" else "plaintext"

internal fun codeFenceLanguage(hint: String): String {
    val alias = hint.trim().lowercase(Locale.ROOT).removePrefix(".")
    return when (alias) {
        "", "text", "txt", "plain", "plaintext", "none" -> "plaintext"
        "c#" -> "csharp"
        "c++" -> "cpp"
        "objective-c", "objective-c++" -> "objectivec"
        "golang" -> "go"
        "shellscript", "console", "sh", "bash", "zsh" -> "shell"
        "javascriptreact", "typescriptreact" -> "react"
        "gradle", "groovy" -> "groovy"
        "sass" -> "scss"
        else -> LanguageRegistry.all.firstOrNull {
            it.id.equals(alias, ignoreCase = true) || it.name.equals(alias, ignoreCase = true)
        }?.id ?: LanguageRegistry.forExtension(alias)?.id ?: "plaintext"
    }
}

internal data class CodePreviewDocument(val text: String, val language: String)

internal data class CodePreviewSpan(val start: Int, val end: Int, val kind: DroideHighlightKind,
                                    val bold: Boolean, val italic: Boolean, val strike: Boolean)

private data class TokenizedPreview(val document: CodePreviewDocument, val budget: Int, val spans: List<CodePreviewSpan>)

/** Uses the editor's registered grammars and lexical fallback, without opening an editor or LSP. */
internal fun tokenizeCodePreview(code: String, language: String, budget: Int = MAX_CODE_PREVIEW_HIGHLIGHT_CHARS): List<CodePreviewSpan> {
    if (code.isEmpty() || language == "plaintext" || budget <= 0) return emptyList()
    var end = minOf(code.length, budget, MAX_CODE_PREVIEW_HIGHLIGHT_CHARS)
    if (end < code.length && end > 0 && code[end - 1].isHighSurrogate() && code[end].isLowSurrogate()) end--
    val prefix = code.substring(0, end)
    return try {
        val result = DroideSyntaxSemanticEngine.highlight(prefix, language)
        val starts = buildList { add(0); prefix.forEachIndexed { i, ch -> if (ch == '\n') add(i + 1) } }
        result.spans.mapNotNull { span ->
            val start = starts.getOrNull(span.line) ?: return@mapNotNull null
            val lineEnd = starts.getOrNull(span.line + 1)?.minus(1) ?: prefix.length
            if (span.start < 0 || span.end <= span.start || span.end > lineEnd - start) return@mapNotNull null
            CodePreviewSpan(start + span.start, start + span.end, span.kind, span.bold, span.italic, span.strike)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A bad optional grammar must not prevent the user from reviewing the original code.
        emptyList()
    }
}

/** One bounded job per visible preview, cancelled on replacement/disposal; no transcript-wide cache. */
@Composable
internal fun rememberCodePreviewSpans(documents: List<CodePreviewDocument>): List<List<CodePreviewSpan>> {
    if (documents.all { it.text.isEmpty() || it.language == "plaintext" }) {
        return remember(documents) { List(documents.size) { emptyList() } }
    }
    var completed by remember { mutableStateOf(emptyList<TokenizedPreview>()) }
    LaunchedEffect(documents) {
        val previous = completed
        completed = withContext(codeHighlightDispatcher) {
            var remaining = MAX_BATCH_HIGHLIGHT_CHARS
            documents.mapIndexed { index, document ->
                ensureActive()
                val budget = minOf(document.text.length, MAX_CODE_PREVIEW_HIGHLIGHT_CHARS, remaining)
                remaining -= budget
                val old = previous.getOrNull(index)
                val spans = if (old?.document == document && old.budget == budget) old.spans
                    else tokenizeCodePreview(document.text, document.language, budget)
                ensureActive()
                TokenizedPreview(document, budget, spans)
            }
        }
    }
    return remember(documents, completed) {
        documents.mapIndexed { index, document ->
            val old = completed.getOrNull(index)
            // While append-only streaming catches up, retain colors for the verified prefix. A replacement
            // or language change must never borrow offsets from different text.
            if (old != null && old.document.language == document.language && document.text.startsWith(old.document.text))
                old.spans else emptyList()
        }
    }
}

@Composable
internal fun rememberCodePreviewPalette(background: Color): DroideEditorPalette {
    val foreground = DroideColors.Text
    val primary = DroideColors.Primary
    return remember(background, foreground, primary) { codePreviewPalette(background, foreground, primary) }
}

private fun codePreviewPalette(background: Color, foreground: Color, primary: Color): DroideEditorPalette =
    EditorCodePalette.create(background.toArgb(), foreground.toArgb(), primary.toArgb())

internal fun colorizeCodePreview(code: String, spans: List<CodePreviewSpan>, palette: DroideEditorPalette): AnnotatedString =
    if (spans.isEmpty()) AnnotatedString(code) else buildAnnotatedString {
        append(code)
        spans.forEach { span ->
            val color = when (span.kind) {
                DroideHighlightKind.NORMAL, DroideHighlightKind.VARIABLE -> palette.foreground
                DroideHighlightKind.KEYWORD, DroideHighlightKind.TAG -> palette.keyword
                DroideHighlightKind.COMMENT -> palette.comment
                DroideHighlightKind.STRING, DroideHighlightKind.NUMBER, DroideHighlightKind.REGEXP -> palette.literal
                DroideHighlightKind.OPERATOR -> palette.operator
                DroideHighlightKind.FUNCTION, DroideHighlightKind.ANNOTATION -> palette.function
                DroideHighlightKind.TYPE -> palette.type
                DroideHighlightKind.ATTRIBUTE, DroideHighlightKind.PROPERTY -> palette.literal
            }
            if (span.start >= 0 && span.end <= code.length && span.start < span.end) {
                addStyle(SpanStyle(color = Color(color),
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null), span.start, span.end)
            }
        }
    }

@Composable
internal fun rememberHighlightedCode(code: String, language: String, background: Color = DroideColors.Background): AnnotatedString {
    val documents = remember(code, language) { listOf(CodePreviewDocument(code, codeFenceLanguage(language))) }
    val spans = rememberCodePreviewSpans(documents).single()
    val palette = rememberCodePreviewPalette(background)
    return remember(code, spans, palette) { colorizeCodePreview(code, spans, palette) }
}

/** Synchronous adapter for non-composable callers; UI consumers use the cancellable worker above. */
internal fun highlightedCode(code: String, language: String): AnnotatedString = colorizeCodePreview(
    code, tokenizeCodePreview(code, codeFenceLanguage(language)),
    codePreviewPalette(DroideColors.Background, DroideColors.Text, DroideColors.Primary),
)

/** Keep syntax legible on both added/removed line backgrounds without coloring the diff marker. */
internal fun readableCodeOn(code: AnnotatedString, background: Color): AnnotatedString {
    val colors = mutableMapOf<Color, Color>()
    val bg = background.toArgb()
    fun readable(color: Color): Color = colors.getOrPut(color) {
        val requested = color.toArgb()
        if (ThemeContrastPolicy.contrast(bg, requested) >= 4.5) color else {
            // Move only as far as needed toward a readable foreground; preserve the token hue.
            val target = ThemeContrastPolicy.bestMonochromeForeground(bg)
            var low = 0.0
            var high = 1.0
            repeat(8) {
                val mid = (low + high) / 2
                if (ThemeContrastPolicy.contrast(bg, ThemeContrastPolicy.mix(requested, target, mid)) >= 4.5) high = mid
                else low = mid
            }
            Color(ThemeContrastPolicy.mix(requested, target, high))
        }
    }
    return AnnotatedString(code.text, code.spanStyles.map { span -> span.copy(item = span.item.copy(color = readable(span.item.color))) })
}

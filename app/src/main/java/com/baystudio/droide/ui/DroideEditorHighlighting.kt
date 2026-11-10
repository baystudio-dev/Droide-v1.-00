package com.baystudio.droide.ui

import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.DroideHighlightKind
import com.baystudio.droide.core.DroideHighlightResult
import com.baystudio.droide.core.DroideHighlightSpan
import com.baystudio.droide.core.DroideSyntaxSemanticEngine
import com.baystudio.droide.core.IndentGuidePlanner
import com.baystudio.droide.core.LspHeldResultGuard
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.runSuspendCatching
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.*

internal class DroideEditorHighlightController(
    private val editor: DroideCodeEditor,
    private val path: String,
    private val languageId: String,
    private val lsp: LspManager,
    private val scope: CoroutineScope,
    codeStyle: CodeStyleProfile,
) {
    private var codeStyle = codeStyle
    private var generation = 0L
    private var syntaxJob: Job? = null
    private var semanticJob: Job? = null
    private var released = false
    private var heldGuards: List<LspHeldResultGuard> = emptyList()

    fun schedule(text: String, immediate: Boolean = false) {
        if (released) return
        val requestGeneration = ++generation
        syntaxJob?.cancel()
        semanticJob?.cancel()

        syntaxJob = scope.launchUiCatching(onError = { editor.reportSurfaceFailure(it) }) {
            if (!immediate) delay(55)
            val local = withContext(Dispatchers.Default) {
                DroideSyntaxSemanticEngine.highlight(text, languageId)
            }
            if (requestGeneration != generation || released) return@launchUiCatching
            apply(local, text, requestGeneration)
        }

        if (text.length <= DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS && languageId != "plaintext") {
            semanticJob = scope.launchUiCatching(onError = { editor.reportSurfaceFailure(it) }) {
                delay(if (immediate) 120 else 220)
                val semantic = withContext(Dispatchers.IO) {
                    runSuspendCatching { lsp.semanticTokens(path, text) }.getOrDefault(emptyList())
                }
                val guards = semantic.mapNotNull { it.activationGuard }
                if (requestGeneration != generation || released || semantic.isEmpty() || guards.any { !it.accepts() }) return@launchUiCatching
                val enriched = withContext(Dispatchers.Default) {
                    DroideSyntaxSemanticEngine.highlight(text, languageId, semantic)
                }
                if (requestGeneration != generation || released || guards.any { !it.accepts() }) return@launchUiCatching
                apply(enriched, text, requestGeneration, guards)
            }
        }
    }

    /** Clear an expired original native capture without issuing another native request. */
    fun invalidateHeldResult() {
        if (released || heldGuards.isEmpty() || heldGuards.all { it.accepts() }) return
        heldGuards = emptyList()
        val requestGeneration = ++generation
        syntaxJob?.cancel()
        semanticJob?.cancel()
        val text = editor.text.toString()
        installSafeBaseStyles(text)
        syntaxJob = scope.launchUiCatching(onError = editor::reportSurfaceFailure) {
            val local = withContext(Dispatchers.Default) { DroideSyntaxSemanticEngine.highlight(text, languageId) }
            apply(local, text, requestGeneration)
        }
    }

    fun updateCodeStyle(profile: CodeStyleProfile, text: String) {
        if (released) return
        val geometryChanged = profile.tabWidth != codeStyle.tabWidth ||
            profile.indentSize != codeStyle.indentSize || profile.useTabs != codeStyle.useTabs
        codeStyle = profile
        if (heldGuards.any { !it.accepts() }) {
            invalidateHeldResult()
            return
        }
        if (!geometryChanged) return

        val styles = editor.styles ?: return
        styles.blocks?.clear()
        installIndentGuides(styles, text.split('\n'))
        styles.finishBuilding()
        editor.setStyles(styles)
    }

    fun release() {
        released = true
        generation++
        syntaxJob?.cancel()
        semanticJob?.cancel()
    }

    fun installSafeBaseStyles(text: String) {
        if (released) return
        editor.setStyles(buildNormalStyles(text))
    }

    private fun apply(result: DroideHighlightResult, text: String, requestGeneration: Long,
        guards: List<LspHeldResultGuard> = emptyList()) {
        if (requestGeneration != generation || released || guards.any { !it.accepts() }) return
        val styles = buildStyles(text, result)
        editor.postInLifecycle {
            if (requestGeneration == generation && !released && guards.all { it.accepts() }) {
                runUiCatching(onError = editor::reportSurfaceFailure) {
                    heldGuards = guards
                    editor.setStyles(styles)
                }
            }
        }
    }

    private fun buildStyles(text: String, result: DroideHighlightResult): Styles {
        val lines = text.split('\n')
        val grouped = result.spans.groupBy { it.line }
        val builder = MappedSpans.Builder(lines.size.coerceAtLeast(1))
        for ((lineIndex, line) in lines.withIndex()) {
            val lineSpans = grouped[lineIndex].orEmpty()
            if (line.isEmpty() || lineSpans.isEmpty()) {
                builder.add(lineIndex, Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
                continue
            }
            val styles = LongArray(line.length) { TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL) }

            lineSpans.asSequence().filter { !it.semantic }.forEach { paint(styles, it) }

            lineSpans.asSequence().filter { it.semantic }.forEach { paint(styles, it) }
            var last: Long? = null
            for (column in styles.indices) {
                val style = styles[column]
                if (last == null || last != style) {
                    builder.add(lineIndex, Span.obtain(column, style))
                    last = style
                }
            }
        }
        builder.determine((lines.size - 1).coerceAtLeast(0))
        val styles = Styles(builder.build(), true)
        installIndentGuides(styles, lines)
        styles.finishBuilding()
        return styles
    }

    private fun buildNormalStyles(text: String): Styles {
        val lineCount = text.count { it == '\n' } + 1
        val builder = MappedSpans.Builder(lineCount.coerceAtLeast(1))
        val normal = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        repeat(lineCount.coerceAtLeast(1)) { lineIndex ->
            builder.add(lineIndex, Span.obtain(0, normal))
        }
        builder.determine((lineCount - 1).coerceAtLeast(0))
        val styles = Styles(builder.build(), true)
        installIndentGuides(styles, text.split('\n'))
        styles.finishBuilding()
        return styles
    }

    private fun installIndentGuides(styles: Styles, lines: List<String>) {
        val guides = IndentGuidePlanner.plan(lines, codeStyle)
        if (guides.isEmpty()) return
        guides.forEach { guide ->
            styles.addCodeBlock(CodeBlock().apply {
                startLine = guide.startLine
                startColumn = guide.column
                endLine = guide.endLine
                endColumn = guide.column
                toBottomOfEndLine = guide.toBottomOfEndLine
            })
        }

        styles.setIndentCountMode(true)
    }

    private fun paint(target: LongArray, span: DroideHighlightSpan) {
        val from = span.start.coerceIn(0, target.size)
        val to = span.end.coerceIn(from, target.size)
        if (to <= from) return
        val color = when (span.kind) {
            DroideHighlightKind.NORMAL -> EditorColorScheme.TEXT_NORMAL
            DroideHighlightKind.KEYWORD -> EditorColorScheme.KEYWORD
            DroideHighlightKind.COMMENT -> EditorColorScheme.COMMENT
            DroideHighlightKind.STRING, DroideHighlightKind.NUMBER, DroideHighlightKind.REGEXP -> EditorColorScheme.LITERAL
            DroideHighlightKind.OPERATOR -> EditorColorScheme.OPERATOR
            DroideHighlightKind.FUNCTION -> EditorColorScheme.FUNCTION_NAME
            DroideHighlightKind.TYPE -> EditorColorScheme.IDENTIFIER_NAME
            DroideHighlightKind.VARIABLE -> EditorColorScheme.IDENTIFIER_VAR
            DroideHighlightKind.ANNOTATION -> EditorColorScheme.ANNOTATION
            DroideHighlightKind.TAG -> EditorColorScheme.HTML_TAG
            DroideHighlightKind.ATTRIBUTE -> EditorColorScheme.ATTRIBUTE_NAME
            DroideHighlightKind.PROPERTY -> EditorColorScheme.ATTRIBUTE_VALUE
        }
        val style = TextStyle.makeStyle(
            color,
            0,
            span.bold,
            span.italic || span.kind == DroideHighlightKind.COMMENT,
            span.strike,
            span.kind == DroideHighlightKind.COMMENT,
        )
        for (i in from until to) target[i] = style
    }
}

package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.core.AgentFileChange
import com.baystudio.droide.core.DiffLine
import com.baystudio.droide.core.DiffLineKind
import com.baystudio.droide.core.UnifiedDiffParser

@Composable
fun DiffText(text: String, scrollable: Boolean = false, path: String = "",
             wrapFileNames: Boolean = false, leadingSummary: (@Composable () -> Unit)? = null) {
    DiffTextBody(text, scrollable, path, wrapFileNames, leadingSummary, null)
}

/** The activity card already displays this file's path, previous path and counts. */
@Composable
internal fun AgentFileDiffText(change: AgentFileChange) {
    DiffTextBody(change.diff, false, change.path, false, null,
        DiffFileIdentity(change.path, change.previousPath))
}

private data class DiffFileIdentity(val path: String, val previousPath: String?)

@Composable
private fun DiffTextBody(text: String, scrollable: Boolean, path: String,
                         wrapFileNames: Boolean, leadingSummary: (@Composable () -> Unit)?,
                         enclosingFile: DiffFileIdentity?) {
    val parsed = remember(text) { UnifiedDiffParser.parse(text) }
    if (parsed.isEmpty()) {
        Column {
            leadingSummary?.invoke()
            Text(text.ifBlank { "(no diff)" }, Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        return
    }
    val hasSummary = leadingSummary != null
    val preview = remember(parsed, path, hasSummary, enclosingFile) {
        val documents = mutableListOf<CodePreviewDocument>()
        val rows = buildList {
            val firstHunkFile = parsed.indexOfFirst { it.hunks.isNotEmpty() }
            // Prose/no-op previews have no hunk strip; keep their summary visible above the excerpt.
            if (hasSummary && firstHunkFile < 0) add(DiffRow.Summary)
            parsed.forEachIndexed { index, file ->
                val fileLabel = if (file.oldPath != null && file.newPath != null && file.oldPath != file.newPath)
                    "${file.oldPath} → ${file.newPath}" else file.newPath ?: file.oldPath ?: "diff"
                // Only omit an identity already fully represented by the enclosing card.
                // Legacy multi-file or mismatched metadata must retain its own file labels.
                val headerAlreadyShown = parsed.size == 1 && enclosingFile != null &&
                    (file.newPath ?: file.oldPath) == enclosingFile.path &&
                    (file.oldPath == null || file.newPath == null || file.oldPath == file.newPath ||
                        file.oldPath == enclosingFile.previousPath)
                if (!headerAlreadyShown) add(DiffRow.FileHeader(fileLabel, file.newPath ?: file.oldPath))
                // Empty patch metadata (including the final newline) is not a code row.
                // Empty source lines remain in h.lines with their marker and line numbers.
                file.headers.filterNot { it.isEmpty() || it.startsWith("diff --git ") || it.startsWith("--- ") || it.startsWith("+++ ") }
                    .forEach { add(DiffRow.Meta(it)) }
                // One whole-proposal total, immediately above the first actual hunk, not per file.
                if (hasSummary && index == firstHunkFile) add(DiffRow.Summary)
                file.hunks.forEach { h ->
                    add(DiffRow.Hunk(h.header))
                    // A diff is two documents. Never let a removed delimiter change the
                    // lexical state of added lines. Missing context between hunks is unknown.
                    val oldIndex = documents.size
                    documents += CodePreviewDocument(h.lines.filter { it.kind == DiffLineKind.REMOVE || it.kind == DiffLineKind.CONTEXT }
                        .joinToString("\n") { it.text }, codeLanguage(file.oldPath ?: file.newPath ?: path))
                    val newIndex = documents.size
                    documents += CodePreviewDocument(h.lines.filter { it.kind == DiffLineKind.ADD || it.kind == DiffLineKind.CONTEXT }
                        .joinToString("\n") { it.text }, codeLanguage(file.newPath ?: file.oldPath ?: path))
                    var oldOffset = 0
                    var newOffset = 0
                    h.lines.forEach { line ->
                        val document = when (line.kind) {
                            DiffLineKind.REMOVE -> oldIndex
                            DiffLineKind.ADD, DiffLineKind.CONTEXT -> newIndex
                            DiffLineKind.META -> -1
                        }
                        add(DiffRow.Line(line, document, if (line.kind == DiffLineKind.REMOVE) oldOffset else newOffset))
                        if (line.kind == DiffLineKind.REMOVE || line.kind == DiffLineKind.CONTEXT) oldOffset += line.text.length + 1
                        if (line.kind == DiffLineKind.ADD || line.kind == DiffLineKind.CONTEXT) newOffset += line.text.length + 1
                    }
                }
            }
        }
        DiffPreview(rows, documents)
    }
    val rows = preview.rows
    val spans = rememberCodePreviewSpans(preview.documents)
    val palette = rememberCodePreviewPalette(DroideColors.Background)
    val colored = remember(preview.documents, spans, palette) {
        preview.documents.mapIndexed { index, document -> colorizeCodePreview(document.text, spans[index], palette) }
    }
    val modifier = Modifier.fillMaxWidth()
    if (scrollable) {
        LazyColumn(modifier) { items(rows) { row -> DiffRowItem(row, colored, wrapFileNames, leadingSummary) } }
    } else {
        // Permission docks and tool cards own their vertical scrolling. A lazy
        // list here would be measured with infinite height by that parent.
        Column(modifier) {
            val visibleRows = rows.take(MAX_INLINE_DIFF_ROWS)
            // A long metadata prelude may hide the hunk under the existing display cap.
            // Keep authoritative totals once above the visible excerpt instead of losing them.
            if (hasSummary && visibleRows.none { it == DiffRow.Summary }) leadingSummary?.invoke()
            visibleRows.forEach { row -> DiffRowItem(row, colored, wrapFileNames, leadingSummary) }
            if (rows.size > MAX_INLINE_DIFF_ROWS) {
                Text("Diff preview shortened. Open the file to review the remaining changes.",
                    Modifier.widthIn(max = 2_480.dp).padding(8.dp), fontSize = 11.sp)
            }
        }
    }
}

private const val MAX_INLINE_DIFF_ROWS = 200

@Composable
private fun DiffRowItem(row: DiffRow, colored: List<AnnotatedString>, wrapFileNames: Boolean, leadingSummary: (@Composable () -> Unit)?) {
    val textModifier = Modifier.fillMaxWidth()
    when (row) {
        DiffRow.Summary -> leadingSummary?.invoke()
        is DiffRow.FileHeader -> {
            val modifier = textModifier.background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp)
            if (row.resourcePath != null) {
                FileIdentityLabel(row.resourcePath, row.label, modifier, iconSize = 14.dp,
                    monospaced = true, style = MaterialTheme.typography.labelMedium,
                    maxLines = if (wrapFileNames) Int.MAX_VALUE else 1, softWrap = wrapFileNames,
                    overflow = if (wrapFileNames) TextOverflow.Clip else TextOverflow.Ellipsis)
            } else {
                Text(row.label, modifier, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        is DiffRow.Hunk -> Text(
            row.header,
            textModifier.background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        is DiffRow.Meta -> Text(
            row.text.ifEmpty { " " }, textModifier.padding(horizontal = 8.dp, vertical = 1.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        is DiffRow.Line -> {
            val code = colored.getOrNull(row.document)?.subSequence(row.offset, row.offset + row.line.text.length)
                ?: AnnotatedString(row.line.text)
            DiffLineRow(row.line, code)
        }
    }
}

private sealed interface DiffRow {
    data object Summary : DiffRow
    data class FileHeader(val label: String, val resourcePath: String?) : DiffRow
    data class Hunk(val header: String) : DiffRow
    data class Meta(val text: String) : DiffRow
    data class Line(val line: DiffLine, val document: Int, val offset: Int) : DiffRow
}

private data class DiffPreview(val rows: List<DiffRow>, val documents: List<CodePreviewDocument>)

@Composable
private fun DiffLineRow(line: DiffLine, highlighted: AnnotatedString) {
    val bg = when (line.kind) {
        DiffLineKind.ADD -> DroideColors.Success.copy(alpha = .16f)
        DiffLineKind.REMOVE -> DroideColors.Error.copy(alpha = .16f)
        DiffLineKind.META -> MaterialTheme.colorScheme.surfaceVariant
        DiffLineKind.CONTEXT -> Color.Transparent
    }
    val prefix = when (line.kind) {
        DiffLineKind.ADD -> "+"
        DiffLineKind.REMOVE -> "-"
        DiffLineKind.CONTEXT -> " "
        DiffLineKind.META -> " "
    }
    val background = bg.compositeOver(DroideColors.Background)
    val code = remember(highlighted, prefix, background) {
        buildAnnotatedString { append(prefix); append(readableCodeOn(highlighted, background)) }
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(vertical = 2.dp)) {
        Text(
            line.oldLine?.toString()?.padStart(4) ?: "    ",
            Modifier.width(34.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        )
        Text(
            line.newLine?.toString()?.padStart(4) ?: "    ",
            Modifier.width(34.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        )
        Text(code, Modifier.weight(1f).padding(end = 5.dp),
            color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 32, softWrap = true, overflow = TextOverflow.Ellipsis)
    }
}

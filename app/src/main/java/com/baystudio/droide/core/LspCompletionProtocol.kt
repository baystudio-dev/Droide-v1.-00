package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** One-based UTF-16 caret for manager-authored captured completion edits. */
data class LspCompletionCaret(val line: Int, val column: Int)

/** One manager-authored immutable guard shared by a response. Never parsed from server JSON.
 * isCurrent must be a bounded pure admission check; no I/O or suspension at editor acceptance.
 */
class LspCompletionApplicationGuard(
    val capturedText: String,
    val requiredCaret: LspCompletionCaret? = null,
    val isCurrent: () -> Boolean,
) {
    constructor(capturedText: String, isCurrent: () -> Boolean) : this(capturedText, null, isCurrent)

    /** Exact, pure held-result check; a rejected guarded item never gets a generic fallback. */
    fun accepts(text: String, caret: LspCompletionCaret, hasSelection: Boolean): Boolean =
        text == capturedText && (requiredCaret?.let { !hasSelection && it == caret } ?: true) &&
            runCatching { isCurrent() }.getOrDefault(false)
}

/** Only an exact manager may opt in; this field is never parsed from server JSON. */
enum class LspNativeSnippetPolicy { GRAPHQL_NUMERIC_RAW, ELP_NUMERIC_RAW }

data class LspCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val documentation: String? = null,
    val kind: Int? = null,
    val filterText: String? = null,
    val sortText: String? = null,
    val preselect: Boolean = false,
    val editStartLine: Int? = null,
    val editStartColumn: Int? = null,
    val editEndLine: Int? = null,
    val editEndColumn: Int? = null,
    val additionalTextEdits: List<TextRangeEdit> = emptyList(),
    val isSnippet: Boolean = false,
    val applicationGuard: LspCompletionApplicationGuard? = null,
    val nativeOrigin: String? = null,
    val nativeBatch: Long? = null,
    val nativeItemIdentity: String? = null,
    val nativeSnippetPolicy: LspNativeSnippetPolicy? = null,
)

 
internal object LspCompletionProtocol {
    fun parse(
        result: JsonElement,
        line: Int? = null,
        column: Int? = null,
        text: String? = null,
        strictSnippets: Boolean = false,
        requirePrimaryEdit: Boolean = false,
        rejectCommand: Boolean = false,
        preserveNativeOrder: Boolean = false,
    ): List<LspCompletionItem> {
        val items = when (result) {
            is JsonArray -> result
            is JsonObject -> result["items"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val bounds = text?.let(::DocumentBounds)
        val parsed = items.take(240).mapNotNull { parseItem(it, line, column, bounds, strictSnippets, requirePrimaryEdit, rejectCommand) }
        return if (preserveNativeOrder) parsed else parsed.distinct()
    }

    private fun parseItem(
        el: JsonElement,
        line: Int?,
        column: Int?,
        bounds: DocumentBounds?,
        strictSnippets: Boolean,
        requirePrimaryEdit: Boolean,
        rejectCommand: Boolean,
    ): LspCompletionItem? {
        val o = el as? JsonObject ?: return null
        if (rejectCommand && o["command"]?.let { it != JsonNull } == true) return null
        val wireLabel = string(o["label"]) ?: return null
        val label = labelPreview(wireLabel)
        val textEdit = o["textEdit"] as? JsonObject
        if (requirePrimaryEdit && textEdit == null) return null
        if (o.containsKey("textEdit") && textEdit == null) return null
        // LSP specifies that textEdit.newText overrides insertText, including an
        // intentionally empty replacement. Never combine one field's text with
        // another field's range or fall back from a malformed explicit edit.
        val insertText = if (textEdit != null) string(textEdit["newText"]) ?: return null
            else string(o["insertText"]) ?: wireLabel
        if (insertText.length > ProfessionalSnippets.MAX_SNIPPET_CHARS) return null
        val range = textEdit?.let { primaryRange(it, line, column) ?: return null }
        val insertTextFormat = (o["insertTextFormat"] as? JsonPrimitive)?.intOrNull ?: 1
        val requestedSnippet = insertTextFormat == 2
        val sanitizedSnippet = if (requestedSnippet) {
            runCatching { ProfessionalSnippets.sanitizeForSora(insertText.take(ProfessionalSnippets.MAX_SNIPPET_CHARS)) }.getOrNull()
        } else null
        if (strictSnippets && requestedSnippet && sanitizedSnippet == null) return null
        val isSnippet = requestedSnippet && sanitizedSnippet != null
        val safeInsert = sanitizedSnippet
            ?: if (requestedSnippet) ProfessionalSnippets.plainTextFallback(insertText)
            else insertText.take(ProfessionalSnippets.MAX_SNIPPET_CHARS)
        val additionalArray = o["additionalTextEdits"] as? JsonArray
        if (o.containsKey("additionalTextEdits") && additionalArray == null) return null
        if ((additionalArray?.size ?: 0) > 32) return null
        val additional = additionalArray?.map { parseTextEdit(it) ?: return null }.orEmpty()
        if (additional.sumOf { it.newText.length.toLong() } > 200_000L) return null
        val primary = range?.copy(newText = safeInsert)
        val edits = additional + listOfNotNull(primary)
        if (edits.indices.any { a -> (a + 1 until edits.size).any { b -> overlaps(edits[a], edits[b]) } }) return null
        if (!validUtf16(safeInsert) || additional.any { !validUtf16(it.newText) }) return null
        if (bounds != null && edits.any { !bounds.accepts(it) }) return null
        return LspCompletionItem(
            label = label,
            insertText = safeInsert,
            detail = string(o["detail"])?.take(1_000),
            documentation = o["documentation"]?.let(::markupText)?.take(4_000),
            kind = (o["kind"] as? JsonPrimitive)?.intOrNull,
            filterText = string(o["filterText"])?.take(500),
            sortText = string(o["sortText"])?.take(500),
            preselect = (o["preselect"] as? JsonPrimitive)?.booleanOrNull == true,
            editStartLine = range?.startLine,
            editStartColumn = range?.startColumn,
            editEndLine = range?.endLine,
            editEndColumn = range?.endColumn,
            additionalTextEdits = additional,
            isSnippet = isSnippet,
        )
    }

    // Label is presentation; fallback insertion must retain the complete wire value.
    private fun labelPreview(label: String): String {
        if (label.length <= 500) return label
        val end = if (label[499].isHighSurrogate() && label[500].isLowSurrogate()) 499 else 500
        return label.substring(0, end)
    }

    private fun primaryRange(edit: JsonObject, line: Int?, column: Int?): TextRangeEdit? {
        val range = if (edit.containsKey("range")) {
            if (edit.containsKey("insert") || edit.containsKey("replace")) return null
            parseRange(edit["range"]) ?: return null
        } else {
            val insert = parseRange(edit["insert"]) ?: return null
            val replace = parseRange(edit["replace"]) ?: return null
            if (!completionRange(insert, line, column) || !completionRange(replace, line, column)) return null
            if (insert.startLine != replace.startLine || insert.startColumn != replace.startColumn ||
                insert.endLine != replace.endLine || insert.endColumn > replace.endColumn) return null
            // The existing editor accepts completion by replacement only. Normalize
            // this optional server form to that behavior; do not advertise a dual
            // insert/replace UI or silently choose the shorter insertion range.
            replace
        }
        return range.takeIf { completionRange(it, line, column) }
    }

    private fun completionRange(range: TextRangeEdit, line: Int?, column: Int?): Boolean =
        range.startLine == range.endLine && (line == null || column == null ||
            (line == range.startLine && column in range.startColumn..range.endColumn))

    private fun parseTextEdit(el: JsonElement): TextRangeEdit? {
        val edit = el as? JsonObject ?: return null
        val newText = string(edit["newText"]) ?: return null
        if (newText.length > 50_000) return null
        return parseRange(edit["range"])?.copy(newText = newText)
    }

    private fun parseRange(value: JsonElement?): TextRangeEdit? {
        val range = value as? JsonObject ?: return null
        val start = range["start"] as? JsonObject ?: return null
        val end = range["end"] as? JsonObject ?: return null
        val sl = coordinate(start["line"]) ?: return null
        val sc = coordinate(start["character"]) ?: return null
        val el = coordinate(end["line"]) ?: return null
        val ec = coordinate(end["character"]) ?: return null
        if (el < sl || (el == sl && ec < sc)) return null
        return TextRangeEdit(sl + 1, sc + 1, el + 1, ec + 1, "")
    }

    private fun coordinate(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0 until Int.MAX_VALUE }

    private fun string(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun overlaps(a: TextRangeEdit, b: TextRangeEdit): Boolean {
        fun compare(al: Int, ac: Int, bl: Int, bc: Int) = if (al == bl) ac.compareTo(bc) else al.compareTo(bl)
        // Identical insertion positions conflict too, even though both ranges are empty.
        if (a.startLine == b.startLine && a.startColumn == b.startColumn) return true
        return compare(a.startLine, a.startColumn, b.endLine, b.endColumn) < 0 &&
            compare(b.startLine, b.startColumn, a.endLine, a.endColumn) < 0
    }

    // Index the request snapshot once. Completion validation must not construct
    // and discard a whole edited document for every candidate in the response.
    private class DocumentBounds(private val text: String) {
        private val starts = IntArray(1 + text.indices.count { index ->
            text[index] == '\r' || (text[index] == '\n' && (index == 0 || text[index - 1] != '\r'))
        })
        init {
            var line = 1
            var index = 0
            while (index < text.length) {
                when (text[index++]) {
                    '\r' -> {
                        if (index < text.length && text[index] == '\n') index++
                        starts[line++] = index
                    }
                    '\n' -> starts[line++] = index
                }
            }
        }

        fun accepts(edit: TextRangeEdit): Boolean =
            accepts(edit.startLine, edit.startColumn) && accepts(edit.endLine, edit.endColumn)

        private fun accepts(line: Int, column: Int): Boolean {
            if (line < 1 || line > starts.size || column < 1) return false
            val start = starts[line - 1]
            var end = if (line < starts.size) starts[line] else text.length
            if (line < starts.size) {
                if (end > start && text[end - 1] == '\n') end--
                if (end > start && text[end - 1] == '\r') end--
            }
            if (column - 1 > end - start) return false
            val offset = start + column - 1
            return offset == 0 || offset == text.length ||
                !text[offset - 1].isHighSurrogate() || !text[offset].isLowSurrogate()
        }
    }

    private fun validUtf16(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                if (index >= text.length || !text[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }

    private fun markupText(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.joinToString("\n") { markupText(it) }
        is JsonObject -> string(el["value"]).orEmpty()
        else -> ""
    }
}

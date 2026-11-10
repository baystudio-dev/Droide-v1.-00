package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Native Python-word prefix only. Neither a CMake parser nor a semantic whole-token repair. */
internal object LspCMakeCompletionContext {
    const val UI_WATCHDOG_MS = 10_000L
    const val MAX_PARSED_ITEMS = 240
    const val MAX_SHOWN_ITEMS = 120
    const val MAX_INSERT_CHARS = 50_000
    private val ambiguous = setOf('-', '.', ':', '/', '\\')
    data class Context(
        val snapshot: LspCMakePositionCodec.Snapshot,
        val prefix: String,
        val triggerCharacter: String?,
        val requestCaret: LspCompletionCaret,
        val prefixRange: TextRangeEdit,
    )
    fun capture(text: String, line1: Int, col1: Int): Context? =
        LspCMakePositionCodec.capture(text)?.let { capture(it, line1, col1) }

    fun capture(snapshot: LspCMakePositionCodec.Snapshot, line1: Int, col1: Int): Context? {
        val text = snapshot.text
        val offset = snapshot.offset(line1, col1) ?: return null
        val start = snapshot.lineStart(line1) ?: return null
        val end = snapshot.lineEnd(line1) ?: return null
        // Range-free native suggestions cannot replace a suffix or qualified token safely.
        if (offset < end && (LspCMakeWordTable.contains(text.codePointAt(offset)) || text[offset] in ambiguous)) return null
        var prefixStart = offset
        while (prefixStart > start) {
            val point = text.codePointBefore(prefixStart)
            if (!LspCMakeWordTable.contains(point)) break
            prefixStart -= Character.charCount(point)
        }
        if (prefixStart > start && text[prefixStart - 1] in ambiguous) return null
        return Context(snapshot, text.substring(prefixStart, offset),
            text.getOrNull(offset - 1)?.takeIf { offset > start && (it == '{' || it == '(') }?.toString(),
            LspCompletionCaret(line1, col1), TextRangeEdit(line1, prefixStart - start + 1, line1, col1, ""))
    }

    fun parse(result: JsonElement, context: Context, guard: LspCompletionApplicationGuard): List<LspCompletionItem> {
        if (guard.capturedText != context.snapshot.text || guard.requiredCaret != context.requestCaret) return emptyList()
        val items = when (result) { is JsonArray -> result; is JsonObject -> result["items"] as? JsonArray; else -> null } ?: return emptyList()
        return items.take(MAX_PARSED_ITEMS).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (listOf("command", "textEdit", "additionalTextEdits").any { item.containsKey(it) }) return@mapNotNull null
            fun string(key: String) = (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun number(key: String) = (item[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (item.containsKey("insertTextFormat") && number("insertTextFormat") != 1) return@mapNotNull null
            if (item.containsKey("insertTextMode") && number("insertTextMode") != 1) return@mapNotNull null
            val label = string("label")?.takeIf { it.isNotEmpty() && it.length <= 500 && validPlain(it) } ?: return@mapNotNull null
            val insertion = if (item.containsKey("insertText")) string("insertText") ?: return@mapNotNull null else label
            if (insertion.isEmpty() || insertion.length > MAX_INSERT_CHARS || !validPlain(insertion)) return@mapNotNull null
            val kind = number("kind")?.takeIf { it in setOf(3, 6, 7, 9) } ?: return@mapNotNull null
            val detail = string("detail")?.take(1_000)
            val documentation = item["documentation"]?.let { value -> when (value) {
                is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull
                is JsonObject -> (value["value"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                else -> null
            } }?.take(4_000)
            LspCompletionItem(label, insertion, detail, documentation, kind,
                editStartLine = context.prefixRange.startLine, editStartColumn = context.prefixRange.startColumn,
                editEndLine = context.prefixRange.endLine, editEndColumn = context.prefixRange.endColumn,
                applicationGuard = guard)
        }.take(MAX_SHOWN_ITEMS)
    }
    private fun validPlain(text: String): Boolean = LspCMakePositionCodec.acceptsText(text) &&
        text.none { it == '\r' || it == '\n' || it.isISOControl() }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** A bounded lexical replacement range, never fabricated semantic completion text. */
internal object LspCSharpCompletionContext {
    const val UI_WATCHDOG_MS = 16_000L
    const val MAX_ITEMS = 240
    data class Context(val text: String, val caret: LspCompletionCaret, val prefix: String, val startColumn: Int,
        val triggerCharacter: String?) {
        fun compatible(previous: Context): Boolean {
            fun offset(value: Context): Int {
                var start = 0
                repeat(value.caret.line - 1) { start = value.text.indexOf('\n', start) + 1 }
                return start + value.caret.column - 1
            }
            val here = offset(this); val old = offset(previous)
            return caret.line == previous.caret.line && startColumn == previous.startColumn && prefix.startsWith(previous.prefix) &&
                text.substring(0, here - prefix.length) == previous.text.substring(0, old - previous.prefix.length) &&
                text.substring(here) == previous.text.substring(old)
        }
    }
    fun capture(text: String, line: Int, column: Int): Context? {
        if (text.length > 1_048_576 || !LspCSharpResultAuthority.validText(text) || !LspCSharpResultAuthority.validPosition(text, line - 1, column - 1)) return null
        var lineStart = 0
        repeat(line - 1) { lineStart = text.indexOf('\n', lineStart).takeIf { it >= 0 }?.plus(1) ?: return null }
        val offset = lineStart + column - 1

        if (!codeAt(text, offset)) return null
        if (offset < text.length && (identifierPart(text.codePointAt(offset)) || text[offset] in "@\\")) return null
        var start = offset
        var scan = lineStart
        while (scan < offset) {
            val tokenStart = scan
            if (text[scan] == '@') scan++
            val first = point(text, scan, offset)
            if (first == null || !identifierStart(first.first)) { scan = tokenStart + Character.charCount(text.codePointAt(tokenStart)); continue }
            scan = first.second
            while (scan < offset) {
                val next = point(text, scan, offset) ?: break
                if (!identifierPart(next.first)) break
                scan = next.second
            }
            if (scan == offset) start = tokenStart
        }
        // An unfinished escape or malformed verbatim identifier cannot receive a range-free edit.
        if (start == offset && offset > lineStart && text[offset - 1] in "@\\") return null
        if (start > lineStart && text[start - 1] == '\\') return null
        return Context(text, LspCompletionCaret(line, column), text.substring(start, offset), start - lineStart + 1,
            text.getOrNull(offset - 1)?.takeIf { it == '.' }?.toString())
    }
    private fun point(text: String, at: Int, end: Int): Pair<Int, Int>? {
        if (at >= end) return null
        if (text[at] != '\\') return text.codePointAt(at).let { it to at + Character.charCount(it) }
        val count = when (text.getOrNull(at + 1)) { 'u' -> 4; 'U' -> 8; else -> return null }
        if (at + 2 + count > end) return null
        val digits = text.substring(at + 2, at + 2 + count)
        if (digits.any { it.digitToIntOrNull(16) == null }) return null
        val code = digits.toLongOrNull(16)?.takeIf { it in 0..0x10ffff && it !in 0xd800..0xdfff }?.toInt() ?: return null
        return code to at + 2 + count
    }
    private fun identifierStart(point: Int): Boolean = point == 95 || Character.getType(point) in setOf(
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.LETTER_NUMBER.toInt())
    private fun identifierPart(point: Int): Boolean = identifierStart(point) || Character.getType(point) in setOf(
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.FORMAT.toInt())
    private fun codeAt(text: String, end: Int): Boolean {
        var i = 0; var state = 0
        while (i < end) {
            val c = text[i]; val next = text.getOrNull(i + 1)
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> { state = 1; i++ }
                    c == '/' && next == '*' -> { state = 2; i++ }
                    c == '@' && next == '"' -> { state = 5; i++ }
                    c == '"' -> state = 3
                    c == '\'' -> state = 4
                }
                1 -> if (c == '\n') state = 0
                2 -> if (c == '*' && next == '/') { state = 0; i++ }
                3, 4 -> if (c == '\\') i++ else if (c == if (state == 3) '"' else '\'') state = 0
                5 -> if (c == '"') { if (next == '"') i++ else state = 0 }
            }; i++
        }
        return state == 0
    }
    data class Envelope(val items: List<LspCompletionItem>, val incomplete: Boolean)
    fun parse(value: JsonElement, context: Context, guard: LspCompletionApplicationGuard, batch: Long): Envelope {
        val root = value as? JsonObject
        require(root?.containsKey("itemDefaults") != true) { "C# completion item defaults are unsupported" }
        val raw = if (value is JsonArray) value else root?.get("items") as? JsonArray ?: error("C# completion response unavailable")
        require(raw.size <= MAX_ITEMS) { "C# completion list exceeds the admitted limit; narrow the prefix" }
        val incomplete = root?.get("isIncomplete")?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: error("Invalid C# incomplete marker") } ?: false
        val prepared = raw.map { item ->
            val o = item as? JsonObject ?: error("Invalid C# completion item")
            require(o["command"] == null || o["command"] == JsonNull) { "C# completion commands are unsupported" }
            require(o["insertTextFormat"] == null || o["insertTextFormat"] == JsonPrimitive(1)) { "C# snippet insertion is not supported by this native route" }
            require(o["insertTextMode"] == null || o["insertTextMode"] == JsonPrimitive(1))
            listOf("label", "insertText", "filterText", "sortText").forEach { key -> o[key]?.let { v ->
                require(v is JsonPrimitive && v.isString && v.content.length <= if (key == "insertText") 50_000 else 500)
            } }
            if (o.containsKey("textEdit")) o else {
                val insertion = (o["insertText"] ?: o["label"]) as? JsonPrimitive ?: error("C# native insertion missing")
                JsonObject(o + ("textEdit" to buildJsonObject {
                    put("newText", insertion); put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", context.caret.line - 1); put("character", context.startColumn - 1) })
                        put("end", buildJsonObject { put("line", context.caret.line - 1); put("character", context.caret.column - 1) })
                    })
                }))
            }
        }
        val parsed = LspCompletionProtocol.parse(JsonArray(prepared), context.caret.line, context.caret.column, context.text,
            strictSnippets = true, requirePrimaryEdit = true, rejectCommand = true, preserveNativeOrder = true)
        require(parsed.size == raw.size) { "C# completion contains an unsupported or malformed edit" }
        return Envelope(parsed.mapIndexed { index, item -> item.copy(applicationGuard = guard, nativeOrigin = "csharp-ls", nativeBatch = batch, nativeItemIdentity = "$batch:$index") }, incomplete)
    }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Bounded client lexical prefix, not a replacement Erlang parser. */
internal object LspElpCompletionContext {
    data class Context(val text: String, val caret: LspCompletionCaret, val startColumn: Int, val offset: Int)
    private fun word(cp: Int): Boolean = Character.isLetterOrDigit(cp) || cp == '_'.code || cp == '@'.code
    fun capture(text: String, line: Int, column: Int): Context? {
        if (line < 1 || column < 1) return null
        val offset = LspElpResultAuthority.offset(text, line - 1, column - 1) ?: return null
        if (offset < text.length && word(text.codePointAt(offset))) return null
        // Inspect at most the already bounded document, preserving escaped quote/comment state.
        var quote: Char? = null; var comment = false; var escaped = false; var at = 0
        while (at < offset) {
            val c = text[at++]
            if (comment) { if (c == '\n') comment = false; continue }
            if (quote != null) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
            } else if (c == '%') comment = true else if (c == '\'' || c == '"') quote = c
        }
        if (comment || quote != null) return null
        var begin = offset
        while (begin > 0) { val cp = text.codePointBefore(begin); if (!word(cp)) break; begin -= Character.charCount(cp) }
        return Context(text, LspCompletionCaret(line, column), column - (offset - begin), offset)
    }
    fun trigger(context: Context, requested: String?, advertised: Set<String>): String? = requested?.takeIf {
        it in advertised && it.length == 1 && context.offset > 0 && context.text.substring(context.offset - 1, context.offset) == it
    }
    fun validSnippet(source: String): Boolean = runCatching {
        require(source.length <= ProfessionalSnippets.MAX_SNIPPET_CHARS && LspElpResultAuthority.validText(source))
        var at = 0; var markers = 0
        fun escape() { require(++at < source.length && source[at] in "\\$}"); at++ }
        while (at < source.length) {
            val c = source[at]
            require(c >= ' ' || c in "\n\r\t")
            if (c == '\\') { escape(); continue }
            if (c != '$') { at++; continue }
            require(++markers <= 96); at++
            val brace = source.getOrNull(at) == '{'; if (brace) at++
            val start = at; while (at < source.length && source[at] in '0'..'9') at++
            require(at > start && at - start <= 9)
            if (brace) {
                if (source.getOrNull(at) == ':') {
                    at++
                    while (at < source.length && source[at] != '}') {
                        require(source[at] != '$' && source[at] != '{' && (source[at] >= ' ' || source[at] in "\n\r\t"))
                        if (source[at] == '\\') escape() else at++
                    }
                }
                require(source.getOrNull(at) == '}'); at++
            }
        }
        true
    }.getOrDefault(false)
    fun parse(value: JsonElement, context: Context, guard: LspCompletionApplicationGuard, batch: Long): List<LspCompletionItem> {
        val raw = when (value) {
            JsonNull -> return emptyList()
            is JsonArray -> value
            is JsonObject -> value["items"] as? JsonArray ?: error("Malformed ELP completion list")
            else -> error("Malformed ELP completion result")
        }
        return raw.take(240).mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val command = item["command"]
            if (command != null && command != JsonNull) {
                val obj = command as? JsonObject ?: return@mapIndexedNotNull null
                if (obj.keys.any { it !in setOf("title", "command", "arguments") } ||
                    obj["command"] != JsonPrimitive("editor.action.triggerParameterHints") ||
                    (obj["arguments"] != null && obj["arguments"] != JsonArray(emptyList()))) return@mapIndexedNotNull null
            }
            val snippet = item["insertTextFormat"] == JsonPrimitive(2)
            val rawInsertion = ((item["textEdit"] as? JsonObject)?.get("newText") ?: item["insertText"] ?: item["label"])
                ?.let { runCatching { LspElpResultAuthority.string(it, ProfessionalSnippets.MAX_SNIPPET_CHARS) }.getOrNull() } ?: return@mapIndexedNotNull null
            if (snippet && !validSnippet(rawInsertion)) return@mapIndexedNotNull null
            val native = JsonObject((item - "command") + ("insertTextFormat" to JsonPrimitive(1)))
            val augmented = if (native.containsKey("textEdit")) native else {
                val insertion = native["insertText"] ?: native["label"] ?: return@mapIndexedNotNull null
                JsonObject(native + ("textEdit" to buildJsonObject {
                    put("newText", insertion)
                    put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", context.caret.line - 1); put("character", context.startColumn - 1) })
                        put("end", buildJsonObject { put("line", context.caret.line - 1); put("character", context.caret.column - 1) })
                    })
                }))
            }
            LspCompletionProtocol.parse(JsonArray(listOf(augmented)), context.caret.line, context.caret.column,
                context.text, strictSnippets = true, requirePrimaryEdit = true, rejectCommand = true, preserveNativeOrder = true)
                .singleOrNull()?.copy(insertText = rawInsertion, isSnippet = snippet, nativeSnippetPolicy = if (snippet) LspNativeSnippetPolicy.ELP_NUMERIC_RAW else null, applicationGuard = guard, nativeOrigin = "elp", nativeBatch = batch, nativeItemIdentity = "$batch:$index")
        }.take(120)
    }
}

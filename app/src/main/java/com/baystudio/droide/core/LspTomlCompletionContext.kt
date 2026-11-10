package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Captured insertion context only; all suggested language text remains native. */
internal object LspTomlCompletionContext {
    const val MAX_ITEMS = 240
    const val UI_WATCHDOG_MS = 31_000L
    data class Context(val text: String, val caret: LspCompletionCaret, val offset: Int, val gap: Boolean)
    fun capture(text: String, line: Int, column: Int): Context? {
        if (!LspTomlPositionAdmission.admits("textDocument/completion", text, line - 1, column - 1)) return null
        val at = LspTomlPositionAdmission.offset(text, line - 1, column - 1) ?: return null
        val start = text.lastIndexOf('\n', (at - 1).coerceAtLeast(-1)) + 1
        val prefix = text.substring(start, at)
        val previous = prefix.lastOrNull { it != ' ' && it != '\t' }
        val next = text.getOrNull(at)
        val gap = lexicalGap(text, at) && (previous == null || previous in "[{,=") && (next == null || next.isWhitespace() || next in "]},")
        return Context(text, LspCompletionCaret(line, column), at, gap)
    }
    /** Only recognizes whether delimiters are outside strings/comments, not TOML semantics. */
    private fun lexicalGap(text: String, end: Int): Boolean {
        var at = 0; var quote: Char? = null; var triple = false; var comment = false
        while (at < end) {
            val c = text[at]
            if (comment) { if (c == '\n') comment = false; at++; continue }
            val q = quote
            if (q != null) {
                if (q == '"' && c == '\\') { at += 2; continue }
                if (c == q && (!triple || at + 2 < end && text[at + 1] == q && text[at + 2] == q)) {
                    quote = null; at += if (triple) 3 else 1; triple = false; continue
                }
                at++; continue
            }
            if (c == '#') comment = true
            else if (c == '"' || c == '\'') {
                quote = c; triple = at + 2 < end && text[at + 1] == c && text[at + 2] == c
                if (triple) at += 2
            }
            at++
        }
        return quote == null && !comment
    }
    /** Native pin uses tabstops/placeholders; variables/transforms are not silently translated. */
    private fun supportedSnippet(text: String): Boolean {
        if (text.length > ProfessionalSnippets.MAX_SNIPPET_CHARS) return false
        var at = 0; var markers = 0
        fun part(endOnBrace: Boolean, depth: Int): Boolean {
            if (depth > 16) return false
            while (at < text.length) {
                val c = text[at++]
                if (c == '\\') { if (at >= text.length) return false; at++; continue }
                if (c == '}' && endOnBrace) return true
                if (c != '$') continue
                if (++markers > ProfessionalSnippets.MAX_SNIPPET_MARKERS) return false
                val braced = text.getOrNull(at) == '{'
                if (braced) at++
                val start = at
                while (at < text.length && text[at] in '0'..'9') at++
                if (start == at || at - start > 9) return false
                if (braced) when (text.getOrNull(at++)) {
                    '}' -> Unit
                    ':' -> if (!part(true, depth + 1)) return false
                    else -> return false
                }
            }
            return !endOnBrace
        }
        return part(false, 0)
    }
    data class Envelope(val items: List<LspCompletionItem>, val incomplete: Boolean)
    fun parse(value: JsonElement, context: Context, guard: LspCompletionApplicationGuard, batch: Long): Envelope {
        if (value == JsonNull) return Envelope(emptyList(), false)
        val root = value as? JsonObject
        require(root?.containsKey("itemDefaults") != true) { "TOML completion item defaults are unavailable" }
        val raw = if (value is JsonArray) value else root?.get("items") as? JsonArray ?: error("Malformed TOML completion response")
        require(raw.size <= MAX_ITEMS) { "TOML completion response exceeds the declared 240-item limit" }
        val incomplete = root?.get("isIncomplete")?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: error("Malformed TOML incomplete marker") } ?: false
        val prepared = raw.map { item ->
            val obj = item as? JsonObject ?: error("Malformed TOML completion item")
            require(obj["command"] == null || obj["command"] == JsonNull) { "TOML completion commands are unavailable" }
            require(obj["insertTextFormat"] == null || obj["insertTextFormat"] in setOf(JsonPrimitive(1), JsonPrimitive(2)))
            require(obj["insertTextMode"] == null || obj["insertTextMode"] == JsonPrimitive(1))
            listOf("label", "insertText", "filterText", "sortText").forEach { key -> obj[key]?.let { field ->
                require(field is JsonPrimitive && field.isString && field.content.length <= if (key == "insertText") 50_000 else 500)
            } }
            // Resolve-only insertion is not a feature of this pin. Never guess missing item text.
            require(obj["label"] is JsonPrimitive && (obj["label"] as JsonPrimitive).isString)
            if (obj.containsKey("textEdit")) obj else {
                require(context.gap) { "Native TOML range-free completion is unavailable inside a nonempty token" }
                val insertion = obj["insertText"] ?: obj["label"] ?: error("Native TOML insertion missing")
                JsonObject(obj + ("textEdit" to buildJsonObject {
                    put("newText", insertion); put("range", buildJsonObject {
                        val point = buildJsonObject { put("line", context.caret.line - 1); put("character", context.caret.column - 1) }
                        put("start", point); put("end", point)
                    })
                }))
            }
        }
        prepared.forEach { obj ->
            val edit = obj["textEdit"] as? JsonObject ?: error("Malformed TOML primary edit")
            listOf("range", "insert", "replace").forEach { key -> edit[key]?.let { LspTomlResultAuthority.range(it, context.text) } }
            obj["additionalTextEdits"]?.let { extras ->
                (extras as? JsonArray ?: error("Malformed TOML additional edits")).forEach { extra ->
                    LspTomlResultAuthority.range((extra as? JsonObject)?.get("range"), context.text)
                }
            }
        }
        prepared.forEach { item -> if (item["insertTextFormat"] == JsonPrimitive(2)) {
            val nativeText = (item["textEdit"] as JsonObject)["newText"] as? JsonPrimitive ?: error("Native TOML snippet missing")
            require(nativeText.isString && supportedSnippet(nativeText.content)) { "Native TOML snippet uses unsupported syntax; no plaintext substitute was made" }
        } }
        val parsed = LspCompletionProtocol.parse(JsonArray(prepared), context.caret.line, context.caret.column, context.text,
            strictSnippets = true, requirePrimaryEdit = true, rejectCommand = true, preserveNativeOrder = true)
        require(parsed.size == raw.size) { "Native TOML completion contains an unsupported edit or snippet" }
        return Envelope(parsed.mapIndexed { index, item -> item.copy(applicationGuard = guard, nativeOrigin = LspTomlProfile.ID,
            nativeBatch = batch, nativeItemIdentity = "$batch:$index") }, incomplete)
    }
}

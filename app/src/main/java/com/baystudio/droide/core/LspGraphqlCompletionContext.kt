package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Validates the adapter's original-parser context; never generates or refilters candidates. */
internal object LspGraphqlCompletionContext {
    const val UI_WATCHDOG_MS = 16_000L
    data class Context(val text: String, val caret: LspCompletionCaret, val offset: Int)
    data class Envelope(val items: List<LspCompletionItem>, val followOnUnsupported: Boolean)
    fun capture(text: String, line: Int, column: Int): Context? = LspGraphqlPositionCodec.cursor(text, line - 1, column - 1)?.let { Context(text, LspCompletionCaret(line, column), it) }
    fun numericSnippet(text: String): Boolean {
        if (text.length > 8_000 || !LspGraphqlPositionCodec.validText(text) || text.any { it == '\r' || it == '`' || it == '\\' }) return false
        var at = 0; var markers = 0
        while (at < text.length) {
            if (text[at++] != '$') continue
            if (++markers > 96) return false
            val start = at; while (at < text.length && text[at] in '0'..'9') at++
            if (at == start || at - start > 9) return false
        }
        return true
    }
    private fun string(value: JsonElement?, max: Int): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Malformed native GraphQL text")
        require(text.length <= max && LspGraphqlPositionCodec.validText(text)); return text
    }
    private fun integer(value: JsonElement?): Int = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("Malformed native GraphQL integer")
    fun parse(value: JsonElement, context: Context, guard: LspCompletionApplicationGuard, batch: Long): Envelope {
        val root = value as? JsonObject ?: error("GraphQL completion envelope missing")
        require(root.keys.all { it in setOf("items", "isIncomplete", "context", "followOnSuggestionsUnsupported") })
        require(root["isIncomplete"] == JsonPrimitive(false)) { "Incomplete GraphQL completion is unavailable" }
        val native = root["context"] as? JsonObject ?: error("Original GraphQL parser context missing")
        require(native.keys == setOf("start", "end", "caret"))
        val start = integer(native["start"]); val end = integer(native["end"])
        require(integer(native["caret"]) == context.offset && start <= context.offset && context.offset <= end)
        require(LspGraphqlPositionCodec.boundary(context.text, start) && LspGraphqlPositionCodec.boundary(context.text, end))
        val first = requireNotNull(LspGraphqlPositionCodec.position(context.text, start)); val last = requireNotNull(LspGraphqlPositionCodec.position(context.text, end))
        require(first.line == context.caret.line - 1 && last.line == first.line)
        if (start != end) require(context.text.substring(start, end).matches(Regex("[_A-Za-z][_0-9A-Za-z]*"))) { "Native GraphQL edit must replace exactly its captured Name token" }
        else require(context.text.getOrNull(end)?.let { it in 'A'..'Z' || it in 'a'..'z' || it == '_' } != true) { "GraphQL gap before an unselected name is unavailable" }
        val raw = root["items"] as? JsonArray ?: error("GraphQL items missing"); require(raw.size <= 240)
        var followOn = root["followOnSuggestionsUnsupported"]?.let { require(it == JsonPrimitive(true) || it == JsonPrimitive(false)); it == JsonPrimitive(true) } ?: false
        val items = raw.mapIndexed { index, value ->
            val item = value as? JsonObject ?: error("Malformed GraphQL native candidate")
            require(item.keys.all { it in setOf("label", "insertText", "detail", "documentation", "kind", "filterText", "sortText", "preselect", "insertTextFormat", "command") }) { "Unexpected GraphQL candidate field or edit" }
            item["command"]?.takeIf { it != JsonNull }?.let { command ->
                val obj = command as? JsonObject ?: error("GraphQL command malformed")
                require(obj == buildJsonObject { put("command", "editor.action.triggerSuggest"); put("title", "Suggestions") }) { "Unsupported GraphQL candidate command" }
                followOn = true
            }
            val label = string(item["label"], 20_000)
            val insertion = if (item.containsKey("insertText")) string(item["insertText"], 20_000) else label
            val format = item["insertTextFormat"]?.let(::integer) ?: 1; require(format == 1 || format == 2)
            if (format == 2) { require(numericSnippet(insertion)) { "Unsupported raw GraphQL snippet; no plaintext substitute" }; LspGraphqlSnippetPolicy.parts(insertion) }
            val documentation = item["documentation"]?.let { field -> when (field) {
                is JsonPrimitive -> string(field, 20_000)
                is JsonObject -> { require(field.keys == setOf("kind", "value") && field["kind"] in setOf(JsonPrimitive("plaintext"), JsonPrimitive("markdown"))); string(field["value"], 20_000) }
                else -> error("Malformed GraphQL documentation")
            } }
            val kind = item["kind"]?.let(::integer)?.also { require(it in 1..25) }
            val preselect = item["preselect"]?.let { require(it == JsonPrimitive(true) || it == JsonPrimitive(false)); it == JsonPrimitive(true) } ?: false
            LspCompletionItem(label, insertion, item["detail"]?.let { string(it, 20_000) }, documentation, kind,
                item["filterText"]?.let { string(it, 20_000) }, item["sortText"]?.let { string(it, 20_000) }, preselect,
                first.line + 1, first.character + 1, last.line + 1, last.character + 1,
                isSnippet = format == 2, applicationGuard = guard, nativeOrigin = LspGraphqlProfile.ID,
                nativeBatch = batch, nativeItemIdentity = "$batch:$index",
                nativeSnippetPolicy = if (format == 2) LspNativeSnippetPolicy.GRAPHQL_NUMERIC_RAW else null)
        }
        return Envelope(items, followOn)
    }
}

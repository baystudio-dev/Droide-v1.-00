package com.baystudio.droide.core

import kotlinx.serialization.json.*

/**
 * Validation only: input is exact captured client text, never a disk read or byte-coordinate conversion.
 * URI, pre-request synchronization, current open state, owner and snapshot checks belong to the manager.
 */
internal object LspNimResultAuthority {
    const val MAX_RESULT_ITEMS = 4096
    const val MAX_COMPLETIONS = 512
    const val MAX_LABEL_CHARS = 2000
    const val MAX_DOCUMENTATION_CHARS = 16_384

    fun validPosition(text: String, line: Int, character: Int): Boolean {
        if (line < 0 || character < 0 || line == Int.MAX_VALUE || character == Int.MAX_VALUE) return false
        return runCatching {
            TextEditApplier.apply(text, listOf(TextRangeEdit(line + 1, character + 1, line + 1, character + 1, "")))
        }.isSuccess
    }

    fun validRange(text: String, range: JsonElement?): Boolean {
        val objectRange = range as? JsonObject ?: return false
        val start = position(objectRange["start"]) ?: return false
        val end = position(objectRange["end"]) ?: return false
        return validRange(text, start.first, start.second, end.first, end.second)
    }

    fun validRange(text: String, startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int): Boolean {
        if (listOf(startLine, startCharacter, endLine, endCharacter).any { it < 0 || it == Int.MAX_VALUE }) return false
        return runCatching {
            TextEditApplier.apply(text, listOf(TextRangeEdit(startLine + 1, startCharacter + 1, endLine + 1, endCharacter + 1, "")))
        }.isSuccess
    }

    /** Throws on one invalid edit; callers reject the complete workspace transaction, never a subset. */
    fun requireEdits(text: String, edits: List<TextRangeEdit>) {
        require(edits.size <= MAX_RESULT_ITEMS) { "Nim edit count exceeds the bounded transaction" }
        TextEditApplier.apply(text, edits)
    }

    private fun position(raw: JsonElement?): Pair<Int, Int>? {
        val value = raw as? JsonObject ?: return null
        fun integer(name: String): Int? = (value[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val line = integer("line") ?: return null
        val character = integer("character") ?: return null
        return line to character
    }

    /** The pinned server returns only native plain labels/kind/detail/documentation, no resolve/edits/snippets. */
    fun plainCompletion(result: JsonElement?): JsonArray {
        val items = result as? JsonArray ?: return buildJsonArray { }
        return JsonArray(items.take(MAX_COMPLETIONS).mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            if (item.keys.any { it !in setOf("label", "kind", "detail", "documentation") }) return@mapNotNull null
            val label = string(item["label"], MAX_LABEL_CHARS)?.takeIf { it.isNotBlank() && it.none { ch -> ch.code < 0x20 } }
                ?: return@mapNotNull null
            val kind = (item["kind"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (item.containsKey("kind") && kind !in 1..25) return@mapNotNull null
            if (item.containsKey("detail") && string(item["detail"], MAX_DOCUMENTATION_CHARS) == null) return@mapNotNull null
            if (item.containsKey("documentation") && string(item["documentation"], MAX_DOCUMENTATION_CHARS) == null) return@mapNotNull null
            buildJsonObject {
                put("label", label)
                kind?.let { put("kind", it) }
                item["detail"]?.let { put("detail", it) }
                item["documentation"]?.let { put("documentation", it) }
                // Always explicit plain text, even if the native symbol kind happens to be Snippet.
                put("insertTextFormat", 1)
            }
        })
    }

    /** Preserve native signature labels/docs, never fabricate parameter ranges or active-parameter accuracy. */
    fun signatureLabels(result: JsonElement?): JsonElement {
        val root = result as? JsonObject ?: return JsonNull
        val signatures = root["signatures"] as? JsonArray ?: return JsonNull
        val labels = signatures.take(12).mapNotNull { raw ->
            val value = raw as? JsonObject ?: return@mapNotNull null
            val label = string(value["label"], MAX_LABEL_CHARS)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val documentation = value["documentation"]?.let(::documentation)
            if (value.containsKey("documentation") && documentation == null) return@mapNotNull null
            buildJsonObject {
                put("label", label)
                documentation?.let { put("documentation", it) }
                put("parameters", buildJsonArray { })
            }
        }
        if (labels.isEmpty()) return JsonNull
        return buildJsonObject { put("signatures", JsonArray(labels)) }
    }

    private fun documentation(value: JsonElement): JsonElement? {
        if (string(value, MAX_DOCUMENTATION_CHARS) != null) return value
        val markup = value as? JsonObject ?: return null
        val kind = string(markup["kind"], 16)?.takeIf { it == "markdown" || it == "plaintext" } ?: return null
        val text = string(markup["value"], MAX_DOCUMENTATION_CHARS) ?: return null
        return buildJsonObject { put("kind", kind); put("value", text) }
    }

    private fun string(value: JsonElement?, max: Int): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= max && validUtf16(it.content) }?.content

    private fun validUtf16(text: String): Boolean = runCatching {
        TextEditApplier.apply("", listOf(TextRangeEdit(1, 1, 1, 1, text)))
    }.isSuccess
}

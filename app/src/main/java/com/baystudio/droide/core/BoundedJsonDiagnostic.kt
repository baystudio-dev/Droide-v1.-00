package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Diagnostic projection only: may be truncated JSON and must never drive authorization. */
internal fun boundedJsonDiagnostic(value: JsonElement?, maxChars: Int = 8_000, maxDepth: Int = 16): String {
    require(maxChars > 0 && maxDepth > 0)
    val output = StringBuilder(minOf(maxChars, 256))
    var truncated = false
    fun put(char: Char): Boolean {
        if (output.length == maxChars) { truncated = true; return false }
        output.append(char)
        return true
    }
    fun raw(text: String): Boolean {
        val count = minOf(text.length, maxChars - output.length)
        output.append(text, 0, count)
        if (count < text.length) truncated = true
        return count == text.length
    }
    fun quoted(text: String): Boolean {
        if (!put('"')) return false
        for (char in text) {
            val accepted = when (char) {
                '"' -> raw("\\\"")
                '\\' -> raw("\\\\")
                '\n' -> raw("\\n")
                '\r' -> raw("\\r")
                '\t' -> raw("\\t")
                '\b' -> raw("\\b")
                '\u000C' -> raw("\\f")
                else -> if (char < ' ') {
                    raw("\\u00") && put("0123456789abcdef"[char.code ushr 4]) && put("0123456789abcdef"[char.code and 15])
                } else put(char)
            }
            if (!accepted) return false
        }
        return put('"')
    }
    fun visit(node: JsonElement?, depth: Int): Boolean {
        if (depth > maxDepth) { truncated = true; return raw("[depth limit]") && false }
        return when (node) {
            null, JsonNull -> raw("null")
            is JsonPrimitive -> if (node.isString) quoted(node.content) else raw(node.content)
            is JsonArray -> {
                if (!put('[')) false else {
                    var ok = true
                    for ((index, child) in node.withIndex()) {
                        if (index > 0 && !put(',') || !visit(child, depth + 1)) { ok = false; break }
                    }
                    ok && put(']')
                }
            }
            is JsonObject -> {
                if (!put('{')) false else {
                    var first = true
                    var ok = true
                    for ((key, child) in node) {
                        if (!first && !put(',') || !quoted(key) || !put(':') || !visit(child, depth + 1)) { ok = false; break }
                        first = false
                    }
                    ok && put('}')
                }
            }
        }
    }
    visit(value, 0)
    if (truncated) {
        val marker = if (maxChars >= 12) "\n[truncated]" else "…"
        var keep = minOf(output.length, maxChars - marker.length)
        if (keep > 0 && output[keep - 1].isHighSurrogate()) keep--
        output.setLength(keep)
        output.append(marker)
    }
    return output.toString()
}

/** Compact presentation only; do not use a clipped label as a permission identity. */
internal fun boundedDiagnosticText(text: String, maxChars: Int = 512): String {
    require(maxChars > 0)
    if (text.length <= maxChars) return text
    var end = maxChars - 1
    if (end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end) + "…"
}

package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** VS Code workspace files use JSONC. Comments are removed only outside JSON strings. */
internal object WorkspaceJsonConfig {
    const val MAX_BYTES = 256_000
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parseObject(text: String): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES) { "Workspace configuration is too large or empty" }
        val clean = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (inString) {
                clean.append(c)
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                index++
            } else when {
                c == '"' -> { inString = true; clean.append(c); index++ }
                c == '/' && text.getOrNull(index + 1) == '/' -> {
                    clean.append(' '); index += 2
                    while (index < text.length && text[index] != '\n' && text[index] != '\r') index++
                }
                c == '/' && text.getOrNull(index + 1) == '*' -> {
                    clean.append(' '); index += 2
                    var closed = false
                    while (index < text.length) {
                        if (text[index] == '*' && text.getOrNull(index + 1) == '/') { index += 2; closed = true; break }
                        if (text[index] == '\n' || text[index] == '\r') clean.append(text[index])
                        index++
                    }
                    require(closed) { "Unterminated workspace configuration comment" }
                }
                else -> { clean.append(c); index++ }
            }
        }
        require(!inString) { "Unterminated workspace configuration string" }
        val result = StringBuilder(clean.length)
        inString = false; escaped = false; index = 0
        while (index < clean.length) {
            val c = clean[index]
            if (!inString && c == ',') {
                var next = index + 1
                while (next < clean.length && clean[next].isWhitespace()) next++
                if (clean.getOrNull(next) == '}' || clean.getOrNull(next) == ']') { index++; continue }
            }
            result.append(c)
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else if (c == '"') inString = true
            index++
        }
        return json.parseToJsonElement(result.toString()) as? JsonObject
            ?: error("Workspace configuration must be an object")
    }
}

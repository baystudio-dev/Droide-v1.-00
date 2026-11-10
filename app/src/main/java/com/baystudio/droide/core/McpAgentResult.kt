package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Never cut a JSON object in half: bounded previews explicitly declare incomplete data. */
internal object McpAgentResult {
    fun render(server: String, protocol: String, operation: String, result: JsonElement): String {
        val envelope = buildJsonObject {
            put("server", server); put("protocolVersion", protocol); put("operation", operation)
            put("isError", (result as? JsonObject)?.get("isError") == JsonPrimitive(true))
            put("result", result)
        }
        val full = envelope.toString()
        if (full.length <= 10_000) return full
        var previewSize = 6_000
        while (true) {
            val bounded = buildJsonObject {
                put("server", server); put("protocolVersion", protocol); put("operation", operation)
                put("isError", envelope.getValue("isError")); put("truncated", true); put("original_chars", full.length)
                put("notice", "Incomplete result preview; narrow the resource/query or use server pagination before relying on omitted content.")
                put("result_preview", result.toString().take(previewSize))
            }.toString()
            if (bounded.length <= 10_000) return bounded
            previewSize /= 2
        }
    }
}

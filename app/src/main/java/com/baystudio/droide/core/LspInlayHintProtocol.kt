package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class LspInlayHint(
    val line: Int,
    val column: Int,
    val label: String,
    val kind: Int? = null,
    val paddingLeft: Boolean = false,
    val paddingRight: Boolean = false,
    val activationGuard: LspHeldResultGuard? = null,
)

internal object LspInlayHintProtocol {
    suspend fun requestFull(rpc: JsonRpcProcess, uri: String, text: String): List<LspInlayHint> {
        val (endLine, endColumn) = documentEnd(text)
        val result = runSuspendCatching {
            rpc.request(
                "textDocument/inlayHint",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", 0); put("character", 0) })
                        put("end", buildJsonObject { put("line", endLine); put("character", endColumn) })
                    })
                },
                timeoutMs = 4_000,
                cancellationNotificationMethod = "$/cancelRequest",
            )
        }.getOrNull() as? JsonArray ?: return emptyList()
        return result.asSequence().mapNotNull { parse(it, endLine, endColumn) }.take(MAX_HINTS).toList()
    }

    private fun parse(element: JsonElement, endLine: Int, endColumn: Int): LspInlayHint? {
        val obj = element as? JsonObject ?: return null
        val position = obj["position"] as? JsonObject ?: return null
        val line = (position["line"] as? JsonPrimitive)?.intOrNull ?: return null
        val column = (position["character"] as? JsonPrimitive)?.intOrNull ?: return null
        if (line < 0 || line >= Int.MAX_VALUE || column < 0 || column >= Int.MAX_VALUE ||
            line > endLine || (line == endLine && column > endColumn)
        ) return null
        val label = parseLabel(obj["label"]).trim().labelPreview()
        if (label.isEmpty()) return null
        return LspInlayHint(
            line = line + 1,
            column = column + 1,
            label = label,
            kind = (obj["kind"] as? JsonPrimitive)?.intOrNull,
            paddingLeft = (obj["paddingLeft"] as? JsonPrimitive)?.booleanOrNull == true,
            paddingRight = (obj["paddingRight"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun parseLabel(label: JsonElement?): String = when (label) {
        null -> ""
        // Keep one lookahead code unit per part; cut only after joining the prefix.
        is JsonArray -> label.asSequence().take(32).joinToString("") { part ->
            ((part as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty().take(MAX_LABEL_LENGTH + 1)
        }.labelPreview()
        is JsonPrimitive -> label.contentOrNull.orEmpty().labelPreview()
        else -> ""
    }

    /** Keep the existing preview limit without leaving half a UTF-16 character. */
    private fun String.labelPreview(): String {
        if (length <= MAX_LABEL_LENGTH) return this
        val end = if (this[MAX_LABEL_LENGTH - 1].isHighSurrogate() &&
            this[MAX_LABEL_LENGTH].isLowSurrogate()) MAX_LABEL_LENGTH - 1 else MAX_LABEL_LENGTH
        return substring(0, end)
    }

    private fun documentEnd(text: String): Pair<Int, Int> {
        var line = 0
        var lineStart = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    index++
                    if (index < text.length && text[index] == '\n') index++
                    line++
                    lineStart = index
                }
                '\n' -> {
                    index++
                    line++
                    lineStart = index
                }
                else -> index++
            }
        }
        return line to (text.length - lineStart)
    }

    private const val MAX_HINTS = 400
    private const val MAX_LABEL_LENGTH = 80
}

package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive

internal object LspDiagnosticsProtocol {
    sealed interface PullReport {
        val resultId: String?
        data class Full(val items: List<LspDiagnostic>, override val resultId: String?) : PullReport
        data class Unchanged(override val resultId: String) : PullReport
    }

    fun parsePull(result: JsonElement?): PullReport? {
        val obj = result as? JsonObject ?: return null
        val resultId = string(obj["resultId"])
        if (obj.containsKey("resultId") && resultId == null) return null
        return when (string(obj["kind"])) {
            "full" -> PullReport.Full(parseItems(obj["items"]) ?: return null, resultId)
            "unchanged" -> PullReport.Unchanged(resultId ?: return null)
            else -> null
        }
    }

    // Missing/malformed payloads are never equivalent to an explicit empty report.
    fun parseItems(value: JsonElement?, retainCode: Boolean = false): List<LspDiagnostic>? {
        val items = value as? JsonArray ?: return null
        return items.map { parse(it, retainCode) ?: return null }
    }

    fun parse(el: JsonElement, retainCode: Boolean = false): LspDiagnostic? {
        val obj = el as? JsonObject ?: return null
        val range = obj["range"] as? JsonObject ?: return null
        val start = range["start"] as? JsonObject ?: return null
        val end = range["end"] as? JsonObject ?: return null
        val sl = position(start["line"]) ?: return null
        val sc = position(start["character"]) ?: return null
        val eline = position(end["line"]) ?: return null
        val ec = position(end["character"]) ?: return null
        if (eline < sl || (eline == sl && ec < sc)) return null
        return LspDiagnostic(
            line = sl + 1, column = sc + 1, endLine = eline + 1, endColumn = ec + 1,
            severity = (obj["severity"] as? JsonPrimitive)?.intOrNull,
            message = messagePreview(string(obj["message"]) ?: return null),
            source = string(obj["source"]),
            code = if (retainCode) diagnosticCode(obj["code"]) else null,
        )
    }

    private fun messagePreview(message: String): String {
        val limit = 2_000
        if (message.length <= limit) return message
        val end = if (message[limit - 1].isHighSurrogate() && message[limit].isLowSurrogate()) limit - 1 else limit
        return message.substring(0, end)
    }

    /** Preserve native string/integer identity; never truncate or invent a key. */
    private fun diagnosticCode(value: JsonElement?): JsonPrimitive? {
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.takeIf { it.content.length <= 256 }
        else primitive.takeIf { it.intOrNull != null }
    }

    private fun string(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun position(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0 until Int.MAX_VALUE }

    fun format(path: String, items: List<LspDiagnostic>): String = buildString {
        appendLine("Diagnostics for $path:")
        items.take(100).forEach { d ->
            val sev = when (d.severity) {
                1 -> "error"
                2 -> "warning"
                3 -> "info"
                4 -> "hint"
                else -> "diagnostic"
            }
            appendLine("${d.line}:${d.column} [$sev${d.source?.let { "/$it" } ?: ""}] ${d.message}")
        }
        if (items.size > 100) appendLine("[LSP_RESULT_LIMIT: showing 100 of ${items.size} diagnostics; narrow the request.]")
    }.trimEnd()
}

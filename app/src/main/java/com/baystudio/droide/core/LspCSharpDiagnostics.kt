package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** A report proves at most a current-document native semantic response, never project completeness. */
internal object LspCSharpDiagnostics {
    data class Report(val items: List<LspDiagnostic>, val resultId: String?, val semanticResult: Boolean)
    fun parse(value: JsonElement, text: String, prior: Report?): Report? {
        val root = value as? JsonObject ?: return null
        val id = (root["resultId"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 4096 }?.content
        return when ((root["kind"] as? JsonPrimitive)?.content) {
            "unchanged" -> prior?.takeIf { id != null && it.resultId == id }
            "full" -> {
                val raw = root["items"] as? JsonArray ?: return null
                if (raw.size > 500 || raw.any { !LspCSharpResultAuthority.validRange(text, (it as? JsonObject)?.get("range")) }) return null
                val items = LspDiagnosticsProtocol.parseItems(raw, retainCode = true) ?: return null
                // A transient empty report without an ID must not erase a meaningful earlier report.
                if (items.isEmpty() && id == null) null else Report(items, id, id != null)
            }
            else -> null
        }
    }
}

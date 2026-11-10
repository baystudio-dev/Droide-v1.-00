package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** A workspace text edit must be explicit; missing wire values are never defaults. */
internal object LspWorkspaceTextEditParser {
    fun parse(value: JsonElement): TextRangeEdit? {
        val obj = value as? JsonObject ?: return null
        val newText = (obj["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        require(newText.length <= 200_000) { "Single language-server edit is too large" }
        val range = obj["range"] as? JsonObject ?: return null
        val start = range["start"] as? JsonObject ?: return null
        val end = range["end"] as? JsonObject ?: return null
        val sl = coordinate(start["line"]) ?: return null
        val sc = coordinate(start["character"]) ?: return null
        val el = coordinate(end["line"]) ?: return null
        val ec = coordinate(end["character"]) ?: return null
        if (el < sl || (el == sl && ec < sc)) return null
        return TextRangeEdit(sl + 1, sc + 1, el + 1, ec + 1, newText)
    }

    private fun coordinate(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it in 0 until Int.MAX_VALUE }
}

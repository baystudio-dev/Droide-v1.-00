package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Immutable indentation snapshot captured by the caller for one formatting request. */
data class LspFormattingOptions(val tabSize: Int, val insertSpaces: Boolean) {
    init { require(tabSize > 0) { "Formatting tab size must be positive" } }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("tabSize", tabSize)
        put("insertSpaces", insertSpaces)
    }

    companion object {
        internal val DEFAULT = LspFormattingOptions(4, true)
        fun fromCodeStyle(style: CodeStyleProfile): LspFormattingOptions =
            LspFormattingOptions(style.indentSize, !style.useTabs)
    }
}

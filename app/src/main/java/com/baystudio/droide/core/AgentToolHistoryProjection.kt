package com.baystudio.droide.core

/**
 * Generic model-history preview. Does not change execution, success evidence, or
 * advertise a retrieval operation. Structured web continuations use their own
 * projection in AgentWebTools. This is not an exact-output archival mechanism.
 */
internal object AgentToolHistoryProjection {
    internal const val MAX_CHARS = 12_000

    fun project(annotatedResult: String): String {
        if (annotatedResult.length <= MAX_CHARS) return annotatedResult
        val marker = "\n[DROIDE_TOOL_OUTPUT_INCOMPLETE original_utf16_chars=${annotatedResult.length}]\n" +
            "Only an output prefix is shown. Omitted content is unknown; this preview is not a complete document or JSON value. " +
            "Do not infer missing results or repeat a side-effecting operation just to recover its output."
        var end = MAX_CHARS - marker.length
        // Kotlin String indices count UTF-16 units; never split an emoji/code point.
        if (end > 0 && annotatedResult[end - 1].isHighSurrogate() && annotatedResult[end].isLowSurrogate()) end--
        return annotatedResult.substring(0, end) + marker
    }
}

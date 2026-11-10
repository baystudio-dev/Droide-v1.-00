package com.baystudio.droide.core

/** Retention failure must never be reclassified as tool failure or cause a retry. */
internal object AgentRetainedToolOutputProjection {
    fun project(tool: String, output: String, retain: (String) -> AgentToolOutputStore.Retained): String {
        if (output.length <= AgentToolHistoryProjection.MAX_CHARS || tool in setOf("web", "webfetch", "websearch", "browser"))
            return AgentWebTools.forAgentHistory(tool, output)
        val retained = try { retain(output) } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val marker = if (retained != null)
            "\n[DROIDE_TOOL_OUTPUT_RETAINED original_utf16_chars=${output.length} reference=${retained.reference}]\n" +
                "Incomplete prefix. Retrieve exact returned output with read_file(path=reference, offset=0); continue with next_offset. " +
                "This archives the tool-return boundary, not content already omitted upstream. Do not repeat the original operation."
        else "\n[DROIDE_TOOL_OUTPUT_RETENTION_UNAVAILABLE original_utf16_chars=${output.length}]\n" +
            "Incomplete prefix; full output could not be archived (capacity or storage unavailable). " +
            "Execution evidence above is unchanged. Do not repeat a side-effecting operation to recover output."
        val previewLimit = if (retained != null) 6_000 else AgentToolHistoryProjection.MAX_CHARS
        var end = previewLimit - marker.length
        if (end > 0 && output[end - 1].isHighSurrogate() && output[end].isLowSurrogate()) end--
        return output.substring(0, end) + marker
    }
}

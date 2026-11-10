package com.baystudio.droide.core

/** A bounded report prefix is not a complete report, even when execution completed. */
internal object SubagentTextProjection {
    const val NOTICE = "[SUBAGENT_TEXT_TRUNCATED: returned prefix only; remainder omitted]\n"
    fun bounded(text: String, limit: Int): String {
        require(limit >= NOTICE.length)
        if (text.length <= limit) return text
        var end = limit - NOTICE.length
        if (end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return NOTICE + text.substring(0, end)
    }
}

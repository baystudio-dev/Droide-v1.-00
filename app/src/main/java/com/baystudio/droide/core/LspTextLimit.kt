package com.baystudio.droide.core

internal object LspTextLimit {
    fun characters(text: String, limit: Int = 12_000): String {
        require(limit >= 128)
        if (text.length <= limit) return text
        val notice = "\n[LSP_RESULT_LIMIT: text truncated; narrow the request. Original characters=${text.length}.]"
        var end = limit - notice.length
        if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end) + notice
    }

    fun items(text: String, total: Int, limit: Int): String = if (total <= limit) text
        else text + "\n[LSP_RESULT_LIMIT: showing at most $limit of $total items; narrow the request.]"
}

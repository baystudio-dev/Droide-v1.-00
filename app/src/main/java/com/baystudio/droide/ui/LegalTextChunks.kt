package com.baystudio.droide.ui

internal fun legalTextChunks(text: String, maximum: Int = 4096): List<String> {
    require(maximum >= 2)
    if (text.isEmpty()) return emptyList()
    return buildList {
        var start = 0
        while (start < text.length) {
            var end = (start + maximum).coerceAtMost(text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline >= start) end = newline + 1
                else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            add(text.substring(start, end))
            start = end
        }
    }
}

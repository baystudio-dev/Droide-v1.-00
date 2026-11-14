package com.baystudio.droide.core

/** Same UTF-16-safe tail as append-then-trim, without growing to the input size. */
internal fun appendBoundedTextTail(target: StringBuilder, text: String, maxChars: Int) {
    require(maxChars > 0)
    if (text.isEmpty()) return
    if (text.length >= maxChars) {
        var from = text.length - maxChars
        if (text[from].isLowSurrogate() &&
            (from > 0 && text[from - 1].isHighSurrogate() ||
                from == 0 && target.isNotEmpty() && target[target.length - 1].isHighSurrogate())) from++
        target.setLength(0)
        target.append(text, from, text.length)
    } else {
        var remove = (target.length.toLong() + text.length - maxChars).coerceAtLeast(0L).toInt()
        if (remove > 0 && remove < target.length &&
            target[remove].isLowSurrogate() && target[remove - 1].isHighSurrogate()) remove++
        // The cut can fall exactly between a high surrogate in the previous
        // chunk and a low surrogate at the start of this one.
        if (remove == target.length && remove > 0 && target[remove - 1].isHighSurrogate() &&
            text[0].isLowSurrogate()) {
            target.setLength(0)
            target.append(text, 1, text.length)
        } else {
            if (remove > 0) target.delete(0, remove)
            target.append(text)
        }
    }
}

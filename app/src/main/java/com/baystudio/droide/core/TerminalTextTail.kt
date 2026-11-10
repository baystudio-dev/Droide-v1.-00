package com.baystudio.droide.core

/** Largest contiguous suffix within a UTF-16 unit cap, without cutting a surrogate pair. */
internal fun terminalTextTailStart(text: String, maxChars: Int): Int {
    require(maxChars >= 0)
    val start = (text.length - maxChars).coerceAtLeast(0)
    return if (start > 0 && start < text.length &&
        Character.isHighSurrogate(text[start - 1]) && Character.isLowSurrogate(text[start])) {
        start + 1
    } else start
}

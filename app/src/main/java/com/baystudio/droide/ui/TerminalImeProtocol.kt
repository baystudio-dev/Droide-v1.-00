package com.baystudio.droide.ui







internal object TerminalImeProtocol {
    



    const val MAX_DELETE_KEYS_PER_CALLBACK = 256

    fun boundedDeleteCount(requested: Int): Int = requested.coerceIn(0, MAX_DELETE_KEYS_PER_CALLBACK)

     
    fun normalizeCommittedText(text: CharSequence?): String {
        if (text.isNullOrEmpty()) return ""
        return buildString(text.length) {
            text.forEachIndexed { index, ch ->
                if (ch != '\n' || index == 0 || text[index - 1] != '\r') append(if (ch == '\n') '\r' else ch)
            }
        }
    }
}

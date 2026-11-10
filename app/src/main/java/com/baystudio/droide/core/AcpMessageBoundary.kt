package com.baystudio.droide.core

/** A plain-text boundary projection, not durable ACP identity storage. One per content kind/turn. */
internal class AcpMessageBoundary(private val maxIdChars: Int = 512) {
    private var lastId: String? = null
    private var hasContent = false

    init { require(maxIdChars > 0) }

    /** Null means legacy unlabelled chunks; retain the last explicit identity. */
    fun prefix(messageId: String?, text: String): String {
        require(messageId == null || messageId.length in 1..maxIdChars) {
            "CLI message identifier exceeds the supported size or is empty"
        }
        if (text.isEmpty()) return ""
        val changed = messageId != null && lastId != null && messageId != lastId
        if (messageId != null) lastId = messageId
        val separator = if (hasContent && changed) "\n\n" else ""
        hasContent = true
        return separator
    }
}

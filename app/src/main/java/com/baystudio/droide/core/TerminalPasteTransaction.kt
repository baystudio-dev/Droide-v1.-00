package com.baystudio.droide.core

import java.util.concurrent.CancellationException

/**
 * Captures only a synchronous call to the pinned emulator's paste producer. Its
 * existing sanitization, newline conversion and DECSET2004 framing stay authoritative.
 * One immutable item is then admitted to the ordinary ordered input relay.
 * Admission is atomic; a later transport failure is still ambiguous and never replayed.
 */
internal class TerminalPasteTransaction(
    private val send: (ByteArray, Int, Int) -> Boolean,
    private val onRejected: (String) -> Unit,
) {
    companion object {
        const val MAX_BYTES = 65_536
        const val MAX_TEXT_UNITS = 65_536
        private val START = "\u001b[200~".toByteArray(Charsets.UTF_8)
        private val END = "\u001b[201~".toByteArray(Charsets.UTF_8)
    }

    private class Capture {
        val bytes = ByteArray(MAX_BYTES)
        var size = 0
        var writes = 0
        var firstWasStart = false
        var lastWasEnd = false
        var error: String? = null
    }
    private val lock = Any()
    private var capture: Capture? = null

    /** Installed on this exact emulator/session output, never a mutable active-tab sink. */
    fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size): Boolean = synchronized(lock) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        val current = capture ?: return@synchronized send(bytes, offset, count)
        if (current.error != null) return@synchronized false
        current.writes++
        if (current.writes > 3 || count > MAX_BYTES - current.size) {
            current.error = "Paste exceeds 64 KiB of encoded input including framing; the whole paste was rejected"
            return@synchronized false
        }
        fun matches(value: ByteArray) = count == value.size && value.indices.all { bytes[offset + it] == value[it] }
        if (current.writes == 1) current.firstWasStart = matches(START)
        current.lastWasEnd = matches(END)
        bytes.copyInto(current.bytes, current.size, offset, offset + count)
        current.size += count
        true
    }

    fun paste(text: String, isCurrent: () -> Boolean,
        admit: (ByteArray, Int, Int) -> Boolean = send, produce: (String) -> Unit): Boolean {
        var rejection: String? = null
        val accepted = synchronized(lock) {
            // A nested producer must invalidate the outer operation too. No observer
            // callbacks run while a capture exists, and another thread is serialized.
            capture?.let {
                it.error = "Reentrant paste was rejected; nothing from this paste was sent"
                return@synchronized false
            }
            if (!isCurrent()) {
                rejection = "Terminal changed or closed; the whole paste was rejected"
                return@synchronized false
            }
            if (text.length > MAX_TEXT_UNITS) {
                rejection = "Clipboard text exceeds the bounded paste limit; the whole paste was rejected"
                return@synchronized false
            }
            if (!wellFormedUtf16(text)) {
                rejection = "Clipboard contains incomplete Unicode characters; the whole paste was rejected"
                return@synchronized false
            }
            val current = Capture()
            capture = current
            try {
                produce(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current.error = "Paste preparation failed; nothing from this paste was sent"
            } finally {
                capture = null
            }
            // Pinned paste emits either one body or begin/body/end. Unexpected
            // extra writes fail closed. This is a shape check, not proof of producer
            // identity: callers must use the synchronous pinned paste producer only.
            if (current.error == null && !((current.writes == 1 && !current.firstWasStart) ||
                    (current.writes == 3 && current.firstWasStart && current.lastWasEnd))) {
                current.error = "Unexpected paste output; the whole paste was rejected"
            }
            rejection = current.error
            if (rejection != null) return@synchronized false
            if (!isCurrent()) {
                rejection = "Terminal changed or closed; the whole paste was rejected"
                return@synchronized false
            }
            if (current.size == 0) return@synchronized true
            if (!admit(current.bytes, 0, current.size)) {
                rejection = "Terminal input queue is unavailable or full; the whole paste was rejected"
                return@synchronized false
            }
            true
        }
        rejection?.let { runCatching { onRejected(it) } }
        return accepted
    }

    private fun wellFormedUtf16(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c.isHighSurrogate()) {
                if (i == text.length || !text[i++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }
}

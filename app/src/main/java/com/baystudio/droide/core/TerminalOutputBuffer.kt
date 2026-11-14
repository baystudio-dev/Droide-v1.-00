package com.baystudio.droide.core

/** Bounded terminal tail. Appending output never copies the existing transcript. */
internal class TerminalOutputBuffer(private val capacity: Int) {
    init { require(capacity in 1..1_000_000) }

    // Native terminals also own a pipe command delegate; allocate only when it has UI output.
    private var data: CharArray? = null
    private var start = 0
    private var size = 0

    @Synchronized fun append(text: String) {
        if (text.isEmpty()) return
        val buffer = data ?: CharArray(capacity).also { data = it }
        if (text.length >= capacity) {
            var acceptedStart = terminalTextTailStart(text, capacity)
            // A decoder may deliver a pair in separate appends. If the new chunk
            // exactly fills the buffer, its first unit can pair with the old tail.
            if (acceptedStart == 0 && size > 0 &&
                Character.isHighSurrogate(buffer[(start + size - 1) % capacity]) &&
                Character.isLowSurrogate(text[0])) {
                acceptedStart++
            }
            text.toCharArray(buffer, 0, acceptedStart, text.length)
            start = 0
            size = text.length - acceptedStart
            return
        }
        var overflow = (size + text.length - capacity).coerceAtLeast(0)
        if (overflow > 0 && overflow < size &&
            Character.isHighSurrogate(buffer[(start + overflow - 1) % capacity]) &&
            Character.isLowSurrogate(buffer[(start + overflow) % capacity])) {
            overflow++
        }
        start = (start + overflow) % capacity
        size -= overflow
        val end = (start + size) % capacity
        val first = minOf(text.length, capacity - end)
        text.toCharArray(buffer, end, 0, first)
        if (first < text.length) text.toCharArray(buffer, 0, first, text.length)
        size += text.length
    }

    @Synchronized fun snapshot(): String {
        val buffer = data ?: return ""
        if (size == 0) return ""
        val first = minOf(size, capacity - start)
        return buildString(size) {
            append(buffer, start, first)
            if (first < size) append(buffer, 0, size - first)
        }
    }

    @Synchronized fun clear() {
        start = 0
        size = 0
    }
}

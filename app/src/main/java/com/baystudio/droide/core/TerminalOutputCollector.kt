package com.baystudio.droide.core

/** One decoder per stream preserves UTF-8 across ADB packet boundaries. */
internal class TerminalOutputCollector(capacity: Int, onOutput: (String) -> Unit) {
    private val tail = TailByteBuffer(capacity)
    private val decoder = Utf8StreamDecoder { text -> runCatching { onOutput(text) } }
    private var finished = false
    val output: String get() = tail.utf8()
    val truncated: Boolean get() = tail.truncated

    fun append(bytes: ByteArray) {
        check(!finished) { "Terminal output stream is closed" }
        tail.write(bytes)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 8192, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            decoder.accept(chunk, chunk.size)
            offset = end
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        decoder.finish()
    }
}

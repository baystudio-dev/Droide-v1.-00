package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/** Search-only admission sits below the decoder, including its read-ahead and EOF probes. */
internal class WorkspaceSearchReader(
    private val budget: WorkspaceSearchBudget,
    private val openInput: (File) -> InputStream = { it.inputStream() },
) {
    private val lineBuffer = CharArray(budget.limits.lineChars)
    private val chars = CharArray(4_096)
    private val head = ByteArray(4_096)

    private fun open(file: File, fileLimit: Long): InputStream {
        budget.beforeOpen()
        val input = openInput(file)
        return object : InputStream() {
            private var fileBytes = 0L
            private val single = ByteArray(1)
            override fun read(): Int {
                val n = read(single, 0, 1)
                return if (n < 0) -1 else single[0].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return 0
                budget.checkpoint()
                if (budget.remainingBytes == 0L) budget.stop(WorkspaceSearchStopReason.BYTE_LIMIT)
                if (fileBytes == fileLimit) budget.stop(WorkspaceSearchStopReason.FILE_BYTE_LIMIT)
                val allowed = minOf(length.toLong(), budget.remainingBytes, fileLimit - fileBytes).toInt()
                budget.beforeRead()
                val n = input.read(bytes, offset, allowed)
                if (n == 0) throw IOException("Search input made no progress")
                if (n > 0) {
                    fileBytes += n.toLong()
                    budget.bytesRead(n)
                }
                // A quota is never translated into EOF. Incomplete UTF-8 cannot be flushed as replacement text.
                return n
            }
            override fun close() = input.close()
        }
    }

    fun looksBinary(file: File): Boolean {
        budget.checkpoint()
        if (file.length() == 0L) return false
        val n = open(file, Long.MAX_VALUE).use { it.read(head) }
        if (n <= 0) return false
        var suspicious = 0
        for (i in 0 until n) {
            val byte = head[i].toInt() and 0xff
            if (byte == 0) return true
            if (byte < 0x09 || byte in 0x0e..0x1f) suspicious++
        }
        return suspicious > n / 10
    }

    suspend fun scanFile(
        file: File,
        shouldContinue: () -> Boolean,
        onLine: suspend (Int, String) -> Unit,
    ) {
        val lines = Lines(budget, lineBuffer, shouldContinue, onLine)
        InputStreamReader(open(file, budget.limits.fileBytes), Charsets.UTF_8).use { reader ->
            while (shouldContinue()) {
                budget.checkpoint()
                val n = reader.read(chars)
                if (n < 0) { lines.finish(); return }
                if (n == 0) throw IOException("Search decoder made no progress")
                if (!lines.accept(chars, n)) return
            }
        }
    }

    suspend fun scanDirty(
        content: String,
        shouldContinue: () -> Boolean,
        onLine: suspend (Int, String) -> Unit,
    ) {
        val lines = Lines(budget, lineBuffer, shouldContinue, onLine)
        var offset = 0
        while (offset < content.length && shouldContinue()) {
            budget.checkpoint()
            val n = minOf(chars.size, content.length - offset)
            content.toCharArray(chars, 0, offset, offset + n)
            if (!lines.accept(chars, n)) return
            offset += n
        }
        if (shouldContinue()) lines.finish(includeTrailingEmpty = true)
    }

    private class Lines(
        private val budget: WorkspaceSearchBudget,
        private val line: CharArray,
        private val shouldContinue: () -> Boolean,
        private val onLine: suspend (Int, String) -> Unit,
    ) {

        private var used = 0
        private var lineNumber = 0
        private var afterCr = false
        private var sawCharacters = false
        private var endedWithTerminator = false

        suspend fun accept(chars: CharArray, count: Int): Boolean {
            budget.checkpoint()
            for (i in 0 until count) {
                if (!shouldContinue()) return false
                val char = chars[i]
                sawCharacters = true
                if (afterCr) {
                    afterCr = false
                    if (char == '\n') continue
                }
                if (char == '\r' || char == '\n') {
                    emit()
                    afterCr = char == '\r'
                    endedWithTerminator = true
                } else {
                    if (used == line.size) budget.stop(WorkspaceSearchStopReason.LINE_LENGTH_LIMIT)
                    line[used++] = char
                    endedWithTerminator = false
                }
            }
            return shouldContinue()
        }
        suspend fun finish(includeTrailingEmpty: Boolean = false) {
            budget.checkpoint()
            if (shouldContinue() && (used > 0 || includeTrailingEmpty && (!sawCharacters || endedWithTerminator))) emit()
        }
        private suspend fun emit() {
            budget.beforeLine()
            lineNumber++
            val value = String(line, 0, used)
            used = 0
            onLine(lineNumber, value)
        }
    }
}

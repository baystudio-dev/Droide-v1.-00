package com.baystudio.droide.core

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import java.util.ArrayDeque

internal object TerminalTranscriptSnapshot {
    fun capture(emulator: TerminalEmulator, limit: Int): String {
        require(limit in 1..1_000_000)
        val notice = emulator.outputLimitNotice.take(limit)
        if (notice.length == limit) return notice
        return notice + capture(emulator.screen, emulator.mColumns, limit - notice.length)
    }

    fun capture(screen: TerminalBuffer, columns: Int, limit: Int): String {
        require(columns > 0 && limit in 1..1_000_000)
        val firstRow = -screen.activeTranscriptRows
        val lastRow = screen.activeRows - screen.activeTranscriptRows - 1
        var row = lastRow
        var length = 0
        var trimEnd = true
        var omittedContent = false
        var tailCut = false
        val parts = ArrayDeque<String>()
        while (row >= firstRow && length < limit && !tailCut) {
            val line = screen.getSelectedText(0, row, columns, row)
            val separator = if (row < lastRow && !screen.getLineWrap(row)) "\n" else ""
            var text = line + separator

            if (trimEnd) {
                text = text.trimEnd { it <= ' ' }
                trimEnd = text.isEmpty()
            }
            if (text.isNotEmpty()) {
                val acceptedStart = terminalTextTailStart(text, limit - length)
                if (acceptedStart > 0) {
                    omittedContent = (0 until acceptedStart).any { text[it] > ' ' }
                    // A pair-safe cut may leave one spare unit. Do not fill it with an earlier row: the result must
                    // remain a contiguous tail.
                    tailCut = true
                }
                val accepted = text.substring(acceptedStart)
                parts.addFirst(accepted)
                length += accepted.length
            }
            row--
        }
        val value = parts.joinToString("")
        if (value.firstOrNull()?.let { it > ' ' } == true) return value
        // Determine whether it is document padding or an interior space without copying the discarded
        // prefix into another whole-transcript String.
        while (!omittedContent && row >= firstRow) {
            omittedContent = screen.getSelectedText(0, row, columns, row).any { it > ' ' }
            row--
        }
        return if (omittedContent) value else value.trimStart { it <= ' ' }
    }
}

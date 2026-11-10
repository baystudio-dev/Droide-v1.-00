package com.baystudio.droide.ui

/** One shared work budget for delimiter lookahead. Exhaustion leaves the remaining source literal. */
internal class AgentMarkdownInlineScan(private val source: String) {
    val limit: Long = source.length.toLong() * 4L + 32L
    var inspected: Long = 0L
        private set
    var exhausted: Boolean = false
        private set

    private fun inspect(): Boolean {
        if (inspected >= limit) { exhausted = true; return false }
        inspected++
        return true
    }

    fun find(char: Char, start: Int): Int {
        for (index in start until source.length) {
            if (!inspect()) return -1
            if (source[index] == char) return index
        }
        return -1
    }

    fun runLength(start: Int, char: Char): Int {
        var end = start
        while (end < source.length) {
            if (!inspect() || source[end] != char) break
            end++
        }
        return end - start
    }

    fun findRun(char: Char, length: Int, start: Int): Int {
        var count = 0
        for (index in start until source.length) {
            if (!inspect()) return -1
            count = if (source[index] == char) count + 1 else 0
            if (count == length) return index - length + 1
        }
        return -1
    }

    fun linkTargetEnd(start: Int): Int {
        var depth = 0
        var escaped = false
        for (index in start until source.length) {
            if (!inspect()) return -1
            val char = source[index]
            if (escaped) { escaped = false; continue }
            if (char == '\\') { escaped = true; continue }
            if (char == '(') depth++
            if (char == ')') {
                if (depth == 0) return index
                depth--
            }
        }
        return -1
    }
}

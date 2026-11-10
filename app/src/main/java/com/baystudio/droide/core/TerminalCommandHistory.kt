package com.baystudio.droide.core

internal data class TerminalCommandDraft(val text: String, val selectionStart: Int, val selectionEnd: Int) {
    companion object {
        fun atEnd(text: String) = TerminalCommandDraft(text, text.length, text.length)
    }
}

/** Bounded, in-memory human command history for a command-mode session; never sends shell input. */
internal class TerminalCommandHistory(
    private val maxEntries: Int = 100,
    private val maxTextUnits: Int = 65_536,
) {
    init { require(maxEntries > 0 && maxTextUnits > 0) }
    private val entries = ArrayDeque<String>()
    private var textUnits = 0
    private var index: Int? = null
    private var draft: TerminalCommandDraft? = null
    private var closed = false

    /** Admission is synchronous; a thrown/rejected attempt never enters this history. */
    fun submit(command: String, admit: (String) -> Boolean): Boolean {
        if (!admit(command)) return false
        submitted(command)
        return true
    }

    /** Call only after the human command has been admitted; failed sends must not enter history. */
    @Synchronized fun submitted(command: String) {
        endBrowsing()
        if (closed || command.isBlank() || command.length > maxTextUnits) return
        if (entries.lastOrNull() == command) return
        while (entries.size >= maxEntries || textUnits > maxTextUnits - command.length) {
            textUnits -= entries.removeFirst().length
        }
        entries.addLast(command)
        textUnits += command.length
    }

    @Synchronized fun previous(current: TerminalCommandDraft): TerminalCommandDraft? {
        if (closed || entries.isEmpty()) return null
        val next = index?.let { (it - 1).coerceAtLeast(0) } ?: entries.lastIndex.also { draft = current }
        index = next
        return TerminalCommandDraft.atEnd(entries[next])
    }

    @Synchronized fun next(): TerminalCommandDraft? {
        val current = index ?: return null
        if (current < entries.lastIndex) {
            index = current + 1
            return TerminalCommandDraft.atEnd(entries[current + 1])
        }
        val restored = draft
        endBrowsing()
        return restored
    }

    /** Edits, pane retirement and submission finish browsing; entries remain session-owned. */
    @Synchronized fun endBrowsing() { index = null; draft = null }

    @Synchronized fun close() {
        closed = true
        endBrowsing()
        entries.clear()
        textUnits = 0
    }
}

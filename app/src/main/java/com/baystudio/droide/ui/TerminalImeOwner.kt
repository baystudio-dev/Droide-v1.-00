package com.baystudio.droide.ui

/** UI-thread owner. A connection never becomes current again after replacement or focus loss. */
internal class TerminalImeOwner {
    private var current: Any? = null

    fun invalidate() { current = null }

    fun connect(session: Any, currentSession: () -> Any?, eligible: () -> Boolean): Lease {
        val token = Any()
        current = token
        return Lease(
            valid = { current === token && currentSession() === session && eligible() },
            invalidate = { if (current === token) current = null },
        )
    }

    class Lease internal constructor(
        private val valid: () -> Boolean,
        private val invalidate: () -> Unit,
    ) {
        private var closed = false
        fun isCurrent(): Boolean = !closed && valid()
        fun close() { closed = true; invalidate() }
        fun deliver(action: () -> Boolean): Boolean = isCurrent() && action()

        fun deleteKeys(before: Int, after: Int, backspace: () -> Boolean, forward: () -> Boolean): Boolean {
            if (before < 0 || after < 0 || !isCurrent()) return false
            repeat(TerminalImeProtocol.boundedDeleteCount(before)) { if (!deliver(backspace)) return false }
            repeat(TerminalImeProtocol.boundedDeleteCount(after)) { if (!deliver(forward)) return false }
            return isCurrent()
        }
    }
}

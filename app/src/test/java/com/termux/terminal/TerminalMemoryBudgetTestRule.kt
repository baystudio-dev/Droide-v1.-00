package com.termux.terminal

import org.junit.rules.ExternalResource

/** Test-only process allocator isolation. Production retention never depends on forced GC. */
class TerminalMemoryBudgetTestRule : ExternalResource() {
    private val field = TerminalMemoryBudget::class.java.getDeclaredField("owners").apply { isAccessible = true }
    private var previous = emptyMap<TerminalEmulator, Boolean>()

    @Suppress("UNCHECKED_CAST")
    private fun registry() = field.get(null) as java.util.WeakHashMap<TerminalEmulator, Boolean>

    override fun before() = synchronized(TerminalMemoryBudget::class.java) {
        previous = HashMap(registry())
        registry().clear()
    }

    override fun after() = synchronized(TerminalMemoryBudget::class.java) {
        registry().clear()
        registry().putAll(previous)
        previous = emptyMap()
    }
}

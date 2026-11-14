package com.baystudio.droide.core

import com.termux.terminal.TerminalSession
import java.util.function.BiConsumer

/** Historical prebuilt engines remain usable, but cannot advertise raw recording without the source hook. */
internal object TerminalRawOutputBridge {
    private val setter = runCatching { TerminalSession::class.java.getMethod("setOutputObserver", BiConsumer::class.java) }.getOrNull()
    val available: Boolean get() = setter != null
    fun attach(session: TerminalSession, output: (ByteArray, Int) -> Unit): Boolean {
        val method = setter ?: return false
        method.invoke(session, BiConsumer<ByteArray, Int> { bytes, count -> output(bytes, count) })
        return true
    }
}

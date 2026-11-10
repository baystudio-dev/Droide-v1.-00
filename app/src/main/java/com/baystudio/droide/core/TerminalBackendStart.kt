package com.baystudio.droide.core

/** Fall back only before a native session is published or can receive user input. */
internal object TerminalBackendStart {
    fun start(native: () -> ITerminalSession, commandPipe: (NativePtyUnavailableException) -> ITerminalSession): ITerminalSession =
        try { native() } catch (failure: NativePtyUnavailableException) { commandPipe(failure) }
}

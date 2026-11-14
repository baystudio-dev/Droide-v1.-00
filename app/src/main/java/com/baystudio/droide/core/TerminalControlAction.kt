package com.baystudio.droide.core

internal enum class TerminalControlAction(val label: String) {
    INTERRUPT("Send Ctrl+C"), STOP_AGENT("Stop agent command"), RESTART_PIPE("Restart command shell");
    companion object {
        fun forSession(session: ITerminalSession): TerminalControlAction = when {
            session is NativePtySessionHandle && session.agentPtyActive -> STOP_AGENT
            session is TerminalSession -> RESTART_PIPE
            else -> INTERRUPT
        }
    }
}

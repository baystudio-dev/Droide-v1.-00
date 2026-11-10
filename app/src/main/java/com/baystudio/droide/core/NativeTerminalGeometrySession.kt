package com.baystudio.droide.core

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow

interface NativeTerminalGeometrySession {
    val geometry: StateFlow<TerminalGeometrySnapshot>
    fun reportViewport(owner: TerminalSession, viewport: TerminalViewportGeometry)
}

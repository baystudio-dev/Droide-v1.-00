package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface TerminalCpuProtectionSession {
    val cpuProtection: StateFlow<HumanTerminalCpuProtection.Status>
}

internal fun humanTerminalCpuProtection(context: Context, stop: (Long, Long) -> Unit,
    warn: (String) -> Unit): HumanTerminalCpuProtection {
    val application = context.applicationContext
    return HumanTerminalCpuProtection(acquire = { acquired, lost, cancel ->
        val handle = DevelopmentForegroundService.startHumanCommand(application, acquired, lost, cancel)
        handle?.let { { DevelopmentForegroundService.finish(application, it) } }
    }, stop = stop, warn = warn)
}

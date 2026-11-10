package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A debugger may launch only after the selected project's build prerequisite succeeds. */
internal object ProjectDebugTaskGate {
    suspend fun <T> start(preLaunchTask: String?, execute: suspend (String) -> TaskResult, launch: suspend () -> T): T {
        preLaunchTask?.let { label ->
            require(label.isNotBlank()) { "Invalid debug preLaunchTask" }
            val result = execute(label)
            check(result.exitCode == 0 && !result.timedOut) {
                "Debug prerequisite '$label' failed (exit ${result.exitCode}${if (result.timedOut) ", timeout" else ""})\n${result.output.takeLast(4_000)}"
            }
        }
        currentCoroutineContext().ensureActive()
        return launch()
    }
}

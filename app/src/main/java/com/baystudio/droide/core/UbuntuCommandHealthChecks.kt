package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Short command probes share one guest preparation; this does not bound installs or builds. */
internal object UbuntuCommandHealthChecks {
    const val TIMEOUT_MS = 60_000L
    const val OUTPUT_BYTES = 64_000

    suspend fun verify(
        commands: Map<String, List<String>>,
        prepare: suspend () -> Unit,
        execute: suspend (List<String>, Int, Long) -> ExecResult,
        progress: (String) -> Unit = {},
    ) {
        currentCoroutineContext().ensureActive()
        if (commands.isEmpty()) return
        prepare()
        for ((name, command) in commands) {
            currentCoroutineContext().ensureActive()
            progress("Checking $name…\n")
            val result = execute(command, OUTPUT_BYTES, TIMEOUT_MS)
            check(result.exitCode == 0 && !result.timedOut) {
                "$name failed its Ubuntu health check: ${result.output.takeLast(2_000)}"
            }
        }
    }
}

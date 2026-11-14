package com.baystudio.droide.core

import java.io.File
import java.io.IOException

/** Validate known bad inputs before any native creation is entered. This is NOT a
 * claim that ProcessBuilder.start is atomic: Android can fail initializing streams
 * after fork. All failures once start is called retain the existing unknown-custody
 * handling. Concurrent mutation after this check does not weaken that handling.
 */
internal object NativeProcessStartPreflight {
    fun validate(builder: ProcessBuilder) {
        val cwd = builder.directory()
        if (cwd != null && !cwd.isDirectory) throw IOException("Process working directory is unavailable: $cwd")
        val command = builder.command().firstOrNull()
            ?: throw IOException("Process executable is missing")
        val executable = File(command)
        // Bare/relative commands retain the platform's own search semantics. Never
        // pretend that the app's PATH inventory is proof of native no-start.
        if (executable.isAbsolute) {
            if (!executable.exists()) throw IOException("Process executable is not installed: $command")
            if (executable.isDirectory || !executable.canExecute()) {
                throw IOException("Process executable is not executable: $command")
            }
        }
    }
}

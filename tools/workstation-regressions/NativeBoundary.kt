package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream

// Native/Android operations are not exercised by this host harness.
internal object LocalExecutionSubstrate {
    fun localRoot(): String = error("Android context is outside host scope")
    fun requireSafeLocalPath(path: String) { require(File(path).isAbsolute) }
}
internal object DeviceBridgeManager {
    fun remoteRoot() = "/data/local/tmp/droide"
    fun requireSafeRemotePath(path: String) { require(File(path).isAbsolute) }
    fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
internal data class ExecResult(val exitCode: Int, val output: String, val timedOut: Boolean)
internal object LocalProcessSupervisor {
    suspend fun capture(argv: List<String>, cwd: File, maxOutputBytes: Int, timeoutMs: Long, input: (OutputStream) -> Unit): ExecResult =
        error("Android toybox execution is outside host scope")
}

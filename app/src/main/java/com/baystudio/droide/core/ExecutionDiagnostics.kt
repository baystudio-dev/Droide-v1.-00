package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded launch metadata only; arguments, stdin, lease tokens and credential values are never retained. */
internal object ExecutionDiagnostics {
    data class Launch(val id: Long, val workspace: String, val owner: String, val timeMs: Long,
        val cwd: String, val program: String, val shell: String, val origin: String,
        val environment: Map<String, String>, val omittedVariables: Int) {
        fun describe(): String = buildString {
            appendLine("Launch #$id · $owner · ${java.util.Date(timeMs)}")
            appendLine("cwd: $cwd\nprogram: $program\nshell: $shell\nenvironment source: $origin")
            environment.forEach { (key, value) -> appendLine("$key=$value") }
            append("$omittedVariables other environment values omitted. Launch snapshot; live shell exports may differ.")
        }
    }
    private val ids = AtomicLong()
    private val mutable = MutableStateFlow<List<Launch>>(emptyList())
    val launches = mutable.asStateFlow()
    private val visibleKeys = setOf("PATH", "HOME", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT",
        "ANDROID_NDK_HOME", "VIRTUAL_ENV", "CONDA_PREFIX", "GOPATH", "GOROOT", "SHELL", "LANG", "TERM", "COLORTERM")

    @Synchronized fun record(workspace: File, owner: String, cwd: String, spec: TerminalLaunchSpec,
        program: String = spec.shell, overrides: Map<String, String> = emptyMap()): Launch {
        val envIndex = spec.prefix.indexOfLast { it == "/usr/bin/env" }
        val environment = if (envIndex >= 0 && spec.prefix.getOrNull(envIndex + 1) == "-i") {
            spec.prefix.drop(envIndex + 2).mapNotNull { field ->
                val at = field.indexOf('='); if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
            }.toMap() + overrides
        } else System.getenv().filterKeys { it in visibleKeys } + spec.environment + overrides
        val visible = environment.filterKeys { it in visibleKeys }.toSortedMap().mapValues { display(it.value) }
        val launch = Launch(ids.incrementAndGet(), workspace.absolutePath, display(owner), System.currentTimeMillis(),
            display(cwd), display(program), display(spec.shell), spec.environmentOrigin, visible, environment.size - visible.size)
        mutable.value = (mutable.value + launch).takeLast(32)
        return launch
    }

    private fun display(value: String): String = value.take(8192)
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
        .replace(Regex("(?i)(https?://)[^/@\\s]+@"), "$1[redacted]@")
}

internal interface TerminalDiagnosticsSession {
    val diagnosticsWorkspace: File
    val recording: TerminalOutputRecording
    val recordingSupported: Boolean get() = true
}

package com.baystudio.droide.core

/** Hard inherited per-process limits and separately sampled group limits are not a sandbox. */
data class ElpResourcePolicy private constructor(val health: Boolean) {
    val limits get() = ProcessResourceLimits(cpuSeconds = if (health) 60 else 600,
        fileSizeKiB = 256 * 1024, openFiles = 512, processes = 256, virtualMemoryKiB = 4 * 1024 * 1024)
    fun shellPrefix(): List<String> {
        val values = linkedMapOf("c" to 0, "t" to limits.cpuSeconds, "f" to limits.fileSizeKiB,
            "n" to limits.openFiles, "u" to limits.processes, "v" to limits.virtualMemoryKiB)
        val script = "set -eu; umask 077; " + values.entries.joinToString("; ") { (key, value) ->
            "ulimit -S$key $value; ulimit -H$key $value; [ \"\$(ulimit -S$key)\" = $value ]; [ \"\$(ulimit -H$key)\" = $value ]"
        } + "; exec /usr/bin/env -u PWD -u SHLVL -u _ \"\$@\""
        return listOf("/bin/bash", "-c", script, "droide-elp")
    }
    companion object {
        val ANALYSIS = ElpResourcePolicy(false)
        val HEALTH = ElpResourcePolicy(true)
        const val PREPARATION_MS = 180_000L
        const val INSTALL_MS = 900_000L
        const val HEALTH_MS = 90_000L
        const val METADATA_MS = 20_000L
        const val PYTHON_MS = 5_000L
        const val PREFLIGHT_MS = 20_000L
        const val PROBE_MS = 8_000L
        const val INITIALIZE_MS = 40_000L
        const val REPLAY_MS = 30_000L
        const val FEATURE_MS = 15_000L
        const val FEATURE_RETIRE_MS = 5_000L
        const val RETIRE_MS = 10_000L
        const val SESSION_MS = 3_600_000L
        const val MONITOR_MS = 250L
        const val GROUP_MEMBERS_MAX = 64
        const val RSS_BYTES_MAX = 1_610_612_736L
        const val PROC_SCAN_MAX = 131_072
        const val PRIVATE_ENTRIES_MAX = 4096
        const val PRIVATE_BYTES_MAX = 268_435_456L
        const val FRAME_BYTES_MAX = 8_388_608
        const val QUEUED_BYTES_MAX = 16_777_216
        const val STDERR_BYTES_MAX = 65_536
    }
}

/** One monotonic total; subordinate phases cannot reset their caller's budget. */
class ElpDeadline private constructor(val endNanos: Long, private val now: () -> Long) {
    val expired get() = endNanos - now() <= 0
    fun remainingMillis(): Long = ((endNanos - now()).coerceAtLeast(0) / 1_000_000)
    fun check() { check(!expired) { "ELP absolute operation deadline exhausted" } }
    fun limited(ms: Long): ElpDeadline { require(ms in 0..3_600_000); return ElpDeadline(minOf(endNanos, now() + ms * 1_000_000), now) }
    fun reserve(ms: Long): ElpDeadline { require(ms in 0..3_600_000); return ElpDeadline(endNanos - ms * 1_000_000, now) }
    companion object {
        fun after(ms: Long, now: () -> Long = System::nanoTime): ElpDeadline { require(ms in 1..3_600_000); return ElpDeadline(now() + ms * 1_000_000, now) }
        fun at(nanos: Long, now: () -> Long = System::nanoTime) = ElpDeadline(nanos, now)
        fun start() = after(ElpResourcePolicy.PREPARATION_MS)
        fun retirement() = after(ElpResourcePolicy.RETIRE_MS)
        fun feature() = after(ElpResourcePolicy.FEATURE_MS)
    }
}

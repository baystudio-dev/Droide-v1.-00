package com.baystudio.droide.core

/** Separate policy: per-process limits and observed ordinary-group retirement, not aggregate kernel containment. */
data class CSharpResourcePolicy private constructor(val health: Boolean) {
    val cpuSeconds: Int get() = if (health) 60 else 600
    val addressSpaceKiB: Long get() = 67_108_864L
    val fileSizeBytes: Long get() = 67_108_864L
    val openFiles: Int get() = 256
    val realUidTasks: Int get() = 256
    val gcHeapBytes: Long get() = 536_870_912L
    val helperArguments: List<String> get() = listOf("--max-old-space-size=64")
    val dotnetEnvironment: Map<String, String> get() = mapOf("DOTNET_GCHeapHardLimit" to "0x20000000", "DOTNET_gcServer" to "0")

    /** Bash uses 1024-byte file units. Set soft AND hard, then read every value back before exec. */
    fun shellPrefix(): List<String> {
        val controls = linkedMapOf("c" to 0L, "t" to cpuSeconds.toLong(), "f" to fileSizeBytes / 1024,
            "n" to openFiles.toLong(), "u" to realUidTasks.toLong(), "v" to addressSpaceKiB)
        val script = "set -eu; umask 077; " + controls.entries.joinToString("; ") { (flag, value) ->
            "ulimit -S$flag $value; ulimit -H$flag $value; [ \"\$(ulimit -S$flag)\" = $value ]; [ \"\$(ulimit -H$flag)\" = $value ]"
        } + "; exec \"\$@\""
        return listOf("/bin/bash", "-c", script, "droide-csharp")
    }
    companion object {
        val ANALYSIS = CSharpResourcePolicy(false)
        val HEALTH = CSharpResourcePolicy(true)
        const val GROUP_MEMBERS_MAX = 32
        const val OBSERVED_RSS_BYTES_MAX = 1_610_612_736L
        const val SCAN_BOUND = 131_072
        const val MONITOR_MS = 100L
        const val PREPARATION_MS = 120_000L
        const val INITIALIZE_MS = 60_000L
        const val FEATURE_MS = 15_000L
        const val HEALTH_TOTAL_MS = 90_000L
        const val SHUTDOWN_GRACE_MS = 2_000L
        const val RETIRE_MS = 10_000L
    }
}

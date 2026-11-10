package com.baystudio.droide.core

/** TOML only: virtual address space, V8 old-space and sampled group RSS are distinct controls. */
data class TomlResourcePolicy private constructor(val health:Boolean) {
    val cpuSeconds:Int get()=if(health)60 else 600
    val addressSpaceKiB:Long get()=16_777_216L
    val fileSizeBytes:Long get()=67_108_864L
    val openFiles:Int get()=256
    val realUidTasks:Int get()=256
    val nodeArguments:List<String> get()=listOf("--disable-wasm-trap-handler","--max-old-space-size=256")
    fun shellPrefix():List<String> {
        val limits=linkedMapOf("c" to 0L,"t" to cpuSeconds.toLong(),"f" to fileSizeBytes/1024,"n" to openFiles.toLong(),"u" to realUidTasks.toLong(),"v" to addressSpaceKiB)
        val script="set -eu; umask 077; "+limits.entries.joinToString("; "){(k,v)->"ulimit -S$k $v; ulimit -H$k $v; [ \"\$(ulimit -S$k)\" = $v ]; [ \"\$(ulimit -H$k)\" = $v ]"}+"; exec /usr/bin/env -u PWD -u SHLVL -u _ \"\$@\""
        return listOf("/bin/bash","-c",script,"droide-toml")
    }
    companion object {
        val ANALYSIS=TomlResourcePolicy(false);val HEALTH=TomlResourcePolicy(true)
        const val GROUP_MEMBERS_MAX=8
        const val OBSERVED_RSS_BYTES_MAX=805_306_368L
        const val MONITOR_MS=250L
        const val SCAN_BOUND=131_072
        const val INITIALIZE_MS=30_000L
        const val CONFIGURATION_MS=10_000L
        const val FEATURE_MS=15_000L
        const val HEALTH_TOTAL_MS=90_000L
        const val SHUTDOWN_GRACE_MS=2_000L
        const val RETIRE_MS=10_000L
    }
}

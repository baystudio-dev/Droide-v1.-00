package com.baystudio.droide.core

import java.util.UUID


internal data class RemoteProcessLease private constructor(
    val pidFile: String,
    private val token: String,
    private val local: Boolean = false,
) {
    fun wrap(command: String): String = wrapUsing(command, "/system/bin/sh", "/system/bin/toybox setsid")

    internal fun wrapForHostTest(command: String, host: TerminalHostLayoutTestAuthority): String =
        wrapUsing(command, host.shell, host.setsid)

    private fun wrapUsing(command: String, programShell: String, programSetsid: String): String {
        require(command.isNotBlank() && command.length <= 256_000 && '\u0000' !in command) { "Invalid leased command" }
        if (local) LocalExecutionSubstrate.requireSafeLocalPath(pidFile) else DeviceBridgeManager.requireSafeRemotePath(pidFile)
        require(token.matches(Regex("[0-9a-f]{20}"))) { "Invalid process-lease token" }
        val runDir = pidFile.substringBeforeLast('/')
        val inner = buildString {
            append("export DROIDE_PROCESS_LEASE=").append(DeviceBridgeManager.shellQuote(token)).append("; ")
            append("echo \"${'$'}${'$'}:").append(token).append("\" > ")
                .append(DeviceBridgeManager.shellQuote(pidFile)).append("; ")
            append("exec $programShell -c ").append(DeviceBridgeManager.shellQuote(command))
        }
        return buildString {
            append("set -eu; mkdir -p ").append(DeviceBridgeManager.shellQuote(runDir)).append("; ")
            append("rm -f ").append(DeviceBridgeManager.shellQuote(pidFile)).append("; ")
            append("exec $programSetsid $programShell -c ")
                .append(DeviceBridgeManager.shellQuote(inner))
        }
    }

    /** Only for the counted expanded local path; leaves legacy wrap untouched. */
    internal fun countedLocalWrap(command: CountedShellArgv.Node): CountedShellArgv.Node =
        countedLocalWrapUsing(command, "/system/bin/sh", "/system/bin/toybox setsid")

    internal fun countedLocalWrapForHostTest(command: CountedShellArgv.Node, host: TerminalHostLayoutTestAuthority): CountedShellArgv.Node =
        countedLocalWrapUsing(command, host.shell, host.setsid)

    private fun countedLocalWrapUsing(command: CountedShellArgv.Node, programShell: String, programSetsid: String): CountedShellArgv.Node {
        require(local) { "Counted shell wrapper requires local lease" }
        require(command.stats.chars > 0) { "Empty leased command" }
        LocalExecutionSubstrate.requireSafeLocalPath(pidFile)
        require(token.matches(Regex("[0-9a-f]{20}"))) { "Invalid process-lease token" }
        val runDir=pidFile.substringBeforeLast('/')
        fun literal(value:String)=CountedShellArgv.Node.literal(value)
        val inner=CountedShellArgv.Node.concat(listOf(
            literal("export DROIDE_PROCESS_LEASE=" + DeviceBridgeManager.shellQuote(token) + "; " +
                "echo \"${'$'}${'$'}:" + token + "\" > " + DeviceBridgeManager.shellQuote(pidFile) + "; " +
                "exec $programShell -c "), CountedShellArgv.Node.quote(command,true)))
        return CountedShellArgv.Node.concat(listOf(literal(
            "set -eu; mkdir -p " + DeviceBridgeManager.shellQuote(runDir) + "; " +
            "rm -f " + DeviceBridgeManager.shellQuote(pidFile) + "; " +
            "exec $programSetsid $programShell -c "),CountedShellArgv.Node.quote(inner,true)))
    }

    val environment: Map<String, String> get() = mapOf("DROIDE_PROCESS_LEASE" to token)

    
    fun recordLocalPid(pid: Int) {
        require(local) { "Only local process leases can register Android-host pids" }
        require(pid > 0) { "Invalid local process pid" }
        LocalExecutionSubstrate.requireSafeLocalPath(pidFile)
        val target = java.io.File(pidFile)
        val parent = target.parentFile ?: error("Local process lease has no parent")
        parent.mkdirs()
        check(parent.isDirectory && !PathSecurity.isSymbolicLink(parent)) { "Local process lease directory is unsafe" }
        check(!PathSecurity.isSymbolicLink(target)) { "Local process lease path is unsafe" }
        val temp = java.io.File(parent, ".${target.name}.${java.util.UUID.randomUUID().toString().take(8)}.tmp")
        check(!PathSecurity.isSymbolicLink(temp)) { "Local process lease temp path is unsafe" }
        try {
            java.io.FileOutputStream(temp).use { output ->
                output.write("$pid:$token\n".toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun cleanupCommand(): String = "rm -f ${DeviceBridgeManager.shellQuote(pidFile)}"

     
    fun aliveCommand(): String {
        if (local) LocalExecutionSubstrate.requireSafeLocalPath(pidFile) else DeviceBridgeManager.requireSafeRemotePath(pidFile)
        val qFile = DeviceBridgeManager.shellQuote(pidFile)
        val qToken = DeviceBridgeManager.shellQuote(token)
        return "set -eu; line=\$(cat $qFile); pid=\${line%%:*}; got=\${line#*:}; " +
            "case \"\$pid\" in ''|*[!0-9]*) exit 1;; esac; " +
            "[ \"\$got\" = $qToken ]; " + ownedProcessShell("\$pid", "\$got")
    }

     
    fun detachedCommand(command: String, logPath: String): String {
        DeviceBridgeManager.requireSafeRemotePath(logPath)
        val parent = logPath.substringBeforeLast('/')
        DeviceBridgeManager.requireSafeRemotePath(parent)
        return "set -eu; mkdir -p ${DeviceBridgeManager.shellQuote(parent)}; " +
            ": > ${DeviceBridgeManager.shellQuote(logPath)}; " +
            "(${wrap(command)}) >> ${DeviceBridgeManager.shellQuote(logPath)} 2>&1 < /dev/null &"
    }

    fun terminateCommand(): String = terminateOneCommand(pidFile, token)

    companion object {
        private const val TOKEN_LENGTH = 20

        fun create(namespace: String): RemoteProcessLease {
            val safe = namespace.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(40)
            require(safe.matches(Regex("[a-z0-9][a-z0-9._-]{0,39}"))) { "Invalid process-lease namespace" }
            val token = UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)
            return RemoteProcessLease("${DeviceBridgeManager.remoteRoot()}/run/$safe-$token.pid", token)
        }

        fun createLocal(namespace: String): RemoteProcessLease {
            require(namespace.matches(Regex("[a-z0-9-]{1,40}")))
            val token = UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)
            return RemoteProcessLease("${LocalExecutionSubstrate.localRoot()}/run/$namespace-$token.pid", token, true)
        }

         
        fun reapAllCommand(): String {
            val runDir = "${DeviceBridgeManager.remoteRoot()}/run"
            DeviceBridgeManager.requireSafeRemotePath(runDir)
            return reapAllInDirectory(runDir)
        }

        fun reapAllLocalCommand(): String {
            val runDir = "${LocalExecutionSubstrate.localRoot()}/run"
            LocalExecutionSubstrate.requireSafeLocalPath(runDir)
            return reapAllInDirectory(runDir, excludeNativePtyLeases = true)
        }

        internal fun reapAllInDirectory(runDir: String, excludeNativePtyLeases: Boolean = false): String {
            val qDir = DeviceBridgeManager.shellQuote(runDir)
            val loop = buildString {
                append("set +e; dir=").append(qDir).append("; [ -d \"${'$'}dir\" ] || exit 0; ")
                if (excludeNativePtyLeases) append("unresolved_native=0; ")
                append("for f in \"${'$'}dir\"/*.pid; do [ -e \"${'$'}f\" ] || [ -L \"${'$'}f\" ] || continue; ")
                if (excludeNativePtyLeases) {
                    // Exclude historical native names before even the symlink deletion branch.
                    // Tokens/procfs do not pin identity across a subsequent numeric signal.
                    append("case \"${'$'}{f##*/}\" in native-terminal-*.pid|agent-native-pty-*.pid) ")
                    append("unresolved_native=${'$'}((unresolved_native + 1)); continue;; esac; ")
                }
                append("[ -L \"${'$'}f\" ] && { rm -f \"${'$'}f\"; continue; }; [ -f \"${'$'}f\" ] || continue; ")
                append("line=${'$'}(cat \"${'$'}f\" 2>/dev/null); pid=${'$'}{line%%:*}; tok=${'$'}{line#*:}; ")
                append("valid=1; case \"${'$'}pid\" in ''|*[!0-9]*) valid=0;; esac; ")
                append("case \"${'$'}tok\" in ''|*[!0-9a-f]*) valid=0;; esac; ")
                append("[ ${'$'}{#tok} -eq $TOKEN_LENGTH ] 2>/dev/null || valid=0; ")
                append("if [ \"${'$'}valid\" = 1 ]; then ")
                append("if owned_process \"${'$'}pid\" \"${'$'}tok\"; then ")
                append(killGroupShell("${'$'}pid", "${'$'}tok"))
                append("fi; kill_token_processes \"${'$'}tok\"; fi; rm -f \"${'$'}f\"; done")
                if (excludeNativePtyLeases) {
                    // Preserve the legacy loop's status while returning the unresolved count.
                    append("; cleanup_status=${'$'}?; printf '")
                    append(NativePtyCleanupReceipt.LEGACY_UNRESOLVED_PREFIX)
                    append("%s\\n' \"${'$'}unresolved_native\"; exit \"${'$'}cleanup_status\"")
                }
            }
            return "owned_process() { " + ownedProcessShell("${'$'}1", "${'$'}2") + "; }; " +
                "kill_token_processes() { " + killTokenProcessesShell("${'$'}1") + "; }; " + loop
        }

        private fun terminateOneCommand(pidFile: String, token: String): String {
            val qFile = DeviceBridgeManager.shellQuote(pidFile)
            val qToken = DeviceBridgeManager.shellQuote(token)
            return buildString {
                append("set +e; f=").append(qFile).append("; expected=").append(qToken).append("; ")
                append("pid=''; got=''; [ -L \"${'$'}f\" ] && { rm -f \"${'$'}f\"; exit 0; }; ")
                append("if [ -r \"${'$'}f\" ]; then line=${'$'}(cat \"${'$'}f\" 2>/dev/null); ")
                append("pid=${'$'}{line%%:*}; got=${'$'}{line#*:}; ")
                append("case \"${'$'}pid\" in ''|*[!0-9]*) pid='';; esac; fi; ")
                append("if [ -n \"${'$'}pid\" ] && [ \"${'$'}got\" = \"${'$'}expected\" ] && ")
                append("(").append(ownedProcessShell("${'$'}pid", "${'$'}expected")).append("); then ")
                append(killGroupShell("${'$'}pid", "${'$'}expected"))
                append("fi; ")
                append(killTokenProcessesShell("${'$'}expected")).append("; ")
                append("rm -f \"${'$'}f\"")
            }
        }


        private fun killTokenProcessesShell(token: String): String = buildString {
            append("for sig in TERM KILL; do matched=0; ")
            append("for e in /proc/[0-9]*/environ; do [ -r \"${'$'}e\" ] || continue; ")
            append("if /system/bin/toybox tr '\\000' '\\n' < \"${'$'}e\" 2>/dev/null | ")
            append("/system/bin/toybox grep -Fqx \"DROIDE_PROCESS_LEASE=$token\"; then ")
            append("p=${'$'}{e#/proc/}; p=${'$'}{p%/environ}; ")
            append("case \"${'$'}p\" in ''|*[!0-9]*) continue;; esac; ")
            append("matched=1; kill -${'$'}sig \"${'$'}p\" 2>/dev/null || true; fi; done; ")
            append("if [ \"${'$'}sig\" = TERM ] && [ \"${'$'}matched\" = 1 ]; then sleep 0.2; fi; done; true")
        }

        private fun ownedProcessShell(pid: String, token: String): String =
            "[ -r /proc/$pid/environ ] && " +
                "/system/bin/toybox tr '\\000' '\\n' < /proc/$pid/environ 2>/dev/null | " +
                "/system/bin/toybox grep -Fqx \"DROIDE_PROCESS_LEASE=$token\""

        /** Only signal a *group* when procfs proves this PID owns its session. */
        private fun isSessionLeaderShell(pid: String): String =
            "( [ -r /proc/$pid/stat ] || exit 1; " +
                "line=\$(cat /proc/$pid/stat 2>/dev/null) || exit 1; " +
                "fields=\${line##*) }; set -- \$fields; " +
                "[ \"\$3\" = \"$pid\" ] && [ \"\$4\" = \"$pid\" ] )"

        private fun signalOwnedShell(pid: String, signal: String): String =
            "if " + isSessionLeaderShell(pid) + "; then " +
                "kill -$signal -\"$pid\" 2>/dev/null || kill -$signal \"$pid\" 2>/dev/null; " +
                "else kill -$signal \"$pid\" 2>/dev/null; fi; "

        private fun killGroupShell(pid: String, token: String): String =
            signalOwnedShell(pid, "TERM") +
                // Token and session leadership must be revalidated after grace.
                "sleep 0.2; if " + ownedProcessShell(pid, token) + "; then " +
                signalOwnedShell(pid, "KILL") + "fi; "
    }
}

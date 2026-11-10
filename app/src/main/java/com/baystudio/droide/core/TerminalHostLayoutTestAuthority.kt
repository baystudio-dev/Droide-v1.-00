package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Internal host-simulation constructor capability. Never obtained from tool arguments/config.
 * Production primary constructor never creates this. It cannot replace the supervisor/factory. */
internal class TerminalHostLayoutTestAuthority(val limits: () -> CountedShellArgv.Limits?) {
    internal val shell = "/bin/sh"
    internal val setsid = listOf("/usr/bin/setsid", "/bin/setsid").firstOrNull { File(it).canExecute() }
        ?: error("Host setsid unavailable")
    private val entries = AtomicInteger()
    val nativeCreationEntries: Int get() = entries.get()
    init {
        check(!File("/system/bin/sh").exists()) { "Host simulation is unavailable on Android layout" }
        check(File(shell).canExecute()) { "Host shell unavailable" }
        check(File("/usr/bin/tr").canExecute() && File("/usr/bin/grep").canExecute()) { "Host cleanup programs unavailable" }
    }
    internal fun recordNativeCreationEntry() { entries.incrementAndGet() }
    internal fun cleanupScript(lease: RemoteProcessLease): String {
        // This script has only generated cleanup logic and validated token/path, NOT user command.
        // Restrict test path so opcode substitution cannot alter quoted path content.
        require(lease.pidFile.matches(Regex("[A-Za-z0-9_./-]+"))) { "Unsupported host fixture path" }
        return lease.terminateCommand().replace("/system/bin/toybox tr ", "/usr/bin/tr ")
            .replace("/system/bin/toybox grep ", "/usr/bin/grep ")
    }
    internal fun terminate(lease: RemoteProcessLease) {
        val cleanup=ProcessBuilder(shell,"-c",cleanupScript(lease))
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        LocalLeaseCleanup.await(cleanup)
    }
}

package com.baystudio.droide.core

import java.io.File

/** Suggestions are separate from raw process output and never retry or mutate a failed command. */
internal object TerminalFailureDiagnostics {
    fun hint(exitCode: Int, output: String, command: String, projectRoot: File? = null): String? {
        if (exitCode == 0) return null
        val tail = output.takeLast(16_384)
        if (tail.contains("Unable to get system boot time", ignoreCase = true)) {
            return "The system process table is unavailable in this environment. Use 'droide process list' for Droide-owned Agent jobs; it does not require ps or procfs boot-time data. Shell-started processes are not included."
        }
        if (tail.contains("EADDRINUSE", ignoreCase = true) || tail.contains("address already in use", ignoreCase = true)) {
            return "A port is occupied. Use 'droide process port PORT' for an Android host loopback bind probe, then inspect 'droide process list'. Stop only a known owned session ID; a port number alone does not identify a safe process to stop."
        }
        if (exitCode == 126 || tail.contains("Permission denied", ignoreCase = true)) {
            // Only a caller with the actual pinned launch cwd may inspect a relative command path.
            // The workspace root may differ after `cd`; generic guidance is safer in that case.
            if (projectRoot != null) {
                val path = runCatching { DroideCli.parse(command).firstOrNull()?.takeIf { it.startsWith("./") } }.getOrNull()
                val diagnostic = path?.let { runCatching { WorkspaceScriptPermissions.inspect(projectRoot, it) }.getOrNull() }
                if (diagnostic != null && !diagnostic.executable) return diagnostic.render()
            }
            return "Permission was denied. Use 'droide file permissions workspace-relative-path' to inspect the intended regular workspace file. Grant an execute bit only after reviewing that specific file; interpreter, mount and Android restrictions can also cause this error. No permission was changed."
        }
        return null
    }
}

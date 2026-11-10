package com.baystudio.droide.core

import java.io.File

/** Shell metadata is a hint, never authority to leave the project or follow guest paths. */
internal object TerminalCommandCwdPolicy {
    fun resolveHostCwd(workspace: File, reported: String?, isNativeHost: Boolean): File {
        if (!isNativeHost || reported.isNullOrEmpty()) return workspace
        if (reported.length > 4_096 || '\u0000' in reported) return workspace
        val candidate = File(reported)
        if (!candidate.isAbsolute) return workspace
        if (candidate.toPath().normalize().startsWith(workspace.absoluteFile.toPath().normalize())) {
            check(candidate.isDirectory) {
                "Terminal directory is no longer available: $reported. Change directory or restart this terminal before running another command."
            }
        }
        if (!candidate.isDirectory) return workspace
        return runCatching {
            if (!PathSecurity.contains(workspace, candidate)) workspace
            else if (PathSecurity.isSymbolicLink(candidate)) workspace
            else candidate.canonicalFile
        }.getOrDefault(workspace)
    }
}

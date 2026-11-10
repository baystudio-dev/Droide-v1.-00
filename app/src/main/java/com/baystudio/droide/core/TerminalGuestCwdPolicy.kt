package com.baystudio.droide.core

import java.io.File

/** Only a managed same-path bind can associate guest cwd with a host workspace directory. */
internal object TerminalGuestCwdPolicy {
    data class ResolvedLaunch(val spec: TerminalLaunchSpec, val workspaceCwd: File? = null)

    fun withObservedCwd(spec: TerminalLaunchSpec, workspace: File, reported: String?): TerminalLaunchSpec =
        resolveLaunch(spec, workspace, reported).spec

    fun resolveLaunch(spec: TerminalLaunchSpec, workspace: File, reported: String?): ResolvedLaunch {
        val prefix = spec.prefix
        if (prefix.isEmpty() || reported.isNullOrBlank()) return ResolvedLaunch(spec)
        val root = runCatching { workspace.canonicalFile }.getOrNull() ?: return ResolvedLaunch(spec)
        val rootPath = root.absolutePath
        // Recognize only the managed -r/-b/-w PRoot shape; custom wrappers fail closed.
        val rootfsIndex = prefix.indexOf("-r")
        val cwdIndex = prefix.indexOf("-w")
        if (rootfsIndex < 0 || rootfsIndex + 1 >= prefix.size ||
            cwdIndex < 0 || cwdIndex + 1 >= prefix.size ||
            rootfsIndex != prefix.lastIndexOf("-r") || cwdIndex != prefix.lastIndexOf("-w")) return ResolvedLaunch(spec)
        if (prefix[rootfsIndex + 1].isBlank() || prefix[cwdIndex + 1] != rootPath) return ResolvedLaunch(spec)
        if (!prefix.windowed(2).any { it[0] == "-b" && it[1] == "$rootPath:$rootPath" }) return ResolvedLaunch(spec)

        val candidate = TerminalCommandCwdPolicy.resolveHostCwd(root, reported, isNativeHost = true)
        val resolvedSpec = if (candidate == root) spec else spec.copy(prefix = prefix.toMutableList().apply {
            this[cwdIndex + 1] = candidate.absolutePath
        })
        return ResolvedLaunch(resolvedSpec, candidate)
    }
}

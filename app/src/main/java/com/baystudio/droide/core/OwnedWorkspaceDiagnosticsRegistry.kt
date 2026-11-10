package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A closed/replaced workspace cannot remove a newer workspace's diagnostic provider. */
object OwnedWorkspaceDiagnosticsRegistry {
    data class Binding internal constructor(internal val workspaceId: String, internal val token: String)
    internal data class Provider(val processes: OwnedProcessDiagnostics, val projectRoot: File)
    private data class Entry(val token: String, val provider: Provider)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun bind(workspaceId: String, processes: OwnedProcessDiagnostics, projectRoot: File): Binding {
        require(workspaceId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid diagnostic workspace identity" }
        require(projectRoot.isDirectory) { "Diagnostic project root is unavailable" }
        val token = UUID.randomUUID().toString()
        entries[workspaceId] = Entry(token, Provider(processes, projectRoot.canonicalFile))
        return Binding(workspaceId, token)
    }

    fun unbind(binding: Binding) {
        entries.computeIfPresent(binding.workspaceId) { _, entry -> if (entry.token == binding.token) null else entry }
    }

    internal fun provider(workspaceId: String?): Provider? = workspaceId?.let { entries[it]?.provider }
}

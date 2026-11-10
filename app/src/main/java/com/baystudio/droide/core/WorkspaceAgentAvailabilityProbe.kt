package com.baystudio.droide.core

/** Presence only; ACP protocol and authentication are checked when the user starts a session. */
internal object WorkspaceAgentAvailabilityProbe {
    suspend fun inspect(resolve: suspend (String) -> String?): List<WorkspaceAgentAvailability> =
        WorkspaceAgentPluginCatalog.entries.map { spec ->
            val executable = runSuspendCatching { resolve(spec.command) }.getOrNull()
            val cli = if (spec.cliCommand == spec.command) executable
                else runSuspendCatching { resolve(spec.cliCommand) }.getOrNull()
            WorkspaceAgentAvailability(spec, executable, cli)
        }
}

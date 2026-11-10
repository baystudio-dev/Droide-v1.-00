package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// It proves presence, never workload success.
internal object AgentEnvironmentProbe {
    private const val MAX_ITEMS = 32
    private const val MAX_NAME_CHARS = 80

    suspend fun run(args: JsonObject, c: AgentTools.Ctx): String {
        val requestedTools = strings(args, "agent_tools")
        val requestedCommands = strings(args, "commands")
        val includePath = args["include_path"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        val resource = (requestedTools + requestedCommands).joinToString(",").take(500).ifBlank { "summary" }
        AgentTools.permissionGateForProbe("read", "environment:$resource", c)?.let { return it }
        // Pin once after admission, before any suspension. All capabilities and PATH belong to this
        // selected tab even if the user switches terminals during the query.
        val terminal = (c.terminal as? ExecutionTerminalRouter)?.bindCommandSession() ?: c.terminal
        val profile = (terminal as? TerminalEnvironmentIdentitySession)?.environmentProfile
        val validCommands = requestedCommands.filter(TerminalCapabilityProbe::validName)
        val probe = TerminalCapabilityProbe.inspect(terminal, validCommands, includePath)

        return buildString {
            append("[DROIDE_ENVIRONMENT_PROBE proof=presence_only workload_executed=false]\n")
            append("workdir=").append(TerminalCapabilityProbe.display(c.workDir.absolutePath)).append('\n')
            append("environment=").append(TerminalEnvironmentReadiness.label(profile)).append(" pinned=true\n")
            append("active_agent_tool_count=").append(c.activeToolNames.size).append('\n')
            requestedTools.forEach { name ->
                val advertised = name in c.activeToolNames
                append("agent_tool=").append(TerminalCapabilityProbe.display(name.take(MAX_NAME_CHARS)))
                    .append(" advertised=").append(advertised)
                    .append(" callable=").append(advertised)
                    .append('\n')
            }
            requestedCommands.forEach { name ->
                if (!TerminalCapabilityProbe.validName(name)) {
                    append("command=").append(TerminalCapabilityProbe.display(name.take(MAX_NAME_CHARS))).append(" available=false reason=invalid_probe_name\n")
                    return@forEach
                }
                val resolved = probe.commands[name]
                append("command=").append(name)
                    .append(" available=").append(if (probe.complete) (resolved != null).toString() else "unknown")
                    .append(" probe_exit=").append(probe.execution?.exitCode)
                if (!probe.complete) append(" reason=").append(if (probe.execution?.timedOut == true) "probe_timeout" else "probe_incomplete")
                if (resolved != null) append(" path=").append(TerminalCapabilityProbe.display(resolved))
                append('\n')
                if (probe.complete && resolved == null) {
                    TerminalEnvironmentReadiness.missing(name, profile)?.let { hint ->
                        append("install_hint=").append(name).append(" automatic=false")
                        hint.managedFamily?.let { append(" family=").append(it) }
                        append(" commands=").append(hint.commands.joinToString(" ; ")).append('\n')
                        append("install_note=").append(hint.detail).append('\n')
                    }
                }
            }
            if (includePath) {
                append("PATH_probe_exit=").append(probe.execution?.exitCode).append('\n')
                if (probe.complete) append("PATH=").append(TerminalCapabilityProbe.display(probe.path.orEmpty())).append('\n')
                else append("PATH_available=unknown\n")
            }
            append("NOTE: paths are bounded diagnostic displays; presence/callability is not proof that a later command or workload succeeded.")
        }.let { if (it.length <= 12_000) it else it.take(11_850) + "\n[DROIDE_ENVIRONMENT_PROBE truncated=true; request fewer commands for complete details]" }
    }

    private fun strings(args: JsonObject, key: String): List<String> =
        runCatching { args[key]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) } }
            .getOrDefault(emptyList())
            .distinct()
            .take(MAX_ITEMS)
}

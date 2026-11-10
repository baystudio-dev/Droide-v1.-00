package com.baystudio.droide.core

import java.io.File

/** Mentionable identities only: no server startup, credentials or full plugin bodies. */
data class AgentMentionTarget(val token: String, val title: String, val detail: String)

internal object AgentMentionCatalog {
    @JvmOverloads
    fun load(workDir: File, plugins: AgentPluginSource, personalRoot: File? = null): List<AgentMentionTarget> {
        val installed = plugins.contributions(workDir).take(AgentPluginContributions.MAX_PLUGIN_COUNT)
        val stableSource = AgentPluginSource { installed }
        return (
            SkillManager(workDir, stableSource, personalRoot).withBundled().invocationCatalog().map {
                AgentMentionTarget("@skill/${it.name}", it.name, "Skill · ${it.sourceLabel} · ${it.description.take(120)}")
            } + installed.map { AgentMentionTarget("@plugin/${it.id}", it.displayName, "Installed plugin · ${it.id}") } +
                McpManager(workDir, stableSource).list().filter { it.enabled }.map {
                    AgentMentionTarget("@mcp/${it.name}", it.name, "MCP server · ${it.source}")
                }
            ).distinctBy { it.token }.sortedBy { it.title.lowercase() }
    }
}

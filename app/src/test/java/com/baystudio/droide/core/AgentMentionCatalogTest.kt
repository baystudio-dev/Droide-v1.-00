package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentMentionCatalogTest {
    @Test fun standardProjectAndPersonalSkillsAreMentionableWithoutTheirBodies() {
        val root = Files.createTempDirectory("skill-mentions-").toFile()
        try {
            val work = root.resolve("work").apply { mkdir() }
            val personal = root.resolve("personal").apply { mkdir() }
            work.resolve(".github/skills/project-skill/SKILL.md").apply {
                parentFile.mkdirs(); writeText(skillMarkdown("project-skill", "PRIVATE_PROJECT_BODY"))
            }
            personal.resolve("user-skill/SKILL.md").apply {
                parentFile.mkdirs(); writeText(skillMarkdown("user-skill", "PRIVATE_USER_BODY"))
            }
            personal.resolve("hidden/SKILL.md").apply {
                parentFile.mkdirs(); writeText(skillMarkdown("hidden", flags = "user-invocable: false\n"))
            }
            val catalog = AgentMentionCatalog.load(work, AgentPluginSource.EMPTY, personal)
            assertEquals(setOf("@skill/project-skill", "@skill/user-skill", "@skill/documents", "@skill/audio", "@skill/image-generation"), catalog.map { it.token }.toSet())
            assertFalse(catalog.toString().contains("PRIVATE_"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun inventoryDoesNotStartServersAndExcludesDisabledConfiguration() {
        val root = Files.createTempDirectory("mentions-").toFile()
        try {
            root.resolve(".droide").mkdirs()
            root.resolve(".droide/mcp.json").writeText("""{"servers":{"active":{"command":"node"},"off":{"command":"node","enabled":false}}}""")
            val entries = AgentMentionCatalog.load(root, AgentPluginSource.EMPTY)
            assertEquals(listOf("@mcp/active"), entries.filter { !it.token.startsWith("@skill/") }.map { it.token })
            assertFalse(McpHealthRegistry.observe(root).value.any { it.state in setOf(McpHealthState.STARTING, McpHealthState.CONNECTED) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun pluginInventoryIsCapturedOnceAndKeepsItsStableIdentity() {
        val root = Files.createTempDirectory("plugin-mentions-").toFile()
        try {
            var reads = 0
            val plugin = AgentPluginContributionRoot("review", "Code review", "1", "Reviewer", AgentPluginFormat.DROIDE, root, "a".repeat(64))
            val source = AgentPluginSource { reads++; listOf(plugin) }
            val entries = AgentMentionCatalog.load(root, source)
            assertEquals(1, reads)
            assertEquals("@plugin/review", entries.single { it.token.startsWith("@plugin/") }.token)
            assertEquals("Code review", entries.single { it.token.startsWith("@plugin/") }.title)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SkillPluginCatalogTest {
    private fun fixture(block: (File, File, AgentPluginContributionRoot) -> Unit) {
        val work = Files.createTempDirectory("droide-skill-work-").toFile()
        val plugin = Files.createTempDirectory("droide-skill-plugin-").toFile()
        try {
            block(work, plugin, AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE,
                plugin, "a".repeat(64)))
        } finally { PathSecurity.deleteTreeNoFollow(work); PathSecurity.deleteTreeNoFollow(plugin) }
    }

    @Test fun pluginRootsNestedSkillsAndCommandFallbackKeepNamespaceAndProvenance() = fixture { work, plugin, record ->
        plugin.resolve("SKILL.md").writeText("---\nname: root-skill\ndescription: Root\n---\nRoot body")
        plugin.resolve("skills/child").mkdirs()
        plugin.resolve("skills/child/SKILL.md").writeText("---\nname: child\n---\nChild body")
        plugin.resolve("commands").mkdir()
        plugin.resolve("commands/review.md").writeText("---\ndescription: Review\n---\nReview body")
        val manager = SkillManager(work, AgentPluginSource { listOf(record) })
        assertEquals(listOf("fixture:root-skill", "fixture:child", "fixture:review"), manager.catalog().map { it.name })
        assertTrue(manager.catalog().all { it.sourceLabel == "plugin:fixture@aaaaaaaaaaaa" })
        assertEquals("Review body", manager.load("fixture:review")!!.body)
        assertNull(manager.load("review"))
        assertEquals(manager.discover().map { SkillSummary(it.name, it.description, it.sourceLabel) }, manager.catalog())
    }

    @Test fun disablingPluginRevokesBothMetadataAndBodyLoading() = fixture { work, plugin, record ->
        plugin.resolve("SKILL.md").writeText("---\nname: test\n---\nBody")
        var enabled = true
        var calls = 0
        val manager = SkillManager(work, AgentPluginSource { calls++; if (enabled) listOf(record) else emptyList() })
        assertEquals(1, manager.catalog().size); assertNotNull(manager.load("fixture:test"))
        enabled = false
        assertTrue(manager.catalog().isEmpty()); assertNull(manager.load("fixture:test"))
        assertEquals(4, calls)
    }

    @Test fun sourceIntegrityRejectionIsNotBypassedByPreviousDiscovery() = fixture { work, plugin, record ->
        val text = "---\nname: test\n---\nBody"
        val file = plugin.resolve("SKILL.md").apply { writeText(text) }
        val manager = SkillManager(work, AgentPluginSource { if (file.readText() == text) listOf(record) else emptyList() })
        assertEquals(1, manager.catalog().size)
        file.writeText(text.replace("Body", "Edit"))
        assertNull(manager.load("fixture:test")); assertTrue(manager.catalog().isEmpty())
    }

    @Test fun changedPluginIdentityAndBodyAreReflectedByTheNextLoad() = fixture { work, plugin, record ->
        val file = plugin.resolve("SKILL.md").apply { writeText("---\nname: test\n---\nBefore") }
        var current = record
        val manager = SkillManager(work, AgentPluginSource { listOf(current) })
        assertEquals("plugin:fixture@aaaaaaaaaaaa", manager.catalog().single().sourceLabel)
        current = record.copy(contentSha256 = "b".repeat(64)); file.writeText(file.readText().replace("Before", "After!"))
        val loaded = manager.load("fixture:test")!!
        assertEquals("plugin:fixture@bbbbbbbbbbbb", loaded.sourceLabel); assertEquals("After!", loaded.body)
    }

    @Test fun pluginMarkdownLimitRemainsStricterThanWorkspaceLimit() = fixture { work, plugin, record ->
        val text = "---\nname: test\n---\n" + "x".repeat(70_000)
        plugin.resolve("SKILL.md").writeText(text)
        work.resolve(".droide/skills/test/SKILL.md").apply { parentFile.mkdirs(); writeText(text) }
        val manager = SkillManager(work, AgentPluginSource { listOf(record) })
        assertEquals(listOf("test"), manager.catalog().map { it.name })
        assertNull(manager.load("fixture:test")); assertNotNull(manager.load("test"))
    }

    @Test fun catalogLimitDoesNotResolvePluginsWhoseEntriesWouldBeOmitted() = fixture { work, _, _ ->
        work.resolve(".droide/skills/test/SKILL.md").apply { parentFile.mkdirs(); writeText("---\nname: test\n---\nBody") }
        var pluginCalls = 0
        val manager = SkillManager(work, AgentPluginSource { pluginCalls++; emptyList() })
        assertEquals(1, manager.catalog(1).size); assertEquals(0, pluginCalls)
        assertNotNull(manager.load("test")); assertEquals(0, pluginCalls)
    }
}

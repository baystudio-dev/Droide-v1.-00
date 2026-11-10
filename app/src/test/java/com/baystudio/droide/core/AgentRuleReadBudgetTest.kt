package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentRuleReadBudgetTest {
    @Test fun plainAndFrontmatterRulesKeepFullBodiesAndMetadata() = fixture { root, plugin, source ->
        rule(plugin, "plain", "Plain body\nTail 🙂")
        rule(plugin, "header", "---\r\nname: Hello World\r\ndescription: 'Description'\r\ntrigger: manual\r\nglobs: [ '*.kt', '*.java' ]\r\n---\r\nBody\r\nTail")
        val manager = AgentRuleManager(root, source)
        assertEquals("Plain body\nTail 🙂", manager.load("fixture:plain")!!.body)
        val value = manager.load("fixture:hello-world")!!
        assertEquals("Description", value.description); assertEquals("Body\nTail", value.body)
        assertEquals(AgentPluginRuleActivation.MANUAL, value.activation)
        assertEquals(listOf("*.kt", "*.java"), value.globs)
        assertEquals("plugin:fixture@aaaaaaaaaaaa", value.sourceLabel)
        assertNull(manager.load("hello-world"))
    }

    @Test fun malformedFrontmatterRemainsPlainText() = fixture { root, plugin, source ->
        val raw = "---\nname: hidden\nNo closing marker"
        rule(plugin, "plain", raw)
        assertEquals(raw, AgentRuleManager(root, source).load("fixture:plain")!!.body)
    }

    @Test fun activationAliasesAndBlockGlobsRemainCompatible() = fixture { root, plugin, source ->
        for (trigger in listOf("always", "always_on", "always-on", "manual", "glob", "globs", "unknown"))
            rule(plugin, trigger, "---\ntrigger: $trigger\nglobs:\n  - '**/*.kt'\n  - '*.java'\n---\nBody $trigger")
        val values = AgentRuleManager(root, source).discover().associateBy { it.name.substringAfter(':') }
        for (trigger in listOf("always", "always_on", "always-on")) assertEquals(AgentPluginRuleActivation.ALWAYS, values[trigger]!!.activation)
        for (trigger in listOf("glob", "globs")) assertEquals(AgentPluginRuleActivation.GLOB, values[trigger]!!.activation)
        assertEquals(AgentPluginRuleActivation.MANUAL, values["manual"]!!.activation)
        assertEquals(AgentPluginRuleActivation.MODEL, values["unknown"]!!.activation)
        assertTrue(values.values.all { it.globs == listOf("**/*.kt", "*.java") })
    }

    @Test fun byteLimitAcceptsExactSizeIncludingMultibyteText() = fixture { root, plugin, source ->
        val file = rule(plugin, "limit", "é".repeat(6_000))
        assertEquals(12_000L, file.length())
        assertEquals("é".repeat(6_000), AgentRuleManager(root, source).load("fixture:limit")!!.body)
        file.appendText("x")
        assertNull(AgentRuleManager(root, source).load("fixture:limit"))
    }

    @Test fun emptyUnsupportedAndSymlinkFilesStayExcluded() = fixture { root, plugin, source ->
        rule(plugin, "empty", "")
        rule(plugin, "blank", "   \n\t")
        val real = rule(plugin, "valid", "Body")
        File(real.parentFile, "unsupported.txt").writeText("Body")
        Files.createSymbolicLink(File(real.parentFile, "link.md").toPath(), real.toPath())
        assertEquals(listOf("fixture:valid"), AgentRuleManager(root, source).discover().map { it.name })
    }

    @Test fun firstValidDuplicateWinsBeforeActivationFiltering() = fixture { root, plugin, source ->
        rule(plugin, "a", "---\nname: same\n---\n   ")
        rule(plugin, "b", "---\nname: same\ntrigger: model\n---\nFirst")
        rule(plugin, "c", "---\nname: same\ntrigger: always\n---\nSecond")
        val manager = AgentRuleManager(root, source)
        assertEquals("First", manager.load("fixture:same")!!.body)
        assertEquals(1, manager.discover().size); assertTrue(manager.alwaysOn().isEmpty())
        assertEquals("First", manager.modelVisible().single().body)
    }

    @Test fun fullBodyApisKeepTheirExistingCapsAndOrdering() = fixture { root, plugin, source ->
        repeat(32) { n -> rule(plugin, "r%02d".format(n), "---\ntrigger: always\n---\nBody $n") }
        val manager = AgentRuleManager(root, source)
        assertEquals(32, manager.discover().size)
        assertEquals((0..23).map { "Body $it" }, manager.alwaysOn().map { it.body })
        assertTrue(manager.modelVisible().isEmpty())
    }

    @Test fun editsDeletionAndRecreationAreVisibleWithoutStaleCache() = fixture { root, plugin, source ->
        val file = rule(plugin, "one", "Before")
        val manager = AgentRuleManager(root, source)
        assertEquals("Before", manager.load("fixture:one")!!.body)
        file.writeText("After!"); assertEquals("After!", manager.load("fixture:one")!!.body)
        assertTrue(file.delete()); assertNull(manager.load("fixture:one"))
        file.writeText("Restored"); assertEquals("Restored", manager.load("fixture:one")!!.body)
    }

    @Test fun disablingOrRejectingPluginRevokesPreviouslyDiscoveredRules() = fixture { root, plugin, _ ->
        val file = rule(plugin, "one", "Verified")
        var enabled = true
        val source = AgentPluginSource { if (enabled && file.readText() == "Verified") listOf(plugin) else emptyList() }
        val manager = AgentRuleManager(root, source)
        assertEquals(1, manager.discover().size)
        enabled = false; assertNull(manager.load("fixture:one"))
        enabled = true; file.writeText("Changed"); assertNull(manager.load("fixture:one"))
    }

    @Test fun zeroLimitDoesNotResolvePluginSources() = fixture { root, _, _ ->
        var calls = 0
        val manager = AgentRuleManager(root, AgentPluginSource { calls++; emptyList() })
        assertTrue(manager.modelVisible(0).isEmpty())
        assertEquals(0, calls)
        assertThrows(IllegalArgumentException::class.java) { manager.modelVisible(97) }
        assertThrows(IllegalArgumentException::class.java) { manager.modelVisible(-1) }
    }

    @Test fun alwaysOnInstructionsStillReachTheProjectBrief() = fixture { root, plugin, source -> runBlocking {
        rule(plugin, "always", "---\ntrigger: always\n---\nALWAYS_RULE_BODY")
        rule(plugin, "manual", "---\ntrigger: manual\n---\nMANUAL_RULE_BODY")
        val brief = AgentProjectInstructions.load(FileRepository(root), root, source)
        assertTrue(brief.contains("ALWAYS_RULE_BODY")); assertFalse(brief.contains("MANUAL_RULE_BODY"))
    } }

    private fun rule(plugin: AgentPluginContributionRoot, name: String, body: String) = plugin.root.resolve("rules/$name.md").apply { writeText(body) }
    private fun fixture(block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("droide-rule-budget-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            val plugin = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            block(root, plugin, AgentPluginSource { listOf(plugin) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentContextCatalogBudgetTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-context-budget-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun skill(root: File, folder: String, name: String, description: String = "Description", body: String = "Body"): File =
        root.resolve(".droide/skills/$folder/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: $name\ndescription: $description\n---\n$body")
        }

    private fun definitions(root: File, plugins: AgentPluginSource = AgentPluginSource.EMPTY, permissions: PermissionEngine? = null) =
        AgentTools.definitionsWithCustom(SkillManager(root, plugins), CustomToolManager(root),
            rules = AgentRuleManager(root, plugins), permissions = permissions)

    private fun function(root: File, name: String = "skill", plugins: AgentPluginSource = AgentPluginSource.EMPTY) =
        definitions(root, plugins).map { it.jsonObject.getValue("function").jsonObject }.first { it["name"] == JsonPrimitive(name) }

    private fun names(fn: JsonObject) = fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject
    private fun description(fn: JsonObject) = fn.getValue("description").jsonPrimitive.content
    private fun assertBounded(fn: JsonObject) = assertTrue("Serialized tool must stay below 16 KiB", fn.toString().toByteArray(Charsets.UTF_8).size <= 16_384)

    @Test fun normalCatalogRetainsExactSortedNamesAndDescriptions() = fixture { root ->
        skill(root, "z", "zeta", "Last"); skill(root, "a", "alpha", "First")
        val fn = function(root)
        assertTrue(description(fn).endsWith("Available: alpha: First; zeta: Last"))
        assertNull(names(fn)["enum"]) // Discovery and newly saved skills must remain callable.
        assertNull(fn.getValue("parameters").jsonObject["required"]) // list does not require a name.
    }

    @Test fun oversizedMetadataCannotInflateTheToolSchema() = fixture { root ->
        skill(root, "oversized", "a".repeat(180_000))
        val fn = function(root)
        assertBounded(fn)
        assertFalse(description(fn).contains("a".repeat(257)))
        assertFalse(description(fn).contains("a".repeat(257)))
        assertNull(names(fn)["enum"])
    }

    @Test fun collidingPrefixesKeepDistinctExactLoadIdentities() = fixture { root ->
        val prefix = "a".repeat(250)
        val first = prefix + "first"; val second = prefix + "second"
        skill(root, "first", first, body = "First body"); skill(root, "second", second, body = "Second body")
        val fn = function(root)
        assertNull(names(fn)["enum"])
        assertTrue(description(fn).contains(first)); assertTrue(description(fn).contains(second))
        assertNull(SkillManager(root).load(prefix))
        assertEquals("First body", SkillManager(root).load(first)!!.body)
        assertEquals("Second body", SkillManager(root).load(second)!!.body)
        runBlocking {
            assertTrue(AgentTools.execute("skill", buildJsonObject { put("name", second) }, agentWorkflowContext(root)).contains("Second body"))
        }
    }

    @Test fun omittedEntryStillRequiresPermissionAtInvocation() = fixture { root ->
        val name = "a".repeat(257); skill(root, "large", name, body = "Private instructions")
        val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", "*", PermEffect.DENY)))
        assertFalse(definitions(root, permissions = deny).any { it.jsonObject["function"]?.jsonObject?.get("name") == JsonPrimitive("skill") })
        runBlocking {
            val result = AgentTools.execute("skill", buildJsonObject { put("name", name) }, agentWorkflowContext(root, permissions = deny))
            assertTrue(result.startsWith("DENIED")); assertFalse(result.contains("Private instructions"))
        }
    }

    @Test fun aggregateBudgetReportsOmissionsWithoutARestrictivePartialEnum() = fixture { root ->
        repeat(80) { n -> skill(root, "s$n", "skill-" + n.toString().padStart(2, '0') + "x".repeat(200), "d".repeat(120)) }
        val fn = function(root)
        assertBounded(fn); assertNull(names(fn)["enum"])
        val listed = Regex("skill-[0-9]{2}x+").findAll(description(fn)).count()
        assertTrue(listed in 1 until 80)
        assertTrue(description(fn).contains("more available via operation=list"))
        assertEquals(80, SkillManager(root).catalog().size)
    }

    @Test fun serializationBudgetIncludesEscapingAndUtf8() = fixture { root ->
        val desc = "😀\t\\\"".repeat(24)
        repeat(80) { n -> skill(root, "s$n", "s$n-" + "x".repeat(220), desc) }
        val fn = function(root)
        assertBounded(fn); assertNull(names(fn)["enum"])
        assertEquals(fn, Json.parseToJsonElement(fn.toString()))
        assertTrue(description(fn).contains("more available via operation=list"))
    }

    @Test fun descriptionTruncationDoesNotSplitASurrogatePair() = fixture { root ->
        skill(root, "unicode", "unicode", "a".repeat(119) + "😀end")
        val text = description(function(root))
        assertFalse(text.any { it.isHighSurrogate() || it.isLowSurrogate() })
        assertTrue(text.endsWith("a".repeat(119)))
    }

    @Test fun oversizedFirstEntryDoesNotHideOrdinaryEntriesAfterIt() = fixture { root ->
        skill(root, "big", "a".repeat(180_000)); skill(root, "normal", "zebra", "Useful")
        val fn = function(root)
        assertBounded(fn); assertTrue(description(fn).contains("zebra: Useful"))
        assertFalse(description(fn).contains("a".repeat(257))); assertNull(names(fn)["enum"])
    }

    @Test fun emptyCatalogAllowsListingWithoutRequiringALoadName() = fixture { root ->
        val fn = function(root)
        assertTrue(description(fn).startsWith("Discover/load reusable SKILL.md instructions on demand"))
        assertEquals(buildJsonObject { put("type", "string"); put("maxLength", 500) }, names(fn))
    }

    @Test fun ordinaryNamesAtDiscoveryBoundaryStayExactAndOversizedNamesAreRejected() = fixture { root ->
        val name = "a".repeat(256)
        skill(root, "boundary", name)
        assertTrue(description(function(root)).contains(name)); assertNotNull(SkillManager(root).load(name))
        skill(root, "boundary", name + "b")
        assertNull(names(function(root))["enum"])
        assertNull(SkillManager(root).load(name + "b")); assertNull(SkillManager(root).load(name))
    }

    @Test fun metadataRefreshKeepsAnOpenNameSchemaAndOtherToolsIdentical() = fixture { root ->
        val before = definitions(root)
        val file = skill(root, "changed", "a".repeat(180_000))
        assertNull(names(function(root))["enum"])
        file.writeText("---\nname: alpha\ndescription: Updated\n---\nBody")
        val after = definitions(root)
        fun otherTools(value: JsonArray) = value.filter { it.jsonObject["function"]?.jsonObject?.get("name") != JsonPrimitive("skill") }
        assertEquals(otherTools(before), otherTools(after))
        assertTrue(description(function(root)).contains("alpha: Updated")); assertNull(names(function(root))["enum"])
        assertFalse(description(function(root)).contains("omitted"))
    }

    @Test fun pluginSkillNamespacesStayExactAndBodyRemainsOnDemand() = fixture { root ->
        val plugin = root.resolve("plugin").apply { mkdir() }
        plugin.resolve("SKILL.md").writeText("---\nname: alpha\ndescription: Plugin\n---\nPLUGIN_PRIVATE_BODY")
        skill(root, "local", "alpha", "Local")
        val source = AgentPluginSource { listOf(AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, plugin, "a".repeat(64))) }
        val fn = function(root, plugins = source)
        assertTrue(description(fn).contains("alpha: Local")); assertTrue(description(fn).contains("fixture:alpha: Plugin")); assertNull(names(fn)["enum"])
        assertFalse(fn.toString().contains("PLUGIN_PRIVATE_BODY"))
        assertEquals("PLUGIN_PRIVATE_BODY", SkillManager(root, source).load("fixture:alpha")!!.body)
    }

    @Test fun sharedRuleCatalogIsBoundedWithoutChangingRuleLoadIdentity() = fixture { root ->
        val records = (0..1).map { p ->
            val plugin = root.resolve("plugin$p").apply { mkdir(); resolve("rules").mkdir() }
            repeat(32) { n -> plugin.resolve("rules/r$n.md").writeText("---\nname: r$n-" + "x".repeat(55) + "\ndescription: " + "😀".repeat(60) + "\n---\nRule body") }
            AgentPluginContributionRoot("plugin$p-" + "p".repeat(55), "Plugin", "1", "", AgentPluginFormat.DROIDE, plugin, "a".repeat(64))
        }
        val source = AgentPluginSource { records }
        val manager = AgentRuleManager(root, source)
        val fn = function(root, "rule", source)
        assertEquals(64, manager.modelVisible().size)
        assertBounded(fn); assertNull(names(fn)["enum"])
        assertTrue(description(fn).contains("entries omitted"))
        assertEquals("Rule body", manager.load(manager.modelVisible().last().name)!!.body)
    }
}

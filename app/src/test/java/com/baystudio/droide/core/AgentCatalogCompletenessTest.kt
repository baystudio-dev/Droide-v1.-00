package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentCatalogCompletenessTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-catalog-completeness-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun skills(root: File, count: Int): List<File> = (0 until count).map { n ->
        val parent = if (n < 100) ".droide" else ".agents"
        root.resolve("$parent/skills/s$n/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: s$n\ndescription: Skill $n\n---\nBody $n")
        }
    }

    private fun rules(root: File, count: Int): Pair<AgentPluginSource, List<File>> {
        val records = (0 until (count + 31) / 32).map { n ->
            val dir = root.resolve("plugin$n").apply { mkdir(); resolve("rules").mkdir() }
            AgentPluginContributionRoot("p$n", "Plugin $n", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
        }
        val files = (0 until count).map { n -> records[n / 32].root.resolve("rules/r$n.md").apply {
            writeText("---\nname: r$n\ndescription: Rule $n\ntrigger: model\n---\nRule body $n")
        } }
        return AgentPluginSource { records } to files
    }

    private fun function(root: File, name: String = "skill", plugins: AgentPluginSource = AgentPluginSource.EMPTY,
                         skills: SkillManager = SkillManager(root, plugins)): JsonObject =
        AgentTools.definitionsWithCustom(skills, CustomToolManager(root), rules = AgentRuleManager(root, plugins))
            .map { it.jsonObject.getValue("function").jsonObject }.first { it["name"] == JsonPrimitive(name) }

    private fun names(fn: JsonObject) = fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject
    private fun description(fn: JsonObject) = fn.getValue("description").jsonPrimitive.content
    private fun assertPartial(fn: JsonObject) {
        assertNull("A display cap must not forbid another valid name", names(fn)["enum"])
        assertTrue(description(fn).contains("Additional entries exist"))
        assertTrue(fn.toString().toByteArray(Charsets.UTF_8).size < 16_384)
    }

    @Test fun skillsAtExactDisplayLimitKeepCompleteEnum() = fixture { root ->
        skills(root, 80)
        val fn = function(root)
        assertEquals((0 until 80).map { "s$it" }.toSet(), names(fn).getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(description(fn).contains("Additional entries"))
    }

    @Test fun skillsBeyondDisplayLimitAreNotForbiddenAndRemainLoadable() = fixture { root ->
        skills(root, 81)
        val manager = SkillManager(root)
        val last = manager.catalog(81).last().name
        val fn = function(root)
        assertPartial(fn)
        assertFalse(description(fn).contains("$last:"))
        assertNotNull(manager.load(last))
        runBlocking {
            val result = AgentTools.execute("skill", buildJsonObject { put("name", last) }, agentWorkflowContext(root))
            assertTrue(result.contains("Body ${last.removePrefix("s")}"))
        }
    }

    @Test fun lookaheadReadsOnlyOneExtraValidMetadataEntry() = fixture { root ->
        skills(root, 99).forEach { it.appendText("x".repeat(100_000)) }
        val reads = mutableMapOf<String, Long>()
        val manager = SkillManager(root, AgentPluginSource.EMPTY) { file ->
            object : FilterInputStream(file.inputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, length).also {
                    if (it > 0) reads[file.path] = (reads[file.path] ?: 0L) + it
                }
            }
        }
        assertPartial(function(root, skills = manager))
        assertEquals(81, reads.size)
        assertTrue(reads.values.all { it <= 512 })
    }

    @Test fun duplicatesAndInvalidFilesDoNotCreateFalseContinuation() = fixture { root ->
        skills(root, 80)
        root.resolve(".droide/skills/duplicate/SKILL.md").apply { parentFile.mkdirs(); writeText("---\nname: s0\n---\nDuplicate") }
        root.resolve(".droide/skills/invalid/SKILL.md").apply { parentFile.mkdirs(); writeText("No metadata") }
        val fn = function(root)
        assertEquals(80, names(fn).getValue("enum").jsonArray.size)
        assertFalse(description(fn).contains("Additional entries"))
    }

    @Test fun completenessRefreshesAfterRemovalAndRecreation() = fixture { root ->
        val files = skills(root, 81)
        assertPartial(function(root))
        val file = files.last(); val bytes = file.readBytes(); assertTrue(file.delete())
        assertEquals(80, names(function(root)).getValue("enum").jsonArray.size)
        file.writeBytes(bytes)
        assertPartial(function(root))
    }

    @Test fun pluginAfterEightyLocalSkillsIsDetectedAndRevocable() = fixture { root ->
        skills(root, 80)
        val plugin = root.resolve("plugin").apply { mkdir(); resolve("SKILL.md").writeText("---\nname: tail\n---\nPlugin body") }
        val record = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, plugin, "a".repeat(64))
        var enabled = true
        val source = AgentPluginSource { if (enabled) listOf(record) else emptyList() }
        assertPartial(function(root, plugins = source))
        assertEquals("Plugin body", SkillManager(root, source).load("fixture:tail")!!.body)
        enabled = false
        assertEquals(80, names(function(root, plugins = source)).getValue("enum").jsonArray.size)
        assertNull(SkillManager(root, source).load("fixture:tail"))
    }

    @Test fun fullAdmissionWindowStillLoadsLastSkillWithoutInventingATotal() = fixture { root ->
        skills(root, 160)
        val manager = SkillManager(root)
        val last = manager.catalog().last().name
        assertEquals(160, manager.catalog().size)
        val fn = function(root)
        assertPartial(fn)
        assertFalse(description(fn).contains("1 entries omitted"))
        assertNotNull(manager.load(last))
    }

    @Test fun rulesAtExactDisplayLimitKeepLegacyDefaultAndCompleteEnum() = fixture { root ->
        val (source, _) = rules(root, 64)
        val manager = AgentRuleManager(root, source)
        assertEquals(64, manager.modelVisible().size)
        val fn = function(root, "rule", source)
        assertEquals(manager.modelVisible().map { it.name }.toSet(), names(fn).getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(description(fn).contains("Additional entries"))
    }

    @Test fun sixtyFifthRuleRemainsLoadableOutsideTheSummary() = fixture { root ->
        val (source, _) = rules(root, 65)
        val manager = AgentRuleManager(root, source)
        assertEquals(64, manager.modelVisible().size)
        val last = manager.discover().last().name
        assertPartial(function(root, "rule", source))
        assertEquals("Rule body 64", manager.load(last)!!.body)
    }

    @Test fun alwaysOnRulesAreFilteredBeforeTestingTheDisplayLimit() = fixture { root ->
        val (source, files) = rules(root, 65)
        files.first().writeText(files.first().readText().replace("trigger: model", "trigger: always"))
        val manager = AgentRuleManager(root, source)
        assertEquals(1, manager.alwaysOn().size)
        val fn = function(root, "rule", source)
        val listed = names(fn).getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(64, listed.size); assertFalse(listed.contains(manager.alwaysOn().single().name))
        assertFalse(description(fn).contains("Additional entries"))
    }

    @Test fun countAndByteOmissionsAreReportedSeparately() = fixture { root ->
        skills(root, 81).forEachIndexed { n, file -> file.writeText("---\nname: s$n-" + "x".repeat(260) + "\n---\nBody") }
        val fn = function(root)
        assertPartial(fn)
        assertTrue(description(fn).contains("80 entries omitted from this summary (size limit)"))
    }

    @Test fun incompleteCatalogDoesNotBypassToolVisibilityPermissions() = fixture { root ->
        skills(root, 81); val (source, _) = rules(root, 65)
        val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", "*", PermEffect.DENY)))
        val definitions = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), rules = AgentRuleManager(root, source), permissions = deny)
        val names = definitions.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        assertFalse(names.contains("skill")); assertFalse(names.contains("rule"))
    }
}

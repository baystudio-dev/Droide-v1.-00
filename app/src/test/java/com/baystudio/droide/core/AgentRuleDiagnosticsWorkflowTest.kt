package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentRuleDiagnosticsWorkflowTest {
    private fun fixture(block: suspend (File, File, AgentPluginSource) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("rule-diagnostics-workflow-").toFile()
        try {
            val plugin = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            val source = AgentPluginSource { listOf(AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, plugin, "a".repeat(64))) }
            block(root, plugin, source)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun rule(plugin: File, name: String, content: String) = plugin.resolve("rules/$name.md").apply { writeText(content) }
    private fun definition(root: File, manager: AgentRuleManager) = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), rules = manager)
        .map { it.jsonObject.getValue("function").jsonObject }.single { it["name"] == JsonPrimitive("rule") }
    private fun description(fn: JsonObject) = fn.getValue("description").jsonPrimitive.content
    private fun enum(fn: JsonObject) = fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject["enum"]
    private fun context(root: File, source: AgentPluginSource, permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))): AgentTools.Ctx {
        val c = agentWorkflowContext(root, permissions = permissions)
        return AgentTools.Ctx(c.files, c.terminal, c.git, c.approvals, c.questions, c.history, c.perms, c.mode,
            c.llm, c.llmConfig, root, c.todos, c.sessionId, c.lsp, pluginSource = source)
    }

    @Test fun invalidRuleIsDisclosedAndCatalogDoesNotAssertCompleteness() = fixture { root, plugin, source ->
        rule(plugin, "bad", "---\nname: !!!\n---\nPRIVATE_BODY")
        rule(plugin, "good", "Valid body")
        val fn = definition(root, AgentRuleManager(root, source))
        assertTrue(description(fn).contains("Rule discovery incomplete: 1"))
        assertTrue(description(fn).contains("fixture:rules/bad.md (invalid_rule)"))
        assertTrue(description(fn).contains("fixture:good")); assertNull(enum(fn))
        assertFalse(description(fn).contains("PRIVATE_BODY"))
    }

    @Test fun emptyBodyIsReportedWithoutAddingInvalidInstructions() = fixture { root, plugin, source ->
        rule(plugin, "empty", "---\nname: blank\ntrigger: always\n---\n  \n")
        val fn = definition(root, AgentRuleManager(root, source))
        assertTrue(description(fn).contains("invalid_rule")); assertNull(enum(fn))
        assertTrue(AgentRuleManager(root, source).alwaysOn().isEmpty())
    }

    @Test fun duplicateNameKeepsFirstRuleButReportsTheSkippedFile() = fixture { root, plugin, source ->
        rule(plugin, "a", "---\nname: same\n---\nFirst")
        rule(plugin, "b", "---\nname: same\n---\nSecond")
        val manager = AgentRuleManager(root, source)
        assertEquals("First", manager.load("fixture:same")!!.body)
        val fn = definition(root, manager)
        assertTrue(description(fn).contains("fixture:rules/b.md (duplicate_name)")); assertNull(enum(fn))
    }

    @Test fun unresolvedNameWithParseFailuresIsNotPresentedAsConfirmedAbsent() = fixture { root, plugin, source ->
        rule(plugin, "broken", "---\nname: !!!\n---\nSECRET")
        val result = AgentTools.execute("rule", buildJsonObject { put("name", "fixture:broken") }, context(root, source))
        assertTrue(result.startsWith("ERROR: Rule unavailable:")); assertTrue(result.contains("absence is not confirmed"))
        assertTrue(result.contains("invalid_rule")); assertFalse(result.contains("SECRET"))
        assertTrue(AgentToolResultSemantics.classify("rule", result, null).isFailure)
    }

    @Test fun initialInstructionsReportPluginFailuresBeforeValidInstructions() = fixture { root, plugin, source ->
        rule(plugin, "a", "---\nname: !!!\ntrigger: always\n---\nPRIVATE_BODY")
        rule(plugin, "good", "---\ntrigger: always\n---\nVALID_GUIDANCE")
        val brief = AgentProjectInstructions.load(FileRepository(root), root, source)
        assertTrue(brief.startsWith("[DROIDE_RULE_DIAGNOSTICS]")); assertTrue(brief.contains("invalid_rule"))
        assertTrue(brief.contains("VALID_GUIDANCE")); assertFalse(brief.contains("PRIVATE_BODY")); assertTrue(brief.length <= 28_000)
    }

    @Test fun diagnosticSpaceNeverSilentlyTruncatesCompleteInitialInstructions() = fixture { root, plugin, source ->
        for (path in listOf("AGENTS.md", ".droide/AGENTS.md", "GEMINI.md")) root.resolve(path).apply { parentFile.mkdirs(); writeText("x".repeat(8_000)) }
        val good = rule(plugin, "good", "---\ntrigger: always\n---\nTail")
        val initial = AgentProjectInstructions.load(FileRepository(root), root, source)
        good.appendText("y".repeat(27_990 - initial.length))
        val complete = AgentProjectInstructions.load(FileRepository(root), root, source)
        assertEquals(27_990, complete.length); assertFalse(complete.contains("incomplete=true"))
        rule(plugin, "bad", "---\nname: !!!\n---\nHidden")
        val brief = AgentProjectInstructions.load(FileRepository(root), root, source)
        assertTrue(brief.startsWith("[DROIDE_RULE_DIAGNOSTICS]")); assertTrue(brief.contains("incomplete=true"))
        assertTrue(brief.contains("total_limit")); assertTrue(brief.length <= 28_000)
    }

    @Test fun fixedRuleRestoresCatalogOnTheSameManagerWithoutStickyWarnings() = fixture { root, plugin, source ->
        val file = rule(plugin, "bad", "---\nname: !!!\n---\nBody")
        val manager = AgentRuleManager(root, source)
        assertTrue(description(definition(root, manager)).contains("incomplete"))
        file.writeText("Repaired body")
        val fn = definition(root, manager)
        assertFalse(description(fn).contains("incomplete")); assertEquals(buildJsonArray { add("fixture:bad") }, enum(fn))
    }

    @Test fun genuinelyMissingRuleKeepsTheExistingError() = fixture { root, _, source ->
        val result = AgentTools.execute("rule", buildJsonObject { put("name", "fixture:missing") }, context(root, source))
        assertEquals("ERROR: rule tidak ada: fixture:missing", result)
    }

    @Test fun deniedRuleNeverEnumeratesThePluginSource() = fixture { root, _, _ ->
        var calls = 0
        val source = AgentPluginSource { calls++; error("must not enumerate") }
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", "*", PermEffect.DENY)))
        val result = AgentTools.execute("rule", buildJsonObject { put("name", "fixture:bad") }, context(root, source, permissions))
        assertTrue(result.startsWith("DENIED")); assertEquals(0, calls)
    }

    @Test fun successfulLoadStillReturnsExactFreshBodyAndProvenance() = fixture { root, plugin, source ->
        rule(plugin, "good", "Instructions")
        val result = AgentTools.execute("rule", buildJsonObject { put("name", "fixture:good") }, context(root, source))
        assertEquals("# Rule fixture:good: good\nSource: plugin:fixture@aaaaaaaaaaaa\nActivation: model\n\nInstructions", result)
    }
}

package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

internal enum class ContributionFamily(val quota: Int, val paths: List<String>) {
    SKILLS(128, listOf("SKILL.md") + (0..63).map { "skills/s%02d/SKILL.md".format(it) } + (0..47).map { "commands/c%02d.md".format(it) }),
    AGENTS(96, (0..31).map { "agents/a%02d.md".format(it) }),
    RULES(96, (0..31).map { "rules/r%02d.md".format(it) }),
    MCP(32, listOf(".mcp.json", "mcp_config.json", "mcp.json")),
    HOOKS(32, listOf("hooks/hooks.json", "hooks.json")),
    TOOLS(32, listOf("tools/tools.json"));

    fun files(source: AgentPluginSource, root: File): List<Pair<AgentPluginContributionRoot, File>> = when (this) {
        SKILLS -> AgentPluginContributions.skillFiles(source, root)
        AGENTS -> AgentPluginContributions.agentFiles(source, root)
        RULES -> AgentPluginContributions.ruleFiles(source, root)
        MCP -> AgentPluginContributions.mcpFiles(source, root)
        HOOKS -> AgentPluginContributions.hookFiles(source, root)
        TOOLS -> AgentPluginContributions.toolFiles(source, root)
    }
}

internal class ContributionFixture(val family: ContributionFamily, count: Int = 32, paths: List<String> = family.paths) : java.io.Closeable {
    class ObservedRoot(file: File) : File(file.path) {
        var resolutions = 0
        override fun getCanonicalFile(): File { resolutions++; return super.getCanonicalFile() }
    }
    val root = Files.createTempDirectory("droide-contribution-").toFile()
    val roots = (0 until count).map { n -> ObservedRoot(root.resolve("plugin$n").apply { mkdir() }) }
    val records = roots.mapIndexed { n, dir ->
        paths.forEach { dir.resolve(it).apply { parentFile!!.mkdirs(); writeText("Body") } }
        AgentPluginContributionRoot("p%02d".format(n), "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
    }
    var sourceCalls = 0
    val source = AgentPluginSource { work -> check(work == root); sourceCalls++; records }
    fun files() = family.files(source, root)
    fun resetObservations() { roots.forEach { it.resolutions = 0 }; sourceCalls = 0 }
    fun names(files: List<Pair<AgentPluginContributionRoot, File>>) = files.map { (plugin, file) ->
        plugin.id + ":" + file.relativeTo(plugin.root).invariantSeparatorsPath
    }
    override fun close() { check(PathSecurity.deleteTreeNoFollow(root)) }
}

class PluginContributionLimitsTest {
    private fun quotaStops(family: ContributionFamily, count: Int = 32) = ContributionFixture(family, count).use { f ->
        f.resetObservations()
        val found = f.files()
        val needed = (family.quota + family.paths.size - 1) / family.paths.size
        val visited = f.roots.count { it.resolutions > 0 }
        assertEquals(family.quota, found.size)
        assertEquals("Visited roots for $family", needed, visited)
        assertEquals(1, f.sourceCalls)
    }

    @Test fun skillsStopAfterTheAggregateQuota() = quotaStops(ContributionFamily.SKILLS)
    @Test fun profilesStopAfterTheAggregateQuota() = quotaStops(ContributionFamily.AGENTS)
    @Test fun rulesStopAfterTheAggregateQuota() = quotaStops(ContributionFamily.RULES)
    @Test fun mcpFilesStopAfterTheAggregateQuota() = quotaStops(ContributionFamily.MCP)
    @Test fun hookFilesStopAfterTheAggregateQuota() = quotaStops(ContributionFamily.HOOKS)
    @Test fun toolFilesStopForAnOversizedCustomSource() = quotaStops(ContributionFamily.TOOLS, 40)

    @Test fun allFamiliesKeepTheSameOrderedPrefixAndPublicCaps() {
        for (family in ContributionFamily.entries) ContributionFixture(family, 4).use { f ->
            val expected = f.records.flatMap { plugin -> family.paths.map { plugin.id + ":" + it } }.take(family.quota)
            assertEquals(family.name, expected, f.names(f.files()))
        }
    }

    @Test fun ruleFileOrderingAndMixedCaseExtensionStayCompatible() = ContributionFixture(ContributionFamily.RULES, 1, emptyList()).use { f ->
        val dir = f.roots.single().resolve("rules").apply { mkdir() }
        for (name in listOf("z.MD", "a.md", "middle.md")) dir.resolve(name).writeText("Body")
        assertEquals(listOf("p00:rules/a.md", "p00:rules/middle.md", "p00:rules/z.MD"), f.names(f.files()))
    }

    @Test fun underfilledInventoriesStillVisitAllRequiredRoots() = ContributionFixture(ContributionFamily.RULES, 10, listOf("rules/one.md")).use { f ->
        f.resetObservations()
        assertEquals(10, f.files().size)
        assertEquals(10, f.roots.count { it.resolutions > 0 })
    }

    @Test fun invalidEntriesDoNotConsumeTheAggregateQuota() = ContributionFixture(ContributionFamily.RULES, 5).use { f ->
        f.roots.first().resolve("rules").listFiles()!!.forEach { it.writeText("") }
        val actual = f.files()
        assertEquals(96, actual.size)
        assertEquals(setOf("p01", "p02", "p03"), actual.map { it.first.id }.toSet())
    }

    @Test fun perPluginRuleAndProfileLimitsRemainThirtyTwo() {
        for (family in listOf(ContributionFamily.RULES, ContributionFamily.AGENTS)) ContributionFixture(family, 1).use { f ->
            val dir = if (family == ContributionFamily.RULES) "rules" else "agents"
            repeat(40) { f.roots.single().resolve("$dir/z%02d.md".format(it)).writeText("Body") }
            val actual = f.files()
            assertEquals(32, actual.size)
            assertTrue(actual.none { it.second.name.startsWith("z") })
        }
    }

    @Test fun perPluginSkillAndCommandLimitsKeepRootSkillFirst() = ContributionFixture(ContributionFamily.SKILLS, 1).use { f ->
        for (n in 64..69) f.roots.single().resolve("skills/s%02d/SKILL.md".format(n)).apply { parentFile!!.mkdirs(); writeText("Body") }
        for (n in 48..59) f.roots.single().resolve("commands/c%02d.md".format(n)).writeText("Body")
        assertEquals(113, f.files().size)
        assertEquals(ContributionFamily.SKILLS.paths.map { "p00:$it" }, f.names(f.files()))
    }

    @Test fun ruleByteAdmissionKeepsTheExactBoundary() = ContributionFixture(ContributionFamily.RULES, 1, listOf("rules/one.md")).use { f ->
        val file = f.roots.single().resolve("rules/one.md")
        file.writeText("x".repeat(12_000)); assertEquals(1, f.files().size)
        file.appendText("x"); assertTrue(f.files().isEmpty())
        file.writeText(""); assertTrue(f.files().isEmpty())
    }

    @Test fun skillMarkdownAdmissionKeepsTheExactBoundary() = ContributionFixture(ContributionFamily.SKILLS, 1, listOf("SKILL.md")).use { f ->
        val file = f.roots.single().resolve("SKILL.md")
        file.writeText("x".repeat(65_536)); assertEquals(1, f.files().size)
        file.appendText("x"); assertTrue(f.files().isEmpty())
    }

    @Test fun directToolPreferenceAndFallbackStayCompatible() = ContributionFixture(ContributionFamily.TOOLS, 1).use { f ->
        f.roots.single().resolve("tools.json").writeText("Fallback")
        assertEquals(listOf("p00:tools/tools.json"), f.names(f.files()))
        f.roots.single().resolve("tools/tools.json").writeText("")
        assertEquals(listOf("p00:tools.json"), f.names(f.files()))
    }

    @Test fun mcpAndHookPathPrecedenceStaysCompatible() {
        for (family in listOf(ContributionFamily.MCP, ContributionFamily.HOOKS)) ContributionFixture(family, 1).use { f ->
            assertEquals(family.paths.map { "p00:$it" }, f.names(f.files()))
        }
    }

    @Test fun everyLookupResolvesAFreshVerifiedSourceAndFreshFileList() = ContributionFixture(ContributionFamily.RULES, 1, listOf("rules/one.md")).use { f ->
        var enabled = true
        var calls = 0
        val source = AgentPluginSource { calls++; if (enabled) f.records else emptyList() }
        assertEquals(1, ContributionFamily.RULES.files(source, f.root).size)
        enabled = false; assertTrue(ContributionFamily.RULES.files(source, f.root).isEmpty())
        enabled = true; check(f.roots.single().resolve("rules/one.md").delete())
        assertTrue(ContributionFamily.RULES.files(source, f.root).isEmpty())
        assertEquals(3, calls)
    }

    @Test fun sourceVerificationFailuresStillPropagate() {
        val root = Files.createTempDirectory("droide-source-failure-").toFile()
        try {
            val failure = IOException("verification failed")
            for (family in ContributionFamily.entries) {
                val thrown = assertThrows(IOException::class.java) { family.files(AgentPluginSource { throw failure }, root) }
                assertSame(failure, thrown)
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun everyFamilyResolvesTheSourceExactlyOncePerLookup() {
        val root = Files.createTempDirectory("droide-source-once-").toFile()
        try {
            for (family in ContributionFamily.entries) {
                var calls = 0
                assertTrue(family.files(AgentPluginSource { calls++; emptyList() }, root).isEmpty())
                assertEquals(1, calls)
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun fullSourceValidationFinishesBeforeAnyQuotaLimitedTraversal() = ContributionFixture(ContributionFamily.RULES, 4).use { f ->
        var checked = 0
        val failure = IOException("last source rejected")
        val source = AgentPluginSource {
            for (record in f.records) {
                checked++
                if (record === f.records.last()) throw failure
            }
            f.records
        }
        f.resetObservations()
        assertSame(failure, assertThrows(IOException::class.java) { ContributionFamily.RULES.files(source, f.root) })
        assertEquals(4, checked)
        assertTrue(f.roots.all { it.resolutions == 0 })
    }

    @Test fun stableLeafSymlinksDirectoriesAndNonMarkdownStayExcluded() = ContributionFixture(ContributionFamily.RULES, 1).use { f ->
        val dir = f.roots.single().resolve("rules")
        val outside = f.root.resolve("outside.md").apply { writeText("Outside") }
        Files.createSymbolicLink(dir.resolve("a-link.md").toPath(), outside.toPath())
        dir.resolve("a-directory.md").mkdir(); dir.resolve("a-other.txt").writeText("Other")
        assertEquals(32, f.files().size)
        assertTrue(f.files().none { it.second.name.startsWith("a-") })
        assertEquals("Outside", outside.readText())
    }
}

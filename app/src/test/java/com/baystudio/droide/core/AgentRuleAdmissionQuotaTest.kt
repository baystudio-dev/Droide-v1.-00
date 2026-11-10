package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentRuleAdmissionQuotaTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rule-quotas-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun plugin(root: File, id: String, count: Int, always: Boolean = false): AgentPluginContributionRoot {
        val dir = root.resolve(id).apply { mkdir(); resolve("rules").mkdir() }
        repeat(count) { n -> dir.resolve("rules/r%02d.md".format(n)).writeText("---\ntrigger: ${if (always) "always" else "model"}\n---\nBody $n") }
        return AgentPluginContributionRoot(id, id, "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
    }
    private fun manager(root: File, vararg plugins: AgentPluginContributionRoot) = AgentRuleManager(root, AgentPluginSource { plugins.toList() })
    private fun definition(root: File, manager: AgentRuleManager) = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), rules = manager)
        .map { it.jsonObject.getValue("function").jsonObject }.single { it["name"] == JsonPrimitive("rule") }
    private fun enum(fn: JsonObject) = fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject["enum"]
    private fun assertLimited(report: AgentRuleDiscovery<*>, expected: String) {
        assertTrue(report.notice(), report.notice().contains(expected))
        assertTrue(report.notice().startsWith("Rule discovery incomplete: "))
    }
    private fun context(root: File, source: AgentPluginSource): AgentTools.Ctx {
        val c = agentWorkflowContext(root)
        return AgentTools.Ctx(c.files, c.terminal, c.git, c.approvals, c.questions, c.history, c.perms, c.mode,
            c.llm, c.llmConfig, root, c.todos, c.sessionId, c.lsp, pluginSource = source)
    }

    @Test fun belowAndAtPluginQuotaRemainComplete() = fixture { root ->
        for (count in listOf(0, 1, 31, 32)) {
            val m = manager(root, plugin(root, "p$count", count))
            val report = m.catalogWithDiagnostics()
            assertEquals(count, report.values.size); assertEquals("", report.notice()); assertEquals(0, report.skippedCount)
            if (count == 0) assertNull(enum(definition(root, m))) else assertEquals(count, enum(definition(root, m))!!.jsonArray.size)
        }
    }

    @Test fun extraCandidateDisclosesPluginQuotaWithoutReadingItsBody() = fixture { root ->
        val p = plugin(root, "p", 33); val opened = mutableListOf<String>()
        val m = AgentRuleManager(root, AgentPluginSource { listOf(p) }) { opened += it.name; it.inputStream() }
        val report = m.catalogWithDiagnostics()
        assertEquals(32, report.values.size); assertEquals((0..31).map { "r%02d.md".format(it) }, opened)
        assertEquals(0, report.skippedCount); assertLimited(report, "Per-plugin rule admission limit (32 files)")
        assertFalse(report.notice().contains("Body")); assertEquals("---\ntrigger: model\n---\nBody 32", p.root.resolve("rules/r32.md").readText())
    }

    @Test fun nonemptyInvalidTailIsNotClaimedToBeAValidRule() = fixture { root ->
        val p = plugin(root, "p", 33); p.root.resolve("rules/r32.md").writeText(" \t\n")
        val report = manager(root, p).catalogWithDiagnostics()
        assertLimited(report, "whose rule validity was not checked")
        assertFalse(report.notice().contains("Additional entries exist")); assertEquals(0, report.skippedCount)
    }

    @Test fun rejectedEntriesDoNotConsumeQuotaOrInventQuotaOverflow() = fixture { root ->
        val p = plugin(root, "p", 32); repeat(20) { p.root.resolve("rules/z$it.md").writeText("") }
        val report = manager(root, p).catalogWithDiagnostics()
        assertEquals(32, report.values.size); assertEquals(20, report.skippedCount)
        assertTrue(report.notice().contains("empty_file")); assertFalse(report.notice().contains("admission limit"))
    }

    @Test fun catalogSchemaIsNotExhaustiveWhenPluginAdmissionStoppedEarly() = fixture { root ->
        val m = manager(root, plugin(root, "p", 33)); val fn = definition(root, m)
        assertNull(enum(fn)); assertTrue(fn.getValue("description").jsonPrimitive.content.contains("Per-plugin rule admission limit"))
        assertEquals((0..31).map { "p:r%02d".format(it) }, m.catalog().map { it.name })
    }

    @Test fun activationFilteringDoesNotHideAdmissionIncompleteness() = fixture { root ->
        val p = plugin(root, "p", 32, always = true)
        p.root.resolve("rules/z-manual.md").writeText("---\ntrigger: manual\n---\nHIDDEN")
        val m = manager(root, p); val report = m.catalogWithDiagnostics()
        assertTrue(report.values.isEmpty()); assertLimited(report, "Per-plugin rule admission limit")
        assertNull(enum(definition(root, m)))
    }

    @Test fun explicitMissBeyondQuotaIsUnresolvedRatherThanConfirmedAbsent() = fixture { root -> runBlocking {
        val p = plugin(root, "p", 33)
        val result = AgentTools.execute("rule", buildJsonObject { put("name", "p:r32") }, context(root, AgentPluginSource { listOf(p) }))
        assertTrue(result, result.startsWith("ERROR: Rule unavailable:")); assertTrue(result.contains("absence is not confirmed"))
        assertTrue(result.contains("Per-plugin rule admission limit")); assertFalse(result.contains("Body 32"))
    } }

    @Test fun initialInstructionsReportQuotaAndKeepAdmittedGuidance() = fixture { root -> runBlocking {
        val p = plugin(root, "p", 33, always = true)
        val result = AgentProjectInstructions.load(FileRepository(root), root, AgentPluginSource { listOf(p) })
        assertTrue(result.startsWith("[DROIDE_RULE_DIAGNOSTICS]")); assertTrue(result.contains("Per-plugin rule admission limit"))
        assertTrue(result.contains("Body 0")); assertFalse(result.contains("Body 32")); assertTrue(result.length <= 28_000)
    } }

    @Test fun exactAggregateQuotaWithNoTailRemainsComplete() = fixture { root ->
        val records = (0..2).map { plugin(root, "p$it", 32) }
        var calls = 0; val m = AgentRuleManager(root, AgentPluginSource { calls++; records })
        val report = m.catalogWithDiagnostics(96)
        assertEquals(96, report.values.size); assertEquals("", report.notice()); assertEquals(1, calls)
    }

    @Test fun aggregateLimitReportsUnexaminedPluginsWithoutResolvingThem() = fixture { root ->
        val records = (0..2).map { plugin(root, "p$it", 32, always = it < 2) }
        val tail = plugin(root, "tail", 1); var resolutions = 0; var calls = 0
        val unread = tail.copy(root = object : File(tail.root.path) {
            override fun getCanonicalFile(): File { resolutions++; error("Tail must not be resolved") }
        })
        val m = AgentRuleManager(root, AgentPluginSource { calls++; records + unread })
        val report = m.catalogWithDiagnostics()
        assertEquals(32, report.values.size); assertEquals(0, resolutions); assertEquals(1, calls)
        assertEquals(0, report.skippedCount); assertLimited(report, "Aggregate rule admission limit (96 files)")
        assertTrue(report.notice().contains("additional rules are not confirmed")); assertNull(enum(definition(root, m)))
        assertEquals(0, resolutions)
    }

    @Test fun unexaminedEmptyPluginIsReportedAsUnknownNotKnownAdditionalRules() = fixture { root ->
        val records = (0..2).map { plugin(root, "p$it", 32) } + plugin(root, "empty", 0)
        val report = manager(root, *records.toTypedArray()).catalogWithDiagnostics(96)
        assertEquals(96, report.values.size); assertLimited(report, "additional rules are not confirmed")
        assertEquals(0, report.skippedCount)
    }

    @Test fun aggregateTruncationInsideFinalPluginIsReported() = fixture { root ->
        val records = listOf(31, 32, 32, 2).mapIndexed { i, count -> plugin(root, "p$i", count) }
        val opened = mutableListOf<String>()
        val m = AgentRuleManager(root, AgentPluginSource { records }) { opened += it.path; it.inputStream() }
        val report = m.catalogWithDiagnostics(96)
        assertEquals(96, opened.size); assertEquals("p3:r00", report.values.last().name)
        assertLimited(report, "Aggregate rule admission limit"); assertFalse(report.notice().contains("Per-plugin"))
        assertFalse(opened.contains(records.last().root.resolve("rules/r01.md").path))
    }

    @Test fun sparsePluginsBelowAggregateLimitDoNotInventIncompleteness() = fixture { root ->
        val records = (0..15).map { plugin(root, "p$it", if (it == 15) 1 else 0) }
        val report = manager(root, *records.toTypedArray()).catalogWithDiagnostics()
        assertEquals(listOf("p15:r00"), report.values.map { it.name }); assertEquals("", report.notice())
    }

    @Test fun quotaDiagnosticsClearAfterFreshRepairOrRevocation() = fixture { root ->
        val p = plugin(root, "p", 33); var enabled = true
        val m = AgentRuleManager(root, AgentPluginSource { if (enabled) listOf(p) else emptyList() })
        val first = m.catalogWithDiagnostics(); assertLimited(first, "Per-plugin rule admission limit")
        Files.move(p.root.resolve("rules/r00.md").toPath(), p.root.resolve("retained-r00.md").toPath())
        val repaired = m.catalogWithDiagnostics()
        assertEquals("", repaired.notice()); assertEquals(32, repaired.values.size); assertTrue(repaired.values.any { it.name == "p:r32" })
        assertNotNull(enum(definition(root, m))); enabled = false
        assertTrue(m.catalogWithDiagnostics().values.isEmpty()); assertEquals("", m.catalogWithDiagnostics().notice())
        assertLimited(first, "Per-plugin rule admission limit"); assertTrue(p.root.resolve("retained-r00.md").isFile)
    }

    @Test fun combinedIssuesAndLimitsStayBoundedAndDoNotInflateIssueCounts() = fixture { root ->
        val records = (0..3).map { plugin(root, "p$it", 40) }
        records.take(3).forEach { p -> repeat(10) { p.root.resolve("rules/z$it.md").writeText("") } }
        val report = manager(root, *records.toTypedArray()).catalogWithDiagnostics()
        assertEquals(30, report.skippedCount); assertEquals(4, report.details.size)
        assertLimited(report, "Per-plugin rule admission limit"); assertLimited(report, "Aggregate rule admission limit")
        assertEquals(1, Regex("Per-plugin rule admission limit").findAll(report.notice()).count())
        assertEquals(1, Regex("Aggregate rule admission limit").findAll(report.notice()).count())
        assertTrue(report.notice().length < 1_500)
    }

    @Test fun discoveryCancellationStillPropagatesAfterAnEarlierLimitedCall() = fixture { root ->
        val p = plugin(root, "p", 33); val cancelled = CancellationException("Cancelled"); var stop = false
        val m = AgentRuleManager(root, AgentPluginSource { if (stop) throw cancelled else listOf(p) })
        m.catalogWithDiagnostics(); stop = true
        try { m.catalogWithDiagnostics(); fail("Must propagate") } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun concurrentCallsKeepTheirCompletenessSeparate() = fixture { root ->
        val complete = plugin(root, "complete", 32); val limited = plugin(root, "limited", 33)
        val flag = ThreadLocal<Boolean>(); val m = AgentRuleManager(root, AgentPluginSource { listOf(if (flag.get() == true) limited else complete) })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reports = (0..9).map { n -> pool.submit<Pair<Boolean, String>> {
                val isLimited = n % 2 == 0; flag.set(isLimited); isLimited to m.catalogWithDiagnostics().notice()
            } }.map { it.get() }
            reports.forEach { (limitedCall, notice) -> assertEquals(limitedCall, notice.contains("Per-plugin rule admission limit")) }
        } finally { pool.shutdownNow() }
    }

    @Test fun legacyResultsAndSuccessfulLoadsRemainUnchangedAtQuota() = fixture { root ->
        val p = plugin(root, "p", 33); val m = manager(root, p)
        assertEquals(m.catalog(), m.catalogWithDiagnostics().values)
        assertEquals(m.discover().take(32).map { it.name }, m.catalog().map { it.name })
        assertEquals(m.load("p:r00"), m.loadWithDiagnostics("p:r00").values.single())
        assertNull(m.load("p:r32")); assertEquals(32, AgentPluginContributions.ruleFiles(AgentPluginSource { listOf(p) }, root).size)
    }

    @Test fun zeroAndInvalidWindowsAvoidDiscovery() = fixture { root ->
        var calls = 0; val m = AgentRuleManager(root, AgentPluginSource { calls++; error("Must not enumerate") })
        assertEquals("", m.catalogWithDiagnostics(0).notice())
        for (n in listOf(-1, 97)) assertThrows(IllegalArgumentException::class.java) { m.catalogWithDiagnostics(n) }
        assertEquals(0, calls)
    }
}

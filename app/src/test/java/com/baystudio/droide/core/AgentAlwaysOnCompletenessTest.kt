package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentAlwaysOnCompletenessTest {
    private fun fixture(count: Int, block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("always-on-completeness-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            val p = AgentPluginContributionRoot("p", "p", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            repeat(count) { rule(p, it) }
            block(root, p, AgentPluginSource { listOf(p) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun rule(p: AgentPluginContributionRoot, n: Int, text: String = "---\ntrigger: always\n---\nGUIDANCE_%02d".format(n)) =
        p.root.resolve("rules/r%02d.md".format(n)).apply { writeText(text) }
    private fun limited(report: AgentRuleDiscovery<*>) {
        assertFalse(report.isComplete); assertTrue(report.notice(), report.notice().contains("Always-on rule selection limit (24 rules)"))
    }

    @Test fun emptyAndExactlyTwentyFourRulesStayComplete() {
        for (count in listOf(0, 1, 23, 24)) fixture(count) { root, _, source ->
            var opened = 0; val m = AgentRuleManager(root, source) { opened++; it.inputStream() }
            val report = m.alwaysOnWithDiagnostics()
            assertEquals(count, report.values.size); assertEquals(count, opened)
            assertTrue(report.isComplete); assertEquals("", report.notice()); assertEquals(m.alwaysOn(), report.values)
        }
    }

    @Test fun twentyFifthRuleIsDisclosedWithoutAddingItsBody() = fixture(25) { root, _, source ->
        val m = AgentRuleManager(root, source); val report = m.alwaysOnWithDiagnostics()
        limited(report); assertEquals(24, report.values.size); assertEquals(0, report.skippedCount)
        assertEquals(m.alwaysOn(), report.values); assertFalse(report.notice().contains("GUIDANCE_24"))
        assertEquals("GUIDANCE_24", m.load("p:r24")!!.body)
    }

    @Test fun probeReadsOnlyMetadataThenStopsBeforeLaterRules() = fixture(26) { root, p, source ->
        val tail = "---\ntrigger: always\n---\nPRIVATE_START" + "x".repeat(10_000) + "PRIVATE_END"
        rule(p, 24, tail); val read = linkedMapOf<String, Int>(); val closed = mutableListOf<String>()
        val m = AgentRuleManager(root, source) { f -> object : FilterInputStream(f.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { if (it > 0) read[f.name] = (read[f.name] ?: 0) + it }
            override fun close() { super.close(); closed += f.name }
        } }
        val report = m.alwaysOnWithDiagnostics(); limited(report)
        assertEquals(25, read.size); assertTrue(read.getValue("r24.md") <= 512); assertFalse(read.containsKey("r25.md"))
        assertEquals(read.keys.toList(), closed); assertFalse(report.toString().contains("PRIVATE_START"))
    }

    @Test fun manualTailDoesNotInventAlwaysOnOmissions() = fixture(24) { root, p, source ->
        repeat(8) { rule(p, 24 + it, "---\ntrigger: manual\n---\n" + "x".repeat(10_000)) }
        var opens = 0; val bytes = linkedMapOf<String, Int>()
        val m = AgentRuleManager(root, source) { f -> opens++; object : FilterInputStream(f.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { if (it > 0) bytes[f.name] = (bytes[f.name] ?: 0) + it }
        } }
        val report = m.alwaysOnWithDiagnostics(); assertTrue(report.isComplete); assertEquals(24, report.values.size)
        assertEquals(32, opens); for (n in 24..31) assertTrue(bytes.getValue("r%02d.md".format(n)) <= 512)
    }

    @Test fun invalidTailIsDiagnosedWithoutClaimingAdditionalAlwaysOnRules() = fixture(24) { root, p, source ->
        rule(p, 24, " \t\n")
        val report = AgentRuleManager(root, source).alwaysOnWithDiagnostics()
        assertEquals(24, report.values.size); assertEquals(1, report.skippedCount)
        assertTrue(report.notice().contains("invalid_rule")); assertFalse(report.notice().contains("selection limit"))
    }

    @Test fun duplicateTailKeepsPriorityAndDoesNotCountAsAnotherRule() = fixture(24) { root, p, source ->
        rule(p, 24, "---\nname: r00\ntrigger: always\n---\nDUPLICATE")
        val report = AgentRuleManager(root, source).alwaysOnWithDiagnostics()
        assertEquals(1, report.skippedCount); assertTrue(report.notice().contains("duplicate_name"))
        assertFalse(report.notice().contains("selection limit")); assertEquals("GUIDANCE_00", report.values.first().body)
    }

    @Test fun probeContinuesPastManualInvalidAndDuplicateCandidates() = fixture(24) { root, p, source ->
        rule(p, 24, "---\ntrigger: manual\n---\nManual")
        rule(p, 25, " "); rule(p, 26, "---\nname: r00\ntrigger: always\n---\nDuplicate"); rule(p, 27)
        val report = AgentRuleManager(root, source).alwaysOnWithDiagnostics()
        limited(report); assertEquals(2, report.skippedCount); assertEquals(24, report.values.size)
    }

    @Test fun selectionAndAdmissionLimitsRemainSeparate() = fixture(33) { root, _, source ->
        val report = AgentRuleManager(root, source).alwaysOnWithDiagnostics()
        limited(report); assertTrue(report.notice().contains("Per-plugin rule admission limit")); assertEquals(0, report.skippedCount)
        assertTrue(report.notice().length < 1_000)
    }

    @Test fun repairAndRevocationClearOnlyTheCurrentReport() = fixture(25) { root, p, _ ->
        var enabled = true; var calls = 0
        val m = AgentRuleManager(root, AgentPluginSource { calls++; if (enabled) listOf(p) else emptyList() })
        val first = m.alwaysOnWithDiagnostics(); limited(first); assertEquals(1, calls)
        rule(p, 24, "---\ntrigger: manual\n---\nRetained")
        assertTrue(m.alwaysOnWithDiagnostics().isComplete); assertEquals("Retained", m.load("p:r24")!!.body)
        enabled = false; assertTrue(m.alwaysOnWithDiagnostics().values.isEmpty()); limited(first)
    }

    @Test fun probeReadFailureDoesNotDiscardSelectedGuidance() = fixture(25) { root, _, source ->
        val m = AgentRuleManager(root, source) { if (it.name == "r24.md") throw IOException("PRIVATE_ERROR") else it.inputStream() }
        val report = m.alwaysOnWithDiagnostics()
        assertEquals(24, report.values.size); assertEquals(1, report.skippedCount); assertTrue(report.notice().contains("read_failed"))
        assertFalse(report.notice().contains("PRIVATE_ERROR")); assertFalse(report.notice().contains("selection limit"))
    }

    @Test fun cancellationDuringProbePropagates() = fixture(25) { root, _, source ->
        val stop = CancellationException("Stop")
        val m = AgentRuleManager(root, source) { if (it.name == "r24.md") throw stop else it.inputStream() }
        try { m.alwaysOnWithDiagnostics(); fail("Cancellation must propagate") } catch (actual: CancellationException) { assertSame(stop, actual) }
    }

    @Test fun changedProbeIsRejectedAndNewBytesPreserved() = fixture(25) { root, p, source ->
        val tail = p.root.resolve("rules/r24.md"); val stamp = tail.lastModified()
        val m = AgentRuleManager(root, source) { f -> object : FilterInputStream(f.inputStream()) {
            override fun close() {
                super.close()
                if (f.name == "r24.md") { f.writeText("New external edit"); Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(stamp + 10_000)) }
            }
        } }
        val report = m.alwaysOnWithDiagnostics(); assertEquals(1, report.skippedCount)
        assertTrue(report.notice().contains("read_failed")); assertFalse(report.notice().contains("selection limit"))
        assertEquals("New external edit", tail.readText()); assertEquals(24, report.values.size)
    }

    @Test fun replacedProbeSymlinkNeverReadsOutsideBody() = fixture(25) { root, _, source ->
        val outside = root.resolve("outside").apply { writeText("PRIVATE_TARGET") }
        val m = AgentRuleManager(root, source) { f ->
            if (f.name == "r24.md") { check(f.delete()); Files.createSymbolicLink(f.toPath(), outside.toPath()) }
            Files.newInputStream(f.toPath(), NOFOLLOW_LINKS)
        }
        val report = m.alwaysOnWithDiagnostics(); assertEquals(1, report.skippedCount); assertTrue(report.notice().contains("read_failed"))
        assertFalse(report.notice().contains("PRIVATE_TARGET")); assertEquals("PRIVATE_TARGET", outside.readText())
    }

    @Test fun projectInstructionsDiscloseOmittedGuidanceWithinBudget() = fixture(25) { root, _, source -> runBlocking {
        root.resolve("AGENTS.md").writeText("Root guidance")
        val brief = AgentProjectInstructions.load(FileRepository(root), root, source)
        assertTrue(brief.startsWith("[DROIDE_RULE_DIAGNOSTICS]")); assertTrue(brief.contains("Always-on rule selection limit"))
        assertTrue(brief.contains("Root guidance")); assertTrue(brief.contains("GUIDANCE_23")); assertFalse(brief.contains("GUIDANCE_24"))
        assertTrue(brief.length <= 28_000)
    } }

    @Test fun warningSurvivesAggregateInstructionTruncation() = fixture(25) { root, p, source -> runBlocking {
        repeat(25) { rule(p, it, "---\ntrigger: always\n---\n" + "x".repeat(10_000)) }
        val brief = AgentProjectInstructions.load(FileRepository(root), root, source)
        assertTrue(brief.startsWith("[DROIDE_RULE_DIAGNOSTICS]")); assertTrue(brief.contains("Always-on rule selection limit"))
        assertTrue(brief.contains("total_limit")); assertTrue(brief.length <= 28_000)
    } }
}

package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentRuleDiagnosticsTest {
    private fun fixture(block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("rule-diagnostics-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            val plugin = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            block(root, plugin, AgentPluginSource { listOf(plugin) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun rule(plugin: AgentPluginContributionRoot, name: String = "one", text: String = "Instructions") = plugin.root.resolve("rules/$name.md").apply { writeText(text) }

    @Test fun ioFailuresHaveFixedReasonsAndNeverExposeExceptionPayload() = fixture { root, plugin, source ->
        rule(plugin)
        val manager = AgentRuleManager(root, source) { throw IOException("SECRET".repeat(100_000)) }
        val report = manager.loadWithDiagnostics("fixture:one")
        assertTrue(report.values.isEmpty()); assertEquals(1, report.skippedCount)
        assertTrue(report.notice().contains("read_failed")); assertFalse(report.notice().contains("SECRET")); assertTrue(report.notice().length < 1_000)
    }

    @Test fun cancellationPropagatesUnchangedFromRead() = fixture { root, plugin, source ->
        rule(plugin); val cancelled = CancellationException("Stop")
        val manager = AgentRuleManager(root, source) { throw cancelled }
        try { manager.catalogWithDiagnostics(); fail("must cancel") } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun sourceAuthorityFailurePropagatesInsteadOfLookingLikeAnEmptyCatalog() = fixture { root, _, _ ->
        val failure = IOException("Corrupt authority index")
        val manager = AgentRuleManager(root, AgentPluginSource { throw failure })
        try { manager.catalogWithDiagnostics(); fail("authority failure must propagate") } catch (actual: IOException) { assertSame(failure, actual) }
    }

    @Test fun sourceCancellationStillPropagates() = fixture { root, _, _ ->
        val cancelled = CancellationException("Stop")
        val manager = AgentRuleManager(root, AgentPluginSource { throw cancelled })
        try { manager.alwaysOnWithDiagnostics(); fail("must cancel") } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun concurrentEditIsReportedAndChangedBytesArePreserved() = fixture { root, plugin, source ->
        val file = rule(plugin); val stamp = Files.getLastModifiedTime(file.toPath()).toMillis()
        val manager = AgentRuleManager(root, source) { object : FilterInputStream(file.inputStream()) {
            override fun close() { super.close(); file.writeText("New edits"); Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(stamp + 5_000)) }
        } }
        val report = manager.loadWithDiagnostics("fixture:one")
        assertTrue(report.values.isEmpty()); assertEquals(1, report.skippedCount); assertTrue(report.notice().contains("read_failed"))
        assertEquals("New edits", file.readText())
    }

    @Test fun deletedFileProducesReadFailureWithoutRecreatingIt() = fixture { root, plugin, source ->
        val file = rule(plugin)
        val manager = AgentRuleManager(root, source) { file.delete(); Files.newInputStream(file.toPath(), NOFOLLOW_LINKS) }
        val report = manager.catalogWithDiagnostics()
        assertTrue(report.values.isEmpty()); assertEquals(1, report.skippedCount); assertFalse(file.exists())
    }

    @Test fun replacedSymlinkDoesNotExposeItsTargetOrWriteEitherFile() = fixture { root, plugin, source ->
        val file = rule(plugin); val target = root.resolve("private").apply { writeText("Outside content") }
        val manager = AgentRuleManager(root, source) {
            check(file.delete()); Files.createSymbolicLink(file.toPath(), target.toPath())
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS)
        }
        val report = manager.loadWithDiagnostics("fixture:one")
        assertTrue(report.values.isEmpty()); assertTrue(report.notice().contains("read_failed")); assertFalse(report.notice().contains("Outside content"))
        assertEquals("Outside content", target.readText()); assertTrue(Files.isSymbolicLink(file.toPath()))
    }

    @Test fun detailsAndUntrustedLabelsAreBoundedWhileSkippedCountIsAccurate() = fixture { root, plugin, _ ->
        val id = "p\n\u202e" + "😀".repeat(100_000)
        val records = (0..2).map { n ->
            val dir = root.resolve("p$n").apply { mkdir(); resolve("rules").mkdir() }
            plugin.copy(id = id, root = dir).also { p -> repeat(32) { rule(p, "r%02d".format(it), " \n ") } }
        }
        val report = AgentRuleManager(root, AgentPluginSource { records }).catalogWithDiagnostics()
        assertEquals(96, report.skippedCount); assertEquals(4, report.details.size); assertTrue(report.values.isEmpty())
        val notice = report.notice(); assertTrue(notice.contains("92 further issue(s) omitted"))
        assertTrue(notice.length < 1_000); assertTrue(notice.toByteArray().size < 2_000); assertFalse(notice.contains('\n')); assertFalse(notice.contains('\u202e'))
        assertFalse(Regex("[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])").containsMatchIn(notice))
    }

    @Test fun diagnosticsAreIndependentForSimultaneousCallsOnOneManager() = fixture { root, plugin, source ->
        rule(plugin)
        val mode = ThreadLocal<Boolean>()
        val manager = AgentRuleManager(root, source) { if (mode.get() == true) throw IOException("bad") else it.inputStream() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reports = (0 until 20).map { n -> pool.submit<Pair<Boolean, AgentRuleDiscovery<AgentRuleSummary>>> {
                val bad = n % 2 == 0; mode.set(bad); bad to manager.catalogWithDiagnostics()
            } }.map { it.get(5, TimeUnit.SECONDS) }
            for ((bad, report) in reports) { assertEquals(if (bad) 1 else 0, report.skippedCount); assertEquals(if (bad) 0 else 1, report.values.size) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun zeroLimitDoesNotDiscoverOrReadAndInvalidLimitsRemainRejected() = fixture { root, _, _ ->
        var calls = 0
        val manager = AgentRuleManager(root, AgentPluginSource { calls++; emptyList() })
        val result = manager.catalogWithDiagnostics(0)
        assertTrue(result.values.isEmpty()); assertEquals("", result.notice()); assertEquals(0, calls)
        for (limit in listOf(-1, 97)) assertThrows(IllegalArgumentException::class.java) { manager.catalogWithDiagnostics(limit) }
        assertEquals(0, calls)
    }

    @Test fun diagnosticsDoNotCauseExtraReadsBeyondTheRequestedResultWindow() = fixture { root, plugin, source ->
        rule(plugin, "a"); rule(plugin, "b", " \n ")
        val opened = mutableListOf<String>()
        val manager = AgentRuleManager(root, source) { opened += it.name; it.inputStream() }
        val report = manager.catalogWithDiagnostics(1)
        assertEquals(listOf("fixture:a"), report.values.map { it.name }); assertEquals(0, report.skippedCount)
        assertEquals(listOf("a.md"), opened)
    }

    @Test fun duplicateAndParseFailuresAreCountedSeparatelyWithoutDiscardingValidRules() = fixture { root, plugin, source ->
        rule(plugin, "a", "---\nname: same\n---\nFirst"); rule(plugin, "b", "---\nname: same\n---\nSecond"); rule(plugin, "c", " \n ")
        val report = AgentRuleManager(root, source).catalogWithDiagnostics()
        assertEquals(listOf("fixture:same"), report.values.map { it.name }); assertEquals(2, report.skippedCount)
        assertTrue(report.details[0].contains("duplicate_name")); assertTrue(report.details[1].contains("invalid_rule"))
    }

    @Test fun reportsRemainSnapshotsAfterLaterCallsAndRepairs() = fixture { root, plugin, source ->
        val file = rule(plugin, text = " \n "); val manager = AgentRuleManager(root, source)
        val first = manager.catalogWithDiagnostics(); val notice = first.notice()
        file.writeText("Valid")
        val second = manager.catalogWithDiagnostics()
        assertEquals(1, first.skippedCount); assertEquals(notice, first.notice()); assertTrue(first.values.isEmpty())
        assertEquals(0, second.skippedCount); assertEquals(1, second.values.size)
    }

    @Test fun legacyApisKeepTheirValuesAndOrdering() = fixture { root, plugin, source ->
        rule(plugin, "a", "---\ntrigger: always\n---\nAlways"); rule(plugin, "b", "Manual")
        val manager = AgentRuleManager(root, source)
        assertEquals(manager.catalog(), manager.catalogWithDiagnostics().values)
        assertEquals(manager.load("fixture:b"), manager.loadWithDiagnostics("fixture:b").values.single())
        assertEquals(manager.alwaysOn(), manager.alwaysOnWithDiagnostics().values)
        assertEquals(listOf("fixture:a", "fixture:b"), manager.discover().map { it.name })
    }
}

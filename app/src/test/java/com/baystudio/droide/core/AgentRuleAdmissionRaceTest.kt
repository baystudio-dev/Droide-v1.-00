package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentRuleAdmissionRaceTest {
    private fun fixture(block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("rule-admission-race-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            val plugin = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            block(root, plugin, AgentPluginSource { listOf(plugin) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun file(plugin: AgentPluginContributionRoot, name: String, body: String) = plugin.root.resolve("rules/$name.md").apply { writeText(body) }

    @Test fun replacedDirectoryCannotPublishTheRetiredCandidateList() = fixture { _, plugin, source ->
        file(plugin, "empty", ""); file(plugin, "valid", "Original")
        val retained = plugin.root.resolve("retained"); val issues = mutableListOf<String>(); var replaced = false
        val results = AgentPluginContributions.ruleFiles(source, plugin.root) { _, _, reason ->
            issues += reason
            if (!replaced && reason == "empty_file") {
                replaced = true; Files.move(plugin.root.resolve("rules").toPath(), retained.toPath())
                plugin.root.resolve("rules").mkdir(); file(plugin, "new", "New")
            }
        }
        assertTrue(replaced); assertTrue(results.isEmpty()); assertTrue(issues.contains("directory_changed"))
        assertEquals("Original", retained.resolve("valid.md").readText()); assertEquals("New", plugin.root.resolve("rules/new.md").readText())
    }

    @Test fun symlinkReplacementDuringEnumerationCannotPublishOutsideCandidates() = fixture { root, plugin, source ->
        file(plugin, "empty", ""); file(plugin, "valid", "Original")
        val outside = root.resolve("outside").apply { mkdir(); resolve("valid.md").writeText("Outside") }
        val retained = plugin.root.resolve("retained"); val issues = mutableListOf<String>(); var replaced = false
        val results = AgentPluginContributions.ruleFiles(source, root) { _, _, reason ->
            issues += reason
            if (!replaced && reason == "empty_file") {
                replaced = true; Files.move(plugin.root.resolve("rules").toPath(), retained.toPath())
                Files.createSymbolicLink(plugin.root.resolve("rules").toPath(), outside.toPath())
            }
        }
        assertTrue(replaced); assertTrue(results.isEmpty()); assertTrue(issues.contains("directory_changed"))
        assertEquals("Outside", outside.resolve("valid.md").readText()); assertEquals("Original", retained.resolve("valid.md").readText())
    }

    @Test fun replacedPluginRootInvalidatesTheObservedDirectory() = fixture { root, plugin, source ->
        file(plugin, "empty", ""); file(plugin, "valid", "Original")
        val retained = root.resolve("retained-plugin"); var replaced = false; val issues = mutableListOf<String>()
        val results = AgentPluginContributions.ruleFiles(source, root) { _, _, reason ->
            issues += reason
            if (!replaced && reason == "empty_file") {
                replaced = true; Files.move(plugin.root.toPath(), retained.toPath())
                plugin.root.mkdir(); plugin.root.resolve("rules").mkdir(); file(plugin, "valid", "New")
            }
        }
        assertTrue(replaced); assertTrue(results.isEmpty()); assertTrue(issues.contains("directory_changed"))
        assertEquals("Original", retained.resolve("rules/valid.md").readText()); assertEquals("New", plugin.root.resolve("rules/valid.md").readText())
    }

    @Test fun entryDeletionDuringEnumerationInvalidatesTheInventory() = fixture { root, plugin, source ->
        file(plugin, "empty", ""); val valid = file(plugin, "valid", "Original")
        val dir = plugin.root.resolve("rules").toPath(); val stamp = Files.getLastModifiedTime(dir).toMillis()
        var deleted = false; val issues = mutableListOf<String>()
        val results = AgentPluginContributions.ruleFiles(source, root) { _, _, reason ->
            issues += reason
            if (!deleted && reason == "empty_file") {
                deleted = true; check(valid.delete()); Files.setLastModifiedTime(dir, FileTime.fromMillis(stamp + 5_000))
            }
        }
        assertTrue(deleted); assertTrue(results.isEmpty()); assertTrue(issues.contains("directory_changed")); assertFalse(valid.exists())
    }

    @Test fun cancellationFromTheDiagnosticConsumerIsNotSwallowed() = fixture { root, plugin, source ->
        file(plugin, "empty", ""); val cancelled = CancellationException("Stop")
        try { AgentPluginContributions.ruleFiles(source, root) { _, _, _ -> throw cancelled }; fail("Must cancel") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun diagnosticConsumerFailureIsNotReclassifiedAsFilesystemFailure() = fixture { root, plugin, source ->
        file(plugin, "empty", ""); val failure = IOException("Consumer failed"); var calls = 0
        try { AgentPluginContributions.ruleFiles(source, root) { _, _, _ -> calls++; throw failure }; fail("Must propagate") }
        catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(1, calls)
    }

    @Test fun sourceAuthorityFailurePrecedesAllDiagnosticTraversal() = fixture { root, _, _ ->
        val failure = IOException("Authority failed"); var calls = 0
        try { AgentPluginContributions.ruleFiles(AgentPluginSource { throw failure }, root) { _, _, _ -> calls++ }; fail("Must propagate") }
        catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(0, calls)
    }

    @Test fun aggregateQuotaStillStopsBeforeLaterPluginTraversal() = fixture { root, plugin, _ ->
        val records = (0..3).map { n ->
            val dir = root.resolve("p$n").apply { mkdir(); resolve("rules").mkdir() }
            repeat(32) { dir.resolve("rules/r%02d.md".format(it)).writeText("Body") }
            plugin.copy(id = "p$n", root = if (n == 3) object : File(dir.path) {
                override fun getCanonicalFile(): File = throw AssertionError("Quota should stop before this plugin")
            } else dir)
        }
        var calls = 0; var diagnostics = 0
        val results = AgentPluginContributions.ruleFiles(AgentPluginSource { calls++; records }, root) { _, _, _ -> diagnostics++ }
        assertEquals(96, results.size); assertEquals(1, calls); assertEquals(0, diagnostics)
        assertEquals(setOf("p0", "p1", "p2"), results.map { it.first.id }.toSet())
    }
}

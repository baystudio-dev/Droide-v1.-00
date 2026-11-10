package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentRuleQuotaBoundaryTest {
    private fun fixture(block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("rule-quota-boundary-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            repeat(33) { dir.resolve("rules/r%02d.md".format(it)).writeText("Body $it") }
            val p = AgentPluginContributionRoot("p", "p", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            block(root, p, AgentPluginSource { listOf(p) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun directoryChangeDoesNotPublishUnstableQuotaObservation() = fixture { root, p, source ->
        p.root.resolve("rules/bad.md").writeText("")
        val reasons = mutableListOf<String>(); val limits = mutableListOf<AgentRuleAdmissionLimit>()
        val result = AgentPluginContributions.ruleFiles(source, root, { _, _, reason ->
            reasons += reason
            if (reason == "empty_file") {
                val dir = p.root.resolve("rules").toPath()
                Files.setLastModifiedTime(dir, FileTime.fromMillis(Files.getLastModifiedTime(dir).toMillis() + 10_000))
            }
        }, limits::add)
        assertTrue(result.isEmpty()); assertEquals(listOf("empty_file", "directory_changed"), reasons); assertTrue(limits.isEmpty())
        assertEquals(34, p.root.resolve("rules").listFiles()!!.size)
    }

    @Test fun quotaCallbackCancellationPropagatesUnchanged() = fixture { root, _, source ->
        val cancelled = CancellationException("Stop")
        try { AgentPluginContributions.ruleFiles(source, root, null) { throw cancelled }; fail("Must propagate") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun quotaCallbackFailureIsNotReclassifiedAsDirectoryFailure() = fixture { root, _, source ->
        val failure = IOException("Observer failure"); var skipped = 0
        try { AgentPluginContributions.ruleFiles(source, root, { _, _, _ -> skipped++ }) { throw failure }; fail("Must propagate") }
        catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(0, skipped)
    }
}

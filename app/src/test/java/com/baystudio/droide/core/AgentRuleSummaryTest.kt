package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentRuleSummaryTest {
    @Test fun summariesMatchTheLegacyVisibleMetadataAndKeepBodiesOutOfTheCatalog() = fixture { root, plugin, source ->
        plugin.root.resolve("rules/one.md").writeText("---\nname: alpha\ndescription: Description\ntrigger: manual\n---\nPRIVATE_BODY")
        val manager = AgentRuleManager(root, source)
        val before = manager.modelVisible().single()
        val summary = manager.catalog().single()
        assertEquals(AgentRuleSummary(before.name, before.description, before.activation, before.sourceLabel), summary)
        assertFalse(summary.toString().contains("PRIVATE_BODY"))
        assertEquals("PRIVATE_BODY", manager.load(summary.name)!!.body)
    }

    @Test fun catalogBoundsAndZeroAvoidUnneededSourceResolution() = fixture { root, _, _ ->
        var calls = 0
        val manager = AgentRuleManager(root, AgentPluginSource { calls++; emptyList() })
        assertTrue(manager.catalog(0).isEmpty()); assertEquals(0, calls)
        for (limit in listOf(-1, 97, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { manager.catalog(limit) }
        assertTrue(manager.catalog(96).isEmpty()); assertEquals(1, calls)
    }

    @Test fun metadataGrowingPastTheFileLimitIsRejectedAfterEarlyPrefixRead() = fixture { root, plugin, source ->
        val file = plugin.root.resolve("rules/one.md").apply { writeText("---\nname: one\n---\nInitial") }
        var closed = false; var bytes = 0
        val manager = AgentRuleManager(root, source) {
            file.appendText("x".repeat(30_000))
            object : FilterInputStream(file.inputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, length).also { if (it > 0) bytes += it }
                override fun close() { super.close(); closed = true }
            }
        }
        assertTrue(manager.catalog().isEmpty()); assertTrue(closed); assertTrue(bytes <= 512)
        assertTrue(file.length() > 30_000)
    }

    @Test fun replacementDuringMetadataReadCannotPublishTheRetiredName() = fixture { root, plugin, source ->
        val file = plugin.root.resolve("rules/one.md").apply { writeText("---\nname: old\n---\nBody") }
        val manager = AgentRuleManager(root, source) {
            val input = file.inputStream()
            object : FilterInputStream(input) {
                override fun close() {
                    super.close()
                    val replacement = plugin.root.resolve("replacement").apply { writeText("---\nname: new\n---\nBody") }
                    Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING)
                }
            }
        }
        assertTrue(manager.catalog().isEmpty())
        val fresh = AgentRuleManager(root, source)
        assertEquals("p0:new", fresh.catalog().single().name)
        assertNull(fresh.load("p0:old")); assertEquals("Body", fresh.load("p0:new")!!.body)
    }

    @Test fun cancellationDuringMetadataReadClosesAndPropagates() = fixture { root, plugin, source ->
        plugin.root.resolve("rules/one.md").writeText("Instructions")
        var closed = false
        val manager = AgentRuleManager(root, source) { object : InputStream() {
            override fun read(): Int = throw CancellationException("stop")
            override fun close() { closed = true }
        } }
        assertThrows(CancellationException::class.java) { manager.catalog() }
        assertTrue(closed)
    }

    @Test fun unterminatedFrontmatterAndBlankBodyScansStayByteBounded() = fixture { root, plugin, source ->
        val file = plugin.root.resolve("rules/one.md")
        for (text in listOf("---\nname: ignored\n".padEnd(12_000, ' '), "---\nname: blank\n---\n".padEnd(12_000, ' '))) {
            file.writeText(text)
            var bytes = 0
            val manager = AgentRuleManager(root, source) { object : FilterInputStream(it.inputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, length).also { if (it > 0) bytes += it }
            } }
            val summaries = manager.catalog()
            assertEquals(if (text.contains("name: ignored")) 1 else 0, summaries.size)
            assertEquals(12_000, bytes)
        }
    }

    @Test fun validCatalogDoesNotTurnAStaleNameIntoAnotherRule() = fixture { root, plugin, source ->
        val file = plugin.root.resolve("rules/one.md").apply { writeText("---\nname: before\n---\nBody") }
        val manager = AgentRuleManager(root, source)
        val selected = manager.catalog().single()
        file.writeText("---\nname: after\n---\nUpdated body")
        assertNull(manager.load(selected.name)); assertEquals("Updated body", manager.load("p0:after")!!.body)
    }

    @Test fun catalogMatchesLegacyProjectionAcrossWhitespaceEncodingAndChunkBoundaries() = fixture { root, plugin, source ->
        val file = plugin.root.resolve("rules/one.md")
        val manager = AgentRuleManager(root, source)
        for (padding in listOf(0, 1, 479, 480, 511, 512, 1_023)) {
            for (newline in listOf("\n", "\r\n")) {
                for (body in listOf("Body", " \u3000\t", "", "🙂", "\uFEFF", " \n")) {
                    file.writeText("---${newline}name: alpha${newline}---${newline}" + " ".repeat(padding) + body)
                    val expected = manager.modelVisible().map { AgentRuleSummary(it.name, it.description, it.activation, it.sourceLabel) }
                    assertEquals("padding=$padding body=$body", expected, manager.catalog())
                }
            }
        }
        file.writeBytes(byteArrayOf(0xe3.toByte()))
        assertEquals(manager.modelVisible().map { it.name }, manager.catalog().map { it.name })
    }

    private fun fixture(block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("droide-rule-summary-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            val plugin = AgentPluginContributionRoot("p0", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            block(root, plugin, AgentPluginSource { listOf(plugin) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

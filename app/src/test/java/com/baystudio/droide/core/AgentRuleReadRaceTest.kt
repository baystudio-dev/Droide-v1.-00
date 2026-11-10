package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentRuleReadRaceTest {
    @Test fun loadStopsBeforeOpeningLaterRules() = fixture { root, plugin, source ->
        repeat(32) { rule(plugin, "r%02d".format(it), "Body $it") }
        val opened = mutableListOf<String>()
        val manager = AgentRuleManager(root, source) { file -> opened += file.name; file.inputStream() }
        assertEquals("Body 0", manager.load("fixture:r00")!!.body)
        assertEquals(listOf("r00.md"), opened)
    }

    @Test fun activationLimitsStopAfterEnoughDistinctMatchingRules() = fixture { root, plugin, source ->
        repeat(30) { n -> rule(plugin, "r%02d".format(n), "---\ntrigger: ${if (n == 0) "manual" else "always"}\n---\nBody $n") }
        var opened = 0
        val manager = AgentRuleManager(root, source) { file -> opened++; file.inputStream() }
        assertEquals(24, manager.alwaysOn().size); assertEquals(25, opened)
        opened = 0
        assertEquals("Body 0", manager.modelVisible(1).single().body); assertEquals(1, opened)
    }

    @Test fun growingFileIsReadWithinBudgetAndNeverPartiallyAdmitted() = fixture { root, plugin, source ->
        val file = rule(plugin, "one", "Initial")
        lateinit var observed: Observed
        val manager = AgentRuleManager(root, source) {
            file.appendText("x".repeat(40_000))
            Observed(file.inputStream()).also { observed = it }
        }
        assertNull(manager.load("fixture:one"))
        assertEquals(12_001, observed.bytes); assertTrue(observed.closed); assertTrue(observed.largestRequest <= 4_096)
        assertEquals(40_007L, file.length())
    }

    @Test fun endlessInputIsBoundedAndClosed() = fixture { root, plugin, source ->
        rule(plugin, "one", "Initial")
        val observed = Observed(object : InputStream() { override fun read() = 'x'.code })
        assertTrue(AgentRuleManager(root, source) { observed }.discover().isEmpty())
        assertEquals(12_001, observed.bytes); assertTrue(observed.closed)
    }

    @Test fun oneByteChunksDecodeUtf8WithoutTruncatingTheBody() = fixture { root, plugin, source ->
        val text = "日本語🙂\nTail"
        val file = rule(plugin, "one", text)
        val observed = Observed(file.inputStream(), chunk = 1)
        assertEquals(text, AgentRuleManager(root, source) { observed }.load("fixture:one")!!.body)
        assertEquals(file.length().toInt(), observed.bytes); assertTrue(observed.closed)
    }

    @Test fun shortenedReadDoesNotPublishPartialInstructions() = fixture { root, plugin, source ->
        rule(plugin, "one", "Complete instructions")
        val observed = Observed(ByteArrayInputStream("Partial".toByteArray()))
        assertNull(AgentRuleManager(root, source) { observed }.load("fixture:one")); assertTrue(observed.closed)
    }

    @Test fun zeroProgressAndIoFailureCloseWithoutSpinning() = fixture { root, plugin, source ->
        rule(plugin, "one", "Instructions")
        for (failure in listOf(false, true)) {
            val observed = Observed(object : InputStream() {
                override fun read(): Int = error("bulk reads expected")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = if (failure) throw IOException("test") else 0
            })
            assertNull(AgentRuleManager(root, source) { observed }.load("fixture:one"))
            assertEquals(1, observed.calls); assertTrue(observed.closed)
        }
    }

    @Test fun cancellationIsNotSilentlyTreatedAsAMissingRule() = fixture { root, plugin, source ->
        rule(plugin, "one", "Instructions")
        val observed = Observed(object : InputStream() { override fun read(): Int = throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { AgentRuleManager(root, source) { observed }.load("fixture:one") }
        assertTrue(observed.closed)
    }

    @Test fun replacementAfterOpenCannotReturnRetiredInstructions() = fixture { root, plugin, source ->
        val file = rule(plugin, "one", "Original")
        val manager = AgentRuleManager(root, source) {
            val stream = file.inputStream()
            val replacement = plugin.root.resolve("replacement").apply { writeText("Replaced") }
            Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING)
            stream
        }
        assertNull(manager.load("fixture:one")); assertEquals("Replaced", file.readText())
    }

    @Test fun sameInodeEditAndDeletionDuringReadAreRejected() = fixture { root, plugin, source ->
        val file = rule(plugin, "one", "Original")
        for (delete in listOf(false, true)) {
            file.writeText("Original")
            val previousTime = Files.getLastModifiedTime(file.toPath()).toMillis()
            val manager = AgentRuleManager(root, source) {
                object : FilterInputStream(file.inputStream()) {
                    override fun close() {
                        super.close()
                        if (delete) check(file.delete()) else {
                            file.writeText("Modified")
                            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(previousTime + 5_000))
                        }
                    }
                }
            }
            assertNull(manager.load("fixture:one"))
        }
    }

    @Test fun parentReplacementIsRejectedEvenWhenFileInodeIsRetained() = fixture { root, plugin, source ->
        val file = rule(plugin, "one", "Original")
        val originalKey = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
        val manager = AgentRuleManager(root, source) {
            object : FilterInputStream(file.inputStream()) {
                override fun close() {
                    super.close()
                    val retained = plugin.root.resolve("retained-rules")
                    Files.move(file.parentFile.toPath(), retained.toPath())
                    check(file.parentFile.mkdir())
                    Files.move(retained.resolve(file.name).toPath(), file.toPath())
                }
            }
        }
        assertNull(manager.load("fixture:one"))
        assertEquals(originalKey, Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals("Original", file.readText())
    }

    @Test fun leafSymlinkReplacementBeforeOpenIsRejected() = fixture { root, plugin, source ->
        val file = rule(plugin, "one", "Original")
        val target = root.resolve("outside-rule").apply { writeText("Outside!") }
        val manager = AgentRuleManager(root, source) {
            check(file.delete()); Files.createSymbolicLink(file.toPath(), target.toPath())
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS)
        }
        assertNull(manager.load("fixture:one")); assertEquals("Outside!", target.readText())
    }

    private class Observed(private val input: InputStream, private val chunk: Int = Int.MAX_VALUE) : InputStream() {
        var bytes = 0; var calls = 0; var largestRequest = 0; var closed = false
        override fun read(): Int = error("bulk reads expected")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++; largestRequest = maxOf(largestRequest, length)
            val n = input.read(buffer, offset, minOf(length, chunk))
            if (n > 0) bytes += n
            return n
        }
        override fun close() { closed = true; input.close() }
    }
    private fun rule(plugin: AgentPluginContributionRoot, name: String, text: String) = plugin.root.resolve("rules/$name.md").apply { writeText(text) }
    private fun fixture(block: (File, AgentPluginContributionRoot, AgentPluginSource) -> Unit) {
        val root = Files.createTempDirectory("droide-rule-read-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            val plugin = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            block(root, plugin, AgentPluginSource { listOf(plugin) })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

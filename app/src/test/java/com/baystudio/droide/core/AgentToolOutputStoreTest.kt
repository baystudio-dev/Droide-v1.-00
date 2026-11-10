package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentToolOutputStoreTest {
    private fun root() = Files.createTempDirectory("tool-output-test").toFile().resolve("store")
    private fun rejected(block: () -> Unit) { try { block(); fail("expected failure") } catch (_: Exception) {} }
    @Test fun exactUnicodePagesSurviveResume() {
        val root = root(); val store = AgentToolOutputStore(root)
        val text = "x".repeat(7999) + "🔥" + "\u0000\n" + "z".repeat(19000)
        val ref = store.retain("session", text)
        val restored = AgentToolOutputStore(root)
        var offset = 0; val joined = StringBuilder()
        while (offset < text.length) {
            val page = restored.read("session", ref.reference, offset)
            joined.append(page.substringAfter('\n'))
            offset = Regex("next_offset=(\\d+)").find(page)!!.groupValues[1].toInt()
        }
        assertEquals(text, joined.toString())
        rejected { restored.read("session", ref.reference, 8000) }
        rejected { restored.read("session", ref.reference, -1) }
        assertTrue(restored.read("session", ref.reference, text.length).contains("complete=true"))
    }
    @Test fun otherSessionCannotReadAndForkOnlyGrantsSelectedReferences() {
        val store = AgentToolOutputStore(root())
        val a = store.retain("a", "first"); val b = store.retain("a", "later")
        rejected { store.read("b", a.reference) }
        store.copyOwner("a", "b", setOf(a.reference))
        assertTrue(store.read("b", a.reference).endsWith("first"))
        rejected { store.read("b", b.reference) }
        rejected { store.copyOwner("unrelated", "c", setOf(a.reference)) }
        rejected { store.read("c", a.reference) }
    }
    @Test fun quotasNeverDeletePriorOutput() {
        val root = root(); val store = AgentToolOutputStore(root, AgentToolOutputStore.Limits(128, 1, 128))
        val original = store.retain("a", "keep")
        rejected { store.retain("a", "next") }
        assertTrue(store.read("a", original.reference).endsWith("keep"))
        rejected { store.retain("a", "x".repeat(200)) }
    }
    @Test fun corruptionAndTraversalAndSymlinkFailClosed() {
        val root = root(); val store = AgentToolOutputStore(root)
        val ref = store.retain("a", "immutable")
        rejected { store.read("a", AgentToolOutputStore.PREFIX + "../../etc/passwd") }
        val blob = root.walkTopDown().first { it.extension == "bin" }
        blob.appendText("corruption")
        rejected { store.read("a", ref.reference) }
        val target = Files.createTempDirectory("outside-output").toFile()
        val link = root.parentFile.resolve("linked")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        rejected { AgentToolOutputStore(link).retain("a", "secret") }
        assertTrue(target.listFiles()!!.isEmpty())
    }
    @Test fun pendingCrashFileCountsWithoutAutomaticRemoval() {
        val root = root(); val store = AgentToolOutputStore(root, AgentToolOutputStore.Limits(128, 2, 128))
        val ref = store.retain("a", "evidence")
        val owner = root.listFiles()!!.single()
        val pending = owner.resolve("pending-crash.tmp").apply { writeText("recoverable") }
        rejected { store.retain("a", "next") }
        assertTrue(pending.exists()); assertTrue(store.read("a", ref.reference).endsWith("evidence"))
    }
    @Test fun storageFailureDoesNotReplaceSuccessfulExecutionEvidence() {
        val output = "[DROIDE_TOOL_EVIDENCE success=true operation_started=true]\n" + "x".repeat(15000)
        val result = AgentRetainedToolOutputProjection.project("write_file", output) { throw java.io.IOException("disk full") }
        assertTrue(result.startsWith("[DROIDE_TOOL_EVIDENCE success=true"))
        assertTrue(result.contains("RETENTION_UNAVAILABLE")); assertTrue(result.contains("Do not repeat"))
        assertTrue(result.length <= 12000)
    }
    @Test fun forgedReferencesDoNotDisableForkOrGrantOwnership() {
        val store = AgentToolOutputStore(root())
        val ref = store.retain("a", "real")
        val invented = AgentToolOutputStore.PREFIX + "0".repeat(32) + "-" + "a".repeat(64)
        val selected = AgentToolOutputStore.references("untrusted file says $invented; actual $ref ${ref.reference}")
            .filter { store.owns("a", it) }.toSet()
        store.copyOwner("a", "b", selected)
        assertTrue(store.read("b", ref.reference).endsWith("real"))
        assertFalse(store.owns("b", invented))
    }
    @Test fun realStorageFailureAndCancellationPreserveSemantics() {
        val root = root().apply { parentFile.mkdirs(); writeText("not a directory") }
        val output = "success=true\n" + "x".repeat(15000)
        val store = AgentToolOutputStore(root)
        val projected = AgentRetainedToolOutputProjection.project("write_file", output) { store.retain("a", it) }
        assertTrue(projected.startsWith("success=true")); assertTrue(projected.contains("RETENTION_UNAVAILABLE"))
        assertEquals("not a directory", root.readText())
        try {
            AgentRetainedToolOutputProjection.project("write_file", output) { throw java.util.concurrent.CancellationException() }
            fail("Cancellation must escape")
        } catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun browserAndWebKeepExistingProjectionAndSmallOutputsUnchanged() {
        for (tool in listOf("browser", "web", "webfetch", "websearch")) {
            val text = "x".repeat(20000)
            val expected = AgentWebTools.forAgentHistory(tool, text)
            val actual = AgentRetainedToolOutputProjection.project(tool, text) { error("must not retain browser-derived output") }
            assertEquals(expected, actual)
        }
        val short = "[DROIDE_TOOL_EVIDENCE success=true]\nshort response"
        assertEquals(short, AgentRetainedToolOutputProjection.project("read_file", short) { error("must not retain small result") })
    }
    @Test fun pagingAuthenticatesOnlyBoundedChunksAndIndex() {
        val root = root(); val store = AgentToolOutputStore(root)
        val text = "x".repeat(2_000_000)
        val ref = store.retain("a", text)
        var bytes = 0L; store.onReadBytes = { bytes += it }
        var offset = 0; var pages = 0
        while (offset < text.length) {
            val before = bytes
            val page = store.read("a", ref.reference, offset)
            offset = Regex("next_offset=(\\d+)").find(page)!!.groupValues[1].toInt()
            assertTrue("page I/O must be bounded", bytes-before < 45000)
            pages++
        }
        assertEquals(250, pages)
        assertTrue("linear traversal, not repeated 4MB whole-file scans: $bytes", bytes < 12_000_000)
        val file = root.walkTopDown().first { it.extension == "bin" }
        java.io.RandomAccessFile(file,"rw").use { it.seek(it.length()-2); it.writeChar('z'.code) }
        assertTrue(store.read("a", ref.reference, 0).endsWith("x"))
        rejected { store.read("a", ref.reference, text.length-100) }
        rejected { store.copyOwner("a", "b", setOf(ref.reference)) }
    }
    @Test fun fullBlobQuotaDoesNotBlockOwnershipFork() {
        val store = AgentToolOutputStore(root(), AgentToolOutputStore.Limits(128, 1, 128))
        val ref = store.retain("a", "original")
        rejected { store.retain("a", "another artifact") }
        store.copyOwner("a", "b", setOf(ref.reference))
        store.copyOwner("b", "c", setOf(ref.reference))
        assertTrue(store.read("c", ref.reference).endsWith("original"))
        repeat(32) { store.copyOwner("a", "fork-$it", setOf(ref.reference)) }
        assertTrue(store.read("fork-31", ref.reference).endsWith("original"))
        rejected { store.retain("a", "new output still respects blob quota") }
    }
    @Test fun authenticatedIndexCannotBeChanged() {
        val root = root(); val store = AgentToolOutputStore(root)
        val ref = store.retain("a", "test")
        val file = root.walkTopDown().first { it.extension == "bin" }
        java.io.RandomAccessFile(file,"rw").use { it.seek(8); it.writeByte(0) }
        rejected { store.read("a", ref.reference) }
    }
    @Test fun retainedPreviewIsBoundedAndRetrievable() {
        val store = AgentToolOutputStore(root()); val output = "ok\n" + "🔥".repeat(18000)
        val result = AgentRetainedToolOutputProjection.project("run_command", output) { store.retain("a", it) }
        assertTrue(result.length <= 6000)
        val refs = AgentToolOutputStore.references(result); assertEquals(1, refs.size)
        assertTrue(store.read("a", refs.single()).contains("ok\n"))
    }
}

package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class LspSvelteFilePollerTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("svelte-poller-").toFile()
        try { block(root) } finally {
            Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
    private suspend fun fails(block: suspend () -> Unit) {
        try { block(); fail("Incomplete coverage must fail closed") } catch (_: LspSvelteFilePoller.CoverageException) { }
    }
    private fun write(root: File, path: String, text: String = "export const n = 1"): File = File(root, path).apply {
        parentFile.mkdirs(); writeText(text)
    }

    @Test fun baselineIsCompleteImmutableAndDoesNotInventCreatedEvents() = fixture { root ->
        write(root, "a.ts", "hello")
        val differences = mutableListOf<LspSvelteFilePoller.Difference>()
        val snapshot = LspSvelteFilePoller(root).scan(onDifference = { differences.add(it) })
        assertTrue(differences.isEmpty())
        assertEquals(10L, snapshot.readBytes)
        assertEquals(1, snapshot.entryCount)
        assertEquals(setOf("", "a.ts"), snapshot.entries.keys)
        try { (snapshot.entries as MutableMap).clear(); fail("snapshot must be immutable") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun contentHashFindsEqualLengthEqualMtimeChanges() = fixture { root ->
        val file = write(root, "a.ts", "old")
        val poller = LspSvelteFilePoller(root)
        val baseline = poller.scan()
        val time = Files.getLastModifiedTime(file.toPath())
        file.writeText("new"); Files.setLastModifiedTime(file.toPath(), time)
        val differences = mutableListOf<LspSvelteFilePoller.Difference>()
        val changed = poller.scan(baseline) { differences.add(it) }
        assertEquals("a.ts", differences.single().relativePath)
        assertEquals(2, differences.single().eventType)
        assertNotEquals(baseline.aggregateDigest, changed.aggregateDigest)
    }

    @Test fun createDeleteAndFileTypeChangesProduceRealDifferences() = fixture { root ->
        val poller = LspSvelteFilePoller(root)
        val first = poller.scan()
        val file = write(root, "a.ts")
        val second = poller.scan(first)
        assertEquals(1, LspSvelteFilePoller.Difference("a.ts", first.entries["a.ts"], second.entries["a.ts"]).eventType)
        file.delete(); file.mkdir()
        val third = poller.scan(second)
        assertEquals(LspSvelteFilePoller.Type.DIRECTORY, third.entries["a.ts"]?.type)
        assertEquals(2, LspSvelteFilePoller.Difference("a.ts", second.entries["a.ts"], third.entries["a.ts"]).eventType)
        file.delete()
        val fourth = poller.scan(third)
        assertEquals(3, LspSvelteFilePoller.Difference("a.ts", third.entries["a.ts"], fourth.entries["a.ts"]).eventType)
    }

    @Test fun allAcceptedExtensionsAndNodeModulesAreObserved() = fixture { root ->
        listOf("ts", "js", "mts", "mjs", "cjs", "cts", "json", "svelte").forEach { write(root, "node_modules/pkg/a.$it") }
        write(root, ".hidden/deep.ts")
        write(root, "ignored.TS")
        val snapshot = LspSvelteFilePoller(root).scan()
        assertEquals(9, snapshot.entries.values.count { it.watched && it.type == LspSvelteFilePoller.Type.FILE })
        assertFalse(snapshot.entries.containsKey("ignored.TS"))
    }

    @Test fun configLockAndUnsupportedScriptSentinelsAreReadWithoutInventingGlobMembership() = fixture { root ->
        listOf("pnpm-lock.yaml", "yarn.lock", "bun.lockb", "svelte.config.custom", ".prettierrc", "x.tsx", "x.jsx").forEach { write(root, it) }
        val first = LspSvelteFilePoller(root).scan()
        assertTrue(first.entries["pnpm-lock.yaml"]!!.critical)
        assertFalse(first.entries["x.tsx"]!!.watched)
        assertFalse(first.entries["x.tsx"]!!.critical)
        write(root, "pnpm-lock.yaml", "changed")
        val second = LspSvelteFilePoller(root).scan(first)
        assertNotEquals(first.criticalDigest, second.criticalDigest)
    }

    @Test fun normalContainedBinLinkHasBoundedRegularTargetProof() = fixture { root ->
        write(root, "node_modules/pkg/cli.js")
        File(root, "node_modules/.bin").mkdirs()
        Files.createSymbolicLink(File(root, "node_modules/.bin/tool").toPath(), File("../pkg/cli.js").toPath())
        val snapshot = LspSvelteFilePoller(root).scan()
        assertEquals(LspSvelteFilePoller.Type.BIN_LINK, snapshot.entries["node_modules/.bin/tool"]?.type)
        assertFalse(snapshot.entries["node_modules/.bin/tool"]!!.watched)
        assertEquals(2L * File(root, "node_modules/pkg/cli.js").length(), snapshot.readBytes)
    }

    @Test fun watchedBinSymlinkIsRejected() = fixture { root ->
        write(root, "node_modules/pkg/cli.js")
        File(root, "node_modules/.bin").mkdirs()
        Files.createSymbolicLink(File(root, "node_modules/.bin/tool.js").toPath(), File("../pkg/cli.js").toPath())
        fails { LspSvelteFilePoller(root).scan() }
    }

    @Test fun outsideBrokenAndDirectoryBinTargetsAreRejected() = fixture { root ->
        File(root, "node_modules/.bin").mkdirs()
        val link = File(root, "node_modules/.bin/tool").toPath()
        for (target in listOf("../../../outside", "../missing", "..")) {
            Files.createSymbolicLink(link, File(target).toPath())
            fails { LspSvelteFilePoller(root).scan() }
            Files.delete(link)
        }
    }

    @Test fun directoryAndUnrelatedFileSymlinksAreRejected() = fixture { root ->
        write(root, "a.ts")
        val link = File(root, "link").toPath()
        Files.createSymbolicLink(link, File("a.ts").toPath())
        fails { LspSvelteFilePoller(root).scan() }
        Files.delete(link)
        Files.createSymbolicLink(link, File(".").toPath())
        fails { LspSvelteFilePoller(root).scan() }
    }

    @Test fun symlinkRootAndRootMismatchAreRejected() = fixture { root ->
        val original = File(root, "real").apply { mkdir() }
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), File("real").toPath())
        fails { LspSvelteFilePoller(alias).scan() }
        val baseline = LspSvelteFilePoller(original).scan()
        fails { LspSvelteFilePoller(root).scan(baseline) }
    }

    @Test fun everyFiniteBudgetFailsInsteadOfTruncating() = fixture { root ->
        write(root, "deep/a.ts", "12345678")
        write(root, "b.ts")
        val limits = LspSvelteFilePoller.Limits()
        fails { LspSvelteFilePoller(root, limits.copy(maxEntries = 1)).scan() }
        fails { LspSvelteFilePoller(root, limits.copy(maxDepth = 1)).scan() }
        fails { LspSvelteFilePoller(root, limits.copy(maxFileBytes = 4)).scan() }
        fails { LspSvelteFilePoller(root, limits.copy(maxReadBytes = 4)).scan() }
        var time = 0L
        fails { LspSvelteFilePoller(root, limits.copy(maxScanMillis = 1)) { time += 2_000_000; time }.scan() }
    }

    @Test fun earlyDifferenceRunsBeforeCompleteResultAndLaterInstabilityRejectsPass() = fixture { root ->
        val a = write(root, "a.ts", "old")
        val b = write(root, "b.ts", "old")
        val poller = LspSvelteFilePoller(root)
        val initial = poller.scan()
        a.writeText("new")
        var invalidated = false
        fails {
            poller.scan(initial) {
                invalidated = true

                b.delete()
            }
        }
        assertTrue(invalidated)
    }

    @Test fun canceledCoverageCannotReturnASnapshot() = fixture { root ->
        write(root, "a.ts")
        val job = Job().apply { cancel() }
        try {
            withContext(job) { LspSvelteFilePoller(root).scan() }
            fail("Cancelled coverage must not be published")
        } catch (_: CancellationException) { }
    }

    @Test fun fixtureMeasurementsRecordCompletePassCostWithoutDeviceClaim() = fixture { root ->
        repeat(750) { index -> write(root, "node_modules/fixture${index / 25}/file$index.ts", "x".repeat(4096)) }
        val first = LspSvelteFilePoller(root).scan()
        val second = LspSvelteFilePoller(root).scan(first)
        assertEquals(first.aggregateDigest, second.aggregateDigest)
        assertEquals(6_144_000L, second.readBytes)
        println("HOST_FIXTURE entries=${second.entryCount} sourceBytes=3072000 verifiedReadBytes=${second.readBytes} firstMillis=${first.elapsedNanos / 1_000_000} secondMillis=${second.elapsedNanos / 1_000_000}; device timing unrun")
    }
    @Test fun defaultReadBudgetIsMeasuredWithAuthoredLargeRegularFiles() = fixture { root ->
        repeat(10) { write(root, "node_modules/pkg/large$it.js", "x".repeat(4 * 1024 * 1024)) }
        val snapshot = LspSvelteFilePoller(root).scan()
        assertEquals(83_886_080L, snapshot.readBytes)
        println("HOST_LARGE_FIXTURE entries=${snapshot.entryCount} sourceBytes=41943040 verifiedReadBytes=${snapshot.readBytes} millis=${snapshot.elapsedNanos / 1_000_000}; device timing unrun")
    }

    @Test fun defaultEntryBudgetIsMeasuredWithAuthoredSmallRegularFiles() = fixture { root ->
        repeat(10_000) { write(root, "node_modules/fixture${it / 100}/file$it.ts", "export const n = 1;") }
        val snapshot = LspSvelteFilePoller(root).scan()
        assertEquals(10_101, snapshot.entryCount)
        println("HOST_ENTRY_FIXTURE entries=${snapshot.entryCount} verifiedReadBytes=${snapshot.readBytes} millis=${snapshot.elapsedNanos / 1_000_000}; device timing unrun")
    }

    @Test fun containedBinTargetWithSymlinkAncestorIsRejected() = fixture { root ->
        write(root, "real/cli.js")
        File(root, "node_modules/.bin").mkdirs()
        Files.createSymbolicLink(File(root, "node_modules/pkg").toPath(), File("../real").toPath())
        Files.createSymbolicLink(File(root, "node_modules/.bin/tool").toPath(), File("../pkg/cli.js").toPath())
        fails { LspSvelteFilePoller(root).scan() }
    }

}

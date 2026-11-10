package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceDiscoveryTraversalTest {
    @Test fun dependencyPruningChargesRawEntriesWithoutOpeningExcludedTrees(): Unit = runBlocking {
        withDiscoveryFiles("src/main.kt", "node_modules/dependency.kt", "packages/app.kt", "packages/node_modules/dependency.kt") { root ->
            val children = mapOf("" to listOf("src", "node_modules", "packages"), "src" to listOf("main.kt"),
                "node_modules" to listOf("dependency.kt"), "packages" to listOf("app.kt", "node_modules"),
                "packages/node_modules" to listOf("dependency.kt"))
            for (include in listOf(false, true)) {
                val streams = RecordingDiscoveryStreams(root, children)
                val budget = DiscoveryBudget(nanoTime = { 0L })
                val found = mutableListOf<String>()
                streams.traversal.walk(root, budget, canDescend = {
                    PathSecurity.canDescend(root, it) &&
                        !DependencyDiscoveryPolicy.isExcluded(it.relativeTo(root).invariantSeparatorsPath, true, include)
                }) { found += it.relativeTo(root).invariantSeparatorsPath }
                assertEquals(if (include) 5 else 3, streams.opened.size)
                assertEquals(if (include) 8 else 6, budget.scannedEntries)
                assertEquals(if (include) 13 else 9, streams.probes)
                assertEquals(streams.probes, budget.directoryProbes)
                assertEquals(streams.opened.size, streams.closed.size)
                assertFalse(budget.incomplete)
                assertEquals(if (include) 4 else 2, found.size)
                if (!include) assertTrue(streams.opened.none { "node_modules" in it.split('/') })
            }
        }
    }

    @Test fun depthBoundaryIsDisclosedAfterPolicyFiltering(): Unit = runBlocking {
        withDiscoveryFiles("deep/hidden.kt", "shallow.kt") { root ->
            val children = mapOf("" to listOf("deep", "shallow.kt"), "deep" to listOf("hidden.kt"))
            for (admitDeep in listOf(true, false)) {
                val streams = RecordingDiscoveryStreams(root, children)
                val budget = DiscoveryBudget(DiscoveryLimits(depth = 1), nanoTime = { 0L })
                val found = mutableListOf<String>()
                streams.traversal.walk(root, budget, canDescend = { admitDeep || it.name != "deep" }) { found += it.name }
                assertEquals(listOf("shallow.kt"), found)
                assertEquals(listOf(""), streams.opened)
                assertEquals(3, streams.probes)
                assertEquals(2, budget.scannedEntries)
                assertEquals(if (admitDeep) setOf(DiscoveryStopReason.DEPTH_LIMIT) else emptySet<DiscoveryStopReason>(), budget.stopReasons)
            }
        }
    }

    @Test fun resultStopDoesNotProbeOrOpenTheNextEntry(): Unit = runBlocking {
        withDiscoveryFiles("first.kt", "later/hidden.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("first.kt", "later"), "later" to listOf("hidden.kt")))
            val budget = DiscoveryBudget(nanoTime = { 0L })
            val found = mutableListOf<String>()
            streams.traversal.walk(root, budget, canDescend = { true }, shouldContinue = { found.isEmpty() }) { found += it.name }
            assertEquals(listOf("first.kt"), found)
            assertEquals(1, streams.probes)
            assertEquals(1, streams.delivered)
            assertEquals(listOf(""), streams.opened)
            assertEquals(streams.opened, streams.closed)
            assertFalse(budget.incomplete)
        }
    }

    @Test fun probeExhaustionDoesNotPerformAnExtraEofCheck(): Unit = runBlocking {
        withDiscoveryFiles("a.kt", "b.kt", "c.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("a.kt", "b.kt", "c.kt")))
            val budget = DiscoveryBudget(DiscoveryLimits(directoryProbes = 2), nanoTime = { 0L })
            val found = mutableListOf<String>()
            streams.traversal.walk(root, budget, canDescend = { true }) { found += it.name }
            assertEquals(listOf("a.kt", "b.kt"), found)
            assertEquals(2, streams.probes)
            assertEquals(2, streams.delivered)
            assertEquals(setOf(DiscoveryStopReason.PROBE_LIMIT), budget.stopReasons)
            assertEquals(1, streams.closed.size)
        }
    }

    @Test fun elapsedLimitIsCheckedBeforeTheNextProbe(): Unit = runBlocking {
        withDiscoveryFiles("a.kt", "b.kt") { root ->
            var now = 0L
            val budget = DiscoveryBudget(DiscoveryLimits(durationNanos = 5L), nanoTime = { now })
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("a.kt", "b.kt")))
            streams.traversal.walk(root, budget, canDescend = { true }) { now = 5L }
            assertEquals(1, streams.probes)
            assertEquals(setOf(DiscoveryStopReason.DEADLINE), budget.stopReasons)
            assertEquals(1, streams.closed.size)
        }
    }

    @Test fun directoryLimitIsCheckedBeforeOpeningAChild(): Unit = runBlocking {
        withDiscoveryFiles("child/a.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("child"), "child" to listOf("a.kt")))
            val budget = DiscoveryBudget(DiscoveryLimits(directoryOpens = 1), nanoTime = { 0L })
            streams.traversal.walk(root, budget, canDescend = { true }) { fail("Child must not be opened") }
            assertEquals(listOf(""), streams.opened)
            assertEquals(1, streams.probes)
            assertEquals(1, budget.directoryOpenAttempts)
            assertEquals(setOf(DiscoveryStopReason.DIRECTORY_LIMIT), budget.stopReasons)
            assertEquals(1, streams.closed.size)
        }
    }

    @Test fun cancellationPropagatesAndClosesTheEntireDepthStack(): Unit = runBlocking {
        withDiscoveryFiles("a/b/file.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("a"), "a" to listOf("b"), "a/b" to listOf("file.kt")))
            val budget = DiscoveryBudget(nanoTime = { 0L })
            try {
                streams.traversal.walk(root, budget, canDescend = { true }) { throw CancellationException("fixture cancellation") }
                fail("Cancellation must propagate")
            } catch (expected: CancellationException) {
                assertEquals("fixture cancellation", expected.message)
            }
            assertEquals(3, streams.opened.size)
            assertEquals(3, streams.probes)
            assertEquals(3, streams.closed.size)
            assertEquals(0, streams.live)
            assertEquals(3, streams.peakLive)
            assertFalse(budget.incomplete)
        }
    }

    @Test fun iteratorFailuresCloseBothEstablishedAndNewlyOpenedStreams(): Unit = runBlocking {
        withDiscoveryFiles("child/a.kt") { root ->
            for (failureInIterator in listOf(false, true)) {
                val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("child"), "child" to listOf("a.kt")),
                    failProbeAt = if (failureInIterator) null else 2, failIteratorIn = if (failureInIterator) "child" else null)
                try {
                    streams.traversal.walk(root, DiscoveryBudget(nanoTime = { 0L }), canDescend = { true }) { fail("Read should fail first") }
                    fail("I/O failure must propagate")
                } catch (expected: DirectoryIteratorException) {
                    assertTrue(expected.cause is IOException)
                }
                assertEquals(2, streams.opened.size)
                assertEquals(2, streams.closed.size)
                assertEquals(0, streams.live)
            }
        }
    }

    @Test fun pathLimitIsDisclosedWithoutMatchingTheRejectedPath(): Unit = runBlocking {
        withDiscoveryFiles("long-name.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("long-name.kt")))
            val budget = DiscoveryBudget(DiscoveryLimits(pathChars = 4), nanoTime = { 0L })
            streams.traversal.walk(root, budget, canDescend = { true }) { fail("Path must not reach matcher") }
            assertEquals(2, streams.probes)
            assertEquals(1, streams.delivered)
            assertEquals(setOf(DiscoveryStopReason.PATH_LIMIT), budget.stopReasons)
            assertEquals(1, streams.closed.size)
        }
    }

    @Test fun zeroOpenAllowanceDoesNotInvokeTheStreamFactory(): Unit = runBlocking {
        withDiscoveryFiles("a.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("a.kt")))
            val budget = DiscoveryBudget(DiscoveryLimits(directoryOpens = 0), nanoTime = { 0L })
            streams.traversal.walk(root, budget, canDescend = { true }) { fail("No entry allowed") }
            assertTrue(streams.opened.isEmpty())
            assertEquals(0, streams.probes)
            assertEquals(setOf(DiscoveryStopReason.DIRECTORY_LIMIT), budget.stopReasons)
        }
    }

    @Test fun symbolicLinkDirectoriesAreCountedButNeverOpened(): Unit = runBlocking {
        withDiscoveryFiles("actual/file.kt", "visible.kt") { root ->
            Files.createSymbolicLink(root.resolve("linked").toPath(), root.resolve("actual").toPath())
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("linked", "visible.kt"), "linked" to listOf("file.kt")))
            val budget = DiscoveryBudget(nanoTime = { 0L })
            val found = mutableListOf<String>()
            streams.traversal.walk(root, budget, canDescend = { true }) { found += it.name }
            assertEquals(listOf("visible.kt"), found)
            assertEquals(listOf(""), streams.opened)
            assertEquals(2, budget.scannedEntries)
            assertEquals(3, streams.probes)
            assertEquals(1, streams.closed.size)
            assertFalse(budget.incomplete)
        }
    }

    @Test fun cancelledJobStopsBeforeAnotherProbeAndClosesItsStream(): Unit = runBlocking {
        withDiscoveryFiles("a.kt", "b.kt") { root ->
            val streams = RecordingDiscoveryStreams(root, mapOf("" to listOf("a.kt", "b.kt")))
            val job = Job()
            try {
                withContext(job) {
                    streams.traversal.walk(root, DiscoveryBudget(nanoTime = { 0L }), canDescend = { true }) { job.cancel() }
                }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(job.isCancelled)
            } finally { job.cancel() }
            assertEquals(1, streams.probes)
            assertEquals(1, streams.delivered)
            assertEquals(1, streams.closed.size)
        }
    }
}

/** Scripted directory order only; actual files keep production path/no-follow checks in the exercised helper. */
internal class RecordingDiscoveryStreams(
    private val root: File,
    private val children: Map<String, List<String>>,
    private val failProbeAt: Int? = null,
    private val failIteratorIn: String? = null,
) {
    val opened = mutableListOf<String>()
    val closed = mutableListOf<String>()
    var probes = 0
        private set
    var delivered = 0
        private set
    var live = 0
        private set
    var peakLive = 0
        private set

    val traversal = WorkspaceDiscoveryTraversal { directory ->
        val relative = directory.relativeTo(root).invariantSeparatorsPath
        val entries = children[relative].orEmpty().map { directory.resolve(it).toPath() }
        opened += relative
        live++
        peakLive = maxOf(peakLive, live)
        object : DirectoryStream<Path> {
            private var iteratorRequested = false
            private var isClosed = false
            override fun iterator(): MutableIterator<Path> {
                check(!iteratorRequested)
                iteratorRequested = true
                if (relative == failIteratorIn) throw DirectoryIteratorException(IOException("fixture iterator"))
                var index = 0
                return object : MutableIterator<Path> {
                    override fun hasNext(): Boolean {
                        check(!isClosed)
                        probes++
                        if (probes == failProbeAt) throw DirectoryIteratorException(IOException("fixture probe"))
                        return index < entries.size
                    }
                    override fun next(): Path {
                        check(!isClosed)
                        if (index >= entries.size) throw NoSuchElementException()
                        delivered++
                        return entries[index++]
                    }
                    override fun remove(): Unit = throw UnsupportedOperationException()
                }
            }
            override fun close() {
                check(!isClosed)
                isClosed = true
                closed += relative
                live--
            }
        }
    }
}

internal suspend fun withDiscoveryFiles(vararg paths: String, action: suspend (File) -> Unit) {
    val root = Files.createTempDirectory("droide-discovery-count-").toFile()
    try {
        paths.forEach { path -> root.resolve(path).apply { parentFile.mkdirs(); writeText("needle\n") } }
        action(root)
    } finally { PathSecurity.deleteTreeNoFollow(root) }
}

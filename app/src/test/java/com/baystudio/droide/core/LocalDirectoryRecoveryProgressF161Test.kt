package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalDirectoryRecoveryProgressF161Test {
    @Test fun relayEmitsFirstAndEveryPhaseButThrottlesRepeatedPhase() {
        var now = 0L
        val emitted = mutableListOf<AgentToolProgress>()
        val relay = AgentLocalDirectoryRecoveryProgress(emitted::add) { now }
        fun report(phase: LocalDirectoryRecoveryPhase, bytes: Long) =
            relay.report(LocalDirectoryRecoveryProgress(phase, entries = 2, bytes = bytes))
        report(LocalDirectoryRecoveryPhase.CAPTURING, 0)
        repeat(10_000) { report(LocalDirectoryRecoveryPhase.CAPTURING, it.toLong()) }
        assertEquals(1, emitted.size)
        now = 249_999_999L; report(LocalDirectoryRecoveryPhase.CAPTURING, 3)
        assertEquals(1, emitted.size)
        now = 250_000_000L; report(LocalDirectoryRecoveryPhase.CAPTURING, 4)
        assertEquals(2, emitted.size)
        report(LocalDirectoryRecoveryPhase.CREATING_TAR, 0)
        assertEquals(3, emitted.size)
        now = 499_999_999L; report(LocalDirectoryRecoveryPhase.CREATING_TAR, 5)
        assertEquals(3, emitted.size)
        now = 500_000_000L; report(LocalDirectoryRecoveryPhase.CREATING_TAR, 6)
        assertEquals(4, emitted.size)
        assertTrue(emitted.last().detail.contains("6 bytes"))
        assertTrue(emitted.all { it.outputDelta.isEmpty() && it.title.isEmpty() && it.fileChanges == null })
    }

    @Test fun relayIsBoundedAndNeverInventsOverallTotalOrCompletion() {
        val emitted = mutableListOf<AgentToolProgress>()
        val relay = AgentLocalDirectoryRecoveryProgress(emitted::add) { 0L }
        LocalDirectoryRecoveryPhase.entries.forEach { phase ->
            relay.report(LocalDirectoryRecoveryProgress(phase, Long.MAX_VALUE, Long.MAX_VALUE))
        }
        assertEquals(LocalDirectoryRecoveryPhase.entries.size, emitted.size)
        assertTrue(emitted.all { it.detail.length < 200 && '%' !in it.detail && '/' !in it.detail })
        assertTrue(emitted.all { !it.detail.contains("completed", true) && !it.detail.contains("published", true) })
        assertTrue(emitted.any { it.detail.startsWith("Archive checked · preparing to save") })
    }

    @Test fun checkedMilestoneOmitsSourceCountersThatAreNotTarSize() {
        val emitted = mutableListOf<AgentToolProgress>()
        AgentLocalDirectoryRecoveryProgress(emitted::add).report(LocalDirectoryRecoveryProgress(
            LocalDirectoryRecoveryPhase.STAGED_TAR_VERIFIED, entries = 7, bytes = 123, totalBytes = 456))
        assertEquals("Archive checked · preparing to save", emitted.single().detail)
    }

    @Test fun relayShowsKnownPublicationTotalWithoutPretendingItIsSourceTotal() {
        val emitted = mutableListOf<AgentToolProgress>()
        AgentLocalDirectoryRecoveryProgress(emitted::add).report(LocalDirectoryRecoveryProgress(
            LocalDirectoryRecoveryPhase.COPYING_TO_PROJECT, bytes = 4096, totalBytes = 8192))
        assertEquals("Saving archive to project · 4096/8192 bytes", emitted.single().detail)
    }

    @Test fun coreProgressSeparatesPrivateStagingFromPublishedProject() = fixture { root, recovery, token ->
        val observations = mutableListOf<Pair<LocalDirectoryRecoveryProgress, Boolean>>()
        val result = recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(), {}, onProgress = {
            // Sink failures are isolated deliberately; assert observations after the callback.
            observations += it to root.resolve("rescued.tar").exists()
        })
        assertTrue(result.committed); assertFalse(result.historical)
        observations.forEach { (progress, destinationExists) ->
            if (progress.phase == LocalDirectoryRecoveryPhase.SYNCHRONIZING_PROJECT) {
                assertTrue(destinationExists)
                assertEquals(progress.totalBytes, progress.bytes)
            } else assertFalse("${progress.phase} must not mean already published", destinationExists)
            if (progress.phase in setOf(LocalDirectoryRecoveryPhase.CAPTURING, LocalDirectoryRecoveryPhase.CREATING_TAR))
                assertNull(progress.totalBytes)
        }
        assertEquals(LocalDirectoryRecoveryPhase.entries.toList(), observations.map { it.first.phase }.distinct())
        assertEquals("original", root.resolve("${LocalDirectoryDeleteCustody.HOME}/${token.operation}/original/data").readText())
        assertEquals("COMMITTED", recovery.inspectArchive(token, "rescued.tar").status)
    }

    @Test fun displayIOExceptionDoesNotAbortRecovery() = failingDisplay { IOException("detached display") }
    @Test fun displayCancellationExceptionIsNotCallerCancellation() = failingDisplay { CancellationException("detached display") }
    @Test fun unexpectedDisplayErrorDuringCapturePropagates() = fatalDisplay(LocalDirectoryRecoveryPhase.CAPTURING)
    @Test fun unexpectedDisplayErrorDuringCopyPropagates() = fatalDisplay(LocalDirectoryRecoveryPhase.COPYING_TO_PROJECT)
    @Test fun unexpectedDisplayErrorAfterPublicationPropagatesAndPreservesReceipt() = fatalDisplay(LocalDirectoryRecoveryPhase.SYNCHRONIZING_PROJECT)

    private fun fatalDisplay(phase: LocalDirectoryRecoveryPhase) = fixture { root, recovery, token ->
        val fatal = AssertionError("unexpected display error")
        val thrown = runCatching {
            recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(), {}, onProgress = {
                if (it.phase == phase) throw fatal
            })
        }.exceptionOrNull()
        assertSame(fatal, thrown)
        val published = phase == LocalDirectoryRecoveryPhase.SYNCHRONIZING_PROJECT
        assertEquals(published, root.resolve("rescued.tar").exists())
        val expected = when (phase) {
            LocalDirectoryRecoveryPhase.SYNCHRONIZING_PROJECT -> "COMMITTED"
            LocalDirectoryRecoveryPhase.COPYING_TO_PROJECT -> "UNKNOWN"
            else -> "PREPARED"
        }
        assertEquals(expected, recovery.inspectArchive(token, "rescued.tar").status)
        if (phase == LocalDirectoryRecoveryPhase.COPYING_TO_PROJECT) assertUnattemptedStagePreserved(root, token)
    }

    private fun failingDisplay(failure: () -> Throwable) = fixture { root, recovery, token ->
        var calls = 0
        val result = recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(), {}, onProgress = {
            calls++; throw failure()
        })
        assertTrue(calls >= LocalDirectoryRecoveryPhase.entries.size)
        assertTrue(result.committed); assertFalse(result.syncPending!!)
        assertTrue(root.resolve("rescued.tar").length() > 512)
    }

    @Test fun requestRevokedAtCaptureStopsBeforePayloadAndPublication() = fixture { root, recovery, token ->
        var current = true
        var calls = 0
        val error = runCatching {
            recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(),
                { check(current) { "request closed" } }, onProgress = { calls++; current = false })
        }.exceptionOrNull()
        assertNotNull(error); assertEquals(1, calls)
        assertFalse(root.resolve("rescued.tar").exists())
        assertEquals("PREPARED", recovery.inspectArchive(token, "rescued.tar").status)
    }

    @Test fun requestRevokedAtCopyCannotPublishEvenThoughRepositoryIsolatesProgress() = fixture { root, recovery, token ->
        var current = true
        var emittedAfterRevocation = false
        val error = runCatching {
            recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(),
                { check(current) { "request closed" } }, onProgress = {
                    if (!current) emittedAfterRevocation = true
                    if (it.phase == LocalDirectoryRecoveryPhase.COPYING_TO_PROJECT) current = false
                })
        }.exceptionOrNull()
        assertNotNull(error); assertFalse(emittedAfterRevocation)
        assertFalse(root.resolve("rescued.tar").exists())
        // OwnedStreamStage.close observes and preserves the admitted sibling stage.
        // No seal is not proof that replay is safe, even at the first copy callback.
        assertEquals("UNKNOWN", recovery.inspectArchive(token, "rescued.tar").status)
        assertUnattemptedStagePreserved(root, token)
    }

    @Test fun requestRevokedAfterPublicationSuppressesResultButPreservesCommittedReceipt() = fixture { root, recovery, token ->
        var current = true
        val error = runCatching {
            recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(),
                { check(current) { "request closed" } }, onProgress = {
                    if (it.phase == LocalDirectoryRecoveryPhase.SYNCHRONIZING_PROJECT) current = false
                })
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(root.resolve("rescued.tar").exists())
        assertEquals("COMMITTED", recovery.inspectArchive(token, "rescued.tar").status)
    }

    @Test fun callerCancellationFromDisplayStopsEvenWhenDisplayThrows() = fixture { root, recovery, token ->
        coroutineScope {
            val review = recovery.prepareArchive(token, "rescued.tar")
            lateinit var task: Job
            var calls = 0
            task = launch(start = CoroutineStart.LAZY) {
                recovery.archive(review, cache(), {}, onProgress = {
                    calls++; task.cancel(CancellationException("user stopped")); throw IOException("display detached")
                })
            }
            task.start(); task.join()
            assertTrue(task.isCancelled); assertEquals(1, calls)
        }
        assertFalse(root.resolve("rescued.tar").exists())
        assertEquals("PREPARED", recovery.inspectArchive(token, "rescued.tar").status)
    }

    @Test fun historicalCoreReturnDoesNotEmitCaptureOrPublicationProgress() = fixture { root, recovery, token ->
        val first = recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(), {})
        val bytes = root.resolve("rescued.tar").readBytes()
        var progress = 0
        val again = recovery.archive(recovery.prepareArchive(token, "rescued.tar"), cache(), {}, onProgress = { progress++ })
        assertEquals(0, progress); assertTrue(again.historical)
        assertEquals(first.sha256, again.sha256); assertArrayEquals(bytes, root.resolve("rescued.tar").readBytes())
    }

    @Test fun emptyFolderStillShowsCaptureAndAllPublicationStages() = fixture(empty = true) { root, recovery, token ->
        val phases = mutableListOf<LocalDirectoryRecoveryPhase>()
        val result = recovery.archive(recovery.prepareArchive(token, "empty.tar"), cache(), {}, onProgress = { phases += it.phase })
        assertTrue(result.committed)
        assertEquals(LocalDirectoryRecoveryPhase.entries.toList(), phases.distinct())
        assertTrue(root.resolve("empty.tar").length() > 0)
    }

    private fun assertUnattemptedStagePreserved(root: File, token: LocalDirectoryDeleteCustody.Token) {
        val stages = root.listFiles().orEmpty().filter { it.name.startsWith(".droide-tmp-") }
        assertEquals(1, stages.size); assertTrue(stages.single().isFile); assertEquals(0L, stages.single().length())
        val journal = root.resolve("${LocalDirectoryArchiveJournal.HOME}/${token.operation}")
        assertTrue(journal.walkTopDown().any { it.name == "intent.json" })
        assertFalse(journal.walkTopDown().any { it.name in setOf("attempt.json", "receipt.json", "seal.json") })
    }

    private fun cache(): File = Files.createTempDirectory("f161-progress-cache-").toFile()
    private fun fixture(empty: Boolean = false, block: suspend (File, LocalDirectoryRecovery, LocalDirectoryDeleteCustody.Token) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("f161-progress-core-").toFile()
        root.resolve("src").mkdir()
        if (!empty) root.resolve("src/data").writeText("original")
        val repo = FileRepository(root)
        assertTrue(repo.delete("src"))
        val recovery = repo.localDirectoryRecovery
        val token = requireNotNull(recovery.list().items.single().token)
        block(root, recovery, token)
    }
}

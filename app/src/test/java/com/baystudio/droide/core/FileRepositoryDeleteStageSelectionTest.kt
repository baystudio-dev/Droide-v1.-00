package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Repository seam only; the SAF participant supplies durable operation/container authority. */
class FileRepositoryDeleteStageSelectionTest {
    private lateinit var root: File
    private val relative = ".droide/saf-delete-custody/00000000-0000-0000-0000-000000000001/original"
    @Before fun setup() {
        root = Files.createTempDirectory("repo-delete-selection-").toFile()
        root.resolve("source.txt").writeText("original sentinel")
    }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(root) }

    private class Probe : WorkspaceMutationParticipant {
        var select: suspend () -> String? = { null }
        var prepare: (WorkspaceLocalPreparation) -> Unit = {}
        var attempt: () -> Unit = {}
        var sync: suspend () -> Unit = {}
        var selections = 0
        var attempts = 0
        var receipt: WorkspaceLocalReceipt? = null
        var outcome: WorkspaceMutationOutcome? = null
        override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
            object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure = true
                override suspend fun prepareDeleteStage(): String? { selections++; return select() }
                override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) = prepare(preparation)
                override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) { attempts++; attempt() }
                override fun localReturned(receipt: WorkspaceLocalReceipt) { this@Probe.receipt = receipt }
                override suspend fun synchronize() = sync()
                override fun retainsDeleteStage(receipt: WorkspaceLocalReceipt): Boolean {
                    assertSame(this@Probe.receipt, receipt)
                    return true
                }
                override fun finish(outcome: WorkspaceMutationOutcome) { this@Probe.outcome = outcome }
            }
    }

    private fun selected(probe: Probe = Probe()): Probe = probe.apply {
        select = { check(root.resolve(relative).parentFile!!.mkdirs()); relative }
    }

    private fun repository(probe: Probe, mover: WorkspacePathMove = WorkspacePathMove { from, to ->
        Files.move(from.toPath(), to.toPath()); Unit
    }) = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, pathMover = mover, participant = probe)

    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected failure")
    }

    @Test fun chosenProtectedStageUsesOneOriginalMoveAndExactReceipt() = runBlocking {
        val probe = selected()
        var moves = 0
        val repo = repository(probe, WorkspacePathMove { from, to ->
            moves++
            assertEquals(root.resolve(relative), to)
            Files.move(from.toPath(), to.toPath()); Unit
        })
        assertTrue(repo.delete("source.txt"))
        assertEquals(1, moves)
        assertEquals(1, probe.selections)
        assertEquals("original sentinel", root.resolve(relative).readText())
        assertFalse(root.resolve("source.txt").exists())
        assertEquals(relative, probe.receipt!!.preparation.relativePath)
        assertEquals("source.txt", probe.receipt!!.preparation.sourcePath)
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
        assertNull(probe.outcome!!.failure)
        assertTrue(root.list()!!.none { it.startsWith(".droide-tmp-") })
    }

    @Test fun nullSelectionKeepsSiblingStaging() = runBlocking {
        val probe = Probe()
        assertTrue(repository(probe).delete("source.txt"))
        val stage = root.listFiles()!!.single()
        assertTrue(stage.name.startsWith(".droide-tmp-"))
        assertEquals("original sentinel", stage.readText())
        assertEquals(stage.name, probe.receipt!!.preparation.relativePath)
        assertEquals(1, probe.selections)
    }

    @Test fun selectionCancellationKeepsOriginalFailureAndNeverAttemptsMove() = runBlocking {
        val cancelled = CancellationException("stage selection cancelled")
        val probe = Probe().apply { select = { throw cancelled } }
        assertOriginatingFailure(cancelled, failure { repository(probe).delete("source.txt") })
        assertEquals("original sentinel", root.resolve("source.txt").readText())
        assertEquals(0, probe.attempts)
        assertFalse(probe.outcome!!.localAttempted)
        assertSame(cancelled, probe.outcome!!.failure)
    }

    @Test fun conditionalDeleteRechecksVersionAfterStageSelection() = runBlocking {
        val probe = selected().apply {
            val create = select
            select = { create().also { root.resolve("source.txt").writeText("newer content") } }
        }
        assertTrue(failure {
            repository(probe).deleteFileIfUnchanged("source.txt", DiskFileVersion.fromText("original sentinel"))
        } is IllegalArgumentException)
        assertEquals("newer content", root.resolve("source.txt").readText())
        assertFalse(root.resolve(relative).exists())
        assertEquals(0, probe.attempts)
    }

    @Test fun unownedAliasedAndCollidingDestinationsNeverFallBackToSibling() = runBlocking {
        val destination = root.resolve(relative)
        destination.parentFile!!.mkdirs()
        val invalid = listOf("source.txt", "ordinary-stage", ".droide/saf_manifest.json", "../outside", "$relative/child",
            ".droide/saf-delete-custody/not-an-operation/original")
        for (path in invalid) {
            val probe = Probe().apply { select = { path } }
            failure { repository(probe).delete("source.txt") }
            assertEquals(0, probe.attempts)
            assertEquals("original sentinel", root.resolve("source.txt").readText())
        }
        destination.writeText("collision sentinel")
        val collision = Probe().apply { select = { relative } }
        failure { repository(collision).delete("source.txt") }
        assertEquals("collision sentinel", destination.readText())
        assertEquals(0, collision.attempts)
        assertTrue(destination.delete())
        Files.createSymbolicLink(destination.toPath(), root.resolve("source.txt").toPath())
        val alias = Probe().apply { select = { relative } }
        failure { repository(alias).delete("source.txt") }
        assertTrue(Files.isSymbolicLink(destination.toPath()))
        assertEquals(0, alias.attempts)
        assertEquals("original sentinel", root.resolve("source.txt").readText())
        assertTrue(root.list()!!.none { it.startsWith(".droide-tmp-") })
    }

    @Test fun absentStageParentsAreNeverManufacturedByRepository() = runBlocking {
        val probe = Probe().apply { select = { relative } }
        failure { repository(probe).delete("source.txt") }
        assertFalse(root.resolve(".droide").exists())
        assertEquals("original sentinel", root.resolve("source.txt").readText())
        assertEquals(0, probe.attempts)
    }

    @Test fun parentExchangeDuringLocalPreparationPreventsMove() = runBlocking {
        val probe = selected().apply {
            prepare = {
                val parent = root.resolve(relative).parentFile!!
                Files.move(parent.toPath(), parent.resolveSibling("retained-container").toPath())
                check(parent.mkdir())
                parent.resolve("newer.txt").writeText("newer container sentinel")
            }
        }
        failure { repository(probe).delete("source.txt") }
        assertEquals("original sentinel", root.resolve("source.txt").readText())
        assertEquals("newer container sentinel", root.resolve(relative).parentFile!!.resolve("newer.txt").readText())
        assertFalse(root.resolve(relative).exists())
        assertFalse(probe.receipt!!.successful)
    }

    @Test fun destinationAppearingAtAttemptBoundaryCannotBeReplaced() = runBlocking {
        val probe = selected().apply { attempt = { root.resolve(relative).writeText("newer stage sentinel") } }
        failure { repository(probe).delete("source.txt") }
        assertEquals("original sentinel", root.resolve("source.txt").readText())
        assertEquals("newer stage sentinel", root.resolve(relative).readText())
        assertFalse(probe.receipt!!.successful)
    }

    @Test fun syncCancellationRetainsProtectedStageAndExactReceipt() = runBlocking {
        val cancelled = CancellationException("sync cancelled")
        val probe = selected().apply { sync = { throw cancelled } }
        assertOriginatingFailure(cancelled, failure { repository(probe).delete("source.txt") })
        assertEquals("original sentinel", root.resolve(relative).readText())
        assertFalse(root.resolve("source.txt").exists())
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
        assertSame(cancelled, probe.outcome!!.failure)
    }

    @Test fun failedNoReplaceMoveHasNoCopyOrAlternativeStageFallback() = runBlocking {
        val failed = IOException("cross-filesystem move refused")
        val probe = selected()
        var moves = 0
        val repo = repository(probe, WorkspacePathMove { _, _ -> moves++; throw failed })
        assertOriginatingFailure(failed, failure { repo.delete("source.txt") })
        assertEquals(1, moves)
        assertEquals("original sentinel", root.resolve("source.txt").readText())
        assertFalse(root.resolve(relative).exists())
        assertTrue(root.list()!!.none { it.startsWith(".droide-tmp-") })
        assertSame(failed, probe.receipt!!.failure)
        assertSame(failed, probe.outcome!!.failure)
    }
}

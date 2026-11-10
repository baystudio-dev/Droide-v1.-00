package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Bounded repository observations only; no Android, provider, process or native fixture. */
class FileRepositoryTextWriteObservationTest {
    @Test(timeout = 15_000)
    fun receiptIsHandedOffBeforeParticipantObserverFailure() = workspace { root -> runBlocking {
        withTimeout(8_000) {
            root.resolve("file.txt").writeText("before")
            lateinit var observation: FileRepository.TextWriteObservation
            var received: WorkspaceLocalReceipt? = null
            var finishes = 0
            val observerFailure = IOException("receipt observer failed")
            val participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override fun localReturned(receipt: WorkspaceLocalReceipt) {
                        received = receipt
                        assertSame(receipt, observation.receipt)
                        throw observerFailure
                    }
                    override fun finish(outcome: WorkspaceMutationOutcome) { finishes++ }
                }
            }
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
            observation = files.prepareTextWriteObservation("file.txt", "after", DiskFileVersion.fromText("before"))
            val failure = try { files.writeTextIfUnchanged(observation); fail("Expected sync failure"); null }
                catch (actual: ProjectFileSyncException) { actual }
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it === observerFailure })
            assertSame(received, observation.receipt)
            assertTrue(observation.writeAttempted)
            assertFalse(requireNotNull(observation.receipt).durable)
            assertEquals(FileRepository.TextWriteDisposition.OWNED_CURRENT, observation.disposition)
            assertEquals("after", root.resolve("file.txt").readText())
            assertEquals(1, finishes)
        }
    } }

    @Test(timeout = 15_000)
    fun cancellationAtIoReturnKeepsReceiptAndFinalDisposition() = workspace { root -> runBlocking {
        withTimeout(8_000) {
            root.resolve("file.txt").writeText("before")
            val owner = Job()
            val stopped = CancellationException("cancel at IO return")
            var finishes = 0
            val participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override fun finish(outcome: WorkspaceMutationOutcome) {
                        finishes++
                        owner.cancel(stopped)
                    }
                }
            }
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
            val observation = files.prepareTextWriteObservation("file.txt", "after", DiskFileVersion.fromText("before"))
            val work = async(owner) { files.writeTextIfUnchanged(observation) }
            try {
                val failure = try { work.await(); fail("Expected return cancellation"); null }
                    catch (actual: CancellationException) { actual }
                work.join()
                assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it === stopped })
                assertTrue(observation.writeAttempted)
                assertTrue(requireNotNull(observation.receipt).successful)
                assertEquals(FileRepository.TextWriteDisposition.OWNED_CURRENT, observation.disposition)
                assertEquals("after", root.resolve("file.txt").readText())
                assertEquals(1, finishes)
            } finally {
                owner.cancel()
                withContext(NonCancellable) { withTimeout(2_000) { work.cancelAndJoin() } }
            }
        }
    } }

    @Test(timeout = 15_000)
    fun holderRejectsWrongRepositoryAndReuseBeforeAnotherMutation() = workspace { root -> runBlocking {
        withTimeout(8_000) {
            val other = Files.createTempDirectory("droide-observation-other-").toFile()
            try {
                root.resolve("file.txt").writeText("before")
                other.resolve("file.txt").writeText("other sentinel")
                var ownCalls = 0
                var foreignCalls = 0
                val files = FileRepository(root, beforeMutation = { ownCalls++ })
                val foreign = FileRepository(other, beforeMutation = { foreignCalls++ })
                val observation = files.prepareTextWriteObservation("file.txt", "after", DiskFileVersion.fromText("before"))
                try { foreign.writeTextIfUnchanged(observation); fail("Wrong repository must be rejected") }
                catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("another repository")) }
                assertEquals(0, foreignCalls)
                assertEquals("other sentinel", other.resolve("file.txt").readText())
                assertFalse(observation.writeAttempted)
                files.writeTextIfUnchanged(observation)
                val exactReceipt = observation.receipt
                try { files.writeTextIfUnchanged(observation); fail("Reusing a holder must be rejected") }
                catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("already used")) }
                assertEquals(1, ownCalls)
                assertSame(exactReceipt, observation.receipt)
                assertEquals(FileRepository.TextWriteDisposition.OWNED_CURRENT, observation.disposition)
                assertEquals("after", root.resolve("file.txt").readText())
            } finally { PathSecurity.deleteTreeNoFollow(other) }
        }
    } }

    @Test(timeout = 15_000)
    fun parentCreationReceiptCannotStandInForAWrite() = workspace { root -> runBlocking {
        withTimeout(8_000) {
            val participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                        if (preparation.effect == WorkspaceLocalEffect.WRITE) throw IOException("write preparation refused")
                    }
                }
            }
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
            val observation = files.prepareTextWriteObservation("parent/file.txt", "after", DiskFileVersion.fromText(null))
            try { files.writeTextIfUnchanged(observation); fail("Expected preparation refusal") }
            catch (expected: IOException) { assertEquals("write preparation refused", expected.message) }
            assertEquals(WorkspaceLocalEffect.CREATE_PARENTS, requireNotNull(observation.receipt).preparation.effect)
            assertFalse(observation.writeAttempted)
            assertEquals(FileRepository.TextWriteDisposition.UNCHANGED_WITHOUT_WRITE, observation.disposition)
            assertFalse(root.resolve("parent/file.txt").exists())
        }
    } }

    private fun workspace(body: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-text-write-observation-").toFile()
        try { body(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PatchDeleteSyncReceiptTest {
    private suspend fun withFixture(move: Boolean) {
        val root = Files.createTempDirectory("droide-patch-delete-sync-").toFile()
        try {
            var receipt: WorkspaceLocalReceipt? = null
            var syncAttempts = 0
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform,
                participant = WorkspaceMutationParticipant { guard, mutation ->
                    object : WorkspaceMutationSession(guard, mutation) {
                        override val retainLocalOnSyncFailure = true
                        override fun localReturned(value: WorkspaceLocalReceipt) {
                            if (mutation is FileRepository.Mutation.Delete && mutation.path == "source.txt") receipt = value
                        }
                        override suspend fun synchronize() {
                            if (mutation is FileRepository.Mutation.Delete && mutation.path == "source.txt") {
                                syncAttempts++
                                throw IOException("provider unavailable after local source deletion")
                            }
                        }
                    }
                })
            val history = EditHistory(files)
            root.resolve("source.txt").writeText("original\n")
            val patch = if (move) """*** Begin Patch
*** Update File: source.txt
*** Move to: destination.txt
@@
-original
+updated
*** End Patch""" else """*** Begin Patch
*** Delete File: source.txt
*** End Patch"""
            val outcome = runCatching { PatchTool.apply(PatchTool.parse(patch), files, history) }
            println("OBSERVED move=$move result=${outcome.getOrNull()} error=${outcome.exceptionOrNull()?.message} sourceExists=${root.resolve("source.txt").exists()} destinationExists=${root.resolve("destination.txt").exists()} historyDepth=${history.depth()} attempts=$syncAttempts stage=${receipt?.preparation?.relativePath}")
            assertEquals(1, syncAttempts)
            assertFalse(root.resolve("source.txt").exists())
            val retained = root.resolve(requireNotNull(receipt).preparation.relativePath)
            assertEquals("original\n", retained.readText())
            assertTrue("Known successful local patch must remain usable with synchronization pending: ${outcome.exceptionOrNull()}", outcome.isSuccess)
            assertTrue(outcome.getOrThrow(), outcome.getOrThrow().contains("synchronization pending"))
            if (move) assertEquals("updated\n", root.resolve("destination.txt").readText())
            assertEquals(1, history.depth())
            val undone = history.undo()
            assertTrue(undone, undone.startsWith("Undone:"))
            assertEquals("original\n", root.resolve("source.txt").readText())
            if (move) assertFalse(root.resolve("destination.txt").exists())
            assertEquals("original\n", retained.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun retainedDeleteSynchronizeFailureKeepsNormalPatchAndUndo() = runBlocking { withFixture(false) }
    @Test fun retainedMoveSourceSynchronizeFailureKeepsDestinationAndUndo() = runBlocking { withFixture(true) }

    private suspend fun project(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("droide-patch-delete-receipt-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class Participant(private val root: File, private val failure: Throwable) : WorkspaceMutationParticipant {
        val events = mutableListOf<String>()
        val retained = mutableListOf<File>()
        var failSourceDelete = true
        override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
            object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure = true
                override suspend fun prepareDeleteStage(): String {
                    val parent = ".droide/saf-delete-custody/${UUID.randomUUID()}"
                    check(root.resolve(parent).mkdirs())
                    return "$parent/original"
                }
                override fun localReturned(receipt: WorkspaceLocalReceipt) {
                    if (mutation is FileRepository.Mutation.Delete && mutation.path == "source.txt")
                        retained += root.resolve(receipt.preparation.relativePath)
                }
                override suspend fun synchronize() {
                    when (mutation) {
                        is FileRepository.Mutation.Write -> events += "write:${mutation.path}"
                        is FileRepository.Mutation.Delete -> events += "delete:${mutation.path}"
                        else -> Unit
                    }
                    if (failSourceDelete && mutation is FileRepository.Mutation.Delete && mutation.path == "source.txt")
                        throw failure
                }
            }
    }

    private fun move() = PatchTool.Op.Update("source.txt", "original", "updated", "destination.txt")

    @Test fun forwardCancellationRestoresSourceBeforeRemovingDestinationAndKeepsIdentity() = runBlocking {
        project { root ->
            root.resolve("source.txt").writeText("original")
            val cancellation = CancellationException("cancel at source synchronization")
            val participant = Participant(root, cancellation)
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
            val history = EditHistory(files)
            val caught = runCatching { PatchTool.apply(listOf(move()), files, history) }.exceptionOrNull()
            assertSame(cancellation, caught)
            assertEquals("original", root.resolve("source.txt").readText())
            assertFalse(root.resolve("destination.txt").exists())
            assertEquals(listOf("write:destination.txt", "delete:source.txt", "write:source.txt", "delete:destination.txt"), participant.events)
            assertEquals("original", participant.retained.single().readText())
            assertEquals(0, history.depth())
        }
    }

    @Test fun laterFailureRollsBackVerifiedMoveWithoutErasingOriginalCustody() = runBlocking {
        project { root ->
            root.resolve("source.txt").writeText("original")
            val participant = Participant(root, IOException("provider offline"))
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant,
                beforeMutation = { if (it is FileRepository.Mutation.Write && it.path == "failure.txt") throw IOException("later preflight failed") })
            val history = EditHistory(files)
            val caught = runCatching { PatchTool.apply(listOf(move(), PatchTool.Op.Add("failure.txt", "no publication")), files, history) }.exceptionOrNull()
            assertNotNull(caught)
            assertTrue(caught!!.message.orEmpty(), caught.message.orEmpty().contains("2 committed target(s) restored"))
            assertEquals("original", root.resolve("source.txt").readText())
            assertFalse(root.resolve("destination.txt").exists())
            assertFalse(root.resolve("failure.txt").exists())
            assertEquals(listOf("write:destination.txt", "delete:source.txt", "write:source.txt", "delete:destination.txt"), participant.events)
            assertEquals("original", participant.retained.single().readText())
            assertEquals(0, history.depth())
        }
    }

    @Test fun fatalSynchronizationErrorNeverClaimsSuccessfulSourceDeletion() = runBlocking {
        project { root ->
            root.resolve("source.txt").writeText("original")
            val fatal = AssertionError("fatal source synchronization")
            val participant = Participant(root, fatal)
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
            val history = EditHistory(files)
            assertSame(fatal, runCatching { PatchTool.apply(listOf(move()), files, history) }.exceptionOrNull())
            assertFalse(root.resolve("source.txt").exists())
            assertFalse(root.resolve("destination.txt").exists())
            assertFalse(participant.events.contains("write:source.txt"))
            assertEquals("original", participant.retained.single().readText())
            assertEquals(0, history.depth())
            assertTrue(root.resolve(".droide/backups").listFiles().orEmpty().any { it.readText() == "original" })
        }
    }

    @Test fun actualAgentApplyPatchDispatchPreservesCompletedMoveAndUndo() = runBlocking {
        project { root ->
            root.resolve("source.txt").writeText("original")
            val participant = Participant(root, IOException("provider offline"))
            val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
            val history = EditHistory(files)
            val base = agentWorkflowContext(root)
            val ctx = AgentTools.Ctx(files, base.terminal, base.git, base.approvals, base.questions, history,
                base.perms, base.mode, base.llm, base.llmConfig, root, base.todos, base.sessionId, base.lsp)
            val result = AgentTools.execute("apply_patch", buildJsonObject {
                put("patchText", "*** Begin Patch\n*** Update File: source.txt\n*** Move to: destination.txt\n@@\n-original\n+updated\n*** End Patch")
            }, ctx)
            assertTrue(result, result.startsWith("OK:"))
            assertTrue(result, result.contains("synchronization pending"))
            assertEquals("updated", root.resolve("destination.txt").readText())
            assertFalse(root.resolve("source.txt").exists())
            assertEquals(1, history.depth())
            participant.failSourceDelete = false
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("original", root.resolve("source.txt").readText())
            assertFalse(root.resolve("destination.txt").exists())
            assertEquals("original", participant.retained.single().readText())
        }
    }
}

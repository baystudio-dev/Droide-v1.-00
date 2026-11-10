package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryExplorerMutationTest {
    @Test fun strictMutationsDoNotRecreateAMissingDestinationFolder() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        val repo = FileRepository(root)
        val operations: List<suspend () -> Unit> = listOf(
            { repo.writeTextIfUnchanged("gone/file.txt", "new", DiskFileVersion.fromText(null), requireExistingParent = true) },
            { repo.writeStream("gone/import.txt", ByteArrayInputStream("new".toByteArray()), requireAbsent = true, requireExistingParent = true) },
            { repo.createDirectory("gone/folder", requireExistingParent = true); Unit },
            { repo.move("source.txt", "gone/moved.txt", requireExistingParent = true); Unit },
        )
        operations.forEach { operation ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { operation() } }
            assertFalse(root.resolve("gone").exists())
            assertEquals("source", root.resolve("source.txt").readText())
        }
    }

    @Test fun strictTextCreationRejectsParentRemovedByBeforeMutation() = withWorkspace { root ->
        root.resolve("dest").mkdir()
        val repo = FileRepository(root, beforeMutation = { check(root.resolve("dest").delete()) })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.writeTextIfUnchanged("dest/new.txt", "new", DiskFileVersion.fromText(null), requireExistingParent = true) }
        }
        assertFalse(root.resolve("dest").exists())
    }

    @Test fun strictImportRejectsParentRemovedByBeforeMutationAndClosesInput() = withWorkspace { root ->
        root.resolve("dest").mkdir()
        var closed = false
        val input = object : ByteArrayInputStream("new".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        val repo = FileRepository(root, beforeMutation = { check(root.resolve("dest").delete()) })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.writeStream("dest/new.txt", input, requireAbsent = true, requireExistingParent = true) }
        }
        assertTrue(closed)
        assertFalse(root.resolve("dest").exists())
    }

    @Test fun strictDirectoryCreationRejectsParentRemovedByBeforeMutation() = withWorkspace { root ->
        root.resolve("dest").mkdir()
        val repo = FileRepository(root, beforeMutation = { check(root.resolve("dest").delete()) })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.createDirectory("dest/new", requireExistingParent = true) }
        }
        assertFalse(root.resolve("dest").exists())
    }

    @Test fun strictMoveRejectsParentRemovedByBeforeMutationAndPreservesSource() = withWorkspace { root ->
        root.resolve("dest").mkdir()
        root.resolve("source.txt").writeText("source")
        val repo = FileRepository(root, beforeMutation = { check(root.resolve("dest").delete()) })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.move("source.txt", "dest/moved.txt", requireExistingParent = true) }
        }
        assertFalse(root.resolve("dest").exists())
        assertEquals("source", root.resolve("source.txt").readText())
    }

    @Test fun moveDestinationCreatedDuringCallbackIsPreservedWithOriginalSource() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        val repo = FileRepository(root, beforeMutation = { root.resolve("moved.txt").writeText("from terminal") })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.move("source.txt", "moved.txt", requireExistingParent = true) }
        }
        assertEquals("source", root.resolve("source.txt").readText())
        assertEquals("from terminal", root.resolve("moved.txt").readText())
    }

    @Test fun strictTargetRevalidationRejectsParentReplacedByInWorkspaceAlias() = withWorkspace { root ->
        root.resolve("dest").mkdir()
        root.resolve("other").mkdir()
        val repo = FileRepository(root, beforeMutation = {
            check(root.resolve("dest").delete())
            Files.createSymbolicLink(root.resolve("dest").toPath(), root.resolve("other").toPath())
        })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.writeTextIfUnchanged("dest/new.txt", "new", DiskFileVersion.fromText(null), requireExistingParent = true) }
        }
        assertFalse(root.resolve("other/new.txt").exists())
    }

    @Test fun strictImportRechecksParentAtCommitAndNeverPublishesThroughNewAlias() = withWorkspace { root ->
        root.resolve("dest").mkdir()
        root.resolve("other").mkdir()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                FileRepository(root).writeStream("dest/new.txt", ByteArrayInputStream("new".toByteArray()),
                    requireAbsent = true, requireExistingParent = true, onProgress = { progress ->
                        if (progress.phase == StreamWritePhase.COMMITTING) {
                            check(root.resolve("dest").renameTo(root.resolve("detached")))
                            Files.createSymbolicLink(root.resolve("dest").toPath(), root.resolve("other").toPath())
                        }
                    })
            }
        }
        assertFalse(root.resolve("other/new.txt").exists())
        assertFalse(root.resolve("detached/new.txt").exists())
    }

    @Test fun defaultRepositoryMutationsStillCreateParentDirectoriesForRuntimeCallers() = withWorkspace { root ->
        val repo = FileRepository(root)
        runBlocking {
            repo.writeTextIfUnchanged("generated/deep/new.txt", "new", DiskFileVersion.fromText(null))
            repo.writeStream("imports/deep/new.txt", ByteArrayInputStream("new".toByteArray()))
            assertTrue(repo.createDirectory("folders/deep/new"))
            assertTrue(repo.move("generated/deep/new.txt", "moved/deep/new.txt"))
        }
        assertEquals("new", root.resolve("imports/deep/new.txt").readText())
        assertEquals("new", root.resolve("moved/deep/new.txt").readText())
    }

    @Test fun failedNewTextSynchronizationPreservesForeignReplacementPublishedDuringCallback() = withWorkspace { root ->
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                root.resolve("replacement.txt").writeText("from terminal")
                Files.move(root.resolve("replacement.txt").toPath(), root.resolve("target.txt").toPath(), StandardCopyOption.REPLACE_EXISTING)
                error("sync failed")
            }
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.writeTextIfUnchanged("target.txt", "new", DiskFileVersion.fromText(null), requireExistingParent = true) }
        }
        assertEquals("from terminal", root.resolve("target.txt").readText())
        val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals("new".toByteArray(), retained.readBytes())
        assertEquals(setOf("target.txt", retained.name), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun strictRepositoryMutationRejectsOriginalDestinationAliasBeforeCanonicalResolutionHidesIt() = withWorkspace { root ->
        root.resolve("ordinary").mkdir()
        root.resolve("source.txt").writeText("source")
        Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve("ordinary").toPath())
        val repo = FileRepository(root)
        val operations: List<suspend () -> Unit> = listOf(
            { repo.writeTextIfUnchanged("alias/new.txt", "new", DiskFileVersion.fromText(null), requireExistingParent = true) },
            { repo.writeStream("alias/import.txt", ByteArrayInputStream("new".toByteArray()), requireAbsent = true, requireExistingParent = true) },
            { repo.createDirectory("alias/folder", requireExistingParent = true); Unit },
            { repo.move("source.txt", "alias/moved.txt", requireExistingParent = true); Unit },
        )
        operations.forEach { operation ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { operation() } }
            assertTrue(root.resolve("ordinary").listFiles()!!.isEmpty())
            assertEquals("source", root.resolve("source.txt").readText())
        }
    }

    @Test fun strictMoveRejectsSourceReplacedByAliasWhileCallbackWasSuspended() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        root.resolve("other.txt").writeText("other")
        val repo = FileRepository(root, beforeMutation = {
            check(root.resolve("source.txt").renameTo(root.resolve("preserved.txt")))
            Files.createSymbolicLink(root.resolve("source.txt").toPath(), root.resolve("other.txt").toPath())
        })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.move("source.txt", "moved.txt", requireExistingParent = true) }
        }
        assertFalse(root.resolve("moved.txt").exists())
        assertEquals("other", root.resolve("other.txt").readText())
        assertEquals("source", root.resolve("preserved.txt").readText())
    }

    @Test fun strictMoveAllowsExplicitUserFileRecoveryFromInternalTreeToProjectRoot() = withWorkspace { root ->
        root.resolve(".droide").mkdir()
        root.resolve(".droide/MainActivity.kt").writeText("user source")
        assertTrue(runBlocking { FileRepository(root).move(".droide/MainActivity.kt", "MainActivity.kt", requireExistingParent = true) })
        assertEquals("user source", root.resolve("MainActivity.kt").readText())
        assertFalse(root.resolve(".droide/MainActivity.kt").exists())
    }

    @Test fun strictMoveFailedSynchronizationPreservesRecreatedSourceAndMovedDestination() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        val reconciled = mutableListOf<FileRepository.Mutation>()
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Move) {
                root.resolve("source.txt").writeText("new source from terminal")
                error("sync failed")
            } else reconciled += mutation
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.move("source.txt", "moved.txt", requireExistingParent = true) }
        }
        assertEquals("new source from terminal", root.resolve("source.txt").readText())
        assertEquals("source", root.resolve("moved.txt").readText())
        assertEquals(listOf(FileRepository.Mutation.Sync("source.txt"), FileRepository.Mutation.Sync("moved.txt")), reconciled)
    }

    @Test fun strictMoveFailedSynchronizationDoesNotRelocateForeignDestinationReplacement() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        val reconciled = mutableListOf<FileRepository.Mutation>()
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Move) {
                root.resolve("replacement.txt").writeText("from terminal")
                Files.move(root.resolve("replacement.txt").toPath(), root.resolve("moved.txt").toPath(), StandardCopyOption.REPLACE_EXISTING)
                error("sync failed")
            } else reconciled += mutation
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.move("source.txt", "moved.txt", requireExistingParent = true) }
        }
        assertFalse(root.resolve("source.txt").exists())
        assertEquals("from terminal", root.resolve("moved.txt").readText())
        assertEquals(listOf(FileRepository.Mutation.Sync("source.txt"), FileRepository.Mutation.Sync("moved.txt")), reconciled)
    }

    @Test fun strictMoveFailedSynchronizationRollsBackOwnedDestinationWhenSourceIsStillAbsent() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        val reconciled = mutableListOf<FileRepository.Mutation>()
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Move) error("sync failed") else reconciled += mutation
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.move("source.txt", "moved.txt", requireExistingParent = true) }
        }
        assertEquals("source", root.resolve("source.txt").readText())
        assertFalse(root.resolve("moved.txt").exists())
        assertEquals(listOf(FileRepository.Mutation.Sync("source.txt"), FileRepository.Mutation.Delete("moved.txt")), reconciled)
    }

    @Test fun strictMoveRejectsRegularSourceReplacementDuringSuspendedAdmission() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        rejectedSuspendedMove(root, "source.txt", "moved.txt") {
            check(root.resolve("source.txt").renameTo(root.resolve("preserved.txt")))
            root.resolve("source.txt").writeText("replacement")
        }
        assertEquals("source", root.resolve("preserved.txt").readText())
        assertEquals("replacement", root.resolve("source.txt").readText())
        assertFalse(root.resolve("moved.txt").exists())
    }

    @Test fun strictMoveRejectsSourceEditDuringSuspendedAdmission() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        rejectedSuspendedMove(root, "source.txt", "moved.txt") {
            root.resolve("source.txt").writeText("newer longer contents")
        }
        assertEquals("newer longer contents", root.resolve("source.txt").readText())
        assertFalse(root.resolve("moved.txt").exists())
    }

    @Test fun strictMoveRejectsDestinationParentReplacementDuringSuspendedAdmission() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        root.resolve("dest").mkdir()
        rejectedSuspendedMove(root, "source.txt", "dest/moved.txt") {
            check(root.resolve("dest").renameTo(root.resolve("preserved-dest")))
            check(root.resolve("dest").mkdir())
            root.resolve("dest/foreign.txt").writeText("foreign")
        }
        assertEquals("source", root.resolve("source.txt").readText())
        assertEquals("foreign", root.resolve("dest/foreign.txt").readText())
        assertTrue(root.resolve("preserved-dest").listFiles()!!.isEmpty())
        assertFalse(root.resolve("dest/moved.txt").exists())
    }

    @Test fun strictMoveRejectsSourceParentReplacementEvenWhenSourceInodeIsRetained() = withWorkspace { root ->
        root.resolve("src").mkdir()
        root.resolve("src/source.txt").writeText("source")
        rejectedSuspendedMove(root, "src/source.txt", "moved.txt") {
            check(root.resolve("src").renameTo(root.resolve("preserved-src")))
            check(root.resolve("src").mkdir())
            check(root.resolve("preserved-src/source.txt").renameTo(root.resolve("src/source.txt")))
        }
        assertEquals("source", root.resolve("src/source.txt").readText())
        assertTrue(root.resolve("preserved-src").isDirectory)
        assertFalse(root.resolve("moved.txt").exists())
    }

    @Test fun cancelledStrictMoveAdmissionNeverMovesTheSource() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        runBlocking { withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val repository = FileRepository(root, beforeMutation = { entered.complete(Unit); resume.await() })
            val move = async { repository.move("source.txt", "moved.txt", requireExistingParent = true) }
            entered.await()
            move.cancelAndJoin()
        } }
        assertEquals("source", root.resolve("source.txt").readText())
        assertFalse(root.resolve("moved.txt").exists())
    }

    @Test fun observedMoveCannotBeSubmittedToAnotherWorkspace() = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        val otherRoot = root.resolve("other").also { it.mkdir() }
        otherRoot.resolve("source.txt").writeText("other")
        val request = FileRepository(root).observeMove("source.txt", "moved.txt")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { FileRepository(otherRoot).moveObserved(request) }
        }
        assertEquals("source", root.resolve("source.txt").readText())
        assertEquals("other", otherRoot.resolve("source.txt").readText())
        assertFalse(root.resolve("moved.txt").exists())
        assertFalse(otherRoot.resolve("moved.txt").exists())
    }

    @Test fun strictMoveRejectsSourceReplacementInParticipantBegin() =
        rejectParticipantReplacement("begin", replaceSource = true)

    @Test fun strictMoveRejectsParentReplacementInParticipantBegin() =
        rejectParticipantReplacement("begin")

    @Test fun strictMoveRejectsParentReplacementInParticipantPreparation() =
        rejectParticipantReplacement("prepare")

    @Test fun strictMoveRejectsParentReplacementImmediatelyBeforeLocalAttempt() =
        rejectParticipantReplacement("attempt")

    private fun rejectParticipantReplacement(phase: String, replaceSource: Boolean = false) = withWorkspace { root ->
        root.resolve("source.txt").writeText("source")
        root.resolve("dest").mkdir()
        var moves = 0
        var synchronizations = 0
        fun replace() {
            if (replaceSource) {
                check(root.resolve("source.txt").renameTo(root.resolve("preserved.txt")))
                root.resolve("source.txt").writeText("replacement")
            } else {
                check(root.resolve("dest").renameTo(root.resolve("preserved-dest")))
                check(root.resolve("dest").mkdir())
            }
        }
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            if (phase == "begin") replace()
            object : WorkspaceMutationSession(guard, mutation) {
                override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                    if (phase == "prepare") replace()
                }
                override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) {
                    if (phase == "attempt") replace()
                }
                override suspend fun synchronize() { synchronizations++ }
            }
        }
        val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform,
            pathMover = WorkspacePathMove { _, _ -> moves++; error("Unexpected local move") },
            participant = participant)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.move("source.txt", "dest/moved.txt", requireExistingParent = true) }
        }
        assertEquals(0, moves)
        assertEquals(0, synchronizations)
        assertEquals(if (replaceSource) "replacement" else "source", root.resolve("source.txt").readText())
        if (replaceSource) assertEquals("source", root.resolve("preserved.txt").readText())
        else assertTrue(root.resolve("preserved-dest").isDirectory)
        assertFalse(root.resolve("dest/moved.txt").exists())
    }

    private fun rejectedSuspendedMove(root: File, source: String, destination: String, change: () -> Unit) = runBlocking { withTimeout(10_000) {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var mutations = 0
        val repository = FileRepository(root,
            beforeMutation = { entered.complete(Unit); resume.await() }, onMutation = { mutations++ })
        // Capture the failure inside async so it cannot cancel the admission controller.
        val move = async { runCatching { repository.move(source, destination, requireExistingParent = true) } }
        entered.await()
        change()
        resume.complete(Unit)
        val failure = move.await().exceptionOrNull()
        assertTrue("Expected identity rejection, got $failure", failure is IllegalStateException)
        assertEquals(0, mutations)
    } }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-explorer-mutation-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

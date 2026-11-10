package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileRepositoryReceiptStageRecoveryTest {
    @Test fun textParticipantMovingPublishedInodeBackToItsStageCannotLoseTheProposal() = runBlocking {
        fixture("text-participant") { root ->
            var stage: File? = null
            var callbacks = 0
            val participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                        if (preparation.effect == WorkspaceLocalEffect.WRITE) stage = root.resolve(preparation.sourcePath!!)
                    }

                    override fun localReturned(receipt: WorkspaceLocalReceipt) {
                        if (receipt.preparation.effect == WorkspaceLocalEffect.WRITE && receipt.successful) {
                            callbacks++
                            returnPublishedToStage(root.resolve(receipt.preparation.relativePath), stage!!)
                        }
                    }
                }
            }
            val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native(), participant = participant)
            val failure = runCatching {
                repository.writeTextIfUnchanged("proposal.txt", PAYLOAD, DiskFileVersion.fromText(null))
            }.exceptionOrNull()
            verify(root, stage!!, callbacks, failure)
        }
    }

    @Test fun streamParticipantMovingPublishedInodeBackToItsStageCannotLoseTheProposal() = runBlocking {
        fixture("stream-participant") { root ->
            var stage: File? = null
            var callbacks = 0
            val participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                        if (preparation.effect == WorkspaceLocalEffect.WRITE) stage = root.resolve(preparation.sourcePath!!)
                    }

                    override fun localReturned(receipt: WorkspaceLocalReceipt) {
                        if (receipt.preparation.effect == WorkspaceLocalEffect.WRITE && receipt.successful) {
                            callbacks++
                            assertTrue(receipt.durable)
                            returnPublishedToStage(root.resolve(receipt.preparation.relativePath), stage!!)
                        }
                    }
                }
            }
            val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native(), participant = participant)
            val failure = runCatching {
                repository.writeStream("proposal.txt", PAYLOAD.byteInputStream(), requireAbsent = true)
            }.exceptionOrNull()
            verify(root, stage!!, callbacks, failure)
            assertTrue("Stream outcome must be uncertain: $failure", failure is StreamPublicationUncertainException)
        }
    }

    @Test fun streamObserverMovingPublishedInodeBackToItsStageCannotLoseTheProposal() = runBlocking {
        fixture("stream-observer") { root ->
            var stage: File? = null
            var callbacks = 0
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) {
                    stage = root.listFiles()!!.single { it.isFile && it.name.startsWith(".droide-tmp-") }
                    assertEquals(preparation.stageIdentity, ProjectCopyEvidence.identity(stage!!).key)
                }

                override fun onPublished(receipt: StreamPublicationReceipt) {
                    callbacks++
                    returnPublishedToStage(root.resolve(receipt.preparation.relativePath), stage!!)
                }
            }
            val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native())
            val failure = runCatching {
                repository.writeStream("proposal.txt", PAYLOAD.byteInputStream(), requireAbsent = true, publicationObserver = observer)
            }.exceptionOrNull()
            verify(root, stage!!, callbacks, failure)
            assertTrue("Stream outcome must be uncertain: $failure", failure is StreamPublicationUncertainException)
        }
    }

    @Test fun textParticipantSynchronizationMovingPublishedInodeBackCannotLoseTheProposal() = runBlocking {
        fixture("text-participant-sync") { root ->
            var stage: File? = null
            var callbacks = 0
            val participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                        if (preparation.effect == WorkspaceLocalEffect.WRITE) stage = root.resolve(preparation.sourcePath!!)
                    }

                    override suspend fun synchronize() {
                        callbacks++
                        returnPublishedToStage(root.resolve("proposal.txt"), stage!!)
                    }
                }
            }
            val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native(), participant = participant)
            val failure = runCatching {
                repository.writeTextIfUnchanged("proposal.txt", PAYLOAD, DiskFileVersion.fromText(null))
            }.exceptionOrNull()
            verify(root, stage!!, callbacks, failure)
        }
    }

    @Test fun streamParticipantSynchronizationFailureCannotLoseTheProposalOrOriginalFailure() = runBlocking {
        fixture("stream-participant-sync-failure") { root ->
            var stage: File? = null
            var callbacks = 0
            val original = java.io.IOException("participant synchronization failed after moving proposal")
            val participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                        if (preparation.effect == WorkspaceLocalEffect.WRITE) stage = root.resolve(preparation.sourcePath!!)
                    }

                    override suspend fun synchronize() {
                        callbacks++
                        returnPublishedToStage(root.resolve("proposal.txt"), stage!!)
                        throw original
                    }
                }
            }
            val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native(), participant = participant)
            val failure = runCatching {
                repository.writeStream("proposal.txt", PAYLOAD.byteInputStream(), requireAbsent = true)
            }.exceptionOrNull()
            verify(root, stage!!, callbacks, failure)
            assertTrue("Original synchronization failure must be preserved", containsFailure(failure!!, original))
        }
    }

    @Test fun textOnMutationWithoutParticipantFailureCannotLoseTheProposalOrOriginalFailure() = runBlocking {
        fixture("text-onmutation-failure") { root ->
            var stage: File? = null
            var callbacks = 0
            val original = java.io.IOException("observer synchronization failed after moving proposal")
            val repository = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write) {
                    callbacks++
                    returnPublishedToStage(root.resolve(mutation.path), stage!!)
                    throw original
                }
            }, newFilePublisher = NewProjectFilePublisher { prepared, destination ->
                stage = prepared
                NewProjectFilePublisher.native().publish(prepared, destination)
            })
            val failure = runCatching {
                repository.writeTextIfUnchanged("proposal.txt", PAYLOAD, DiskFileVersion.fromText(null))
            }.exceptionOrNull()
            verify(root, stage!!, callbacks, failure)
            assertTrue("Original synchronization failure must be preserved", containsFailure(failure!!, original))
        }
    }

    @Test fun streamOnMutationWithoutParticipantMovingPublishedInodeBackCannotLoseTheProposal() = runBlocking {
        fixture("stream-onmutation") { root ->
            var stage: File? = null
            var callbacks = 0
            val repository = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write) {
                    callbacks++
                    returnPublishedToStage(root.resolve(mutation.path), stage!!)
                }
            }, newFilePublisher = NewProjectFilePublisher { prepared, destination ->
                stage = prepared
                NewProjectFilePublisher.native().publish(prepared, destination)
            })
            val failure = runCatching {
                repository.writeStream("proposal.txt", PAYLOAD.byteInputStream(), requireAbsent = true)
            }.exceptionOrNull()
            verify(root, stage!!, callbacks, failure)
        }
    }

    private fun containsFailure(actual: Throwable, expected: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val remaining = java.util.ArrayDeque<Throwable>()
        remaining.add(actual)
        while (remaining.isNotEmpty()) {
            val current = remaining.removeFirst()
            if (!seen.add(current)) continue
            if (current === expected) return true
            current.cause?.let(remaining::add)
            current.suppressed.forEach(remaining::add)
        }
        return false
    }

    private fun returnPublishedToStage(published: File, stage: File) {
        assertFalse("Native publication must have consumed the stage name", stage.exists())
        assertEquals(PAYLOAD, published.readText())
        NewProjectFilePublisher.native().publish(published, stage)
        assertFalse(published.exists())
        assertEquals(PAYLOAD, stage.readText())
    }

    private fun verify(root: File, stage: File, callbacks: Int, failure: Throwable?) {
        assertEquals(1, callbacks)
        assertFalse("The callback deliberately moved the canonical name", root.resolve("proposal.txt").exists())
        assertTrue("Cleanup lost the only named proposal at $stage; returned failure=$failure", stage.isFile)
        assertEquals(PAYLOAD, stage.readText())
        assertNotNull("A missing canonical destination must not return successful publication", failure)
    }

    private suspend fun fixture(name: String, action: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("droide-receipt-recovery-$name-").toFile()
        try { action(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private companion object {
        const val PAYLOAD = "the only unpublished proposal must remain named"
    }
}

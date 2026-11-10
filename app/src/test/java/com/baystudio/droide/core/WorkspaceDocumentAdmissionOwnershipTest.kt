package com.baystudio.droide.core

import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import java.io.File
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceDocumentAdmissionOwnershipTest {
    @get:Rule val temp = TemporaryFolder()
    private val mutation = FileRepository.Mutation.Write("file.txt", "proposal")

    private class Authority(private val validate: suspend () -> Unit = {}) : WorkspaceDocumentAuthority {
        var calls = 0
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = error("Admission must not scan every document")
        override suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) {
            calls++
            validate()
        }
    }

    @Test fun neverBoundHeadlessWorkspaceRemainsWritable() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        bridge.validatePersistedMutation(mutation)
        val root = temp.newFolder()
        val files = FileRepository(root, beforeMutation = bridge::validatePersistedMutation,
            onMutation = bridge::persistedMutation)
        files.writeText("file.txt", "headless content")
        assertEquals("headless content", File(root, "file.txt").readText())
        assertEquals(1L, bridge.persistedRevision.value)
    }

    @Test fun unchangedOwnerValidatesOnceWithoutScanning() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val authority = Authority()
        bridge.bind(authority)
        bridge.validatePersistedMutation(mutation)
        assertEquals(1, authority.calls)
        assertEquals(0L, bridge.persistedRevision.value)
    }

    @Test(timeout = 10_000) fun replacementDuringSuspendedAdmissionInvalidatesTheOldResult() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val old = Authority { entered.complete(Unit); resume.await() }
        val next = Authority { error("A different owner must not be silently retried") }
        bridge.bind(old)
        val work = async { runCatching { bridge.validatePersistedMutation(mutation) } }
        entered.await()
        bridge.bind(next)
        resume.complete(Unit)
        assertTrue(work.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, old.calls)
        assertEquals(0, next.calls)
    }

    @Test(timeout = 10_000) fun detachmentDuringSuspendedAdmissionInvalidatesTheOldResult() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val binding = bridge.bind(Authority { entered.complete(Unit); resume.await() })
        val work = async { runCatching { bridge.validatePersistedMutation(mutation) } }
        entered.await(); binding.close(); resume.complete(Unit)
        assertTrue(work.await().exceptionOrNull() is IllegalStateException)
        assertFalse(bridge.isBound)
    }

    @Test(timeout = 10_000) fun rebindingTheSameObjectStillInvalidatesItsOldAdmission() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val authority = Authority { entered.complete(Unit); resume.await() }
        val binding = bridge.bind(authority)
        val work = async { runCatching { bridge.validatePersistedMutation(mutation) } }
        entered.await(); binding.close(); bridge.bind(authority); resume.complete(Unit)
        assertTrue(work.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, authority.calls)
    }

    @Test(timeout = 10_000) fun closingAStaleBindingDoesNotInvalidateTheCurrentOwner() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val stale = bridge.bind(Authority())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        bridge.bind(Authority { entered.complete(Unit); resume.await() })
        val work = async { runCatching { bridge.validatePersistedMutation(mutation) } }
        entered.await(); stale.close(); resume.complete(Unit)
        assertTrue(work.await().isSuccess)
        assertTrue(bridge.isBound)
    }

    @Test fun detachedPreviouslyBoundAuthorityCannotBeTreatedAsAnEmptyEditor() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        bridge.bind(Authority()).close()
        val mutations = listOf(mutation, FileRepository.Mutation.Delete("file.txt"),
            FileRepository.Mutation.Move("file.txt", "renamed.txt"), FileRepository.Mutation.CreateDirectory("nested"))
        for (item in mutations) {
            val failure = runCatching { bridge.validatePersistedMutation(item) }.exceptionOrNull()
            assertTrue("Detached authority admitted $item", failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("detached"))
        }
    }

    @Test fun explicitRebindRestoresAdmissionWithoutChangingThePersistedRevision() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        bridge.bind(Authority()).close()
        assertTrue(runCatching { bridge.validatePersistedMutation(mutation) }.isFailure)
        val next = Authority()
        bridge.bind(next)
        bridge.validatePersistedMutation(mutation)
        assertEquals(1, next.calls)
        assertEquals(0L, bridge.persistedRevision.value)
    }

    @Test fun synchronousReentrantRebindAlsoRejectsStaleAdmission() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val next = Authority()
        bridge.bind(Authority { bridge.bind(next) })
        assertTrue(runCatching { bridge.validatePersistedMutation(mutation) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, next.calls)
    }

    @Test fun originalValidationFailureIsNotMaskedByAnOwnerChange() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val original = IOException("original document validation failure")
        bridge.bind(Authority { bridge.bind(Authority()); throw original })
        assertSame(original, runCatching { bridge.validatePersistedMutation(mutation) }.exceptionOrNull())
    }

    @Test(timeout = 10_000) fun cancellationDuringAdmissionDoesNotBecomeAnOwnershipFailure() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val entered = CompletableDeferred<Unit>()
        var observed: Throwable? = null
        bridge.bind(Authority { entered.complete(Unit); awaitCancellation() })
        val work = launch {
            try { bridge.validatePersistedMutation(mutation) }
            catch (failure: Throwable) { observed = failure; throw failure }
        }
        entered.await(); bridge.bind(Authority()); work.cancelAndJoin()
        assertTrue(observed is CancellationException)
        assertEquals(0L, bridge.persistedRevision.value)
    }

    @Test(timeout = 15_000) fun finalStreamAdmissionRejectsReboundDirtyEditorBeforePublication() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "file.txt").apply { writeText("original") }
        val bridge = WorkspaceDocumentAuthorityBridge()
        val files = FileRepository(root, beforeMutation = bridge::validatePersistedMutation,
            onMutation = bridge::persistedMutation)
        val oldState = EditorWorkspaceState()
        oldState.document("file.txt").ensureLoaded(files)
        val old = EditorWorkspaceDocumentAuthority(oldState, owner, files = files)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var validations = 0
        bridge.bind(object : WorkspaceDocumentAuthority by old {
            override suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) {
                old.validatePersistedMutation(mutation)
                if (++validations == 2) { entered.complete(Unit); resume.await() }
            }
        })
        val nextState = EditorWorkspaceState()
        val document = nextState.document("file.txt").also { it.ensureLoaded(files); it.capture("unsaved user edit") }
        val expected = StreamFileVersion.capture(root, "file.txt")
        val proposal = "CLI proposal".toByteArray()
        var published = 0
        val work = async { runCatching {
            files.writeStream("file.txt", proposal.inputStream(), expectedBytes = proposal.size.toLong(), expectedVersion = expected,
                publicationObserver = object : StreamPublicationObserver {
                    override fun beforePublish(preparation: StreamPublicationPreparation) = Unit
                    override fun onPublished(receipt: StreamPublicationReceipt) { published++ }
                })
        } }
        entered.await()
        bridge.bind(EditorWorkspaceDocumentAuthority(nextState, owner, files = files))
        resume.complete(Unit)
        assertTrue(work.await().isFailure)
        assertEquals("The stale owner must not authorize a disk publication", 0, published)
        assertEquals("original", target.readText())
        assertEquals("unsaved user edit", document.content)
        assertTrue(document.dirty)
        assertEquals(0L, bridge.persistedRevision.value)
        document.capture("original")
        files.writeStream("file.txt", proposal.inputStream(), expectedBytes = proposal.size.toLong(), expectedVersion = expected)
        assertEquals("CLI proposal", target.readText())
        assertEquals("CLI proposal", document.content)
        assertFalse(document.dirty)
    }

    @Test(timeout = 15_000) fun detachedRealEditorCannotPermitAnOrphanedRepositoryWrite() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "file.txt").apply { writeText("original") }
        val bridge = WorkspaceDocumentAuthorityBridge()
        val files = FileRepository(root, beforeMutation = bridge::validatePersistedMutation, onMutation = bridge::persistedMutation)
        val state = EditorWorkspaceState()
        val document = state.document("file.txt").also { it.ensureLoaded(files); it.capture("unsaved edit") }
        bridge.bind(EditorWorkspaceDocumentAuthority(state, owner, files = files)).close()
        assertTrue(runCatching { files.writeText("file.txt", "unexpected replacement") }.isFailure)
        assertEquals("original", target.readText())
        assertEquals("unsaved edit", document.content)
        assertTrue(document.dirty)
        assertEquals(0L, bridge.persistedRevision.value)
    }

    @Test(timeout = 15_000) fun unrelatedDirtyDocumentDoesNotBlockAStableOwnersWrite() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        File(root, "other.txt").writeText("other original")
        val bridge = WorkspaceDocumentAuthorityBridge()
        val files = FileRepository(root, beforeMutation = bridge::validatePersistedMutation, onMutation = bridge::persistedMutation)
        val state = EditorWorkspaceState()
        val other = state.document("other.txt").also { it.ensureLoaded(files); it.capture("other unsaved") }
        bridge.bind(EditorWorkspaceDocumentAuthority(state, owner, files = files))
        files.writeText("file.txt", "allowed")
        assertEquals("allowed", File(root, "file.txt").readText())
        assertEquals("other unsaved", other.content)
        assertTrue(other.dirty)
    }

    @Test fun alreadyPersistedChangesStillInvalidateExplorerAfterDetachment() = runBlocking {
        var notifications = 0
        val bridge = WorkspaceDocumentAuthorityBridge { notifications++ }
        bridge.bind(Authority()).close()
        bridge.persistedMutation(mutation)
        bridge.reconcileAfterPersistedMutation()
        assertEquals(2L, bridge.persistedRevision.value)
        assertEquals(2, notifications)
    }
}

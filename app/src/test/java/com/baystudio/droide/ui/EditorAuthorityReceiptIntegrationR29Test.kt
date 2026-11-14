package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import java.io.File
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual authority callback integration; tiny local files and existing coroutine dispatchers. */
class EditorAuthorityReceiptIntegrationR29Test {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun matchingAuthorityCallbackDefersRecoveryAcknowledgementUntilOwnedReceipt() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        File(root, "main.txt").writeText("A")
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { mutation ->
            authority.persistedMutation(mutation)
            if (mutation is FileRepository.Mutation.Write) withContext(owner) {
                assertTrue(document.isSaveInProgress)
                assertEquals("A", document.savedContent)
                assertTrue(document.dirty)
            }
        })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files)
        document.restoreUnsaved("B", document.savedDiskVersion)
        assertTrue(document.save(files).isSuccess)
        assertEquals("B", document.savedContent)
        assertFalse(document.dirty)
        assertFalse(document.isSaveInProgress)
    }

    @Test(timeout = 15_000)
    fun cancelledOwnedReceiptAcknowledgesAfterAuthorityDefersMatchingText() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        File(root, "main.txt").writeText("A")
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { mutation ->
            authority.persistedMutation(mutation)
            if (mutation is FileRepository.Mutation.Write) {
                withContext(owner) { assertEquals("A", document.savedContent); assertTrue(document.dirty) }
                throw CancellationException("after deferred authority acknowledgement")
            }
        })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files); document.restoreUnsaved("B", document.savedDiskVersion)
        assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        assertEquals("B", document.savedContent)
        assertFalse(document.dirty)
        assertTrue(document.saveSynchronizationPending)
        assertFalse(document.isSaveInProgress)
    }

    @Test(timeout = 15_000)
    fun syncFallbackDoesNotRemoveCleanLookingUndoWhileSaveIsInFlight() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("A") }
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        var fallback = false
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                withContext(owner) {
                    document.capture("A")
                    assertFalse(document.dirty)
                    assertTrue(document.isSaveInProgress)
                }
                assertTrue(target.delete())
                throw IOException("foreign deletion during observer")
            }
            if (mutation is FileRepository.Mutation.Sync) fallback = true
            authority.persistedMutation(mutation)
        })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files); document.capture("B")
        assertTrue(document.save(files).isFailure)
        assertTrue(fallback)
        assertSame("In-flight Undo must not be removed by the Sync fallback", document, state.peek("main.txt"))
        assertEquals("A", document.content)
        assertEquals("A", document.savedContent)
        assertTrue(document.loaded)
        assertFalse(target.exists())
    }

    @Test(timeout = 15_000)
    fun syncOnlyThroughActualAuthorityPreservesAcceptedTokenAndNoWriteReceipt() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        File(root, "main.txt").writeText("A")
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        var failFirst = true
        var admissions = 0
        val files = FileRepository(root, beforeMutation = { admissions++; authority.validatePersistedMutation(it) }, onMutation = { mutation ->
            authority.persistedMutation(mutation)
            if (mutation is FileRepository.Mutation.Write && failFirst) {
                failFirst = false
                throw IOException("first observer failure")
            }
        })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files); document.capture("B")
        assertNotNull(document.save(files).getOrThrow().synchronizationFailure)
        val accepted = document.savedDiskVersion
        assertTrue(document.saveSynchronizationPending)
        val retry = document.save(files).getOrThrow()
        assertFalse(retry.didWrite)
        assertSame(accepted, document.savedDiskVersion)
        assertFalse(document.dirty)
        assertFalse(document.saveSynchronizationPending)
        assertEquals(1, admissions)
    }

    @Test(timeout = 15_000)
    fun matchingIdleExternalMutationStillAcknowledgesDirtyBuffer() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        File(root, "main.txt").writeText("A")
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { authority.persistedMutation(it) })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files); document.capture("B")
        assertTrue(document.dirty)
        files.writeText("main.txt", "B")
        assertFalse(document.isSaveInProgress)
        assertEquals("B", document.savedContent)
        assertFalse(document.dirty)
    }
}

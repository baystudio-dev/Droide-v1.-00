package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Original R28 APIs only; real authority callback, tiny local files, no new executor. */
class EditorSaveAuthorityBoundaryR29Test {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun undoToOriginalTextDuringSaveSurvivesWriteCallbackAndSyncFallback() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        var syncCallbacks = 0
        var undo = true
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write && undo) {
                undo = false
                withContext(owner) { document.capture("original A") }
            }
            if (mutation is FileRepository.Mutation.Sync) syncCallbacks++
            authority.persistedMutation(mutation)
        })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files); document.capture("saved B")
        val receipt = document.save(files).getOrThrow()
        assertEquals("The newer Undo must not be overwritten by the persisted callback", "original A", document.content)
        assertEquals("saved B", target.readText())
        assertEquals("saved B", document.savedContent)
        assertTrue(document.dirty)
        assertTrue(receipt.synchronizationFailure?.cause is WorkspaceDocumentConflictException)
        assertEquals(1, syncCallbacks)
        assertSame(document, state.peek("main.txt"))
    }

    @Test(timeout = 15_000)
    fun authorityCallbackCannotAcknowledgeBeforeForeignSameByteReplacementIsChecked() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        var owned: Any? = null
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { mutation ->
            authority.persistedMutation(mutation)
            if (mutation is FileRepository.Mutation.Write) {
                owned = key(target)
                val foreign = File(root, "foreign.tmp").apply { writeText("saved B") }
                assertNotEquals(owned, key(foreign))
                Files.move(foreign.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files); document.capture("saved B")
        val outcome = document.save(files)
        assertEquals("The authority must not commit the editor's baseline before receipt completion", "original A", document.savedContent)
        assertTrue(outcome.isFailure)
        assertEquals("saved B", document.content)
        assertEquals("saved B", target.readText())
        assertNotEquals(owned, key(target))
        assertTrue(document.dirty)
        assertFalse(document.saveSynchronizationPending)
    }

    @Test(timeout = 15_000)
    fun ordinaryOwnedSaveThroughActualAuthorityStillAcknowledges() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { authority.persistedMutation(it) })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files); document.capture("saved B")
        val receipt = document.save(files).getOrThrow()
        assertTrue(receipt.didWrite)
        assertNull(receipt.synchronizationFailure)
        assertEquals("saved B", target.readText())
        assertEquals("saved B", document.savedContent)
        assertEquals("saved B", document.content)
        assertFalse(document.dirty)
        assertFalse(document.saveSynchronizationPending)
    }

    @Test(timeout = 15_000)
    fun idleExternalWriteStillReconcilesThroughActualAuthority() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { authority.persistedMutation(it) })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files)
        files.writeText("main.txt", "external C")
        assertEquals("external C", target.readText())
        assertEquals("external C", document.content)
        assertEquals("external C", document.savedContent)
        assertFalse(document.dirty)
    }

    private fun key(file: File): Any = requireNotNull(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
}

package com.baystudio.droide.ui

import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Existing model/authority APIs; tiny local files, no process, provider, network or new executor. */
class EditorCleanAbsenceRefreshR29Test {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun cleanAbsentDocumentAdoptsExternallyCreatedEmptyFileDuringRefresh() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "new.txt")
        val state = EditorWorkspaceState()
        val document = state.document("new.txt")
        lateinit var authority: EditorWorkspaceDocumentAuthority
        val files = FileRepository(root, beforeMutation = { authority.validatePersistedMutation(it) }, onMutation = { authority.persistedMutation(it) })
        authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files)
        assertEquals(DiskFileVersion.fromText(null), document.savedDiskVersion)
        assertEquals("", document.content)
        assertFalse(document.dirty)
        target.writeText("") // External creation bypasses the outbound Write callback.
        authority.persistedMutation(FileRepository.Mutation.Sync("new.txt"))
        assertEquals("Clean refresh must adopt observed existence even when text is unchanged",
            DiskFileVersion.fromText(""), document.savedDiskVersion)
        assertFalse(document.dirty)
        document.capture("next edit")
        assertTrue("A successfully refreshed clean baseline must permit a later ordinary Save", document.save(files).isSuccess)
        assertEquals("next edit", target.readText())
        assertFalse(document.dirty)
    }

    @Test(timeout = 15_000)
    fun dirtyAbsentDocumentKeepsItsOriginalIdentityAndProtectsExternalEmptyFile() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "new.txt")
        val state = EditorWorkspaceState()
        val document = state.document("new.txt")
        val files = FileRepository(root)
        val authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files)
        val absent = document.savedDiskVersion
        document.capture("unsaved draft")
        target.writeText("")
        assertTrue(runCatching { authority.persistedMutation(FileRepository.Mutation.Sync("new.txt")) }.exceptionOrNull() is WorkspaceDocumentConflictException)
        assertSame(absent, document.savedDiskVersion)
        assertTrue(document.save(files).isFailure)
        assertEquals("", target.readText())
        assertEquals("unsaved draft", document.content)
        assertTrue(document.dirty)
    }

    @Test(timeout = 15_000)
    fun historicalRecoveryMatchingCurrentEmptyTextDoesNotAdoptCurrentIdentity() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("") }
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        val files = FileRepository(root)
        val authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files)
        val historical = DiskFileVersion.fromText("historical A")
        document.restoreUnsaved("", historical)
        // Equal current/saved text is intentionally still dirty: the original identity differs.
        assertEquals(document.content, document.savedContent)
        assertTrue(document.dirty)
        assertTrue(runCatching { authority.persistedMutation(FileRepository.Mutation.Sync("main.txt")) }.exceptionOrNull() is WorkspaceDocumentConflictException)
        assertSame(historical, document.savedDiskVersion)
        assertTrue(document.save(files).isFailure)
        assertEquals("", target.readText())
        assertEquals("", document.content)
        assertTrue(document.dirty)
    }

    @Test(timeout = 15_000)
    fun unknownRecoveryMatchingCurrentEmptyTextRemainsBlocked() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("") }
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        val files = FileRepository(root)
        val authority = EditorWorkspaceDocumentAuthority(state, owner, files = files)
        document.ensureLoaded(files)
        document.restoreUnsaved("") // Existing default represents a legacy unknown original identity.
        assertEquals(document.content, document.savedContent)
        assertNull(document.savedDiskVersion)
        assertTrue(document.dirty)
        assertTrue(runCatching { authority.persistedMutation(FileRepository.Mutation.Sync("main.txt")) }.exceptionOrNull() is WorkspaceDocumentConflictException)
        assertNull(document.savedDiskVersion)
        assertTrue(document.save(files).isFailure)
        assertEquals("", target.readText())
        assertEquals("", document.content)
        assertTrue(document.dirty)
    }
}

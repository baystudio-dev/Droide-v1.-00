package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorkspaceNativeAuthorityTest {
    private val root = Files.createTempDirectory("r62-authority").toFile()
    private val files = FileRepository(root)
    private val state = EditorWorkspaceState()
    private val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
    private fun document(): EditorDocument {
        root.resolve("a").writeText("abc")
        return state.document("a").also { runBlocking { it.ensureLoaded(files) } }
    }
    private fun snapshot() = runBlocking { checkNotNull(authority.snapshot("a")) }
    private fun replace(expected: WorkspaceDocumentVersion) = runBlocking { authority.replaceText("a", expected, "XYZ", "Agent") }
    @After fun cleanUp() { PathSecurity.deleteTreeNoFollow(root) }

    @Test fun prepareCapturesPendingNativeTypingBeforeCas() {
        val document = document()
        val reviewed = snapshot()
        var replacements = 0
        document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { true },
            { document.captureNativeText("abc user") }, { _, _, publish -> replacements++; publish() })
        assertTrue(replace(reviewed.version) is WorkspaceDocumentMutationResult.Conflict)
        assertEquals(0, replacements)
        assertEquals("abc user", document.content)
    }
    @Test fun readOnlyReturnsTypedResultWithoutNativePreparation() {
        val document = document()
        val reviewed = snapshot()
        document.setReviewMode(true)
        document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { false },
            { error("must not prepare read-only document") }, { _, _, _ -> error("must not mutate") })
        assertTrue(replace(reviewed.version) is WorkspaceDocumentMutationResult.ReadOnly)
        assertEquals("abc", document.content)
    }
    @Test fun replacingBindingDuringPrepareRejectsPublication() {
        val document = document()
        val reviewed = snapshot()
        document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { true },
            { document.nativeMutationBinding = null }, { _, _, _ -> error("must not mutate") })
        assertTrue(runCatching { replace(reviewed.version) }.isFailure)
        assertEquals("abc", document.content)
    }
    @Test fun nativeFailureDoesNotReturnAppliedOrPublishModelOnly() {
        val document = document()
        val reviewed = snapshot()
        document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { true }, {},
            { _, _, _ -> throw IllegalStateException("native failed before publication") })
        assertEquals("native failed before publication", runCatching { replace(reviewed.version) }.exceptionOrNull()?.message)
        assertEquals("abc", document.content)
    }
    @Test fun sameTextStillValidatesOwnerAndVersionWithoutDirtyingDocument() {
        val document = document()
        val reviewed = snapshot()
        var applied = 0
        document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { true }, {},
            { before, after, publish -> assertEquals(before, after); applied++; publish() })
        val result = runBlocking { authority.replaceText("a", reviewed.version, "abc", "Agent") }
        assertTrue(result is WorkspaceDocumentMutationResult.Applied)
        assertEquals(1, applied)
        assertEquals(reviewed.version, snapshot().version)
        assertFalse(document.dirty)
    }
    @Test fun inactiveDocumentRetainsExistingModelOnlyMutationPath() {
        val document = document()
        assertNull(document.nativeMutationBinding)
        assertTrue(replace(snapshot().version) is WorkspaceDocumentMutationResult.Applied)
        assertEquals("XYZ", document.content)
        assertEquals("abc", root.resolve("a").readText())
    }
}

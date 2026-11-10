package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class EditorSaveCaptureContractTest {
    @Test fun persistedCallbackCannotEraseNativeOnlyEditsFromAnOptimizedBuffer() = runBlocking {
        val root = Files.createTempDirectory("droide-save-native-callback-").toFile()
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val text = "x".repeat(3 * 1024 * 1024); root.resolve("large.txt").writeText(text)
            withContext(owner) {
                val state = EditorWorkspaceState(); val doc = state.document("large.txt")
                val authority = EditorWorkspaceDocumentAuthority(state, owner)
                var changed = false
                val files = FileRepository(root, beforeMutation = authority::validatePersistedMutation, onMutation = {
                    if (!changed) { changed = true; withContext(owner) { doc.captureInsert(0, "native edit") } }
                    authority.persistedMutation(it)
                })
                doc.ensureLoaded(files); doc.capture(text + " saved")
                val receipt = doc.save(files).getOrThrow()
                assertNotNull(receipt.synchronizationFailure)
                assertTrue(doc.dirty); assertTrue(doc.requiresBufferCapture)
                assertEquals(text + " saved", doc.savedContent)
                assertTrue(doc.save(files).isFailure)
                assertEquals(text + " saved", root.resolve("large.txt").readText())
            }
        } finally { owner.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun anExternalWriteCannotTreatAStaleLargeModelAsTheCompleteDirtyBuffer() = runBlocking {
        val root = Files.createTempDirectory("droide-save-native-admission-").toFile()
        try {
            val text = "x".repeat(3 * 1024 * 1024); root.resolve("large.txt").writeText(text)
            val state = EditorWorkspaceState(); val files = FileRepository(root); val doc = state.document("large.txt")
            doc.ensureLoaded(files); doc.captureInsert(0, "native edit")
            val authority = EditorWorkspaceDocumentAuthority(state, kotlinx.coroutines.Dispatchers.Unconfined)
            val failure = runCatching { authority.validatePersistedMutation(FileRepository.Mutation.Write("large.txt", text)) }.exceptionOrNull()
            assertTrue(failure is WorkspaceDocumentConflictException)
            assertTrue(doc.requiresBufferCapture); assertTrue(doc.dirty)
            assertEquals(text, root.resolve("large.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun optimizedNativeEditsCannotBeSavedFromAnUncapturedModel() = runBlocking {
        val root = Files.createTempDirectory("droide-save-uncaptured-").toFile()
        try {
            val text = "x".repeat(3 * 1024 * 1024)
            root.resolve("large.txt").writeText(text)
            val repository = FileRepository(root)
            val document = EditorDocument("large.txt"); document.ensureLoaded(repository)
            assertTrue(document.largeFileOptimized)
            assertTrue(document.captureInsert(0, "new native edit"))
            assertTrue(document.dirty)
            val outcome = document.save(repository)
            assertTrue("Saving an uncaptured optimized model would lose the actual editor edit", outcome.isFailure)
            assertTrue(document.dirty)
            assertEquals(text, root.resolve("large.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

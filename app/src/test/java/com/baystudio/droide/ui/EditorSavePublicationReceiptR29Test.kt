package com.baystudio.droide.ui

import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.FileRepository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real model/repository call paths, with bounded local files and existing callback seams. */
class EditorSavePublicationReceiptR29Test {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun editorSaveAboveOrdinaryTextCapRetainsTheVerifiedSnapshot() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "large.txt").apply { writeText("before") }
        val files = FileRepository(root)
        val document = EditorDocument("large.txt")
        document.ensureLoaded(files)
        val text = "x".repeat(5 * 1024 * 1024 + 1)
        document.capture(text)
        assertTrue(document.largeFileOptimized)
        val receipt = document.save(files).getOrThrow()
        assertTrue(receipt.didWrite)
        assertEquals(text, target.readText())
        assertEquals(text, document.savedContent)
        assertEquals(text.toByteArray().size.toLong(), document.savedDiskVersion?.bytes)
        assertFalse(document.dirty)
    }

    @Test(timeout = 15_000)
    fun normalReturnAfterForeignSameByteReplacementPreservesDirtyIntent() = runBlocking {
        foreignReplacementScenario("normal")
    }

    @Test(timeout = 15_000)
    fun callbackFailureAfterForeignSameByteReplacementPreservesDirtyIntent() = runBlocking {
        foreignReplacementScenario("failure")
    }

    @Test(timeout = 15_000)
    fun cancellationAfterForeignSameByteReplacementPreservesDirtyIntent() = runBlocking {
        foreignReplacementScenario("cancel")
    }

    @Test(timeout = 15_000)
    fun recoveryLatchSurvivesCancelledAdmissionWithForeignMatchingBytes() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        val files = FileRepository(root, beforeMutation = {
            target.writeText("recovered B")
            throw CancellationException("admission cancelled")
        })
        val document = EditorDocument("main.txt")
        document.ensureLoaded(files)
        val accepted = requireNotNull(document.savedDiskVersion)
        document.restoreUnsaved("recovered B", accepted)
        assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        assertSame(accepted, document.savedDiskVersion)
        assertEquals("original A", document.savedContent)
        assertEquals("recovered B", document.content)
        assertEquals("recovered B", target.readText())
        document.capture("recovered B")
        assertTrue(document.dirty)
        assertFalse(document.saveSynchronizationPending)
    }

    @Test(timeout = 15_000)
    fun cancellationBeforePreparationDoesNotAcceptMatchingForeignBytes() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        var admissions = 0
        val files = FileRepository(root, beforeMutation = { admissions++ })
        val document = EditorDocument("main.txt")
        document.ensureLoaded(files); document.capture("intended B")
        val accepted = document.savedDiskVersion
        target.writeText("intended B")
        val failure = runCatching {
            coroutineScope {
                currentCoroutineContext().cancel(CancellationException("cancelled before preparation"))
                document.save(files)
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, admissions)
        assertSame(accepted, document.savedDiskVersion)
        assertEquals("original A", document.savedContent)
        assertTrue(document.dirty)
        assertFalse(document.saveSynchronizationPending)
    }

    @Test(timeout = 15_000)
    fun ownedCancelledSaveKeepsUncapturedNativeDeltaAndItsNewBaseline() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val original = "\n".repeat(300_001)
        val saved = original + "saved"
        val target = File(root, "large.txt").apply { writeText(original) }
        val document = EditorDocument("large.txt")
        var interrupt = true
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write && interrupt) {
                interrupt = false
                withContext(owner) { assertTrue(document.captureInsert(0, "native")) }
                throw CancellationException("owned save interrupted with a native delta")
            }
        })
        document.ensureLoaded(files); document.capture(saved)
        assertTrue(document.largeFileOptimized)
        assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        assertEquals(saved, target.readText())
        assertEquals(saved, document.savedContent)
        assertEquals(DiskFileVersion.fromText(saved), document.savedDiskVersion)
        assertTrue(document.requiresBufferCapture)
        assertTrue(document.dirty)
        assertTrue(document.save(files).isFailure)
        document.capture("native" + saved)
        assertTrue(document.save(files).isSuccess)
        assertFalse(document.dirty)
        assertEquals("native" + saved, target.readText())
    }

    @Test(timeout = 15_000)
    fun cancelledSyncOnlyKeepsAcceptedIdentityAndNewerTextWithoutRewriting() = runBlocking {
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        val document = EditorDocument("main.txt")
        var callbacks = 0
        var admissions = 0
        val files = FileRepository(root, beforeMutation = { admissions++ }, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) when (++callbacks) {
                1 -> throw IOException("first synchronization failed")
                2 -> {
                    withContext(owner) { document.capture("newer C") }
                    throw CancellationException("sync retry interrupted")
                }
            }
        })
        document.ensureLoaded(files); document.capture("saved B")
        assertNotNull(document.save(files).getOrThrow().synchronizationFailure)
        val accepted = document.savedDiskVersion
        val published = key(target)
        assertFalse(document.dirty)
        assertTrue(document.saveSynchronizationPending)
        assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        assertEquals(1, admissions)
        assertEquals(published, key(target))
        assertSame(accepted, document.savedDiskVersion)
        assertEquals("saved B", document.savedContent)
        assertEquals("newer C", document.content)
        assertTrue(document.dirty)
        assertTrue(document.saveSynchronizationPending)
        assertTrue(document.save(files).getOrThrow().didWrite)
        assertEquals("newer C", target.readText())
        assertFalse(document.dirty)
        assertFalse(document.saveSynchronizationPending)
    }

    @Test(timeout = 15_000)
    fun cancelledSyncOnlyRetainsPendingStatusUntilNoWriteRetrySucceeds() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        var callbacks = 0
        var admissions = 0
        val files = FileRepository(root, beforeMutation = { admissions++ }, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) when (++callbacks) {
                1 -> throw IOException("first synchronization failed")
                2 -> throw CancellationException("sync retry interrupted")
            }
        })
        val document = EditorDocument("main.txt")
        document.ensureLoaded(files); document.capture("saved B")
        assertNotNull(document.save(files).getOrThrow().synchronizationFailure)
        val accepted = document.savedDiskVersion
        val published = key(target)
        assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        assertSame(accepted, document.savedDiskVersion)
        assertEquals(published, key(target))
        assertFalse(document.dirty)
        assertTrue(document.saveSynchronizationPending)
        val receipt = document.save(files).getOrThrow()
        assertFalse(receipt.didWrite)
        assertFalse(document.saveSynchronizationPending)
        assertSame(accepted, document.savedDiskVersion)
        assertEquals(published, key(target))
        assertEquals(1, admissions)
    }

    private suspend fun foreignReplacementScenario(mode: String) {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("original A") }
        var owned: Any? = null
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                owned = key(target)
                val replacement = File(root, "foreign.tmp").apply { writeText("intended B") }
                assertNotEquals(owned, key(replacement))
                Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (mode == "failure") throw IOException("foreign replacement preceded callback failure")
                if (mode == "cancel") throw CancellationException("foreign replacement preceded cancellation")
            }
        })
        val document = EditorDocument("main.txt")
        document.ensureLoaded(files); document.capture("intended B")
        val accepted = document.savedDiskVersion
        if (mode == "cancel") {
            assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        } else {
            assertTrue(document.save(files).isFailure)
        }
        assertNotEquals(owned, key(target))
        assertEquals("intended B", target.readText())
        assertSame(accepted, document.savedDiskVersion)
        assertEquals("original A", document.savedContent)
        assertEquals("intended B", document.content)
        assertTrue(document.dirty)
        assertFalse(document.saveSynchronizationPending)
    }

    private fun key(file: File): Any = requireNotNull(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
}

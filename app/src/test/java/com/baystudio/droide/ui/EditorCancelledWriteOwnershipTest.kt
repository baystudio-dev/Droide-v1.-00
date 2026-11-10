package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Original APIs only. */

class EditorCancelledWriteOwnershipTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun cancellationBeforeAdmissionDoesNotAdoptCoincidentallyMatchingForeignBytes() = runBlocking {
        val root = temp.newFolder()
        val source = File(root, "main.txt").apply { writeText("original A") }
        var admissions = 0
        var persistedCallbacks = 0
        val cancelled = CancellationException("fixture cancelled before admission")
        val files = FileRepository(root, beforeMutation = {
            admissions++

            source.writeText("intended B")
            throw cancelled
        }, onMutation = { persistedCallbacks++ })
        val document = EditorDocument("main.txt")
        document.ensureLoaded(files)
        document.capture("intended B")
        val failure = runCatching { document.save(files) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, admissions)
        assertEquals("The Save never published or reached a persisted callback", 0, persistedCallbacks)
        assertEquals("intended B", source.readText())
        assertEquals("Unowned matching bytes must not advance the accepted baseline", "original A", document.savedContent)
        assertEquals("intended B", document.content)
        assertTrue("The unsaved intent must remain pending", document.dirty)
        assertFalse(document.saveSynchronizationPending)
    }

    @Test(timeout = 15_000)
    fun cancellationAfterOwnedPublicationAcknowledgesTheCommittedSnapshot() = runBlocking {
        val root = temp.newFolder()
        val source = File(root, "main.txt").apply { writeText("original A") }
        var committedCallbacks = 0
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                committedCallbacks++
                assertEquals("intended B", source.readText())
                throw CancellationException("fixture cancelled after owned publication")
            }
        })
        val document = EditorDocument("main.txt")
        document.ensureLoaded(files); document.capture("intended B")
        assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        assertEquals(1, committedCallbacks)
        assertEquals("intended B", source.readText())
        assertEquals("intended B", document.savedContent)
        assertFalse(document.dirty)
        assertTrue(document.saveSynchronizationPending)
    }

    @Test(timeout = 15_000)
    fun cancellationAfterOwnedPublicationKeepsNewerTypingAndItsCommittedBaseline() = runBlocking {
        val root = temp.newFolder()
        val source = File(root, "main.txt").apply { writeText("original A") }
        val document = EditorDocument("main.txt")
        var interrupt = true
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write && interrupt) {
                interrupt = false
                assertEquals("intended B", source.readText())
                document.capture("newer C")
                throw CancellationException("fixture cancelled after newer typing")
            }
        })
        document.ensureLoaded(files); document.capture("intended B")
        assertTrue(runCatching { document.save(files) }.exceptionOrNull() is CancellationException)
        assertEquals("intended B", source.readText())
        assertEquals("intended B", document.savedContent)
        assertEquals("newer C", document.content); assertTrue(document.dirty)
        assertTrue(document.save(files).isSuccess)
        assertEquals("newer C", source.readText())
        assertEquals("newer C", document.savedContent)
        assertFalse(document.dirty)
    }
}

package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProjectRecentsPublicationTest {
    @get:Rule val temp = TemporaryFolder()

    private val before = listOf("p-first", "p-second")
    private val after = listOf("p-second", "p-first")

    private fun stages() = File(temp.root, ".droide").listFiles().orEmpty()
        .filter { it.name.startsWith(".recent_projects.json.tmp-") }

    private suspend fun seed() {
        FileProjectRecentsStore(temp.root).save(before)
        File(temp.root, "projects/p-first/source.txt").apply { parentFile.mkdirs(); writeText("unpublished work") }
    }

    private suspend fun assertFailure(expected: Throwable, action: suspend () -> Unit) {
        assertSame(expected, runCatching { action() }.exceptionOrNull())
    }

    @Test fun normalSavePreservesFilteringOrderAndCapacity() = runBlocking {
        assertNull(FileProjectRecentsStore(temp.root).load())
        val ids = listOf("p-b", "../invalid", "p-b", "p-a") + (0..150).map { "p-$it" }
        FileProjectRecentsStore(temp.root).save(ids)
        assertEquals((listOf("p-b", "p-a") + (0..150).map { "p-$it" }).take(128),
            FileProjectRecentsStore(temp.root).load())
        assertTrue(stages().isEmpty())
    }

    @Test fun failedReplacementKeepsPreviousRecentsAndProposalWithOneAttempt() = runBlocking {
        seed()
        val failure = IOException("before recents rename")
        var attempts = 0
        val store = FileProjectRecentsStore(temp.root) { _, _, _ -> attempts++; throw failure }
        assertFailure(failure) { store.save(after) }
        assertEquals(1, attempts)
        assertEquals(before, FileProjectRecentsStore(temp.root).load())
        assertEquals("[\"p-second\",\"p-first\"]", stages().single().readText())
        assertEquals("unpublished work", File(temp.root, "projects/p-first/source.txt").readText())
    }

    @Test fun lostReturnAfterRenamePreservesOriginalFailureWithoutRetry() = runBlocking {
        seed()
        val failure = IOException("after recents rename")
        var attempts = 0
        val store = FileProjectRecentsStore(temp.root) { from, to, options ->
            attempts++
            Files.move(from, to, *options)
            throw failure
        }
        assertFailure(failure) { store.save(after) }
        assertEquals(1, attempts)
        assertEquals(after, FileProjectRecentsStore(temp.root).load())
        assertTrue(stages().isEmpty())
    }

    @Test fun unsupportedAtomicMoveCannotFallBackToReplacingRecents() = runBlocking {
        seed()
        val failure = AtomicMoveNotSupportedException("stage", "recents", "injected unsupported capability")
        var attempts = 0
        val store = FileProjectRecentsStore(temp.root) { from, to, options ->
            attempts++
            if (StandardCopyOption.ATOMIC_MOVE in options) throw failure
            Files.move(from, to, *options)
        }
        assertFailure(failure) { store.save(after) }
        assertEquals(1, attempts)
        assertEquals(before, FileProjectRecentsStore(temp.root).load())
        assertEquals(1, stages().size)
    }

    @Test fun failedFirstWriterCannotOverwriteConcurrentRecentsCommit() = runBlocking {
        seed()
        val concurrent = listOf("p-other", "p-first")
        val failure = IOException("first writer did not commit")
        var attempts = 0
        val store = FileProjectRecentsStore(temp.root) { from, to, options ->
            attempts++
            if (attempts == 1) {
                val workerFailure = AtomicReference<Throwable?>()
                val worker = thread(name = "recents-concurrent-writer") {
                    try { runBlocking { FileProjectRecentsStore(temp.root).save(concurrent) } }
                    catch (error: Throwable) { workerFailure.set(error) }
                }
                worker.join(5_000)
                assertFalse("Concurrent writer did not finish", worker.isAlive)
                workerFailure.get()?.let { throw AssertionError("Concurrent writer failed", it) }
                throw failure
            }
            Files.move(from, to, *options)
        }
        assertFailure(failure) { store.save(after) }
        assertEquals(1, attempts)
        assertEquals(concurrent, FileProjectRecentsStore(temp.root).load())
        assertEquals("[\"p-second\",\"p-first\"]", stages().single().readText())
    }

    @Test fun reusedStagingPathIsNeitherRetriedNorDeleted() = runBlocking {
        seed()
        val failure = IOException("renamed but return lost")
        var attempts = 0
        val store = FileProjectRecentsStore(temp.root) { from, to, options ->
            attempts++
            Files.move(from, to, *options)
            Files.write(from, "another owner's retained content".toByteArray())
            throw failure
        }
        assertFailure(failure) { store.save(after) }
        assertEquals(1, attempts)
        assertEquals(after, FileProjectRecentsStore(temp.root).load())
        assertEquals("another owner's retained content", stages().single().readText())
    }

    @Test fun cancellationBeforeRenameDoesNotDiscardProposal() = runBlocking {
        seed()
        val cancelled = CancellationException("before rename")
        var attempts = 0
        val store = FileProjectRecentsStore(temp.root) { _, _, _ -> attempts++; throw cancelled }
        assertFailure(cancelled) { store.save(after) }
        assertEquals(1, attempts)
        assertEquals(before, FileProjectRecentsStore(temp.root).load())
        assertEquals(1, stages().size)
    }

    @Test fun cancellationAfterRenameIsNotMaskedByMissingStageError() = runBlocking {
        seed()
        val cancelled = CancellationException("after rename")
        var attempts = 0
        val store = FileProjectRecentsStore(temp.root) { from, to, options ->
            attempts++
            Files.move(from, to, *options)
            throw cancelled
        }
        assertFailure(cancelled) { store.save(after) }
        assertEquals(1, attempts)
        assertEquals(after, FileProjectRecentsStore(temp.root).load())
    }

    @Test fun laterSaveAndNewReaderDoNotDeleteUncommittedRecentsProposal() = runBlocking {
        seed()
        val failure = IOException("keep proposal")
        assertFailure(failure) { FileProjectRecentsStore(temp.root) { _, _, _ -> throw failure }.save(after) }
        val retained = stages().single()
        val bytes = retained.readBytes()
        FileProjectRecentsStore(temp.root).save(listOf("p-third"))
        assertEquals(listOf("p-third"), FileProjectRecentsStore(temp.root).load())
        assertArrayEquals(bytes, retained.readBytes())
        assertEquals(listOf(retained), stages())
    }

    @Test fun recentsFailureDoesNotUndoCommittedSelectionOrLoseProjectOnRestart() = runBlocking {
        var fail = false
        var attempts = 0
        val recents = FileProjectRecentsStore(temp.root) { from, to, options ->
            if (fail) { attempts++; throw IOException("recents unavailable") }
            Files.move(from, to, *options)
        }
        val manager = ProjectManager(temp.root, FileProjectStore(temp.root), recents).apply { init() }
        val added = manager.add("Work")
        val source = File(added.rootPath, "source.txt").apply { writeText("keep project") }
        fail = true
        manager.switch(added.id)
        assertEquals(1, attempts)
        assertEquals(added.id, manager.activeId.value)
        assertEquals(added.id, FileProjectStore(temp.root).loadActiveId())
        assertFalse(manager.selectionIsUncertain)
        assertTrue(stages().single().readText().contains(added.id))
        val bytes = stages().single().readBytes()
        val restarted = ProjectManager(temp.root).apply { init() }
        assertEquals(added.id, restarted.activeId.value)
        assertTrue(restarted.projects.value.contains(added))
        assertEquals("keep project", source.readText())
        assertArrayEquals(bytes, stages().single().readBytes())
    }
}

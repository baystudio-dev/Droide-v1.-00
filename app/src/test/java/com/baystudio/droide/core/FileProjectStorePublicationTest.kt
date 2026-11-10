package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProjectStorePublicationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun project(name: String = "Before") = DroideProject("p-owned", name,
        File(temp.root, "projects/p-owned").apply { mkdirs() }.canonicalPath)

    private fun stages(target: String = "projects.json") =
        File(temp.root, ".droide").listFiles().orEmpty().filter { it.name.startsWith(".$target.tmp-") }

    private suspend fun seed(): DroideProject = project().also {
        FileProjectStore(temp.root).save(listOf(it))
        FileProjectStore(temp.root).saveActiveId(it.id)
        File(it.rootPath, "source.kt").writeText("unsynced work")
    }

    private suspend fun assertFailure(expected: Throwable, block: suspend () -> Unit) {
        assertSame(expected, runCatching { block() }.exceptionOrNull())
    }

    @Test fun successfulReplacementKeepsUnicodeAndLeavesNoStagingFile() = runBlocking {
        seed()
        val after = project("Proyek \uD83C\uDF31")
        FileProjectStore(temp.root).save(listOf(after))
        FileProjectStore(temp.root).saveActiveId("p-next")
        assertEquals(listOf(after), FileProjectStore(temp.root).load())
        assertEquals("p-next", FileProjectStore(temp.root).loadActiveId())
        assertTrue(stages().isEmpty())
        assertTrue(stages("active_project.txt").isEmpty())
    }

    @Test fun uncommittedFailureKeepsCatalogAndProposalWithoutRetry() = runBlocking {
        val before = seed()
        val failure = IOException("before rename")
        var calls = 0
        val store = FileProjectStore(temp.root) { _, _, _ -> calls++; throw failure }
        assertFailure(failure) { store.save(listOf(project("Proposed"))) }
        assertEquals(1, calls)
        assertEquals(listOf(before), FileProjectStore(temp.root).load())
        assertTrue(stages().single().readText().contains("Proposed"))
        assertEquals("unsynced work", File(before.rootPath, "source.kt").readText())
    }

    @Test fun lostReturnAfterRenamePropagatesOriginalFailureWithoutSecondAttempt() = runBlocking {
        seed()
        val after = project("Committed")
        val failure = IOException("after rename")
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            calls++
            Files.move(from, to, *options)
            throw failure
        }
        assertFailure(failure) { store.save(listOf(after)) }
        assertEquals(1, calls)
        assertEquals(listOf(after), FileProjectStore(temp.root).load())
        assertTrue(stages().isEmpty())
    }

    @Test fun unsupportedAtomicMoveRetainsProposalAndDoesNotUseNonAtomicFallback() = runBlocking {
        val before = seed()
        val failure = AtomicMoveNotSupportedException("stage", "catalog", "injected unsupported capability")
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            calls++
            if (StandardCopyOption.ATOMIC_MOVE in options) throw failure
            Files.move(from, to, *options)
        }
        assertFailure(failure) { store.save(listOf(project("Proposed"))) }
        assertEquals(1, calls)
        assertEquals(listOf(before), FileProjectStore(temp.root).load())
        assertTrue(stages().single().readText().contains("Proposed"))
    }

    @Test fun failedPublicationCannotClobberAnExternalWriterThatCommittedWhileItWasPending() = runBlocking {
        seed()
        val concurrent = project("Other writer")
        val failure = IOException("first publication not committed")
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            calls++
            if (calls == 1) {
                // Cooperating stores now serialize, so nesting and joining a second store inside this callback
                // would deadlock rather than simulate an external filesystem write.
                val external = Files.newBufferedReader(to, UTF_8).use { it.readText() }.replace("Before", concurrent.name)
                Files.newBufferedWriter(to, UTF_8).use { it.write(external) }
                throw failure
            }
            Files.move(from, to, *options)
        }
        assertFailure(failure) { store.save(listOf(project("First proposal"))) }
        assertEquals(1, calls)
        assertEquals(listOf(concurrent), FileProjectStore(temp.root).load())
        assertTrue(stages().single().readText().contains("First proposal"))
    }

    @Test fun replacementAtConsumedStagePathIsNotRetriedOrDeleted() = runBlocking {
        seed()
        val after = project("Committed")
        val failure = IOException("lost return with a new staging occupant")
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            calls++
            Files.move(from, to, *options)
            Files.write(from, "external retained content".toByteArray())
            throw failure
        }
        assertFailure(failure) { store.save(listOf(after)) }
        assertEquals(1, calls)
        assertEquals(listOf(after), FileProjectStore(temp.root).load())
        assertEquals("external retained content", stages().single().readText())
    }

    @Test fun replacementSymlinkAtConsumedStagePathIsPreservedWithoutFollowingIt() = runBlocking {
        seed()
        val outside = temp.newFile("other-owner.txt").apply { writeText("other owner") }
        val failure = IOException("lost return")
        var staged: Path? = null
        val store = FileProjectStore(temp.root) { from, to, options ->
            Files.move(from, to, *options)
            staged = from
            Files.createSymbolicLink(from, outside.toPath())
            throw failure
        }
        assertFailure(failure) { store.save(listOf(project("Committed"))) }
        assertTrue(Files.isSymbolicLink(checkNotNull(staged)))
        assertEquals(outside.toPath(), Files.readSymbolicLink(checkNotNull(staged)))
        assertEquals("other owner", outside.readText())
        assertEquals("Committed", FileProjectStore(temp.root).load().single().name)
    }

    @Test fun cancellationBeforeRenameKeepsProposalAndIsNotRetried() = runBlocking {
        val before = seed()
        val cancelled = CancellationException("cancelled before rename")
        var calls = 0
        val store = FileProjectStore(temp.root) { _, _, _ -> calls++; throw cancelled }
        assertFailure(cancelled) { store.save(listOf(project("Cancelled proposal"))) }
        assertEquals(1, calls)
        assertEquals(listOf(before), FileProjectStore(temp.root).load())
        assertTrue(stages().single().readText().contains("Cancelled proposal"))
    }

    @Test fun fatalFailureAfterRenameIsNotReplacedByARetryFailure() = runBlocking {
        seed()
        val fatal = AssertionError("fatal return loss")
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            calls++
            Files.move(from, to, *options)
            throw fatal
        }
        assertFailure(fatal) { store.save(listOf(project("Committed"))) }
        assertEquals(1, calls)
        assertEquals("Committed", FileProjectStore(temp.root).load().single().name)
    }

    @Test fun activeSelectionFailureKeepsPreviousIdentityAndNewProposal() = runBlocking {
        val before = seed()
        val failure = IOException("selection not committed")
        var calls = 0
        val store = FileProjectStore(temp.root) { _, _, _ -> calls++; throw failure }
        assertFailure(failure) { store.saveActiveId("p-next") }
        assertEquals(1, calls)
        assertEquals(before.id, FileProjectStore(temp.root).loadActiveId())
        assertEquals("p-next\n", stages("active_project.txt").single().readText())
    }

    @Test fun activeSelectionLostReturnKeepsCommittedIdentityAndCancellation() = runBlocking {
        seed()
        val failure = CancellationException("selection return lost")
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            calls++
            Files.move(from, to, *options)
            throw failure
        }
        assertFailure(failure) { store.saveActiveId("p-next") }
        assertEquals(1, calls)
        assertEquals("p-next", FileProjectStore(temp.root).loadActiveId())
        assertTrue(stages("active_project.txt").isEmpty())
    }

    @Test fun laterSaveAndFreshReaderDoNotConsumeOrCleanUncommittedStage() = runBlocking {
        seed()
        val failure = IOException("retain for explicit recovery")
        val store = FileProjectStore(temp.root) { _, _, _ -> throw failure }
        assertFailure(failure) { store.save(listOf(project("First proposal"))) }
        val retained = stages().single()
        val bytes = retained.readBytes()
        FileProjectStore(temp.root).save(listOf(project("Later explicit save")))
        assertEquals("Later explicit save", FileProjectStore(temp.root).load().single().name)
        assertArrayEquals(bytes, retained.readBytes())
        assertEquals(listOf(retained), stages())
    }

    @Test fun projectManagerResolvesCommittedCatalogFromRealStoreAfterLostReturn() = runBlocking {
        var loseReturn = false
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            Files.move(from, to, *options)
            if (loseReturn && to.fileName.toString() == "projects.json") {
                calls++
                throw IOException("real catalog committed")
            }
        }
        val manager = ProjectManager(temp.root, store).apply { init() }
        loseReturn = true
        val added = manager.add("Published despite lost return")
        assertEquals(1, calls)
        assertFalse(manager.selectionIsUncertain)
        assertEquals(FileProjectStore(temp.root).load(), manager.projects.value)
        val restarted = ProjectManager(temp.root, FileProjectStore(temp.root)).apply { init() }
        assertTrue(restarted.projects.value.contains(added))
        assertTrue(File(added.rootPath).isDirectory)
    }

    @Test fun projectManagerKeepsUncommittedProjectAndProposalOnRealStoreFailure() = runBlocking {
        var fail = false
        var calls = 0
        val failure = IOException("real catalog not committed")
        val store = FileProjectStore(temp.root) { from, to, options ->
            if (fail && to.fileName.toString() == "projects.json") { calls++; throw failure }
            Files.move(from, to, *options)
        }
        val manager = ProjectManager(temp.root, store).apply { init() }
        val before = manager.projects.value
        fail = true
        assertFailure(failure) { manager.add("Retained registration") }
        assertEquals(1, calls)
        assertEquals(before, manager.projects.value)
        assertEquals(before, FileProjectStore(temp.root).load())
        assertFalse(manager.selectionIsUncertain)
        assertTrue(stages().single().readText().contains("Retained registration"))
        val root = File(temp.root, "projects").listFiles().orEmpty().single { it.name != "default" }
        assertTrue(root.isDirectory)
        ProjectManager(temp.root, FileProjectStore(temp.root)).init()
        assertTrue(root.isDirectory)
        assertEquals(1, stages().size)
    }

    @Test fun projectManagerPublishesOwnershipBeforeRethrowingCancellationFromRealStore() = runBlocking {
        var fail = false
        val cancelled = CancellationException("committed registration cancellation")
        var calls = 0
        val store = FileProjectStore(temp.root) { from, to, options ->
            Files.move(from, to, *options)
            if (fail && to.fileName.toString() == "projects.json") { calls++; throw cancelled }
        }
        val manager = ProjectManager(temp.root, store).apply { init() }
        fail = true
        assertFailure(cancelled) { manager.add("Committed cancellation") }
        assertEquals(1, calls)
        assertFalse(manager.selectionIsUncertain)
        assertEquals(FileProjectStore(temp.root).load(), manager.projects.value)
        assertTrue(File(manager.projects.value.single { it.id != "default" }.rootPath).isDirectory)
    }
}

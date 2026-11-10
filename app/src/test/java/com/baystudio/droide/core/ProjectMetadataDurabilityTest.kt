package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMetadataDurabilityTest {
    @get:Rule val temp = TemporaryFolder()

    private class Io : StreamPublicationDurability {
        val events = mutableListOf<String>()
        var failAt: Int? = null
        var failure: Throwable = IOException("injected persistence failure")
        private fun force(event: String, action: () -> Unit) {
            events += event
            if (events.size == failAt) throw failure
            action()
        }
        override fun forceFile(channel: FileChannel) = force("file") {
            StreamPublicationDurability.platform.forceFile(channel)
        }
        override fun forceDirectory(directory: File) = force("dir:${directory.name}") {
            StreamPublicationDurability.platform.forceDirectory(directory)
        }
        fun reset(point: Int? = null, error: Throwable = IOException("injected persistence failure")) {
            events.clear(); failAt = point; failure = error
        }
    }

    private fun target() = File(temp.root, ".droide/projects.json").apply {
        parentFile.mkdirs(); writeText("before")
    }

    private fun stages() = File(temp.root, ".droide").listFiles().orEmpty().filter { ".tmp-" in it.name }

    private fun assertUnconfirmed(failure: Throwable?): Throwable {
        assertNotNull("Publication must report an unconfirmed persistence outcome", failure)
        return checkNotNull(failure).also { assertTrue(ProjectMetadataPublication.isUnconfirmed(it)) }
    }

    private fun store(io: Io) = FileProjectStore(temp.root, io, ProjectMetadataPublication::replace)

    private fun failingAfterReplacement(io: Io, targetName: String) = FileProjectStore(temp.root, io, replace = { from, to, options ->
        Files.move(from, to, *options)
        if (to.fileName.toString() == targetName) io.reset(1)
    })

    private suspend fun manager(io: Io) = ProjectManager(temp.root, store(io)).apply { init(); io.reset() }

    private fun assertFilesKept(manager: ProjectManager) {
        manager.projects.value.forEach { assertTrue(File(it.rootPath).isDirectory) }
    }

    @Test fun fileAndNamespaceFlushesBracketTheSingleRename() {
        val target = target(); val io = Io(); val order = mutableListOf<String>()
        val observed = object : StreamPublicationDurability {
            override fun forceFile(channel: FileChannel) { order += "file"; io.forceFile(channel) }
            override fun forceDirectory(directory: File) {
                order += if (directory == temp.root) "container" else "metadata"
                io.forceDirectory(directory)
            }
        }
        ProjectMetadataPublication.write(target, "after", { from, to, options ->
            order += "rename"; Files.move(from, to, *options)
        }, observed)
        assertEquals(listOf("container", "file", "metadata", "rename", "metadata"), order)
        assertEquals("after", target.readText())
        assertTrue(stages().isEmpty())
    }

    @Test fun failedContainerFlushDoesNotCreateAStageOrReplaceMetadata() {
        val target = target(); val io = Io().apply { failAt = 1 }; var moves = 0
        val failure = runCatching { ProjectMetadataPublication.write(target, "after", { _, _, _ -> moves++ }, io) }.exceptionOrNull()
        assertSame(io.failure, assertUnconfirmed(failure).cause)
        assertEquals(0, moves); assertEquals("before", target.readText()); assertTrue(stages().isEmpty())
    }

    @Test fun failedFileFlushRetainsTheFullProposalWithoutReplacingMetadata() {
        val target = target(); val io = Io().apply { failAt = 2; failure = IOException("ENOSPC injected at file force") }
        var moves = 0
        assertUnconfirmed(runCatching { ProjectMetadataPublication.write(target, "after", { _, _, _ -> moves++ }, io) }.exceptionOrNull())
        assertEquals(0, moves); assertEquals("before", target.readText()); assertEquals("after", stages().single().readText())
    }

    @Test fun failedStageDirectoryFlushRetainsBothVersionsAndDoesNotRename() {
        val target = target(); val io = Io().apply { failAt = 3 }; var moves = 0
        assertUnconfirmed(runCatching { ProjectMetadataPublication.write(target, "after", { _, _, _ -> moves++ }, io) }.exceptionOrNull())
        assertEquals(0, moves); assertEquals("before", target.readText()); assertEquals("after", stages().single().readText())
    }

    @Test fun readableNewBytesDoNotTurnFailedPostRenameFlushIntoSuccess() {
        val target = target(); val io = Io().apply { failAt = 4 }; var moves = 0
        val failure = runCatching { ProjectMetadataPublication.write(target, "after", { from, to, options ->
            moves++; Files.move(from, to, *options)
        }, io) }.exceptionOrNull()
        assertSame(io.failure, assertUnconfirmed(failure).cause)
        assertEquals(1, moves); assertEquals("after", target.readText()); assertTrue(stages().isEmpty())
    }

    @Test fun lostRenameReturnIsPreservedAfterNamespaceFlushSucceeds() {
        val target = target(); val io = Io(); val lost = IOException("lost rename return"); var moves = 0
        val failure = runCatching { ProjectMetadataPublication.write(target, "after", { from, to, options ->
            moves++; Files.move(from, to, *options); throw lost
        }, io) }.exceptionOrNull()
        assertSame(lost, failure); assertFalse(ProjectMetadataPublication.isUnconfirmed(checkNotNull(failure)))
        assertEquals(4, io.events.size); assertEquals(1, moves); assertEquals("after", target.readText())
    }

    @Test fun renameFailureAndFlushFailureBothRemainAvailableForDiagnosis() {
        val target = target(); val io = Io().apply { failAt = 4 }; val move = IOException("rename not committed")
        val failure = assertUnconfirmed(runCatching { ProjectMetadataPublication.write(target, "after", { _, _, _ -> throw move }, io) }.exceptionOrNull())
        assertSame(io.failure, failure.cause); assertTrue(failure.suppressed.contains(move))
        assertEquals("before", target.readText()); assertEquals("after", stages().single().readText())
    }

    @Test fun flushCancellationAndFatalFailureKeepTheirFailureCategory() {
        for (cause in listOf(CancellationException("flush cancelled"), AssertionError("flush fatal"))) {
            val target = target(); val io = Io().apply { failAt = 4; failure = cause }
            val failure = assertUnconfirmed(runCatching {
                ProjectMetadataPublication.write(target, "after", ProjectMetadataPublication::replace, io)
            }.exceptionOrNull())
            assertSame(cause, failure.cause)
            if (cause is Error) assertTrue(failure is Error) else assertTrue(failure is CancellationException)
            assertEquals("after", target.readText())
        }
    }

    @Test fun interruptedRenameWithFailedFlushIsStillUnconfirmedAndInterrupted() {
        val target = target(); val io = Io().apply { failAt = 4 }; val cancelled = CancellationException("move return cancelled")
        val failure = assertUnconfirmed(runCatching {
            ProjectMetadataPublication.write(target, "after", { from, to, options ->
                Files.move(from, to, *options); throw cancelled
            }, io)
        }.exceptionOrNull())
        assertTrue(failure is CancellationException); assertSame(cancelled, failure.cause)
        assertTrue(failure.suppressed.any(ProjectMetadataPublication::isUnconfirmed))
        assertEquals("after", target.readText())
    }

    @Test fun malformedUtf16IsRejectedWithoutPublishingReplacementCharacters() {
        val target = target(); val io = Io(); var moves = 0
        val failure = runCatching { ProjectMetadataPublication.write(target, "\ud800", { _, _, _ -> moves++ }, io) }.exceptionOrNull()
        assertTrue(failure is CharacterCodingException); assertEquals(0, moves); assertEquals("before", target.readText())
    }

    @Test fun symlinkMetadataDirectoryCannotRedirectTheWrite() {
        val elsewhere = temp.newFolder("elsewhere")
        val original = File(elsewhere, "projects.json").apply { writeText("other owner's metadata") }
        Files.createSymbolicLink(File(temp.root, ".droide").toPath(), elsewhere.toPath())
        assertTrue(runCatching { ProjectMetadataPublication.write(File(temp.root, ".droide/projects.json"), "after",
            ProjectMetadataPublication::replace, Io()) }.isFailure)
        assertEquals("other owner's metadata", original.readText())
        assertEquals(listOf("projects.json"), elsewhere.listFiles().orEmpty().map { it.name })
    }

    @Test fun replacedMetadataDirectoryCannotBeBlessedByFlushingItsReplacement() {
        val target = target(); val io = Io(); val retained = File(temp.root, "retained")
        val failure = assertUnconfirmed(runCatching {
            ProjectMetadataPublication.write(target, "after", { from, to, options ->
                Files.move(from, to, *options)
                Files.move(target.parentFile.toPath(), retained.toPath())
                target.parentFile.mkdir(); File(target.parentFile, "new-work").writeText("keep")
            }, io)
        }.exceptionOrNull())
        assertNotNull(failure.cause)
        assertEquals(3, io.events.size)
        assertEquals("after", File(retained, "projects.json").readText())
        assertEquals("keep", File(target.parentFile, "new-work").readText())
        assertFalse(target.exists())
    }

    @Test fun catalogReadbackCannotAcceptUnconfirmedCommitAndFurtherMutationsStop() = runBlocking {
        val io = Io(); val manager = manager(io); val before = manager.projects.value
        io.reset(4)
        assertTrue(runCatching { manager.add("Retained registration") }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertTrue(manager.selectionIsUncertain); assertEquals(before, manager.projects.value)
        val actual = FileProjectStore(temp.root).load()
        val retained = actual.single { it.id != "default" }
        assertTrue(File(retained.rootPath).isDirectory)
        val count = io.events.size
        assertTrue(runCatching { manager.add("Blocked") }.isFailure)
        assertTrue(runCatching { manager.switch("default") }.isFailure)
        assertTrue(runCatching { manager.init() }.isFailure)
        assertEquals(count, io.events.size); assertFilesKept(manager)
        val reopened = ProjectManager(temp.root).apply { init() }
        assertTrue(reopened.projects.value.contains(retained))
    }

    @Test fun selectionReadbackCannotActivateAnUnconfirmedNewProject() = runBlocking {
        val io = Io(); val manager = manager(io); val added = manager.add("Other"); io.reset(4)
        var published = false
        assertTrue(runCatching { manager.selectAndPublish(added.id, publish = { published = true }) }.exceptionOrNull() is ProjectSelectionUncertainException)
        assertFalse(published); assertEquals("default", manager.activeId.value); assertTrue(manager.selectionIsUncertain)
        assertEquals(added.id, FileProjectStore(temp.root).loadActiveId()); assertFilesKept(manager)
    }

    @Test fun restoredSelectionReadbackCannotHideItsFailedFlush() = runBlocking {
        val io = Io(); val manager = manager(io); val added = manager.add("Other"); io.reset()
        val rejected = IllegalStateException("runtime preparation rejected")
        val failure = runCatching { manager.selectAndPublish(added.id, validate = {
            io.reset(4); throw rejected
        }) }.exceptionOrNull()
        assertTrue(failure is ProjectSelectionUncertainException); assertTrue(manager.selectionIsUncertain)
        assertEquals("default", manager.activeId.value)
        assertEquals("default", FileProjectStore(temp.root).loadActiveId()); assertFilesKept(manager)
    }

    @Test fun initializationCannotRetryAfterAnUnconfirmedCatalogFlush() = runBlocking {
        val io = Io(); manager(io)
        File(temp.root, ".droide/projects.json").appendText("\n")
        val fresh = ProjectManager(temp.root, failingAfterReplacement(io, "projects.json"))
        assertTrue(runCatching { fresh.init() }.exceptionOrNull() is ProjectCatalogUncertainException)
        assertTrue(fresh.selectionIsUncertain); val count = io.events.size
        assertTrue(runCatching { fresh.init(readOnlyExisting = true) }.isFailure)
        assertEquals(count, io.events.size); assertFilesKept(fresh)
    }

    @Test fun initializationCannotAcceptUnconfirmedActiveIdPersistence() = runBlocking {
        val io = Io(); manager(io)
        // A real selection repair must still reject an unconfirmed post-replacement flush.
        File(temp.root, ".droide/active_project.txt").writeText("missing-project\n")
        val fresh = ProjectManager(temp.root, failingAfterReplacement(io, "active_project.txt"))
        assertTrue(runCatching { fresh.init() }.exceptionOrNull() is ProjectSelectionUncertainException)
        assertEquals("default", FileProjectStore(temp.root).loadActiveId())
        assertTrue(fresh.selectionIsUncertain); assertNull(fresh.activeId.value); assertFilesKept(fresh)
        assertTrue(File(temp.root, "projects/default").isDirectory)
    }

    @Test fun cancelledCatalogFlushMarksUncertaintyWithoutBecomingAnOrdinaryError() = runBlocking {
        val io = Io(); val manager = manager(io); val before = manager.projects.value
        val cancelled = CancellationException("cancelled force"); io.reset(4, cancelled)
        val failure = runCatching { manager.add("Retained") }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertSame(cancelled, failure?.cause)
        assertTrue(manager.selectionIsUncertain); assertEquals(before, manager.projects.value); assertFilesKept(manager)
    }

    @Test fun fatalCatalogFlushMarksUncertaintyBeforeRethrowingFatalFailure() = runBlocking {
        val io = Io(); val manager = manager(io); val before = manager.projects.value
        val fatal = AssertionError("fatal force"); io.reset(4, fatal)
        val failure = runCatching { manager.add("Retained") }.exceptionOrNull()
        assertTrue(failure is Error); assertSame(fatal, failure?.cause)
        assertTrue(manager.selectionIsUncertain); assertEquals(before, manager.projects.value); assertFilesKept(manager)
    }

    @Test fun ordinaryReadsDoNotStartDurabilityWork() = runBlocking {
        val io = Io(); val store = store(io); val root = File(temp.root, "projects/p-owned").apply { mkdirs() }
        store.save(listOf(DroideProject("p-owned", "Owned", root.canonicalPath))); store.saveActiveId("p-owned")
        val count = io.events.size
        repeat(3) { assertEquals("p-owned", store.load().single().id); assertEquals("p-owned", store.loadActiveId()) }
        assertEquals(8, count); assertEquals(count, io.events.size)
    }
}

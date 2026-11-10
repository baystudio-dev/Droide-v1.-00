package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryImportCollisionTest {
    @Test fun requireAbsentRejectsExistingTargetAndClosesPickedInput() = withWorkspace { root ->
        root.resolve("target.txt").writeText("existing")
        val input = TrackingInput("new")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { FileRepository(root).writeStream("target.txt", input, requireAbsent = true) }
        }
        assertTrue(input.closed)
        assertEquals("existing", root.resolve("target.txt").readText())
        assertEquals(listOf("target.txt"), root.listFiles()!!.map { it.name })
    }

    @Test fun targetCreatedWhileImportCopiesIsPreservedAndProposalRetained() = withWorkspace { root ->
        val input = object : ByteArrayInputStream("new".toByteArray()) {
            private var created = false
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!created) { root.resolve("target.txt").writeText("from terminal"); created = true }
                return super.read(bytes, offset, length)
            }
        }
        val repo = FileRepository(root)
        val failure = assertThrows(StreamPublicationUncertainException::class.java) {
            runBlocking { repo.writeStream("target.txt", input, requireAbsent = true) }
        }
        assertTrue(failure.cause is FileAlreadyExistsException)
        assertFalse(failure.publicationReturned)
        assertEquals("from terminal", root.resolve("target.txt").readText())
        assertRetainedProposal(root, repo, "target.txt", "new".toByteArray(), historicalWriteReturned = false)
    }

    @Test fun targetCreatedAtCommitBoundaryCannotBeSilentlyReplaced() = withWorkspace { root ->
        val repo = FileRepository(root)
        val input = TrackingInput("new")
        val failure = assertThrows(StreamPublicationUncertainException::class.java) {
            runBlocking {
                repo.writeStream("target.txt", input, onProgress = { progress ->
                    if (progress.phase == StreamWritePhase.COMMITTING) root.resolve("target.txt").writeText("concurrent")
                }, requireAbsent = true)
            }
        }
        assertTrue(failure.cause is FileAlreadyExistsException)
        assertFalse(failure.publicationReturned)
        assertEquals("concurrent", root.resolve("target.txt").readText())
        assertTrue(input.closed)
        assertRetainedProposal(root, repo, "target.txt", "new".toByteArray(), historicalWriteReturned = false)
    }

    @Test fun beforeMutationCallbackCollisionIsRejectedBeforeInputRead() = withWorkspace { root ->
        val input = TrackingInput("new")
        val repo = FileRepository(root, beforeMutation = { root.resolve("target.txt").writeText("concurrent") })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.writeStream("target.txt", input, requireAbsent = true) }
        }
        assertTrue(input.closed)
        assertEquals(3, input.available())
        assertEquals("concurrent", root.resolve("target.txt").readText())
    }

    @Test fun newImportPublishesWholeFileAndPreservesSynchronizationContract() = withWorkspace { root ->
        val mutations = mutableListOf<FileRepository.Mutation>()
        val input = TrackingInput("whole file")
        val repo = FileRepository(root, onMutation = { mutations += it })
        val bytes = runBlocking { repo.writeStream("src/target.txt", input, requireAbsent = true) }
        assertEquals(10L, bytes)
        assertTrue(input.closed)
        assertEquals("whole file", root.resolve("src/target.txt").readText())
        assertEquals(listOf(FileRepository.Mutation.Write("src/target.txt")), mutations)
        assertRetainedProposal(root, repo, "src/target.txt", "whole file".toByteArray(), historicalWriteReturned = true)
        val stage = root.resolve("src").listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertTrue(Files.isSameFile(stage.toPath(), root.resolve("src/target.txt").toPath()))
    }

    @Test fun failedSynchronizationPreservesCommittedImportAndReportsItsActualState() = withWorkspace { root ->
        val failure = assertThrows(ProjectFileSyncException::class.java) {
            runBlocking {
                FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) error("sync failed") })
                    .writeStream("target.txt", TrackingInput("new"), requireAbsent = true)
            }
        }
        assertEquals("target.txt", failure.relativePath)
        assertEquals(3L, failure.bytes)
        assertEquals("new", root.resolve("target.txt").readText())
        val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals("new".toByteArray(), retained.readBytes())
        assertEquals(setOf("target.txt", retained.name), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun defaultStreamWritesRetainExistingExplicitOverwriteBehavior() = withWorkspace { root ->
        root.resolve("target.txt").writeText("old")
        runBlocking { FileRepository(root).writeStream("target.txt", TrackingInput("replacement")) }
        assertEquals("replacement", root.resolve("target.txt").readText())
        assertEquals(listOf("target.txt"), root.listFiles()!!.map { it.name })
    }

    @Test fun failedImportSynchronizationPreservesForeignReplacementPublishedDuringCallback() = withWorkspace { root ->
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                root.resolve("replacement.txt").writeText("from terminal")
                Files.move(root.resolve("replacement.txt").toPath(), root.resolve("target.txt").toPath(), StandardCopyOption.REPLACE_EXISTING)
                error("sync failed")
            }
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.writeStream("target.txt", TrackingInput("new"), requireAbsent = true) }
        }
        assertEquals("from terminal", root.resolve("target.txt").readText())
        val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals("new".toByteArray(), retained.readBytes())
        assertEquals(setOf("target.txt", retained.name), root.listFiles()!!.map { it.name }.toSet())
    }

    private fun assertRetainedProposal(root: File, repo: FileRepository, target: String,
        bytes: ByteArray, historicalWriteReturned: Boolean) {
        val destination = root.resolve(target)
        val stage = destination.parentFile!!.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals(bytes, stage.readBytes())
        assertEquals(setOf(destination.name, stage.name), destination.parentFile!!.listFiles()!!.map { it.name }.toSet())
        val notice = repo.retainedWork.value.notices.single()
        assertEquals(target, notice.target)
        assertEquals(stage.relativeTo(root).invariantSeparatorsPath, notice.candidate.path)
        assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
        assertEquals(bytes.size.toLong(), notice.candidate.bytes)
        assertEquals(historicalWriteReturned, notice.historicalWriteReturned)
    }

    private class TrackingInput(text: String) : ByteArrayInputStream(text.toByteArray()) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-import-collision-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

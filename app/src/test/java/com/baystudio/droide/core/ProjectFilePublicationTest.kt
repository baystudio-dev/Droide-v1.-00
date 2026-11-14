package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectFilePublicationTest {
    @Test fun workspaceMoveUsesOneNativeNoReplaceCallAndPreservesCollision() = workspace { root ->
        val source = root.resolve("source").apply { mkdir(); resolve("code.kt").writeText("original") }
        val target = root.resolve("target").apply { mkdir(); resolve("other.kt").writeText("terminal") }
        var calls = 0
        val mover = WorkspacePathMove.native { from, to ->
            calls++
            assertArrayEquals(source.absolutePath.toByteArray(Charsets.UTF_8), from)
            assertArrayEquals(target.absolutePath.toByteArray(Charsets.UTF_8), to)
            17
        }
        assertThrows(FileAlreadyExistsException::class.java) { mover.move(source, target) }
        assertEquals(1, calls)
        assertEquals("original", source.resolve("code.kt").readText())
        assertEquals("terminal", target.resolve("other.kt").readText())
    }

    @Test fun unsupportedWorkspaceMoveDoesNotCopyDeleteOrOverwrite() = workspace { root ->
        val source = root.resolve("folder").apply { mkdir(); resolve("keep").writeText("original") }
        val target = root.resolve("destination")
        assertThrows(FileSystemException::class.java) { WorkspacePathMove.native { _, _ -> 38 }.move(source, target) }
        assertEquals("original", source.resolve("keep").readText())
        assertFalse(target.exists())
        assertThrows(FileSystemException::class.java) {
            WorkspacePathMove.native { _, _ -> throw UnsatisfiedLinkError("fixture") }.move(source, target)
        }
        assertEquals("original", source.resolve("keep").readText())
    }
    @Test fun errnoCollisionNeverFallsThroughToReplacingTheExistingFile() = workspace { root ->
        val staged = root.resolve("staged").apply { writeText("upload") }
        val target = root.resolve("file.zip").apply { writeText("terminal") }
        var calls = 0
        val publisher = NewProjectFilePublisher.native { _, _ -> calls++; 17 }
        assertThrows(FileAlreadyExistsException::class.java) { publisher.publish(staged, target) }
        assertEquals(1, calls)
        assertEquals("upload", staged.readText())
        assertEquals("terminal", target.readText())
    }

    @Test fun unsupportedDeniedOrInterruptedSyscallNeverCreatesOrReplacesData() = workspace { root ->
        for (error in listOf(38, 95, 22, 13, 4)) {
            val staged = root.resolve("staged").apply { writeText("original") }
            val target = root.resolve("target")
            val failure = assertThrows(FileSystemException::class.java) {
                NewProjectFilePublisher.native { _, _ -> error }.publish(staged, target)
            }
            assertTrue(failure.message!!.contains("errno=$error"))
            assertEquals("original", staged.readText())
            assertFalse(target.exists())
        }
    }

    @Test fun nativeLoadFailureIsARecoverableFileErrorAndFactoryDoesNotLoadEagerly() = workspace { root ->
        var invoked = false
        val publisher = NewProjectFilePublisher.native { _, _ -> invoked = true; throw UnsatisfiedLinkError("fixture") }
        assertFalse(invoked)
        val failure = assertThrows(IOException::class.java) { publisher.publish(root.resolve("tmp"), root.resolve("new")) }
        assertTrue(invoked)
        assertTrue(failure.cause is UnsatisfiedLinkError)
        assertTrue(failure.message!!.contains("Repair or update Droide"))
        assertFalse(root.resolve("new").exists())
    }

    @Test fun nativeBoundaryReceivesStandardUtf8IncludingSupplementaryCharacters() = workspace { root ->
        val staged = root.resolve(".logo-日本語-🚀.tmp")
        val destination = root.resolve("logo-日本語-🚀.zip")
        NewProjectFilePublisher.native { source, target ->
            assertArrayEquals(staged.absolutePath.toByteArray(Charsets.UTF_8), source)
            assertArrayEquals(destination.absolutePath.toByteArray(Charsets.UTF_8), target)
            0
        }.publish(staged, destination)
    }

    @Test fun streamStagesWholeZipBeforeSinglePublishAndRemovesItsTemporaryFile() = workspace { root ->
        val bytes = byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        var calls = 0
        var closed = false
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher { staged, destination ->
            calls++
            assertArrayEquals(bytes, staged.readBytes())
            assertFalse(destination.exists())
            Files.move(staged.toPath(), destination.toPath()) // Controlled fixture; real JNI races are tested separately.
        })
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        assertEquals(bytes.size.toLong(), runBlocking { repo.writeStream("project.zip", input, requireAbsent = true) })
        assertTrue(closed)
        assertEquals(1, calls)
        assertArrayEquals(bytes, root.resolve("project.zip").readBytes())
        assertEquals(listOf("project.zip"), root.listFiles()!!.map { it.name })
    }

    @Test fun failedPublicationRetainsItsProposalAndClosesOriginalInput() = workspace { root ->
        root.resolve("dependency").writeText("keep")
        var closeCalls = 0
        var publicationCalls = 0
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native { _, _ -> publicationCalls++; 13 })
        val input = object : ByteArrayInputStream("original".toByteArray()) {
            override fun close() { closeCalls++; super.close() }
        }
        val failure = assertThrows(StreamPublicationUncertainException::class.java) {
            runBlocking { repo.writeStream("new.zip", input, requireAbsent = true) }
        }
        assertEquals("new.zip", failure.relativePath)
        assertEquals(8L, failure.bytes)
        assertFalse(failure.publicationReturned)
        assertTrue(failure.cause is FileSystemException)
        assertTrue(failure.cause!!.message!!.contains("errno=13"))
        assertEquals(1, publicationCalls)
        assertEquals(1, closeCalls)
        assertFalse(root.resolve("new.zip").exists())
        assertEquals("keep", root.resolve("dependency").readText())
        assertRetainedProposal(root, repo, "original".toByteArray(), setOf("dependency"))
    }

    @Test fun failedSynchronizationRetainsCommittedNewTextAndReportsExactUtf8Size() = workspace { root ->
        var publications = 0
        val repo = FileRepository(root,
            onMutation = { if (it is FileRepository.Mutation.Write) error("offline sync") },
            newFilePublisher = NewProjectFilePublisher { staged, target ->
                publications++; Files.move(staged.toPath(), target.toPath())
            })
        val failure = assertThrows(ProjectFileSyncException::class.java) {
            runBlocking { repo.writeTextIfUnchanged("logo.txt", "日本語 🚀", DiskFileVersion.fromText(null)) }
        }
        assertEquals(1, publications)
        assertEquals("logo.txt", failure.relativePath)
        assertEquals("日本語 🚀".toByteArray().size.toLong(), failure.bytes)
        assertEquals("日本語 🚀", root.resolve("logo.txt").readText())
        assertEquals(listOf("logo.txt"), root.listFiles()!!.map { it.name })
    }

    @Test fun inPlaceUserEditDuringSyncIsKeptAndNeverReportedAsTheOriginalUpload() = workspace { root ->
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                val target = root.resolve("new.zip")
                val oldTime = Files.getLastModifiedTime(target.toPath())
                target.writeText("user")
                Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(oldTime.toMillis() + 2000))
                error("sync failed after edit")
            }
        })
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.writeStream("new.zip", "data".byteInputStream(), requireAbsent = true) }
        }
        assertEquals("sync failed after edit", failure.message)
        assertEquals("user", root.resolve("new.zip").readText())
        val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals("user".toByteArray(), retained.readBytes())
        assertEquals(setOf("new.zip", retained.name), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun cancellationAfterPublicationKeepsItsMeaningAndCommittedFile() = workspace { root ->
        val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) throw CancellationException("stopped") })
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.writeStream("new.zip", "complete".byteInputStream(), requireAbsent = true) }
        }
        assertEquals("complete", root.resolve("new.zip").readText())
        val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals("complete".toByteArray(), retained.readBytes())
        assertEquals(setOf("new.zip", retained.name), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun failedSourceReadNeverPublishesAPartialProjectFile() = workspace { root ->
        var calls = 0
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ -> calls++ })
        val failure = IOException("source disconnected")
        val prefix = "received prefix".toByteArray()
        var closeCalls = 0
        val input = object : java.io.InputStream() {
            private var first = true
            override fun read(): Int = throw failure
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw failure
                first = false
                prefix.copyInto(bytes, offset)
                return prefix.size
            }
            override fun close() { closeCalls++ }
        }
        val actual = assertThrows(IOException::class.java) {
            runBlocking { repo.writeStream("new.zip", input, requireAbsent = true) }
        }
        assertSame(failure, actual)
        assertEquals(1, closeCalls)
        assertEquals(0, calls)
        assertFalse(root.resolve("new.zip").exists())
        assertRetainedProposal(root, repo, prefix, emptySet())
    }

    private fun assertRetainedProposal(root: File, repository: FileRepository, bytes: ByteArray,
        otherNames: Set<String>) {
        val stage = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals(bytes, stage.readBytes())
        assertEquals(otherNames + stage.name, root.listFiles()!!.map { it.name }.toSet())
        val notice = repository.retainedWork.value.notices.single()
        assertEquals("new.zip", notice.target)
        assertEquals(stage.name, notice.candidate.path)
        assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
        assertEquals(bytes.size.toLong(), notice.candidate.bytes)
        assertFalse(notice.historicalWriteReturned)
    }

    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-publication-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

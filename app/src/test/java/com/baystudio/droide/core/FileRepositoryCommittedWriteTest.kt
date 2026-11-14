package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Publication commits canonical bytes; observer/mirror failures cannot roll that commit back. */
class FileRepositoryCommittedWriteTest {
    @Test fun explorerNewFileUsesTheConfiguredAtomicPublisherForTheExactRequestedName() = workspace { root ->
        var publications = 0
        val template = "fun main() { println(\"hello\") }\n"
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native { source, destination ->
            val staged = File(source.toString(Charsets.UTF_8))
            val target = File(destination.toString(Charsets.UTF_8))
            assertEquals("main.kt", target.name)
            assertEquals(template, staged.readText())
            publications++
            Files.move(staged.toPath(), target.toPath()) // Host fixture; real JNI is tested by its native harness.
            0
        })
        runBlocking {
            repo.writeTextIfUnchanged("main.kt", template, DiskFileVersion.fromText(null), requireExistingParent = true)
        }
        assertEquals(1, publications)
        assertEquals(template, root.resolve("main.kt").readText())
        assertWorkspace(root)
    }

    @Test fun validLongAsciiAndUnicodeNamesSupportCreateOverwriteAndStagedDelete() = workspace { root ->
        val names = listOf("a".repeat(237) + ".kt", "a".repeat(252) + ".kt",
            "界".repeat(79) + ".kt", "界".repeat(84) + ".kt")
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native(), onMutation = {})
        for (name in names) {
            assertTrue(name.toByteArray(Charsets.UTF_8).size in 240..255)
            assertEquals(name, PathSecurity.safeLeafName(name))
            runBlocking {
                repo.writeTextIfUnchanged(name, "created", DiskFileVersion.fromText(null), requireExistingParent = true)
                assertEquals("created", root.resolve(name).readText())
                repo.writeStream(name, "updated".byteInputStream(), requireExistingParent = true)
                assertEquals("updated", root.resolve(name).readText())
                assertTrue(repo.delete(name))
            }
            assertEquals(listOf("dependency"), root.listFiles()!!.map { it.name })
        }
    }

    @Test fun utf8NamesBeyondTheFilesystemLimitAreRejectedBeforeFileCreation() = workspace { root ->
        for (name in listOf("a".repeat(256), "界".repeat(84) + "a.kt")) {
            assertEquals(256, name.toByteArray(Charsets.UTF_8).size)
            val failure = assertThrows(IllegalArgumentException::class.java) { PathSecurity.safeLeafName(name) }
            assertTrue(failure.message!!.contains("255 UTF-8 bytes"))
        }
        assertEquals(listOf("dependency"), root.listFiles()!!.map { it.name })
    }

    @Test fun failedSynchronizationKeepsEveryExistingWriteAndReportsExactCommittedBytes() = forEachWriter { root, writer ->
        root.resolve(TARGET).writeText(OLD)
        val failure = IOException("mirror disconnected")
        val mutations = mutableListOf<FileRepository.Mutation>()
        val repo = FileRepository(root, onMutation = { mutation ->
            mutations += mutation
            if (mutation is FileRepository.Mutation.Write) {
                assertEquals(NEW, root.resolve(TARGET).readText())
                assertEquals(setOf(TARGET, "dependency"), root.listFiles()!!.map { it.name }.toSet())
                throw failure
            }
        })

        val actual = assertThrows(ProjectFileSyncException::class.java) { runBlocking { writer.write(repo) } }
        assertSame(failure, actual.cause)
        assertEquals(TARGET, actual.relativePath)
        assertEquals(NEW.toByteArray(Charsets.UTF_8).size.toLong(), actual.bytes)
        assertEquals(NEW, root.resolve(TARGET).readText())
        assertEquals(1, mutations.count { it is FileRepository.Mutation.Write })
        assertEquals(listOf(FileRepository.Mutation.Sync(TARGET)), mutations.filterIsInstance<FileRepository.Mutation.Sync>())
        assertWorkspace(root)
    }

    @Test fun uncheckedWritesToAbsentPathsAlsoRemainCommittedWhenSynchronizationFails() = workspace { root ->
        for (writer in listOf(Writer.TEXT, Writer.STREAM)) {
            root.resolve(TARGET).delete()
            val failure = IOException("sync unavailable")
            val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) throw failure })
            val actual = assertThrows(ProjectFileSyncException::class.java) { runBlocking { writer.write(repo) } }
            assertSame(failure, actual.cause)
            assertEquals(NEW, root.resolve(TARGET).readText())
            assertWorkspace(root)
        }
    }

    @Test fun terminalReplacementDuringFailedSynchronizationIsPreservedWithoutFalseSavedReceipt() = forEachWriter { root, writer ->
        root.resolve(TARGET).writeText(OLD)
        val failure = IOException("sync failed after terminal replacement")
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                val replacement = root.resolve("terminal-output").apply { writeText("newer terminal work") }
                Files.move(replacement.toPath(), root.resolve(TARGET).toPath(), StandardCopyOption.REPLACE_EXISTING)
                throw failure
            }
        })
        assertOriginatingFailure(failure, assertThrows(IOException::class.java) { runBlocking { writer.write(repo) } })
        assertEquals("newer terminal work", root.resolve(TARGET).readText())
        assertWorkspace(root)
    }

    @Test fun inPlaceTerminalEditDuringFailedSynchronizationKeepsItsBytesAndOriginalFailure() = forEachWriter { root, writer ->
        root.resolve(TARGET).writeText(OLD)
        val failure = IOException("sync failed after in-place edit")
        val newerBytes = ByteArray(NEW.toByteArray(Charsets.UTF_8).size) { 'x'.code.toByte() }
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                val target = root.resolve(TARGET)
                val before = Files.getLastModifiedTime(target.toPath())
                target.writeBytes(newerBytes)
                // Keep the size equal; an in-place edit must be distinguished by publication metadata.
                Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(before.toMillis() + 2000))
                throw failure
            }
        })
        assertOriginatingFailure(failure, assertThrows(IOException::class.java) { runBlocking { writer.write(repo) } })
        assertArrayEquals(newerBytes, root.resolve(TARGET).readBytes())
        assertWorkspace(root)
    }

    @Test fun terminalDeletionDuringFailedSynchronizationDoesNotResurrectOldOrCommittedBytes() = forEachWriter { root, writer ->
        root.resolve(TARGET).writeText(OLD)
        val failure = IOException("sync failed after removal")
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                assertTrue(root.resolve(TARGET).delete())
                throw failure
            }
        })
        assertOriginatingFailure(failure, assertThrows(IOException::class.java) { runBlocking { writer.write(repo) } })
        assertFalse(root.resolve(TARGET).exists())
        assertEquals(listOf("dependency"), root.listFiles()!!.map { it.name })
        assertEquals("keep dependency", root.resolve("dependency").readText())
    }

    @Test fun cancellationAfterAnOverwriteRetainsItsTypeAndCompletePublishedFile() = forEachWriter { root, writer ->
        root.resolve(TARGET).writeText(OLD)
        val cancelled = CancellationException("user stopped")
        val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) throw cancelled })
        assertOriginatingFailure(cancelled, assertThrows(CancellationException::class.java) { runBlocking { writer.write(repo) } })
        assertEquals(NEW, root.resolve(TARGET).readText())
        assertWorkspace(root)
    }

    @Test fun fatalObserverFailureIsNeverConvertedToARecoverableSavedReceipt() = forEachWriter { root, writer ->
        root.resolve(TARGET).writeText(OLD)
        val fatal = LinkageError("test observer linkage failure")
        val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) throw fatal })
        assertOriginatingFailure(fatal, assertThrows(LinkageError::class.java) { runBlocking { writer.write(repo) } })
        assertEquals(NEW, root.resolve(TARGET).readText())
        assertWorkspace(root)
    }

    @Test fun permissionFailureBeforePublicationNeverChangesTheExistingFileOrReportsSaved() = forEachWriter { root, writer ->
        root.resolve(TARGET).writeText(OLD)
        val failure = IOException("write denied")
        var observerCalls = 0
        val repo = FileRepository(root, beforeMutation = { throw failure }, onMutation = { observerCalls++ })
        assertOriginatingFailure(failure, assertThrows(IOException::class.java) { runBlocking { writer.write(repo) } })
        assertEquals(OLD, root.resolve(TARGET).readText())
        assertEquals(0, observerCalls)
        assertWorkspace(root)
    }

    @Test fun failedSourceReadBeforeOverwriteKeepsExistingBytesAndClosesTheInput() = workspace { root ->
        root.resolve(TARGET).writeText(OLD)
        val failure = IOException("picked source disconnected")
        var closed = false
        var observerCalls = 0
        val input = object : ByteArrayInputStream(NEW.toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw failure
            override fun close() { closed = true; super.close() }
        }
        val repo = FileRepository(root, onMutation = { observerCalls++ })
        assertOriginatingFailure(failure, assertThrows(IOException::class.java) { runBlocking { repo.writeStream(TARGET, input) } })
        assertTrue(closed)
        assertEquals(0, observerCalls)
        assertEquals(OLD, root.resolve(TARGET).readText())
        val retained = root.listFiles()!!.filter { RetainedWorkPolicy.isCandidateName(it.name) }.single()
        assertEquals(0L, retained.length())
        assertEquals(setOf(TARGET, "dependency", retained.name), root.listFiles()!!.map { it.name }.toSet())
        assertEquals("keep dependency", root.resolve("dependency").readText())
        val notice = repo.retainedWork.value.notices.single()
        assertEquals(retained.name, notice.candidate.path)
        assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
        assertFalse(notice.historicalWriteReturned)
    }

    private enum class Writer {
        TEXT, CHECKED_TEXT, EDITOR, STREAM;

        suspend fun write(repo: FileRepository) {
            when (this) {
                TEXT -> repo.writeText(TARGET, NEW)
                CHECKED_TEXT -> repo.writeTextIfUnchanged(TARGET, NEW, DiskFileVersion.fromText(OLD))
                EDITOR -> repo.writeEditorTextIfUnchanged(TARGET, NEW, DiskFileVersion.fromText(OLD))
                STREAM -> repo.writeStream(TARGET, NEW.byteInputStream())
            }
        }
    }

    private fun forEachWriter(test: (File, Writer) -> Unit) {
        for (writer in Writer.entries) workspace { root -> test(root, writer) }
    }

    private fun assertWorkspace(root: File) {
        assertEquals("keep dependency", root.resolve("dependency").readText())
        assertEquals(setOf(TARGET, "dependency"), root.listFiles()!!.map { it.name }.toSet())
    }

    private fun workspace(test: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-committed-write-").toFile()
        try {
            root.resolve("dependency").writeText("keep dependency")
            test(root)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    companion object {
        private const val TARGET = "main.kt"
        private const val OLD = "original file"
        private const val NEW = "new committed content 日本語 🚀"
    }
}

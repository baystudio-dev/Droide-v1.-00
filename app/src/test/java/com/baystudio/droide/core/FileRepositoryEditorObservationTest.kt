package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fixed, local files only. No process, native runtime, provider, or new executor. */
class FileRepositoryEditorObservationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun editorObservationAcceptsAboveFiveMiBWithoutExpandingOrdinaryTextLimit() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "large.txt").apply { writeText("before") }
        val files = FileRepository(root)
        val text = "x".repeat(5 * 1024 * 1024 + 1)
        val before = DiskFileVersion.fromText("before")
        val ordinary = withContext(Dispatchers.IO) { files.prepareTextWriteObservation("large.txt", text, before) }
        assertEquals(5L * 1024 * 1024, ordinary.verificationMaxBytes)
        assertTrue(runCatching { files.writeTextIfUnchanged(ordinary) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(ordinary.writeAttempted)
        assertEquals("before", target.readText())
        val editor = withContext(Dispatchers.IO) { files.prepareEditorTextWriteObservation("large.txt", text, before) }
        assertEquals(EditorLargeFilePolicy.MAX_EDITABLE_BYTES, editor.verificationMaxBytes)
        files.writeEditorTextIfUnchanged(editor)
        assertEquals(FileRepository.TextWriteDisposition.OWNED_CURRENT, editor.disposition)
        assertTrue(editor.writeAttempted)
        assertEquals(text, target.readText())
    }

    @Test(timeout = 15_000)
    fun sameBytesInAForeignReplacementInodeDoNotCountAsOwnedCurrent() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("before") }
        var ownedIdentity: Any? = null
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                ownedIdentity = key(target)
                val foreign = File(root, "foreign.tmp").apply { writeText("after") }
                assertNotEquals(ownedIdentity, key(foreign))
                Files.move(foreign.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        })
        val observation = files.prepareEditorTextWriteObservation("main.txt", "after", DiskFileVersion.fromText("before"))
        files.writeEditorTextIfUnchanged(observation)
        assertTrue(requireNotNull(observation.receipt).successful)
        assertTrue(observation.writeAttempted)
        assertNotEquals(ownedIdentity, key(target))
        assertEquals("after", target.readText())
        assertEquals(FileRepository.TextWriteDisposition.RECOVERY_REQUIRED, observation.disposition)
    }

    @Test(timeout = 15_000)
    fun cancellationBeforeIoEntryStillClaimsHolderButDoesNotPublish() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("before") }
        var admissions = 0
        val files = FileRepository(root, beforeMutation = { admissions++ })
        val observation = files.prepareEditorTextWriteObservation("main.txt", "after", DiskFileVersion.fromText("before"))
        val failure = runCatching {
            coroutineScope {
                currentCoroutineContext().cancel(CancellationException("before IO entry"))
                files.writeEditorTextIfUnchanged(observation)
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, admissions)
        assertFalse(observation.localAttempted)
        assertFalse(observation.writeAttempted)
        assertNull(observation.receipt)
        assertEquals(FileRepository.TextWriteDisposition.UNVERIFIED, observation.disposition)
        assertEquals("before", target.readText())
        val reused = runCatching { files.writeEditorTextIfUnchanged(observation) }.exceptionOrNull()
        assertTrue(reused is IllegalStateException)
        assertTrue(reused?.message.orEmpty().contains("already used"))
        assertEquals(0, admissions)
    }

    @Test(timeout = 15_000)
    fun writerModeAndRepositoryAreCheckedBeforeClaimAndMutation() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("before") }
        var admissions = 0
        val files = FileRepository(root, beforeMutation = { admissions++ })
        val editor = files.prepareEditorTextWriteObservation("main.txt", "after", DiskFileVersion.fromText("before"))
        assertTrue(runCatching { files.writeTextIfUnchanged(editor) }.exceptionOrNull() is IllegalStateException)
        val other = FileRepository(temp.newFolder(), beforeMutation = { fail("Foreign repository must not be admitted") })
        assertTrue(runCatching { other.writeEditorTextIfUnchanged(editor) }.exceptionOrNull() is IllegalStateException)
        val ordinary = files.prepareTextWriteObservation("main.txt", "after", DiskFileVersion.fromText("before"))
        assertTrue(runCatching { files.writeEditorTextIfUnchanged(ordinary) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, admissions)
        files.writeEditorTextIfUnchanged(editor)
        assertEquals("after", target.readText())
        assertEquals(1, admissions)
        assertTrue(runCatching { files.writeEditorTextIfUnchanged(editor) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, admissions)
    }

    @Test(timeout = 15_000)
    fun explicitlyOpenedDroideConfigurationKeepsEditorDestinationPolicy() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, ".droide/mcp.json").apply { parentFile.mkdirs(); writeText("{}") }
        val files = FileRepository(root)
        val observation = files.prepareEditorTextWriteObservation(".droide/mcp.json", "{\"servers\":[]}", DiskFileVersion.fromText("{}"))
        files.writeEditorTextIfUnchanged(observation)
        assertEquals("{\"servers\":[]}", target.readText())
        assertEquals(FileRepository.TextWriteDisposition.OWNED_CURRENT, observation.disposition)
    }

    @Test(timeout = 15_000)
    fun executableAndPrivatePermissionsSurviveObservedEditorWrite() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "start.sh").apply { writeText("before") }
        val mode = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(target.toPath(), mode)
        val files = FileRepository(root)
        val observation = files.prepareEditorTextWriteObservation("start.sh", "after", DiskFileVersion.fromText("before"))
        files.writeEditorTextIfUnchanged(observation)
        assertEquals(mode, Files.getPosixFilePermissions(target.toPath()))
        assertEquals(FileRepository.TextWriteDisposition.OWNED_CURRENT, observation.disposition)
    }

    @Test(timeout = 15_000)
    fun readOnlyPermissionsCannotBeBypassedByObservedEditorWrite() = runBlocking {
        val root = temp.newFolder()
        val target = File(root, "main.txt").apply { writeText("before") }
        val mode = PosixFilePermissions.fromString("r--r-----")
        Files.setPosixFilePermissions(target.toPath(), mode)
        val files = FileRepository(root)
        val observation = files.prepareEditorTextWriteObservation("main.txt", "after", DiskFileVersion.fromText("before"))
        assertNotNull(runCatching { files.writeEditorTextIfUnchanged(observation) }.exceptionOrNull())
        assertEquals("before", target.readText())
        assertEquals(mode, Files.getPosixFilePermissions(target.toPath()))
        assertFalse(observation.writeAttempted)
        assertNotEquals(FileRepository.TextWriteDisposition.OWNED_CURRENT, observation.disposition)
    }

    @Test(timeout = 15_000)
    fun observedEditorWriteDoesNotCreateMissingParent() = runBlocking {
        val root = temp.newFolder()
        val files = FileRepository(root)
        val observation = files.prepareEditorTextWriteObservation("missing/main.txt", "after", DiskFileVersion.fromText(null))
        assertNotNull(runCatching { files.writeEditorTextIfUnchanged(observation) }.exceptionOrNull())
        assertFalse(File(root, "missing").exists())
        assertFalse(observation.writeAttempted)
    }

    @Test(timeout = 15_000)
    fun observedEditorWriteRechecksParentAfterAdmissionSuspends() = runBlocking {
        val root = temp.newFolder()
        val parent = File(root, "chosen").apply { mkdir() }
        val files = FileRepository(root, beforeMutation = { assertTrue(parent.delete()) })
        val observation = files.prepareEditorTextWriteObservation("chosen/main.txt", "after", DiskFileVersion.fromText(null))
        assertNotNull(runCatching { files.writeEditorTextIfUnchanged(observation) }.exceptionOrNull())
        assertFalse(parent.exists())
        assertFalse(observation.writeAttempted)
    }

    private fun key(file: File): Any = requireNotNull(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
}

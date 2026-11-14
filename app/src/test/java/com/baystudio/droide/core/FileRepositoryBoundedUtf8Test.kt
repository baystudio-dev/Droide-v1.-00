package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryBoundedUtf8Test {
    @Test fun allBoundedReadersPreserveBomNewlinesAndSupplementaryText() = withWorkspace { root ->
        val text = "\uFEFFfirst\r\n\u00e9\u6f22\uD83D\uDE00\nlast"
        val bytes = text.toByteArray(Charsets.UTF_8)
        root.resolve("text.txt").writeBytes(bytes)
        val repository = FileRepository(root)
        runBlocking {
            assertEquals(text, repository.readText("text.txt", bytes.size.toLong()))
            assertEquals(text, repository.readEditorText("text.txt", bytes.size.toLong()))
            assertEquals(text, repository.readTextForEdit("text.txt", bytes.size.toLong()))
        }
        assertArrayEquals(bytes, root.resolve("text.txt").readBytes())
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun allBoundedReadersAcceptAnEmptyFile() = withWorkspace { root ->
        root.resolve("empty.txt").writeBytes(byteArrayOf())
        val repository = FileRepository(root)
        runBlocking {
            assertEquals("", repository.readText("empty.txt", 1L))
            assertEquals("", repository.readEditorText("empty.txt", 1L))
            assertEquals("", repository.readTextForEdit("empty.txt", 1L))
        }
    }

    @Test fun readLimitsRemainByteBasedAndRetainPublicExceptionTypes() = withWorkspace { root ->
        val bytes = "\u00e9\u6f22\uD83D\uDE00".toByteArray(Charsets.UTF_8)
        root.resolve("text.txt").writeBytes(bytes)
        val repository = FileRepository(root)
        val limit = bytes.size - 1L
        val readFailure = assertThrows(LargeFileException::class.java) {
            runBlocking { repository.readText("text.txt", limit) }
        }
        val editorFailure = assertThrows(LargeFileException::class.java) {
            runBlocking { repository.readEditorText("text.txt", limit) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.readTextForEdit("text.txt", limit) }
        }
        assertEquals(bytes.size.toLong(), readFailure.bytes)
        assertEquals("text.txt", editorFailure.relativePath)
        assertArrayEquals(bytes, root.resolve("text.txt").readBytes())
    }

    @Test fun strictDecodeFailuresKeepOriginalBytesAcrossAllBoundedReaders() = withWorkspace { root ->
        val fixtures = listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        )
        val repository = FileRepository(root)
        for ((index, bytes) in fixtures.withIndex()) {
            val path = "invalid-$index.txt"
            root.resolve(path).writeBytes(bytes)
            val reads: List<suspend () -> String> = listOf(
                { repository.readText(path) },
                { repository.readEditorText(path) },
                { repository.readTextForEdit(path) },
            )
            for (read in reads) {
                val failure = assertThrows(UnsupportedTextEncodingException::class.java) { runBlocking { read() } }
                assertEquals(path, failure.relativePath)
                assertArrayEquals(bytes, root.resolve(path).readBytes())
            }
        }
        assertTrue(root.listFiles()!!.none { it.name.contains(".droide-tmp-") })
    }

    @Test fun writesPreserveBomNewlinesAndOriginalCallbackPayload() = withWorkspace { root ->
        val text = "\uFEFFfirst\r\n\u00e9\u6f22\uD83D\uDE00\n"
        val callbacks = mutableListOf<String>()
        val repository = FileRepository(root,
            beforeMutation = { callbacks += "before:$it" },
            onMutation = { callbacks += "after:$it" },
        )
        runBlocking { repository.writeText("text.txt", text) }
        val mutation = FileRepository.Mutation.Write("text.txt", text)
        assertEquals(listOf("before:$mutation", "after:$mutation"), callbacks)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), root.resolve("text.txt").readBytes())
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun malformedUtf16WritesMatchPreviousReplacementPolicyWithoutChangingCallbackText() = withWorkspace { root ->
        val text = "a".repeat(8_191) + "\uD83D\uDE00\uD800x\uDC00\uD800"
        val before = mutableListOf<FileRepository.Mutation>()
        val after = mutableListOf<FileRepository.Mutation>()
        val repository = FileRepository(root, beforeMutation = { before += it }, onMutation = { after += it })
        runBlocking { repository.writeText("text.txt", text) }
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), root.resolve("text.txt").readBytes())
        assertEquals(listOf(FileRepository.Mutation.Write("text.txt", text)), before)
        assertEquals(before, after)
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun defaultWriteAcceptsExactlyFiveMebibytes() = withWorkspace { root ->
        val text = "a".repeat(DEFAULT_WRITE_LIMIT)
        runBlocking { FileRepository(root).writeText("text.txt", text) }
        assertEquals(DEFAULT_WRITE_LIMIT.toLong(), root.resolve("text.txt").length())
        assertEquals(text, root.resolve("text.txt").readText(Charsets.UTF_8))
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun oversizedUnicodeWriteFailsBeforeCallbacksAndPreservesExistingDestination() = withWorkspace { root ->
        root.resolve("text.txt").writeText("original")
        var before = 0
        var after = 0
        val repository = FileRepository(root, beforeMutation = { before++ }, onMutation = { after++ })
        val text = "\u20ac".repeat(DEFAULT_WRITE_LIMIT / 3 + 1)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.writeText("text.txt", text) } }
        assertEquals(0, before)
        assertEquals(0, after)
        assertEquals("original", root.resolve("text.txt").readText())
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun oversizedNewWriteDoesNotCreateParentDirectoriesOrInvokeCallbacks() = withWorkspace { root ->
        var callbacks = 0
        val repository = FileRepository(root, beforeMutation = { callbacks++ }, onMutation = { callbacks++ })
        val text = "a".repeat(DEFAULT_WRITE_LIMIT + 1)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.writeTextIfUnchanged("new/deep/text.txt", text, DiskFileVersion.fromText(null)) }
        }
        assertEquals(0, callbacks)
        assertFalse(root.resolve("new").exists())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun staleExpectedVersionFailsBeforeMutationCallbacks() = withWorkspace { root ->
        root.resolve("text.txt").writeText("newer disk value")
        var callbacks = 0
        val repository = FileRepository(root, beforeMutation = { callbacks++ }, onMutation = { callbacks++ })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.writeTextIfUnchanged("text.txt", "replacement", DiskFileVersion.fromText("old")) }
        }
        assertEquals(0, callbacks)
        assertEquals("newer disk value", root.resolve("text.txt").readText())
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun versionIsRecheckedAfterBeforeMutationAndForeignContentIsPreserved() = withWorkspace { root ->
        root.resolve("text.txt").writeText("original")
        var after = 0
        val repository = FileRepository(root,
            beforeMutation = { root.resolve("text.txt").writeText("foreign change") },
            onMutation = { after++ },
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.writeTextIfUnchanged("text.txt", "replacement", DiskFileVersion.fromText("original")) }
        }
        assertEquals(0, after)
        assertEquals("foreign change", root.resolve("text.txt").readText())
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun absentExpectedVersionNeverOverwritesDestinationCreatedByBeforeMutation() = withWorkspace { root ->
        var after = 0
        val repository = FileRepository(root,
            beforeMutation = { root.resolve("text.txt").writeText("competing creation") },
            onMutation = { after++ },
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.writeTextIfUnchanged("text.txt", "replacement", DiskFileVersion.fromText(null)) }
        }
        assertEquals(0, after)
        assertEquals("competing creation", root.resolve("text.txt").readText())
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun beforeMutationFailureLeavesDestinationUnchangedAndNoTemporaryFiles() = withWorkspace { root ->
        root.resolve("text.txt").writeText("original")
        val failure = IdentityFailure("before failed")
        var after = 0
        val repository = FileRepository(root, beforeMutation = { throw failure }, onMutation = { after++ })
        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.writeText("text.txt", "replacement") }
        }
        assertSame(failure, actual)
        assertEquals(0, after)
        assertEquals("original", root.resolve("text.txt").readText())
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun existingFileCallbackFailurePreservesCommittedBytesAndReconciliationContract() = withWorkspace { root ->
        root.resolve("text.txt").writeText("original")
        val failure = IllegalStateException("sync failed")
        val mutations = mutableListOf<FileRepository.Mutation>()
        val repository = FileRepository(root, onMutation = { mutation ->
            mutations += mutation
            if (mutation is FileRepository.Mutation.Write) throw failure
        })
        // Editor-save's canonical publication contract supersedes the donor's overwrite rollback.
        val actual = assertThrows(ProjectFileSyncException::class.java) {
            runBlocking { repository.writeTextIfUnchanged("text.txt", "replacement", DiskFileVersion.fromText("original")) }
        }
        assertSame(failure, actual.cause)
        assertEquals("text.txt", actual.relativePath)
        assertEquals("replacement".toByteArray(Charsets.UTF_8).size.toLong(), actual.bytes)
        assertEquals("replacement", root.resolve("text.txt").readText())
        assertEquals(listOf(FileRepository.Mutation.Write("text.txt", "replacement"), FileRepository.Mutation.Sync("text.txt")), mutations)
        assertOnlyTarget(root, "text.txt")
    }

    @Test fun newFileCallbackFailurePreservesCommittedBytesAndReportsExactReplacementByteCount() = withWorkspace { root ->
        val text = "a\uD800\uD83D\uDE00z"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val cause = IllegalStateException("sync failed")
        val mutations = mutableListOf<FileRepository.Mutation>()
        val repository = FileRepository(root, onMutation = { mutation ->
            mutations += mutation
            if (mutation is FileRepository.Mutation.Write) throw cause
        })
        // Keep the editor-save receipt type and its byte count, including surrogate replacement.
        val failure = assertThrows(ProjectFileSyncException::class.java) {
            runBlocking { repository.writeTextIfUnchanged("text.txt", text, DiskFileVersion.fromText(null)) }
        }
        assertEquals("text.txt", failure.relativePath)
        assertEquals(bytes.size.toLong(), failure.bytes)
        assertSame(cause, failure.cause)
        assertArrayEquals(bytes, root.resolve("text.txt").readBytes())
        assertEquals(listOf(FileRepository.Mutation.Write("text.txt", text), FileRepository.Mutation.Sync("text.txt")), mutations)
        val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals(bytes, retained.readBytes())
        assertEquals(setOf("text.txt", retained.name), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun failedNewFileSynchronizationNeverRemovesAForeignReplacement() = withWorkspace { root ->
        val failure = IdentityFailure("sync failed")
        val repository = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                root.resolve("foreign.txt").writeText("foreign replacement")
                Files.move(root.resolve("foreign.txt").toPath(), root.resolve("text.txt").toPath(), StandardCopyOption.REPLACE_EXISTING)
                throw failure
            }
        })
        // A foreign replacement is not this operation's committed file; never issue a saved receipt.
        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.writeTextIfUnchanged("text.txt", "replacement", DiskFileVersion.fromText(null)) }
        }
        assertSame(failure, actual)
        assertEquals("foreign replacement", root.resolve("text.txt").readText())
        val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals("replacement".toByteArray(), retained.readBytes())
        assertEquals(setOf("text.txt", retained.name), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun cancellationBeforeStagingCannotPublishAndCleansUpTemporaryFiles() = withWorkspace { root ->
        root.resolve("text.txt").writeText("original")
        var after = 0
        val repository = FileRepository(root,
            beforeMutation = { currentCoroutineContext().cancel(CancellationException("cancel before staging")) },
            onMutation = { after++ },
        )
        assertThrows(CancellationException::class.java) {
            runBlocking { repository.writeText("text.txt", "replacement".repeat(10_000)) }
        }
        assertEquals(0, after)
        assertEquals("original", root.resolve("text.txt").readText())
        assertOnlyTarget(root, "text.txt")
    }

    // Coroutine debug stack recovery may copy ordinary exceptions across withContext.
    // This identity-bearing fixture deliberately cannot be copied; assertSame still proves
    // the repository propagated the exact failure and did not relabel a foreign mutation.
    private class IdentityFailure(message: String) : IllegalStateException(message) {
        val identityMarker = Any()
    }

    private fun assertOnlyTarget(root: File, target: String) {
        assertEquals(listOf(target), root.listFiles()!!.map { it.name }.sorted())
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-bounded-utf8-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    companion object {
        private const val DEFAULT_WRITE_LIMIT = 5 * 1024 * 1024
    }
}

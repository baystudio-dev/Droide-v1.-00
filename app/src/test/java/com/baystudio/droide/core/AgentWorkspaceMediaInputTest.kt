package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspaceMediaInputTest {
    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("workspace-media-input-").toFile()
        try { block(root, File(root, "working").apply { mkdir() }) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unchangedBinaryMediaStreamsExactBytesAndKeepsItsOriginal() = fixture { _, working ->
        val bytes = ByteArray(128 * 1024 + 13) { (it % 256).toByte() }
        val file = File(working, "output.bin").apply { writeBytes(bytes) }
        AgentWorkspaceMediaInput.open(working, file).use { input ->
            assertArrayEquals(bytes, input.readBytes())
            assertEquals(-1, input.read())
            assertEquals(0, input.read(ByteArray(1), 0, 0))
        }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun emptyOversizedDirectoryAndOutsideOutputsAreRejectedWithoutMutation() = fixture { root, working ->
        val empty = File(working, "empty").apply { createNewFile() }
        val large = File(working, "large").apply { writeText("five!") }
        val outside = File(root, "outside").apply { writeText("private") }
        for (path in listOf(empty, large, working, outside)) {
            assertTrue("Accepted $path", runCatching { AgentWorkspaceMediaInput.open(working, path, maxBytes = 4).close() }.isFailure)
        }
        assertEquals("five!", large.readText())
        assertEquals("private", outside.readText())
        assertEquals(0L, empty.length())
    }

    @Test fun leafIntermediateAndRootSymlinksNeverExposeExternalBytes() = fixture { root, working ->
        val outside = File(root, "outside").apply { mkdir() }
        val secret = File(outside, "private").apply { writeText("outside-secret") }
        val leaf = File(working, "leaf")
        val parent = File(working, "parent")
        val alias = File(root, "root-alias")
        Files.createSymbolicLink(leaf.toPath(), secret.toPath())
        Files.createSymbolicLink(parent.toPath(), outside.toPath())
        Files.createSymbolicLink(alias.toPath(), working.toPath())
        for ((base, file) in listOf(working to leaf, working to File(parent, "private"), alias to File(alias, "leaf"))) {
            assertTrue(runCatching { AgentWorkspaceMediaInput.open(base, file).use { it.readBytes() } }.isFailure)
        }
        assertEquals("outside-secret", secret.readText())
    }

    @Test fun replacingTheNamedFileWithSameSizeAndTimestampInvalidatesTheTransfer() = fixture { _, working ->
        val original = File(working, "output").apply { writeText("original") }
        val modified = Files.getLastModifiedTime(original.toPath())
        AgentWorkspaceMediaInput.open(working, original).use { input ->
            assertEquals('o'.code, input.read())
            val retained = File(working, "retained")
            Files.move(original.toPath(), retained.toPath())
            original.writeText("CLI-next")
            Files.setLastModifiedTime(original.toPath(), modified)
            assertTrue(runCatching { input.readBytes() }.exceptionOrNull() is IOException)
            assertEquals("original", retained.readText())
            assertEquals("CLI-next", original.readText())
            Files.move(original.toPath(), File(working, "CLI-next").toPath())
            Files.move(retained.toPath(), original.toPath())
            assertTrue(runCatching { input.read() }.exceptionOrNull() is IOException)
        }
    }

    @Test fun anAlreadyOpenWriterCannotAppendUndetectedDuringTheTransfer() = fixture { _, working ->
        val file = File(working, "output").apply { writeText("original") }
        RandomAccessFile(file, "rw").use { writer ->
            AgentWorkspaceMediaInput.open(working, file).use { input ->
                assertEquals('o'.code, input.read())
                writer.seek(writer.length()); writer.write("-late CLI".toByteArray()); writer.fd.sync()
                assertTrue(runCatching { input.readBytes() }.exceptionOrNull() is IOException)
            }
        }
        assertEquals("original-late CLI", file.readText())
    }

    @Test fun truncationIsReportedInsteadOfPublishingAPartialMediaFile() = fixture { _, working ->
        val file = File(working, "output").apply { writeText("original") }
        AgentWorkspaceMediaInput.open(working, file).use { input ->
            assertEquals('o'.code, input.read())
            RandomAccessFile(file, "rw").use { it.setLength(2); it.fd.sync() }
            assertTrue(runCatching { input.readBytes() }.exceptionOrNull() is IOException)
        }
        assertEquals("or", file.readText())
    }

    @Test fun replacingAParentWithASymlinkCannotRedirectAnOpenedStream() = fixture { root, working ->
        val parent = File(working, "media").apply { mkdir() }
        val file = File(parent, "image").apply { writeText("inside-only") }
        val outside = File(root, "outside").apply { mkdir() }
        File(outside, "image").writeText("EXTERNAL SECRET")
        val consumed = ByteArrayOutputStream()
        AgentWorkspaceMediaInput.open(working, file).use { input ->
            consumed.write(input.read())
            Files.move(parent.toPath(), File(working, "retained-media").toPath())
            Files.createSymbolicLink(parent.toPath(), outside.toPath())
            val failed = runCatching { while (true) { val byte = input.read(); if (byte < 0) break; consumed.write(byte) } }
            assertTrue(failed.exceptionOrNull() is IOException)
        }
        assertEquals("inside-only", consumed.toString("UTF-8"))
        assertEquals("EXTERNAL SECRET", File(outside, "image").readText())
        assertEquals("inside-only", File(working, "retained-media/image").readText())
    }

    @Test fun failedVerificationDoesNotCommitChatMediaOrRemoveCliWork() = fixture { root, working ->
        val file = File(working, "image.bin").apply { writeBytes(ByteArray(96 * 1024) { 42 }) }
        val project = File(root, "files/projects/default").apply { mkdirs() }
        val store = ChatMediaStore(project)
        val input = object : FilterInputStream(AgentWorkspaceMediaInput.open(working, file)) {
            var changed = false
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                if (!changed && count > 0) {
                    changed = true
                    file.appendText("-unpublished CLI update")
                }
                return count
            }
        }
        val failure = input.use { runCatching { store.import("s-media-review", "image.bin", "application/octet-stream", it) }.exceptionOrNull() }
        assertTrue(failure is IOException)
        assertTrue(store.ownerRefs("s-media-review").isEmpty())
        assertTrue(File(store.storageDirectory, "staging").listFiles().orEmpty().isEmpty())
        assertArrayEquals(ByteArray(96 * 1024) { 42 } + "-unpublished CLI update".toByteArray(), file.readBytes())
    }

    @Test fun actualLocalCliIoReadsUnpublishedWorkingCopyBytesWithoutStartingUbuntu() = fixture { root, _ ->
        val source = File(root, "files/projects/main").apply { mkdirs() }
        File(source, "output.bin").writeText("canonical version")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val host = LocalAgentWorkspaceProcessHost(source, File(root, "cache"), scope, File(root, "files/agent-workspaces"))
            val mapper = host.pathMapper()
            val remote = mapper.localRelativeToRemote("output.bin")
            File(remote).writeText("unpublished CLI version")
            val bytes = host.workspaceIo.openMedia(remote).use { it.readBytes() }
            assertEquals("unpublished CLI version", bytes.toString(Charsets.UTF_8))
            assertEquals("canonical version", File(source, "output.bin").readText())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun rejectedAndRepeatedOpenCloseOperationsDoNotLeakDescriptors() = fixture { _, working ->
        val file = File(working, "output").apply { writeText("small") }
        AgentWorkspaceMediaInput.open(working, file).close()
        val descriptors = File("/proc/self/fd")
        val before = descriptors.list()!!.size
        repeat(100) {
            AgentWorkspaceMediaInput.open(working, file).use { assertEquals('s'.code, it.read()) }
            assertTrue(runCatching { AgentWorkspaceMediaInput.open(working, file, 1).close() }.isFailure)
        }
        assertTrue("Descriptors grew from $before to ${descriptors.list()!!.size}", descriptors.list()!!.size <= before + 2)
    }
}

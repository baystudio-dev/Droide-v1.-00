package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceTransferSpaceTest {
    @Test fun admissionUsesAvailableStorageInsteadOfAFileSizeTier() {
        val gib = 1024L * 1024 * 1024
        for (size in listOf(17L * 1024 * 1024, 4 * gib + 1, 128 * gib, 4 * 1024 * gib)) {
            assertTrue(WorkspaceTransferSpace.canStage(size * 2 + 192 * 1024 * 1024,
                size, 192 * 1024 * 1024, copies = 2))
            assertFalse(WorkspaceTransferSpace.canStage(size * 2 + 192 * 1024 * 1024 - 1,
                size, 192 * 1024 * 1024, copies = 2))
        }
    }

    @Test fun largeLengthsCannotOverflowStorageAdmissionIntoSuccess() {
        assertFalse(WorkspaceTransferSpace.canStage(1024, Long.MAX_VALUE, 1))
        assertFalse(WorkspaceTransferSpace.canStage(Long.MAX_VALUE, Long.MAX_VALUE, 1))
        assertFalse(WorkspaceTransferSpace.canStage(Long.MAX_VALUE, Long.MAX_VALUE / 2 + 1, 0, copies = 2))
        assertTrue(WorkspaceTransferSpace.canStage(Long.MAX_VALUE, Long.MAX_VALUE - 1, 1))
        assertTrue(WorkspaceTransferSpace.canStage(Long.MAX_VALUE, Long.MAX_VALUE / 2, 0, copies = 2))
    }

    @Test fun unavailableOrFullStorageCannotPassEvenForAnEmptyStream() {
        for (available in listOf(Long.MIN_VALUE, -1L, 0L, 192L * 1024 * 1024)) {
            assertFalse(WorkspaceTransferSpace.canStage(available, 0L, 192L * 1024 * 1024))
        }
    }

    @Test fun negativeLengthsAndInvalidCopyCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceTransferSpace.canStage(10, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceTransferSpace.canStage(10, 1, -1) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceTransferSpace.canStage(10, 1, 0, 0) }
    }

    @Test fun repositorySizeHintDoesNotTruncateOrRejectAnActuallyLongerStream() = runBlocking {
        val root = Files.createTempDirectory("stream-length-hint-").toFile()
        try {
            val bytes = ByteArray(1024 * 1024 + 37) { (it % 251).toByte() }
            for ((index, hint) in listOf(null, -1L, 0L, 17L, bytes.size.toLong() + 100L).withIndex()) {
                assertEquals(bytes.size.toLong(), FileRepository(root).writeStream("file-$index.bin",
                    bytes.inputStream(), expectedBytes = hint, requireAbsent = true))
                assertArrayEquals(bytes, root.resolve("file-$index.bin").readBytes())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun impossibleDeclaredLengthFailsForDiskSpaceBeforeReadingAndClosesInput() = runBlocking {
        val root = Files.createTempDirectory("stream-length-overflow-").toFile()
        try {
            root.resolve("asset.bin").writeText("user")
            var reads = 0
            var closed = false
            val input = object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++; return super.read(buffer, offset, length)
                }
                override fun close() { closed = true; super.close() }
            }
            try {
                FileRepository(root).writeStream("asset.bin", input, expectedBytes = Long.MAX_VALUE)
                fail("Insufficient storage must reject before publication")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message.orEmpty().contains("storage"))
            }
            assertEquals(0, reads)
            assertTrue(closed)
            assertEquals("user", root.resolve("asset.bin").readText())
            assertEquals(listOf("asset.bin"), root.listFiles()!!.map { it.name })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unknownLengthStreamStopsWhenDiskFillsAndPreservesTheExistingFile() = runBlocking {
        val directory = Files.createTempDirectory("stream-disk-pressure-").toFile()
        var supplied = 0L
        var closed = false
        val root = object : java.io.File(directory.path) {
            override fun getUsableSpace(): Long =
                if (supplied < 17L * 1024 * 1024) 1024L * 1024 * 1024 else 100L * 1024 * 1024
        }
        try {
            root.resolve("asset.bin").writeText("user changes")
            val input = object : java.io.InputStream() {
                override fun read(): Int = error("Streaming copy must use a buffer")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    java.util.Arrays.fill(buffer, offset, offset + length, 42.toByte())
                    supplied += length
                    return length // No EOF: only physical-space admission can stop this source.
                }
                override fun close() { closed = true }
            }
            val repository = FileRepository(root)
            try {
                repository.writeStream("asset.bin", input,
                    expectedVersion = StreamFileVersion.capture(root, "asset.bin"))
                fail("Copy must stop once storage headroom is unavailable")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message.orEmpty().contains("storage"))
            }
            assertTrue(supplied in 17L * 1024 * 1024..34L * 1024 * 1024)
            assertTrue(closed)
            assertEquals("user changes", root.resolve("asset.bin").readText())
            val partial = root.listFiles()!!.filter { RetainedWorkPolicy.isCandidateName(it.name) }.single()
            assertEquals(setOf("asset.bin", partial.name), root.listFiles()!!.map { it.name }.toSet())
            assertTrue(partial.length() in 17L * 1024 * 1024 until supplied)
            partial.inputStream().use { retained ->
                val buffer = ByteArray(64 * 1024)
                var verified = 0L
                while (true) {
                    val count = retained.read(buffer)
                    if (count < 0) break
                    assertTrue((0 until count).all { buffer[it] == 42.toByte() })
                    verified += count
                }
                assertEquals(partial.length(), verified)
            }
            val notice = repository.retainedWork.value.notices.single()
            assertEquals("asset.bin", notice.target)
            assertEquals(partial.name, notice.candidate.path)
            assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
            assertEquals(partial.length(), notice.candidate.bytes)
            assertFalse(notice.historicalWriteReturned)
            val before = ProjectCopyEvidence.identity(partial)
            val reopened = FileRepository(root).inspectRetainedWork()
            assertTrue(reopened.complete)
            assertNull(reopened.problem)
            assertEquals(partial.name, reopened.candidates.single().path)
            assertEquals(partial.length(), reopened.candidates.single().bytes)
            assertEquals(before, ProjectCopyEvidence.identity(partial))
            assertEquals("user changes", root.resolve("asset.bin").readText())
        } finally { PathSecurity.deleteTreeNoFollow(directory) }
    }
}

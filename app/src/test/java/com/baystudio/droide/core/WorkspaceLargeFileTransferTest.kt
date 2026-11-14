package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Real host >4 GiB reads/writes, not just sparse-file metadata admission.
 * Requires room for two 4 GiB stages plus headroom. No device/PRoot claim.
 */
class WorkspaceLargeFileTransferTest {
    @Test(timeout = 600_000)
    fun cliAndCanonicalRepositoryTransferMoreThanFourGiBWithoutWholeFileAllocation() = runBlocking {
        // Use the build filesystem, not a small /tmp mount (which may be RAM-backed).
        val fixtures = File("build-host-jvm-tests/large-file-fixtures").absoluteFile.apply {
            check(isDirectory || mkdirs())
        }
        val root = Files.createTempDirectory(fixtures.toPath(), "workspace-above-four-gib-").toFile()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            val stage = root.resolve("staging").apply { mkdirs() }.resolve("copy.bin")
            val canonical = root.resolve("canonical").apply { mkdirs() }
            val size = 4L * 1024 * 1024 * 1024 + 65_537L
            val original = source.resolve("asset.glb")
            val marker = byteArrayOf(0, -1, 17, -128, 55, 12, 0)
            RandomAccessFile(original, "rw").use {
                it.setLength(size)
                for (offset in listOf(0L, 2L * 1024 * 1024 * 1024, size - marker.size)) {
                    it.seek(offset); it.write(marker)
                }
            }
            assertTrue("Host needs two-copy free space; available=${root.usableSpace}, path=$root",
                WorkspaceTransferSpace.canStage(root.usableSpace, size, 192L * 1024 * 1024, copies = 2))
            val before = StreamFileVersion.capture(source, original.name)
            assertEquals(size, before.bytes)
            assertEquals(before.sha256, AgentWorkspaceArtifacts.snapshot(source)[original.name])
            AgentWorkspaceArtifacts.pull(source, original, stage)
            assertEquals(size, stage.length())

            var largestRead = 0
            var consumed = 0L
            var lastProgress = 0L
            var progressMonotonic = true
            var streamClosed = false
            val input = object : FilterInputStream(stage.inputStream()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    largestRead = maxOf(largestRead, length)
                    return super.read(bytes, offset, length).also { if (it > 0) consumed += it }
                }
                override fun close() { streamClosed = true; super.close() }
            }
            val mutations = mutableListOf<FileRepository.Mutation>()
            val repository = FileRepository(canonical, onMutation = { mutations += it })
            val absent = StreamFileVersion.capture(canonical, original.name)
            val copied = repository.writeStream(original.name, input, expectedBytes = size,
                requireAbsent = true, expectedVersion = absent, onProgress = {
                    progressMonotonic = progressMonotonic && it.completedBytes >= lastProgress
                    lastProgress = it.completedBytes
                })
            assertEquals(size, copied)
            assertEquals(size, consumed)
            assertEquals(size, lastProgress)
            assertTrue(progressMonotonic)
            assertTrue(streamClosed)
            assertTrue(largestRead in 1..256 * 1024)
            val published = StreamFileVersion.capture(canonical, original.name)
            assertEquals(size, published.bytes)
            assertEquals(before.sha256, published.sha256)
            assertEquals(listOf(FileRepository.Mutation.Write(original.name)), mutations)
            assertTrue(stage.delete()) // Drop only this fixture stage before testing another full copy.
            assertTrue(canonical.resolve(original.name).delete())

            val mirror = LocalAgentWorkspaceMirror(source, root.resolve("mirror"))
            mirror.refresh()
            val mirrored = mirror.directory.resolve(original.name)
            assertEquals(size, mirrored.length())
            assertEquals(before.sha256, mirror.baseline()[original.name]?.sha256)
            assertEquals(before.sha256, StreamFileVersion.capture(mirror.directory, original.name).sha256)
            val key = Files.readAttributes(mirrored.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
            mirror.refresh()
            assertEquals("Unchanged large files must not be recopied", key,
                Files.readAttributes(mirrored.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            before.requireMatches(source, original.name)
            assertTrue(mirrored.delete())
            val storage = SafOperationStorage(forceDirectory = { directory ->
                java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            })
            val evidence = root.resolve("saf-evidence.bin")
            val receipt = storage.copyNew(original, evidence, SafJournalLimits().treeBytes) {}
            assertEquals(size, receipt.bytes)
            assertEquals(before.sha256, receipt.sha256)
            assertEquals(size, original.length())
            println("R115_LARGE_TRANSFER bytes=$size sha256=${before.sha256} largestRepositoryRead=$largestRead " +
                "artifactPull=true canonicalPublication=true mirrorRefresh=true unchangedCopyReused=true safEvidenceCopy=true")
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

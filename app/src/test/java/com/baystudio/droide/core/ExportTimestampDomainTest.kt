package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Also replayed with a test-only Android-style coarse NIO attribute adapter. Filesystem content,
 * inode identity and ExportFileStamp stay real and precise. */
class ExportTimestampDomainTest {
    private val home = Files.createTempDirectory("export-timestamp-").toFile()
    private val root = File(home, "project").apply { mkdir() }
    private val staging = File(home, "staging")
    @After fun cleanup() { home.deleteRecursively() }
    private fun source(name: String, size: Int = 256 * 1024): File = File(root, name).apply {
        writeBytes(ByteArray(size) { (it % 251).toByte() })
        Files.setLastModifiedTime(toPath(), FileTime.from(Instant.ofEpochSecond(1_800_000_000, 123456789)))
        assertEquals(123456789L, ExportFileStamp.read(toPath()).modifiedNanos)
    }
    private fun permit(file: File) = object : VerifiedFileExportCapturePermit {
        override val root = this@ExportTimestampDomainTest.root
        override val scope = ExportScope(file.name)
        override val selectedFileIdentity = ExportFileStamp.read(file.toPath()).identity
        override val verifySingleFile = true
        override val stabilityAssumptions = emptyList<String>()
        override fun validate() = Unit
    }
    private fun requireFixtureIfSelected(store: ExportCaptureStore, file: File) {
        if (System.getProperty("droide.export.coarseFixture") != "true") return
        val method = ExportCaptureStore::class.java.getDeclaredMethod("attrs", Path::class.java).apply { isAccessible = true }
        val attributes = method.invoke(store, file.toPath()) as BasicFileAttributes
        assertEquals("Fixture must exercise Android-style coarse NIO", 0, attributes.lastModifiedTime().toInstant().nano)
        assertEquals("Precise stat must NOT be rounded", 123456789L, ExportFileStamp.read(file.toPath()).modifiedNanos)
    }
    private fun stable(name: String, size: Int = 256 * 1024) {
        val file = source(name, size)
        val store = ExportCaptureStore(staging)
        requireFixtureIfSelected(store, file)
        val initial = ExportFileStamp.read(file.toPath())
        store.capture(permit(file)).use {
            assertEquals(1L, it.entries)
            assertEquals(size.toLong(), it.bytes)
            assertArrayEquals(file.readBytes(), it.data(0).readBytes())
            assertEquals(initial, ExportFileStamp.read(file.toPath()))
            assertEquals(if (size == 0) 0L else 2L, it.sourceFilesRead)
        }
    }
    @Test fun stableShellScriptWithFractionalMtimeCanBeCaptured() = stable("test_device.sh")
    @Test fun stableApkWithFractionalMtimeCanBeCaptured() = stable("Droide3D-debug.apk")
    @Test fun stableEmptyFileWithFractionalMtimeCanBeCaptured() = stable("empty.txt", 0)
    private fun mutation(change: (File, ExportFileStamp) -> Unit) {
        val file = source("mutable.bin")
        val initial = ExportFileStamp.read(file.toPath())
        var changed = false
        val control = ExportControl(onProgress = { progress ->
            if (!changed && progress.bytes > 0) { changed = true; change(file, initial) }
        })
        val failure = assertThrows(ExportFailure::class.java) { ExportCaptureStore(staging).capture(permit(file), control) }
        assertEquals("SourceChanged", failure.category)
        assertTrue("Mutation must actually run after initial admission", changed)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }
    @Test fun nanosecondOnlyMtimeChangeIsStillRejected() = mutation { file, initial ->
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.ofEpochSecond(initial.modifiedSeconds, initial.modifiedNanos + 1)))
        assertEquals(initial.modifiedSeconds, ExportFileStamp.read(file.toPath()).modifiedSeconds)
        assertNotEquals(initial.modifiedNanos, ExportFileStamp.read(file.toPath()).modifiedNanos)
    }
    @Test fun rewriteWithRestoredMtimeIsStillRejected() = mutation { file, initial ->
        RandomAccessFile(file, "rw").use { it.write(42) }
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.ofEpochSecond(initial.modifiedSeconds, initial.modifiedNanos)))
        assertEquals(initial.modifiedNanos, ExportFileStamp.read(file.toPath()).modifiedNanos)
        assertNotEquals(initial, ExportFileStamp.read(file.toPath()))
    }
    @Test fun metadataRoundTripWithSameBytesAndMtimeIsStillRejected() = mutation { file, initial ->
        Files.setAttribute(file.toPath(), "unix:mode", (initial.mode and 0xfff) xor 0x40)
        Files.setAttribute(file.toPath(), "unix:mode", initial.mode and 0xfff)
        val after = ExportFileStamp.read(file.toPath())
        assertEquals(initial.mode, after.mode)
        assertEquals(initial.modifiedSeconds, after.modifiedSeconds)
        assertEquals(initial.modifiedNanos, after.modifiedNanos)
        assertNotEquals(initial.changedSeconds to initial.changedNanos, after.changedSeconds to after.changedNanos)
    }
    @Test fun sameContentReplacementIsStillRejected() = mutation { file, initial ->
        val replacement = File(root, "replacement").apply { writeBytes(file.readBytes()) }
        Files.setLastModifiedTime(replacement.toPath(), FileTime.from(Instant.ofEpochSecond(initial.modifiedSeconds, initial.modifiedNanos)))
        Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING)
    }
}

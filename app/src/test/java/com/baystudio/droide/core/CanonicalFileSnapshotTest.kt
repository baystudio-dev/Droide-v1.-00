package com.baystudio.droide.core

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Host integration tests: the production JNI library must be on java.library.path. */
class CanonicalFileSnapshotTest {
    @Test fun completedSnapshotSurvivesWritesThroughAnOldDescriptorAndHardLink() = fixture { f, source, custody ->
        val alias = Files.createLink(f.root.resolve("old-hardlink"), f.file)
        RandomAccessFile(f.file.toFile(), "rw").use { oldDescriptor ->
            val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
            val expected = engine.inspect(source, "source", empty)
            val receipt = engine.capture(source, "source", expected, custody, "copy", empty)
            assertNotEquals(expected.identity, receipt.copy.identity)
            assertEquals(expected.copy(identity = receipt.copy.identity), receipt.copy)
            assertEquals(source.directoryKey(), receipt.sourceParent)
            assertEquals(custody.directoryKey(), receipt.copyParent)
            oldDescriptor.seek(0); oldDescriptor.write(byteArrayOf(99, 98, 97))
            RandomAccessFile(alias.toFile(), "rw").use { it.seek(20); it.write(byteArrayOf(96, 95)) }
            val changed = f.bytes.copyOf().apply { this[0] = 99; this[1] = 98; this[2] = 97; this[20] = 96; this[21] = 95 }
            assertBytes(changed, f.file); assertBytes(changed, alias); assertBytes(f.bytes, f.output)
            assertEquals(key(f.output), receipt.copy.identity)
        }
    }

    @Test fun growthDuringCopyRejectsReceiptAndPreservesSourceAndPartialSnapshot() = sizeMutation(grow = true)
    @Test fun shrinkDuringCopyRejectsReceiptAndPreservesSourceAndPartialSnapshot() = sizeMutation(grow = false)

    private fun sizeMutation(grow: Boolean) = fixture { f, source, custody ->
        var copied = 0L
        val changed = if (grow) f.bytes + byteArrayOf(91, 92) else f.bytes.copyOf(23)
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { boundary, total ->
            if (boundary == CanonicalFileSnapshot.Boundary.COPIED_BLOCK && copied == 0L) {
                copied = total
                val modified = Files.getLastModifiedTime(f.file)
                if (grow) RandomAccessFile(f.file.toFile(), "rw").use { it.seek(it.length()); it.write(byteArrayOf(91, 92)) }
                else RandomAccessFile(f.file.toFile(), "rw").use { it.setLength(23) }
                Files.setLastModifiedTime(f.file, modified)
            }
        }
        val expected = engine.inspect(source, "source", empty)
        refused { engine.capture(source, "source", expected, custody, "copy", empty) }
        assertTrue("Mutation checkpoint was reached", copied > 0)
        assertBytes(changed, f.file)
        val partial = Files.readAllBytes(f.output)
        assertTrue(partial.size.toLong() >= copied && partial.size <= f.bytes.size)
        assertArrayEquals(f.bytes.copyOf(partial.size), partial)
    }

    @Test fun sameSizeSourceWriteAfterCopyIsDetectedEvenWithRestoredMtime() = fixture { f, source, custody ->
        val modified = Files.getLastModifiedTime(f.file)
        val changed = f.bytes.copyOf().apply { this[0] = (this[0].toInt() xor 127).toByte() }
        var reached = false
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { boundary, _ ->
            if (boundary == CanonicalFileSnapshot.Boundary.COPIED) {
                reached = true; Files.write(f.file, changed); Files.setLastModifiedTime(f.file, modified)
            }
        }
        val expected = engine.inspect(source, "source", empty)
        refused { engine.capture(source, "source", expected, custody, "copy", empty) }
        assertTrue(reached); assertEquals(modified, Files.getLastModifiedTime(f.file))
        assertBytes(changed, f.file); assertBytes(f.bytes, f.output)
    }

    @Test fun sameSizeWriteIntoUnreadSourceContentWithRestoredMtimeCannotReceiveACaptureReceipt() = fixture { f, source, custody ->
        val modified = Files.getLastModifiedTime(f.file)
        val changed = f.bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 127).toByte() }
        var reached = false
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { boundary, total ->
            if (boundary == CanonicalFileSnapshot.Boundary.COPIED_BLOCK && !reached) {
                assertTrue("Mutation must target bytes not yet copied", total < f.bytes.size)
                reached = true
                RandomAccessFile(f.file.toFile(), "rw").use { it.seek(changed.lastIndex.toLong()); it.write(changed.last().toInt()) }
                Files.setLastModifiedTime(f.file, modified)
            }
        }
        val expected = engine.inspect(source, "source", empty)
        refused { engine.capture(source, "source", expected, custody, "copy", empty) }
        assertTrue(reached); assertEquals(modified, Files.getLastModifiedTime(f.file))
        assertBytes(changed, f.file); assertBytes(changed, f.output)
    }

    @Test fun sameContentInodeSubstitutionBeforeCaptureRejectsBeforeCreatingOutput() = fixture { f, source, custody ->
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
        val expected = engine.inspect(source, "source", empty)
        val retired = Files.move(f.file, f.work.resolve("retained"))
        write(f.file, f.bytes)
        assertNotEquals(expected.identity, key(f.file))
        refused { engine.capture(source, "source", expected, custody, "copy", empty) }
        assertBytes(f.bytes, retired); assertBytes(f.bytes, f.file); assertAbsent(f.output)
    }

    @Test fun sameContentInodeSubstitutionAfterOpenCannotHideBehindPinnedDescriptor() = fixture { f, source, custody ->
        val retired = f.work.resolve("retained")
        var reached = false
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { boundary, _ ->
            if (boundary == CanonicalFileSnapshot.Boundary.OPENED) {
                reached = true; Files.move(f.file, retired); write(f.file, f.bytes)
            }
        }
        val expected = engine.inspect(source, "source", empty)
        refused { engine.capture(source, "source", expected, custody, "copy", empty) }
        assertTrue(reached); assertNotEquals(expected.identity, key(f.file))
        assertBytes(f.bytes, retired); assertBytes(f.bytes, f.file); assertAbsent(f.output)
    }

    @Test fun existingFileDirectorySymlinkAndHardlinkCustodyEntriesAreNeverReplaced() {
        for (kind in listOf("file", "directory", "symlink", "hardlink")) fixture { f, source, custody ->
            val contender = byteArrayOf(71, 72, 73)
            val outside = f.root.resolve("contender").also { write(it, contender) }
            when (kind) {
                "file" -> write(f.output, contender)
                "directory" -> { Files.createDirectory(f.output); write(f.output.resolve("kept"), contender) }
                "symlink" -> Files.createSymbolicLink(f.output, outside)
                "hardlink" -> Files.createLink(f.output, f.file)
            }
            val identity = key(f.output)
            val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
            val expected = engine.inspect(source, "source", empty)
            refused { engine.capture(source, "source", expected, custody, "copy", empty) }
            assertEquals(identity, key(f.output)); assertBytes(f.bytes, f.file); assertBytes(contender, outside)
            when (kind) {
                "directory" -> assertBytes(contender, f.output.resolve("kept"))
                "symlink" -> assertEquals(outside, Files.readSymbolicLink(f.output))
                "hardlink" -> { assertTrue(Files.isSameFile(f.file, f.output)); assertBytes(f.bytes, f.output) }
                else -> assertBytes(contender, f.output)
            }
        }
    }

    @Test fun replacingOrMutatingSnapshotAfterForceOrVerificationRejectsAndRetainsEveryVersion() {
        for (boundary in listOf(CanonicalFileSnapshot.Boundary.FORCED, CanonicalFileSnapshot.Boundary.VERIFIED)) {
            for (replace in listOf(false, true)) fixture { f, source, custody ->
                val contender = ByteArray(f.bytes.size) { 77 }
                val retired = f.custody.resolve("retained-copy")
                var reached = false
                val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { at, _ ->
                    if (at == boundary) {
                        reached = true
                        val modified = Files.getLastModifiedTime(f.output)
                        if (replace) Files.move(f.output, retired)
                        write(f.output, contender); Files.setLastModifiedTime(f.output, modified)
                    }
                }
                val expected = engine.inspect(source, "source", empty)
                refused { engine.capture(source, "source", expected, custody, "copy", empty) }
                assertTrue(reached); assertBytes(f.bytes, f.file); assertBytes(contender, f.output)
                if (replace) assertBytes(f.bytes, retired)
            }
        }
    }

    @Test fun sameContentSnapshotReplacementStillRejectsItsDifferentIdentity() = fixture { f, source, custody ->
        val retired = f.custody.resolve("retained-copy")
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { boundary, _ ->
            if (boundary == CanonicalFileSnapshot.Boundary.FORCED) {
                Files.move(f.output, retired); write(f.output, f.bytes)
            }
        }
        val expected = engine.inspect(source, "source", empty)
        refused { engine.capture(source, "source", expected, custody, "copy", empty) }
        assertNotEquals(key(retired), key(f.output))
        assertBytes(f.bytes, retired); assertBytes(f.bytes, f.output); assertBytes(f.bytes, f.file)
    }

    @Test fun swappingEitherPinnedRootOrItsParentRejectsWithoutTouchingContenders() {
        for (swapSource in listOf(false, true)) for (ancestor in listOf(false, true)) fixture { f, source, custody ->
            val directory = if (swapSource) f.work else f.custody
            val moved = if (ancestor) directory.parent else directory
            val retained = f.root.resolve("retained-tree")
            val retainedDirectory = if (ancestor) retained.resolve(directory.fileName) else retained
            val name = if (swapSource) "source" else "copy"
            val contender = byteArrayOf(81, 82, 83)
            var reached = false
            val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { boundary, _ ->
                if (boundary == CanonicalFileSnapshot.Boundary.VERIFIED) {
                    reached = true; Files.move(moved, retained); Files.createDirectories(directory)
                    write(directory.resolve(name), contender)
                }
            }
            val expected = engine.inspect(source, "source", empty)
            refused { engine.capture(source, "source", expected, custody, "copy", empty) }
            assertTrue(reached); assertBytes(contender, directory.resolve(name))
            assertBytes(f.bytes, retainedDirectory.resolve(name))
            assertBytes(f.bytes, if (swapSource) f.output else f.file)
        }
    }

    @Test fun cancellationBeforeAdmissionCreatesNothing() = fixture { f, source, custody ->
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
        val expected = engine.inspect(source, "source", empty)
        val job = Job().apply { cancel() }
        assertTrue(refused { engine.capture(source, "source", expected, custody, "copy", job) } is CancellationException)
        assertBytes(f.bytes, f.file); assertAbsent(f.output)
    }

    @Test fun cancellationAtEachBoundaryRetainsSourceAndAnyAlreadyCreatedSnapshot() {
        for (stop in CanonicalFileSnapshot.Boundary.values()) fixture { f, source, custody ->
            val job = Job()
            var reached = false
            var copied = 0L
            val engine = CanonicalFileSnapshot(f.bytes.size.toLong()) { boundary, total ->
                if (boundary == stop) { reached = true; copied = total; job.cancel() }
            }
            val expected = engine.inspect(source, "source", empty)
            try {
                assertTrue(refused { engine.capture(source, "source", expected, custody, "copy", job) } is CancellationException)
                assertTrue(reached); assertBytes(f.bytes, f.file)
                if (stop == CanonicalFileSnapshot.Boundary.OPENED) assertAbsent(f.output)
                else assertBytes(f.bytes.copyOf(copied.toInt()), f.output)
            } finally { job.cancel() }
        }
    }

    @Test fun byteBudgetRejectsOversizeBeforeOutputAndAcceptsExactSizeAndEmptyFiles() = fixture { f, source, custody ->
        val expected = CanonicalFileSnapshot(f.bytes.size.toLong()).inspect(source, "source", empty)
        val small = CanonicalFileSnapshot(f.bytes.size - 1L)
        refused { small.inspect(source, "source", empty) }
        refused { small.capture(source, "source", expected, custody, "copy", empty) }
        assertAbsent(f.output); assertBytes(f.bytes, f.file)
        CanonicalFileSnapshot(f.bytes.size.toLong()).capture(source, "source", expected, custody, "copy", empty)
        assertBytes(f.bytes, f.output)
        write(f.work.resolve("empty"), byteArrayOf())
        val zero = CanonicalFileSnapshot(0)
        val emptyVersion = zero.inspect(source, "empty", empty)
        assertEquals(0L, zero.capture(source, "empty", emptyVersion, custody, "empty-copy", empty).copy.size)
        assertBytes(byteArrayOf(), f.custody.resolve("empty-copy"))
    }

    @Test fun privateOrdinaryAndExecutableModesArePreservedExactly() {
        for (mode in listOf(384, 420, 493)) fixture { f, source, custody ->
            Files.setAttribute(f.file, "unix:mode", mode)
            val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
            val expected = engine.inspect(source, "source", empty)
            val receipt = engine.capture(source, "source", expected, custody, "copy", empty)
            assertEquals(mode, receipt.copy.mode); assertEquals(mode, permissionBits(f.file)); assertEquals(mode, permissionBits(f.output))
            assertBytes(f.bytes, f.file); assertBytes(f.bytes, f.output)
        }
    }

    @Test fun readOnlyAndSpecialBitModesRejectBeforeAnyOutputCreation() {
        for (mode in listOf(292, 2541, 1517, 1005)) fixture { f, source, custody ->
            Files.setAttribute(f.file, "unix:mode", mode)
            assertEquals(mode, permissionBits(f.file))
            val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
            val expected = engine.inspect(source, "source", empty)
            refused { engine.capture(source, "source", expected, custody, "copy", empty) }
            assertEquals(mode, permissionBits(f.file)); assertBytes(f.bytes, f.file); assertAbsent(f.output)
        }
    }

    @Test fun unsafeLeafNamesCannotReadOrCreateOutsideThePinnedParents() = fixture { f, source, custody ->
        val sentinel = f.root.resolve("outside").also { write(it, byteArrayOf(31, 32)) }
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
        val expected = engine.inspect(source, "source", empty)
        for (name in listOf("", ".", "..", "../outside", "nested/file", "a\\b", "a\u0000b", sentinel.toString())) {
            refused { engine.inspect(source, name, empty) }
            refused { engine.capture(source, name, expected, custody, "copy", empty) }
            refused { engine.capture(source, "source", expected, custody, name, empty) }
        }
        assertBytes(byteArrayOf(31, 32), sentinel); assertBytes(f.bytes, f.file); assertAbsent(f.output)
        Files.newDirectoryStream(f.custody).use { assertFalse(it.iterator().hasNext()) }
    }

    @Test fun sourceSymlinksAndDirectoriesCannotBeCapturedAsOrdinaryFiles() = fixture { f, source, custody ->
        Files.createSymbolicLink(f.work.resolve("alias"), f.file)
        Files.createDirectory(f.work.resolve("directory"))
        val engine = CanonicalFileSnapshot(f.bytes.size.toLong())
        val expected = engine.inspect(source, "source", empty)
        for (name in listOf("alias", "directory")) {
            refused { engine.inspect(source, name, empty) }
            refused { engine.capture(source, name, expected, custody, "copy", empty) }
        }
        assertEquals(f.file, Files.readSymbolicLink(f.work.resolve("alias")))
        assertTrue(Files.isDirectory(f.work.resolve("directory"), NOFOLLOW_LINKS))
        assertBytes(f.bytes, f.file); assertAbsent(f.output)
    }

    private val empty: CoroutineContext = EmptyCoroutineContext
    private class Fixture {
        val root: Path = Files.createTempDirectory("canonical-snapshot-jni-")
        val work: Path = Files.createDirectories(root.resolve("source-parent/work"))
        val custody: Path = Files.createDirectories(root.resolve("custody-parent/custody"))
        val file: Path = work.resolve("source")
        val output: Path = custody.resolve("copy")
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
    }
    private fun fixture(block: (Fixture, AnchoredWorkspaceFiles, AnchoredWorkspaceFiles) -> Unit) {
        val f = Fixture()
        try {
            write(f.file, f.bytes); Files.setLastModifiedTime(f.file, FileTime.fromMillis(1_600_000_000_000L))
            AnchoredWorkspaceFiles(f.work.toFile()).use { source ->
                AnchoredWorkspaceFiles(f.custody.toFile()).use { custody -> block(f, source, custody) }
            }
        } finally {
            // walkFileTree without FOLLOW_LINKS only removes this fixture and the links themselves.
            Files.walkFileTree(f.root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir); return FileVisitResult.CONTINUE
                }
            })
        }
    }
    private fun write(path: Path, bytes: ByteArray) { Files.write(path, bytes); Files.setAttribute(path, "unix:mode", 420) }
    private fun key(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey().toString()
    private fun permissionBits(path: Path) = (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 4095
    private fun assertBytes(expected: ByteArray, path: Path) = assertArrayEquals(path.toString(), expected, Files.readAllBytes(path))
    private fun assertAbsent(path: Path) = assertFalse(path.toString(), Files.exists(path, NOFOLLOW_LINKS))
    private fun refused(action: () -> Any?): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Conflicting or cancelled operation returned a successful result")
    }
}

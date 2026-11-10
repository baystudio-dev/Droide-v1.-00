package com.baystudio.droide.core

import android.app.Application
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class CMakeHostOsTest {
    private val directoryFlags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
    private val createFlags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW

    @Test fun realPermissionsAndNoFollowIdentityAreObserved() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-stat")
        try {
            Os.chmod(root.path, 448)
            val value = Os.lstat(root.path)
            assertEquals(448, value.st_mode and 511)
            assertEquals(Os.getuid(), value.st_uid)
            val alias = File(root, "alias")
            Files.createSymbolicLink(alias.toPath(), root.toPath())
            assertTrue(OsConstants.S_ISLNK(Os.lstat(alias.path).st_mode))
            assertThrows(IllegalStateException::class.java) { Os.chmod(alias.path, 511) }
            assertEquals(448, Os.lstat(root.path).st_mode and 511)
        } finally { root.deleteRecursively() }
    }

    @Test fun descriptorIdentitySurvivesPathReplacementAndSyncUsesOpenedDirectory() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-descriptor")
        try {
            val directory = File(root, "directory").apply { mkdir() }
            val before = Os.lstat(directory.path)
            val fd = Os.open(directory.path, directoryFlags, 0)
            try {
                Files.move(directory.toPath(), File(root, "moved").toPath())
                assertTrue(directory.mkdir())
                assertNotEquals(before.st_ino, Os.lstat(directory.path).st_ino)
                assertEquals(before.st_ino, Os.fstat(fd).st_ino)
                assertTrue(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
                Os.fsync(fd)
            } finally { Os.close(fd) }
            assertThrows(ErrnoException::class.java) { Os.fstat(fd) }
            assertThrows(ErrnoException::class.java) { Os.fsync(fd) }
            assertThrows(ErrnoException::class.java) { Os.close(fd) }
        } finally { root.deleteRecursively() }
    }

    @Test fun exclusiveCreateWritesRealBytesWithRequestedModeAndRejectsReplacement() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-exclusive")
        try {
            val file = File(root, "record")
            val fd = Os.open(file.path, createFlags, 384)
            FileOutputStream(fd).use { stream ->
                stream.write("retained".toByteArray())
                stream.flush()
                Os.fsync(fd)
                assertEquals(384, Os.fstat(fd).st_mode and 511)
            }
            assertFalse(fd.valid())
            assertThrows(ErrnoException::class.java) { Os.fstat(fd) }
            val failure = assertThrows(ErrnoException::class.java) { Os.open(file.path, createFlags, 384) }
            assertEquals(OsConstants.EEXIST, failure.errno)
            assertEquals("retained", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun regularReadRequiresActualCloseOnExecAndStreamCloseInvalidatesDescriptor() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-read")
        try {
            val file = File(root, "read").apply { writeText("actual bytes") }
            val flags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
            val fd = Os.open(file.path, flags, 0)
            val expected = Os.lstat(file.path)
            assertEquals(expected.st_ino, Os.fstat(fd).st_ino)
            FileInputStream(fd).use { assertEquals("actual bytes", it.readBytes().toString(Charsets.UTF_8)) }
            assertFalse(fd.valid())
            assertThrows(ErrnoException::class.java) { Os.fstat(fd) }
            assertThrows(ErrnoException::class.java) { Os.close(fd) }
        } finally { root.deleteRecursively() }
    }

    @Test fun symlinkOpenAndAncestorTraversalNeverTouchTarget() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-links")
        val outside = Files.createTempDirectory("cmake-os-outside").toFile()
        try {
            val target = File(outside, "sentinel").apply { writeText("unchanged") }
            val alias = File(root, "alias")
            Files.createSymbolicLink(alias.toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { Os.open(alias.path, directoryFlags, 0) }
            assertThrows(IllegalStateException::class.java) { Os.open(File(alias, "new").path, createFlags, 384) }
            assertThrows(IllegalStateException::class.java) { Os.chmod(target.path, 0) }
            assertEquals("unchanged", target.readText())
            assertFalse(File(outside, "new").exists())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun unsupportedFlagsAndNonblockingRegularFilesAreRejected() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-flags")
        try {
            val file = File(root, "file").apply { writeText("retain") }
            assertThrows(ErrnoException::class.java) { Os.open(file.path, OsConstants.O_TRUNC or OsConstants.O_WRONLY, 0) }
            assertThrows(IllegalStateException::class.java) { Os.open(file.path, directoryFlags, 0) }
            assertEquals("retain", file.readText())
            assertThrows(IllegalStateException::class.java) { Os.kill(1, 0) }
        } finally { root.deleteRecursively() }
    }

    @Test fun rootReplacementIsNotAContinuedGrant() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-root")
        val moved = File(root.parentFile, root.name + "-moved")
        try {
            Files.move(root.toPath(), moved.toPath())
            assertTrue(root.mkdir())
            assertThrows(IllegalStateException::class.java) { Os.chmod(root.path, 448) }
            assertThrows(IllegalStateException::class.java) { Os.open(File(root, "new").path, createFlags, 384) }
            assertFalse(File(root, "new").exists())
        } finally { root.deleteRecursively(); moved.deleteRecursively() }
    }

    @Test fun resetDoesNotCloseAnotherDescriptorAfterStreamOwnedClose() {
        val root = CMakeHostOs.temporaryRoot("cmake-os-recycle")
        try {
            val first = Os.open(File(root, "first").path, createFlags, 384)
            FileOutputStream(first).use { it.write(1) }
            val unrelated = FileOutputStream(File(root, "unrelated"))
            unrelated.use {
                CMakeHostOs.reset()
                it.write(2)
                it.flush()
                assertTrue(it.fd.valid())
            }
            assertArrayEquals(byteArrayOf(2), File(root, "unrelated").readBytes())
        } finally { root.deleteRecursively() }
    }
}

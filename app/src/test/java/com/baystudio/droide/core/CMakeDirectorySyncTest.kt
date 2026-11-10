package com.baystudio.droide.core

import android.app.Application
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Inert API fault boundary. Robolectric's ordinary ShadowLinux cannot open a directory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [DirectorySyncOsBoundary::class])
class CMakeDirectorySyncTest {
    @Before fun reset() { DirectorySyncOsBoundary.reset() }

    @Test fun directoryDescriptorIsSyncedAndClosed() {
        CMakePrivateState.syncDirectory(File("/authored/directory"))
        assertEquals(listOf("open", "fstat", "fsync", "close"), DirectorySyncOsBoundary.calls)
        assertTrue(DirectorySyncOsBoundary.flags and OsConstants.O_NOFOLLOW != 0)
        assertTrue(DirectorySyncOsBoundary.flags and OsConstants.O_NONBLOCK != 0)
    }

    @Test fun nonDirectoryCannotBeSyncedAndDescriptorIsStillClosed() {
        DirectorySyncOsBoundary.directory = false
        assertThrows(IllegalStateException::class.java) { CMakePrivateState.syncDirectory(File("/authored/file")) }
        assertEquals(listOf("open", "fstat", "close"), DirectorySyncOsBoundary.calls)
    }

    @Test fun syncFailurePropagatesAndDescriptorIsStillClosed() {
        DirectorySyncOsBoundary.failSync = true
        assertThrows(IllegalStateException::class.java) { CMakePrivateState.syncDirectory(File("/authored/directory")) }
        assertEquals(listOf("open", "fstat", "fsync", "close"), DirectorySyncOsBoundary.calls)
    }
}

@Implements(Os::class)
class DirectorySyncOsBoundary {
    companion object {
        val calls = mutableListOf<String>()
        var directory = true
        var failSync = false
        var flags = 0
        private var descriptor = FileDescriptor()
        fun reset() { calls.clear(); directory = true; failSync = false; flags = 0; descriptor = FileDescriptor() }
        @JvmStatic @Implementation fun open(path: String, mode: Int, permissions: Int): FileDescriptor {
            calls += "open"; flags = mode; return descriptor
        }
        @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat {
            check(fd === descriptor); calls += "fstat"
            return StructStat(0, 0, if (directory) OsConstants.S_IFDIR else OsConstants.S_IFREG,
                1, 0, 0, 0, 0, 0, 0, 0, 4096, 0)
        }
        @JvmStatic @Implementation fun fsync(fd: FileDescriptor) {
            check(fd === descriptor); calls += "fsync"; check(!failSync) { "Authored fsync failure" }
        }
        @JvmStatic @Implementation fun close(fd: FileDescriptor) { check(fd === descriptor); calls += "close" }
    }
}

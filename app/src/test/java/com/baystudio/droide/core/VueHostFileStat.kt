package com.baystudio.droide.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.system.StructTimespec
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Explicit Vue-fixture boundary, never installed globally or used by production.
 * Observe real Unix host identity, type and link count without following symlinks.
 * NIO does not expose allocation blocks/block size: those two unused fields are
 * zero and are NOT certified by this fixture. Unsupported Unix views fail closed.
 */
@Implements(Os::class)
class VueHostFileStat {
    companion object {
        @JvmStatic
        @Implementation
        @Throws(ErrnoException::class)
        fun lstat(path: String): StructStat {
            val attributes = try {
                Files.readAttributes(Paths.get(path), "unix:*", NOFOLLOW_LINKS)
            } catch (failure: IOException) {
                val errno = when (failure) {
                    is NoSuchFileException -> OsConstants.ENOENT
                    is AccessDeniedException -> OsConstants.EACCES
                    is NotDirectoryException -> OsConstants.ENOTDIR
                    is FileSystemLoopException -> OsConstants.ELOOP
                    else -> OsConstants.EIO
                }
                throw ErrnoException("lstat", errno, failure)
            }
            fun number(name: String) = (attributes.getValue(name) as Number).toLong()
            fun time(name: String): StructTimespec {
                val instant = (attributes.getValue(name) as FileTime).toInstant()
                return StructTimespec(instant.epochSecond, instant.nano.toLong())
            }
            return StructStat(number("dev"), number("ino"), number("mode").toInt(),
                number("nlink"), number("uid").toInt(), number("gid").toInt(),
                number("rdev"), number("size"), time("lastAccessTime"),
                time("lastModifiedTime"), time("ctime"), 0L, 0L)
        }
    }
}

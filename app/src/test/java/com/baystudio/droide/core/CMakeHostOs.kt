package com.baystudio.droide.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.system.StructTimespec
import java.io.File
import java.io.FileInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** Opt-in Linux host fixture, never an Android implementation or global shadow.
 * Real no-follow opens, descriptor identity and FileChannel.force are exercised.
 * This does not establish crash survival or Android device compatibility.
 * Path checks are not atomic with open, and external stream closes are not locked
 * by this shadow. This fixture does not certify adversarial concurrent-writer safety.
 * Requires the dedicated CMake host profile's sun.nio.ch/sun.nio.fs/java.io openings.
 */
@Implements(Os::class)
class CMakeHostOs {
    companion object {
        private data class Handle(val channel: FileChannel, val number: Int)
        private val roots = linkedMapOf<Path, Any>()
        private val handles = java.util.IdentityHashMap<FileDescriptor, Handle>()

        @Synchronized fun temporaryRoot(prefix: String): File {
            val root = Files.createTempDirectory(prefix).toAbsolutePath().normalize()
            roots[root] = requireNotNull(Files.readAttributes(root, "unix:dev,ino", NOFOLLOW_LINKS).let {
                Pair(it.getValue("dev"), it.getValue("ino"))
            })
            return root.toFile()
        }

        private fun checkedPath(value: String, allowLeafLink: Boolean = false): Path {
            val path = Path.of(value)
            require(path.isAbsolute && path == path.normalize()) { "Noncanonical fixture path" }
            val owner = roots.entries.firstOrNull { path.startsWith(it.key) }
                ?: error("Path outside registered CMake fixture roots")
            val identity = Files.readAttributes(owner.key, "unix:dev,ino", NOFOLLOW_LINKS)
            check(Pair(identity.getValue("dev"), identity.getValue("ino")) == owner.value) { "Fixture root replaced" }
            var current: Path? = path
            while (current != null) {
                if (!(allowLeafLink && current == path)) check(!Files.isSymbolicLink(current)) { "Fixture symlink traversal" }
                current = current.parent
            }
            return path
        }

        private fun stat(path: Path, follow: Boolean): StructStat {
            val values = if (follow) Files.readAttributes(path, "unix:*")
                         else Files.readAttributes(path, "unix:*", NOFOLLOW_LINKS)
            fun number(name: String) = (values.getValue(name) as Number).toLong()
            fun time(name: String): StructTimespec {
                val instant = (values.getValue(name) as FileTime).toInstant()
                return StructTimespec(instant.epochSecond, instant.nano.toLong())
            }
            // NIO has no allocation-block-size contract; these unused fields are not certified.
            return StructStat(number("dev"), number("ino"), number("mode").toInt(), number("nlink"),
                number("uid").toInt(), number("gid").toInt(), number("rdev"), number("size"),
                time("lastAccessTime"), time("lastModifiedTime"), time("ctime"), 0L, 0L)
        }

        private inline fun <T> io(operation: String, action: () -> T): T = try { action() }
        catch (failure: IOException) {
            val errno = when (failure) {
                is java.nio.file.NoSuchFileException -> OsConstants.ENOENT
                is java.nio.file.FileAlreadyExistsException -> OsConstants.EEXIST
                is java.nio.file.AccessDeniedException -> OsConstants.EACCES
                is java.nio.file.NotDirectoryException -> OsConstants.ENOTDIR
                is java.nio.file.FileSystemLoopException -> OsConstants.ELOOP
                else -> OsConstants.EIO
            }
            throw ErrnoException(operation, errno, failure)
        }

        @JvmStatic @Implementation @Synchronized fun lstat(path: String): StructStat =
            io("lstat") { stat(checkedPath(path, allowLeafLink = true), follow = false) }

        @JvmStatic @Implementation @Synchronized fun chmod(path: String, mode: Int) {
            require(mode in 0..511) { "Unsupported permission bits" }
            io("chmod") { Files.setAttribute(checkedPath(path), "unix:mode", mode, NOFOLLOW_LINKS) }
        }

        @JvmStatic @Implementation fun getuid(): Int =
            Files.newBufferedReader(Path.of("/proc/self/status"), UTF_8).use { it.readText() }.lineSequence()
                .first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+"))[0].toInt()

        private fun readChannelWithCloseOnExec(target: Path, flags: Int): FileChannel {
            // FileChannel.open omits CLOEXEC on this pinned JDK. Use the same JDK's
            // native Unix open with all requested flags atomically, never a post-open toggle.
            check(System.getProperty("os.name") == "Linux") { "Linux host fixture required" }
            // Verified against this host's fcntl.h; never reinterpret foreign ABI flag bits.
            check(OsConstants.O_RDONLY == 0 && OsConstants.O_NOFOLLOW == 131072 && OsConstants.O_CLOEXEC == 524288) {
                "Android/host open flag ABI mismatch"
            }
            val dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher")
            val open = dispatcher.getDeclaredMethod("open", Class.forName("sun.nio.fs.UnixPath"),
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val close = dispatcher.getDeclaredMethod("close", Int::class.javaPrimitiveType).apply { isAccessible = true }
            val number = try { open.invoke(null, target, flags, 0) as Int }
            catch (failure: java.lang.reflect.InvocationTargetException) {
                val cause = failure.targetException
                if (cause.javaClass.name != "sun.nio.fs.UnixException") throw cause
                val errno = cause.javaClass.getDeclaredMethod("errno").apply { isAccessible = true }.invoke(cause) as Int
                throw ErrnoException("open", errno, cause)
            }
            var stream: FileInputStream? = null
            try {
                val descriptor = FileDescriptor()
                FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }.setInt(descriptor, number)
                stream = FileInputStream(descriptor)
                return stream.channel
            } catch (failure: Throwable) {
                try {
                    if (stream != null) stream.close() else close.invoke(null, number)
                } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }

        @JvmStatic @Implementation @Synchronized fun open(path: String, flags: Int, mode: Int): FileDescriptor = io("open") {
            val target = checkedPath(path)
            val directoryFlags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
            val readFlags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
            val createFlags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW
            val options = when (flags) {
                directoryFlags -> {
                    check(Files.isDirectory(target, NOFOLLOW_LINKS)) { "O_NONBLOCK fixture opens only directories" }
                    check(mode == 0)
                    setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)
                }
                readFlags -> {
                    check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Fixture read requires regular file" }
                    check(mode == 0)
                    setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)
                }
                createFlags -> {
                    check(mode == 384) { "Fixture create must request 0600" }
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW_LINKS)
                }
                else -> throw ErrnoException("open", OsConstants.EINVAL)
            }
            val channel = if (flags == readFlags) readChannelWithCloseOnExec(target, flags)
                else FileChannel.open(target, options, *if (flags == createFlags)
                    arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray())
            try {
                val field = channel.javaClass.getDeclaredField("fd").apply { isAccessible = true }
                val descriptor = field.get(channel) as FileDescriptor
                val number = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }.getInt(descriptor)
                check(number >= 0 && descriptor.valid())
                if (flags == readFlags) {
                    // Do not silently ignore requested close-on-exec semantics.
                    val info = Files.newBufferedReader(Path.of("/proc/self/fdinfo/$number"), UTF_8).use { it.readText() }
                    val actualFlags = info.lineSequence().first { it.startsWith("flags:") }.substringAfter(':').trim().toLong(8)
                    check(actualFlags and OsConstants.O_CLOEXEC.toLong() != 0L) { "Host channel did not establish O_CLOEXEC" }
                }
                check(!handles.containsKey(descriptor))
                handles[descriptor] = Handle(channel, number)
                descriptor
            } catch (failure: Throwable) {
                try { channel.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }

        private fun handle(fd: FileDescriptor): Handle {
            val result = handles[fd] ?: throw ErrnoException("descriptor", OsConstants.EBADF)
            if (!fd.valid() || !result.channel.isOpen) throw ErrnoException("descriptor", OsConstants.EBADF)
            val number = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }.getInt(fd)
            if (number != result.number) throw ErrnoException("descriptor", OsConstants.EBADF)
            return result
        }

        @JvmStatic @Implementation @Synchronized fun fstat(fd: FileDescriptor): StructStat = io("fstat") {
            // Deliberately FOLLOW the proc descriptor link, not the original pathname.
            stat(Path.of("/proc/self/fd/${handle(fd).number}"), follow = true)
        }
        @JvmStatic @Implementation @Synchronized fun fsync(fd: FileDescriptor) {
            io("fsync") { handle(fd).channel.force(true) }
        }
        @JvmStatic @Implementation @Synchronized fun close(fd: FileDescriptor) {
            val owned = handle(fd)
            handles.remove(fd)
            io("close") { owned.channel.close() }
        }
        @JvmStatic @Implementation fun kill(pid: Int, signal: Int): Unit =
            error("Process signalling is outside the CMake host fixture contract")

        @JvmStatic @Resetter @Synchronized fun reset() {
            var failure: Throwable? = null
            try {
                handles.entries.forEach { (fd, handle) ->
                    try {
                        // A JVM stream can close its shared descriptor. Never close a recycled OS number.
                        if (fd.valid() && handle.channel.isOpen) handle.channel.close()
                    } catch (closeFailure: Throwable) {
                        if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
                    }
                }
            } finally { handles.clear(); roots.clear() }
            failure?.let { throw it }
        }
    }
}

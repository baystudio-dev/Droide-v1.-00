package com.baystudio.droide.core

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

internal class AnchoredWorkspaceFiles(private val root: File) : Closeable {
    data class MoveReceipt(val published: Boolean, val error: Int) {
        val durable: Boolean get() = published && error == 0
    }
    enum class Kind { ABSENT, FILE, DIRECTORY, SYMLINK, OTHER }
    data class Observation(val attributes: BasicFileAttributes, val executable: Boolean, val changed: FileTime)

    class OpenedFile internal constructor(private var descriptor: Int, private val writable: Boolean = false,
                                         private var parentDescriptor: Int = -1) : InputStream() {
        private var metadataLeaseActive = false
        @Synchronized fun observe(): Observation {
            check(descriptor >= 0) { "Workspace input is closed" }
            val path = File("/proc/self/fd/$descriptor").toPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            require(attributes.isRegularFile && attributes.size() >= 0L) { "Workspace input is not an ordinary file" }
            val changed = NativeFilePublication.changedTimeDescriptor(descriptor)
            check(changed.size == 3 && changed[0] == 0L && changed[2] in 0L..999_999_999L) {
                "Workspace file metadata is unavailable; data was preserved"
            }
            val nanos = Math.addExact(Math.multiplyExact(changed[1], 1_000_000_000L), changed[2])
            return Observation(attributes, PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(path),
                FileTime.from(nanos, TimeUnit.NANOSECONDS))
        }

        @Synchronized fun permissionMode(): Int {
            check(descriptor >= 0) { "Workspace input is closed" }
            val mode = Files.getAttribute(File("/proc/self/fd/$descriptor").toPath(), "unix:mode") as Number
            return mode.toInt() and 4095
        }

        /** Reads only through this pinned descriptor; never follows a caller-supplied path. */
        @Synchronized fun openReadChannel(): FileChannel {
            check(descriptor >= 0) { "Workspace input is closed" }
            val before = observe()
            val channel = FileChannel.open(File("/proc/self/fd/$descriptor").toPath(), READ)
            try {
                check(observe().attributes.fileKey() == before.attributes.fileKey() && channel.position() == 0L)
                return channel
            } catch (failure: Throwable) {
                try { channel.close() } catch (close: Exception) { failure.addSuppressed(close) }
                throw failure
            }
        }

        @Synchronized fun linkCount(): Long {
            check(descriptor >= 0) { "Workspace input is closed" }
            return (Files.getAttribute(File("/proc/self/fd/$descriptor").toPath(), "unix:nlink") as Number).toLong()
        }

        @Synchronized fun openWriteChannel(): FileChannel {
            check(descriptor >= 0 && writable) { "Workspace output is closed or read-only" }
            val before = observe()
            // Follow only this kernel descriptor alias while the original descriptor is pinned.
            val channel = FileChannel.open(File("/proc/self/fd/$descriptor").toPath(), READ, WRITE)
            try {
                check(observe().attributes.fileKey() == before.attributes.fileKey() && channel.position() == 0L) {
                    "Workspace output descriptor changed while opening its channel"
                }
                return channel
            } catch (failure: Throwable) {
                try { channel.close() } catch (close: Exception) { failure.addSuppressed(close) }
                throw failure
            }
        }

        @Synchronized override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(descriptor >= 0) { "Workspace input is closed" }
            require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset)
            val count = NativeFilePublication.readDescriptor(descriptor, bytes, offset, length)
            if (count < -1) throw java.io.IOException("Workspace input failed (errno=${-count - 1}); data was preserved")
            return count
        }

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        }

        @Synchronized fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            check(descriptor >= 0 && writable) { "Workspace output is closed or read-only" }
            require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset)
            var written = 0
            while (written < length) {
                val count = NativeFilePublication.writeDescriptor(descriptor, bytes, offset + written, length - written)
                if (count <= 0) throw java.io.IOException("Workspace output failed (errno=${if (count < 0) -count - 1 else 5}); data was preserved")
                written += count
            }
        }

        /** A dedicated metadata lock lease. */

        interface MetadataLease : Closeable {
            fun requireHeld()
        }

        /** Export-only descriptor-scoped ownership. Closing an unrelated alias of this inode cannot
         * release this flock; do not substitute it for existing POSIX database leases. */
        @Synchronized fun acquireExclusiveExportLease(): MetadataLease {
            check(descriptor >= 0 && !metadataLeaseActive) { "Export descriptor already leased or closed" }
            val error = NativeFilePublication.exportDescriptorLock(descriptor, true)
            check(error == 0) { "Export metadata is owned elsewhere or locking is unavailable (errno=$error)" }
            metadataLeaseActive = true
            return object : MetadataLease {
                private var closed = false
                override fun requireHeld() = synchronized(this@OpenedFile) {
                    check(!closed && metadataLeaseActive && descriptor >= 0) { "Export ownership lease is closed" }
                }
                override fun close() = synchronized(this@OpenedFile) {
                    if (closed) return@synchronized
                    closed = true
                    // Closing the owning descriptor in the caller's finally also releases flock.
                    try {
                        val unlock = NativeFilePublication.exportDescriptorLock(descriptor, false)
                        check(unlock == 0) { "Export metadata unlock failed (errno=$unlock)" }
                    } finally { metadataLeaseActive = false }
                }
            }
        }

        @Synchronized fun acquireExclusiveMetadataLease(): MetadataLease {
            check(descriptor >= 0 && !metadataLeaseActive) { "Metadata descriptor already has a lease or is closed" }
            val channel = FileChannel.open(File("/proc/self/fd/$descriptor").toPath(), READ, WRITE)
            try {
                val lock = checkNotNull(channel.tryLock()) { "Metadata authority is busy in another runtime" }
                metadataLeaseActive = true
                return object : MetadataLease {
                    private var closed = false
                    override fun requireHeld() = synchronized(this@OpenedFile) {
                        check(!closed && metadataLeaseActive && descriptor >= 0 && lock.isValid && channel.isOpen) {
                            "Metadata authority lease is no longer held"
                        }
                    }
                    override fun close() = synchronized(this@OpenedFile) {
                        if (closed) return@synchronized
                        closed = true
                        try { lock.release() } finally {
                            try { channel.close() } finally { metadataLeaseActive = false }
                        }
                    }
                }
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }

        /** Only for a dedicated private metadata lock inode, never a SQLite/user file. */

        @Synchronized fun <T> withExclusiveMetadataLock(action: (() -> Unit) -> T): T {
            check(descriptor >= 0 && !metadataLeaseActive) { "Metadata lock descriptor is closed or already leased" }
            val before = observe()
            FileChannel.open(File("/proc/self/fd/$descriptor").toPath(), READ, WRITE).use { channel ->
                val held = try { channel.tryLock() }
                catch (failure: java.nio.channels.OverlappingFileLockException) {
                    throw IllegalStateException("Metadata lock is already held in this runtime", failure)
                }
                checkNotNull(held) { "Synchronization metadata is busy in another runtime; retry after it finishes" }
                held.use { lock ->
                    fun requireHeld() {
                        check(lock.isValid && channel.isOpen && observe().attributes.fileKey() == before.attributes.fileKey()) {
                            "Metadata lock ownership changed; existing data was preserved"
                        }
                    }
                    requireHeld()
                    return action(::requireHeld).also { requireHeld() }
                }
            }
        }

        @Synchronized fun force(mode: Int) {
            check(descriptor >= 0 && writable) { "Workspace output is closed or read-only" }
            val error = NativeFilePublication.forceFileDescriptor(descriptor, mode)
            check(error == 0) { "Workspace output durability could not be confirmed (errno=$error)" }
            check(parentDescriptor >= 0) { "Workspace output parent capability is missing" }
            val directoryError = NativeFilePublication.forceDirectory(parentDescriptor)
            check(directoryError == 0) { "Workspace output namespace durability could not be confirmed (errno=$directoryError)" }
        }

        @Synchronized fun forceContents() {
            check(descriptor >= 0) { "Workspace input is closed" }
            val error = NativeFilePublication.forceFileDescriptor(descriptor, -1)
            check(error == 0) { "Workspace file durability could not be confirmed (errno=$error)" }
        }

        @Synchronized override fun close() {
            check(!metadataLeaseActive) { "Release the metadata lease before closing its pinned descriptor" }
            if (descriptor < 0) return
            val owned = descriptor
            val parent = parentDescriptor
            descriptor = -1
            parentDescriptor = -1
            val error = NativeFilePublication.closeDescriptor(owned)
            val parentError = if (parent >= 0) NativeFilePublication.closeDescriptor(parent) else 0
            check(error == 0 && parentError == 0) { "Workspace file close failed (errno=$error, parent=$parentError); descriptors were not reused" }
        }
    }
    private val identity = WorkspaceDirectoryIdentity(root)
    private var descriptor = -1

    init {
        identity.requireCurrent()
        val expected = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        val opened = native("open root") { NativeFilePublication.openDirectoryRoot(root.canonicalPath.toByteArray(Charsets.UTF_8)) }
        requireDescriptor(opened)
        try {
            val observed = Files.readAttributes(File("/proc/self/fd/$opened").toPath(), BasicFileAttributes::class.java)
            check(observed.isDirectory && observed.fileKey() == expected) { "Workspace identity changed while opening it; data was preserved" }
            identity.requireCurrent()
            descriptor = opened
        } finally { if (descriptor < 0) NativeFilePublication.closeDescriptor(opened) }
    }

    @Synchronized private fun borrow(): Int {
        check(descriptor >= 0) { "Workspace directory capability is closed" }
        identity.requireCurrent()
        return native("duplicate root") { NativeFilePublication.duplicateDescriptor(descriptor) }.also(::requireDescriptor)
    }

    fun openRead(relative: String): OpenedFile {
        val path = relativeBytes(relative)
        val rootCopy = borrow()
        try {
            val file = native("open file") { NativeFilePublication.openOrdinaryRelative(rootCopy, path) }
            requireDescriptor(file)
            return OpenedFile(file)
        } finally { NativeFilePublication.closeDescriptor(rootCopy) }
    }

    fun createNew(relative: String): OpenedFile {
        val path = relativeBytes(relative)
        val rootCopy = borrow()
        try {
            val opened = native("create new file") { NativeFilePublication.createOrdinaryRelative(rootCopy, path) }
            if (opened == -17L) throw FileAlreadyExistsException(relative, null, "Existing workspace data was preserved")
            if (opened < 0) throw FileSystemException(relative, null, "Cannot create a confined file (errno=${-opened}); data was preserved")
            return OpenedFile((opened and 0xffffffffL).toInt(), writable = true, parentDescriptor = (opened ushr 32).toInt())
        } finally { NativeFilePublication.closeDescriptor(rootCopy) }
    }

    fun createDirectory(relative: String): MoveReceipt {
        val path = relativeBytes(relative)
        val rootCopy = borrow()
        try {
            val receipt = native("create new directory") { NativeFilePublication.createDirectoryRelative(rootCopy, path) }
            return MoveReceipt(receipt and (1L shl 32) != 0L, (receipt and 0xffffffffL).toInt())
        } finally { NativeFilePublication.closeDescriptor(rootCopy) }
    }

    fun directoryKey(relative: String = ""): String {
        val rootCopy = borrow()
        var opened = -1
        try {
            opened = if (relative.isEmpty()) rootCopy else native("inspect directory identity") {
                NativeFilePublication.openDirectoryRelative(rootCopy, relativeBytes(relative))
            }
            requireDescriptor(opened)
            val attributes = Files.readAttributes(File("/proc/self/fd/$opened").toPath(), BasicFileAttributes::class.java)
            check(attributes.isDirectory) { "Workspace entry is not a directory" }
            return checkNotNull(attributes.fileKey()).toString()
        } finally {
            if (opened >= 0 && opened != rootCopy) NativeFilePublication.closeDescriptor(opened)
            NativeFilePublication.closeDescriptor(rootCopy)
        }
    }

    fun openDirectory(relative: String = ""): AnchoredWorkspaceFiles {
        val key = directoryKey(relative)
        val child = AnchoredWorkspaceFiles(if (relative.isEmpty()) root else File(root, relative))
        try {
            check(child.directoryKey() == key && directoryKey(relative) == key) {
                "Workspace directory changed while opening it; data was preserved"
            }
            return child
        } catch (failure: Throwable) { child.close(); throw failure }
    }

    fun <T> read(relative: String, block: (InputStream, Observation) -> T): T =
        openRead(relative).use { block(it, it.observe()) }

    fun kind(relative: String): Kind {
        val path = relativeBytes(relative)
        val rootCopy = borrow()
        try {
            return when (val kind = native("inspect entry") { NativeFilePublication.kindRelative(rootCopy, path) }) {
                -2 -> Kind.ABSENT
                1 -> Kind.FILE
                2 -> Kind.DIRECTORY
                3 -> Kind.SYMLINK
                4 -> Kind.OTHER
                else -> throw FileSystemException(relative, null, "Cannot inspect workspace entry (errno=${-kind}); data was preserved")
            }
        } finally { NativeFilePublication.closeDescriptor(rootCopy) }
    }

    fun forEachChild(relative: String = "", block: (String) -> Unit) {
        val rootCopy = borrow()
        var opened = -1
        try {
            opened = if (relative.isEmpty()) rootCopy else native("open directory") {
                NativeFilePublication.openDirectoryRelative(rootCopy, relativeBytes(relative))
            }
            requireDescriptor(opened)
            Files.newDirectoryStream(File("/proc/self/fd/$opened").toPath()).use { children ->
                for (child in children) {
                    val name = child.fileName.toString()
                    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
                        "Workspace contains an unsupported filename; data was preserved"
                    }
                    block(name)
                }
            }
        } finally {
            if (opened >= 0 && opened != rootCopy) NativeFilePublication.closeDescriptor(opened)
            NativeFilePublication.closeDescriptor(rootCopy)
        }
    }

    fun moveNoReplace(relative: String, target: AnchoredWorkspaceFiles, destination: String) {
        val sourceBytes = relativeBytes(relative)
        val targetBytes = target.relativeBytes(destination)
        val sourceRoot = borrow()
        var targetRoot = -1
        try {
            targetRoot = target.borrow()
            val error = native("move without replacement") {
                NativeFilePublication.moveNoReplaceAnchored(sourceRoot, sourceBytes, targetRoot, targetBytes)
            }
            when (error) {
                0 -> Unit
                17 -> throw FileAlreadyExistsException(destination, null, "Existing destination data was preserved")
                else -> throw FileSystemException(relative, destination, "Anchored move failed (errno=$error); no replacement fallback was used")
            }
        } finally {
            if (targetRoot >= 0) NativeFilePublication.closeDescriptor(targetRoot)
            NativeFilePublication.closeDescriptor(sourceRoot)
        }
    }

    fun moveNoReplaceDurable(relative: String, target: AnchoredWorkspaceFiles, destination: String): MoveReceipt {
        val sourceBytes = relativeBytes(relative)
        val targetBytes = target.relativeBytes(destination)
        val sourceRoot = borrow()
        var targetRoot = -1
        try {
            targetRoot = target.borrow()
            val receipt = native("durable move without replacement") {
                NativeFilePublication.moveNoReplaceAnchoredDurable(sourceRoot, sourceBytes, targetRoot, targetBytes)
            }
            return MoveReceipt(receipt and (1L shl 32) != 0L, (receipt and 0xffffffffL).toInt())
        } finally {
            if (targetRoot >= 0) NativeFilePublication.closeDescriptor(targetRoot)
            NativeFilePublication.closeDescriptor(sourceRoot)
        }
    }

    /** Remove only caller-validated redundant private metadata. */

    fun deleteRedundantMetadata(name: String, expectedKey: Any): Boolean {
        require('/' !in name)
        relativeBytes(name)
        val rootCopy = borrow()
        try {
            Files.newDirectoryStream(File("/proc/self/fd/$rootCopy").toPath()).use { stream ->
                val pinned = stream as? java.nio.file.SecureDirectoryStream<java.nio.file.Path>
                    ?: return false
                val relative = java.nio.file.Paths.get(name)
                val attributes = checkNotNull(pinned.getFileAttributeView(relative,
                    java.nio.file.attribute.BasicFileAttributeView::class.java, NOFOLLOW_LINKS)).readAttributes()
                check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.fileKey() == expectedKey) {
                    "Redundant metadata identity changed; evidence was preserved"
                }
                pinned.deleteFile(relative)
            }
            val error = NativeFilePublication.forceDirectory(rootCopy)
            check(error == 0) { "Redundant metadata removal durability is unconfirmed (errno=$error)" }
            return true
        } finally { NativeFilePublication.closeDescriptor(rootCopy) }
    }

    fun forceDirectory() {
        val fd = borrow()
        try {
            val error = native("force directory") { NativeFilePublication.forceDirectory(fd) }
            check(error == 0) { "Workspace directory durability could not be confirmed (errno=$error)" }
        } finally { NativeFilePublication.closeDescriptor(fd) }
    }

    @Synchronized override fun close() {
        if (descriptor < 0) return
        val owned = descriptor
        descriptor = -1
        val error = NativeFilePublication.closeDescriptor(owned)
        check(error == 0) { "Workspace directory descriptor close failed (errno=$error); it was not reused" }
    }

    private fun relativeBytes(relative: String): ByteArray {
        require(relative.isNotEmpty() && !relative.startsWith('/') && '\u0000' !in relative && '\\' !in relative &&
            relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Expected a confined relative file path" }
        return relative.toByteArray(Charsets.UTF_8)
    }

    private fun requireDescriptor(value: Int) {
        if (value < 0) throw FileSystemException(root.path, null, "Cannot open a confined file descriptor (errno=${-value})")
    }

    private inline fun <T> native(operation: String, action: () -> T): T = try { action() }
    catch (failure: LinkageError) {
        throw FileSystemException(root.path, null, "Native workspace operation unavailable: $operation; data was preserved")
            .also { it.initCause(failure) }
    }
}

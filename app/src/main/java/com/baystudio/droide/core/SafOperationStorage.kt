package com.baystudio.droide.core

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SafStoredFile(val key: String, val bytes: Long, val sha256: String)

internal class SafFrameReadCounter(val file: File) {
    var reads: Long = 0; internal set
    var bytes: Long = 0; internal set
    var largestAllocation: Int = 0; internal set
    fun reset() { reads = 0; bytes = 0; largestAllocation = 0 }
}

/** SAF-only storage primitives. Tests inject force/move faults; no provider call is made here. */
internal class SafOperationStorage(
    private val forceDirectory: (File) -> Unit = { directory ->
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "SAF durability target is not a directory" }
            Os.fsync(fd)
        } finally { Os.close(fd) }
    },
    private val forceFile: (File) -> Unit = { file ->
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    },
    private val replaceAtomically: (File, File) -> Unit = { source, destination ->
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
    private val beforeRead: (File) -> Unit = {},
    private val removeOwned: (File) -> Unit = { Files.delete(it.toPath()) },
    private val frameReadCounter: SafFrameReadCounter? = null,
) {
    private fun countedRead(file: File) {
        frameReadCounter?.takeIf { it.file == file }?.let { it.reads = Math.addExact(it.reads, 1) }
    }
    private fun countedBytes(file: File, bytes: Int) {
        frameReadCounter?.takeIf { it.file == file }?.let { it.bytes = Math.addExact(it.bytes, bytes.toLong()) }
    }
    private fun countedChunk(file: File, input: InputStream, buffer: ByteArray, poll: () -> Unit): Int {
        repeat(32) {
            poll()
            val count = input.read(buffer)
            if (count > 0) countedBytes(file, count)
            poll()
            if (count != 0) return count
        }
        error("SAF input made no progress within its read limit")
    }
    fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    fun key(file: File, directory: Boolean): String {
        val attrs = requireNotNull(attributes(file)) { "Missing SAF evidence: ${file.name}" }
        require(if (directory) attrs.isDirectory else attrs.isRegularFile) { "Unexpected SAF evidence type: ${file.name}" }
        return requireNotNull(attrs.fileKey()) { "Stable SAF filesystem identity unavailable" }.toString()
    }

    fun requireRealAncestors(file: File) {
        var path = file.absoluteFile.toPath().normalize()
        while (true) {
            val attrs = attributes(path.toFile())
            if (attrs != null) require(!attrs.isSymbolicLink) { "SAF evidence must not traverse a symbolic link" }
            path = path.parent ?: break
        }
    }

    fun createDirectory(directory: File) {
        requireRealAncestors(directory)
        Files.createDirectory(directory.toPath())
        syncDirectory(directory)
        syncDirectory(requireNotNull(directory.parentFile))
    }

    fun syncDirectory(directory: File) {
        val before = key(directory, true)
        forceDirectory(directory)
        check(key(directory, true) == before) { "SAF directory identity changed during force" }
    }

    fun syncFile(file: File) {
        val before = key(file, false)
        forceFile(file)
        check(key(file, false) == before) { "SAF file identity changed during force" }
    }

    fun writeNew(file: File, bytes: ByteArray, checkActive: () -> Unit = {}): SafStoredFile {
        checkActive()
        requireRealAncestors(file)
        var createdKey: String? = null
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            createdKey = key(file, false)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                checkActive()
                check(channel.write(buffer) > 0) { "SAF metadata write made no progress" }
            }
        }
        syncFile(file)
        syncDirectory(requireNotNull(file.parentFile))
        return describe(file, bytes.size.toLong(), checkActive).also {
            check(it.key == createdKey && it.bytes == bytes.size.toLong() && it.sha256 == bytes.safDigest(checkActive)) {
                "SAF evidence differs from its created identity or planned bytes"
            }
        }
    }

    /** One exclusive, exactly priced frame; only the caller's bounded chunks are buffered. */
    internal fun writeFrame(file: File, expectedBytes: Long, checkActive: () -> Unit,
        write: ((ByteArray) -> Unit) -> Unit): SafStoredFile {
        require(expectedBytes >= 0)
        checkActive()
        requireRealAncestors(file)
        var createdKey: String? = null
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            createdKey = key(file, false)
            write { bytes ->
                check(bytes.size.toLong() <= expectedBytes - written) { "SAF frame exceeded its exact price" }
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    checkActive()
                    check(channel.write(buffer) > 0) { "SAF frame write made no progress" }
                }
                digest.update(bytes)
                written += bytes.size
            }
            check(written == expectedBytes) { "Incomplete SAF frame retained" }
        }
        syncFile(file)
        syncDirectory(requireNotNull(file.parentFile))
        return describe(file, expectedBytes, checkActive).also {
            check(it.key == createdKey && it.bytes == written && it.sha256 == digest.digest().safHex())
        }
    }

    internal fun <T> readFrame(file: File, maximum: Long, checkActive: () -> Unit,
        decode: (FrameReader) -> T): Pair<T, SafStoredFile> {
        checkActive()
        beforeRead(file)
        countedRead(file)
        require(maximum >= 0)
        requireRealAncestors(file)
        val before = key(file, false)
        val size = requireNotNull(attributes(file)).size()
        require(size in 0..maximum) { "SAF frame exceeds its capacity" }
        val digest = MessageDigest.getInstance("SHA-256")
        val value = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val reader = FrameReader(input, size, digest, checkActive,
                frameReadCounter?.takeIf { it.file == file })
            decode(reader).also { check(reader.remaining == 0L) { "Trailing SAF frame bytes retained" } }
        }
        check(key(file, false) == before && attributes(file)?.size() == size)
        return value to SafStoredFile(before, size, digest.digest().safHex())
    }

    internal class FrameReader(private val input: InputStream, size: Long,
        private val digest: MessageDigest, private val poll: () -> Unit,
        private val counter: SafFrameReadCounter? = null) {
        var remaining: Long = size
            private set
        /** Every allocation has its own explicit bound: header, control, image or one section. */
        fun readExact(length: Long, maximum: Long): ByteArray {
            require(length in 0..minOf(maximum, Int.MAX_VALUE.toLong()) && length <= remaining)
            val result = ByteArray(length.toInt())
            counter?.let { it.largestAllocation = maxOf(it.largestAllocation, result.size) }
            var offset = 0
            var empty = 0
            while (offset < result.size) {
                poll()
                val count = input.read(result, offset, minOf(64 * 1024, result.size - offset))
                if (count > 0) counter?.let { it.bytes = Math.addExact(it.bytes, count.toLong()) }
                poll()
                check(count >= 0) { "Truncated SAF frame retained" }
                if (count == 0) { check(++empty < 32) { "SAF frame read made no progress" }; continue }
                empty = 0
                digest.update(result, offset, count)
                offset += count
                remaining -= count
            }
            return result
        }
    }

    fun copyNew(source: File, destination: File, maximum: Long, checkActive: () -> Unit): SafStoredFile {
        requireRealAncestors(source)
        val original = describe(source, maximum, checkActive)
        val copied = copyNew(Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS), destination, maximum, checkActive)
        check(describe(source, maximum, checkActive) == original) { "SAF source changed while preserving evidence" }
        check(copied.bytes == original.bytes && copied.sha256 == original.sha256) { "SAF evidence copy is incomplete" }
        return copied
    }

    /** Consumes/closes the input; close or force failure retains the private file without a receipt. */
    fun copyNew(input: InputStream, destination: File, maximum: Long, checkActive: () -> Unit): SafStoredFile {
        var copied = 0L
        var createdKey: String? = null
        val digest = MessageDigest.getInstance("SHA-256")
        input.use {
            require(maximum >= 0)
            checkActive()
            requireRealAncestors(destination)
            FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { output ->
                createdKey = key(destination, false)
                val bytes = ByteArray(64 * 1024)
                var bytesSinceSpaceCheck = 16L * 1024 * 1024
                while (true) {
                    val count = readSafChunk(input, bytes, checkActive)
                    check(count in -1..bytes.size) { "Invalid SAF input count" }
                    if (count < 0) break
                    check(count.toLong() <= maximum - copied) { "SAF evidence capacity exceeded" }
                    bytesSinceSpaceCheck += count
                    if (bytesSinceSpaceCheck >= 16L * 1024 * 1024) {
                        WorkspaceTransferSpace.requireAvailable(requireNotNull(destination.parentFile),
                            count.toLong(), 192L * 1024 * 1024)
                        bytesSinceSpaceCheck = 0L
                    }
                    val buffer = ByteBuffer.wrap(bytes, 0, count)
                    while (buffer.hasRemaining()) {
                        checkActive()
                        check(output.write(buffer) > 0) { "SAF payload write made no progress" }
                    }
                    digest.update(bytes, 0, count)
                    copied += count
                }
            }
        }
        syncFile(destination)
        syncDirectory(requireNotNull(destination.parentFile))
        return describe(destination, maximum, checkActive).also {
            check(it.key == createdKey && it.bytes == copied && it.sha256 == digest.digest().safHex()) {
                "SAF evidence copy is incomplete or replaced"
            }
        }
    }

    fun read(file: File, maximum: Long, checkActive: () -> Unit = {}): ByteArray {
        checkActive()
        beforeRead(file)
        countedRead(file)
        require(maximum in 0..Int.MAX_VALUE.toLong())
        requireRealAncestors(file)
        val before = key(file, false)
        require(requireNotNull(attributes(file)).size() <= maximum) { "SAF metadata capacity exceeded; evidence retained" }
        val output = ByteArrayOutputStream()
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = countedChunk(file, input, buffer, checkActive)
                if (count < 0) break
                check(count.toLong() <= maximum - output.size()) { "SAF metadata grew beyond its capacity" }
                output.write(buffer, 0, count)
            }
        }
        check(key(file, false) == before && attributes(file)?.size() == output.size().toLong()) {
            "SAF metadata identity or size changed during read"
        }
        return output.toByteArray()
    }

    fun describe(file: File, maximum: Long, checkActive: () -> Unit = {}): SafStoredFile {
        checkActive()
        beforeRead(file)
        countedRead(file)
        require(maximum >= 0L)
        requireRealAncestors(file)
        val before = key(file, false)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = countedChunk(file, input, buffer, checkActive)
                if (count < 0) break
                check(count.toLong() <= maximum - bytes) { "SAF payload exceeds its capacity" }
                bytes += count
                digest.update(buffer, 0, count)
            }
        }
        check(key(file, false) == before && attributes(file)?.size() == bytes) { "SAF payload identity or size changed" }
        return SafStoredFile(before, bytes, digest.digest().safHex())
    }

    fun replace(source: File, target: File) {
        requireRealAncestors(source)
        requireRealAncestors(target)
        replaceAtomically(source, target) // No non-atomic fallback.
    }

    /** One already inventoried node, never a recursive walk or a missing/replaced-name adoption. */
    internal fun removeExact(file: File, expectedKey: String, kind: SafLocalKind) {
        requireRealAncestors(requireNotNull(file.parentFile))
        val actual = requireNotNull(attributes(file))
        check(actual.fileKey()?.toString() == expectedKey && when (kind) {
            SafLocalKind.FILE -> actual.isRegularFile
            SafLocalKind.DIRECTORY -> actual.isDirectory
            SafLocalKind.SYMLINK -> actual.isSymbolicLink
        }) { "SAF retirement node changed; evidence retained" }
        removeOwned(file)
        syncDirectory(requireNotNull(file.parentFile))
    }
}

internal object SafJournalCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val value = json.decodeFromString(serializer, text)
        // Our new format is canonical. Duplicate/missing/defaulted fields cannot gain authority.
        require(encode(serializer, value).contentEquals(bytes)) { "Noncanonical or malformed SAF evidence was retained" }
        return value
    }
}

internal fun ByteArray.safDigest(checkActive: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var offset = 0
    while (offset < size) {
        checkActive()
        val count = minOf(64 * 1024, size - offset)
        digest.update(this, offset, count)
        offset += count
    }
    checkActive()
    return digest.digest().safHex()
}
private fun ByteArray.safHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

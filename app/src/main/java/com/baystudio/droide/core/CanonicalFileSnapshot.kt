package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

// Dormant custody primitive: never publishes, retires, restores, or deletes workspace entries.
internal class CanonicalFileSnapshot(
    private val maxBytes: Long,
    private val checkpoint: (Boundary, Long) -> Unit = { _, _ -> },
) {
    enum class Boundary { OPENED, CREATED, COPIED_BLOCK, COPIED, FORCED, VERIFIED }
    data class Version(val identity: String, val size: Long, val sha256: String, val mode: Int)
    class VerifiedBytes internal constructor(val version: Version, bytes: ByteArray) {
        private val content = bytes.copyOf()
        fun openStream(): InputStream = ByteArrayInputStream(content)
    }
    // A verified observation, not an immutable snapshot or permission to publish/delete later.
    data class Capture(val source: Version, val copy: Version, val sourceParent: String,
                       val copyParent: String, val sourceName: String, val copyName: String)
    private data class Stamp(val identity: String, val size: Long, val modified: FileTime,
                             val changed: FileTime, val mode: Int)

    init { require(maxBytes >= 0L) }

    fun inspect(parent: AnchoredWorkspaceFiles, name: String, context: CoroutineContext): Version {
        requireLeaf(name)
        context.ensureActive()
        return parent.openRead(name).use { input ->
            val before = stamp(input)
            require(before.size <= maxBytes) { "File exceeds snapshot budget; no data was changed" }
            val hash = copyBytes(input, null, before.size, context)
            requireUnchanged(before, stamp(input))
            requireNamed(parent, name, before)
            context.ensureActive()
            Version(before.identity, before.size, hash, before.mode)
        }
    }

    // Owns bounded bytes from the reviewed version; it never authorizes a later filesystem mutation.
    fun readVerifiedBytes(parent: AnchoredWorkspaceFiles, name: String, expected: Version,
                          context: CoroutineContext): VerifiedBytes {
        requireLeaf(name)
        require(expected.size in 0..minOf(maxBytes, MAX_READ_BYTES.toLong())) {
            "Recovery file exceeds the in-memory read limit; retained data was preserved"
        }
        require(expected.sha256.matches(Regex("[0-9a-f]{64}")))
        context.ensureActive()
        val parentKey = parent.directoryKey()
        return parent.openRead(name).use { input ->
            val before = stamp(input)
            check(before.identity == expected.identity && before.size == expected.size && before.mode == expected.mode) {
                "Recovery file identity or metadata changed; review it again"
            }
            checkpoint(Boundary.OPENED, 0)
            context.ensureActive()
            requireNamed(parent, name, before)
            val bytes = ByteArray(before.size.toInt())
            var offset = 0
            val hash = copyBytes(input, { block, count ->
                block.copyInto(bytes, offset, 0, count)
                offset += count
            }, before.size, context)
            check(hash == expected.sha256) { "Recovery file content changed; review it again" }
            checkpoint(Boundary.COPIED, before.size)
            fun verify() {
                context.ensureActive()
                requireUnchanged(before, stamp(input))
                requireNamed(parent, name, before)
                check(parent.directoryKey() == parentKey) { "Recovery file parent changed" }
            }
            verify()
            val result = VerifiedBytes(expected, bytes)
            checkpoint(Boundary.VERIFIED, before.size)
            verify()
            result
        }
    }

    // Forces only the pinned, verified inode; changed content or name binding refuses recovery.
    fun forceVerified(parent: AnchoredWorkspaceFiles, name: String, expected: Version, context: CoroutineContext): Version {
        requireLeaf(name)
        require(expected.size in 0..maxBytes)
        context.ensureActive()
        return parent.openRead(name).use { input ->
            val before = stamp(input)
            check(before.identity == expected.identity && before.size == expected.size && before.mode == expected.mode) {
                "Recovery file identity or metadata changed"
            }
            checkpoint(Boundary.OPENED, 0)
            val hash = copyBytes(input, null, before.size, context)
            check(hash == expected.sha256) { "Recovery file content changed" }
            requireUnchanged(before, stamp(input)); requireNamed(parent, name, before)
            checkpoint(Boundary.COPIED, before.size)
            context.ensureActive(); input.forceContents()
            checkpoint(Boundary.FORCED, before.size)
            requireUnchanged(before, stamp(input)); requireNamed(parent, name, before)
            context.ensureActive()
            expected
        }
    }

    fun capture(source: AnchoredWorkspaceFiles, name: String, expected: Version,
                custody: AnchoredWorkspaceFiles, copyName: String, context: CoroutineContext): Capture {
        requireLeaf(name)
        requireLeaf(copyName)
        require(expected.size in 0..maxBytes && expected.sha256.matches(Regex("[0-9a-f]{64}")))
        require(expected.mode in listOf(384, 420, 493)) {
            "Snapshot permissions are unsupported; the original file was not changed"
        }
        context.ensureActive()
        val sourceParent = source.directoryKey()
        val copyParent = custody.directoryKey()
        return source.openRead(name).use { input ->
            val before = stamp(input)
            check(before.identity == expected.identity && before.size == expected.size && before.mode == expected.mode) {
                "Source identity or metadata changed; snapshot admission stopped"
            }
            checkpoint(Boundary.OPENED, 0)
            context.ensureActive()
            requireNamed(source, name, before)
            custody.createNew(copyName).use { output ->
                val createdIdentity = stamp(output).identity
                check(createdIdentity != expected.identity) { "Snapshot must have an independent inode" }
                checkpoint(Boundary.CREATED, 0)
                val hash = copyBytes(input, { bytes, count -> output.write(bytes, 0, count) }, expected.size, context)
                check(hash == expected.sha256) { "Source content changed; original and incomplete snapshot were preserved" }
                checkpoint(Boundary.COPIED, expected.size)
                requireUnchanged(before, stamp(input))
                requireNamed(source, name, before)
                context.ensureActive()
                output.force(expected.mode)
                val forced = stamp(output)
                checkpoint(Boundary.FORCED, expected.size)
                val actual = inspect(custody, copyName, context)
                check(actual == expected.copy(identity = createdIdentity)) {
                    "Snapshot identity or content changed; all named versions were preserved"
                }
                checkpoint(Boundary.VERIFIED, expected.size)
                context.ensureActive()
                requireUnchanged(forced, stamp(output))
                requireNamed(custody, copyName, forced)
                requireUnchanged(before, stamp(input))
                requireNamed(source, name, before)
                check(source.directoryKey() == sourceParent && custody.directoryKey() == copyParent) {
                    "Snapshot parent changed; recovery must inspect preserved versions"
                }
                context.ensureActive()
                Capture(expected, actual, sourceParent, copyParent, name, copyName)
            }
        }
    }

    // Produced bytes become retained custody only after exclusive creation, force and verification.
    fun captureBytes(custody: AnchoredWorkspaceFiles, name: String, bytes: ByteArray,
                     expectedHash: String, mode: Int, context: CoroutineContext): Version {
        requireLeaf(name)
        require(bytes.size.toLong() <= maxBytes && expectedHash.matches(Regex("[0-9a-f]{64}")))
        require(mode in listOf(384, 420, 493)) { "Snapshot permissions are unsupported" }
        context.ensureActive()
        val owned = bytes.copyOf()
        val size = owned.size.toLong()
        val hash = ByteArrayInputStream(owned).use { copyBytes(it, null, size, context) }
        check(hash == expectedHash) { "Produced proposal differs from its admitted content; no file was created" }
        val parentKey = custody.directoryKey()
        context.ensureActive()
        return custody.createNew(name).use { output ->
            val expected = Version(stamp(output).identity, size, expectedHash, mode)
            checkpoint(Boundary.CREATED, 0)
            ByteArrayInputStream(owned).use {
                check(copyBytes(it, { bytes, count -> output.write(bytes, 0, count) }, size, context) == expectedHash) {
                    "Produced proposal changed during capture"
                }
            }
            checkpoint(Boundary.COPIED, size)
            context.ensureActive(); output.force(mode)
            val forced = stamp(output)
            checkpoint(Boundary.FORCED, size)
            check(inspect(custody, name, context) == expected) {
                "Produced proposal identity or content changed; all named versions were preserved"
            }
            checkpoint(Boundary.VERIFIED, size)
            context.ensureActive()
            requireUnchanged(forced, stamp(output)); requireNamed(custody, name, forced)
            check(custody.directoryKey() == parentKey) { "Proposal parent changed; retained data requires review" }
            expected
        }
    }

    private fun copyBytes(input: InputStream, output: ((ByteArray, Int) -> Unit)?,
                          size: Long, context: CoroutineContext): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            context.ensureActive()
            val count = input.read(bytes)
            if (count < 0) break
            check(count.toLong() <= size - total) { "File grew during snapshot; all named data was preserved" }
            output?.invoke(bytes, count)
            digest.update(bytes, 0, count)
            total += count
            if (output != null) checkpoint(Boundary.COPIED_BLOCK, total)
        }
        context.ensureActive()
        check(total == size) { "File shrank during snapshot; all named data was preserved" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun stamp(file: AnchoredWorkspaceFiles.OpenedFile): Stamp {
        fun observed(mode: Int): Stamp {
            val value = file.observe()
            return Stamp(checkNotNull(value.attributes.fileKey()).toString(), value.attributes.size(),
                value.attributes.lastModifiedTime(), value.changed, mode)
        }
        val before = observed(0)
        val mode = file.permissionMode()
        requireUnchanged(before, observed(0))
        return before.copy(mode = mode)
    }

    private fun requireNamed(parent: AnchoredWorkspaceFiles, name: String, expected: Stamp) {
        parent.openRead(name).use { requireUnchanged(expected, stamp(it)) }
    }

    private fun requireUnchanged(before: Stamp, after: Stamp) {
        check(before == after) { "File changed during snapshot; no version was discarded" }
    }

    private fun requireLeaf(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "Snapshot requires a single leaf in an already pinned parent directory"
        }
    }

    companion object {
        private const val MAX_READ_BYTES = 4 * 1024 * 1024
    }
}

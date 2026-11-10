package com.baystudio.droide.core

import java.io.File
import java.io.InterruptedIOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Cooperative metadata exclusion only. Old binaries and arbitrary same-UID writers do not
 * participate; this lease never authorizes rollback replay or payload release. */
internal object MirrorMetadataProcessLock {
    internal const val LOCK = ".metadata-access.lock"
    internal const val IDENTITY = ".metadata-access.identity"
    private const val STAGE = ".metadata-access-stage-"
    private val activeInodes = mutableSetOf<String>()

    fun <T> access(directory: File, binding: String, requireCurrent: () -> Unit = {},
                   checkpoint: (String) -> Unit = {}, action: (() -> Unit) -> T): T {
        require(binding.matches(Regex("[0-9a-f]{64}"))) { "Invalid synchronization lock binding" }
        val identity = WorkspaceDirectoryIdentity(directory)
        fun current() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Metadata access interrupted")
            requireCurrent(); identity.requireCurrent()
        }
        current()
        AnchoredWorkspaceFiles(directory).use { owner ->
            val key = synchronized(activeInodes) {
                // Creation descriptor must also close before ANY local acquisition; otherwise its late close
                // could drop another thread's acquired lock.
                if (owner.kind(LOCK) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    try {
                        owner.createNew(LOCK).use {
                            it.force(384)
                            checkpoint("metadata-lock-created-before-close")
                        }
                    } catch (_: FileAlreadyExistsException) { /* Another JVM published the stable lock. */ }
                }
                val observed = namespaceKey(directory)
                check(activeInodes.add(observed)) { "Synchronization metadata access is already active in this runtime" }
                observed
            }
            try {
                return owner.openRead(LOCK).use { pinned ->
                    check(pinned.observe().attributes.fileKey().toString() == key) { "Metadata lock was replaced before acquisition" }
                    pinned.withExclusiveMetadataLock { requireHeld ->
                        fun guarded() {
                            current(); requireHeld()
                            check(namespaceKey(directory) == key) { "Metadata lock namespace changed; existing evidence was preserved" }
                        }
                        guarded()
                        // Re-establish durability if a creator died before its fsync.
                        pinned.forceContents(); owner.forceDirectory()
                        val expected = marker(binding, owner.directoryKey(), key)
                        if (owner.kind(IDENTITY) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                            check(directory.list()?.count { it.startsWith(STAGE) }?.let { it < 8 } == true) {
                                "Metadata lock enrollment evidence capacity reached; existing evidence was preserved"
                            }
                            val staged = STAGE + UUID.randomUUID()
                            owner.createNew(staged).use { it.write(expected); it.force(384) }
                            checkpoint("metadata-lock-before-identity-publication")
                            guarded()
                            check(owner.moveNoReplaceDurable(staged, owner, IDENTITY).durable) {
                                "Metadata lock identity durability is unconfirmed; existing evidence was preserved"
                            }
                            checkpoint("metadata-lock-after-identity-publication")
                        }
                        validateMarker(owner, directory, expected)
                        guarded()
                        checkpoint("metadata-lock-acquired")
                        guarded()
                        action(::guarded).also { guarded(); validateMarker(owner, directory, expected) }
                    }
                }
            } finally {

                synchronized(activeInodes) { check(activeInodes.remove(key)) }
            }
        }
    }

    private fun namespaceKey(directory: File): String {
        val path = File(directory, LOCK).toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == 0L &&
            (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) {
            "Metadata lock must be a private ordinary zero-byte inode; existing evidence was preserved"
        }
        return checkNotNull(attributes.fileKey()).toString()
    }

    private fun marker(binding: String, authorityKey: String, lockKey: String): ByteArray {
        require(listOf(authorityKey, lockKey).all { it.length in 1..512 && '\n' !in it && '\r' !in it })
        val body = "DROIDE-METADATA-LOCK-1\n$binding\n$authorityKey\n$lockKey\n"
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$body$hash\n".toByteArray(Charsets.UTF_8)
    }

    private fun validateMarker(owner: AnchoredWorkspaceFiles, directory: File, expected: ByteArray) {
        // Reject aliases to another live zero-byte lock before opening/closing it.
        val path = File(directory, IDENTITY).toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == expected.size.toLong() &&
            (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) {
            "Metadata lock identity is unsafe; existing evidence was preserved"
        }
        owner.openRead(IDENTITY).use { input ->
            val before = input.observe()
            check(before.attributes.size() == expected.size.toLong()) { "Metadata lock identity changed; existing evidence was preserved" }
            val bytes = ByteArray(expected.size + 1)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                check(count > 0) { "Metadata lock identity read stalled" }; offset += count
            }
            val after = input.observe()
            check(offset == expected.size && bytes.copyOf(offset).contentEquals(expected) &&
                before.attributes.fileKey() == after.attributes.fileKey() && before.changed == after.changed &&
                before.attributes.size() == after.attributes.size()) {
                "Metadata lock identity mismatch; no automatic re-enrollment is allowed"
            }
        }
    }
}

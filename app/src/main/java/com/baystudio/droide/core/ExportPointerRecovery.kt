package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Private metadata authority. Never opens a capture subtree or destination output. */
internal class ExportPointerRecovery private constructor(
    private val registryKey: String,
    private val base: File,
    private val root: AnchoredWorkspaceFiles,
    private val lockFile: AnchoredWorkspaceFiles.OpenedFile,
    private val lease: AnchoredWorkspaceFiles.OpenedFile.MetadataLease,
) : Closeable {
    enum class Current { ABSENT, VALID, MALFORMED, INCOMPATIBLE }
    enum class Checkpoint { DIRECTORY_CREATED, INTENT_DURABLE, BEFORE_MOVE, MOVED, EVIDENCE_DURABLE }
    data class Result(val recovered: Boolean, val retainedRecords: Int)
    private data class Stamp(val key: String, val size: Long, val digest: String)
    private var closed = false

    @Synchronized fun requireHeld() {
        check(!closed) { "Export metadata authority is closed" }
        lease.requireHeld()
        root.directoryKey() // Also checks the pinned root still names this directory.
    }

    /** Startup only, under the controller initialization lock before any job can be published. */
    @Synchronized fun recover(
        checkCancelled: () -> Unit = {},
        checkpoint: (Checkpoint) -> Unit = {},
    ): Result {
        requireHeld(); checkCancelled()
        var retained = 0
        var recovered = false
        val transactions = mutableListOf<String>()
        root.forEachChild { if (it.startsWith(PREFIX)) transactions += it }
        for (name in transactions.sorted()) {
            checkCancelled()
            check(name.removePrefix(PREFIX).matches(Regex("[a-f0-9-]{36}"))) { "Export recovery identity is invalid; data preserved" }
            root.openDirectory(name).use { transaction ->
                when (transaction.kind(INTENT)) {
                    AnchoredWorkspaceFiles.Kind.ABSENT -> {
                        // No durable intent and no evidence: no move was authorized by this
                        // protocol. Preserve this artifact as diagnostic only, never as proof
                        // of recovered output or permission to clean old staging.
                        check(transaction.kind(EVIDENCE) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                            "Unfinished export recovery needs review; data preserved"
                        }
                        retained++
                    }
                    AnchoredWorkspaceFiles.Kind.FILE -> {
                        val expected = readIntent(transaction)
                        if (transaction.kind(EVIDENCE) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                            check(stamp(root, CURRENT, checkCancelled) == expected) { "Export recovery source changed; data preserved" }
                            check(classify(root, CURRENT) == Current.MALFORMED) { "Export recovery source is not malformed" }
                            moveAndConfirm(transaction, expected, checkCancelled, checkpoint)
                            recovered = true
                        } else confirmEvidence(transaction, expected, checkCancelled)
                        retained++
                    }
                    else -> error("Export recovery intent is not ordinary metadata; data preserved")
                }
            }
        }
        when (classify(root, CURRENT)) {
            Current.ABSENT, Current.VALID -> Unit
            Current.INCOMPATIBLE -> error("Export recovery format is not supported; update compatibility before retrying. Data preserved.")
            Current.MALFORMED -> {
                val expected = stamp(root, CURRENT, checkCancelled)
                ExportStorageBudget.requireMetadataAvailable(base)
                val name = PREFIX + UUID.randomUUID().toString()
                val made = root.createDirectory(name)
                check(made.durable) { "Cannot establish durable export recovery directory; data preserved" }
                checkpoint(Checkpoint.DIRECTORY_CREATED)
                root.openDirectory(name).use { transaction ->
                    val data = Properties().apply {
                        setProperty("version", "1"); setProperty("key", expected.key)
                        setProperty("size", expected.size.toString()); setProperty("sha256", expected.digest)
                    }
                    val encoded = ByteArrayOutputStream().also { data.store(it, "Export metadata evidence identity") }.toByteArray()
                    transaction.createNew("intent.pending").use { it.write(encoded); it.force(384) }
                    check(transaction.moveNoReplaceDurable("intent.pending", transaction, INTENT).durable) {
                        "Cannot confirm export recovery intent durability; data preserved"
                    }
                    checkpoint(Checkpoint.INTENT_DURABLE)
                    moveAndConfirm(transaction, expected, checkCancelled, checkpoint)
                    retained++; recovered = true
                }
            }
        }
        return Result(recovered, retained)
    }

    private fun moveAndConfirm(transaction: AnchoredWorkspaceFiles, expected: Stamp,
        checkCancelled: () -> Unit, checkpoint: (Checkpoint) -> Unit) {
        checkCancelled(); requireHeld()
        check(stamp(root, CURRENT, checkCancelled) == expected) { "Export recovery source changed; data preserved" }
        checkpoint(Checkpoint.BEFORE_MOVE)
        checkCancelled()
        check(stamp(root, CURRENT, checkCancelled) == expected) { "Export recovery source changed before move" }
        check(classify(root, CURRENT) == Current.MALFORMED) { "Export recovery source is no longer malformed" }
        val moved = root.moveNoReplaceDurable(CURRENT, transaction, EVIDENCE)
        check(moved.published) { "Export recovery move did not publish; data preserved" }
        // After publication finish durability bookkeeping before observing cancellation.
        // Exceptions/fatal termination are recoverable from durable intent + exact evidence.
        checkpoint(Checkpoint.MOVED)
        confirmEvidence(transaction, expected) {}
        checkpoint(Checkpoint.EVIDENCE_DURABLE)
        checkCancelled()
    }

    private fun confirmEvidence(transaction: AnchoredWorkspaceFiles, expected: Stamp, poll: () -> Unit) {
        check(stamp(transaction, EVIDENCE, poll) == expected) { "Export recovery evidence changed; data preserved" }
        transaction.openRead(EVIDENCE).use { it.forceContents() }
        transaction.forceDirectory(); root.forceDirectory()
    }

    private fun readIntent(transaction: AnchoredWorkspaceFiles): Stamp {
        val p = Properties().also { it.load(ByteArrayInputStream(readSmall(transaction, INTENT))) }
        check(p.getProperty("version") == "1") { "Unsupported export recovery intent; data preserved" }
        val key = checkNotNull(p.getProperty("key"))
        val size = checkNotNull(p.getProperty("size")).toLong()
        val digest = checkNotNull(p.getProperty("sha256"))
        check(key.isNotBlank() && key.length <= 256 && size >= 0 && digest.matches(Regex("[0-9a-f]{64}")))
        return Stamp(key, size, digest)
    }

    private fun stamp(files: AnchoredWorkspaceFiles, name: String, poll: () -> Unit): Stamp = files.openRead(name).use { input ->
        val before = input.observe()
        check(input.linkCount() == 1L) { "Export metadata has ambiguous links; data preserved" }
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            poll()
            val n = input.read(buffer)
            if (n < 0) break
            check(n > 0) { "Export metadata read made no progress" }
            count = Math.addExact(count, n.toLong())
            check(count <= before.attributes.size()) { "Export metadata grew during inspection" }
            hash.update(buffer, 0, n)
        }
        val after = input.observe()
        check(before.attributes.fileKey() == after.attributes.fileKey() &&
            before.attributes.size() == count && after.attributes.size() == count && before.changed == after.changed) {
            "Export metadata changed during inspection; data preserved"
        }
        Stamp(checkNotNull(before.attributes.fileKey()).toString(), count,
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun classify(files: AnchoredWorkspaceFiles, name: String): Current {
        if (files.kind(name) == AnchoredWorkspaceFiles.Kind.ABSENT) return Current.ABSENT
        // Identity/IO failure is not evidence of malformed content and must propagate.
        val bytes = readSmall(files, name, allowOversize = true) ?: return Current.INCOMPATIBLE
        val p = ExportStateProperties()
        // Syntax damage may hide escaped/continued future declarations. Preserve and block
        // rather than guessing a schema. Valid syntax with version=broken remains recoverable.
        try { p.load(ByteArrayInputStream(bytes)) } catch (_: IllegalArgumentException) { return Current.INCOMPATIBLE }
        val numeric = Regex("[+-]?[0-9]+")
        if (p.declaredVersions.size > 1 || p.declaredVersions.any { it != "1" && numeric.matches(it.trim()) })
            return Current.INCOMPATIBLE
        val version = p.getProperty("version")
        if (version != "1") return Current.MALFORMED
        return try {
            ExportStateCodec.decode(p)
            Current.VALID
        } catch (_: IllegalArgumentException) { Current.MALFORMED }
        catch (_: IllegalStateException) { Current.MALFORMED }
    }

    private fun readSmall(files: AnchoredWorkspaceFiles, name: String): ByteArray =
        checkNotNull(readSmall(files, name, false))
    private fun readSmall(files: AnchoredWorkspaceFiles, name: String, allowOversize: Boolean): ByteArray? =
        files.openRead(name).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(n > 0)
                if (output.size() + n > 1024 * 1024) {
                    check(allowOversize) { "Export recovery receipt is oversized; data preserved" }
                    return@use null
                }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { lease.close() } finally {
            try { lockFile.close() } finally {
                try { root.close() } finally { synchronized(owners) { owners.remove(registryKey) } }
            }
        }
    }

    companion object {
        private const val CURRENT = "current.properties"
        private const val PREFIX = "recovery-"
        private const val INTENT = "intent.properties"
        private const val EVIDENCE = "current.properties"
        private val owners = mutableSetOf<String>()
        fun open(base: File): ExportPointerRecovery {
            val root = AnchoredWorkspaceFiles(base)
            // Registry identity precedes EVERY owner-inode open, including alias paths. Merely
            // opening another alias must not establish a second owner in this runtime.
            val key = try { root.directoryKey() } catch (failure: Throwable) { root.close(); throw failure }
            synchronized(owners) {
                if (!owners.add(key)) {
                    root.close()
                    error("Export metadata is already owned in this runtime")
                }
            }
            var lock: AnchoredWorkspaceFiles.OpenedFile? = null
            try {
                if (root.kind("export-owner.lock") == AnchoredWorkspaceFiles.Kind.ABSENT)
                    root.createNew("export-owner.lock").use { it.force(384) }
                lock = root.openRead("export-owner.lock")
                val lease = lock.acquireExclusiveExportLease()
                return ExportPointerRecovery(key, base, root, lock, lease)
            } catch (failure: Throwable) {
                try { lock?.close() } finally { try { root.close() } finally { synchronized(owners) { owners.remove(key) } } }
                throw failure
            }
        }
    }
}

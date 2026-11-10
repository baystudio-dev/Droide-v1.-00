package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Immutable evidence of an exact handle's completed cleanup, not a process inventory. */

internal class MirrorProcessCleanupReceipts(
    private val directory: File,
    private val binding: String,
    private val deleteMetadata: (AnchoredWorkspaceFiles, String, Any) -> Boolean = { files, name, key ->
        files.deleteRedundantMetadata(name, key)
    },
    private val checkpoint: (String) -> Unit = {},
) {
    companion object {
        const val LIMIT = 256
        private const val FOLDER = "process-cleanup-receipts"
        private const val SUFFIX = ".cleanup"
    }

    fun requireCapacity(owners: List<MirrorProcessCustodyLedger.Owner>) {
        AnchoredWorkspaceFiles(directory).use { authority ->
            val names = names(authority)
            check((names + owners.map { it.id }).size < LIMIT) {
                "Exact process cleanup evidence capacity is full; evidence was preserved and no process was launched"
            }
        }
    }

    /** committedOwners must be read at transaction entry, BEFORE any owner removal. An uncommitted
     * reconciliation is never sufficient authority to retire its proof. */
    fun retireCompleted(committedOwners: List<MirrorProcessCustodyLedger.Owner>) {
        AnchoredWorkspaceFiles(directory).use { authority ->
            val names = names(authority)
            if (names.isEmpty()) return
            val pending = committedOwners.mapTo(mutableSetOf()) { it.id }
            authority.openDirectory(FOLDER).use { receipts ->
                val fingerprint = authorityFingerprint(authority)
                for (id in names - pending) {
                    val owner = readOwner(receipts, id)
                    val key = requireReceipt(receipts, owner, fingerprint)
                    checkpoint("writer-cleanup-retirement-before-unlink")
                    // Unsupported descriptor-relative unlink preserves evidence and lets admission use remaining
                    // capacity. Never fall back to paths.
                    if (!deleteMetadata(receipts, id + SUFFIX, key)) return
                    checkpoint("writer-cleanup-retirement-after-unlink")
                }
            }
        }
    }

    private fun readOwner(receipts: AnchoredWorkspaceFiles, id: String): MirrorProcessCustodyLedger.Owner {
        val text = receipts.openRead(id + SUFFIX).use { input ->
            val size = input.observe().attributes.size()
            check(size in 1L..1024L) { "Cleanup proof is malformed; evidence was preserved" }
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0); offset += count
            }
            check(input.read() == -1)
            bytes.toString(Charsets.UTF_8)
        }.split('\n')
        check(text.size == 8 && text[3] == id && text[7].isEmpty()) { "Cleanup proof is malformed; evidence was preserved" }
        check(UUID.fromString(text[4]).toString() == text[4])
        val boot = if (text[5].isEmpty() && text[6].isEmpty()) null
            else WorkspaceProcessBootIdentity.Identity(text[5], text[6])
        return MirrorProcessCustodyLedger.Owner(id, text[4], boot)
    }

    fun remaining(owners: List<MirrorProcessCustodyLedger.Owner>): List<MirrorProcessCustodyLedger.Owner> =
        AnchoredWorkspaceFiles(directory).use { authority ->
            val names = names(authority)
            if (names.isEmpty()) owners else authority.openDirectory(FOLDER).use { receipts ->
                owners.filter { owner ->
                    if (owner.id !in names) true else {
                        requireReceipt(receipts, owner, authorityFingerprint(authority))
                        receipts.openRead(owner.id + SUFFIX).use { it.forceContents() }
                        receipts.forceDirectory()
                        false
                    }
                }
            }
        }

    fun record(owner: MirrorProcessCustodyLedger.Owner, owners: List<MirrorProcessCustodyLedger.Owner>) {
        check(owners.singleOrNull { it.id == owner.id } == owner) { "Cleanup receipt does not own this process" }
        AnchoredWorkspaceFiles(directory).use { authority ->
            val names = names(authority)
            check((names + owners.map { it.id }).size <= LIMIT) { "Cleanup evidence reservation is missing" }
            if (authority.kind(FOLDER) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                val created = authority.createDirectory(FOLDER)
                check(created.durable) { "Cleanup evidence directory durability is unconfirmed" }
            }
            authority.openDirectory(FOLDER).use { receipts ->
                val name = owner.id + SUFFIX
                if (owner.id !in names) {
                    receipts.createNew(name).use { output ->
                        output.write(expected(owner, authorityFingerprint(authority)))
                        checkpoint("writer-cleanup-receipt-before-force")
                        output.force(384)
                    }
                }
                // A retry never overwrites a torn/mismatched receipt. Re-force a valid existing record before
                // allowing a SQL commit after a lost response.
                requireReceipt(receipts, owner, authorityFingerprint(authority))
                receipts.openRead(name).use { it.forceContents() }
                receipts.forceDirectory()
                checkpoint("writer-cleanup-receipt-after-force")
            }
        }
    }

    private fun names(authority: AnchoredWorkspaceFiles): Set<String> {
        if (authority.kind(FOLDER) == AnchoredWorkspaceFiles.Kind.ABSENT) return emptySet()
        check(authority.kind(FOLDER) == AnchoredWorkspaceFiles.Kind.DIRECTORY) { "Cleanup receipt directory is unsafe" }
        return authority.openDirectory(FOLDER).use { receipts ->
            val key = receipts.directoryKey()
            val result = mutableSetOf<String>()
            receipts.forEachChild { name ->
                check(result.size < LIMIT) { "Cleanup evidence exceeds its bounded capacity" }
                check(name.endsWith(SUFFIX)) { "Unknown cleanup evidence was preserved" }
                val id = name.removeSuffix(SUFFIX)
                check(UUID.fromString(id).toString() == id && result.add(id)) { "Cleanup evidence identity is invalid" }
                check(receipts.kind(name) == AnchoredWorkspaceFiles.Kind.FILE) { "Cleanup receipt is not an ordinary file" }
            }
            check(authority.directoryKey(FOLDER) == key && receipts.directoryKey() == key) { "Cleanup evidence directory changed" }
            result
        }
    }

    private fun requireReceipt(receipts: AnchoredWorkspaceFiles, owner: MirrorProcessCustodyLedger.Owner, key: String): Any {
        val expected = expected(owner, key)
        return receipts.openRead(owner.id + SUFFIX).use { input ->
            val before = input.observe()
            check(before.attributes.size() == expected.size.toLong()) { "Cleanup receipt is incomplete or belongs to another owner" }
            val actual = ByteArray(expected.size)
            var offset = 0
            while (offset < actual.size) {
                val count = input.read(actual, offset, actual.size - offset)
                check(count > 0) { "Cleanup receipt is truncated" }; offset += count
            }
            check(input.read() == -1 && actual.contentEquals(expected)) { "Cleanup receipt owner or authority changed" }
            val after = input.observe()
            check(before.attributes.fileKey() == after.attributes.fileKey() &&
                before.attributes.size() == after.attributes.size() &&
                before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() &&
                before.changed == after.changed) { "Cleanup receipt changed during inspection" }
            receipts.openRead(owner.id + SUFFIX).use { current ->
                check(current.observe().attributes.fileKey() == before.attributes.fileKey()) { "Cleanup receipt was replaced" }
            }
            checkNotNull(before.attributes.fileKey())
        }
    }

    private fun authorityFingerprint(authority: AnchoredWorkspaceFiles): String {
        // Include the immutable authority nonce, not just inode numbers which can be reused after
        // replacement. The authority validates this marker before access.
        val bytes = authority.openRead("identity").use { input ->
            val before = input.observe()
            check(before.attributes.size() in 1L..4096L) { "Authority identity is unavailable" }
            val bytes = ByteArray(before.attributes.size().toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0); offset += count
            }
            val after = input.observe()
            check(input.read() == -1 && before.attributes.fileKey() == after.attributes.fileKey() &&
                before.attributes.size() == after.attributes.size() && before.changed == after.changed) {
                "Authority identity changed during cleanup receipt inspection"
            }
            bytes
        }
        return authority.directoryKey() + ":" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun expected(owner: MirrorProcessCustodyLedger.Owner, key: String): ByteArray =
        listOf("DROIDE-EXACT-PROCESS-CLEANUP-1", binding, key, owner.id, owner.runtimeId,
            owner.boot?.device.orEmpty(), owner.boot?.boot.orEmpty(), "").joinToString("\n").toByteArray(Charsets.UTF_8)
}

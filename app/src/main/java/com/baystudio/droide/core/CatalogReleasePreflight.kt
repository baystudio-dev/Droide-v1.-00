package com.baystudio.droide.core

import java.security.MessageDigest

/** Pure, in-memory comparison of supplied observations. There is deliberately no production
 * collector or release capability here. Evidence is not a lock, reservation or writer proof.
 * PRECONDITION for a future collector: raw encoded records and decoded fields must come
 * from the same verified journal read. This evaluator does not decode/validate raw JSON. */
internal object CatalogReleasePreflight {
    const val LIMITATION = "Physical release is unavailable until writer admission and durable retirement are integrated."
    data class Scope(val owner: String, val authority: String, val root: String,
                     val rootIdentity: String, val generation: String)
    data class Custody(val name: String, val version: CanonicalFileSnapshot.Version)
    class Record(val id: String, val owner: String, val kind: String, val encoded: String,
                 val retainedBytes: Long, custody: List<Custody>, val path: String = "projects.json") {
        val custody: List<Custody> = java.util.Collections.unmodifiableList(custody.toList())
        fun copy(id: String = this.id, owner: String = this.owner, kind: String = this.kind,
                 encoded: String = this.encoded, retainedBytes: Long = this.retainedBytes,
                 custody: List<Custody> = this.custody, path: String = this.path) =
            Record(id, owner, kind, encoded, retainedBytes, custody, path)
    }
    class Snapshot(val scope: Scope, val catalog: CanonicalFileSnapshot.Version,
                   val policy: CanonicalJournalRetention.Policy, records: List<Record>,
                   val pendingOperation: String? = null) {
        val records: List<Record> = java.util.Collections.unmodifiableList(records.toList())
        fun copy(scope: Scope = this.scope, catalog: CanonicalFileSnapshot.Version = this.catalog,
                 policy: CanonicalJournalRetention.Policy = this.policy, records: List<Record> = this.records,
                 pendingOperation: String? = this.pendingOperation) =
            Snapshot(scope, catalog, policy, records, pendingOperation)
    }
    enum class Blocker {
        INVALID_INPUT, DUPLICATE_ID, UNKNOWN_SELECTION, WRONG_OWNER, UNSUPPORTED_RECORD_KIND,
        OVERFLOW, CAPACITY_EXHAUSTED, METADATA_CAPACITY_EXHAUSTED, IDENTITY_CHANGED,
        CATALOG_CHANGED, HISTORY_CHANGED, CUSTODY_CHANGED, PENDING_CATALOG_OPERATION,
        DIRTY_BUFFERS, PENDING_SAVES, PENDING_SYNC, UNRESOLVED_HANDOFF, LIVE_PROCESS_OWNERS,
        RETAINED_PROCESS_OWNERS, PARTICIPATING_WRITERS,
        UNKNOWN_DESCENDANT_OWNERSHIP, UNKNOWN_DESCRIPTOR_OWNERSHIP, UNKNOWN_PROCESS_COVERAGE,
        EDITOR_EVIDENCE_UNAVAILABLE, PROCESS_EVIDENCE_UNAVAILABLE, SNAPSHOT_UNAVAILABLE,
    }
    // null is unavailable, never an empty successful observation. These lists report only
    // observations; an empty list must never be consumed as permission to remove anything.
    data class EditorEvidence(val dirtyBuffers: List<String>, val pendingSaves: List<String>,
                              val pendingSync: List<String>, val unresolvedHandoffs: List<String>)
    data class ProcessEvidence(val liveOwners: List<String>, val unknownDescendants: List<String>,
                               val unknownDescriptors: List<String>, val unknownCoverage: List<String> = emptyList(),
                               val retainedOwners: List<String> = emptyList(), val participatingWriters: List<String> = emptyList())
    data class SelectedRecord(val id: String, val recordDigest: String, val custody: List<Custody>)
    data class Observation(val scope: Scope, val catalog: CanonicalFileSnapshot.Version,
                           val policy: CanonicalJournalRetention.Policy, val historyDigest: String,
                           val selected: List<SelectedRecord>)
    sealed interface Result {
        data class ObservedForReview(val observation: Observation) : Result {
            val limitation: String get() = LIMITATION
        }
        data class Blocked(val blockers: Set<Blocker>) : Result
    }

    // Inputs must not be concurrently mutated during construction/evaluation. Stored snapshots
    // own their collection structure; they cannot be rewritten through the original lists.
    fun evaluate(selectedAt: Snapshot?, current: Snapshot?, selectedIds: List<String>,
                 editor: EditorEvidence?, processes: ProcessEvidence?): Result {
        val selectedIds = selectedIds.toList()
        val editor = editor?.let { EditorEvidence(it.dirtyBuffers.toList(), it.pendingSaves.toList(),
            it.pendingSync.toList(), it.unresolvedHandoffs.toList()) }
        val processes = processes?.let { ProcessEvidence(it.liveOwners.toList(),
            it.unknownDescendants.toList(), it.unknownDescriptors.toList(), it.unknownCoverage.toList(),
            it.retainedOwners.toList(), it.participatingWriters.toList()) }
        val blockers = linkedSetOf<Blocker>()
        if (editor == null) blockers += Blocker.EDITOR_EVIDENCE_UNAVAILABLE else {
            if (editor.dirtyBuffers.isNotEmpty()) blockers += Blocker.DIRTY_BUFFERS
            if (editor.pendingSaves.isNotEmpty()) blockers += Blocker.PENDING_SAVES
            if (editor.pendingSync.isNotEmpty()) blockers += Blocker.PENDING_SYNC
            if (editor.unresolvedHandoffs.isNotEmpty()) blockers += Blocker.UNRESOLVED_HANDOFF
        }
        if (processes == null) blockers += Blocker.PROCESS_EVIDENCE_UNAVAILABLE else {
            if (processes.liveOwners.isNotEmpty()) blockers += Blocker.LIVE_PROCESS_OWNERS
            if (processes.retainedOwners.isNotEmpty()) blockers += Blocker.RETAINED_PROCESS_OWNERS
            if (processes.participatingWriters.isNotEmpty()) blockers += Blocker.PARTICIPATING_WRITERS
            if (processes.unknownDescendants.isNotEmpty()) blockers += Blocker.UNKNOWN_DESCENDANT_OWNERSHIP
            if (processes.unknownDescriptors.isNotEmpty()) blockers += Blocker.UNKNOWN_DESCRIPTOR_OWNERSHIP
            if (processes.unknownCoverage.isNotEmpty()) blockers += Blocker.UNKNOWN_PROCESS_COVERAGE
        }
        if (selectedIds.isEmpty() || selectedIds.size > 128 || selectedIds.any { it.isBlank() }) blockers += Blocker.INVALID_INPUT
        if (selectedIds.distinct().size != selectedIds.size) blockers += Blocker.DUPLICATE_ID
        if (selectedAt == null || current == null) {
            blockers += Blocker.SNAPSHOT_UNAVAILABLE
            return Result.Blocked(blockers.toSet())
        }
        validate(selectedAt, blockers)
        validate(current, blockers)
        if (blockers.any { it in invalid }) return Result.Blocked(blockers.toSet())
        if (selectedAt.scope != current.scope) blockers += Blocker.IDENTITY_CHANGED
        if (selectedAt.catalog != current.catalog) blockers += Blocker.CATALOG_CHANGED
        if (selectedAt.policy != current.policy || historyDigest(selectedAt) != historyDigest(current)) blockers += Blocker.HISTORY_CHANGED
        val before = selectedAt.records.associateBy { it.id }
        val now = current.records.associateBy { it.id }
        for (id in selectedIds) {
            val a = before[id]; val b = now[id]
            if (a == null || b == null) blockers += Blocker.UNKNOWN_SELECTION
            else if (a.custody.sortedBy { it.name } != b.custody.sortedBy { it.name }) blockers += Blocker.CUSTODY_CHANGED
        }
        if (blockers.isNotEmpty()) return Result.Blocked(blockers.toSet())
        return Result.ObservedForReview(Observation(current.scope, current.catalog, current.policy,
            historyDigest(current), selectedIds.sorted().map { id ->
                val record = now.getValue(id)
                SelectedRecord(id, hash(listOf(record.encoded)), record.custody.sortedBy { it.name }.toList())
            }))
    }

    private val invalid = setOf(Blocker.INVALID_INPUT, Blocker.DUPLICATE_ID, Blocker.WRONG_OWNER,
        Blocker.UNSUPPORTED_RECORD_KIND, Blocker.OVERFLOW)

    private fun validate(snapshot: Snapshot, blockers: MutableSet<Blocker>) {
        val scope = snapshot.scope
        if (listOf(scope.owner, scope.authority, scope.root, scope.rootIdentity, scope.generation).any { it.isBlank() } ||
            !validVersion(snapshot.catalog)) blockers += Blocker.INVALID_INPUT
        if (snapshot.pendingOperation != null) blockers += Blocker.PENDING_CATALOG_OPERATION
        val records = snapshot.records
        if (records.size > snapshot.policy.maxEntries) blockers += Blocker.INVALID_INPUT
        if (records.size >= snapshot.policy.maxEntries) blockers += Blocker.CAPACITY_EXHAUSTED
        if (records.map { it.id }.distinct().size != records.size) blockers += Blocker.DUPLICATE_ID
        var retained = 0L
        var metadata = 0L
        try {
            for (record in records) {
                if (record.id.isBlank() || record.encoded.isEmpty() || record.retainedBytes < 0 ||
                    record.custody.size != 3 || record.custody.any { it.name.isBlank() || !validVersion(it.version) }) blockers += Blocker.INVALID_INPUT
                if (record.owner != scope.owner) blockers += Blocker.WRONG_OWNER
                if (record.kind != "REPLACE" || record.path != "projects.json") blockers += Blocker.UNSUPPORTED_RECORD_KIND
                if (record.custody.map { it.name }.distinct().size != record.custody.size) blockers += Blocker.DUPLICATE_ID
                if (record.custody.map { it.name }.toSet() != setOf("${record.id}.before", "${record.id}.after", "${record.id}.retired")) blockers += Blocker.INVALID_INPUT
                val custodyBytes = record.custody.fold(0L) { sum, item -> Math.addExact(sum, item.version.size) }
                if (custodyBytes != record.retainedBytes) blockers += Blocker.INVALID_INPUT
                val bytes = record.encoded.toByteArray(Charsets.UTF_8).size.toLong()
                if (bytes > MAX_RECORD_BYTES) blockers += Blocker.INVALID_INPUT
                retained = Math.addExact(retained, record.retainedBytes)
                metadata = Math.addExact(metadata, bytes)
            }
        } catch (_: ArithmeticException) { blockers += Blocker.OVERFLOW }
        if (retained > snapshot.policy.maxBytes) blockers += Blocker.INVALID_INPUT
        if (retained >= snapshot.policy.maxBytes) blockers += Blocker.CAPACITY_EXHAUSTED
        if (metadata > MAX_METADATA_BYTES - MAX_RECORD_BYTES) blockers += Blocker.METADATA_CAPACITY_EXHAUSTED
    }

    private fun validVersion(v: CanonicalFileSnapshot.Version) = v.identity.isNotBlank() && v.size >= 0 &&
        v.sha256.matches(Regex("[0-9a-f]{64}")) && v.mode >= 0

    // Same length-prefixed membership encoding as retention schema 2. Includes ALL records,
    // not just selections. Policy comes from both compared snapshots, never a relaxed override.
    private fun historyDigest(snapshot: Snapshot): String = hash(buildList {
        add(snapshot.policy.maxEntries.toString()); add(snapshot.policy.maxBytes.toString())
        snapshot.records.sortedBy { it.id }.forEach { add(it.id); add(it.retainedBytes.toString()); add(it.encoded) }
    })
    private fun hash(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private const val MAX_RECORD_BYTES = 524_288L
    private const val MAX_METADATA_BYTES = 4L * 1024 * 1024
}

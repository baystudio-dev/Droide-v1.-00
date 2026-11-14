package com.baystudio.droide.core

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

// Metadata only; the caller owns the SQLite transaction and all filesystem validation.
internal object CanonicalJournalRetention {
    data class Policy(val maxEntries: Int, val maxBytes: Long) {
        init {
            require(maxEntries in 1..128)
            require(maxBytes > 0)
        }
    }

    data class Entry(val id: String, val record: String, val retainedBytes: Long)

    enum class CapacityBlocker { ENTRY_LIMIT, RETAINED_BYTE_BUDGET, METADATA_BUDGET }

    // An accounting observation, not a reservation, filesystem free-space estimate or release permission.
    data class Usage(val completedEntries: Int, val maxEntries: Int, val retainedBytes: Long,
                     val maxRetainedBytes: Long, val metadataBytes: Long,
                     val maxMetadataBytes: Long, val nextRecordReserveBytes: Long) {
        fun blockers(peakBytes: Long): Set<CapacityBlocker> {
            require(peakBytes >= 0)
            return buildSet {
                if (completedEntries >= maxEntries) add(CapacityBlocker.ENTRY_LIMIT)
                if (peakBytes > maxRetainedBytes - retainedBytes) add(CapacityBlocker.RETAINED_BYTE_BUDGET)
                if (metadataBytes > maxMetadataBytes - nextRecordReserveBytes) add(CapacityBlocker.METADATA_BUDGET)
            }
        }
    }

    class CapacityException(val usage: Usage, val requestedPeakBytes: Long,
                            val blockers: Set<CapacityBlocker>) : IllegalStateException(
        "Recovery retention is full; retained work needs review before another operation. " +
            "Completed entries: ${usage.completedEntries}/${usage.maxEntries}; retained bytes: " +
            "${usage.retainedBytes}/${usage.maxRetainedBytes}; requested peak bytes: $requestedPeakBytes; " +
            "metadata bytes: ${usage.metadataBytes}/${usage.maxMetadataBytes}. " +
            "Blocked by: ${blockers.joinToString()}. No retained files were released.",
    )

    data class State(val policy: Policy, val entries: List<Entry>) {
        val retainedBytes: Long = entries.fold(0L) { sum, entry -> Math.addExact(sum, entry.retainedBytes) }
        val metadataBytes: Long = entries.sumOf { it.record.toByteArray(Charsets.UTF_8).size.toLong() }
        val usage: Usage get() = Usage(entries.size, policy.maxEntries, retainedBytes, policy.maxBytes,
            metadataBytes, MAX_METADATA_BYTES, MAX_RECORD_BYTES)

        fun requireAdmission(peakBytes: Long) {
            val observed = usage
            val blockers = observed.blockers(peakBytes)
            if (blockers.isNotEmpty()) throw CapacityException(observed, peakBytes, blockers)
        }
    }

    fun read(db: SQLiteDatabase): State? {
        if (db.version == 1) {
            check(count(db, "SELECT count(*) FROM sqlite_master WHERE name IN ('retention','completed_operation')") == 0L) {
                "Retention schema does not match its version; existing evidence requires review"
            }
            return null
        }
        check(db.version in 2..4) { "Unsupported authority schema" }
        check(count(db, "SELECT count(*) FROM retention") == 1L) { "Retention membership is missing or duplicated" }
        var expectedCount = 0L
        var expectedDigest = ""
        val policy = db.rawQuery(
            "SELECT id,max_entries,max_bytes,expected_count," +
                "CASE WHEN typeof(records_digest)='text' AND length(records_digest)=64 THEN records_digest END FROM retention",
            null,
        ).use { row ->
            check(row.moveToFirst() && integer(row, 0) == 1L)
            val entryLimit = integer(row, 1)
            check(entryLimit in 1..128)
            val result = Policy(entryLimit.toInt(), integer(row, 2))
            expectedCount = integer(row, 3)
            check(expectedCount in 0..entryLimit)
            expectedDigest = string(row, 4)
            check(!row.moveToNext())
            result
        }
        check(count(db, "SELECT count(*) FROM completed_operation") == expectedCount) {
            "Completed recovery records are missing or unexpected"
        }
        val metadataBytes = count(db, "SELECT coalesce(sum(length(CAST(record_json AS BLOB))),0) FROM completed_operation")
        check(metadataBytes <= MAX_METADATA_BYTES) { "Recovery metadata exceeds its budget" }
        val entries = db.rawQuery(
            "SELECT CASE WHEN typeof(id)='text' AND length(id)=36 THEN id END," +
                "CASE WHEN typeof(record_json)='text' AND length(CAST(record_json AS BLOB)) BETWEEN 1 AND $MAX_RECORD_BYTES " +
                "THEN record_json END,retained_bytes FROM completed_operation ORDER BY id",
            null,
        ).use { row ->
            buildList {
                while (row.moveToNext()) {
                    check(size < policy.maxEntries)
                    val retainedBytes = integer(row, 2)
                    check(retainedBytes >= 0)
                    add(Entry(string(row, 0), string(row, 1), retainedBytes))
                }
            }
        }
        check(entries.size.toLong() == expectedCount && digest(policy, entries) == expectedDigest) {
            "Completed records do not match durable retention membership"
        }
        return State(policy, entries).also {
            check(it.retainedBytes <= policy.maxBytes && it.metadataBytes == metadataBytes) {
                "Recovery retention accounting is invalid"
            }
        }
    }

    fun enable(db: SQLiteDatabase, policy: Policy) {
        check(db.version == 1 && read(db) == null) { "Retention requires an explicit version-1 upgrade" }
        db.execSQL("CREATE TABLE retention(id INTEGER PRIMARY KEY CHECK(id=1),max_entries INTEGER NOT NULL," +
            "max_bytes INTEGER NOT NULL,expected_count INTEGER NOT NULL,records_digest TEXT NOT NULL)")
        db.execSQL("CREATE TABLE completed_operation(id TEXT PRIMARY KEY NOT NULL,record_json TEXT NOT NULL,retained_bytes INTEGER NOT NULL)")
        db.execSQL("INSERT INTO retention VALUES(1,?,?,0,?)", arrayOf<Any>(policy.maxEntries, policy.maxBytes, digest(policy, emptyList())))
        db.version = 2
    }

    fun append(db: SQLiteDatabase, state: State, entry: Entry) {
        check(state.entries.none { it.id == entry.id }) { "Completed operation already has membership" }
        state.requireAdmission(entry.retainedBytes)
        check(entry.record.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES)
        val entries = (state.entries + entry).sortedBy { it.id }
        db.execSQL("INSERT INTO completed_operation VALUES(?,?,?)", arrayOf<Any>(entry.id, entry.record, entry.retainedBytes))
        db.execSQL("UPDATE retention SET expected_count=?,records_digest=? WHERE id=1",
            arrayOf<Any>(entries.size, digest(state.policy, entries)))
    }

    private fun digest(policy: Policy, entries: List<Entry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        add(policy.maxEntries.toString())
        add(policy.maxBytes.toString())
        entries.forEach { entry -> add(entry.id); add(entry.retainedBytes.toString()); add(entry.record) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun integer(row: Cursor, index: Int): Long {
        check(row.getType(index) == Cursor.FIELD_TYPE_INTEGER) { "Recovery metadata must be integral" }
        return row.getLong(index)
    }

    private fun string(row: Cursor, index: Int): String {
        check(row.getType(index) == Cursor.FIELD_TYPE_STRING) { "Recovery metadata must be bounded text" }
        return row.getString(index)
    }

    private fun count(db: SQLiteDatabase, query: String): Long = db.rawQuery(query, null).use { row ->
        check(row.moveToFirst())
        integer(row, 0).also { check(it >= 0 && !row.moveToNext()) }
    }

    private const val MAX_RECORD_BYTES = 524_288L
    private const val MAX_METADATA_BYTES = 4L * 1024 * 1024
}

package com.baystudio.droide.core

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Explicit membership receipts and opt-in schema-4 compact historical links. No file release. */
internal object CatalogMembershipCommands {
    const val RECIPIENT = "manager-membership-service-v1"
    private const val MAX_COMPACT = 4096
    private const val MAX_ROW = 2 * 1024 * 1024
    private const val MAX_TOTAL = 8L * 1024 * 1024
    private val json = Json { encodeDefaults = true }

    @Serializable data class Request(val targetId: String, val rootPath: String, val rootKey: String,
        val beforeBytes: Long, val beforeHash: String, val proposal: String)
    @Serializable data class Receipt(val token: String, val authority: String, val request: Request,
        val operationId: String?, val frozenIntent: String?, val outcome: String?, val acknowledged: Boolean = false,
        val recipient: String = RECIPIENT) {
        val needsReview: Boolean get() = outcome == null
    }
    @Serializable private data class Mapping(val operationId: String, val command: String?)
    @Serializable data class Compact(val token: String, val authority: String, val operationId: String,
        val completedRecordHash: String, val frozenIntentHash: String, val requestHash: String,
        val acceptedEnvelopeHash: String, val recipient: String = RECIPIENT, val format: Int = 1)
    class OutcomeUnavailableException : IllegalStateException("Membership outcome unavailable: unknown or expired command")
    enum class Expiry { EXPIRED, OUTCOME_UNAVAILABLE }
    data class State(val receipts: List<Receipt>, val operations: Map<String, String?>, val compacted: List<Compact> = emptyList())

    fun encode(receipt: Receipt): String = json.encodeToString(receipt)
    fun encode(compact: Compact): String = json.encodeToString(compact)
    fun compact(receipt: Receipt): Compact {
        validate(receipt)
        check(receipt.acknowledged && receipt.outcome != null)
        return Compact(receipt.token, receipt.authority, checkNotNull(receipt.operationId), receipt.outcome,
            hash(checkNotNull(receipt.frozenIntent)), hash(json.encodeToString(receipt.request)), hash(encode(receipt)))
    }
    private fun validate(c: Compact) {
        uuid(c.token); uuid(c.operationId)
        check(c.format == 1 && c.recipient == RECIPIENT)
        listOf(c.authority, c.completedRecordHash, c.frozenIntentHash, c.requestHash, c.acceptedEnvelopeHash)
            .forEach { check(it.matches(Regex("[a-f0-9]{64}"))) }
        check(bytes(encode(c)).size <= MAX_COMPACT)
    }
    /** Stable admitted identity; completion/acceptance are outcome state, not a new request. */
    fun admissionEnvelopeHash(receipt: Receipt): String {
        validate(receipt)
        check(receipt.operationId != null && receipt.frozenIntent != null) { "An operation-backed command is required" }
        return hash(encode(receipt.copy(outcome = null, acknowledged = false)))
    }
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(bytes(text))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bytes(text: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }
    private fun uuid(value: String) { check(UUID.fromString(value).toString() == value) }
    private fun validate(r: Receipt) {
        uuid(r.token); check(r.recipient == RECIPIENT); check(r.authority.matches(Regex("[a-f0-9]{64}")))
        val q = r.request
        check(q.targetId.isNotEmpty() && q.targetId.length <= 256 && q.rootPath.isNotEmpty() && q.rootPath.length <= 4096 &&
            q.rootKey.isNotEmpty() && q.rootKey.length <= 256 && q.beforeBytes >= 0 && q.beforeHash.matches(Regex("[a-f0-9]{64}")))
        check(q.proposal.isNotEmpty() && reservedRowBytes(r) <= MAX_ROW)
        check((r.operationId == null) == (r.frozenIntent == null))
        r.operationId?.let(::uuid)
        r.outcome?.let { check(it.matches(Regex("[a-f0-9]{64}"))) }
        check(r.operationId != null || r.outcome != null) { "No-change command must complete atomically" }
        check(!r.acknowledged || r.outcome != null)
    }
    private fun text(row: Cursor, index: Int): String {
        check(row.getType(index) == Cursor.FIELD_TYPE_STRING); return row.getString(index)
    }
    private fun count(db: SQLiteDatabase, query: String): Long = db.rawQuery(query, null).use {
        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        it.getLong(0).also { n -> check(n >= 0 && !it.moveToNext()) }
    }
    private fun digest(receipts: List<Receipt>, operations: Map<String, String?>): String = hash(
        json.encodeToString(receipts.sortedBy { it.token }) + "\n" +
            json.encodeToString(operations.toSortedMap().map { Mapping(it.key, it.value) }))

    private fun digest4(receipts: List<Receipt>, operations: Map<String, String?>, compacted: List<Compact>): String =
        hash("MEMBERSHIP4\n" + digest(receipts, operations) + "\n" + json.encodeToString(compacted.sortedBy { it.token }))
    private fun stateDigest(db: SQLiteDatabase, receipts: List<Receipt>, operations: Map<String, String?>, compacted: List<Compact>): String =
        if (db.version == 4) digest4(receipts, operations, compacted) else digest(receipts, operations)

    fun read(db: SQLiteDatabase): State? {
        if (db.version < 3) {
            check(count(db, "SELECT count(*) FROM sqlite_master WHERE name IN ('membership_commands','membership_operations','membership_state','membership_compacted')") == 0L)
            return null
        }
        check(db.version in 3..4)
        check(count(db, "SELECT count(*) FROM membership_state") == 1L)
        val meta = db.rawQuery("SELECT id,command_count,operation_count,CASE WHEN typeof(digest)='text' AND length(CAST(digest AS BLOB))=64 THEN digest END FROM membership_state", null).use {
            check(it.moveToFirst() && (0..2).all { i -> it.getType(i) == Cursor.FIELD_TYPE_INTEGER } && it.getLong(0) == 1L)
            Triple(it.getLong(1), it.getLong(2), text(it, 3)).also { _ -> check(!it.moveToNext()) }
        }
        val compactCount = if (db.version == 4) count(db, "SELECT compact_count FROM membership_state WHERE id=1") else {
            check(count(db, "SELECT count(*) FROM sqlite_master WHERE name='membership_compacted'") == 0L)
            0L
        }
        check(compactCount in 0..128)
        val compacted = if (db.version == 4) {
            check(count(db, "SELECT count(*) FROM membership_compacted") == compactCount)
            check(count(db, "SELECT coalesce(sum(length(CAST(record AS BLOB))),0) FROM membership_compacted") <= MAX_TOTAL)
            db.rawQuery("SELECT CASE WHEN typeof(id)='text' AND length(CAST(id AS BLOB))=36 THEN id END,CASE WHEN typeof(record)='text' AND length(CAST(record AS BLOB)) BETWEEN 1 AND $MAX_COMPACT THEN record END FROM membership_compacted ORDER BY id", null).use { row ->
                buildList { while (row.moveToNext()) {
                    val raw = text(row, 1); val c = json.decodeFromString<Compact>(raw)
                    validate(c); check(c.token == text(row, 0) && encode(c) == raw); add(c)
                } }
            }
        } else emptyList()
        check(compacted.size.toLong() == compactCount)
        check(meta.first in 0..128 && meta.second in 0..129)
        check(count(db, "SELECT count(*) FROM membership_commands") == meta.first &&
            count(db, "SELECT count(*) FROM membership_operations") == meta.second)
        check(count(db, "SELECT coalesce(sum(length(CAST(record AS BLOB))),0) FROM membership_commands") <= MAX_TOTAL)
        val receipts = db.rawQuery("SELECT CASE WHEN typeof(id)='text' AND length(CAST(id AS BLOB))=36 THEN id END,CASE WHEN typeof(record)='text' AND length(CAST(record AS BLOB)) BETWEEN 1 AND $MAX_ROW THEN record END FROM membership_commands ORDER BY id", null).use { row ->
            buildList { while (row.moveToNext()) {
                val raw = text(row, 1); val r = json.decodeFromString<Receipt>(raw)
                validate(r); check(r.token == text(row, 0) && encode(r) == raw); add(r)
            } }
        }
        val mappings = db.rawQuery("SELECT CASE WHEN typeof(id)='text' AND length(CAST(id AS BLOB))=36 THEN id END,CASE WHEN command IS NULL THEN NULL WHEN typeof(command)='text' AND length(CAST(command AS BLOB))=36 THEN command ELSE 0 END FROM membership_operations ORDER BY id", null).use { row ->
            buildMap { while (row.moveToNext()) {
                val id = text(row, 0).also(::uuid)
                val command = if (row.isNull(1)) null else text(row, 1).also(::uuid)
                check(!containsKey(id)); put(id, command)
            } }
        }
        check(receipts.size.toLong() == meta.first && mappings.size.toLong() == meta.second && stateDigest(db, receipts, mappings, compacted) == meta.third)
        check(receipts.count { !it.acknowledged } <= 1)
        check(receipts.mapNotNull { it.operationId }.distinct().size == receipts.count { it.operationId != null })
        receipts.forEach { r -> r.operationId?.let { check(mappings.containsKey(it) && mappings[it] == r.token) } }
        check((receipts.map { it.token } + compacted.map { it.token }).distinct().size == receipts.size + compacted.size)
        compacted.forEach { c -> check(mappings[c.operationId] == c.token) }
        mappings.forEach { (id, command) -> command?.let {
            val targets = receipts.filter { it.token == command }.map { it.operationId } +
                compacted.filter { it.token == command }.map { it.operationId }
            check(targets.size == 1 && targets.single() == id)
        } }
        check(reservedBytes(receipts, compacted, db.version == 4) <= MAX_TOTAL)
        return State(java.util.Collections.unmodifiableList(receipts), java.util.Collections.unmodifiableMap(mappings),
            java.util.Collections.unmodifiableList(compacted))
    }
    // Derive both per-row and total headroom from the exact longest completed/ack shape.
    // Acknowledgement never reduces the reserved budget or frees capacity.
    private fun reservedRowBytes(r: Receipt): Int = bytes(encode(r.copy(outcome = r.outcome ?: "0".repeat(64), acknowledged = false))).size
    private fun reservedBytes(rows: List<Receipt>, compacted: List<Compact>, schema4: Boolean): Long {
        val full = rows.fold(0L) { total, r ->
            val compactSize = if (schema4 && r.operationId != null)
                bytes(encode(compact(r.copy(outcome = r.outcome ?: "0".repeat(64), acknowledged = true)))).size else 0
            Math.addExact(total, maxOf(reservedRowBytes(r), compactSize).toLong())
        }
        return compacted.fold(full) { total, c -> Math.addExact(total, bytes(encode(c)).size.toLong()) }
    }
    // Logical serialized envelope charge only; not SQLite pages/indexes or canonical custody.
    fun envelopeBytes(db: SQLiteDatabase): Long = checkNotNull(read(db)).let {
        reservedBytes(it.receipts, it.compacted, db.version == 4)
    }
    private fun write(db: SQLiteDatabase, rows: List<Receipt>, operations: Map<String, String?>, compacted: List<Compact> = emptyList()) {
        check(rows.size <= 128 && operations.size <= 129 && compacted.size <= 128 && reservedBytes(rows, compacted, db.version == 4) <= MAX_TOTAL)
        check(db.version == 4 || compacted.isEmpty())
        compacted.forEach(::validate)
        rows.forEach(::validate)
        db.execSQL("DELETE FROM membership_commands")
        rows.forEach { db.execSQL("INSERT INTO membership_commands VALUES(?,?)", arrayOf(it.token, encode(it))) }
        db.execSQL("DELETE FROM membership_operations")
        operations.forEach { (id, command) -> db.execSQL("INSERT INTO membership_operations VALUES(?,?)", arrayOf(id, command)) }
        if (db.version == 4) {
            db.execSQL("DELETE FROM membership_compacted")
            compacted.forEach { db.execSQL("INSERT INTO membership_compacted VALUES(?,?)", arrayOf(it.token, encode(it))) }
            db.execSQL("UPDATE membership_state SET compact_count=? WHERE id=1", arrayOf(compacted.size))
        }
        db.execSQL("UPDATE membership_state SET command_count=?,operation_count=?,digest=? WHERE id=1",
            arrayOf<Any>(rows.size, operations.size, stateDigest(db, rows, operations, compacted)))
    }
    fun enable(db: SQLiteDatabase, completed: List<String>) {
        check(db.version == 2 && read(db) == null)
        db.execSQL("CREATE TABLE membership_state(id INTEGER PRIMARY KEY CHECK(id=1),command_count INTEGER NOT NULL,operation_count INTEGER NOT NULL,digest TEXT NOT NULL)")
        db.execSQL("CREATE TABLE membership_commands(id TEXT PRIMARY KEY NOT NULL,record TEXT NOT NULL)")
        db.execSQL("CREATE TABLE membership_operations(id TEXT PRIMARY KEY NOT NULL,command TEXT)")
        db.execSQL("INSERT INTO membership_state VALUES(1,0,0,'')")
        db.version = 3
        write(db, emptyList(), completed.associateWith { null })
    }
    fun admit(db: SQLiteDatabase, operation: String?, receipt: Receipt?, limit: Int) {
        val state = read(db) ?: run { check(receipt == null); return }
        if (receipt != null) {
            check(state.receipts.none { !it.acknowledged }) { "A membership result requires explicit acceptance before another command" }
            check(state.receipts.size < limit && state.receipts.none { it.token == receipt.token } && state.compacted.none { it.token == receipt.token }) { "Membership command capacity exhausted" }
            check(receipt.operationId == operation)
        }
        val mapping = state.operations.toMutableMap()
        operation?.let { check(!mapping.containsKey(it)); mapping[it] = receipt?.token }
        write(db, state.receipts + listOfNotNull(receipt), mapping, state.compacted)
    }
    fun complete(db: SQLiteDatabase, operation: String, outcome: String) {
        val state = read(db) ?: return
        val token = state.operations[operation] ?: return
        val receipt = state.receipts.single { it.token == token }
        check(receipt.outcome == null || receipt.outcome == outcome)
        write(db, state.receipts.map { if (it.token == token) it.copy(outcome = outcome) else it }, state.operations, state.compacted)
    }
    fun noChangeOutcome(receipt: Receipt): String {
        check(receipt.operationId == null && receipt.frozenIntent == null)
        return hash("NO_CHANGE:" + encode(receipt.copy(outcome = "0".repeat(64), acknowledged = false)))
    }

    /** Caller owns the authority transaction. Acceptance alone never invokes expiry. */
    fun expireAcceptedNoChange(db: SQLiteDatabase, token: String, outcome: String): Expiry {
        uuid(token)
        check(outcome.matches(Regex("[a-f0-9]{64}"))) { "Exact outcome identity is required" }
        val state = checkNotNull(read(db)) { "Membership commands are not enabled" }
        val receipt = state.receipts.singleOrNull { it.token == token } ?: return Expiry.OUTCOME_UNAVAILABLE
        check(receipt.acknowledged && receipt.recipient == RECIPIENT && receipt.operationId == null &&
            receipt.frozenIntent == null && receipt.outcome == outcome && outcome == noChangeOutcome(receipt)) {
            "Only the exact explicitly accepted no-change result may expire"
        }
        val remaining = state.receipts.filterNot { it.token == token }
        check(db.delete("membership_commands", "id=?", arrayOf(token)) == 1)
        // No operation classification, canonical retention entry, or custody byte is removed/credited.
        db.execSQL("UPDATE membership_state SET command_count=?,digest=? WHERE id=1",
            arrayOf<Any>(remaining.size, stateDigest(db, remaining, state.operations, state.compacted)))
        return Expiry.EXPIRED
    }

    /** Explicit forward-only format migration. Caller must hold and validate the journal transaction. */
    fun enableCompaction(db: SQLiteDatabase) {
        check(db.version == 3)
        val state = checkNotNull(read(db))
        check(reservedBytes(state.receipts, emptyList(), true) <= MAX_TOTAL)
        db.execSQL("CREATE TABLE membership_compacted(id TEXT PRIMARY KEY NOT NULL,record TEXT NOT NULL)")
        db.execSQL("ALTER TABLE membership_state ADD COLUMN compact_count INTEGER NOT NULL DEFAULT 0")
        db.version = 4
        write(db, state.receipts, state.operations)
    }

    fun expireAcceptedOperation(db: SQLiteDatabase, token: String, outcome: String): Expiry {
        uuid(token); check(outcome.matches(Regex("[a-f0-9]{64}")))
        check(db.version == 4) { "Membership compaction requires explicit migration" }
        val state = checkNotNull(read(db))
        val receipt = state.receipts.singleOrNull { it.token == token } ?: return Expiry.OUTCOME_UNAVAILABLE
        check(receipt.acknowledged && receipt.operationId != null && receipt.frozenIntent != null && receipt.outcome == outcome) {
            "Only the exact explicitly accepted operation result may compact"
        }
        val compact = compact(receipt)
        val remaining = state.receipts.filterNot { it.token == token }
        val links = state.compacted + compact
        check(reservedBytes(remaining, links, true) <= MAX_TOTAL)
        db.execSQL("INSERT INTO membership_compacted VALUES(?,?)", arrayOf(compact.token, encode(compact)))
        check(db.delete("membership_commands", "id=?", arrayOf(token)) == 1)
        db.execSQL("UPDATE membership_state SET command_count=?,compact_count=?,digest=? WHERE id=1",
            arrayOf<Any>(remaining.size, links.size, stateDigest(db, remaining, state.operations, links)))
        return Expiry.EXPIRED
    }

    fun acknowledge(db: SQLiteDatabase, token: String, outcome: String): Receipt {
        uuid(token)
        val state = checkNotNull(read(db)); val r = state.receipts.singleOrNull { it.token == token } ?: throw OutcomeUnavailableException()
        check(r.outcome != null && r.outcome == outcome) { "Exact completed membership outcome is required" }
        val accepted = r.copy(acknowledged = true)
        write(db, state.receipts.map { if (it.token == token) accepted else it }, state.operations, state.compacted)
        return accepted
    }
}

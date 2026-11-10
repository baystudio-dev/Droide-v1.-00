package com.baystudio.droide.core

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Metadata only. */

internal object MirrorProcessCustodyLedger {
    const val MAX_OWNERS = 256
    const val BOOT_SCHEMA = 5
    const val LAUNCH_SCHEMA = 6
    enum class LaunchState { UNKNOWN, PREPARED, FACTORY_ENTERED, CANCELLED_NO_START }
    data class Owner(val id: String, val runtimeId: String, val boot: WorkspaceProcessBootIdentity.Identity? = null)

    fun validateSchema(db: SQLiteDatabase, enabled: Boolean) {
        val tables = db.rawQuery("SELECT name,type FROM sqlite_master WHERE name IN ('process_custody','process_owners')", null).use { rows ->
            buildSet { while (rows.moveToNext()) { check(rows.getString(1) == "table"); add(rows.getString(0)) } }
        }
        check(tables == if (enabled) setOf("process_custody", "process_owners") else emptySet<String>()) {
            "Process custody schema is missing or changed; existing ownership was preserved"
        }
        if (enabled) {
            val columns = db.rawQuery("PRAGMA table_info(process_owners)", null).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
            }
            check(("device_identity" in columns) == (db.version >= BOOT_SCHEMA) &&
                ("boot_id" in columns) == (db.version >= BOOT_SCHEMA) &&
                ("launch_state" in columns) == (db.version >= LAUNCH_SCHEMA)) {
                "Process boot identity schema is missing or changed; ownership was preserved"
            }
            if (db.version >= LAUNCH_SCHEMA) db.rawQuery("PRAGMA table_info(process_owners)", null).use { rows ->
                var checked = false
                while (rows.moveToNext()) if (rows.getString(1) == "launch_state") {
                    check(rows.getInt(3) == 1 && rows.isNull(4)) { "Launch state must be explicit with no legacy INSERT default" }
                    checked = true
                }
                check(checked)
            }
        }
    }

    fun enable(db: SQLiteDatabase, binding: String) {
        check(db.inTransaction())
        validateSchema(db, false)
        db.execSQL("CREATE TABLE process_custody(id INTEGER PRIMARY KEY CHECK(id=1),expected_count INTEGER NOT NULL,members_digest TEXT NOT NULL)")
        db.execSQL("CREATE TABLE process_owners(id TEXT PRIMARY KEY NOT NULL,runtime_id TEXT NOT NULL)")
        db.execSQL("INSERT INTO process_custody VALUES(1,0,?)", arrayOf(digest(binding, emptyList(), withBoot = false)))
    }

    fun enableBootIdentity(db: SQLiteDatabase, binding: String) {
        if (db.version >= BOOT_SCHEMA) return
        check(db.inTransaction() && db.version == BOOT_SCHEMA - 1)
        val owners = read(db, binding)
        db.execSQL("ALTER TABLE process_owners ADD COLUMN device_identity TEXT")
        db.execSQL("ALTER TABLE process_owners ADD COLUMN boot_id TEXT")
        db.version = BOOT_SCHEMA
        // Older records stay unknown. Never assign today's boot to an older process.
        update(db, binding, owners)
    }

    fun enableLaunchState(db: SQLiteDatabase, binding: String) {
        if (db.version >= LAUNCH_SCHEMA) return
        check(db.inTransaction() && db.version == BOOT_SCHEMA)
        check(read(db, binding).isEmpty()) {
            "Legacy process custody is unresolved; finish exact cleanup before upgrading launch permission metadata"
        }
        // No default: an old runtime that validated v5 before this transaction cannot INSERT a
        // phase-less owner after the upgrade.
        db.execSQL("CREATE TABLE process_owners_launch(id TEXT PRIMARY KEY NOT NULL,runtime_id TEXT NOT NULL,device_identity TEXT,boot_id TEXT,launch_state TEXT NOT NULL CHECK(launch_state IN ('UNKNOWN','PREPARED','FACTORY_ENTERED','CANCELLED_NO_START')))")
        db.execSQL("DROP TABLE process_owners")
        db.execSQL("ALTER TABLE process_owners_launch RENAME TO process_owners")
        db.version = LAUNCH_SCHEMA
        update(db, binding, emptyList())
    }

    fun launchStates(db: SQLiteDatabase): Map<String, LaunchState> {
        if (db.version < LAUNCH_SCHEMA) return emptyMap()
        return db.rawQuery("SELECT CASE WHEN typeof(id)='text' AND length(id)=36 THEN id END,CASE WHEN typeof(launch_state)='text' AND length(launch_state)<=32 THEN launch_state END FROM process_owners ORDER BY id", null).use { rows ->
            buildMap { while (rows.moveToNext()) {
                check(size < MAX_OWNERS)
                val id = uuid(text(rows, 0)); val state = text(rows, 1)
                check(state.length <= 32)
                check(put(id, LaunchState.valueOf(state)) == null)
            } }
        }
    }

    fun enterFactory(db: SQLiteDatabase, binding: String, owner: Owner) {
        val owners = read(db, binding)
        check(owners.singleOrNull { it.id == owner.id } == owner) { "Process launch permission is missing or belongs to another owner" }
        check(db.version >= LAUNCH_SCHEMA)
        transition(db, owner, LaunchState.PREPARED, LaunchState.FACTORY_ENTERED)
        update(db, binding, owners)
    }

    fun cancelUnentered(db: SQLiteDatabase, binding: String, runtimeId: String): Boolean {
        check(db.inTransaction())
        val owners = read(db, binding)
        if (db.version < LAUNCH_SCHEMA) return false
        val states = launchStates(db)
        val cancelled = owners.filter { it.runtimeId != runtimeId && states[it.id] == LaunchState.PREPARED }
        for (owner in cancelled) transition(db, owner, LaunchState.PREPARED, LaunchState.CANCELLED_NO_START)
        if (cancelled.isNotEmpty()) update(db, binding, owners)
        return cancelled.isNotEmpty()
    }

    private fun transition(db: SQLiteDatabase, owner: Owner, before: LaunchState, after: LaunchState) {
        check(db.inTransaction())
        db.execSQL("UPDATE process_owners SET launch_state=? WHERE id=? AND runtime_id=? AND launch_state=?",
            arrayOf(after.name, owner.id, owner.runtimeId, before.name))
        db.rawQuery("SELECT changes()", null).use { check(it.moveToFirst() && it.getLong(0) == 1L) {
            "Process launch permission was already consumed or durably cancelled"
        } }
    }

    fun read(db: SQLiteDatabase, binding: String): List<Owner> {
        val withBoot = db.version >= BOOT_SCHEMA
        val (expected, hash) = db.rawQuery("SELECT id,expected_count,CASE WHEN typeof(members_digest)='text' AND length(members_digest)=64 THEN members_digest END FROM process_custody", null).use { row ->
            check(row.moveToFirst() && integer(row, 0) == 1L)
            val count = integer(row, 1); check(count in 0..MAX_OWNERS.toLong())
            val value = text(row, 2); check(!row.moveToNext())
            count to value
        }
        val bootColumns = if (withBoot) ",CASE WHEN device_identity IS NULL THEN NULL WHEN typeof(device_identity)='text' AND length(device_identity)=64 THEN device_identity ELSE '' END," +
            "CASE WHEN boot_id IS NULL THEN NULL WHEN typeof(boot_id)='text' AND length(boot_id)=36 THEN boot_id ELSE '' END" else ""
        val owners = db.rawQuery("SELECT CASE WHEN typeof(id)='text' AND length(id)=36 THEN id END,CASE WHEN typeof(runtime_id)='text' AND length(runtime_id)=36 THEN runtime_id END$bootColumns FROM process_owners ORDER BY id", null).use { rows ->
            buildList { while (rows.moveToNext()) {
                check(size < MAX_OWNERS) { "Unconfirmed process ownership exceeds its limit" }
                val boot = if (!withBoot || rows.isNull(2) && rows.isNull(3)) null
                    else WorkspaceProcessBootIdentity.Identity(text(rows, 2), text(rows, 3))
                add(Owner(uuid(text(rows, 0)), uuid(text(rows, 1)), boot))
            } }
        }
        val states = if (db.version >= LAUNCH_SCHEMA) launchStates(db) else null
        check(states == null || states.keys == owners.mapTo(mutableSetOf()) { it.id })
        check(owners.size.toLong() == expected && digest(binding, owners, withBoot, states) == hash) {
            "Process custody membership is missing or changed; cleanup remains unconfirmed"
        }
        return owners
    }

    fun append(db: SQLiteDatabase, binding: String, owners: List<Owner>, owner: Owner) {
        check(db.inTransaction()); uuid(owner.id); uuid(owner.runtimeId)
        check(owners.size < MAX_OWNERS) { "Too many unconfirmed CLI writers; cleanup is required before launching another process" }
        check(owners.none { it.id == owner.id })
        check(owners.all { it.runtimeId == owner.runtimeId }) {
            "CLI cleanup from an earlier runtime is unconfirmed; existing writers and retained data require recovery before another launch"
        }
        check(db.version >= LAUNCH_SCHEMA)
        db.execSQL("INSERT INTO process_owners(id,runtime_id,device_identity,boot_id,launch_state) VALUES(?,?,?,?,?)",
            arrayOf(owner.id, owner.runtimeId, owner.boot?.device, owner.boot?.boot, LaunchState.PREPARED.name))
        update(db, binding, (owners + owner).sortedBy { it.id })
    }

    fun withoutPreviousBoot(owners: List<Owner>, current: WorkspaceProcessBootIdentity.Identity?, runtimeId: String): List<Owner> {
        if (current == null) return owners
        return owners.filterNot { owner ->
            val recorded = owner.boot
            owner.runtimeId != runtimeId && recorded != null && recorded.device == current.device && recorded.boot != current.boot
        }
    }

    fun recordBootReconciliation(db: SQLiteDatabase, binding: String, before: List<Owner>, remaining: List<Owner>) {
        check(db.inTransaction() && remaining.all { it in before })
        if (before == remaining) return
        for (owner in before) if (owner !in remaining) {
            db.execSQL("DELETE FROM process_owners WHERE id=? AND runtime_id=?", arrayOf(owner.id, owner.runtimeId))
        }
        update(db, binding, remaining)
    }

    fun confirm(db: SQLiteDatabase, binding: String, owners: List<Owner>, owner: Owner) {
        check(db.inTransaction())
        // Retrying after a committed confirmation lost its response is safe. The caller still holds the
        // original exact-process receipt, and IDs are never reused.
        val existing = owners.find { it.id == owner.id } ?: return
        check(existing == owner) { "Process cleanup belongs to a different owner" }
        db.execSQL("DELETE FROM process_owners WHERE id=? AND runtime_id=?", arrayOf(owner.id, owner.runtimeId))
        update(db, binding, owners.filterNot { it.id == owner.id })
    }

    private fun update(db: SQLiteDatabase, binding: String, owners: List<Owner>) {
        db.execSQL("UPDATE process_custody SET expected_count=?,members_digest=? WHERE id=1",
            arrayOf<Any>(owners.size, digest(binding, owners, db.version >= BOOT_SCHEMA,
                if (db.version >= LAUNCH_SCHEMA) launchStates(db) else null)))
    }
    private fun digest(binding: String, owners: List<Owner>, withBoot: Boolean, states: Map<String, LaunchState>? = null): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun part(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }

        if (states != null) part("DROIDE-PROCESS-LAUNCH-CUSTODY-6")
        part(binding); owners.forEach {
            part(it.id); part(it.runtimeId)
            if (withBoot) { part(it.boot?.device.orEmpty()); part(it.boot?.boot.orEmpty()) }
            if (states != null) part(checkNotNull(states[it.id]).name)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun uuid(value: String) = value.also { check(UUID.fromString(it).toString() == it) }
    private fun text(row: Cursor, index: Int): String { check(row.getType(index) == Cursor.FIELD_TYPE_STRING); return row.getString(index) }
    private fun integer(row: Cursor, index: Int): Long { check(row.getType(index) == Cursor.FIELD_TYPE_INTEGER); return row.getLong(index) }
}

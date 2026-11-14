package com.baystudio.droide.core

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Lazy framework SQLite, indexed metadata, append-only message parts, and no deleting recovery. */
class SqlitePluginConversationHistory(
    private val workspace: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : PluginConversationHistory, PluginHistoryCheckpointStore {
    private companion object {
        const val MAX_PART_CODE_UNITS = 16_384
        const val MAX_PART_BYTES = MAX_PART_CODE_UNITS * 2
        const val MAX_TITLE_CODE_UNITS = 120
    }

    internal var lifecycleCheckpoint: (String) -> Unit = {}
    private val mutex = Mutex()
    private var database: SQLiteDatabase? = null
    // Raw capture stays blocked for the connection lifetime. Coordinated export
    // checkpoints under this owner's mutex without closing the history session.
    private var databaseWriter: AutoCloseable? = null
    private var closed = false
    private val ownerId = UUID.randomUUID().toString()
    private val mediaStore by lazy { ChatMediaStore(workspace) }
    private val mediaJson = Json { ignoreUnknownKeys = true }

    private fun validatePaths(): File {
        for (relative in listOf(".droide", ".droide/sessions", ".droide/sessions/plugins.sqlite")) {
            require(!Files.isSymbolicLink(File(workspace, relative).toPath())) { "Plugin history path is a symbolic link; existing data was preserved" }
        }
        val directory = PathSecurity.resolveWithin(workspace, ".droide/sessions")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create plugin history directory" }
        val target = PathSecurity.resolveWithin(workspace, ".droide/sessions/plugins.sqlite")
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val path = File(target.path + suffix).toPath()
            require(!Files.isSymbolicLink(path)) { "Plugin history path is a symbolic link; existing data was preserved" }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid plugin history file; existing data was preserved" }
            }
        }
        return target
    }

    private fun db(): SQLiteDatabase {
        check(!closed) { "Plugin history is closed" }
        val target = validatePaths()
        database?.takeIf { it.isOpen }?.let { return it }
        val preservingHandler = DatabaseErrorHandler {
            throw SQLiteDatabaseCorruptException("Saved plugin history is unreadable; existing data was preserved")
        }
        val opened = SQLiteDatabase.openDatabase(
            target.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            preservingHandler,
        )
        try {
            require(opened.version in 0..6) { "Unsupported plugin history schema; existing data was preserved" }
            opened.setForeignKeyConstraintsEnabled(true)
            opened.execSQL("PRAGMA synchronous=FULL")
            if (opened.version == 0) transaction(opened) {
                opened.execSQL("""CREATE TABLE conversations (
                    id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, title TEXT NOT NULL,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, native_id TEXT,
                    mode TEXT NOT NULL, model_id TEXT, message_count INTEGER NOT NULL DEFAULT 0,
                    part_count INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, owner_id TEXT NOT NULL)""")
                opened.execSQL("CREATE INDEX conversation_time ON conversations(updated_at DESC,id DESC)")
                opened.execSQL("CREATE INDEX conversation_agent_time ON conversations(agent_id,updated_at DESC,id DESC)")
                opened.execSQL("""CREATE TABLE message_parts (
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL, logical_ordinal INTEGER NOT NULL,
                    role TEXT NOT NULL, content TEXT NOT NULL,
                    PRIMARY KEY(conversation_id,ordinal))""")
                opened.execSQL("""CREATE TABLE live_parts (
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL, content TEXT NOT NULL,
                    PRIMARY KEY(conversation_id,ordinal))""")
                opened.execSQL("""CREATE TABLE active_conversations (
                    agent_id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE)""")
                opened.version = 1
            }
            if (opened.version == 1) transaction(opened) {
                opened.execSQL("""CREATE TABLE message_media (
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    logical_ordinal INTEGER NOT NULL, refs TEXT NOT NULL,
                    PRIMARY KEY(conversation_id,logical_ordinal))""")
                opened.version = 2
            }
            if (opened.version == 2) transaction(opened) {
                fun addColumn(table: String, name: String) {
                    val columns = opened.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                    }
                    if (name !in columns) opened.execSQL("ALTER TABLE $table ADD COLUMN $name TEXT")
                }
                addColumn("live_parts", "activity_id")
                addColumn("live_parts", "activity")
                addColumn("message_parts", "activity")
                opened.execSQL("CREATE INDEX IF NOT EXISTS live_activity_id ON live_parts(conversation_id,activity_id)")
                opened.version = 3
            }
            if (opened.version == 3) transaction(opened) {
                // Legacy TEXT stays untouched. New rows preserve UTF-16 code units,
                // including a surrogate pair split across durable stream checkpoints.
                opened.execSQL("ALTER TABLE live_parts ADD COLUMN content_utf16 BLOB")
                opened.execSQL("ALTER TABLE message_parts ADD COLUMN content_utf16 BLOB")
                opened.version = 4
            }
            if (opened.version == 4) transaction(opened) {
                // Version 5 marks deferred checkpoint promotion after owner-authored
                // retirement. Older binaries must reject before their next turn can
                // overlook and discard the retained non-RUNNING live_parts.
                opened.version = 5
                lifecycleCheckpoint("before-retirement-version-commit")
            }
            if (opened.version == 5) transaction(opened) {
                // One generation and receipt per conversation. Superseding this metadata
                // never removes transcript, live checkpoint or media evidence.
                opened.execSQL("ALTER TABLE conversations ADD COLUMN turn_id TEXT")
                opened.execSQL("ALTER TABLE conversations ADD COLUMN finish_digest TEXT")
                opened.execSQL("ALTER TABLE conversations ADD COLUMN finish_message_count INTEGER")
                opened.execSQL("ALTER TABLE conversations ADD COLUMN finish_part_count INTEGER")
                opened.execSQL("ALTER TABLE conversations ADD COLUMN finish_updated_at INTEGER")
                opened.version = 6
                lifecycleCheckpoint("before-finish-schema-commit")
            }
            // The connection can write its database and sidecars, not arbitrary
            // project files. Individual operations retain their broader fence.
            val identity = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
            databaseWriter = ExportWriterAdmission.beginManagedWriter(File(workspace, ".droide/sessions"),
                "Open plugin conversation database", object : ExportCaptureParticipant {
                    override suspend fun <T> withQuiescence(action: suspend () -> T): T =
                        withExportQuiescence(opened, target, identity, action)
                })
            database = opened
            return opened
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }

    /** Keep the same live session, but hold all history operations until capture is sealed. */
    private suspend fun <T> withExportQuiescence(connection: SQLiteDatabase, target: File, identity: Any?,
        action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed && database === connection && connection.isOpen) { "Chat history owner changed; retry export" }
            check(validatePaths() == target && identity != null &&
                Files.readAttributes(target.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == identity) {
                "Chat history file changed; export was stopped without replacing it"
            }
            connection.rawQuery("SELECT 1 FROM conversations WHERE status=? AND owner_id=? LIMIT 1",
                arrayOf(PluginHistoryStatus.RUNNING.name, ownerId)).use {
                check(!it.moveToFirst()) { "An external Agent turn is active; stop or finish it before exporting chat history" }
            }
            // Checkpoint every committed WAL page before reading the main file.
            // A busy reader/writer is an explicit refusal, never a partial snapshot.
            connection.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                check(it.moveToFirst() && it.getInt(0) == 0 && it.getLong(1) == it.getLong(2)) {
                    "Chat history is busy; retry export after its current transaction finishes"
                }
            }
            action()
        }
    }

    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T =
        ExportWriterAdmission.withSuspendingWriter(workspace, "Plugin conversation database access") {
            withContext(Dispatchers.IO) { mutex.withLock { block(db()) } }
        }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    private fun validateId(id: String) {
        require(Regex("p-[0-9a-fA-F-]{36}").matches(id)) { "Invalid local plugin conversation ID" }
    }

    private fun validateAgent(agentId: String) {
        require(Regex("[a-zA-Z0-9._-]{1,128}").matches(agentId)) { "Invalid plugin identity" }
    }

    private fun Cursor.summary(): PluginHistorySummary = PluginHistorySummary(
        id = getString(getColumnIndexOrThrow("id")), agentId = getString(getColumnIndexOrThrow("agent_id")),
        title = getString(getColumnIndexOrThrow("title")), createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        nativeSessionId = getColumnIndexOrThrow("native_id").let { if (isNull(it)) null else getString(it) },
        mode = WorkspaceAgentMode.valueOf(getString(getColumnIndexOrThrow("mode"))),
        modelId = getColumnIndexOrThrow("model_id").let { if (isNull(it)) null else getString(it) },
        messageCount = getInt(getColumnIndexOrThrow("message_count")), partCount = getLong(getColumnIndexOrThrow("part_count")),
        status = PluginHistoryStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
    )

    private fun summary(db: SQLiteDatabase, id: String): PluginHistorySummary? =
        db.rawQuery("SELECT * FROM conversations WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.summary() else null
        }

    override suspend fun listPage(agentId: String?, query: String, before: PluginHistoryCursor?, limit: Int): PluginHistoryPage = access { db ->
        agentId?.let(::validateAgent)
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (agentId != null) { clauses += "agent_id=?"; args += agentId }
        if (query.isNotBlank()) {
            clauses += "title LIKE ? ESCAPE '\\'"
            args += "%" + query.take(200).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        if (before != null) {
            validateId(before.id)
            clauses += "(updated_at<? OR (updated_at=? AND id<?))"
            args += before.updatedAt.toString(); args += before.updatedAt.toString(); args += before.id
        }
        val pageSize = limit.coerceIn(1, 80)
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val entries = db.rawQuery("SELECT * FROM conversations$where ORDER BY updated_at DESC,id DESC LIMIT ${pageSize + 1}", args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.summary()) }
        }
        val page = entries.take(pageSize)
        PluginHistoryPage(page, page.lastOrNull()?.takeIf { entries.size > pageSize }?.let { PluginHistoryCursor(it.updatedAt, it.id) })
    }

    private fun activate(db: SQLiteDatabase, entry: PluginHistorySummary) {
        db.insertWithOnConflict("active_conversations", null, ContentValues().apply {
            put("agent_id", entry.agentId); put("conversation_id", entry.id)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Cannot save active plugin conversation" } }
    }

    override suspend fun latest(agentId: String): PluginHistoryWindow? = access { db ->
        validateAgent(agentId)
        val id = db.rawQuery("SELECT conversation_id FROM active_conversations WHERE agent_id=?", arrayOf(agentId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: db.rawQuery("SELECT id FROM conversations WHERE agent_id=? ORDER BY updated_at DESC,id DESC LIMIT 1", arrayOf(agentId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        id?.let { restore(db, it, null) }
    }

    override suspend fun load(id: String, beforePart: Long?): PluginHistoryWindow? = access { db ->
        validateId(id)
        require(beforePart == null || beforePart >= 0) { "Invalid transcript cursor" }
        if (summary(db, id) == null) null else restore(db, id, beforePart)
    }

    private fun restore(db: SQLiteDatabase, id: String, beforePart: Long?): PluginHistoryWindow = transaction(db) {
        val entry = checkNotNull(summary(db, id)) { "Saved plugin conversation is unavailable" }
        if (entry.status == PluginHistoryStatus.RUNNING) {
            check(!owns(db, id)) { "This saved turn has not finished. Finish it or use Stop before opening it." }
            return@transaction window(db, id, beforePart,
                "This saved turn is still active or its owner did not close cleanly. The original is preserved. Use New Session to start independent work without the old CLI context.")
        }
        // A non-RUNNING status is durable owner-authored retirement. Validate and
        // promote its retained checkpoints atomically; malformed evidence survives.
        if (hasLiveParts(db, id)) movePartialToMessages(db, id, entry.status)
        activate(db, checkNotNull(summary(db, id)))
        window(db, id, beforePart)
    }

    private data class Part(val ordinal: Long, val logical: Int?, val role: String, val text: String, val activity: String?)

    private fun window(db: SQLiteDatabase, id: String, beforePart: Long? = null, readOnlyReason: String? = null): PluginHistoryWindow {
        val saved = checkNotNull(summary(db, id)) { "Saved plugin conversation is unavailable" }
        val liveCount = if (readOnlyReason == null) 0L else db.rawQuery(
            "SELECT COALESCE(MAX(ordinal)+1,0) FROM live_parts WHERE conversation_id=?", arrayOf(id)).use { it.moveToFirst(); it.getLong(0) }
        val entry = saved.copy(partCount = Math.addExact(saved.partCount, liveCount))
        val end = (beforePart ?: entry.partCount).coerceAtMost(entry.partCount)
        var chars = 0
        val descending = mutableListOf<Part>()
        var budgetReached = false
        fun read(query: String, args: Array<String>) {
            db.rawQuery(query, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val text = cursor.historyText()
                    val activity = cursor.getString(3)
                    val size = text.length.toLong() + (activity?.length ?: 0)
                    if (descending.isNotEmpty() && chars + size > 512_000) { budgetReached = true; break }
                    chars += size.toInt()
                    descending += Part(cursor.getLong(0), if (cursor.isNull(1)) null else cursor.getInt(1), cursor.getString(2), text, activity)
                }
            }
        }
        // Each range is indexed and bounded before decoding. Do not materialize or
        // sort the full transcript to present a read-only checkpoint suffix.
        if (readOnlyReason != null && end > saved.partCount) {
            read("SELECT ${saved.partCount}+ordinal,NULL,'assistant',activity,$historyTextProjection FROM live_parts WHERE conversation_id=? AND ordinal<? ORDER BY ordinal DESC LIMIT 80",
                arrayOf(id, (end - saved.partCount).toString()))
        }
        if (!budgetReached && descending.size < 80) {
            read("SELECT ordinal,logical_ordinal,role,activity,$historyTextProjection FROM message_parts WHERE conversation_id=? AND ordinal<? ORDER BY ordinal DESC LIMIT ${80 - descending.size}",
                arrayOf(id, minOf(end, saved.partCount).toString()))
        }
        val parts = descending.asReversed()
        val messages = mutableListOf<AgentMessage>()
        val keys = mutableListOf<Long>()
        var currentLogical: Int? = null
        var currentRole = ""
        var currentActivity: AgentStreamPart? = null
        var text = StringBuilder()
        val logicalStart = parts.firstOrNull { it.logical != null }?.logical ?: 0
        val logicalEnd = parts.lastOrNull { it.logical != null }?.logical ?: -1
        val mediaByLogical = db.rawQuery("SELECT logical_ordinal,refs FROM message_media WHERE conversation_id=? AND logical_ordinal>=? AND logical_ordinal<=?",
            arrayOf(id, logicalStart.toString(), logicalEnd.toString())).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getInt(0), mediaJson.decodeFromString(ListSerializer(ChatMediaRef.serializer()), cursor.getString(1)))
            }
        }
        fun flush() {
            currentLogical?.let { logical -> messages += AgentMessage(currentRole, text.toString(), streamPart = currentActivity, media = mediaByLogical[logical].orEmpty()) }
            text = StringBuilder()
        }
        var liveLogical = saved.messageCount - 1
        var previousLiveText = false
        for (part in parts) {
            val logical = part.logical ?: run {
                if (part.activity != null || !previousLiveText) liveLogical++
                liveLogical
            }
            if (logical != currentLogical) {
                flush(); currentLogical = logical; currentRole = part.role; keys += part.ordinal
                currentActivity = part.activity?.let { mediaJson.decodeFromString(AgentStreamPart.serializer(), it) }
            }
            text.append(part.text)
            previousLiveText = part.logical == null && part.activity == null
        }
        flush()
        return PluginHistoryWindow(entry, messages, keys, parts.firstOrNull()?.ordinal ?: end, parts.lastOrNull()?.ordinal?.plus(1) ?: end, readOnlyReason)
    }

    /** SQLite TEXT conversion cannot preserve isolated UTF-16 code units in a stream delta.
     * Store code units directly, not via a Charset encoder (which replaces unpaired surrogates).
     * The nullable column distinguishes new payloads from unchanged legacy TEXT rows.
     */
    private fun ContentValues.putHistoryText(text: String) {
        require(text.length <= MAX_PART_CODE_UNITS) { "Plugin history part exceeds storage budget" }
        val bytes = ByteArray(text.length * 2)
        text.forEachIndexed { index, unit ->
            bytes[index * 2] = unit.code.toByte()
            bytes[index * 2 + 1] = (unit.code ushr 8).toByte()
        }
        put("content", "")
        put("content_utf16", bytes)
    }

    // Reject malformed storage without projecting an unbounded BLOB into CursorWindow
    // or allocating it through getBlob. Type/length distinguish rejection from legacy NULL.
    private val historyTextProjection = """CASE WHEN content_utf16 IS NULL THEN content ELSE NULL END AS content,
        (typeof(content)='text' AND content COLLATE BINARY = '') AS history_text_legacy_empty,
        typeof(content_utf16) AS history_text_kind,
        length(content_utf16) AS history_text_bytes,
        CASE WHEN typeof(content_utf16)='blob' AND length(content_utf16)<=$MAX_PART_BYTES
            AND length(content_utf16)%2=0 AND typeof(content)='text' AND content COLLATE BINARY = ''
            THEN content_utf16 ELSE NULL END AS history_text_payload"""

    private fun Cursor.historyText(): String {
        val kind = getString(getColumnIndexOrThrow("history_text_kind"))
        if (kind == "null") return getString(getColumnIndexOrThrow("content"))
        val size = getLong(getColumnIndexOrThrow("history_text_bytes"))
        require(kind == "blob" && size in 0..MAX_PART_BYTES.toLong() && size % 2 == 0L &&
            getInt(getColumnIndexOrThrow("history_text_legacy_empty")) == 1) {
            "Invalid plugin history text payload; existing data was preserved"
        }
        val bytes = getBlob(getColumnIndexOrThrow("history_text_payload"))
        check(bytes.size.toLong() == size) { "Plugin history text payload changed; existing data was preserved" }
        return CharArray(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 255) or ((bytes[index * 2 + 1].toInt() and 255) shl 8)).toChar()
        }.concatToString()
    }

    /** Keep complete pairs together within each bounded presentation part when possible. */
    private inline fun parts(text: String, block: (String) -> Unit) {
        if (text.isEmpty()) { block(""); return }
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + MAX_PART_CODE_UNITS, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            block(text.substring(offset, end)); offset = end
        }
    }

    private fun appendMessage(db: SQLiteDatabase, id: String, role: String, content: String, media: List<ChatMediaRef> = emptyList()) {
        val entry = checkNotNull(summary(db, id))
        var ordinal = entry.partCount
        parts(content) { part ->
            db.insertOrThrow("message_parts", null, ContentValues().apply {
                put("conversation_id", id); put("ordinal", ordinal++); put("logical_ordinal", entry.messageCount)
                put("role", role); putHistoryText(part)
            })
        }
        db.execSQL("UPDATE conversations SET message_count=?,part_count=? WHERE id=?", arrayOf<Any?>(entry.messageCount + 1, ordinal, id))
        saveMedia(db, id, entry.messageCount, media)
    }

    private fun saveMedia(db: SQLiteDatabase, id: String, logical: Int, media: List<ChatMediaRef>) {
        if (media.isEmpty()) return
        require(media.size <= 4) { "Too many plugin media items" }
        db.insertOrThrow("message_media", null, ContentValues().apply {
            put("conversation_id", id); put("logical_ordinal", logical)
            put("refs", mediaJson.encodeToString(ListSerializer(ChatMediaRef.serializer()), media))
        })
    }

    override suspend fun beginTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode, nativeSessionId: String?): PluginHistoryWindow =
        beginInternal(id, agentId, prompt, mode, nativeSessionId, emptyList()).window

    override suspend fun beginMediaTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                      nativeSessionId: String?, media: List<ChatMediaRef>): PluginHistoryWindow =
        if (media.isEmpty()) beginTurn(id, agentId, prompt, mode, nativeSessionId) else beginInternal(id, agentId, prompt, mode, nativeSessionId, media).window

    override suspend fun beginCheckpointTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                            nativeSessionId: String?, media: List<ChatMediaRef>): PluginHistoryTurnAdmission =
        beginInternal(id, agentId, prompt, mode, nativeSessionId, media)

    private fun titlePrefix(prompt: String): String {
        val line = prompt.trim().lineSequence().first()
        var end = minOf(MAX_TITLE_CODE_UNITS, line.length)
        if (end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
        return line.substring(0, end)
    }

    private suspend fun beginInternal(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                      nativeSessionId: String?, media: List<ChatMediaRef>): PluginHistoryTurnAdmission = access { db ->
        validateAgent(agentId)
        require(prompt.isNotBlank() && prompt.length <= 200_000) { "Plugin prompt is empty or too large" }
        require(nativeSessionId == null || nativeSessionId.isNotBlank() && nativeSessionId.length <= 512) { "Invalid native plugin session ID" }
        transaction(db) {
            val localId = id ?: PluginConversationHistory.newId()
            val turnId = UUID.randomUUID().toString()
            validateId(localId)
            val existing = summary(db, localId)
            if (existing == null) {
                require(id == null) { "Saved plugin conversation is unavailable" }
                val now = clock()
                db.insertOrThrow("conversations", null, ContentValues().apply {
                    put("id", localId); put("agent_id", agentId); put("title", titlePrefix(prompt))
                    put("created_at", now); put("updated_at", now); put("native_id", nativeSessionId)
                    put("mode", mode.name); put("status", PluginHistoryStatus.RUNNING.name)
                    put("owner_id", ownerId)
                })
            } else {
                require(existing.agentId == agentId) { "Plugin conversation belongs to another Agent" }
                require(existing.status != PluginHistoryStatus.RUNNING) { "Plugin conversation is already running" }
                if (hasLiveParts(db, localId)) movePartialToMessages(db, localId, existing.status)
                db.execSQL("UPDATE conversations SET updated_at=?,mode=?,status=?,owner_id=? WHERE id=?", arrayOf<Any?>(maxOf(clock(), existing.updatedAt), mode.name, PluginHistoryStatus.RUNNING.name, ownerId, localId))
            }
            db.update("conversations", ContentValues().apply {
                put("turn_id", turnId); putNull("finish_digest"); putNull("finish_message_count")
                putNull("finish_part_count"); putNull("finish_updated_at")
            }, "id=?", arrayOf(localId))
            appendMessage(db, localId, "user", prompt, media)
            lifecycleCheckpoint("before-turn-commit")
            activate(db, checkNotNull(summary(db, localId)))
            PluginHistoryTurnAdmission(window(db, localId), PluginHistoryTurnIdentity(localId, turnId))
        }
    }

    /** Run under the operation's transaction: a separate preflight check would race. */
    private fun requireTurnIdentity(db: SQLiteDatabase, identity: PluginHistoryTurnIdentity) {
        validateId(identity.conversationId)
        require(Regex("[0-9a-fA-F-]{36}").matches(identity.turnId)) { "Invalid plugin turn identity" }
        db.rawQuery("SELECT owner_id,turn_id FROM conversations WHERE id=?", arrayOf(identity.conversationId)).use {
            require(it.moveToFirst() && it.getString(0) == ownerId && it.getString(1) == identity.turnId) {
                "Plugin turn ownership or generation changed; existing history was preserved"
            }
        }
    }

    override suspend fun bind(id: String, nativeSessionId: String, modelId: String?): Unit =
        bindInternal(id, nativeSessionId, modelId, null)

    override suspend fun bindCheckpoint(identity: PluginHistoryTurnIdentity, nativeSessionId: String, modelId: String?): Unit =
        bindInternal(identity.conversationId, nativeSessionId, modelId, identity)

    private suspend fun bindInternal(id: String, nativeSessionId: String, modelId: String?, identity: PluginHistoryTurnIdentity?): Unit = access { db ->
        validateId(id)
        require(nativeSessionId.isNotBlank() && nativeSessionId.length <= 512) { "Invalid native plugin session ID" }
        require(modelId == null || modelId.length <= 512) { "Invalid plugin model ID" }
        transaction(db) {
            val entry = checkNotNull(summary(db, id)) { "Saved plugin conversation is unavailable" }
            if (identity != null) {
                requireTurnIdentity(db, identity)
                require(entry.status == PluginHistoryStatus.RUNNING) { "Plugin turn is already finalized" }
            }
            check(entry.status != PluginHistoryStatus.RUNNING || owns(db, id)) { "Cannot change another active plugin conversation; existing history was preserved" }
            db.update("conversations", ContentValues().apply { put("native_id", nativeSessionId); if (modelId != null) put("model_id", modelId) }, "id=?", arrayOf(id))
        }
        Unit
    }

    override suspend fun appendPartial(id: String, fragments: List<String>): Unit = appendPartialInternal(id, fragments, null)

    override suspend fun appendCheckpointPartial(identity: PluginHistoryTurnIdentity, fragments: List<String>): Unit =
        appendPartialInternal(identity.conversationId, fragments, identity)

    private suspend fun appendPartialInternal(id: String, fragments: List<String>, identity: PluginHistoryTurnIdentity?): Unit = access { db ->
        validateId(id)
        if (fragments.isEmpty()) return@access
        transaction(db) {
            identity?.let { requireTurnIdentity(db, it) }
            val entry = checkNotNull(summary(db, id))
            require(entry.status == PluginHistoryStatus.RUNNING) { "Plugin turn no longer owns its output checkpoint" }
            require(owns(db, id)) { "Plugin turn ownership changed; existing history was preserved" }
            var ordinal = db.rawQuery("SELECT COALESCE(MAX(ordinal)+1,0) FROM live_parts WHERE conversation_id=?", arrayOf(id)).use { it.moveToFirst(); it.getLong(0) }
            parts(fragments.joinToString("")) { part ->
                db.insertOrThrow("live_parts", null, ContentValues().apply { put("conversation_id", id); put("ordinal", ordinal++); putHistoryText(part) })
            }
        }
    }

    override suspend fun appendTranscript(id: String, updates: List<ExternalTranscriptUpdate>): Unit = appendTranscriptInternal(id, updates, null)

    override suspend fun appendCheckpointTranscript(identity: PluginHistoryTurnIdentity, updates: List<ExternalTranscriptUpdate>): Unit =
        appendTranscriptInternal(identity.conversationId, updates, identity)

    private suspend fun appendTranscriptInternal(id: String, updates: List<ExternalTranscriptUpdate>, identity: PluginHistoryTurnIdentity?): Unit = access { db ->
        validateId(id)
        if (updates.isEmpty()) return@access
        transaction(db) {
            identity?.let { requireTurnIdentity(db, it) }
            require(summary(db, id)?.status == PluginHistoryStatus.RUNNING && owns(db, id)) { "Plugin turn no longer owns its activity checkpoint" }
            var ordinal = db.rawQuery("SELECT COALESCE(MAX(ordinal)+1,0) FROM live_parts WHERE conversation_id=?", arrayOf(id)).use { it.moveToFirst(); it.getLong(0) }
            for (update in updates) {
                val part = update.part
                require(part.id.isNotBlank() && part.id.length <= 512)
                if (update.textDelta != null) {
                    parts(update.textDelta) { fragment ->
                        db.insertOrThrow("live_parts", null, ContentValues().apply {
                            put("conversation_id", id); put("ordinal", ordinal++); putHistoryText(fragment); put("activity_id", part.id)
                        })
                    }
                } else {
                    val previous = db.rawQuery("SELECT activity FROM live_parts WHERE conversation_id=? AND activity_id=? AND activity IS NOT NULL LIMIT 1", arrayOf(id, part.id)).use { cursor ->
                        if (cursor.moveToFirst()) mediaJson.decodeFromString(AgentStreamPart.serializer(), cursor.getString(0)) else null
                    }
                    val merged = if (previous != null && update.patch != null) update.patch.applyTo(previous, update.plan) else part
                    val payload = mediaJson.encodeToString(AgentStreamPart.serializer(), merged)
                    // JSON escaping can use six characters per bounded preview character.
                    require(payload.length <= 1_000_000) { "Activity preview exceeds storage budget" }
                    val values = ContentValues().apply { putHistoryText(""); put("activity", payload) }
                    val updated = db.update("live_parts", values, "conversation_id=? AND activity_id=? AND activity IS NOT NULL", arrayOf(id, part.id))
                    if (updated == 0) {
                        values.put("conversation_id", id); values.put("ordinal", ordinal++); values.put("activity_id", part.id)
                        db.insertOrThrow("live_parts", null, values)
                    }
                }
            }
        }
    }

    private fun hasLiveParts(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM live_parts WHERE conversation_id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    private fun hasLiveActivity(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM live_parts WHERE conversation_id=? AND activity IS NOT NULL LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    /** Compare the final reply against the streamed suffix without loading the entire transcript. */
    private fun replyWasStreamed(db: SQLiteDatabase, id: String, reply: String): Boolean {
        val query = "SELECT $historyTextProjection FROM live_parts WHERE conversation_id=? AND activity IS NULL ORDER BY ordinal"
        val length = db.rawQuery(query, arrayOf(id)).use { cursor ->
            var count = 0L
            while (cursor.moveToNext()) count += cursor.historyText().length
            count
        }
        if (length < reply.length) return false
        var skip = length - reply.length
        var offset = 0
        db.rawQuery(query, arrayOf(id)).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.historyText()
                val start = minOf(skip, text.length.toLong()).toInt()
                skip -= start
                val size = text.length - start
                if (!reply.regionMatches(offset, text, start, size)) return false
                offset += size
            }
        }
        return offset == reply.length
    }

    private fun movePartialToMessages(db: SQLiteDatabase, id: String, status: PluginHistoryStatus) {
        val entry = checkNotNull(summary(db, id))
        var ordinal = entry.partCount
        var logical = entry.messageCount - 1
        var previousText = false
        db.rawQuery("SELECT activity_id,activity,$historyTextProjection FROM live_parts WHERE conversation_id=? ORDER BY ordinal", arrayOf(id)).use { cursor ->
            while (cursor.moveToNext()) {
                val activity = cursor.getString(1)?.let {
                    mediaJson.encodeToString(AgentStreamPart.serializer(), externalFinishedPart(mediaJson.decodeFromString(AgentStreamPart.serializer(), it), status))
                }
                if (activity != null || !previousText) logical++
                db.insertOrThrow("message_parts", null, ContentValues().apply {
                    put("conversation_id", id); put("ordinal", ordinal++); put("logical_ordinal", logical)
                    put("role", "assistant"); putHistoryText(cursor.historyText()); put("activity", activity)
                })
                previousText = activity == null
            }
        }
        db.execSQL("UPDATE conversations SET message_count=?,part_count=? WHERE id=?", arrayOf<Any?>(logical + 1, ordinal, id))
        db.delete("live_parts", "conversation_id=?", arrayOf(id))
    }

    override suspend fun finish(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus): PluginHistoryWindow =
        finishInternal(id, reply, notices, status, emptyList())

    override suspend fun finishMediaTurn(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus,
                                        media: List<ChatMediaRef>): PluginHistoryWindow =
        if (media.isEmpty()) finish(id, reply, notices, status) else finishInternal(id, reply, notices, status, media)

    override suspend fun finishCheckpoint(request: PluginHistoryFinishRequest): PluginHistoryWindow {
        val retained = request.copy(notices = request.notices.toList(), media = request.media.toList())
        return finishInternal(retained.identity.conversationId, retained.reply, retained.notices, retained.status,
            retained.media, retained.identity, pluginHistoryFinishFingerprint(retained))
    }

    private suspend fun finishInternal(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus,
                                       media: List<ChatMediaRef>, identity: PluginHistoryTurnIdentity? = null,
                                       fingerprint: String? = null): PluginHistoryWindow = access { db ->
        validateId(id)
        require(status != PluginHistoryStatus.RUNNING) { "Invalid final plugin history state" }
        val saved = transaction(db) {
            val entry = checkNotNull(summary(db, id))
            if (identity != null) {
                requireTurnIdentity(db, identity)
                if (entry.status != PluginHistoryStatus.RUNNING) {
                    db.rawQuery("SELECT finish_digest,finish_message_count,finish_part_count,finish_updated_at FROM conversations WHERE id=?", arrayOf(id)).use { receipt ->
                        require(receipt.moveToFirst() && !receipt.isNull(0) && receipt.getString(0) == fingerprint &&
                            !receipt.isNull(1) && receipt.getInt(1) == entry.messageCount &&
                            !receipt.isNull(2) && receipt.getLong(2) == entry.partCount &&
                            !receipt.isNull(3) && receipt.getLong(3) == entry.updatedAt && entry.status == status && !hasLiveParts(db, id)) {
                            "Plugin finish receipt does not match this exact turn; existing history was preserved"
                        }
                    }
                    return@transaction window(db, id)
                }
            }
            require(entry.status == PluginHistoryStatus.RUNNING) { "Plugin turn is already finalized" }
            require(owns(db, id)) { "Plugin turn ownership changed; existing history was preserved" }
            if (hasLiveActivity(db, id)) {
                val appendReply = reply != null && !replyWasStreamed(db, id, reply)
                movePartialToMessages(db, id, status)
                if (appendReply) appendMessage(db, id, "assistant", checkNotNull(reply), media)
                else if (media.isNotEmpty()) saveMedia(db, id, checkNotNull(summary(db, id)).messageCount - 1, media)
            } else if (reply != null) {
                // A final reply supersedes partial output only after every retained
                // payload is readable. Malformed evidence must survive a failed finish.
                db.rawQuery("SELECT $historyTextProjection FROM live_parts WHERE conversation_id=? ORDER BY ordinal", arrayOf(id)).use { cursor ->
                    while (cursor.moveToNext()) cursor.historyText()
                }
                appendMessage(db, id, "assistant", reply, media)
                db.delete("live_parts", "conversation_id=?", arrayOf(id))
            } else {
                movePartialToMessages(db, id, status)
                if (media.isNotEmpty()) {
                    if (checkNotNull(summary(db, id)).messageCount == entry.messageCount) appendMessage(db, id, "assistant", "", media)
                    else saveMedia(db, id, entry.messageCount, media)
                }
            }
            for (notice in notices) appendMessage(db, id, "assistant", notice)
            db.execSQL("UPDATE conversations SET updated_at=?,status=? WHERE id=?", arrayOf<Any?>(maxOf(clock(), entry.updatedAt), status.name, id))
            if (identity != null) {
                val finalized = checkNotNull(summary(db, id))
                db.update("conversations", ContentValues().apply {
                    put("finish_digest", checkNotNull(fingerprint)); put("finish_message_count", finalized.messageCount)
                    put("finish_part_count", finalized.partCount); put("finish_updated_at", finalized.updatedAt)
                }, "id=?", arrayOf(id))
            }
            window(db, id).also { lifecycleCheckpoint("before-finish-commit") }
        }
        lifecycleCheckpoint("after-finish-commit")
        saved
    }

    override suspend fun delete(id: String): Unit = access { db ->
        validateId(id)
        val deleted = transaction(db) {
            val entry = summary(db, id) ?: return@transaction false
            require(entry.status != PluginHistoryStatus.RUNNING) { "Cannot delete an active or unverified plugin conversation; existing history was preserved" }
            db.delete("conversations", "id=?", arrayOf(id)) > 0
        }
        if (deleted) mediaStore.releaseDeletedOwner(id)
        Unit
    }

    override suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        ExportWriterAdmission.withWriterAfterCapture(workspace, "Close plugin conversation database") {
            mutex.withLock {
                // Admission closes permanently under the same mutex as every operation.
                // Retire only this exact owner's rows, never a foreign UUID. A retry uses
                // the retained connection even after admission has closed.
                closed = true
                var failure: Throwable? = null
                fun record(error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                }
                database?.let { connection ->
                    try {
                        lifecycleCheckpoint("before-retirement")
                        transaction(connection) {
                            // Retirement does not read, rewrite or discard checkpoint payloads.
                            // Even malformed evidence can be safely retained while closing.
                            connection.execSQL("UPDATE conversations SET status=? WHERE owner_id=? AND status=?",
                                arrayOf(PluginHistoryStatus.INTERRUPTED.name, ownerId, PluginHistoryStatus.RUNNING.name))
                            lifecycleCheckpoint("before-retirement-commit")
                        }
                    } catch (error: Throwable) { record(error) }
                    // A failed retirement must not strand a replaced controller's open
                    // connection forever. Its unchanged RUNNING rows remain read-only.
                    try {
                        lifecycleCheckpoint("before-database-close")
                        connection.close()
                        database = null
                        lifecycleCheckpoint("after-database-close")
                    } catch (error: Throwable) { record(error) }
                }
                if (database == null) {
                    try {
                        lifecycleCheckpoint("before-registration-close")
                        databaseWriter?.close()
                        databaseWriter = null
                    } catch (error: Throwable) { record(error) }
                }
                failure?.let { throw it }
            }
        }
    }

    private fun owns(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT owner_id FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst() && it.getString(0) == ownerId }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Owns the two histories with intentionally different lifecycles: - active: model-facing and compactable.


internal class AgentConversationHistory(private val ledger: SessionHistoryLedger? = null) {
    @Volatile var active = mutableListOf<JsonObject>()
        private set
    private class ExactState {
        val pages = mutableListOf<SessionHistoryPage>()
        val tail = mutableListOf<JsonObject>()
        var tailBytes = 0L
        var valid = true
        var storageFailure: Exception? = null
        val remainingTools = hashSetOf<String>()
    }
    private val ownerLock = Any()
    @Volatile private var state = ExactState()
    @Volatile private var loadGeneration = 0L
    private val appendMutex = Mutex()
    /** Null in production; suspension is outside both ownership and append locks. */
    @Volatile internal var checkpointBoundaryProbe: (suspend (Long) -> Unit)? = null
    @Volatile private var exact = true

    fun resetNew() = synchronized(ownerLock) {
        active = mutableListOf()
        state = ExactState()
        loadGeneration++
        exact = true
    }

    fun beginRestore(): Long = synchronized(ownerLock) { ++loadGeneration }

    internal val ownershipVersion: Long get() = synchronized(ownerLock) { loadGeneration }

    suspend fun load(
        session: DroideSession,
        shouldPublish: () -> Boolean = { true },
        restoreTicket: Long? = null,
        publishTransaction: (() -> List<AgentMessage>) -> List<AgentMessage> = { commit -> commit() },
    ): List<AgentMessage> {
        val request = restoreTicket ?: beginRestore()
        if (request != loadGeneration || !shouldPublish()) throw CancellationException("Agent history owner changed before restore")
        val prepared = withContext(Dispatchers.IO) {
            val migrated = when {
                session.forkHistoryVersion == SessionForkHistory.SCHEMA_VERSION &&
                    SessionForkHistory.isExact(session.forkHistory) -> session.forkHistory
                session.forkHistoryVersion == 0 -> SessionForkHistory.migrateLegacy(session.messages, session.apiHistory)
                else -> null
            }
            val replacement = ExactState()
            if (migrated != null) {
                if (ledger != null && (migrated is SessionHistoryLedger.Snapshot ||
                        migrated.sumOf(SessionJsonSize::estimatedBytes) > SessionHistoryLedger.HOT_HISTORY_BYTES)) {
                    replacement.pages += ledger.persist(migrated)
                } else {
                    replacement.tail += migrated
                    replacement.tailBytes = migrated.sumOf(SessionJsonSize::estimatedBytes)
                }
            }
            val messages = if (migrated != null) SessionForkHistory.annotate(session.messages, migrated)
                else session.messages.map { it.copy(forkHistoryEnd = null) }
            Triple(replacement, migrated != null, messages)
        }
        return publishTransaction { synchronized(ownerLock) {
        if (!shouldPublish() || request != loadGeneration) throw CancellationException("Agent history owner changed during restore")
        active = mutableListOf()
        if (session.apiHistory.isNotEmpty()) {
            active.addAll(session.apiHistory)
        } else {
            session.messages.filter { it.streamPart == null }
                .filterNot { it.content.startsWith("TOOL|") }
                .filter { it.role == "user" || it.role == "assistant" }
                .forEach { message ->
                    active += buildJsonObject {
                        put("role", message.role)
                        put("content", message.content.take(8_000))
                    }
                }
        }

        state = prepared.first
        exact = prepared.second
        prepared.third
        } }
    }

    suspend fun append(entry: JsonObject, beforeAppend: (() -> Unit)? = null) = append(listOf(entry), beforeAppend = beforeAppend)

    suspend fun append(entries: List<JsonObject>, expectedOwnerVersion: Long? = null,
        beforeAppend: (() -> Unit)? = null) {
        currentCoroutineContext().ensureActive()
        val (owner, generation) = synchronized(ownerLock) {
            if (expectedOwnerVersion != null && expectedOwnerVersion != loadGeneration)
                throw CancellationException("Agent history owner changed before append admission")
            state to loadGeneration
        }
        appendMutex.withLock {
        // External policy/run checks stay outside ownerLock; the existing owner
        // generation fences still decide which history may be mutated.
        beforeAppend?.invoke()
        currentCoroutineContext().ensureActive()
        val proposal = synchronized(ownerLock) {
        if (state !== owner || generation != loadGeneration) throw CancellationException("Agent history owner changed before append")
        if (exact) {
            val bytes = entries.sumOf(SessionJsonSize::estimatedBytes)
            require(entries.all { SessionJsonSize.estimatedBytes(it) <= SessionHistoryLedger.MAX_PAGE_BYTES }) {
                "Conversation entry exceeds the mobile history capacity; existing conversation was preserved"
            }
            val nextTail = owner.tail + entries
            val nextBytes = owner.tailBytes + bytes
            Triple(nextTail, nextBytes, ledger != null && owner.storageFailure == null && nextBytes > SessionHistoryLedger.HOT_HISTORY_BYTES)
        } else null
        }
        if (proposal != null) {
            val (nextTail, nextBytes, spill) = proposal
            val coldPages = owner.pages.toList()
            val pages = if (spill && ledger != null) {
                try { withContext(Dispatchers.IO) {
                    beforeAppend?.invoke()
                    // This is admission, not revocable rollback. Once persist starts
                    // under valid authority, later DENY cannot erase its durable bytes.
                    ledger.persist(ledger.snapshot(coldPages, nextTail))
                } }
                catch (revoked: BrowserPublicationRevokedException) { throw revoked }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    synchronized(ownerLock) {
                        if (state === owner && generation == loadGeneration) owner.storageFailure = failure
                    }
                    null
                }
            } else null
            synchronized(ownerLock) {
            if (state !== owner || generation != loadGeneration) throw CancellationException("Agent history owner changed during append")
            if (pages != null) {
                owner.pages.clear(); owner.pages += pages
                owner.tail.clear(); owner.tailBytes = 0L
            } else {
                // The no-store constructor is only for small host fixtures. Production always owns a ledger.
                check(nextBytes <= 8L * 1024 * 1024) { "Conversation history requires durable storage; existing conversation was preserved" }
                owner.tail += entries; owner.tailBytes = nextBytes
            }
            entries.forEach { updateExactGrammar(owner, it) }
            active += entries
            }
        } else synchronized(ownerLock) {
            if (state !== owner || generation != loadGeneration) throw CancellationException("Agent history owner changed during append")
            active += entries
        }
        }
    }

    fun completeActive(): List<JsonObject> = AgentHistory.completeGroups(synchronized(ownerLock) { active.toList() }).flatten()

    /** Provider launch is a complete-turn boundary, unlike in-progress persistence snapshots. */
    fun strictActiveForProvider(): List<JsonObject> {
        val snapshot = synchronized(ownerLock) { active.toList() }
        AgentHistory.requireCompleteGroups(snapshot)
        return snapshot
    }

    suspend fun completeExactOrNull(expectedOwnerVersion: Long? = null): List<JsonObject>? {
        val (owner, generation) = synchronized(ownerLock) {
            if (expectedOwnerVersion != null && expectedOwnerVersion != loadGeneration)
                throw CancellationException("Agent history owner changed before checkpoint admission")
            state to loadGeneration
        }
        checkpointBoundaryProbe?.invoke(generation)
        return appendMutex.withLock {
        val proposal = synchronized(ownerLock) {
        if (state !== owner || generation != loadGeneration) throw CancellationException("Agent history owner changed before checkpoint")
        if (!exact) return@withLock null
        check(owner.valid && owner.remainingTools.isEmpty()) {
            "Exact fork history contains an incomplete structured tool group"
        }
        ledger?.snapshot(owner.pages, owner.tail, verified = true)
        }
        if (ledger != null && proposal != null && proposal.tail.isNotEmpty()) {
            val pages = try { withContext(Dispatchers.IO) { ledger.persist(proposal) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(ownerLock) {
                    if (state === owner && generation == loadGeneration) owner.storageFailure = failure
                }
                throw failure
            }
            synchronized(ownerLock) {
            if (state !== owner || generation != loadGeneration) throw CancellationException("Agent history owner changed during checkpoint")
            owner.pages.clear(); owner.pages += pages
            owner.tail.clear(); owner.tailBytes = 0L
            owner.storageFailure = null
            }
        }
        synchronized(ownerLock) {
            if (state !== owner || generation != loadGeneration) throw CancellationException("Agent history owner changed during checkpoint")
            ledger?.snapshot(owner.pages, owner.tail, verified = true) ?: owner.tail.toList()
        }
        }
    }

    suspend fun ensureStorageAvailable(expectedOwnerVersion: Long? = null) {
        if (synchronized(ownerLock) {
            if (expectedOwnerVersion != null && expectedOwnerVersion != loadGeneration)
                throw CancellationException("Agent history owner changed before storage admission")
            state.storageFailure != null
        }) {
            try { completeExactOrNull(expectedOwnerVersion) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                throw java.io.IOException("Exact conversation storage is unavailable. Work paused before the next provider/tool request; existing context was preserved", failure)
            }
        }
    }

    internal val hotHistoryBytes: Long get() = synchronized(ownerLock) { state.tailBytes }

    private fun updateExactGrammar(owner: ExactState, entry: JsonObject) {
        val role = (entry["role"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (owner.remainingTools.isNotEmpty()) {
            val id = (entry["tool_call_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (role != "tool" || id == null || !owner.remainingTools.remove(id)) owner.valid = false
            return
        }
        if (role !in setOf("user", "assistant", "system")) { owner.valid = false; return }
        val calls = if (role == "assistant") entry["tool_calls"] as? kotlinx.serialization.json.JsonArray else null
        for (call in calls.orEmpty()) {
            val id = ((call as? JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (id == null || !owner.remainingTools.add(id)) owner.valid = false
        }
    }
}

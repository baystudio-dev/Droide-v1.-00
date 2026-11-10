package com.baystudio.droide.core

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/** Strongly owned by managers/in-flight operations; unused workspace coordinators may be collected. */
internal class SessionWriteTransactions {
    class Entry {
        val mutex = Mutex()
        var conversationRevision = 0L
    }

    private class EntryRef(val id: String, entry: Entry, queue: ReferenceQueue<Entry>) :
        WeakReference<Entry>(entry, queue)

    private val entryQueue = ReferenceQueue<Entry>()
    private val entries = HashMap<String, EntryRef>()
    private val deletedIds = ConcurrentHashMap.newKeySet<String>()
    @Synchronized fun entry(id: String): Entry {
        while (true) {
            val reference = entryQueue.poll() as EntryRef? ?: break

            if (entries[reference.id] === reference) entries.remove(reference.id)
        }
        return entries[id]?.get() ?: Entry().also { entries[id] = EntryRef(id, it, entryQueue) }
    }
    fun deleted(id: String): Boolean = id in deletedIds
    fun markDeleted(id: String) { deletedIds += id }

    companion object {
        private val workspaces = HashMap<String, WeakReference<SessionWriteTransactions>>()
        @Synchronized fun forWorkspace(path: String): SessionWriteTransactions {
            workspaces.entries.removeAll { it.value.get() == null }
            return workspaces[path]?.get() ?: SessionWriteTransactions().also { workspaces[path] = WeakReference(it) }
        }
    }
}

/** Pins this exact lock/revision while a caller prepares an asynchronous result. */
class SessionWriteRevision internal constructor(
    internal val entry: SessionWriteTransactions.Entry,
    internal val value: Long,
)

internal data class SessionWriteSnapshot(val session: DroideSession, val revision: SessionWriteRevision)

internal class StaleSessionWriteException : IllegalStateException(
    "The conversation changed while this operation was running. Newer history and pending prompts were preserved."
)

package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only newly produced, unpublished refs are rolled back; earlier chat cards remain owned. */
internal class AgentMediaPublication private constructor(private val store: ChatMediaStore, private val owner: String, existing: Set<String>) {
    private val committed = existing.toMutableSet()
    private val pending = linkedMapOf<String, ChatMediaRef>()
    @Synchronized fun produced(ref: ChatMediaRef) { if (ref.id !in committed) pending[ref.id] = ref }
    fun producedImages(images: List<AgentImageRef>) { images.forEach { image -> image.mediaId?.let(store::ref)?.let(::produced) } }
    @Synchronized fun commit(refs: List<ChatMediaRef>, images: List<AgentImageRef>) {
        val ids = refs.map { it.id } + images.mapNotNull { it.mediaId }
        ids.forEach { pending.remove(it); committed += it }
    }
    suspend fun discardUnpublished() = withContext(Dispatchers.IO) {
        val refs = synchronized(this@AgentMediaPublication) { pending.values.toList().also { pending.clear() } }
        refs.forEach { ref -> runCatching { store.release(owner, ref) } }
    }
    companion object {
        suspend fun begin(workDir: File, owner: String): AgentMediaPublication = withContext(Dispatchers.IO) {
            val store = ChatMediaStore(workDir)
            AgentMediaPublication(store, owner, if (store.hasManifest()) store.ownerRefs(owner).map { it.id }.toSet() else emptySet())
        }
    }
}

package com.baystudio.droide.core

import java.security.MessageDigest

/** Process-local optimization of immutable admission, never a content-read integrity cache. Cold
 * reads/export still verify bytes. Unrelated metadata saves need not detect new bit rot. */
internal class AgentPublicContentAdmissionCache(private val capacity: Int = 128) {
    private data class Key(val session: String, val ref: AgentPublicContentRef,
        val abort: PublicAnswerAbortCheckpoint?, val preview: String)
    private val entries = LinkedHashMap<Key, Unit>(16, .75f, true)
    private var generation = 0L
    init { require(capacity > 0) }
    @Synchronized fun clear() { entries.clear(); generation++ }
    fun verify(session: String, messages: List<AgentMessage>, checkActive: () -> Unit,
        verifyOne: (AgentMessage) -> Unit) {
        val epoch = synchronized(this) { generation }
        val admitted = ArrayList<Key>()
        messages.forEach { message ->
            checkActive()
            val ref = message.publicContent
            require(message.publicAbort == null || message.publicAbort.reference == ref)
            if (ref != null) {
                require(message.role == "assistant")
                val digest = MessageDigest.getInstance("SHA-256").digest(listOf(message.role, message.content, message.presentationId, message.streamPart?.let { kotlinx.serialization.json.Json.encodeToString(AgentStreamPart.serializer(), it) }.orEmpty()).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val key = Key(session, ref, message.publicAbort, digest)
                val known = synchronized(this) { entries[key] != null }
                if (!known) verifyOne(message)
                admitted.add(key)
            }
        }
        checkActive()
        // A failed/cancelled collection never grants partial new admission.
        synchronized(this) {
            check(generation == epoch) { "Public content authority changed during admission" }
            admitted.forEach {
                entries[it] = Unit
                while (entries.size > capacity) entries.remove(entries.keys.first())
            }
        }
    }
}

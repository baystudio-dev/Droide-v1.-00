package com.baystudio.droide.core

/** Message-manifest admission is distinct from model-facing API history. Never add owned
 * capabilities to provider messages. All blob checks occur off the UI thread in SessionManager. */
internal object AgentPublicContentPersistence {
    fun verify(messages: List<AgentMessage>, archive: AgentPublicContentArchive) {
        messages.forEach { message ->
            val ref = message.publicContent
            require(message.publicAbort == null || message.publicAbort.reference == ref) { "Abort evidence does not match public content" }
            if (ref != null) {
                require(message.role == "assistant") { "Only assistant public answers may own public content" }
                val stored = archive.reopen(ref)
                require(message.content == stored.preview) { "Public answer preview differs from its owned source" }
            }
        }
    }

    /** Copy selected immutable blobs before publishing a fork manifest. Never blindly copy owner-bound refs. */
    fun fork(messages: List<AgentMessage>, source: AgentPublicContentArchive, target: AgentPublicContentArchive): List<AgentMessage> {
        verify(messages, source)
        return messages.map { message ->
            val ref = message.publicContent ?: return@map message
            val copied = source.copyForFork(ref, target, ref.runId, ref.itemId)
            message.copy(content = copied.preview, publicContent = copied.reference,
                publicAbort = message.publicAbort?.copy(reference = copied.reference))
        }
    }
}

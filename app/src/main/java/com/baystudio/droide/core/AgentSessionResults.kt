package com.baystudio.droide.core

internal sealed interface AgentSessionResult {
    val key: String
    data class Media(val ref: ChatMediaRef) : AgentSessionResult { override val key = "media:${ref.id}" }
    data class File(val path: String, val activity: String, val step: Int) : AgentSessionResult {
        override val key = "file:$path"
    }
}

internal data class AgentSessionResultPage(val entries: List<AgentSessionResult>, val hasNext: Boolean)

/** Projects recorded results on demand; never guesses paths from model prose or scans the workspace. */
internal object AgentSessionResults {
    const val PAGE_SIZE = 40

    fun page(messages: List<AgentMessage>, live: List<AgentStreamPart> = emptyList(),
             media: List<ChatMediaRef> = emptyList(), offset: Int = 0): AgentSessionResultPage {
        require(offset >= 0 && offset <= Int.MAX_VALUE - PAGE_SIZE)
        val candidates = sequence {
            media.asReversed().forEach { yield(AgentSessionResult.Media(it)) }
            suspend fun SequenceScope<AgentSessionResult>.changes(part: AgentStreamPart?) {
                if (part?.state != AgentStreamPartState.COMPLETED) return
                for (path in part.producedFiles.asReversed()) if (safePath(path)) {
                    yield(AgentSessionResult.File(path, part.toolName.ifBlank { part.title }.take(100), part.step))
                }
                for (change in part.fileChanges.asReversed()) if (safePath(change.path)) {
                    yield(AgentSessionResult.File(change.path, part.toolName.ifBlank { part.title }.take(100), part.step))
                }
            }
            live.asReversed().forEach { changes(it) }
            for (message in messages.asReversed()) {
                if (message.role != "assistant") continue
                message.media.asReversed().forEach { yield(AgentSessionResult.Media(it)) }
                changes(message.streamPart)
            }
        }
        val entries = candidates.distinctBy { it.key }.drop(offset).take(PAGE_SIZE + 1).toList()
        return AgentSessionResultPage(entries.take(PAGE_SIZE), entries.size > PAGE_SIZE)
    }

    fun safePath(path: String): Boolean = path.isNotBlank() && path.length <= 4096 &&
        !path.startsWith('/') && ':' !in path && '\\' !in path && path.none(Char::isISOControl) &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

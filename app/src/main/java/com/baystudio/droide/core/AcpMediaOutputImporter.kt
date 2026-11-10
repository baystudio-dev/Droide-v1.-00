package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Snapshot typed outputs before mirror reconciliation. Source files are never used as a fallback. */
internal object AcpMediaOutputImporter {
    suspend fun read(block: JsonObject, store: ChatMediaStore, owner: String, mapper: WorkspacePathMapper,
                     workspaceIo: AgentWorkspaceIo, onImported: suspend (ChatMediaRef) -> Unit = {},
                     authorizeRead: suspend (String) -> Boolean,
    ): ChatMediaRef? {
        val imported = when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
            "image" -> {
                val (mime, bytes) = AcpChatMedia.decodeImage(block)
                val suffix = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/gif" -> "gif"; else -> "webp" }
                ByteArrayInputStream(bytes).use { store.import(owner, "Agent image.$suffix", mime, it) }
            }
            "resource_link" -> {
                val file = AcpChatMedia.localResource(block, mapper)
                if (file == null) null else {
                    val relative = file.relativeTo(mapper.localRoot).invariantSeparatorsPath
                    val registered = store.findByPath(relative)
                    if (registered != null) {
                        registered.takeIf { it in store.ownerRefs(owner) && store.file(it) != null && authorizeRead(relative) }
                    } else if (!AgentWorkspacePathPolicy.visible(relative) || !authorizeRead(relative)) null else {
                        val mime = (block["mimeType"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "application/octet-stream"
                        val name = (block["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: file.name
                        workspaceIo.openMedia(mapper.localRelativeToRemote(relative)).use { store.import(owner, name, mime, it) }
                    }
                }
            }
            else -> null
        }
        if (imported != null) onImported(imported)
        return imported
    }
}

/** One serial ACP output consumer per turn; ordinary streaming chunks never schedule cleanup. */
internal class AcpMediaOutputRetention(private val schedule: suspend () -> Unit) {
    private var enrolled = false
    suspend fun imported(@Suppress("UNUSED_PARAMETER") ref: ChatMediaRef) {
        if (enrolled) return
        schedule()
        enrolled = true
    }
}

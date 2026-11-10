package com.baystudio.droide.core

import kotlinx.serialization.json.JsonPrimitive

/** Paths refer to chat-owned copies. The model can request bounded reads beyond a UI excerpt. */
internal object AgentMediaPromptReceipt {
    fun forModel(text: String, media: List<ChatMediaRef>, images: List<AgentImageRef>): String {
        if (media.isEmpty()) return text
        return buildString {
            append("[Chat attachments; read_file reads text; save_media_to_project copies every original file type]\n")
            media.take(4).forEach { ref ->
                append("name=").append(JsonPrimitive(ref.name)).append(" | ").append(ref.path).append(" | ").append(ref.mediaType).append(" | ").append(ref.bytes)
                    .append(" bytes | expires_at_ms=").append(ref.expiresAt).append('\n')
            }
            if (media.any { it.mediaType.startsWith("video/") }) {
                append("Delivered ").append(images.size).append(" image/frame samples in total. Video evidence is sampled only; audio and unsampled intervals have not been analyzed.\n")
            }
            append("Any inline file preview may be an excerpt; use read_file with next_offset for complete text.\n[/Chat attachments]\n\n")
            append("For a requested project file or asset (ZIP, PDF, documents, source code, images or videos), use save_media_to_project with an original managed path above and a project destination when that tool is advertised. Managed paths are not shell paths; saved project assets survive chat expiry.\n\n")
            append(text)
        }
    }
}

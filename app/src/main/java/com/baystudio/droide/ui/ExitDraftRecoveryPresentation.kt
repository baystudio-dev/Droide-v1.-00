package com.baystudio.droide.ui

import com.baystudio.droide.core.ExitDraftAttachment
import java.text.DateFormat
import java.util.Date

/** A display excerpt only. Neither the archive nor restore uses this bounded presentation. */
internal fun exitDraftDisplayPreview(text: String, limit: Int = 240): String {
    require(limit > 0)
    var end = minOf(text.length, limit)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end)
}

internal fun exitDraftAttachmentDescription(attachment: ExitDraftAttachment, now: Long): String {
    val media = attachment.media ?: return "${attachment.name}: saved preview only. The original file is not stored in this archive; reattach it."
    val expiry = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(media.expiresAt))
    val status = if (media.expiresAt <= now) "Expired reference" else "Reference only; availability is not checked"
    return "${attachment.name}: ${media.mediaType}, ${media.bytes} bytes. $status. Expiry: $expiry. Reattach before Send."
}

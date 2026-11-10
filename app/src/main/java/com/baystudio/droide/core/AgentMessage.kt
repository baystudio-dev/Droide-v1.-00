package com.baystudio.droide.core

import kotlinx.serialization.Serializable

@Serializable
data class AgentMessage(
    val role: String,
    val content: String,
    val streamPart: AgentStreamPart? = null,

    val forkHistoryEnd: Int? = null,
    val media: List<ChatMediaRef> = emptyList(),
    val textPresentation: AgentTextPresentation = AgentTextPresentation.LEGACY,
    val presentationId: String = "",
    val publicTextSpans: List<AgentPublicTextSpan> = emptyList(),
    val publicContentId: String? = null,
    // Full public text is separately owned; content remains a bounded display projection.
    @kotlinx.serialization.Transient val publicContent: AgentPublicContentRef? = null,
    @kotlinx.serialization.Transient val publicAbort: PublicAnswerAbortCheckpoint? = null,
)

package com.baystudio.droide.core

/** Presentation only: counts describe model-facing history, not billed provider usage. */
data class AgentCompactionActivity(
    val id: Long,
    val sessionId: String,
    val running: Boolean,
    val beforeTokens: Int,
    val afterTokens: Int? = null,
    val detail: String = "Compacting context…",
)

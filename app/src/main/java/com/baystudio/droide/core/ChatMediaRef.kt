package com.baystudio.droide.core

import kotlinx.serialization.Serializable

/** Small durable reference only; bytes and thumbnails never live in the conversation JSON. */
@Serializable
data class ChatMediaRef(
    val id: String,
    val path: String,
    val name: String,
    val mediaType: String,
    val bytes: Long,
    val createdAt: Long,
    val expiresAt: Long,
)

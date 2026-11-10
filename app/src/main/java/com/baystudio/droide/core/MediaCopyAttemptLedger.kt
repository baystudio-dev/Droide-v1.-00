package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One per user-requested agent run. Never evict: exhaustion fails closed instead of enabling loops. */
class MediaCopyAttemptLedger(private val maxAttempts: Int = 32) {
    private data class Attempt(val replayReceipt: String? = null)
    private val attempts = linkedMapOf<Pair<String, String>, Attempt>()
    private val blockedSources = mutableMapOf<String, Pair<String, String>>()
    init { require(maxAttempts in 1..128) }

    @Synchronized fun begin(source: String, destination: String): String? {
        attempts[source to destination]?.replayReceipt?.let { return it }
        blockedSources[source]?.let {
            return "ERROR: MEDIA_COPY_ALREADY_ATTEMPTED " + buildJsonObject {
                put("path", destination)
                put("previous_destination", it.first)
                put("retryable", false)
                put("do_not_recopy", true)
                put("no_new_copy", true)
                // The bounded diagnostic is a JSON STRING, never a truncated serialized object.
                put("previous_result_excerpt", it.second)
                put("previous_result_excerpt_may_be_truncated", true)
            }
        }
        if (attempts.size >= maxAttempts) return "ERROR: MEDIA_COPY_RUN_LIMIT retryable=false. " +
            "This run has reached its bounded copy budget; ask the user for a new decision before copying more attachments."
        val pending = "An attempt already started in this run and has not completed successfully."
        attempts[source to destination] = Attempt()
        blockedSources[source] = destination to pending
        return null
    }

    @Synchronized fun record(source: String, destination: String, result: String, completed: Boolean = false) {
        if ((source to destination) !in attempts) return
        val replay = if (completed) historicalReplay(destination, result) else null
        attempts[source to destination] = Attempt(replay)
        if (replay == null) {
            val diagnostic = if (completed)
                "The completed copy's receipt could not be captured safely. Inspect the original outcome; no automatic recopy is allowed."
                else diagnosticExcerpt(result)
            blockedSources[source] = destination to diagnostic
        } else if (blockedSources[source]?.first == destination) blockedSources.remove(source)
    }

    private fun historicalReplay(destination: String, result: String): String? {

        if (destination.length > 500 || !result.startsWith(SUCCESS_PREFIX) || result.length > MAX_CAPTURE_CHARS) return null
        val original = try { Json.parseToJsonElement(result.removePrefix(SUCCESS_PREFIX)) as? JsonObject }
            catch (_: IllegalArgumentException) { null } ?: return null
        fun primitive(key: String) = original[key] as? JsonPrimitive
        if (primitive("path")?.takeIf { it.isString }?.content != destination ||
            primitive("local_commit_completed")?.booleanOrNull != true) return null
        val synchronization = primitive("synchronization")?.takeIf { it.isString }?.content
        if (synchronization != "completed") return null
        val bytes = (primitive("committed_bytes") ?: primitive("bytes"))?.longOrNull
            ?.takeIf { it >= 0 } ?: return null
        val detail = primitive("detail")?.takeIf { it.isString }?.content
        val serialized = "MEDIA_SAVE_RECEIPT_REPLAY " + buildJsonObject {
            put("path", destination)
            put("committed_bytes", bytes)
            put("local_commit_completed", true)
            put("synchronization", synchronization)
            put("historical_receipt", true)
            put("replayed", true)
            put("current_file_reverified", false)
            put("no_new_copy", true)
            put("retryable", false)
            put("do_not_recopy", true)
            primitive("media_id")?.takeIf { it.isString && it.content.length <= 128 }?.let { put("media_id", it) }
            primitive("integrity_basis")?.takeIf { it.isString && it.content in setOf("import", "legacy_snapshot") }
                ?.let { put("integrity_basis", it) }
            primitive("historical_original_digest_available")?.booleanOrNull
                ?.let { put("historical_original_digest_available", it) }
            if (detail != null) {
                put("detail", diagnosticExcerpt(detail))
                put("detail_truncated", detail.length > MAX_DIAGNOSTIC_CHARS)
            }
            put("next_action", "This is the earlier commit receipt. No file was copied or checked now. Inspect the current destination before deciding on another save.")
        }
        return serialized.takeIf { it.length <= MAX_REPLAY_CHARS } // Fail closed; never cut serialized JSON.
    }

    private fun diagnosticExcerpt(text: String): String {
        var end = minOf(text.length, MAX_DIAGNOSTIC_CHARS)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end)
    }

    private companion object {
        const val SUCCESS_PREFIX = "MEDIA_SAVED_TO_PROJECT "
        const val MAX_CAPTURE_CHARS = 16_384
        const val MAX_REPLAY_CHARS = 8_192
        const val MAX_DIAGNOSTIC_CHARS = 500
    }
}

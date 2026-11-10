package com.baystudio.droide.core

import okio.BufferedSource

internal object ProviderPayloadLimits {
    const val MAX_JSON_DEPTH = 128

    fun readLine(source: BufferedSource, maxBytes: Int = LlmClient.MAX_RESPONSE_CHARS): String? {
        require(maxBytes > 0)
        val newline = source.indexOf('\n'.code.toByte(), 0, maxBytes.toLong() + 1)
        if (newline >= 0) return source.readUtf8LineStrict(newline)
        // indexOf reads at most the bounded prefix plus an Okio segment. Never call an unbounded
        // readUtf8Line: a server can otherwise send an endless line and OOM.
        check(source.buffer.size <= maxBytes) { "Provider response line exceeds the mobile response capacity" }
        return if (source.buffer.size == 0L) null else source.readUtf8()
    }

    fun checkedJson(text: String, maxChars: Int = LlmClient.MAX_RESPONSE_CHARS): String {
        check(text.length <= maxChars) { "Provider JSON exceeds the supported response capacity" }
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> quoted = false
                }
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    check(depth <= MAX_JSON_DEPTH) { "Provider JSON nesting exceeds the supported response capacity" }
                }
                '}', ']' -> depth--
            }
        }
        // Syntax validation belongs to the JSON parser; this check only prevents stack exhaustion.
        return text
    }

    fun checkedRequest(text: String): String {
        if (text.length > LlmClient.MAX_MULTIMODAL_REQUEST_CHARS) throw LlmRequestTooLargeException(text.length)
        return checkedJson(text, LlmClient.MAX_MULTIMODAL_REQUEST_CHARS)
    }

    /** Count only state retained by adapters, not disposable SSE envelope traffic. */

    class RetainedBudget(private val maximumChars: Int = 8_000_000) {
        private var retained = 0L
        private val slots = mutableMapOf<String, Long>()
        var revision: Long = 0L
            private set
        init { require(maximumChars > 0) }
        fun validateEvent(payload: String) { checkedJson(payload) }
        fun reserve(units: Long) {
            require(units >= 0)
            check(units <= maximumChars.toLong() - retained) {
                "Provider retained response exceeds the in-memory capacity"
            }
            retained += units
            if (units > 0) revision++
        }
        fun append(target: StringBuilder, fragment: String) {
            reserve(fragment.length.toLong())
            target.append(fragment)
        }
        fun replace(key: String, units: Long) {
            require(units >= 0)
            val previous = slots[key]
            val overhead = if (previous == null) 64L + key.length else 0L
            val change = units - (previous ?: 0L) + overhead
            val previousRevision = revision
            if (change > 0) reserve(change) else retained += change
            // Replacing metadata/usage snapshots is not generation progress.
            revision = previousRevision
            slots[key] = units
        }
    }

    /** Called only on already depth-checked event JSON. Count collections even if empty. */
    fun retainedJsonUnits(value: kotlinx.serialization.json.JsonElement): Long = when (value) {
        is kotlinx.serialization.json.JsonObject -> 64L + value.entries.sumOf { (key, child) ->
            64L + key.length + retainedJsonUnits(child)
        }
        is kotlinx.serialization.json.JsonArray -> 64L + value.sumOf { 16L + retainedJsonUnits(it) }
        is kotlinx.serialization.json.JsonPrimitive -> 64L + value.content.length
    }
}

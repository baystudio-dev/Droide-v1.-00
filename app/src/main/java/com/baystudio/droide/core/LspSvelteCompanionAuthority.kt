package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Separate native custom-document ledger. No standard TS/JS opens or invented wire versions. */
internal class LspSvelteCompanionAuthority {
    data class Capture(val path: String, val uri: String, val text: String, val revision: Any)
    class Primer internal constructor(val capture: Capture, internal val owner: Any)
    private val owner = Any()
    private val primed = mutableMapOf<String, Primer>()
    private val written = mutableMapOf<String, Capture>()
    private var closed = false

    /** Caller serializes reconciliation; revision identity is checked again at actual wire write. */
    @Synchronized fun needsPrimer(capture: Capture): Boolean = !closed && primed[capture.path] == null
    @Synchronized fun needsWrite(capture: Capture): Boolean = !closed && written[capture.path] != capture

    @Synchronized fun acceptPrimer(capture: Capture, result: JsonElement): Primer {
        check(!closed) { "Svelte companion owner is closed" }
        val locations = result as? JsonArray ?: error("Svelte companion primer returned no array")
        require(locations.size <= 10_000) { "Svelte companion primer is oversized" }
        locations.forEach { value ->
            val location = value as? JsonObject ?: error("Malformed Svelte companion primer location")
            val uri = (location["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Primer URI missing")
            require(uri.isNotEmpty() && uri.length <= 16_384 && uri.none { it.code < 0x20 }) { "Primer URI is empty or invalid" }
            val parsedUri = runCatching { java.net.URI(uri) }.getOrNull()
            require(parsedUri?.isAbsolute == true && !parsedUri.rawSchemeSpecificPart.isNullOrEmpty()) { "Primer URI must be absolute" }
            val range = location["range"] as? JsonObject ?: error("Primer range missing")
            fun position(key: String): Pair<Int, Int> {
                val value = range[key] as? JsonObject ?: error("Primer position missing")
                fun coordinate(name: String): Int = requireNotNull((value[name] as? JsonPrimitive)
                    ?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }) { "Malformed primer position" }
                return coordinate("line") to coordinate("character")
            }
            val start = position("start"); val end = position("end")
            require(end.first > start.first || end.first == start.first && end.second >= start.second) { "Primer range is reversed" }
        }
        return Primer(capture, owner).also { primed[capture.path] = it }
    }

    @Synchronized fun recordWritten(capture: Capture) {
        check(!closed && primed[capture.path]?.owner === owner) { "Svelte companion has no current native primer" }
        written[capture.path] = capture
    }
    @Synchronized fun current(capture: Capture): Boolean = !closed && written[capture.path] == capture
    @Synchronized fun owns(path: String): Boolean = path in primed || path in written
    @Synchronized fun paths(): Set<String> = (primed.keys + written.keys).toSet()
    @Synchronized fun close() { closed = true; primed.clear(); written.clear() }

    companion object {
        fun change(capture: Capture): JsonObject = buildJsonObject {
            put("uri", capture.uri)
            put("changes", buildJsonArray { add(buildJsonObject { put("text", capture.text) }) })
        }
    }
}

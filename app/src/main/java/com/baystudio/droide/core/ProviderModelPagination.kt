package com.baystudio.droide.core

import java.net.URLEncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Collect reviewed native catalogs without accepting URLs or inventing gateway cursor semantics. */
internal object ProviderModelPagination {
    private const val MAX_PAGES = 32
    private const val MAX_MODELS = 1_000
    private const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L
    private const val MAX_CURSOR_CHARS = 4_096

    suspend fun collect(provider: AiProvider, json: Json, fetch: suspend (String) -> String): List<ProviderModelCatalog.Entry> {
        val firstPath = ProviderModelCatalog.modelsPath(provider)
        val models = LinkedHashMap<String, ProviderModelCatalog.Entry>()
        val seen = HashSet<String>()
        var path = firstPath
        var bytes = 0L
        repeat(MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val body = fetch(path)
            currentCoroutineContext().ensureActive()
            bytes += body.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_TOTAL_BYTES) { "Provider model catalog exceeds the aggregate pagination budget" }
            val root = json.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("Provider model catalog must be an object")
            ProviderModelCatalog.parse(body, provider, json).forEach { entry ->
                if (models.size < MAX_MODELS) models.putIfAbsent(entry.id, entry)
            }
            // The existing model-picker/certification cap is intentional, not an unlimited census.
            if (models.size == MAX_MODELS) return models.values.toList()
            val cursor = nextCursor(root, provider.wireProtocol) ?: return models.values.toList()
            require(seen.add(cursor.second)) { "Provider repeated a model catalog cursor" }
            val encoded = URLEncoder.encode(cursor.second, Charsets.UTF_8.name()).replace("+", "%20")
            path = firstPath + (if ('?' in firstPath) "&" else "?") + cursor.first + "=" + encoded
        }
        throw IllegalArgumentException("Provider model catalog exceeds the pagination page budget")
    }

    private fun nextCursor(root: JsonObject, protocol: ProviderWireProtocol): Pair<String, String>? = when (protocol) {
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> {
            val raw = root["nextPageToken"]
            if (raw == null) null else {
                val token = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("Invalid Google model catalog cursor")
                if (token.isEmpty()) null else "pageToken" to validCursor(token)
            }
        }
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> {
            val raw = root["has_more"]
            val more = if (raw == null) false else (raw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("Invalid Anthropic model catalog pagination flag")
            if (!more) null else {
                val cursor = (root["last_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("Anthropic model catalog is missing its continuation cursor")
                "after_id" to validCursor(cursor)
            }
        }
        else -> null
    }

    private fun validCursor(cursor: String): String {
        require(cursor.length in 1..MAX_CURSOR_CHARS && cursor.none { it.code < 0x20 || it.code == 0x7f }) {
            "Invalid provider model catalog cursor"
        }
        return cursor
    }
}

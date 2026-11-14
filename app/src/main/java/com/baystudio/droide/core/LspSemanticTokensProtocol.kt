package com.baystudio.droide.core

import kotlinx.serialization.json.*

 
internal object LspSemanticTokensProtocol {
    private const val MAX_LEGEND_ITEMS = 128
    private const val MAX_TOKEN_NAME = 64

    fun supportsFull(provider: JsonObject?): Boolean {
        provider ?: return false
        return when (val full = provider["full"]) {
            is JsonPrimitive -> full.booleanOrNull == true
            is JsonObject -> true
            else -> false
        }
    }

    fun legendFromProvider(provider: JsonObject?): LspSemanticTokenLegend {
        val legend = provider?.get("legend") as? JsonObject ?: return LspSemanticTokenLegend()
        fun names(key: String): List<String>? {
            val array = legend[key] as? JsonArray ?: return emptyList()
            val names = ArrayList<String>(minOf(array.size, MAX_LEGEND_ITEMS))
            for (element in array.take(MAX_LEGEND_ITEMS)) {
                val primitive = element as? JsonPrimitive ?: return null
                if (!primitive.isString) return null
                val name = primitive.content
                if (name.isBlank() || name.length > MAX_TOKEN_NAME) return null
                names += name
            }
            return names
        }
        // Legend indexes and modifier bits are wire identities; never compact invalid entries.
        val types = names("tokenTypes") ?: return LspSemanticTokenLegend()
        val modifiers = names("tokenModifiers") ?: return LspSemanticTokenLegend()
        return LspSemanticTokenLegend(types, modifiers)
    }

    fun parseFull(result: JsonElement, legend: LspSemanticTokenLegend): List<LspSemanticToken> {
        val obj = result as? JsonObject ?: return emptyList()
        val array = obj["data"] as? JsonArray ?: return emptyList()
        if (array.size % 5 != 0 || array.size / 5 > LspSemanticTokenDecoder.MAX_TOKENS) return emptyList()
        val data = ArrayList<Long>(array.size)
        for (element in array) {
            val primitive = element as? JsonPrimitive ?: return emptyList()
            if (primitive.isString) return emptyList()
            data += primitive.longOrNull ?: return emptyList()
        }
        return LspSemanticTokenDecoder.decode(data, legend)
    }

    /** Exact built-in PSES opt-in; all other profiles keep parseFull unchanged. */
    fun parseFullMultiline(result: JsonElement, legend: LspSemanticTokenLegend, text: String): List<LspSemanticToken> =
        LspPowerShellSemanticTokens.parseFull(result, legend, text)
    suspend fun requestFull(
        rpc: JsonRpcProcess,
        uri: String,
        legend: LspSemanticTokenLegend,
    ): List<LspSemanticToken> {
        val result = runSuspendCatching {
            rpc.request(
                "textDocument/semanticTokens/full",
                buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri) }) },
                timeoutMs = 4_500,
            )
        }.getOrNull() ?: return emptyList()
        return parseFull(result, legend)
    }

}

package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Mechanical validation only. Semantic data is always provided by an original native engine. */
internal object LspVueResultAuthority {
    fun validText(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c == '\u0000' || c == '\r' && (i == text.length || text[i] != '\n')) return false
            if (c.isHighSurrogate()) { if (i == text.length || !text[i++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
    fun validPosition(text: String, line: Int, column: Int): Boolean = LspNimResultAuthority.validPosition(text, line, column)
    fun validRange(text: String, range: JsonElement?): Boolean = LspNimResultAuthority.validRange(text, range)
    fun response(value: JsonElement, envelope: JsonObject): Pair<JsonElement, JsonObject> {
        val obj = value as? JsonObject ?: error("Vue response envelope missing")
        require(listOf("pairEpoch", "syncSerial", "captureDigest", "requestSerial").all { obj[it] == envelope[it] }) { "Vue response capture mismatched" }
        val outcomes = obj["sourceOutcomes"] as? JsonObject ?: error("Vue source outcomes missing")
        val method = (envelope["method"] as? JsonPrimitive)?.content
        val expected = when (method) {
            "textDocument/completion" -> setOf("vue", "private-ts-lsp")
            "textDocument/diagnostic" -> setOf("vue", "ts-syntactic", "ts-semantic", "ts-suggestion")
            "textDocument/formatting", "textDocument/documentSymbol" -> setOf("vue")
            "textDocument/hover", "textDocument/signatureHelp", "textDocument/definition", "textDocument/typeDefinition",
            "textDocument/implementation", "textDocument/references", "textDocument/prepareRename", "textDocument/rename" -> setOf("private-ts-lsp")
            else -> error("Unsupported Vue source method")
        }
        require(outcomes.keys == expected && outcomes.all { (_, raw) ->
            val status = ((raw as? JsonObject)?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content
            status in setOf("ok", "error", "unsupported")
        }) { "Vue source outcome malformed" }
        return (obj["result"] ?: error("Vue native result missing")) to outcomes
    }
    fun completion(value: JsonElement, line: Int, col: Int, text: String, batch: Long,
        guard: LspCompletionApplicationGuard): List<LspCompletionItem> {
        val root = value as? JsonObject ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        if (items.size > 32 || root["itemDefaults"] != null) return emptyList()
        val identities = items.map { raw ->
            val item = raw as? JsonObject ?: return emptyList()
            val origin = (item["droideVueOrigin"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
            val id = (item["droideVueItem"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
            if (origin !in setOf("vue", "private-ts-lsp") || !id.startsWith("$origin:") ||
                id.substringAfterLast(':').toIntOrNull() !in 0..15) return emptyList()
            origin to id
        }
        if (identities.distinct().size != identities.size || identities.groupingBy { it.first }.eachCount().values.any { it > 16 }) return emptyList()
        return items.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val origin = (item["droideVueOrigin"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val id = (item["droideVueItem"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (origin !in setOf("vue", "private-ts-lsp") || id.length > 128 ||
                (item["droideVueBatch"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull != batch) return@mapNotNull null
            if (item["insertTextFormat"]?.let { (it as? JsonPrimitive)?.takeUnless { v -> v.isString }?.intOrNull !in setOf(1, 2) } == true ||
                item["insertTextMode"]?.let { (it as? JsonPrimitive)?.takeUnless { v -> v.isString }?.intOrNull != 1 } == true ||
                item["command"]?.let { it != JsonNull } == true) return@mapNotNull null
            val primaryText = ((item["textEdit"] as? JsonObject)?.get("newText") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (!validText(primaryText) || (item["additionalTextEdits"] as? JsonArray)?.any { edit ->
                    val text = ((edit as? JsonObject)?.get("newText") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    text == null || !validText(text)
                } == true) return@mapNotNull null
            if ((item["insertTextFormat"] as? JsonPrimitive)?.intOrNull == 2 && !validSnippet(primaryText)) return@mapNotNull null
            LspCompletionProtocol.parse(JsonArray(listOf(item)), line, col, text, strictSnippets = true,
                requirePrimaryEdit = true, rejectCommand = true, preserveNativeOrder = true).singleOrNull()?.copy(
                    applicationGuard = guard, nativeOrigin = origin, nativeBatch = batch, nativeItemIdentity = id)
        }
    }
    /** Finite native snippet subset also accepted by the helper; not an SFC or semantic parser. */
    fun validSnippet(source: String): Boolean = runCatching {
        require(source.length <= ProfessionalSnippets.MAX_SNIPPET_CHARS && validText(source))
        var index = 0
        var markers = 0
        fun escape() { require(index < source.length && source[index++] in "\\${'$'}}") }
        fun number() {
            val start = index
            while (index < source.length && source[index] in '0'..'9') index++
            require(index > start && source.substring(start, index).toIntOrNull() in 0..999999)
        }
        while (index < source.length) when (source[index++]) {
            '\\' -> escape()
            '$' -> {
                require(++markers <= ProfessionalSnippets.MAX_SNIPPET_MARKERS && index < source.length)
                if (source[index] in '0'..'9') number() else {
                    require(source[index++] == '{'); number(); require(index < source.length)
                    if (source[index] == '}') index++ else {
                        require(source[index++] == ':')
                        var closed = false
                        while (index < source.length) {
                            when (val c = source[index++]) {
                                '\\' -> escape()
                                '}' -> { closed = true; break }
                                else -> require(c != '$' && c != '{')
                            }
                        }
                        require(closed)
                    }
                }
            }
        }
        true
    }.getOrDefault(false)

    /** Validate native UTF-16 spans without replacing labels, parameters, selection or documentation. */
    fun signatureHelp(value: JsonElement?): JsonElement {
        val root = value as? JsonObject ?: return JsonNull
        val signatures = root["signatures"] as? JsonArray ?: return JsonNull
        if (signatures.isEmpty() || signatures.size > LspSignatureHelpProtocol.MAX_SIGNATURES) return JsonNull
        fun string(raw: JsonElement?): String? = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun number(raw: JsonElement?): Int? = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        fun documentation(raw: JsonElement?): Boolean = when (raw) {
            null -> true
            is JsonPrimitive -> raw.isString && validText(raw.content)
            is JsonObject -> string(raw["kind"]) in setOf("markdown", "plaintext") &&
                string(raw["value"])?.let(::validText) == true
            else -> false
        }
        fun active(raw: JsonElement?, count: Int): Boolean = raw == null ||
            number(raw)?.let { it >= 0 && (it < count || count == 0 && it == 0) } == true
        for (raw in signatures) {
            val signature = raw as? JsonObject ?: return JsonNull
            val label = string(signature["label"]) ?: return JsonNull
            if (label.isBlank() || label.length > LspSignatureHelpProtocol.MAX_LABEL_CHARS || !validText(label) ||
                !documentation(signature["documentation"])) return JsonNull
            val parameters = signature["parameters"]?.let { it as? JsonArray ?: return JsonNull } ?: JsonArray(emptyList())
            if (parameters.size > LspSignatureHelpProtocol.MAX_PARAMETERS || !active(signature["activeParameter"], parameters.size)) return JsonNull
            fun boundary(offset: Int): Boolean = offset in 0..label.length &&
                (offset == 0 || offset == label.length || !label[offset - 1].isHighSurrogate() || !label[offset].isLowSurrogate())
            for (parameterRaw in parameters) {
                val parameter = parameterRaw as? JsonObject ?: return JsonNull
                if (!documentation(parameter["documentation"])) return JsonNull
                when (val span = parameter["label"]) {
                    is JsonPrimitive -> if (!span.isString || !validText(span.content) || span.content.isEmpty() ||
                        span.content.length > label.length || !label.contains(span.content)) return JsonNull
                    is JsonArray -> {
                        if (span.size != 2) return JsonNull
                        val start = number(span[0]) ?: return JsonNull
                        val end = number(span[1]) ?: return JsonNull
                        if (start > end || !boundary(start) || !boundary(end)) return JsonNull
                    }
                    else -> return JsonNull
                }
            }
        }
        if (!active(root["activeSignature"], signatures.size)) return JsonNull
        val selected = (signatures[number(root["activeSignature"]) ?: 0] as JsonObject)["parameters"] as? JsonArray
        if (!active(root["activeParameter"], selected?.size ?: 0)) return JsonNull
        return value
    }

    fun diagnosticReport(value: JsonElement?, text: String): LspDiagnosticsProtocol.PullReport.Full? {
        val report = value as? JsonObject ?: return null
        if ((report["kind"] as? JsonPrimitive)?.content != "full") return null
        val raw = report["items"] as? JsonArray ?: return null
        if (raw.size > 500 || raw.any { !validRange(text, (it as? JsonObject)?.get("range")) }) return null
        val items = LspDiagnosticsProtocol.parseItems(raw, retainCode = true) ?: return null
        return LspDiagnosticsProtocol.PullReport.Full(items, null)
    }

    fun diagnosticComplete(outcomes: JsonObject): Boolean = outcomes.keys == setOf("vue", "ts-syntactic", "ts-semantic", "ts-suggestion") &&
        outcomes.values.all { ((it as? JsonObject)?.get("status") as? JsonPrimitive)?.content == "ok" }
}

/** Optional client-authored activation/display ticket, never trusted from JSON. */
class LspHeldResultGuard(val isCurrent: () -> Boolean) {
    fun accepts(): Boolean = runCatching(isCurrent).getOrDefault(false)
}

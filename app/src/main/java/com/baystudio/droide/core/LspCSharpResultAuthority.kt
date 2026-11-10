package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object LspCSharpResultAuthority {
    fun validText(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (c == '\u0000' || c == '\r' && text.getOrNull(index) != '\n') return false
            if (c.isHighSurrogate()) { if (index == text.length || !text[index++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }; return true
    }
    fun validPosition(text: String, line: Int, column: Int): Boolean = LspNimResultAuthority.validPosition(text, line, column)
    fun validRange(text: String, range: JsonElement?): Boolean = LspNimResultAuthority.validRange(text, range)
    fun coordinate(raw: JsonElement?): Int = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0 until Int.MAX_VALUE } ?: error("Invalid C# UTF-16 position")
    fun range(raw: JsonElement?, text: String, newText: String = ""): TextRangeEdit {
        require(validRange(text, raw) && validText(newText)) { "C# result range is outside its captured UTF-16 text" }
        val range = raw as JsonObject; val start = range["start"] as JsonObject; val end = range["end"] as JsonObject
        return TextRangeEdit(coordinate(start["line"]) + 1, coordinate(start["character"]) + 1,
            coordinate(end["line"]) + 1, coordinate(end["character"]) + 1, newText)
    }
    fun uriPath(uri: String, root: String, texts: Map<String, String>): String {
        val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: error("Malformed C# source URI")
        require(parsed.scheme == "file" && parsed.rawAuthority.isNullOrEmpty() && parsed.query == null && parsed.fragment == null &&
            parsed.path != null && !parsed.path.contains('\\') && parsed.path.split('/').none { it == ".." }) { "C# result is not a local source URI" }
        val file = java.io.File(parsed.path).absoluteFile.normalize()
        require(CSharpAnalysisSelections.contains(root, file.path)) { "C# result escaped the selected analysis directory" }
        require(file.path in texts) { "Open the C# navigation or edit target and request again" }
        return file.path
    }
    fun locations(value: JsonElement, root: String, texts: Map<String, String>, relative: (String) -> String,
        guard: LspHeldResultGuard): List<LspLocation> {
        if (value == JsonNull) return emptyList()
        val items = if (value is JsonArray) value else JsonArray(listOf(value))
        require(items.size <= 1000)
        return items.map { raw ->
            val o = raw as? JsonObject ?: error("Malformed C# navigation result")
            val uri = ((o["uri"] ?: o["targetUri"]) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing C# target URI")
            val path = uriPath(uri, root, texts)
            val range = range(o["range"] ?: o["targetSelectionRange"], texts.getValue(path))
            LspLocation(relative(path), range.startLine, range.startColumn, guard)
        }
    }
    fun edits(value: JsonElement, currentPath: String?, root: String, texts: Map<String, String>, versions: Map<String, Int>,
        relative: (String) -> String, guard: () -> Boolean): LspWorkspaceEdit? {
        if (value == JsonNull) return null
        val edits = mutableListOf<LspTextEdit>()
        fun add(path: String, raw: JsonElement) {
            require(path in texts)
            val array = raw as? JsonArray ?: error("Malformed C# edit list")
            require(array.size <= 1000 && edits.size + array.size <= 1000)
            array.forEach { element ->
                val o = element as? JsonObject ?: error("Malformed C# text edit")
                require(o.keys.all { it in setOf("range", "newText") }) { "Annotated C# edits are unsupported" }
                val insertion = (o["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("C# edit text missing")
                require(insertion.length <= 1_048_576)
                edits += LspTextEdit(relative(path), range(o["range"], texts.getValue(path), insertion))
            }
        }
        if (currentPath != null) add(currentPath, value) else {
            val o = value as? JsonObject ?: error("Malformed C# workspace edit")
            require(o.keys.all { it in setOf("changes", "documentChanges") } && !(o.containsKey("changes") && o.containsKey("documentChanges"))) { "Mixed or resource C# edits are unsupported" }
            (o["changes"] as? JsonObject)?.forEach { (uri, raw) -> add(uriPath(uri, root, texts), raw) }
            o["documentChanges"]?.let { changes ->
                (changes as? JsonArray ?: error("Malformed C# document changes")).forEach { raw ->
                    val edit = raw as? JsonObject ?: error("Malformed C# document edit")
                    require(edit.keys == setOf("textDocument", "edits")) { "C# resource edits are unsupported" }
                    val doc = edit["textDocument"] as? JsonObject ?: error("C# text document missing")
                    val uri = (doc["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("C# URI missing")
                    val path = uriPath(uri, root, texts)
                    doc["version"]?.takeIf { it != JsonNull }?.let { require(coordinate(it) == versions[path]) { "C# edit version changed" } }
                    add(path, requireNotNull(edit["edits"]))
                }
            }
        }
        edits.groupBy { it.path }.forEach { (_, group) ->
            val sorted = group.sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn }, { it.range.endLine }, { it.range.endColumn }))
            sorted.zipWithNext().forEach { (a, b) -> require(a.range.endLine < b.range.startLine ||
                a.range.endLine == b.range.startLine && a.range.endColumn <= b.range.startColumn &&
                !(a.range.startLine == b.range.startLine && a.range.startColumn == b.range.startColumn)) { "Overlapping C# edits" } }
        }
        val paths = edits.map { it.path }.toSet()
        return LspWorkspaceEdit(edits, texts.mapKeys { relative(it.key) }.filterKeys { it in paths },
            versions.mapKeys { relative(it.key) }.filterKeys { it in paths }, guard)
    }

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

}

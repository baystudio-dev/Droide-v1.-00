package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Native result validation against one original capture; never obtains newer editor state. */
internal object LspTomlResultAuthority {
    fun coordinate(raw: JsonElement?): Int = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?.takeIf { it in 0 until Int.MAX_VALUE } ?: error("Invalid TOML UTF-16 coordinate")
    fun range(raw: JsonElement?, text: String, newText: String = ""): TextRangeEdit {
        val value = raw as? JsonObject ?: error("TOML range missing")
        require(value.keys == setOf("start", "end"))
        val start = value["start"] as? JsonObject ?: error("TOML range start missing")
        val end = value["end"] as? JsonObject ?: error("TOML range end missing")
        require(start.keys == setOf("line", "character") && end.keys == start.keys)
        val sl = coordinate(start["line"]); val sc = coordinate(start["character"])
        val el = coordinate(end["line"]); val ec = coordinate(end["character"])
        require((el > sl || el == sl && ec >= sc) && LspTomlPositionAdmission.outgoing(text, sl, sc) &&
            LspTomlPositionAdmission.outgoing(text, el, ec) && LspTomlPositionAdmission.validText(newText)) { "TOML range is outside captured scalar boundaries" }
        return TextRangeEdit(sl + 1, sc + 1, el + 1, ec + 1, newText)
    }
    fun renameSpelling(name: String): Boolean {
        if (name.isEmpty() || name.length > 256 || !LspTomlPositionAdmission.validText(name)) return false
        if (name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) return true
        if (name.length < 2 || name.first() != name.last() || name.first() !in "\"'") return false
        val quote = name.first()
        return name.substring(1, name.length - 1).none { it == quote || it.isISOControl() || quote == '"' && it == '\\' }
    }
    fun prepareRename(value: JsonElement, text: String): TextRangeEdit {
        require(value != JsonNull) { "Native Taplo did not prepare this key for rename" }
        val obj = value as? JsonObject ?: error("Malformed native prepareRename")
        val nativeRange = if (obj.containsKey("range")) {
            require(obj.keys.all { it in setOf("range", "placeholder") })
            obj["placeholder"]?.let { require(it is JsonPrimitive && it.isString && LspTomlPositionAdmission.validText(it.content)) }
            obj["range"]
        } else value
        return range(nativeRange, text)
    }
    fun edits(value: JsonElement, formatting: Boolean, uri: String, relativePath: String, text: String, version: Int,
        guard: () -> Boolean): LspWorkspaceEdit? {
        if (value == JsonNull) return null
        val native = mutableListOf<JsonElement>()
        fun append(raw: JsonElement?) {
            val list = raw as? JsonArray ?: error("Malformed TOML edit list")
            require(native.size + list.size <= 1000); native.addAll(list)
        }
        if (formatting) append(value) else {
            val obj = value as? JsonObject ?: error("Malformed TOML workspace edit")
            require(obj.keys.all { it in setOf("changes", "documentChanges") } && !(obj.containsKey("changes") && obj.containsKey("documentChanges"))) { "Unsupported TOML resource or mixed edits" }
            obj["changes"]?.let { raw ->
                val changes = raw as? JsonObject ?: error("Malformed TOML changes")
                require(changes.keys.all { it == uri }) { "TOML rename is current-document-only" }
                changes.values.forEach(::append)
            }
            obj["documentChanges"]?.let { raw ->
                val changes = raw as? JsonArray ?: error("Malformed TOML document changes")
                require(changes.size <= 1) { "TOML rename is current-document-only" }
                changes.forEach { item ->
                    val edit = item as? JsonObject ?: error("Malformed TOML document edit")
                    require(edit.keys == setOf("textDocument", "edits")) { "TOML resource operations are unavailable" }
                    val doc = edit["textDocument"] as? JsonObject ?: error("Missing TOML document")
                    require(doc.keys.all { it in setOf("uri", "version") } && doc["uri"] == JsonPrimitive(uri))
                    doc["version"]?.takeIf { it != JsonNull }?.let { require(coordinate(it) == version) { "TOML edit version changed" } }
                    append(edit["edits"])
                }
            }
        }
        var total = 0L
        val edits = native.map { item ->
            val obj = item as? JsonObject ?: error("Malformed TOML edit")
            require(obj.keys == setOf("range", "newText")) { "TOML annotated edits are unavailable" }
            val insertion = (obj["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("TOML edit text missing")
            total += insertion.length
            require(total <= LspTomlPositionAdmission.MAX_TEXT_UNITS)
            LspTextEdit(relativePath, range(obj["range"], text, insertion))
        }
        val ordered = edits.sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn }, { it.range.endLine }, { it.range.endColumn }))
        ordered.zipWithNext().forEach { (first, second) ->
            val a = first.range; val b = second.range
            require((a.endLine < b.startLine || a.endLine == b.startLine && a.endColumn <= b.startColumn) &&
                !(a.startLine == b.startLine && a.startColumn == b.startColumn)) { "Overlapping native TOML edits" }
        }
        return LspWorkspaceEdit(edits, mapOf(relativePath to text), mapOf(relativePath to version), guard)
    }
    fun hover(value: JsonElement, text: String): String {
        if (value == JsonNull) return "No native hover information."
        val obj = value as? JsonObject ?: error("Malformed TOML hover")
        obj["range"]?.let { range(it, text) }
        fun content(raw: JsonElement?, depth: Int = 0): String {
            require(depth <= 4)
            return when (raw) {
                null, JsonNull -> ""
                is JsonPrimitive -> { require(raw.isString); raw.content }
                is JsonArray -> { require(raw.size <= 32); raw.joinToString("\n") { content(it, depth + 1) } }
                is JsonObject -> { require(raw["value"] is JsonPrimitive); content(raw["value"], depth + 1) }
            }
        }
        val message = content(obj["contents"])
        require(message.length <= 100_000 && LspTomlPositionAdmission.validText(message))
        return message.ifBlank { "No native hover information." }
    }
    fun symbols(value: JsonElement, text: String, uri: String, path: String, guard: LspHeldResultGuard): List<LspSymbol> {
        if (value == JsonNull) return emptyList()
        val out = mutableListOf<LspSymbol>()
        fun visit(raw: JsonElement, depth: Int) {
            require(depth <= 32 && out.size < 1000)
            val obj = raw as? JsonObject ?: error("Malformed TOML symbol")
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 1000 && LspTomlPositionAdmission.validText(it.content) }?.content ?: error("TOML symbol name missing")
            val location = obj["location"] as? JsonObject
            if (location != null) require(location["uri"] == JsonPrimitive(uri))
            obj["range"]?.let { range(it, text) }
            val point = range(location?.get("range") ?: obj["selectionRange"] ?: obj["range"], text)
            out += LspSymbol(name, (obj["kind"] as? JsonPrimitive)?.intOrNull, path, point.startLine, point.startColumn, depth, guard)
            obj["children"]?.let { (it as? JsonArray ?: error("Malformed TOML child symbols")).forEach { item -> visit(item, depth + 1) } }
        }
        (value as? JsonArray ?: error("Malformed TOML symbol list")).forEach { visit(it, 0) }
        return out
    }
    fun semantic(value: JsonElement, text: String, legend: LspSemanticTokenLegend): List<LspSemanticToken> {
        val obj = value as? JsonObject ?: error("TOML semantic result unavailable")
        val data = obj["data"] as? JsonArray ?: error("TOML semantic data missing")
        require(data.size % 5 == 0 && data.size / 5 <= LspSemanticTokenDecoder.MAX_TOKENS)
        require(data.all { it is JsonPrimitive && !it.isString && it.longOrNull != null })
        val tokens = LspSemanticTokensProtocol.parseFull(value, legend)
        require(tokens.size == data.size / 5) { "Unsupported TOML semantic token data" }
        tokens.forEach { token -> require(token.type in setOf("tomlArrayKey", "tomlTableKey") && token.startCharacter <= Int.MAX_VALUE - token.length &&
            LspTomlPositionAdmission.outgoing(text, token.line, token.startCharacter) && LspTomlPositionAdmission.outgoing(text, token.line, token.startCharacter + token.length)) }
        return tokens
    }
    fun diagnostics(params: JsonElement?, uri: String, text: String): List<LspDiagnostic> {
        val obj = params as? JsonObject ?: error("Malformed TOML diagnostic push")
        require(obj["uri"] == JsonPrimitive(uri) && (obj["version"] == null || obj["version"] == JsonNull)) { "Taplo diagnostics must retain their unversioned provenance" }
        val data = obj["diagnostics"] as? JsonArray ?: error("TOML diagnostics missing")
        require(data.size <= 1000)
        return data.map { raw ->
            val item = raw as? JsonObject ?: error("Malformed TOML diagnostic")
            val at = range(item["range"], text)
            val message = (item["message"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 10_000 && LspTomlPositionAdmission.validText(it.content) }?.content ?: error("Invalid TOML diagnostic message")
            val severity = item["severity"]?.let { coordinate(it).also { n -> require(n in 1..4) } }
            LspDiagnostic(at.startLine, at.startColumn, at.endLine, at.endColumn, severity, message,
                "Taplo: " + LspTomlProfile.DIAGNOSTIC_QUALIFICATION, item["code"] as? JsonPrimitive)
        }
    }
}

package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal class ElpDiagnosticPublication(val owner: Any, val serial: Long,
    private val admit: ((Map<String, List<LspDiagnostic>>) -> Unit) -> Boolean) {
    fun publishIfCurrent(publish: (Map<String, List<LspDiagnostic>>) -> Unit): Boolean = admit(publish)
}

internal object LspElpResultAuthority {
    fun integer(value: JsonElement?): Int = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?.takeIf { it in 0 until Int.MAX_VALUE } ?: error("Invalid ELP coordinate/integer")
    fun string(value: JsonElement?, max: Int = 20_000): String = (value as? JsonPrimitive)?.takeIf {
        it.isString && it.content.length <= max && validText(it.content)
    }?.content ?: error("Invalid ELP text")
    fun validText(text: String): Boolean {
        var at = 0
        while (at < text.length) { val c = text[at++]; if (c.isHighSurrogate()) {
            if (at == text.length || !text[at++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false }
        return true
    }
    fun offset(text: String, line: Int, column: Int): Int? {
        if (line < 0 || column < 0 || !validText(text)) return null
        var start = 0; var row = 0
        while (row < line) { val next = text.indexOf('\n', start); if (next < 0) return null; start = next + 1; row++ }
        val lf = text.indexOf('\n', start); var end = if (lf < 0) text.length else lf
        if (lf >= 0 && end > start && text[end - 1] == '\r') end--
        if (column > end - start) return null
        val at = start + column
        if (at > 0 && at < text.length && text[at - 1].isHighSurrogate() && text[at].isLowSurrogate()) return null
        return at
    }
    fun range(value: JsonElement?, source: String): TextRangeEdit {
        val root = value as? JsonObject ?: error("ELP range missing")
        fun point(value: JsonElement?): Pair<Int, Int> { val p = value as? JsonObject ?: error("ELP position missing"); return integer(p["line"]) to integer(p["character"]) }
        val (sl, sc) = point(root["start"]); val (el, ec) = point(root["end"])
        val begin = requireNotNull(offset(source, sl, sc)); val end = requireNotNull(offset(source, el, ec)); require(begin <= end)
        return TextRangeEdit(sl + 1, sc + 1, el + 1, ec + 1, "")
    }
    fun rangeJson(edit: TextRangeEdit): JsonObject = buildJsonObject {
        put("start", buildJsonObject { put("line", edit.startLine - 1); put("character", edit.startColumn - 1) })
        put("end", buildJsonObject { put("line", edit.endLine - 1); put("character", edit.endColumn - 1) })
    }
    fun hover(value: JsonElement, text: String): String {
        if (value == JsonNull) return "No native hover information."
        val root = value as? JsonObject ?: error("Malformed ELP hover")
        root["range"]?.let { range(it, text) }
        fun markup(v: JsonElement?): String = when (v) {
            is JsonPrimitive -> string(v, 100_000)
            is JsonObject -> string(v["value"], 100_000)
            is JsonArray -> { require(v.size <= 64); v.joinToString("\n") { markup(it) } }
            else -> error("Malformed ELP hover contents")
        }
        return markup(root["contents"]).also { require(it.length <= 100_000) }
    }
    /** Native signature indices and UTF-16 labels are admitted before the generic renderer can clamp. */
    fun signature(value: JsonElement): LspSignatureHelp? {
        if (value == JsonNull) return null
        val root = value as? JsonObject ?: error("Malformed ELP signature help")
        val signatures = root["signatures"] as? JsonArray ?: error("Missing ELP signatures")
        require(signatures.size <= LspSignatureHelpProtocol.MAX_SIGNATURES)
        if (signatures.isEmpty()) return null
        fun documentation(raw: JsonElement?) {
            if (raw == null || raw == JsonNull) return
            if (raw is JsonObject) { require(raw["kind"] in setOf(JsonPrimitive("markdown"), JsonPrimitive("plaintext"))); string(raw["value"], LspSignatureHelpProtocol.MAX_DOCUMENTATION_CHARS) }
            else string(raw, LspSignatureHelpProtocol.MAX_DOCUMENTATION_CHARS)
        }
        fun boundary(label: String, at: Int) = at in 0..label.length && !(at > 0 && at < label.length && label[at - 1].isHighSurrogate() && label[at].isLowSurrogate())
        val counts = signatures.map { raw ->
            val item = raw as? JsonObject ?: error("Malformed ELP signature")
            val label = string(item["label"], LspSignatureHelpProtocol.MAX_LABEL_CHARS); require(label.isNotBlank()); documentation(item["documentation"])
            val parameters = item["parameters"]?.takeUnless { it == JsonNull }?.let { it as? JsonArray ?: error("Malformed ELP parameters") } ?: JsonArray(emptyList())
            require(parameters.size <= LspSignatureHelpProtocol.MAX_PARAMETERS)
            parameters.forEach { p ->
                val parameter = p as? JsonObject ?: error("Malformed ELP parameter"); documentation(parameter["documentation"])
                when (val part = parameter["label"]) {
                    is JsonPrimitive -> require(string(part, 500).isNotEmpty())
                    is JsonArray -> { require(part.size == 2); val start = integer(part[0]); val end = integer(part[1]); require(start <= end && end - start <= 500 && boundary(label, start) && boundary(label, end)) }
                    else -> error("Missing ELP parameter label")
                }
            }
            item["activeParameter"]?.takeUnless { it == JsonNull }?.let { require(integer(it) in parameters.indices) }
            parameters.size
        }
        val active = root["activeSignature"]?.takeUnless { it == JsonNull }?.let(::integer) ?: 0
        require(active in signatures.indices)
        root["activeParameter"]?.takeUnless { it == JsonNull }?.let { require(integer(it) < counts[active] || counts[active] == 0 && integer(it) == 0) }
        return LspSignatureHelpProtocol.parse(value)
    }
    fun inlay(value: JsonElement, text: String, guard: LspHeldResultGuard): List<LspInlayHint> {
        if (value == JsonNull) return emptyList()
        val items = value as? JsonArray ?: error("Malformed ELP inlay list"); require(items.size <= 400)
        return items.map { raw ->
            val item = raw as? JsonObject ?: error("Malformed ELP inlay")
            val point = item["position"] as? JsonObject ?: error("Missing inlay position")
            val line = integer(point["line"]); val col = integer(point["character"]); requireNotNull(offset(text, line, col))
            require(item["textEdits"] == null && item["command"] == null) { "Executable inlay hints are unavailable" }
            val label = when (val label = item["label"]) {
                is JsonPrimitive -> string(label, 512)
                is JsonArray -> { require(label.size <= 32); label.joinToString("") {
                    val part = it as? JsonObject ?: error("Invalid inlay label"); require(part["command"] == null && part["location"] == null); string(part["value"], 512)
                } }
                else -> error("Invalid inlay label")
            }
            require(label.isNotEmpty() && label.length <= 512)
            LspInlayHint(line + 1, col + 1, label, item["kind"]?.let(::integer), item["paddingLeft"] == JsonPrimitive(true), item["paddingRight"] == JsonPrimitive(true), guard)
        }
    }
    fun semantic(value: JsonElement, text: String, legend: LspSemanticTokenLegend): List<LspSemanticToken> {
        if (value == JsonNull) return emptyList()
        val root = value as? JsonObject ?: error("Malformed ELP semantic result")
        val data = root["data"] as? JsonArray ?: error("Missing ELP semantic data")
        require(data.size % 5 == 0 && data.size <= LspSemanticTokenDecoder.MAX_TOKENS * 5)
        val numbers = data.map { integer(it).toLong() }
        val tokens = LspSemanticTokenDecoder.decode(numbers, legend); require(tokens.size * 5 == data.size)
        tokens.forEach { require(it.startCharacter <= Int.MAX_VALUE - it.length); requireNotNull(offset(text, it.line, it.startCharacter)); requireNotNull(offset(text, it.line, it.startCharacter + it.length)) }
        return tokens
    }
    /** Uncaptured targets are omitted, never remapped into the workspace. */
    fun locations(value: JsonElement, root: String, sources: Map<String, String>, guard: LspHeldResultGuard): List<LspLocation> {
        if (value == JsonNull) return emptyList()
        val items = if (value is JsonArray) value else JsonArray(listOf(value)); require(items.size <= 2000)
        return items.mapNotNull { raw ->
            val item = raw as? JsonObject ?: error("Malformed ELP location")
            val uri = string(item["uri"] ?: item["targetUri"], 16_384)
            val path = runCatching { LspElpProfile.ownedPath(uri, root) }.getOrNull() ?: return@mapNotNull null
            val text = sources[path] ?: return@mapNotNull null
            val position = range(item["range"] ?: item["targetSelectionRange"], text)
            item["targetRange"]?.let { range(it, text) }
            LspLocation(path.removePrefix(root.trimEnd('/') + "/"), position.startLine, position.startColumn, guard)
        }
    }
    fun symbols(value: JsonElement, root: String, primary: String, sources: Map<String, String>, guard: LspHeldResultGuard): List<LspSymbol> {
        if (value == JsonNull) return emptyList()
        val items = value as? JsonArray ?: error("Malformed ELP symbols"); val out = mutableListOf<LspSymbol>()
        fun visit(raw: JsonElement, depth: Int) {
            require(depth <= 32 && out.size < 2000)
            val item = raw as? JsonObject ?: error("Malformed ELP symbol")
            val location = item["location"] as? JsonObject
            val path = if (location == null) primary else runCatching { LspElpProfile.ownedPath(string(location["uri"], 16_384), root) }.getOrNull() ?: return
            val text = sources[path] ?: return
            val range = range(if (location == null) item["selectionRange"] ?: item["range"] else location["range"], text)
            item["range"]?.let { range(it, text) }
            out += LspSymbol(string(item["name"]), item["kind"]?.let(::integer), path.removePrefix(root.trimEnd('/') + "/"), range.startLine, range.startColumn, depth, guard)
            item["children"]?.let { children -> (children as? JsonArray ?: error("Malformed symbol children")).forEach { visit(it, depth + 1) } }
        }
        require(items.size <= 2000); items.forEach { visit(it, 0) }; return out
    }
    fun workspaceEdit(value: JsonElement, root: String, sources: Map<String, String>, versions: Map<String, Int>, guard: LspHeldResultGuard): LspWorkspaceEdit {
        val obj = value as? JsonObject ?: error("Malformed ELP edit proposal")
        require(obj.keys.all { it in setOf("changes", "documentChanges") }) { "Resource operations/annotations unavailable" }
        require(!(obj.containsKey("changes") && obj.containsKey("documentChanges")))
        val edits = mutableListOf<LspTextEdit>(); val used = linkedMapOf<String, String>(); val expectedVersions = linkedMapOf<String, Int>()
        fun append(uri: String, raw: JsonElement?, version: JsonElement? = null) {
            val path = LspElpProfile.ownedPath(uri, root); val text = sources[path] ?: error("Open the target $path and request the edit again")
            val expected = requireNotNull(versions[path]); if (version != null && version != JsonNull) require(integer(version) == expected)
            val items = raw as? JsonArray ?: error("Malformed ELP text edits"); require(items.size <= 2000)
            val relative = path.removePrefix(root.trimEnd('/') + "/"); used[relative] = text; expectedVersions[relative] = expected
            items.forEach { element -> val edit = element as? JsonObject ?: error("Malformed ELP edit")
                require(edit.keys == setOf("range", "newText")); edits += LspTextEdit(relative, range(edit["range"], text).copy(newText = string(edit["newText"], 1_048_576))) }
        }
        (obj["changes"] as? JsonObject)?.forEach { (uri, raw) -> append(uri, raw) }
        if (obj.containsKey("changes")) require(obj["changes"] is JsonObject)
        if (obj.containsKey("documentChanges")) (obj["documentChanges"] as? JsonArray ?: error("Malformed document changes")).forEach { raw ->
            val change = raw as? JsonObject ?: error("Malformed document edit"); require(change.keys == setOf("textDocument", "edits"))
            val doc = change["textDocument"] as? JsonObject ?: error("Missing edit document"); append(string(doc["uri"], 16_384), change["edits"], doc["version"])
        }
        require(used.size <= 100 && edits.size <= 2000 && edits.sumOf { it.range.newText.toByteArray(Charsets.UTF_8).size.toLong() } <= 1_048_576)
        edits.groupBy { it.path }.forEach { (path, group) ->
            val text = requireNotNull(used[path]); val offsets = group.map { val r = it.range; requireNotNull(offset(text, r.startLine - 1, r.startColumn - 1)) to requireNotNull(offset(text, r.endLine - 1, r.endColumn - 1)) }.sortedBy { it.first }
            offsets.zipWithNext().forEach { (a, b) -> require(a.first != b.first && a.second <= b.first) { "Overlapping ELP edits" } }
        }
        return LspWorkspaceEdit(edits, used, expectedVersions, guard::accepts)
    }
    fun diagnostics(raw: JsonElement?, text: String, guard: LspHeldResultGuard): List<LspDiagnostic> {
        val array = raw as? JsonArray ?: error("Malformed ELP diagnostic list"); require(array.size <= 1000)
        return array.map { element ->
            val obj = element as? JsonObject ?: error("Malformed ELP diagnostic"); val r = range(obj["range"], text)
            val code = obj["code"]?.let { require(it is JsonPrimitive && (it.isString && it.content.length <= 256 || !it.isString && it.intOrNull != null)); it as JsonPrimitive }
            val severity = obj["severity"]?.let(::integer); require(severity == null || severity in 1..4)
            LspDiagnostic(r.startLine, r.startColumn, r.endLine, r.endColumn, severity, string(obj["message"]), obj["source"]?.let { string(it, 256) }, code, guard, LspElpProfile.REPORT_NOTE)
        }
    }
    fun diagnosticJson(d: LspDiagnostic): JsonObject = buildJsonObject {
        put("range", rangeJson(TextRangeEdit(d.line, d.column, d.endLine, d.endColumn, ""))); put("message", d.message)
        d.source?.let { put("source", it) }; d.code?.let { put("code", it) }; d.severity?.let { put("severity", it) }
    }
}

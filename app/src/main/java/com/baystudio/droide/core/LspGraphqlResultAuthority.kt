package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal class GraphqlDiagnosticPublication(
    val owner: Any,
    val serial: Long,
    private val admit: ((Map<String, List<LspDiagnostic>>) -> Unit) -> Boolean,
) {
    fun publishIfCurrent(publish: (Map<String, List<LspDiagnostic>>) -> Unit): Boolean = admit(publish)
}

internal object LspGraphqlResultAuthority {
    fun integer(value: JsonElement?): Int = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 } ?: error("Invalid GraphQL integer")
    fun text(value: JsonElement?, limit: Int = 20_000): String = (value as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= limit && LspGraphqlPositionCodec.validText(it.content) }?.content ?: error("Invalid native GraphQL text")
    fun range(value: JsonElement?, source: String): TextRangeEdit {
        val obj = value as? JsonObject ?: error("GraphQL range missing"); require(obj.keys == setOf("start", "end"))
        fun point(raw: JsonElement?): Pair<Int, Int> {
            val point = raw as? JsonObject ?: error("GraphQL point missing"); require(point.keys == setOf("line", "character"))
            return integer(point["line"]) to integer(point["character"])
        }
        val (sl, sc) = point(obj["start"]); val (el, ec) = point(obj["end"])
        val start = requireNotNull(LspGraphqlPositionCodec.offset(source, sl, sc)); val end = requireNotNull(LspGraphqlPositionCodec.offset(source, el, ec)); require(start <= end)
        return TextRangeEdit(sl + 1, sc + 1, el + 1, ec + 1, "")
    }
    fun hover(value: JsonElement): String {
        if (value == JsonNull) return "No native hover information."
        val obj = value as? JsonObject ?: error("Malformed GraphQL hover"); require(obj.keys == setOf("contents"))
        val contents = obj["contents"]
        val result = if (contents is JsonObject) { require(contents.keys == setOf("kind", "value") && contents["kind"] == JsonPrimitive("plaintext")); text(contents["value"], 100_000) } else text(contents, 100_000)
        return result.ifEmpty { "No native hover information." }
    }
    fun symbols(value: JsonElement, source: String, uri: String, path: String, guard: LspHeldResultGuard): List<LspSymbol> {
        require(!LspGraphqlPositionCodec.hasBareCr(source)) { "Bare-CR native GraphQL outline is unavailable" }
        if (value == JsonNull) return emptyList()
        val items = value as? JsonArray ?: error("GraphQL symbol array missing"); require(items.size <= 1000)
        return items.map { raw ->
            val item = raw as? JsonObject ?: error("GraphQL symbol malformed")
            require(item.keys.all { it in setOf("name", "kind", "location", "containerName") })
            val name = text(item["name"]); require(name.isNotEmpty())
            val kind = integer(item["kind"]); require(kind in setOf(8, 12, 13, 23, 11, 10, 22))
            item["containerName"]?.let { text(it) }
            val location = item["location"] as? JsonObject ?: error("GraphQL symbol location missing")
            require(location.keys == setOf("uri", "range") && location["uri"] == JsonPrimitive(uri))
            val position = range(location["range"], source)
            LspSymbol(name, kind, path, position.startLine, position.startColumn, activationGuard = guard)
        }
    }
    data class Source(val text: String, val openGeneration: Long?, val version: Long?, val digest: String, val origin: String = "editor")
    fun diagnostics(value: JsonElement, uri: String, sources: Map<String, Source>, snapshotGeneration: String): Map<String, List<LspDiagnostic>> {
        val root = value as? JsonObject ?: error("GraphQL diagnostic report missing")
        val reports = linkedMapOf(uri to root)
        root["relatedDocuments"]?.let { related ->
            val relatedObject = related as? JsonObject ?: error("Malformed related GraphQL diagnostic map")
            relatedObject.forEach { (key, raw) ->
            require(key != uri && key in sources); reports[key] = raw as? JsonObject ?: error("Malformed related GraphQL diagnostics")
            }
        }
        var count = 0
        return reports.mapValues { (target, report) ->
            val allowed = if (target == uri) setOf("kind", "items", "relatedDocuments", "sourceAuthority") else setOf("kind", "items", "sourceAuthority")
            require(report.keys.all { it in allowed }) { "Nested GraphQL related reports are unavailable" }
            require(report["kind"] == JsonPrimitive("full")) { "Only full GraphQL diagnostics are accepted" }
            val source = sources[target] ?: error("GraphQL diagnostic source was not captured")
            require(!LspGraphqlPositionCodec.hasBareCr(source.text)) { "Bare-CR GraphQL diagnostic UI mapping remains unavailable" }
            run {
                val authority = report["sourceAuthority"] as? JsonObject ?: error("GraphQL diagnostic source authority missing")
                require(authority.keys == setOf("uri", "textDigest", "origin", "openGeneration", "version", "snapshotGeneration"))
                require(authority["uri"] == JsonPrimitive(target) && authority["origin"] == JsonPrimitive(source.origin) && authority["snapshotGeneration"] == JsonPrimitive(snapshotGeneration))
                require(authority["textDigest"] == JsonPrimitive(source.digest))
                require(authority["openGeneration"] == (source.openGeneration?.let(::JsonPrimitive) ?: JsonNull))
                require(authority["version"] == (source.version?.let(::JsonPrimitive) ?: JsonNull))
            }
            val items = report["items"] as? JsonArray ?: error("GraphQL diagnostic items missing")
            count += items.size; require(count <= 1000)
            items.map { raw ->
                val item = raw as? JsonObject ?: error("Malformed GraphQL diagnostic")
                val point = range(item["range"], source.text); val message = text(item["message"])
                val severity = integer(item["severity"]); require(severity in 1..4)
                val category = item["source"]?.let { text(it) } ?: "GraphQL"
                LspDiagnostic(point.startLine, point.startColumn, point.endLine, point.endColumn, severity, message, category, item["code"] as? JsonPrimitive)
            }
        }
    }
}

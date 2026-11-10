package com.baystudio.droide.core

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Inert root-only configuration selection. This parser never loads GraphQL configuration code. */
internal object LspGraphqlConfiguration {
    const val CONFIG = ".graphqlrc.json"
    const val CONFIG_BYTES = 65_536
    const val PACKAGE_BYTES = 262_144
    data class Selection(val schema: List<String>, val fragments: List<String>)
    val competitors = listOf("graphql.config.ts", "graphql.config.cts", "graphql.config.mts", "graphql.config.js", "graphql.config.cjs", "graphql.config.mjs", "graphql.config.json", "graphql.config.yaml", "graphql.config.yml", "graphql.config.toml", ".graphqlrc", ".graphqlrc.ts", ".graphqlrc.cts", ".graphqlrc.mts", ".graphqlrc.js", ".graphqlrc.cjs", ".graphqlrc.mjs", ".graphqlrc.yml", ".graphqlrc.yaml", ".graphqlrc.toml", ".graphqlconfig", ".graphqlconfig.json", ".graphqlconfig.yaml", ".graphqlconfig.yml")
    fun decode(bytes: ByteArray, max: Int): String {
        require(bytes.size <= max) { "GraphQL input exceeds $max bytes" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(!text.startsWith('\uFEFF') && LspGraphqlPositionCodec.validText(text)) { "GraphQL input must be strict UTF-8 without BOM" }
        return text
    }
    fun relativePath(path: String): String {
        require(path.isNotEmpty() && path.length <= 256 && LspGraphqlPositionCodec.validText(path))
        require(path.none { it.isISOControl() || it in "\\%:~$*?[]{}!" } && !path.startsWith('/')) { "Only exact root-relative local GraphQL paths are supported" }
        val parts = path.split('/')
        require(parts.size <= 32 && parts.none { it.isEmpty() || it == "." || it == ".." })
        require(LspGraphqlProfile.supportsPath(path)) { "GraphQL schema and fragments must use .graphql or .gql" }
        return path
    }
    fun selection(bytes: ByteArray): Selection {
        val obj = strictObject(decode(bytes, CONFIG_BYTES))
        require(obj.keys.all { it in setOf("schema", "documents") } && "schema" in obj) { "Only schema and optional documents are supported" }
        fun paths(raw: JsonElement?, maximum: Int, required: Boolean): List<String> {
            val values = when (raw) { null -> emptyList(); is JsonPrimitive -> listOf(raw); is JsonArray -> raw.toList(); else -> error("GraphQL paths must be strings or arrays") }
            require(values.size <= maximum && (!required || values.isNotEmpty()))
            val result = values.map { relativePath((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("GraphQL path is not text")) }
            require(result.distinct().size == result.size) { "Duplicate GraphQL path" }
            return result
        }
        val schema = paths(obj["schema"], 8, true); val fragments = paths(obj["documents"], 32, false)
        require(schema.none { it in fragments }) { "GraphQL schema and fragment roles must be disjoint" }
        return Selection(schema, fragments)
    }
    fun admitPackage(bytes: ByteArray) {
        require("graphql" !in strictObject(decode(bytes, PACKAGE_BYTES))) { "Root package.json contains competing GraphQL configuration" }
    }
    fun admitSource(text: String) {
        require(LspGraphqlPositionCodec.validText(text))
        require(!Regex("(?m)^[ \\t]*#[ \\t]*import(?:[ \\t]|$)").containsMatchIn(text)) { "GraphQL #import conventions are unavailable; select explicit fragment files" }
    }
    /** Duplicate keys are rejected before constructing JsonObject, including escaped aliases. */
    fun strictObject(text: String): JsonObject {
        require(!text.startsWith('\uFEFF') && LspGraphqlPositionCodec.validText(text))
        var at = 0; var nodes = 0
        fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        fun string(): JsonPrimitive {
            require(text.getOrNull(at) == '"'); val start = at++; var escaped = false
            while (at < text.length) {
                val c = text[at++]
                require(c.code >= 0x20)
                if (!escaped && c == '"') {
                    val value = Json.parseToJsonElement(text.substring(start, at)) as JsonPrimitive
                    require(value.isString && LspGraphqlPositionCodec.validText(value.content)); return value
                }
                if (!escaped && c == '\\') escaped = true else escaped = false
            }
            error("Unterminated JSON string")
        }
        fun value(depth: Int): JsonElement {
            require(depth <= 8 && ++nodes <= 65_536) { "GraphQL configuration JSON exceeds bounds" }; whitespace()
            return when (text.getOrNull(at)) {
                '"' -> string()
                '{' -> {
                    at++; whitespace(); val out = linkedMapOf<String, JsonElement>()
                    if (text.getOrNull(at) != '}') while (true) {
                        whitespace(); val key = string().content; require(key !in out) { "Duplicate JSON key: $key" }
                        whitespace(); require(text.getOrNull(at++) == ':'); out[key] = value(depth + 1); whitespace()
                        if (text.getOrNull(at) == '}') break
                        require(text.getOrNull(at++) == ',')
                    }
                    require(text.getOrNull(at++) == '}'); JsonObject(out)
                }
                '[' -> {
                    at++; whitespace(); val out = mutableListOf<JsonElement>()
                    if (text.getOrNull(at) != ']') while (true) {
                        out += value(depth + 1); whitespace(); if (text.getOrNull(at) == ']') break
                        require(text.getOrNull(at++) == ',')
                    }
                    require(text.getOrNull(at++) == ']'); JsonArray(out)
                }
                else -> {
                    val start = at; while (at < text.length && text[at] !in " \t\r\n,]}") at++
                    val raw = text.substring(start, at)
                    require(raw in setOf("true", "false", "null") || raw.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) { "Invalid strict JSON literal" }
                    if (raw !in setOf("true", "false", "null")) require(raw.toDoubleOrNull()?.isFinite() == true) { "Nonfinite JSON number" }
                    Json.parseToJsonElement(raw)
                }
            }
        }
        val result = value(0); whitespace(); require(at == text.length)
        return result as? JsonObject ?: error("GraphQL configuration must be a JSON object")
    }
}
